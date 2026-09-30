package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * « Mon espace » dans MON RCC : chaque utilisateur retrouve SON portail, SON équipe, des repères propres à son
 * métier (agent, Team Leader, QA, RH, Superviseur, agence…) et SES correspondants (son Team Leader et ses
 * collègues pour un agent, les membres de son équipe pour un Team Leader…) — au lieu du même affichage partout.
 */
@Service
public class MonRccSpaceService {

    public record Tile(String icon, String label, String value, String hint, String link, String tone) {}

    public record Contact(String username, String name, String role, String group) {}

    public record MySpace(String profile, String theme, String firstName, String portalUrl, String portalLabel, String team, String teamLabel,
                          String headline, List<Tile> tiles, List<Contact> contacts) {}

    /** Instantané d'un collaborateur pour le choix des correspondants. */
    record Member(Long id, String username, String name, String team, Set<String> services, String ledTeam, boolean active, String branch) {
        boolean isTeamLeader() { return ledTeam != null && !ledTeam.isBlank() || services.stream().anyMatch(s -> s.startsWith("TEAM_LEADER")); }
        boolean isQa() { return services.contains("QUALITY_ASSURANCE") || services.contains("SUPERVISEUR_QA"); }
        boolean isRh() { return services.contains("RH"); }
        boolean isSupervisor() { return services.contains("SUPERVISEUR"); }
        boolean isAgence() { return services.stream().anyMatch(s -> s.startsWith("AGENCE")); }
        boolean isAgent() { return !isTeamLeader() && !isQa() && !isRh() && !isSupervisor() && !isAgence() && team != null; }
    }

    static final Map<String, String> TEAM_LABELS = Map.of("INBOUND_VOICE", "Inbound Voix", "INBOUND_MAIL", "Inbound Mail", "TCHAT", "Réseaux sociaux",
            "RAFIKI", "Rafiki", "OUTBOUND", "Outbound", "CIB", "CIB", "TELEVENTE", "Télévente", "DIGITALISATION", "Digitalisation");

    private static final Map<String, String> PORTAL_LABELS = Map.ofEntries(
            Map.entry("/dashboard", "Portail agent"), Map.entry("/portail-tchat", "Portail agent Réseaux sociaux"), Map.entry("/portail-rafiki", "Portail agent Rafiki"),
            Map.entry("/outbound-dashboard", "Portail Outbound — Digitalisation"), Map.entry("/portail-televente", "Portail Télévente"), Map.entry("/team-leader", "Portail Team Leader"), Map.entry("/qa", "Portail Quality Assurance"),
            Map.entry("/qa-supervisor", "Portail Head QA"), Map.entry("/rh", "Portail RH"), Map.entry("/supervisor", "Portail Superviseur"),
            Map.entry("/agence", "Portail Agence"), Map.entry("/training", "Centre de formation"));

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final TeamPerfFileService perfFiles;
    private final ScheduleService schedules;
    private final QualityEvaluationService evaluations;

    public MonRccSpaceService(UserRepository users, JdbcTemplate jdbc, UserService userService, TeamPerfFileService perfFiles,
                              ScheduleService schedules, QualityEvaluationService evaluations) {
        this.users = users;
        this.jdbc = jdbc;
        this.userService = userService;
        this.perfFiles = perfFiles;
        this.schedules = schedules;
        this.evaluations = evaluations;
    }

