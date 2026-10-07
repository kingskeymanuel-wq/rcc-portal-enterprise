package com.ecobank.rccportal.service;

import com.ecobank.rccportal.security.AccessResolver;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Administration — organigramme hiérarchique : Superviseur (Head RCC), RH, Head QA et son équipe QA,
 * Team Leaders par équipe avec leurs agents, puis administrateurs, agences et comptes à classer.
 * Chaque personne vient avec ses rôles et services réels (identifiants compris) pour que l'administrateur
 * retire ou ajoute ce qu'il faut sur place ; le niveau est calculé par la même règle que les portails
 * (AccessResolver) — ce que l'on voit ici est donc exactement l'accès obtenu.
 */
@Service
public class AdminHierarchyService {

    public record Item(Long id, String code, String name) {}

    public record Person(Long id, String username, String name, String email, String country, boolean active,
                         String level, String profile, String team, String ledTeam,
                         List<Item> roles, List<Item> services, List<String> warnings,
                         String branch, String activity, String leaderSource) {}

    public record TeamBlock(String code, String label, List<Person> leaders, List<Person> agents) {}

    public record Hierarchy(List<Person> supervisors, List<Person> rh, List<Person> headQa, List<Person> qa,
                            List<TeamBlock> teams, List<Person> admins, List<Person> agencies, List<Person> unclassified,
                            Map<String, Integer> counts) {}

    public static final Map<String, String> TEAM_LABELS = new LinkedHashMap<>();
    static {
        TEAM_LABELS.put("INBOUND_VOICE", "Inbound Voix");
        TEAM_LABELS.put("INBOUND_MAIL", "Inbound Mail");
        TEAM_LABELS.put("TCHAT", "Réseaux sociaux");
        TEAM_LABELS.put("RAFIKI", "Rafiki");
        TEAM_LABELS.put("CIB", "CIB");
        TEAM_LABELS.put("DIGITALISATION", "Outbound — Digitalisation");
        TEAM_LABELS.put("TELEVENTE", "Télévente");
        TEAM_LABELS.put("OUTBOUND", "Outbound (pôle, sans sous-équipe)");
    }

    private final JdbcTemplate jdbc;

    public AdminHierarchyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Niveau hiérarchique d'une personne (voir la javadoc de la classe). */
    static String levelOf(String profile, List<String> serviceCodes) {
        Set<String> s = new HashSet<>();
        serviceCodes.forEach(c -> s.add(up(c)));
        String p = up(profile);
        if (p.equals("ADMIN")) return "ADMIN";
        if (p.equals("SUPERVISOR")) return "SUPERVISEUR";
        if (p.equals("RH")) return "RH";
        if (s.contains("SUPERVISEUR_QA")) return "HEAD_QA";
        if (p.equals("TEAM_LEADER")) return "TEAM_LEADER";
        if (s.contains("QUALITY_ASSURANCE") || s.contains("FORMATEUR")) return "QA";
        if (p.equals("AGENCE")) return "AGENCE";
        if (p.equals("AGENT")) return "AGENT";
        return "A_CLASSER";
    }

    /** Équipe affichée : équipe menée (Team Leader), sinon canal digital (Tchat, Rafiki), sinon équipe métier. */
    static String teamOf(String level, String ledTeam, String activity, List<String> serviceCodes) {
        if (level.equals("TEAM_LEADER")) {
            if (ledTeam != null && !ledTeam.isBlank()) return up(ledTeam);
            for (String c : serviceCodes) if (up(c).startsWith("TEAM_LEADER_")) return up(c).substring("TEAM_LEADER_".length());
            return null;
        }
        if (!level.equals("AGENT")) return null;
        String channel = TeamClassifier.channel(activity, serviceCodes);
        if (channel != null) return channel;
        TeamClassifier.Team t = TeamClassifier.classify(activity, serviceCodes);
        return t == TeamClassifier.Team.OTHER ? null : t.name();
    }

    /**
     * D'où vient l'accès Team Leader : « ROLE » (rôle Team Leader…), « SERVICE » (service Team Leader …) ou
     * « LED_TEAM » quand seul le champ équipe menée le donne — cas typique d'un ancien Team Leader ou d'une
     * saisie erronée, signalé à l'administrateur pour qu'il repasse la personne en agent d'un clic.
     */
    static String leaderSourceOf(String level, List<Item> roles, List<String> serviceCodes) {
        if (!level.equals("TEAM_LEADER")) return null;
        if (roles.stream().anyMatch(r -> "TEAM_LEADER".equals(AccessResolver.roleOfName(r.name())))) return "ROLE";
        if (serviceCodes.stream().anyMatch(c -> up(c).startsWith("TEAM_LEADER_"))) return "SERVICE";
        return "LED_TEAM";
    }

    static List<String> warningsOf(String level, String team, boolean active, List<Item> roles, List<String> serviceCodes) {
        List<String> w = new ArrayList<>();
        if (active && "LED_TEAM".equals(leaderSourceOf(level, roles, serviceCodes))) {
            w.add("Team Leader uniquement par le champ « équipe menée » (aucun rôle ni service Team Leader) : "
                    + "si ce n'est pas un Team Leader, choisissez « Agent » dans Accès.");
        }
        if (!active) w.add("Compte désactivé : aucun accès au portail.");
        if (level.equals("TEAM_LEADER") && team == null) w.add("Team Leader sans équipe : ajoutez un service « Team Leader … » pour lui donner son portail.");
        if (level.equals("AGENT") && team == null) w.add("Agent sans équipe : ajoutez un service agent (Inbound, Outbound, Réseaux sociaux, Rafiki, Mail, CIB).");
        if (level.equals("A_CLASSER")) w.add("Aucun rôle reconnu : ajoutez un rôle (agent, Team Leader, RH…) pour lui ouvrir un portail.");
        long tl = serviceCodes.stream().filter(c -> up(c).startsWith("TEAM_LEADER_")).count();
        if (tl > 1) w.add("Plusieurs services Team Leader : une seule équipe est prise en compte, retirez les autres.");
        if (roles.stream().anyMatch(r -> up(r.name()).equals("EXCELLIAM"))) w.add("Rôle Excelliam : ce portail n'existe plus, retirez ce rôle.");
        return w;
    }

