package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.dto.QualityEvaluationResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Performance par équipe avec les indicateurs PROPRES à chaque métier :
 * <ul>
 *   <li><b>Inbound Voix</b> : appels traités, DMT, taux de décroché, résolution au 1er contact, score des écoutes ;</li>
 *   <li><b>Inbound Mail / Tchat / Rafiki</b> : indicateurs du rapport hebdo — jours travaillés, appels sortants, CIS, mails
 *       assistés, sollicitations, total activités, moyenne / jour, target / jour, productivité, qualité ;</li>
 *   <li><b>Outbound</b> : appels émis, clients joints, taux de joignabilité, RDV, ventes, taux de transformation ;</li>
 *   <li><b>CIB</b> : interactions, cas créés, score QA.</li>
 * </ul>
 * Les valeurs viennent des KPI importés (codes reconnus sous leurs différentes appellations), des
 * évaluations QA (voix / écrit), du journal des appels de campagne et des ventes. La performance
 * globale reste celle du reporting (Score QA du mois importé), inchangée.
 */
@Service
public class TeamPerformanceService {

    /** unit : COUNT | RATE | PCT | SECONDS | MINUTES | SCORE ; alias : code KPI exact, « ~MOT1+MOT2 » (le code contient les deux)
     *  ou « @clé » (indicateur du fichier de performance de l'équipe, voir TeamPerfFileService). */
    public record Column(String key, String label, String unit, boolean higherIsBetter, String hint) {}

    public record Row(Long userId, String username, String name, String affiliateBranch, Map<String, Double> values,
                      Double presenceRate, Double performanceGlobale) {}

    public record TeamPerformance(String team, String teamLabel, String period, List<Column> columns, List<Row> rows,
                                  Map<String, Double> teamValues) {}

    record Def(Column column, List<String> aliases, String computed) {}

    private static Def kpi(String key, String label, String unit, boolean higher, String hint, String... aliases) {
        return new Def(new Column(key, label, unit, higher, hint), List.of(aliases), null);
    }

    private static Def computed(String key, String label, String unit, boolean higher, String hint, String computed, String... aliases) {
        return new Def(new Column(key, label, unit, higher, hint), List.of(aliases), computed);
    }

    private static final String[] INTERACTIONS = {"INTERACTIONS", "NOMBRE_INTERACTIONS", "NOMBRE_D_INTERACTIONS", "INTERACTIONS_TRAITEES"};
    private static final String[] SCORE_QA = {"SCORE_QA", "SCORE_QA_MOYEN", "SCORE_QA_MOYENNE", "NOTE_QA_MOYENNE", "SCORE_QUALITE", "SCORE_QUALITY", "QUALITY_SCORE", "NOTE_QUALITE"};