    public MySpace mySpace(AuthenticatedUser requester) {
        if (requester == null) throw ApiException.unauthorized("Non connecté.");
        User me = users.findFirstByUsernameIgnoreCase(requester.username()).orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
        List<Member> members = members();
        Member self = members.stream().filter(m -> m.id().equals(me.getId())).findFirst()
                .orElse(new Member(me.getId(), me.getUsername(), me.getName(), null, Set.of(), me.getLedTeam(), true, me.getAffiliateBranch()));
        String profile = profile(requester);

        String portal = null;
        try {
            portal = userService.teamStatus(me.getUsername()).redirectTo();
        } catch (RuntimeException ignored) {
            // pas de redirection connue : portail par défaut du profil
        }
        if (portal == null) portal = defaultPortal(profile);
        String team = self.team();
        if ("TEAM_LEADER".equals(profile) && me.getLedTeam() != null) team = me.getLedTeam().toUpperCase(Locale.ROOT);
        String teamLabel = team == null ? null : TEAM_LABELS.getOrDefault(team, team);
        String first = firstName(me.getName() != null ? me.getName() : me.getUsername());

        return new MySpace(profile, theme(profile, portal), first, portal, PORTAL_LABELS.getOrDefault(portal, "Mon portail"), team, teamLabel,
                headline(profile, teamLabel), tiles(profile, me, self, members, team, portal), contacts(profile, self, members));
    }

    // ───────────── Profil, thème, textes ─────────────

    static String profile(AuthenticatedUser u) {
        String role = u.role() == null ? "AGENT" : u.role().toUpperCase(Locale.ROOT);
        String service = u.service() == null ? "" : u.service().toUpperCase(Locale.ROOT).replace(' ', '_');
        if ("SUPERVISEUR_QA".equals(service) || "QA_SUPERVISOR".equals(role)) return "QA_SUPERVISOR";
        return role;
    }

    static String defaultPortal(String profile) {
        return switch (profile) {
            case "TEAM_LEADER" -> "/team-leader";
            case "QA" -> "/qa";
            case "QA_SUPERVISOR" -> "/qa-supervisor";
            case "RH" -> "/rh";
            case "SUPERVISOR" -> "/supervisor";
            case "AGENCE" -> "/agence";
            default -> "/dashboard";
        };
    }

    static String theme(String profile, String portal) {
        if ("/portail-tchat".equals(portal)) return "tchat";
        if ("/portail-rafiki".equals(portal)) return "rafiki";
        if ("/outbound-dashboard".equals(portal) || "/portail-televente".equals(portal)) return "outbound";
        return switch (profile) {
            case "TEAM_LEADER" -> "tl";
            case "QA", "QA_SUPERVISOR" -> "qa";
            case "RH" -> "rh";
            case "SUPERVISOR", "ADMIN" -> "sup";
            case "AGENCE" -> "agence";
            default -> "agent";
        };
    }

    static String headline(String profile, String teamLabel) {
        return switch (profile) {
            case "TEAM_LEADER" -> "Votre équipe " + (teamLabel == null ? "" : teamLabel + " ") + "à portée de main : échangez avec vos agents et suivez leurs actualités.";
            case "QA", "QA_SUPERVISOR" -> "Restez en lien avec les Team Leaders et vos collègues Quality Assurance.";
            case "RH" -> "Les actualités du centre et vos correspondants : Team Leaders, Superviseur et équipe RH.";
            case "SUPERVISOR", "ADMIN" -> "Toute la communauté RCC, vos Team Leaders et le Head QA en un coup d'œil.";
            case "AGENCE" -> "Les actualités du réseau et vos collègues d'agence.";
            default -> "Votre équipe " + (teamLabel == null ? "" : teamLabel + " ") + "et votre Team Leader, vos actualités et vos échanges.";
        };
    }

    private static String firstName(String name) {
        String[] parts = name.trim().split("\\s+");
        // Les plannings écrivent souvent le NOM en majuscules d'abord : on prend le premier mot qui n'est pas tout en capitales.
        for (String p : parts) if (p.length() > 1 && !p.equals(p.toUpperCase(Locale.ROOT))) return p;
        String p = parts[parts.length > 1 ? 1 : 0];
        return p.isEmpty() ? name : p.charAt(0) + p.substring(1).toLowerCase(Locale.ROOT);
    }

    // ───────────── Correspondants ─────────────