    @Transactional(readOnly = true)
    public Hierarchy hierarchy() {
        Map<Long, List<Item>> roles = new HashMap<>();
        jdbc.query("SELECT ur.USERS_ID, r.ID, r.NAME FROM dbo.USER_ROLES ur JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID", rs -> {
            roles.computeIfAbsent(rs.getLong("USERS_ID"), k -> new ArrayList<>()).add(new Item(rs.getLong("ID"), rs.getString("NAME"), rs.getString("NAME")));
        });
        Map<Long, List<Item>> services = new HashMap<>();
        jdbc.query("SELECT us.USER_ID, s.ID, s.CODE, s.NAME FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID", rs -> {
            services.computeIfAbsent(rs.getLong("USER_ID"), k -> new ArrayList<>()).add(new Item(rs.getLong("ID"), rs.getString("CODE"), rs.getString("NAME")));
        });
        List<Person> people = new ArrayList<>();
        jdbc.query("SELECT ID, USERNAME, NAME, EMAIL, AFFILIATE_BRANCH, ACCOUNT_ENABLED, ACTIVITY, LED_TEAM FROM dbo.USERS", rs -> {
            long id = rs.getLong("ID");
            List<Item> r = roles.getOrDefault(id, List.of());
            List<Item> s = services.getOrDefault(id, List.of());
            List<String> codes = s.stream().map(x -> x.code() != null ? x.code() : x.name()).filter(Objects::nonNull).toList();
            String led = rs.getString("LED_TEAM");
            String profile = AccessResolver.primaryRole(r.stream().map(Item::name).toList(), codes, led);
            String level = levelOf(profile, codes);
            Object enabled = rs.getObject("ACCOUNT_ENABLED");
            boolean active = enabled == null || (enabled instanceof Boolean b ? b : ((Number) enabled).intValue() != 0);
            String team = teamOf(level, led, rs.getString("ACTIVITY"), codes);
            String name = rs.getString("NAME");
            people.add(new Person(id, rs.getString("USERNAME"), name == null || name.isBlank() ? rs.getString("USERNAME") : name,
                    rs.getString("EMAIL"), HrOrganizationService.countryOf(rs.getString("AFFILIATE_BRANCH")), active, level, profile, team, led,
                    r, s, warningsOf(level, team, active, r, codes),
                    rs.getString("AFFILIATE_BRANCH"), rs.getString("ACTIVITY"), leaderSourceOf(level, r, codes)));
        });
        return build(people);
    }

    static Hierarchy build(List<Person> people) {
        people.sort(Comparator.comparing((Person p) -> !p.active()).thenComparing(p -> p.name() == null ? "" : p.name(), String.CASE_INSENSITIVE_ORDER));
        Map<String, List<Person>> by = new HashMap<>();
        for (Person p : people) by.computeIfAbsent(p.level(), k -> new ArrayList<>()).add(p);
        List<TeamBlock> teams = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(TEAM_LABELS.keySet());
        for (Person p : people) if (p.team() != null) known.add(p.team());
        for (String code : known) {
            List<Person> leaders = by.getOrDefault("TEAM_LEADER", List.of()).stream().filter(p -> code.equals(p.team())).toList();
            List<Person> agents = by.getOrDefault("AGENT", List.of()).stream().filter(p -> code.equals(p.team())).toList();
            if (!leaders.isEmpty() || !agents.isEmpty() || TEAM_LABELS.containsKey(code)) {
                teams.add(new TeamBlock(code, TEAM_LABELS.getOrDefault(code, code), leaders, agents));
            }
        }
        List<Person> noTeamLeaders = by.getOrDefault("TEAM_LEADER", List.of()).stream().filter(p -> p.team() == null).toList();
        List<Person> noTeamAgents = by.getOrDefault("AGENT", List.of()).stream().filter(p -> p.team() == null).toList();
        if (!noTeamLeaders.isEmpty() || !noTeamAgents.isEmpty()) teams.add(new TeamBlock("SANS_EQUIPE", "Sans équipe", noTeamLeaders, noTeamAgents));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String l : List.of("SUPERVISEUR", "RH", "HEAD_QA", "QA", "TEAM_LEADER", "AGENT", "ADMIN", "AGENCE", "A_CLASSER")) {
            counts.put(l, by.getOrDefault(l, List.of()).size());
        }
        counts.put("WARNINGS", (int) people.stream().filter(p -> p.active() && !p.warnings().isEmpty()).count());
        counts.put("GHOST_LEADERS", (int) people.stream().filter(p -> p.active() && "LED_TEAM".equals(p.leaderSource())).count());
        return new Hierarchy(by.getOrDefault("SUPERVISEUR", List.of()), by.getOrDefault("RH", List.of()), by.getOrDefault("HEAD_QA", List.of()),
                by.getOrDefault("QA", List.of()), teams, by.getOrDefault("ADMIN", List.of()), by.getOrDefault("AGENCE", List.of()),
                by.getOrDefault("A_CLASSER", List.of()), counts);
    }
}
