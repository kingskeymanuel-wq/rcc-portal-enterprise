package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.PersonNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Doublons d'agents JUSQU'À LA BASE : détection (même e-mail, ou même personne selon
 * {@link PersonNames} — ordre des mots, accents, suffixe « (2°) », prénom en plus, petite faute)
 * puis fusion confirmée par l'administrateur, groupe par groupe.
 *
 * Fusion (moteur commun {@link UserDeduplicationService#mergeInto}) : champs manquants du
 * principal complétés, TOUT l'historique du doublon (pointages, plannings, KPI, évaluations,
 * demandes, messages, formations…) déplacé sur le principal, lignes qui feraient double emploi
 * retirées, puis compte doublon SUPPRIMÉ de dbo.USERS.
 */
@Service
public class UserDuplicateService {

    private static final Logger log = LoggerFactory.getLogger(UserDuplicateService.class);

    public record Member(Long id, String username, String name, String email, String team, boolean active, boolean main) {}

    public record Group(String key, String reason, List<Member> members) {}

    public record MergeResult(Long mainId, String mainUsername, List<String> merged, int rowsReassigned, int rowsRemoved, List<String> warnings) {}

    private final UserRepository userRepository;
    private final UserDeduplicationService engine;

    public UserDuplicateService(UserRepository userRepository, UserDeduplicationService engine) {
        this.userRepository = userRepository;
        this.engine = engine;
    }

    // ── Détection ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Group> findGroups() {
        return groups(userRepository.findAll());
    }

    static List<Group> groups(List<User> users) {
        int n = users.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        String[] reason = new String[n];

        Map<String, Integer> byEmail = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String e = email(users.get(i));
            if (e.isEmpty()) continue;
            Integer j = byEmail.putIfAbsent(e, i);
            if (j != null) { union(parent, i, j); reason[find(parent, i)] = "même e-mail"; }
        }
        // Noms : clé exacte d'abord (rapide), puis rapprochement tolérant.
        List<String> keys = new ArrayList<>();
        for (User u : users) keys.add(PersonNames.key(u.getName()));
        for (int i = 0; i < n; i++) {
            if (keys.get(i).isEmpty() || !keys.get(i).contains(" ")) continue;
            for (int j = i + 1; j < n; j++) {
                if (keys.get(j).isEmpty()) continue;
                boolean same = keys.get(i).equals(keys.get(j));
                if (same || PersonNames.similar(users.get(i).getName(), users.get(j).getName())) {
                    if (find(parent, i) != find(parent, j)) {
                        union(parent, i, j);
                        int r = find(parent, i);
                        if (reason[r] == null) reason[r] = same ? "même nom" : "nom très proche";
                    }
                }
            }
        }

        Map<Integer, List<User>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(users.get(i));
        List<Group> out = new ArrayList<>();
        for (Map.Entry<Integer, List<User>> e : byRoot.entrySet()) {
            List<User> g = e.getValue();
            if (g.size() < 2) continue;
            User main = chooseMain(g);
            List<Member> members = new ArrayList<>();
            members.add(member(main, true));
            g.stream().filter(u -> u != main).forEach(u -> members.add(member(u, false)));
            String r = reason[e.getKey()];
            if (r == null) r = "même personne";
            out.add(new Group(PersonNames.key(main.getName()), r, members));
        }
        out.sort(Comparator.comparing(Group::key));
        return out;
    }

    /** Compte principal : celui qui a une vraie équipe, puis actif, puis sans suffixe « 2 », puis le plus ancien. */
    static User chooseMain(List<User> group) {
        return group.stream().max(Comparator
                .comparingInt((User u) -> HrTeamConsolidationService.hasRealTeam(u) ? 1 : 0)
                .thenComparingInt(u -> Boolean.TRUE.equals(u.getAccountEnabled()) ? 1 : 0)
                .thenComparingInt(u -> u.getUsername() != null && u.getUsername().matches(".*[._-]?\\d+$") ? 0 : 1)
                .thenComparingInt(u -> u.getName() != null && u.getName().contains("(") ? 0 : 1)
                .thenComparing(u -> -(u.getId() == null ? Long.MAX_VALUE : u.getId()))).orElseThrow();
    }

    private static Member member(User u, boolean main) {
        return new Member(u.getId(), u.getUsername(), u.getName(), u.getEmail(), u.getActivity(), Boolean.TRUE.equals(u.getAccountEnabled()), main);
    }

    private static String email(User u) {
        return u.getEmail() == null || !u.getEmail().contains("@") ? "" : u.getEmail().trim().toLowerCase(Locale.ROOT);
    }

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    // ── Fusion (moteur commun : UserDeduplicationService.mergeInto) ─────────

    @Transactional
    public MergeResult merge(Long mainId, List<Long> duplicateIds) {
        User main = userRepository.findById(mainId).orElseThrow(() -> ApiException.notFound("Compte principal introuvable."));
        if (duplicateIds == null || duplicateIds.isEmpty()) throw ApiException.badRequest("Aucun doublon sélectionné.");
        List<User> dups = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Long id : duplicateIds) {
            if (id == null || id.equals(mainId)) continue;
            userRepository.findById(id).ifPresentOrElse(dups::add, () -> warnings.add("Compte " + id + " introuvable (déjà fusionné ?)."));
        }
        if (dups.isEmpty()) throw ApiException.badRequest("Aucun doublon à fusionner.");
        dups.forEach(d -> completeMain(main, d));
        UserDeduplicationService.MergeCounts counts = engine.mergeInto(main, dups);
        warnings.addAll(counts.warnings());
        List<String> merged = dups.stream().map(User::getUsername).toList();
        log.warn("[DOUBLONS] {} fusionné(s) dans {} ({}) — {} ligne(s) réassignée(s).", merged, main.getUsername(), main.getName(), counts.reassigned());
        return new MergeResult(main.getId(), main.getUsername(), merged, counts.reassigned(), counts.dedupedAway(), warnings);
    }

    static void completeMain(User main, User dup) {
        if (blank(main.getEmail())) main.setEmail(dup.getEmail());
        if (!HrTeamConsolidationService.hasRealTeam(main) && HrTeamConsolidationService.hasRealTeam(dup)) main.setActivity(dup.getActivity());
        if (blank(main.getAffiliateBranch())) main.setAffiliateBranch(dup.getAffiliateBranch());
        if (blank(main.getGender())) main.setGender(dup.getGender());
        if (blank(main.getContractType())) main.setContractType(dup.getContractType());
        if (blank(main.getContractStatus())) main.setContractStatus(dup.getContractStatus());
        if (main.getContractStartDate() == null) main.setContractStartDate(dup.getContractStartDate());
        if (main.getContractEndDate() == null) main.setContractEndDate(dup.getContractEndDate());
        if (blank(main.getRoleDetail())) main.setRoleDetail(dup.getRoleDetail());
        if (blank(main.getResidencePlace())) main.setResidencePlace(dup.getResidencePlace());
        if (blank(main.getLedTeam())) main.setLedTeam(dup.getLedTeam());
        // Nom : on garde la forme la plus complète, sans suffixe d'import « (2°) ».
        String mainName = main.getName() == null ? "" : main.getName().replaceAll("\\s*\\(.*?\\)\\s*", " ").trim();
        String dupName = dup.getName() == null ? "" : dup.getName().replaceAll("\\s*\\(.*?\\)\\s*", " ").trim();
        if (PersonNames.tokens(dupName).size() > PersonNames.tokens(mainName).size()) mainName = dupName;
        if (!mainName.isEmpty()) main.setName(mainName);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