    /** Les correspondants proposés à chacun, par ordre d'utilité pour son métier. */
    static List<Contact> contacts(String profile, Member self, List<Member> all) {
        List<Member> others = all.stream().filter(m -> m.active() && !m.id().equals(self.id())).toList();
        Map<String, Contact> out = new LinkedHashMap<>();
        java.util.function.BiConsumer<List<Member>, String> add = (list, group) -> list.stream()
                .sorted(Comparator.comparing(m -> m.name() == null ? "" : m.name(), String.CASE_INSENSITIVE_ORDER))
                .forEach(m -> out.putIfAbsent(m.username(), new Contact(m.username(), m.name(), roleLabel(m), group)));
        String classifierTeam = classifierOf(self.team());
        switch (profile) {
            case "TEAM_LEADER" -> {
                String led = self.ledTeam() == null ? classifierTeam : self.ledTeam().toUpperCase(Locale.ROOT);
                add.accept(others.stream().filter(m -> m.isAgent() && led != null && (led.equals(m.team()) || led.equals(classifierOf(m.team())))).limit(40).toList(), "Mon équipe");
                add.accept(others.stream().filter(Member::isSupervisor).limit(3).toList(), "Superviseur");
                add.accept(others.stream().filter(Member::isTeamLeader).limit(10).toList(), "Team Leaders");
                add.accept(others.stream().filter(Member::isQa).limit(6).toList(), "Quality Assurance");
            }
            case "QA", "QA_SUPERVISOR" -> {
                add.accept(others.stream().filter(Member::isQa).limit(15).toList(), "Quality Assurance");
                add.accept(others.stream().filter(Member::isTeamLeader).limit(10).toList(), "Team Leaders");
                add.accept(others.stream().filter(Member::isSupervisor).limit(3).toList(), "Superviseur");
            }
            case "RH" -> {
                add.accept(others.stream().filter(Member::isRh).limit(10).toList(), "Équipe RH");
                add.accept(others.stream().filter(Member::isSupervisor).limit(3).toList(), "Superviseur");
                add.accept(others.stream().filter(Member::isTeamLeader).limit(10).toList(), "Team Leaders");
            }
            case "SUPERVISOR", "ADMIN" -> {
                add.accept(others.stream().filter(Member::isTeamLeader).limit(12).toList(), "Team Leaders");
                add.accept(others.stream().filter(m -> m.services().contains("SUPERVISEUR_QA")).limit(3).toList(), "Head QA");
                add.accept(others.stream().filter(Member::isRh).limit(4).toList(), "RH");
            }
            case "AGENCE" -> add.accept(others.stream().filter(m -> m.isAgence() && Objects.equals(m.branch(), self.branch())).limit(30).toList(), "Mon agence");
            default -> {
                // Team Leader de son canal (Tchat, Rafiki) d'abord, puis celui du pôle.
                add.accept(others.stream().filter(m -> m.isTeamLeader() && self.team() != null && self.team().equalsIgnoreCase(m.ledTeam())).toList(), "Mon Team Leader");
                if (out.isEmpty()) add.accept(others.stream().filter(m -> m.isTeamLeader() && classifierTeam != null && classifierTeam.equalsIgnoreCase(m.ledTeam())).toList(), "Mon Team Leader");
                add.accept(others.stream().filter(m -> m.isAgent() && self.team() != null && self.team().equals(m.team())).limit(30).toList(), "Mon équipe");
                add.accept(others.stream().filter(Member::isQa).limit(4).toList(), "Quality Assurance");
            }
        }
        return new ArrayList<>(out.values());
    }

    private static String classifierOf(String team) {
        return team != null && TeamClassifier.isChannel(team) ? TeamClassifier.teamOf(team).name() : team;
    }

    private static String roleLabel(Member m) {
        if (m.isTeamLeader()) return "Team Leader " + TEAM_LABELS.getOrDefault(m.ledTeam() == null ? "" : m.ledTeam().toUpperCase(Locale.ROOT), "");
        if (m.services().contains("SUPERVISEUR_QA")) return "Head QA";
        if (m.isQa()) return "Quality Assurance";
        if (m.isRh()) return "RH";
        if (m.isSupervisor()) return "Superviseur";
        if (m.isAgence()) return "Agence";
        return "Agent " + TEAM_LABELS.getOrDefault(m.team() == null ? "" : m.team(), "");
    }

