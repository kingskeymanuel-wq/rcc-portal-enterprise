package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Portail RH : organisation des collaborateurs par filiale (Côte d'Ivoire, Togo), population et
 * équipe, performance par équipe et sorties d'agents.
 * <pre>
 *   Outsource      : Inbound Voix, Tchat, Mail, Rafiki, CIB
 *   Stagiaires     : Inbound Voix, Rafiki, Tchat, Digitalisation (Outbound Digital)
 *   Staff Ecobank  : Quality Assurance, Service Opérations Cartes
 * </pre>
 * Le placement est déduit automatiquement (type de contrat, services, activité, fonction) ; le RH
 * peut le corriger agent par agent (dbo.HrAssignments), sa correction l'emporte toujours.
 */
@Service
public class HrOrganizationService {

    public record TeamDef(String code, String label, String icon) {}

    public record PopulationDef(String code, String label, String icon, List<TeamDef> teams) {}

    public static final List<PopulationDef> POPULATIONS = List.of(
            new PopulationDef("OUTSOURCE", "Outsource", "bi-building", List.of(
                    new TeamDef("VOICE", "Inbound Voix", "bi-headset"), new TeamDef("TCHAT", "Tchat", "bi-chat-dots"),
                    new TeamDef("MAIL", "Mail", "bi-envelope-at"), new TeamDef("RAFIKI", "Rafiki", "bi-robot"),
                    new TeamDef("CIB", "CIB", "bi-briefcase"))),
            new PopulationDef("STAGIAIRE", "Stagiaires", "bi-mortarboard", List.of(
                    new TeamDef("VOICE", "Inbound Voix", "bi-headset"), new TeamDef("RAFIKI", "Rafiki", "bi-robot"),
                    new TeamDef("TCHAT", "Tchat", "bi-chat-dots"), new TeamDef("DIGITAL", "Digitalisation (Outbound Digital)", "bi-phone"))),
            new PopulationDef("STAFF", "Staff Ecobank", "bi-bank", List.of(
                    new TeamDef("QA", "Quality Assurance", "bi-patch-check"), new TeamDef("CARD_OPS", "Service Opérations Cartes", "bi-credit-card-2-front"))));

    public static final Map<String, String> COUNTRIES = new LinkedHashMap<>(Map.of("CI", "Côte d'Ivoire", "TG", "Togo"));

    public static final List<String> DEPARTURE_REASONS = List.of("DEMISSION", "FIN_CONTRAT", "FIN_STAGE", "LICENCIEMENT", "MUTATION", "ABANDON_POSTE", "AUTRE");

    public record Person(Long userId, String username, String name, String email, String role, String service, String activity,
                         String contractType, String contractStatus, LocalDate contractStart, LocalDate contractEnd, boolean active,
                         String countryCode, String population, String team, boolean manual, String photoUrl) {}

    public record TeamCount(String code, String label, String icon, int active, int inactive) {}

    public record PopulationCount(String code, String label, String icon, int active, int inactive, List<TeamCount> teams) {}

    public record Organisation(String country, String countryLabel, Map<String, String> countries, List<PopulationCount> populations,
                               List<Person> people, int toAssign, List<String> departureReasons) {}

    public record Departure(Long id, Long userId, String name, String username, String reason, LocalDate date, String comment,
                            String population, String team, String countryCode, String recordedBy, LocalDateTime recordedAt,
                            LocalDateTime reintegratedAt) {}

    public record AgentPerf(Long userId, String name, String population, String team, Double presence, Double qualityScore,
                            int evaluations, Double performance, Map<String, Double> kpis) {}

    public record TeamPerf(String population, String team, String label, String businessTeam, int headcount, Double presence,
                           Double qualityScore, Double performance, int evaluations, List<AgentPerf> agents) {}

    public record HrPerformance(String country, String period, List<TeamPerf> teams) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ReportingService reporting;

    public HrOrganizationService(JdbcTemplate jdbc, UserRepository users, ReportingService reporting) {
        this.jdbc = jdbc;
        this.users = users;
        this.reporting = reporting;
    }

    // ── Droits ────────────────────────────────────────────────────────────

    static boolean canRead(AuthenticatedUser u) {
        String r = u == null || u.role() == null ? "" : u.role().toUpperCase(Locale.ROOT);
        return r.equals("RH") || r.equals("ADMIN") || r.equals("SUPERVISOR");
    }

    static boolean canWrite(AuthenticatedUser u) {
        String r = u == null || u.role() == null ? "" : u.role().toUpperCase(Locale.ROOT);
        return r.equals("RH") || r.equals("ADMIN");
    }

    private static void requireRead(AuthenticatedUser u) {
        if (!canRead(u)) throw ApiException.forbidden("Réservé aux Ressources Humaines, au Superviseur et à l'administrateur.");
    }

    private static void requireWrite(AuthenticatedUser u) {
        if (!canWrite(u)) throw ApiException.forbidden("Réservé aux Ressources Humaines et à l'administrateur.");
    }

    // ── Classement ────────────────────────────────────────────────────────

    /** Code filiale sur 2 lettres (« CIV » → « CI », « TGO » → « TG ») ; sans filiale renseignée → CI. */
    public static String countryOf(String branch) {
        return country(branch);
    }

    static String country(String branch) {
        if (branch == null || branch.isBlank()) return "CI";
        String b = branch.trim().toUpperCase(Locale.ROOT);
        if (b.startsWith("CI") || b.contains("IVOIRE")) return "CI";
        if (b.startsWith("TG") || b.contains("TOGO")) return "TG";
        return b.length() > 2 ? b.substring(0, 2) : b;
    }

    private static String fold(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    /** Population déduite : stage → Stagiaire ; QA / opérations cartes / contrat Ecobank → Staff ; sinon Outsource. */
    static String inferPopulation(String contractType, String contractStatus, String roleDetail, String activity, Collection<String> serviceCodes) {
        String text = fold(String.join(" ", Objects.toString(contractType, ""), Objects.toString(contractStatus, ""),
                Objects.toString(roleDetail, ""), Objects.toString(activity, "")));
        String services = fold(String.join(" ", serviceCodes == null ? List.of() : serviceCodes));
        if (text.contains("STAGI") || text.contains("STAGE") || text.contains("INTERN")) return "STAGIAIRE";
        if (isQa(text, services) || isCardOps(text, services)) return "STAFF";
        if (fold(contractType).contains("ECOBANK") || fold(contractType).contains("STAFF")) return "STAFF";
        if (text.contains("DIGITAL")) return "STAGIAIRE"; // la digitalisation (Outbound Digital) est tenue par les stagiaires
        return "OUTSOURCE";
    }

    private static boolean isQa(String text, String services) {
        return services.contains("QUALITY_ASSURANCE") || services.contains("SUPERVISEUR_QA") || services.contains("FORMATEUR")
                || text.contains("QUALITY") || text.contains("QUALITE") || text.matches(".*\\bQA\\b.*");
    }

    private static boolean isCardOps(String text, String services) {
        return services.contains("CARTE") || services.contains("CARD") || services.contains("MONETIQUE")
                || text.contains("OPERATION CARTE") || text.contains("OPERATIONS CARTE") || text.contains("MONETIQUE") || text.contains("CARD OPS");
    }

    /** Équipe déduite, limitée aux équipes de la population (sinon null = « à affecter »). */
    static String inferTeam(String population, String roleDetail, String activity, Collection<String> serviceCodes) {
        String text = fold(Objects.toString(activity, "") + " " + Objects.toString(roleDetail, ""));
        String services = fold(String.join(" ", serviceCodes == null ? List.of() : serviceCodes));
        String team;
        if ("STAFF".equals(population)) {
            team = isQa(text, services) ? "QA" : isCardOps(text, services) ? "CARD_OPS" : null;
        } else if (text.contains("RAFIKI")) team = "RAFIKI";
        else if (text.contains("TCHAT") || text.contains("CHAT")) team = "TCHAT";
        else if (text.contains("CIB")) team = "CIB";
        else if (text.contains("DIGITAL") || text.contains("OUTBOUND") || text.contains("TELEVENTE") || services.contains("OUTBOUND")) team = "DIGITAL";
        else if (text.contains("MAIL") || services.contains("INBOUND_MAIL")) team = "MAIL";
        else if (text.contains("VOICE") || text.contains("VOIX") || text.contains("INBOUND") || text.contains("CONSEILLER") || services.contains("INBOUND")) team = "VOICE";
        else team = null;
        return team != null && teamExists(population, team) ? team : null;
    }

    static boolean teamExists(String population, String team) {
        return POPULATIONS.stream().anyMatch(p -> p.code().equals(population) && p.teams().stream().anyMatch(t -> t.code().equals(team)));
    }

    static String teamLabel(String population, String team) {
        return POPULATIONS.stream().filter(p -> p.code().equals(population)).flatMap(p -> p.teams().stream())
                .filter(t -> t.code().equals(team)).map(TeamDef::label).findFirst().orElse("À affecter");
    }

    /** Équipe métier (indicateurs du reporting) correspondant à l'équipe RH. */
    static String businessTeam(String team) {
        if (team == null) return null;
        return switch (team) {
            case "VOICE" -> "INBOUND_VOICE";
            case "TCHAT", "MAIL", "RAFIKI" -> "INBOUND_MAIL";
            case "CIB" -> "CIB";
            case "DIGITAL" -> "OUTBOUND";
            default -> null;
        };
    }

    // ── Organisation ──────────────────────────────────────────────────────

    private record Override(String population, String team) {}

    private Map<Long, Override> overrides() {
        Map<Long, Override> out = new HashMap<>();
        try {
            jdbc.query("SELECT UserId, Population, HrTeam FROM dbo.HrAssignments",
                    rs -> { out.put(rs.getLong("UserId"), new Override(rs.getString("Population"), rs.getString("HrTeam"))); });
        } catch (RuntimeException ignored) {
            // table pas encore créée
        }
        return out;
    }

    private Map<Long, List<String>> serviceCodes() {
        Map<Long, List<String>> out = new HashMap<>();
        try {
            jdbc.query("SELECT us.USER_ID, s.CODE FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID",
                    rs -> { out.computeIfAbsent(rs.getLong("USER_ID"), k -> new ArrayList<>()).add(rs.getString("CODE")); });
        } catch (RuntimeException ignored) {
            // services absents
        }
        return out;
    }

    private Map<Long, String> roleNames() {
        Map<Long, String> out = new HashMap<>();
        try {
            jdbc.query("SELECT ur.USERS_ID, r.NAME FROM dbo.USER_ROLES ur JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID",
                    rs -> { out.merge(rs.getLong("USERS_ID"), rs.getString("NAME"), (a, b) -> a + "," + b); });
        } catch (RuntimeException ignored) {
            // rôles absents
        }
        return out;
    }

    /** Comptes hors périmètre RH : administrateurs techniques, prestataire Excelliam, agents d'agence. */
    private static boolean outOfScope(String roles) {
        String r = fold(roles);
        return r.contains("EXCELLIAM") || r.contains("AGENCE") || (r.contains("ADMIN") && !r.contains("AGENT"));
    }

    List<Person> people(String country) {
        Map<Long, Override> ov = overrides();
        Map<Long, List<String>> svc = serviceCodes();
        Map<Long, String> roles = roleNames();
        List<Person> out = new ArrayList<>();
        for (User u : users.findAll()) {
            if (!country.equals(country(u.getAffiliateBranch()))) continue;
            String roleList = roles.getOrDefault(u.getId(), "");
            if (outOfScope(roleList)) continue;
            List<String> codes = svc.getOrDefault(u.getId(), List.of());
            Override o = ov.get(u.getId());
            String population = o != null && o.population() != null ? o.population()
                    : inferPopulation(u.getContractType(), u.getContractStatus(), u.getRoleDetail(), u.getActivity(), codes);
            String team = o != null && o.team() != null && teamExists(population, o.team()) ? o.team()
                    : (o != null && o.team() != null ? null : inferTeam(population, u.getRoleDetail(), u.getActivity(), codes));
            out.add(new Person(u.getId(), u.getUsername(), u.getName() != null ? u.getName() : u.getUsername(), u.getEmail(),
                    roleList, String.join(", ", codes), u.getActivity(), u.getContractType(), u.getContractStatus(),
                    u.getContractStartDate(), u.getContractEndDate(), !Boolean.FALSE.equals(u.getAccountEnabled()),
                    country, population, team, o != null, null));
        }
        out.sort(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Transactional(readOnly = true)
    public Organisation organisation(AuthenticatedUser requester, String countryParam) {
        requireRead(requester);
        String country = normalizeCountry(countryParam);
        List<Person> people = people(country);
        List<PopulationCount> pops = new ArrayList<>();
        for (PopulationDef p : POPULATIONS) {
            List<TeamCount> teams = new ArrayList<>();
            for (TeamDef t : p.teams()) {
                int a = 0, i = 0;
                for (Person x : people) if (p.code().equals(x.population()) && t.code().equals(x.team())) { if (x.active()) a++; else i++; }
                teams.add(new TeamCount(t.code(), t.label(), t.icon(), a, i));
            }
            int a = 0, i = 0;
            for (Person x : people) if (p.code().equals(x.population())) { if (x.active()) a++; else i++; }
            pops.add(new PopulationCount(p.code(), p.label(), p.icon(), a, i, teams));
        }
        int toAssign = (int) people.stream().filter(x -> x.active() && x.team() == null).count();
        return new Organisation(country, COUNTRIES.get(country), COUNTRIES, pops, people, toAssign, DEPARTURE_REASONS);
    }

    static String normalizeCountry(String c) {
        String k = c == null || c.isBlank() ? "CI" : country(c);
        if (!COUNTRIES.containsKey(k)) throw ApiException.badRequest("Filiale inconnue : CI (Côte d'Ivoire) ou TG (Togo).");
        return k;
    }

    /** Le RH place un collaborateur : population, équipe et, au besoin, filiale. */
    @Transactional
    public void assign(AuthenticatedUser requester, Long userId, String population, String team, String countryCode) {
        requireWrite(requester);
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Collaborateur introuvable."));
        if (POPULATIONS.stream().noneMatch(p -> p.code().equals(population))) throw ApiException.badRequest("Population inconnue.");
        if (team != null && !team.isBlank() && !teamExists(population, team)) throw ApiException.badRequest("Cette équipe n'existe pas pour cette population.");
        String t = team == null || team.isBlank() ? null : team;
        int n = jdbc.update("UPDATE dbo.HrAssignments SET Population = ?, HrTeam = ?, UpdatedBy = ?, UpdatedAt = SYSUTCDATETIME() WHERE UserId = ?",
                population, t, requester.username(), userId);
        if (n == 0) jdbc.update("INSERT INTO dbo.HrAssignments (UserId, Population, HrTeam, UpdatedBy) VALUES (?, ?, ?, ?)", userId, population, t, requester.username());
        if (countryCode != null && !countryCode.isBlank()) {
            u.setAffiliateBranch(normalizeCountry(countryCode));
            users.save(u);
        }
    }

    // ── Sorties ───────────────────────────────────────────────────────────

    /** Retrait d'un agent par le RH : compte désactivé, sortie tracée (motif, date, commentaire). */
    @Transactional
    public Departure recordDeparture(AuthenticatedUser requester, Long userId, String reason, LocalDate date, String comment) {
        requireWrite(requester);
        if (reason == null || !DEPARTURE_REASONS.contains(reason)) throw ApiException.badRequest("Motif de sortie invalide.");
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Collaborateur introuvable."));
        if (Boolean.FALSE.equals(u.getAccountEnabled())) throw ApiException.badRequest("Ce collaborateur est déjà sorti des effectifs.");
        if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(requester.username())) throw ApiException.badRequest("Vous ne pouvez pas enregistrer votre propre sortie.");
        String country = writeDeparture(u, reason, date, comment, requester.username());
        return departures(requester, country).stream().filter(d -> d.userId().equals(userId) && d.reintegratedAt() == null).findFirst().orElse(null);
    }

    /**
     * Sortie enregistrée par le Team Leader de l'agent (bouton « Retirer » de son portail) : même trace que
     * côté RH (onglet Sorties, réintégration possible), le droit sur l'agent étant vérifié par TeamLeaderService.
     */
    @Transactional
    public void recordDepartureForTeamLeader(String teamLeaderUsername, Long userId, String reason, String comment) {
        if (reason == null || !DEPARTURE_REASONS.contains(reason)) throw ApiException.badRequest("Motif de sortie invalide.");
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Collaborateur introuvable."));
        if (Boolean.FALSE.equals(u.getAccountEnabled())) throw ApiException.badRequest("Ce collaborateur est déjà sorti des effectifs.");
        writeDeparture(u, reason, LocalDate.now(), comment, teamLeaderUsername);
    }

    /** Désactive le compte et trace la sortie (population / équipe du parcours au moment du départ). */
    private String writeDeparture(User u, String reason, LocalDate date, String comment, String recordedBy) {
        LocalDate when = date != null ? date : LocalDate.now();
        String cmt = comment == null || comment.isBlank() ? null : (comment.trim().length() > 500 ? comment.trim().substring(0, 500) : comment.trim());
        String country = country(u.getAffiliateBranch());
        Person p = people(country).stream().filter(x -> x.userId().equals(u.getId())).findFirst().orElse(null);
        u.setAccountEnabled(false);
        users.save(u);
        jdbc.update("INSERT INTO dbo.HrDepartures (UserId, Reason, DepartureDate, Comment, Population, HrTeam, CountryCode, RecordedBy) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                u.getId(), reason, java.sql.Date.valueOf(when), cmt, p == null ? null : p.population(), p == null ? null : p.team(), country, recordedBy);
        return country;
    }

    @Transactional(readOnly = true)
    public List<Departure> departures(AuthenticatedUser requester, String countryParam) {
        requireRead(requester);
        String country = normalizeCountry(countryParam);
        Map<Long, User> byId = new HashMap<>();
        users.findAll().forEach(u -> byId.put(u.getId(), u));
        List<Departure> out = new ArrayList<>();
        try {
            jdbc.query("SELECT * FROM dbo.HrDepartures WHERE CountryCode = ? ORDER BY DepartureDate DESC, DepartureId DESC", rs -> {
                User u = byId.get(rs.getLong("UserId"));
                java.sql.Timestamp re = rs.getTimestamp("ReintegratedAt");
                out.add(new Departure(rs.getLong("DepartureId"), rs.getLong("UserId"), u == null ? "#" + rs.getLong("UserId") : (u.getName() != null ? u.getName() : u.getUsername()),
                        u == null ? null : u.getUsername(), rs.getString("Reason"), rs.getDate("DepartureDate").toLocalDate(), rs.getString("Comment"),
                        rs.getString("Population"), rs.getString("HrTeam"), rs.getString("CountryCode"), rs.getString("RecordedBy"),
                        rs.getTimestamp("RecordedAt").toLocalDateTime(), re == null ? null : re.toLocalDateTime()));
            }, country);
        } catch (RuntimeException ignored) {
            // table pas encore créée
        }
        return out;
    }

    /** Réintégration : compte réactivé, la sortie reste dans l'historique (marquée réintégrée). */
    @Transactional
    public void reintegrate(AuthenticatedUser requester, Long departureId) {
        requireWrite(requester);
        List<Long> ids = jdbc.queryForList("SELECT UserId FROM dbo.HrDepartures WHERE DepartureId = ? AND ReintegratedAt IS NULL", Long.class, departureId);
        if (ids.isEmpty()) throw ApiException.notFound("Sortie introuvable ou déjà réintégrée.");
        User u = users.findById(ids.get(0)).orElseThrow(() -> ApiException.notFound("Collaborateur introuvable."));
        u.setAccountEnabled(true);
        users.save(u);
        jdbc.update("UPDATE dbo.HrDepartures SET ReintegratedAt = SYSUTCDATETIME(), ReintegratedBy = ? WHERE DepartureId = ?", requester.username(), departureId);
    }

    // ── Performance ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public HrPerformance performance(AuthenticatedUser requester, String countryParam, String month) {
        requireRead(requester);
        String country = normalizeCountry(countryParam);
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        Map<Long, Person> byId = new HashMap<>();
        for (Person p : people(country)) if (p.active()) byId.put(p.userId(), p);
        List<PerformanceResponse> rows = reporting.teamSummary(ym.atDay(1), ym.atEndOfMonth(), ym.toString(), null).stream()
                .filter(r -> r.userId() != null && byId.containsKey(r.userId())).toList();
        return new HrPerformance(country, ym.toString(), buildPerformance(rows, byId));
    }

    static List<TeamPerf> buildPerformance(List<PerformanceResponse> rows, Map<Long, Person> byId) {
        Map<String, List<AgentPerf>> byTeam = new LinkedHashMap<>();
        for (PopulationDef p : POPULATIONS) for (TeamDef t : p.teams()) byTeam.put(p.code() + "|" + t.code(), new ArrayList<>());
        for (PerformanceResponse r : rows) {
            Person p = byId.get(r.userId());
            if (p == null || p.team() == null) continue;
            byTeam.get(p.population() + "|" + p.team()).add(new AgentPerf(p.userId(), p.name(), p.population(), p.team(), r.presenceRate(),
                    r.avgQualityScore(), r.evaluationCount(), r.performanceGlobale(), r.kpiMetrics() == null ? Map.of() : r.kpiMetrics()));
        }
        List<TeamPerf> out = new ArrayList<>();
        byTeam.forEach((key, agents) -> {
            String[] k = key.split("\\|");
            agents.sort(Comparator.comparing(AgentPerf::name, String.CASE_INSENSITIVE_ORDER));
            out.add(new TeamPerf(k[0], k[1], teamLabel(k[0], k[1]), businessTeam(k[1]), agents.size(),
                    avg(agents.stream().map(AgentPerf::presence).toList()), avg(agents.stream().map(AgentPerf::qualityScore).toList()),
                    avg(agents.stream().map(AgentPerf::performance).toList()), agents.stream().mapToInt(AgentPerf::evaluations).sum(), agents));
        });
        return out;
    }

    private static Double avg(List<Double> values) {
        List<Double> v = values.stream().filter(Objects::nonNull).toList();
        return v.isEmpty() ? null : Math.round(v.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }
}