    private static String[] concat(String[] a, String... b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static final Map<TeamClassifier.Team, List<Def>> PROFILES = new EnumMap<>(TeamClassifier.Team.class);

    static {
        PROFILES.put(TeamClassifier.Team.INBOUND_VOICE, List.of(
                kpi("calls", "Appels traités", "COUNT", true, "Appels décrochés et traités",
                        concat(new String[]{"APPELS_TRAITES", "NOMBRE_APPELS", "NOMBRE_D_APPELS", "CALLS_HANDLED", "HANDLED_CALLS",
                                "APPELS_REPONDUS", "APPELS_DECROCHES", "~APPEL+TRAIT"}, INTERACTIONS)),
                kpi("aht", "DMT", "SECONDS", false, "Durée moyenne de traitement d'un appel",
                        "DMT", "AHT", "DUREE_MOYENNE_TRAITEMENT", "DUREE_MOYENNE_DE_TRAITEMENT", "TEMPS_MOYEN_TRAITEMENT", "~DUREE+MOYENNE", "~TEMPS+MOYEN+TRAITEMENT"),
                kpi("pickup", "Taux de décroché", "PCT", true, "Part des appels présentés qui ont été décrochés",
                        "TAUX_DECROCHE", "TAUX_DE_DECROCHE", "TAUX_DECROCHAGE", "TAUX_REPONSE", "~TAUX+DECROCH"),
                kpi("fcr", "Résolution 1er contact", "PCT", true, "FCR : demandes réglées dès le premier appel",
                        "FCR", "TAUX_FCR", "~RESOLUTION+PREMIER", "~FCR"),
                computed("qaVoice", "Score écoutes", "PCT", true, "Moyenne des écoutes QA (appels) de la période", "QA_VOICE", SCORE_QA),
                kpi("evaluation", "Évaluation", "PCT", true, "Score au module Évaluation", "SCORE_EVALUATION"),
                computed("presence", "Présence", "PCT", true, "Taux de présence (pointage)", "PRESENCE")));
        // Inbound Mail (Mails, Tchat, Rafiki) : indicateurs du rapport hebdo « Performances MAILS » (fichier importé par la QA,
        // alias « @clé »), repli sur les KPI importés sous leurs autres appellations.
        PROFILES.put(TeamClassifier.Team.INBOUND_MAIL, List.of(
                kpi("daysWorked", "Jours travaillés", "COUNT", true, "Jours travaillés sur la période", "@daysWorked", "JOURS_TRAVAILLES", "~JOUR+TRAVAIL"),
                kpi("outboundCalls", "Appels sortants", "COUNT", true, "Rappels clients passés", "@outboundCalls", "APPELS_SORTANT", "APPELS_SORTANTS"),
                kpi("cis", "CIS", "COUNT", true, "Dossiers CIS traités", "@cis", "CIS"),
                kpi("mails", "Mails assistés", "COUNT", true, "Mails traités / assistés",
                        concat(new String[]{"@mails", "MAILS_ASSIST", "MAILS_ASSISTES", "MAILS_TRAITES", "TICKETS_TRAITES", "~MAIL+ASSIST", "~MAIL+TRAIT"}, INTERACTIONS)),
                kpi("requests", "Sollicitations", "COUNT", true, "Sollicitations traitées (tchat, Rafiki…)", "@requests", "SOLLICITATIONS", "~SOLLICITATION"),
                kpi("totalActivities", "Total activités", "COUNT", true, "CIS + Mails assistés + Sollicitations", "@totalActivities", "TOTAL_ACTIVITES"),
                kpi("avgPerDay", "Moy / jour", "RATE", true, "Total activités ÷ jours travaillés", "@avgPerDay", "MOY_JOUR", "~MOY+JOUR"),
                kpi("targetPerDay", "Target / jour", "RATE", true, "Objectif journalier de l'agent", "@targetPerDay", "TARGET_JOUR"),
                kpi("productivity", "Productivité", "PCT", true, "Moy / jour ÷ Target / jour — vert ≥ 100 %, orange 90–99 %, rouge < 90 %",
                        "@productivity", "PRODUCTIVITE", "~PRODUCTIVIT"),
                computed("qaWritten", "Qualité", "PCT", true, "Qualité du rapport hebdo, sinon moyenne des évaluations écrites (mails / chat)", "QA_WRITTEN",
                        concat(new String[]{"@quality"}, SCORE_QA)),
                computed("presence", "Présence", "PCT", true, "Taux de présence (pointage)", "PRESENCE")));
        PROFILES.put(TeamClassifier.Team.OUTBOUND, List.of(
                computed("calls", "Appels émis", "COUNT", true, "Appels passés (campagnes du portail, sinon KPI importé)", "OUT_CALLS",
                        concat(new String[]{"APPELS_EMIS", "APPELS_PASSES", "NOMBRE_APPELS", "NOMBRE_D_APPELS", "~APPEL+EMIS", "~APPEL+PASS"}, INTERACTIONS)),
                computed("reached", "Clients joints", "COUNT", true, "Clients effectivement joints (interaction ou RDV)", "OUT_REACHED"),
                computed("reachRate", "Taux de joignabilité", "PCT", true, "Clients joints / appels émis", "OUT_REACH_RATE"),
                computed("appointments", "RDV pris", "COUNT", true, "Rendez-vous fixés", "OUT_APPOINTMENTS"),
                computed("sales", "Ventes", "COUNT", true, "Ventes enregistrées (hors annulées)", "OUT_SALES"),
                computed("conversion", "Taux de transformation", "PCT", true, "Ventes / clients joints", "OUT_CONVERSION"),
                computed("qaVoice", "Score écoutes", "PCT", true, "Moyenne des écoutes QA de la période", "QA_VOICE", SCORE_QA),
                computed("presence", "Présence", "PCT", true, "Taux de présence (pointage)", "PRESENCE")));
        PROFILES.put(TeamClassifier.Team.CIB, List.of(
                kpi("interactions", "Interactions", "COUNT", true, "Interactions traitées", INTERACTIONS),
                kpi("cases", "Cas créés", "COUNT", true, "Cas créés", "CAS_CREES", "NOMBRE_CAS_CREES"),
                kpi("responseTime", "Délai moyen de réponse", "MINUTES", false, "Temps moyen de réponse",
                        "DELAI_MOYEN_REPONSE", "DMR", "TEMPS_MOYEN_REPONSE", "~DELAI"),
                computed("qaVoice", "Score QA", "PCT", true, "Moyenne des évaluations QA de la période", "QA_ALL", SCORE_QA),
                computed("presence", "Présence", "PCT", true, "Taux de présence (pointage)", "PRESENCE")));
    }

    /** Canaux de l'Inbound Mail menés par leur propre Team Leader : indicateurs de leur rapport hebdo. */
    static final Map<String, List<Def>> CHANNEL_PROFILES = Map.of(
            "TCHAT", List.of(
                    kpi("liveChat", "Live Chat", "COUNT", true, "Conversations live chat traitées", "@liveChat", "LIVE_CHAT", "~LIVE+CHAT"),
                    kpi("otherActivities", "Autres activités", "COUNT", true, "Activités annexes", "@otherActivities", "AUTRES_ACTIVITES"),
                    kpi("totalProduction", "Prod globale", "COUNT", true, "Live Chat + autres activités", "@totalProduction", "PROD_GLOBALE"),
                    kpi("avgPerDay", "Prod moyenne / jour", "RATE", true, "Prod globale ÷ jours travaillés", "@avgPerDay", "PROD_MOYENNE"),
                    kpi("productivity", "Taux d'atteinte", "PCT", true, "Prod globale ÷ target hebdo — vert ≥ 100 %", "@productivity", "TX_ATTEINTE_TARGET"),
                    kpi("aht", "DMT", "SECONDS", false, "Durée moyenne de traitement d'une conversation", "@aht", "DMT", "AHT"),
                    computed("qaWritten", "Qualité", "PCT", true, "Qualité du rapport, sinon évaluations écrites", "QA_WRITTEN", concat(new String[]{"@quality"}, SCORE_QA)),
                    computed("presence", "Présence", "PCT", true, "Taux de présence (pointage)", "PRESENCE")),
            "RAFIKI", List.of(
                    kpi("resolved", "Conversations résolues", "COUNT", true, "Facebook, Instagram et X", "@resolved", "RESOLVED_CONVERSATIONS"),
                    kpi("otherActivities", "Activités annexes", "COUNT", true, "Activités annexes", "@otherActivities", "ACTIVITES_ANNEXES"),
                    kpi("totalProduction", "Performance globale", "COUNT", true, "Conversations résolues + activités annexes", "@totalProduction", "PERFORMANCE_GLOBALE"),
                    kpi("productivity", "Taux de productivité", "PCT", true, "Performance globale ÷ target hebdo — vert ≥ 100 %", "@productivity", "TAUX_DE_PRODUCTIVITE"),
                    kpi("firstResponse", "First Response Time", "SECONDS", false, "Délai avant la première réponse", "@firstResponse", "FIRST_RESPONSE_TIME"),
                    computed("qaWritten", "Qualité", "PCT", true, "Qualité du rapport, sinon évaluations écrites", "QA_WRITTEN", concat(new String[]{"@quality"}, SCORE_QA)),
                    computed("presence", "Présence", "PCT", true, "Taux de présence (pointage)", "PRESENCE")));

    private final ReportingService reporting;
    private final TeamLeaderService teamLeaders;
    private final QualityEvaluationService evaluations;
    private final JdbcTemplate jdbc;
    private final TeamPerfFileService perfFiles;

    public TeamPerformanceService(ReportingService reporting, TeamLeaderService teamLeaders, QualityEvaluationService evaluations, JdbcTemplate jdbc,
                                  TeamPerfFileService perfFiles) {
        this.reporting = reporting;
        this.teamLeaders = teamLeaders;
        this.evaluations = evaluations;
        this.jdbc = jdbc;
        this.perfFiles = perfFiles;
    }

    /** Colonnes (indicateurs) d'une équipe. */
    public static List<Column> columns(TeamClassifier.Team team) {
        return PROFILES.getOrDefault(team, PROFILES.get(TeamClassifier.Team.CIB)).stream().map(Def::column).toList();
    }

    private static boolean isManager(AuthenticatedUser u) {
        String role = u.role() == null ? "" : u.role().toUpperCase(Locale.ROOT);
        String service = u.service() == null ? "" : u.service().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return Set.of("ADMIN", "SUPERVISOR", "QA", "QA_SUPERVISOR", "RH").contains(role)
                || Set.of("SUPERVISEUR_QA", "SUPERVISEUR", "QUALITY_ASSURANCE").contains(service);
    }

    public TeamPerformance performance(AuthenticatedUser requester, String teamCode, String month, String from, String to, String countryCode) {
        if (requester == null) throw ApiException.unauthorized("Non connecté.");
        TeamClassifier.Team team;
        String channel = null;
        if (isManager(requester)) {
            String code = teamCode == null ? "" : teamCode.trim().toUpperCase(Locale.ROOT);
            if (TeamClassifier.isChannel(code)) { channel = code; team = TeamClassifier.Team.INBOUND_MAIL; }
            else try {
                team = TeamClassifier.Team.valueOf(code);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Équipe inconnue : INBOUND_VOICE, INBOUND_MAIL, TCHAT, RAFIKI, CIB ou OUTBOUND.");
            }
        } else if ("TEAM_LEADER".equalsIgnoreCase(requester.role())) {
            team = teamLeaders.requireLedTeam(requester); // un Team Leader ne voit que son équipe
            channel = teamLeaders.ledChannel(requester);  // … ou son canal (Tchat, Rafiki)
        } else {
            throw ApiException.forbidden("Réservé aux Team Leaders, au Superviseur, à la QA et à l'administrateur.");
        }
        if (team == TeamClassifier.Team.OTHER) throw ApiException.badRequest("Équipe inconnue.");

        LocalDate start, end;
        String label;
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            start = LocalDate.parse(from); end = LocalDate.parse(to);
            label = from.equals(to) ? from : from + " → " + to;
        } else {
            YearMonth ym = month != null && !month.isBlank() ? YearMonth.parse(month) : YearMonth.now();
            start = ym.atDay(1); end = ym.atEndOfMonth(); label = ym.toString();
        }
        Map<Long, List<String>> services = serviceCodes();
        final TeamClassifier.Team target = team;
        final String ch = channel;
        List<PerformanceResponse> base = reporting.teamSummary(start, end, label, countryCode).stream()
                .filter(r -> TeamClassifier.classify(r.activity(), services.getOrDefault(r.userId(), List.of())) == target)
                .filter(r -> ch == null || ch.equals(TeamClassifier.channel(r.activity(), services.getOrDefault(r.userId(), List.of()))))
                .toList();
        Sources src = sources(start, end);
        String sheetTeam = channel != null ? channel : team.name();
        src = perfFiles == null ? src : src.withSheet(perfFiles.aggregate(sheetTeam, start, end));
        return channel != null
                ? build(CHANNEL_PROFILES.get(channel), channel, "TCHAT".equals(channel) ? "Réseaux sociaux" : "Rafiki", false, label, base, src)
                : build(team, label, base, src);
    }