    /** Équipe fine d'un collaborateur : placement RH (Tchat, Rafiki…) d'abord, puis activité et services. */
    static String teamOf(String activity, Collection<String> services, String hrTeam) {
        if (hrTeam != null) {
            switch (hrTeam) {
                case "TCHAT": return "TCHAT";
                case "RAFIKI": return "RAFIKI";
                case "MAIL": return "INBOUND_MAIL";
                case "VOICE": return "INBOUND_VOICE";
                case "CIB": return "CIB";
                case "DIGITAL": return "DIGITALISATION";
                case "TELEVENTE": return "TELEVENTE";
                default: break;
            }
        }
        String a = activity == null ? "" : activity.toUpperCase(Locale.ROOT);
        if (a.contains("RAFIKI") || services.contains("AGENT_RAFIKI")) return "RAFIKI";
        if (a.contains("TCHAT") || a.contains("LIVE CHAT") || services.contains("AGENT_TCHAT")) return "TCHAT";
        String sub = TeamClassifier.channel(activity, services);
        if ("TELEVENTE".equals(sub) || "DIGITALISATION".equals(sub)) return sub;
        TeamClassifier.Team t = TeamClassifier.classify(activity, services);
        return t == TeamClassifier.Team.OTHER ? null : t.name();
    }

    private List<Member> members() {
        Map<Long, Set<String>> svc = new HashMap<>();
        Map<Long, String> hr = new HashMap<>();
        try {
            jdbc.query("SELECT us.USER_ID, s.CODE FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID",
                    rs -> { svc.computeIfAbsent(rs.getLong("USER_ID"), k -> new HashSet<>()).add(rs.getString("CODE").toUpperCase(Locale.ROOT)); });
        } catch (RuntimeException ignored) {
            // services absents
        }
        try {
            jdbc.query("SELECT UserId, HrTeam FROM dbo.HrAssignments", rs -> { hr.put(rs.getLong("UserId"), rs.getString("HrTeam")); });
        } catch (RuntimeException ignored) {
            // pas encore de placement RH
        }
        List<Member> out = new ArrayList<>();
        for (User u : users.findAll()) {
            Set<String> s = svc.getOrDefault(u.getId(), Set.of());
            out.add(new Member(u.getId(), u.getUsername(), u.getName() != null ? u.getName() : u.getUsername(), teamOf(u.getActivity(), s, hr.get(u.getId())),
                    s, u.getLedTeam(), !Boolean.FALSE.equals(u.getAccountEnabled()), u.getAffiliateBranch()));
        }
        return out;
    }

    // ───────────── Repères du métier ─────────────

