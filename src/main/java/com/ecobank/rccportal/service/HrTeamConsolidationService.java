package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;

/**
 * RCC est l'enceinte qui regroupe toutes les équipes : ce n'est PAS une équipe. Les comptes
 * rangés « RCC » ou sans équipe correspondent en général à une personne déjà présente dans
 * une vraie équipe (INBOUND, OUTBOUND…) sous un autre compte (autre identifiant, import roster).
 *
 * Ce service rapproche ces doublons (même nom — ordre des mots et accents ignorés — ou même
 * e-mail) et range chaque compte sans équipe dans l'équipe de son double. Aperçu d'abord,
 * application ensuite (RH / Admin) : seul le champ équipe (ACTIVITY) est renseigné, aucun
 * compte n'est supprimé.
 */
@Service
public class HrTeamConsolidationService {

    /** Libellés qui désignent l'enceinte RCC elle-même, jamais une équipe. */
    static final Set<String> UMBRELLA = Set.of("RCC", "RELATION CLIENT", "CENTRE DE RELATION CLIENT", "SANS EQUIPE", "AUCUNE", "NONE", "N/A", "NA", "-");

    public record Move(Long userId, String username, String fullName, String fromTeam, String toTeam, String matchedWith, String matchedBy) {}

    public record Report(int totalAccounts, int people, int duplicateAccounts, List<Move> moves, List<String> unmatched, boolean applied) {}

    private final UserRepository userRepository;

    public HrTeamConsolidationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Report preview() {
        return run(userRepository.findAll(), false);
    }

    @Transactional
    public Report apply() {
        List<User> users = userRepository.findAll();
        Report report = run(users, true);
        Map<Long, User> byId = new HashMap<>();
        users.forEach(u -> byId.put(u.getId(), u));
        for (Move m : report.moves()) {
            User u = byId.get(m.userId());
            if (u != null) {
                u.setActivity(m.toTeam());
                userRepository.save(u);
            }
        }
        return report;
    }

    Report run(List<User> users, boolean applied) {
        // Index des comptes qui ont une vraie équipe, par nom normalisé et par e-mail.
        Map<String, User> byName = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        Map<String, User> byEmail = new HashMap<>();
        for (User u : users) {
            if (!hasRealTeam(u)) continue;
            String nk = nameKey(u.getName());
            // Deux homonymes dans des équipes : on ne devine pas lequel est le bon.
            if (!nk.isEmpty() && byName.containsKey(nk) && !sameEmailOrTeam(byName.get(nk), u)) ambiguousNames.add(nk);
            if (!nk.isEmpty()) byName.merge(nk, u, HrTeamConsolidationService::preferred);
            String ek = emailKey(u.getEmail());
            if (!ek.isEmpty()) byEmail.merge(ek, u, HrTeamConsolidationService::preferred);
        }

        List<Move> moves = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (User u : users) {
            if (hasRealTeam(u)) continue;
            User twin = null;
            String by = null;
            String ek = emailKey(u.getEmail());
            if (!ek.isEmpty() && byEmail.containsKey(ek) && !byEmail.get(ek).getId().equals(u.getId())) { twin = byEmail.get(ek); by = "e-mail"; }
            String nk = nameKey(u.getName());
            if (twin == null && !nk.isEmpty() && byName.containsKey(nk) && !ambiguousNames.contains(nk)
                    && !byName.get(nk).getId().equals(u.getId())) { twin = byName.get(nk); by = "nom"; }
            if (twin != null) {
                moves.add(new Move(u.getId(), u.getUsername(), u.getName(), display(u.getActivity()), canonicalTeam(twin.getActivity()), twin.getUsername(), by));
            } else {
                unmatched.add((u.getName() != null ? u.getName() : u.getUsername()) + " (" + u.getUsername() + ")"
                        + (ambiguousNames.contains(nk) ? " — plusieurs homonymes, à affecter à la main" : ""));
            }
        }

        // Chaque compte rapproché est le double d'une personne déjà comptée dans une équipe.
        return new Report(users.size(), users.size() - moves.size(), moves.size(), moves, unmatched, applied);
    }

    // ── Règles ──────────────────────────────────────────────────────────

    static boolean hasRealTeam(User u) {
        return !isUmbrella(u.getActivity());
    }

    static boolean isUmbrella(String team) {
        if (team == null || team.isBlank()) return true;
        return UMBRELLA.contains(fold(team).replace('_', ' ').trim());
    }

    /** Libellé d'équipe canonique : « inbound_voice » → « INBOUND VOICE ». */
    static String canonicalTeam(String team) {
        return team == null ? null : team.replaceAll("[_\\s]+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private static String display(String team) {
        return team == null || team.isBlank() ? "Sans équipe" : team.trim();
    }

    /** Nom normalisé : minuscules, sans accents ni ponctuation, mots triés (« KONE Awa » = « Awa Koné »). */
    static String nameKey(String name) {
        if (name == null) return "";
        String[] words = fold(name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        List<String> list = new ArrayList<>();
        for (String w : words) if (w.length() > 1) list.add(w);
        if (list.size() < 2) return ""; // un seul mot : trop ambigu pour rapprocher deux comptes
        Collections.sort(list);
        return String.join(" ", list);
    }

    static String emailKey(String email) {
        return email == null || !email.contains("@") ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameEmailOrTeam(User a, User b) {
        String ea = emailKey(a.getEmail()), eb = emailKey(b.getEmail());
        if (!ea.isEmpty() && ea.equals(eb)) return true;
        return Objects.equals(canonicalTeam(a.getActivity()), canonicalTeam(b.getActivity()));
    }

    /** Si plusieurs comptes « avec équipe » pour la même personne : le compte actif le plus récent. */
    private static User preferred(User a, User b) {
        boolean ea = Boolean.TRUE.equals(a.getAccountEnabled()), eb = Boolean.TRUE.equals(b.getAccountEnabled());
        if (ea != eb) return ea ? a : b;
        return (b.getId() != null && a.getId() != null && b.getId() > a.getId()) ? b : a;
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }
}