    /** Données calculées de la période : écoutes / écrits par agent, appels de campagne, ventes. */
    record Sources(Map<String, double[]> qaVoice, Map<String, double[]> qaWritten, Map<Long, long[]> outbound, Map<Long, Long> sales,
                   TeamPerfFileService.Aggregate sheet) {
        Sources(Map<String, double[]> qaVoice, Map<String, double[]> qaWritten, Map<Long, long[]> outbound, Map<Long, Long> sales) {
            this(qaVoice, qaWritten, outbound, sales, new TeamPerfFileService.Aggregate(Map.of(), Map.of()));
        }

        Sources withSheet(TeamPerfFileService.Aggregate s) {
            return new Sources(qaVoice, qaWritten, outbound, sales, s);
        }
    }

    Sources sources(LocalDate start, LocalDate end) {
        Map<String, double[]> voice = new HashMap<>(), written = new HashMap<>();
        for (QualityEvaluationResponse e : evaluations.listAll()) {
            if (e.agentMatricule() == null || e.evaluationDate() == null || e.evaluationDate().isBefore(start) || e.evaluationDate().isAfter(end)) continue;
            boolean isWritten = "CHAT".equalsIgnoreCase(e.channel()) || "MAIL".equalsIgnoreCase(e.channel());
            double[] acc = (isWritten ? written : voice).computeIfAbsent(e.agentMatricule().toLowerCase(Locale.ROOT), k -> new double[2]);
            acc[0] += e.scorePercentage(); acc[1]++;
        }
        Timestamp f = Timestamp.valueOf(start.atStartOfDay()), t = Timestamp.valueOf(end.plusDays(1).atStartOfDay());
        Map<Long, long[]> outbound = new HashMap<>(); // appels, joints, RDV
        try {
            jdbc.query("SELECT AgentUserId, COUNT(*) AS N, SUM(CASE WHEN CallStatus IN ('GREEN','YELLOW') THEN 1 ELSE 0 END) AS J, "
                            + "SUM(CASE WHEN CallStatus = 'YELLOW' THEN 1 ELSE 0 END) AS R FROM dbo.CampaignCallLogs "
                            + "WHERE AgentUserId IS NOT NULL AND CalledAt >= ? AND CalledAt < ? GROUP BY AgentUserId",
                    rs -> { outbound.put(rs.getLong("AgentUserId"), new long[]{rs.getLong("N"), rs.getLong("J"), rs.getLong("R")}); }, f, t);
        } catch (RuntimeException ignored) {
            // journal des appels absent
        }
        Map<Long, Long> sales = new HashMap<>();
        try {
            jdbc.query("SELECT AgentUserId, COUNT(*) AS N FROM dbo.SalesRecords WHERE Status <> 'CANCELLED' AND SaleDate >= ? AND SaleDate <= ? GROUP BY AgentUserId",
                    rs -> { sales.put(rs.getLong("AgentUserId"), rs.getLong("N")); }, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
        } catch (RuntimeException ignored) {
            // module ventes absent
        }
        return new Sources(voice, written, outbound, sales);
    }

    TeamPerformance build(TeamClassifier.Team team, String label, List<PerformanceResponse> base, Sources src) {
        return build(PROFILES.get(team), team.name(), team.label, team == TeamClassifier.Team.OUTBOUND, label, base, src);
    }

    TeamPerformance build(List<Def> defs, String teamCode, String teamLabel, boolean outbound, String label, List<PerformanceResponse> base, Sources src) {
        List<Row> rows = new ArrayList<>();
        for (PerformanceResponse r : base) {
            Map<String, Double> kpis = r.kpiMetrics() == null ? Map.of() : r.kpiMetrics();
            String user = r.username() == null ? "" : r.username().toLowerCase(Locale.ROOT);
            long[] out = r.userId() == null ? null : src.outbound().get(r.userId());
            Long sold = r.userId() == null ? null : src.sales().get(r.userId());
            // Fichier de performance de l'équipe (semaines de la période) : prioritaire sur le reste.
            Map<String, Double> sheet = src.sheet().find(r.userId(), r.userFullName() != null ? r.userFullName() : r.username());
            Map<String, Double> values = new LinkedHashMap<>();
            for (Def d : defs) {
                Double v = null;
                if (sheet != null) {
                    for (String a : d.aliases()) if (a.startsWith("@") && sheet.get(a.substring(1)) != null) { v = sheet.get(a.substring(1)); break; }
                }
                if (v == null && d.computed() != null) {
                    v = switch (d.computed()) {
                        case "PRESENCE" -> r.presenceRate();
                        case "QA_VOICE" -> avg(src.qaVoice().get(user));
                        case "QA_WRITTEN" -> avg(src.qaWritten().get(user));
                        case "QA_ALL" -> avg(merge(src.qaVoice().get(user), src.qaWritten().get(user)));
                        case "OUT_CALLS" -> out != null ? (double) out[0] : null;
                        case "OUT_REACHED" -> out != null ? (double) out[1] : null;
                        case "OUT_REACH_RATE" -> out != null && out[0] > 0 ? round1(out[1] * 100.0 / out[0]) : null;
                        case "OUT_APPOINTMENTS" -> out != null ? (double) out[2] : null;
                        case "OUT_SALES" -> sold != null ? (double) sold : (out != null ? 0.0 : null);
                        case "OUT_CONVERSION" -> out != null && out[1] > 0 ? round1((sold == null ? 0 : sold) * 100.0 / out[1]) : null;
                        default -> null;
                    };
                }
                if (v == null) v = fromKpis(kpis, d.aliases());
                values.put(d.column().key(), v);
            }
            rows.add(new Row(r.userId(), r.username(), r.userFullName() != null ? r.userFullName() : r.username(), r.affiliateBranch(),
                    values, r.presenceRate(), r.performanceGlobale()));
        }
        rows.sort(Comparator.comparing(Row::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        Map<String, Double> team_ = new LinkedHashMap<>();
        for (Def d : defs) {
            String key = d.column().key();
            List<Double> vals = rows.stream().map(x -> x.values().get(key)).filter(Objects::nonNull).toList();
            if (vals.isEmpty()) { team_.put(key, null); continue; }
            double sum = vals.stream().mapToDouble(Double::doubleValue).sum();
            team_.put(key, "COUNT".equals(d.column().unit()) ? round1(sum) : round1(sum / vals.size()));
        }
        // Taux d'équipe recalculés sur les totaux (plus justes qu'une moyenne de taux).
        if (outbound) {
            Double calls = team_.get("calls"), reached = team_.get("reached"), sales = team_.get("sales");
            if (calls != null && reached != null && calls > 0) team_.put("reachRate", round1(reached * 100 / calls));
            if (reached != null && reached > 0 && sales != null) team_.put("conversion", round1(sales * 100 / reached));
        }
        return new TeamPerformance(teamCode, teamLabel, label, defs.stream().map(Def::column).toList(), rows, team_);
    }

    /** Valeur d'un KPI importé sous l'une de ses appellations (code exact, puis « ~MOT1+MOT2 »). */
    static Double fromKpis(Map<String, Double> kpis, List<String> aliases) {
        if (kpis.isEmpty()) return null;
        for (String a : aliases) {
            if (a.startsWith("~") || a.startsWith("@")) continue;
            Double v = kpis.get(a);
            if (v != null) return v;
        }
        for (String a : aliases) {
            if (!a.startsWith("~")) continue;
            String[] words = a.substring(1).split("\\+");
            for (Map.Entry<String, Double> e : kpis.entrySet()) {
                String key = e.getKey().toUpperCase(Locale.ROOT);
                if (key.contains("OBJECTIF") || key.contains("TARGET") || key.contains("ANNEE_PRECEDENTE") || key.startsWith("CUMUL")) continue;
                if (Arrays.stream(words).allMatch(key::contains) && e.getValue() != null) return e.getValue();
            }
        }
        return null;
    }

    private Map<Long, List<String>> serviceCodes() {
        Map<Long, List<String>> out = new HashMap<>();
        try {
            jdbc.query("SELECT us.USER_ID, s.CODE FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID",
                    rs -> { out.computeIfAbsent(rs.getLong("USER_ID"), k -> new ArrayList<>()).add(rs.getString("CODE")); });
        } catch (RuntimeException ignored) {
            // services absents : classement par le champ Activité seul
        }
        return out;
    }

    private static double[] merge(double[] a, double[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new double[]{a[0] + b[0], a[1] + b[1]};
    }

    private static Double avg(double[] acc) {
        return acc == null || acc[1] == 0 ? null : round1(acc[0] / acc[1]);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