    private List<Tile> tiles(String profile, User me, Member self, List<Member> all, String team, String portal) {
        List<Tile> t = new ArrayList<>();
        String portalLabel = PORTAL_LABELS.getOrDefault(portal, "Mon portail");
        switch (profile) {
            case "AGENT" -> {
                try {
                    var perf = perfFiles.mine(new AuthenticatedUser(me.getUsername(), "AGENT", null, me.getName()));
                    if (!perf.weeks().isEmpty()) {
                        var w = perf.weeks().get(0);
                        Double p = TeamPerfFileService.d(w.values(), "productivity");
                        t.add(new Tile("bi-speedometer2", "Ma performance", p == null ? "—" : Math.round(p) + " %",
                                "Semaine du " + w.from().getDayOfMonth() + "/" + w.from().getMonthValue() + (w.rank() != null ? " · " + w.rank() + "e / " + w.teamSize() : ""),
                                "/performance", "GOOD".equals(w.level()) ? "good" : "WARN".equals(w.level()) ? "warn" : "BAD".equals(w.level()) ? "bad" : null));
                    }
                } catch (RuntimeException ignored) {
                    // pas encore de rapport hebdo
                }
                try {
                    var today = schedules.planningForUser(me.getUsername(), LocalDate.now(), LocalDate.now());
                    var s = today.isEmpty() ? null : today.get(0);
                    t.add(new Tile("bi-clock", "Mon shift aujourd'hui", s == null ? "Non planifié" : s.shiftCode(),
                            s != null && s.startTime() != null ? s.startTime().toString().substring(0, 5) + " – " + (s.endTime() == null ? "" : s.endTime().toString().substring(0, 5)) : (s == null ? "" : s.shiftLabel()),
                            "/shift", null));
                } catch (RuntimeException ignored) {
                    // planning indisponible
                }
                long mates = all.stream().filter(m -> m.active() && m.isAgent() && team != null && team.equals(m.team())).count();
                if (team != null) t.add(new Tile("bi-people", "Mon équipe", TEAM_LABELS.getOrDefault(team, team), mates + " agent(s)", portal, null));
            }
            case "TEAM_LEADER" -> {
                long size = all.stream().filter(m -> m.active() && m.isAgent() && team != null && (team.equals(m.team()) || team.equals(classifierOf(m.team())))).count();
                t.add(new Tile("bi-people", "Mon équipe", String.valueOf(size), "agent(s) actif(s)", "/team-leader", null));
                t.add(new Tile("bi-file-earmark-spreadsheet", "Performances hebdo", "Importer", "rapport de mon équipe", "/team-leader", null));
            }
            case "QA", "QA_SUPERVISOR" -> {
                YearMonth ym = YearMonth.now();
                try {
                    long n = evaluations.listAll().stream().filter(e -> e.evaluationDate() != null && YearMonth.from(e.evaluationDate()).equals(ym)).count();
                    long mine = evaluations.listAll().stream().filter(e -> e.evaluationDate() != null && YearMonth.from(e.evaluationDate()).equals(ym)
                            && me.getUsername().equalsIgnoreCase(e.evaluatorMatricule())).count();
                    t.add(new Tile("bi-headset", "Écoutes du mois", String.valueOf(n), mine + " par moi", portal, null));
                } catch (RuntimeException ignored) {
                    // module QA indisponible
                }
                t.add(new Tile("bi-file-earmark-spreadsheet", "Fichiers de performance", "Importer", "par équipe", "/qa", null));
            }
            case "RH" -> {
                long active = all.stream().filter(m -> m.active() && (m.isAgent() || m.isTeamLeader())).count();
                t.add(new Tile("bi-people", "Collaborateurs actifs", String.valueOf(active), "agents et Team Leaders", "/rh", null));
                try {
                    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.HrDepartures WHERE ReintegratedAt IS NULL AND DepartureDate >= ?", Integer.class,
                            java.sql.Date.valueOf(YearMonth.now().atDay(1)));
                    t.add(new Tile("bi-box-arrow-right", "Sorties du mois", String.valueOf(n == null ? 0 : n), "enregistrées par le RH", "/rh#exits", null));
                } catch (RuntimeException ignored) {
                    // table des sorties absente
                }
            }
            case "SUPERVISOR", "ADMIN" -> {
                t.add(new Tile("bi-people", "Collaborateurs actifs", String.valueOf(all.stream().filter(m -> m.active() && m.isAgent()).count()), "agents", portal, null));
                t.add(new Tile("bi-person-badge", "Team Leaders", String.valueOf(all.stream().filter(m -> m.active() && m.isTeamLeader()).count()), "équipes encadrées", portal, null));
            }
            case "AGENCE" -> t.add(new Tile("bi-shop-window", "Mon agence", self.branch() == null ? "—" : self.branch(), "réseau d'agences", "/agence", null));
            default -> { }
        }
        t.add(new Tile("bi-box-arrow-up-right", "Mon portail", portalLabel, "revenir à mon espace de travail", portal, "portal"));
        return t;
    }
}
