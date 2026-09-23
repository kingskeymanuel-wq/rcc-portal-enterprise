package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.DataAnalysisResponse;
import com.ecobank.rccportal.dto.PerformanceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyse de données IT — agrège les vraies statistiques du call center, par ÉQUIPE (le
 * service unique désormais étant "RCC", l'axe utile est l'équipe : Inbound, Outbound,
 * Digital...), puis demande à Anthropic Claude une analyse approfondie : causes réelles,
 * corrélations entre les indicateurs, priorisation, et plan d'action. L'IA ne voit JAMAIS
 * de donnée brute individuelle ni n'invente de chiffre — uniquement les agrégats déjà
 * calculés ci-dessous.
 *
 * Causes d'impact couvertes, toutes comptées depuis de vraies données (jamais estimées) :
 *  — absences (congés approuvés, ShiftService.approvedLeaveDates)
 *  — dépassements de pause / pause déjeuner (ShiftService.countPauseOverruns)
 *  — écart Interactions vs Target (rubriques réellement importées)
 * Explicitement PAS couvert : durée des appels (AHT) — aucune donnée de durée d'appel n'existe
 * dans le système actuellement (ni import KPI, ni évaluation QA n'en capture). Le narratif le
 * signale plutôt que d'inventer une cause.
 */
@Service
public class DataAnalysisService {

    private final ReportingService reportingService;
    private final ShiftService shiftService;
    private final com.ecobank.rccportal.repository.DataAnalysisSnapshotRepository snapshotRepository;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;

    public DataAnalysisService(ReportingService reportingService, ShiftService shiftService,
                               com.ecobank.rccportal.repository.DataAnalysisSnapshotRepository snapshotRepository,
                               com.ecobank.rccportal.repository.UserRepository userRepository) {
        this.reportingService = reportingService;
        this.shiftService = shiftService;
        this.snapshotRepository = snapshotRepository;
        this.userRepository = userRepository;
    }

    /** Historique des analyses déjà générées pour ce mois — le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.model.DataAnalysisSnapshot> history(YearMonth month) {
        return snapshotRepository.findByPeriodMonthOrderByCreatedAtDesc(
                (month != null ? month : YearMonth.now()).toString());
    }

    /**
     * Frise chronologique — le snapshot le plus récent de chaque mois, sur les monthsBack
     * derniers mois, pour une équipe donnée (team=null ou vide = vue globale toutes équipes).
     * Un mois sans analyse jamais générée est simplement absent (pas de valeur inventée).
     */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.model.DataAnalysisSnapshot> timeline(String team, int monthsBack) {
        YearMonth now = YearMonth.now();
        List<com.ecobank.rccportal.model.DataAnalysisSnapshot> out = new java.util.ArrayList<>();
        for (int i = monthsBack - 1; i >= 0; i--) {
            YearMonth m = now.minusMonths(i);
            List<com.ecobank.rccportal.model.DataAnalysisSnapshot> monthSnapshots =
                    snapshotRepository.findByPeriodMonthOrderByCreatedAtDesc(m.toString());
            monthSnapshots.stream()
                    .filter(s -> (team == null || team.isBlank()) ? s.getTeamFilter() == null : team.equalsIgnoreCase(s.getTeamFilter()))
                    .findFirst() // le plus récent pour ce mois (déjà trié desc)
                    .ifPresent(out::add);
        }
        return out;
    }

    /** Vue globale — toutes équipes confondues, avec le détail par équipe. */
    @Transactional(readOnly = true)
    public DataAnalysisResponse analyze(YearMonth month) {
        return analyze(month, null, null);
    }

    /** team non nul : ne conserve que cette équipe (mêmes chiffres, narratif recentré dessus). */
    @Transactional
    public DataAnalysisResponse analyze(YearMonth month, String team, String requesterUsername) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        return analyzeRange(targetMonth.atDay(1), targetMonth.atEndOfMonth(), targetMonth.toString(), targetMonth, team, requesterUsername, true);
    }

    /**
     * Granularité jour/semaine/mois — même agrégation, sur une plage de dates arbitraire (voir
     * Portail Superviseur/Team Leader, sélecteur Jour/Semaine/Mois). Le narratif référence
     * toujours un "mois" en interne (généré à partir du début de la plage) pour rester
     * cohérent avec le format existant, même si la plage réelle est plus courte qu'un mois.
     * Snapshot NON persisté pour une granularité jour/semaine — l'historique/les alertes
     * restent strictement mensuels, on ne les pollue pas avec des vues ponctuelles.
     */
    @Transactional
    public DataAnalysisResponse analyzeRange(java.time.LocalDate from, java.time.LocalDate to, String periodLabel,
                                              String team, String requesterUsername) {
        return analyzeRange(from, to, periodLabel, YearMonth.from(from), team, requesterUsername, false);
    }

    private DataAnalysisResponse analyzeRange(java.time.LocalDate periodStart, java.time.LocalDate periodEnd, String periodLabel,
                                               YearMonth narrativeMonth, String team, String requesterUsername, boolean persistSnapshot) {
        List<PerformanceResponse> allRows = reportingService.teamSummary(periodStart, periodEnd, periodLabel, null);
        List<PerformanceResponse> rows;
        if (team == null || team.isBlank()) {
            rows = allRows;
        } else {
            // Le tableau "Détail par équipe" recentre sur un libellé brut exact (flux admin
            // existant) ; le Team Leader passe un code canonique TeamClassifier ("OUTBOUND"...)
            // qui doit capter toutes les variantes réelles ("TELEVENDEUR", "AGENT DIGITAL"...).
            boolean isCanonicalTeamCode = isKnownTeamCode(team);
            rows = allRows.stream()
                    .filter(r -> isCanonicalTeamCode
                            ? team.equalsIgnoreCase(com.ecobank.rccportal.util.TeamClassifier.classify(r.activity()).name())
                            : team.equalsIgnoreCase(r.activity()))
                    .toList();
        }

        Double avgPresence = avg(rows, PerformanceResponse::presenceRate);
        Double avgQuality = avg(rows, PerformanceResponse::avgQualityScore);
        Double avgPerf = avg(rows, PerformanceResponse::performanceGlobale);

        Map<String, List<PerformanceResponse>> byTeam = rows.stream()
                .collect(Collectors.groupingBy(this::teamLabel));

        List<DataAnalysisResponse.TeamBreakdown> breakdown = byTeam.entrySet().stream()
                .map(e -> buildTeamBreakdown(e.getKey(), e.getValue(), periodStart, periodEnd))
                .sorted(Comparator.comparing(DataAnalysisResponse.TeamBreakdown::serviceName))
                .toList();

        String narrative;
        boolean aiAvailable = false; // IA retirée volontairement de l'Analyse de données — moteur de règles uniquement (rapide, aucune dépendance externe)
        boolean generatedByAi = false;
        if (rows.isEmpty()) {
            narrative = "Aucune donnée disponible pour cette période" + (team != null ? " et cette équipe" : "")
                    + " — importez d'abord des KPI ou attendez que les agents génèrent de l'activité.";
        } else {
            // Moteur de règles — seule source de la synthèse désormais (voir aiAvailable
            // ci-dessus), sur les vraies données agrégées, jamais un renvoi brut sans interprétation.
            narrative = generateRuleBasedNarrative(narrativeMonth, team, rows.size(), avgPresence, avgQuality, avgPerf, breakdown);
        }

        if (!rows.isEmpty() && persistSnapshot) {
            snapshotRepository.save(com.ecobank.rccportal.model.DataAnalysisSnapshot.builder()
                    .periodMonth(periodLabel)
                    .teamFilter(team)
                    .narrative(narrative)
                    .generatedByAi(generatedByAi)
                    .totalAgents(rows.size())
                    .avgPresenceRate(avgPresence)
                    .avgQualityScore(avgQuality)
                    .avgPerformanceGlobale(avgPerf)
                    .generatedByUsername(requesterUsername)
                    .build());
        }

        return new DataAnalysisResponse(narrative, periodLabel, rows.size(),
                avgPresence, avgQuality, avgPerf, breakdown, aiAvailable);
    }

    /** Liste des équipes distinctes — lit directement User.activity, sans jamais recalculer les KPI de personne (ce sélecteur n'a besoin que des libellés). */
    @Transactional(readOnly = true)
    public List<String> listTeamsWithData(YearMonth month) {
        return userRepository.findAll().stream()
                .map(u -> com.ecobank.rccportal.util.TeamClassifier.classify(u.getActivity()))
                .filter(t -> t != com.ecobank.rccportal.util.TeamClassifier.Team.OTHER)
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList();
    }

    private DataAnalysisResponse.TeamBreakdown buildTeamBreakdown(String teamName, List<PerformanceResponse> teamRows,
                                                                   java.time.LocalDate monthStart, java.time.LocalDate monthEnd) {
        int totalAbsenceDays = 0;
        int totalPauseOverruns = 0;
        int belowTargetCount = 0;
        int withTargetCount = 0;

        for (PerformanceResponse r : teamRows) {
            totalAbsenceDays += shiftService.approvedLeaveDates(r.username(), monthStart, monthEnd).size();
            totalPauseOverruns += shiftService.countPauseOverruns(r.username(), monthStart, monthEnd);

            Double interactions = r.kpiMetrics() != null ? r.kpiMetrics().get("INTERACTIONS") : null;
            Double target = r.kpiMetrics() != null ? r.kpiMetrics().get("TARGET") : null;
            if (interactions != null && target != null) {
                withTargetCount++;
                if (interactions < target) belowTargetCount++;
            }
        }

        Double pctBelowTarget = withTargetCount > 0 ? round2(100.0 * belowTargetCount / withTargetCount) : null;

        String teamCode = teamRows.isEmpty() ? null
                : com.ecobank.rccportal.util.TeamClassifier.classify(teamRows.get(0).activity()).name();

        return new DataAnalysisResponse.TeamBreakdown(
                teamName,
                teamCode,
                teamRows.size(),
                avg(teamRows, PerformanceResponse::presenceRate),
                avg(teamRows, PerformanceResponse::avgQualityScore),
                avg(teamRows, PerformanceResponse::performanceGlobale),
                avgMetric(teamRows, "INTERACTIONS"),
                totalAbsenceDays,
                totalPauseOverruns,
                pctBelowTarget
        );
    }

    /**
     * Analyse automatique par moteur de règles — utilisée dès que l'IA n'est pas configurée
     * (quality.ai.anthropic-key absent), sur EXACTEMENT les mêmes agrégats réels que
     * generateNarrative(). Aucun appel réseau, aucune dépendance externe : uniquement des
     * seuils métier appliqués aux vraies données déjà calculées ci-dessus. Reproduit la même
     * structure en 5 points pour rester lisible et comparable au format IA.
     */
    private String generateRuleBasedNarrative(YearMonth month, String team, int totalAgents, Double avgPresence,
                                               Double avgQuality, Double avgPerf, List<DataAnalysisResponse.TeamBreakdown> breakdown) {

        StringBuilder out = new StringBuilder();
        out.append("*Analyse automatique (moteur de règles Ecobank) — ")
                .append(month).append(team != null ? ", équipe " + team : "").append("*\n\n");

        // ---------- 1. Constat d'ensemble ----------
        out.append("**1. Constat d'ensemble**\n");
        out.append(totalAgents).append(" agent(s) avec données ce mois. ");
        out.append(presenceLabel(avgPresence)).append(" ");
        out.append(qualityLabel(avgQuality)).append(" ");
        out.append(perfLabel(avgPerf)).append("\n\n");

        if (breakdown.size() > 1) {
            out.append("**Points forts et points faibles — par équipe**\n");
            appendStrengthsWeaknesses(out, breakdown, "Performance", DataAnalysisResponse.TeamBreakdown::avgPerformanceGlobale);
            appendStrengthsWeaknesses(out, breakdown, "Présence", DataAnalysisResponse.TeamBreakdown::avgPresenceRate);
            appendStrengthsWeaknesses(out, breakdown, "Score qualité", DataAnalysisResponse.TeamBreakdown::avgQualityScore);
            out.append("\n");
        }

        if (breakdown.size() <= 1) {
            out.append("**2. Analyse par équipe**\nUne seule équipe dans ce périmètre — pas de comparaison possible.\n\n");
        } else {
            // ---------- 2. Analyse équipe par équipe ----------
            out.append("**2. Analyse équipe par équipe**\n");
            var byPerf = breakdown.stream()
                    .filter(t -> t.avgPerformanceGlobale() != null)
                    .sorted(Comparator.comparing(DataAnalysisResponse.TeamBreakdown::avgPerformanceGlobale))
                    .toList();
            if (!byPerf.isEmpty()) {
                var worst = byPerf.get(0);
                var best = byPerf.get(byPerf.size() - 1);
                out.append("Équipe la plus en difficulté : **").append(worst.serviceName()).append("** (performance ")
                        .append(fmt(worst.avgPerformanceGlobale())).append(", ").append(worst.agentCount()).append(" agents)");
                if (worst.totalAbsenceDays() > 0 || worst.totalPauseOverruns() > 0) {
                    out.append(" — ").append(worst.totalAbsenceDays()).append(" jour(s) d'absence cumulés et ")
                            .append(worst.totalPauseOverruns()).append(" dépassement(s) de pause enregistrés sur la période.");
                }
                out.append("\n");
                if (!best.serviceName().equals(worst.serviceName())) {
                    out.append("Équipe la plus solide : **").append(best.serviceName()).append("** (performance ")
                            .append(fmt(best.avgPerformanceGlobale())).append(").\n");
                }
            }
            out.append("\n");

            // ---------- 3. Cause racine priorisée ----------
            out.append("**3. Cause racine priorisée**\n");
            var ranked = breakdown.stream()
                    .sorted(Comparator.comparingDouble(this::impactScore).reversed())
                    .toList();
            if (!ranked.isEmpty() && impactScore(ranked.get(0)) > 0) {
                var top = ranked.get(0);
                String dominantCause = dominantCause(top);
                out.append("L'équipe **").append(top.serviceName()).append("** concentre le plus fort impact opérationnel, ")
                        .append("porté principalement par : ").append(dominantCause).append(". ");
                if (top.pctBelowTarget() != null && top.pctBelowTarget() >= 50) {
                    out.append(fmt(top.pctBelowTarget())).append(" des agents de cette équipe sont sous leur target ce mois — ")
                            .append("à croiser avec l'absentéisme/les dépassements de pause avant toute conclusion définitive.");
                }
                out.append("\n\n");
            } else {
                out.append("Aucune cause d'impact significative détectée (absences, dépassements de pause, écart target) sur la période.\n\n");
            }

            // ---------- 4. Plan d'action ----------
            out.append("**4. Plan d'action**\n");
            List<String> actions = new java.util.ArrayList<>();
            if (!ranked.isEmpty() && ranked.get(0).totalAbsenceDays() >= 5) {
                actions.add("Examiner les motifs d'absence de **" + ranked.get(0).serviceName() + "** avec le manager direct (quick win).");
            }
            if (!ranked.isEmpty() && ranked.get(0).totalPauseOverruns() >= 5) {
                actions.add("Rappeler les durées de pause standard à **" + ranked.get(0).serviceName() + "** et vérifier le pointage (quick win).");
            }
            if (!ranked.isEmpty() && ranked.get(0).pctBelowTarget() != null && ranked.get(0).pctBelowTarget() >= 50) {
                actions.add("Revoir la charge/target de **" + ranked.get(0).serviceName() + "** avec le superviseur (action de fond).");
            }
            if (avgQuality != null && avgQuality < 70) {
                actions.add("Renforcer le coaching qualité — score moyen sous le seuil critique de 70 % (action de fond).");
            }
            if (avgPresence != null && avgPresence < 85) {
                actions.add("Analyser les causes de présence sous 85 % avec les RH (action de fond).");
            }
            if (actions.isEmpty()) {
                actions.add("Aucune action corrective urgente identifiée — maintenir le suivi mensuel standard.");
            }
            actions.forEach(a -> out.append("- ").append(a).append("\n"));
            out.append("\n");
        }

        // ---------- 5. Point de vigilance ----------
        out.append("**5. Point de vigilance**\n");
        out.append("Analyse automatique par seuils métier — pas d'intelligence contextuelle : ");
        out.append("elle signale des écarts chiffrés, mais l'interprétation finale reste au superviseur/QA. ");
        out.append("Aucune donnée de durée d'appel (AHT) n'est disponible dans le portail. ");
        if (totalAgents < 5) {
            out.append("Échantillon réduit (").append(totalAgents).append(" agent(s)) : à interpréter avec prudence. ");
        }
        out.append("\n");

        return out.toString();
    }

    /** Score d'impact opérationnel simple — combine absences, dépassements de pause et écart target. */
    private double impactScore(DataAnalysisResponse.TeamBreakdown t) {
        return t.totalAbsenceDays() * 1.0 + t.totalPauseOverruns() * 1.5
                + (t.pctBelowTarget() != null ? t.pctBelowTarget() * 0.3 : 0);
    }

    private String dominantCause(DataAnalysisResponse.TeamBreakdown t) {
        double absenceWeight = t.totalAbsenceDays() * 1.0;
        double pauseWeight = t.totalPauseOverruns() * 1.5;
        double targetWeight = t.pctBelowTarget() != null ? t.pctBelowTarget() * 0.3 : 0;
        if (absenceWeight >= pauseWeight && absenceWeight >= targetWeight) {
            return t.totalAbsenceDays() + " jour(s) d'absence cumulés";
        }
        if (pauseWeight >= targetWeight) {
            return t.totalPauseOverruns() + " dépassement(s) de pause";
        }
        return "un écart important par rapport à la target (" + fmt(t.pctBelowTarget()) + " sous objectif)";
    }

    /** Classe les équipes sur une métrique donnée et affiche la meilleure (point fort) et la
     *  plus faible (point faible) — factuel, chiffré, jamais une appréciation vague. */
    private void appendStrengthsWeaknesses(StringBuilder out, List<DataAnalysisResponse.TeamBreakdown> breakdown,
                                            String label, java.util.function.Function<DataAnalysisResponse.TeamBreakdown, Double> extractor) {
        var ranked = breakdown.stream()
                .filter(t -> extractor.apply(t) != null)
                .sorted(Comparator.comparingDouble(t -> extractor.apply(t)))
                .toList();
        if (ranked.size() < 2) return;
        var weakest = ranked.get(0);
        var strongest = ranked.get(ranked.size() - 1);
        out.append("- ").append(label).append(" — 🟢 point fort : **").append(strongest.serviceName())
                .append("** (").append(fmt(extractor.apply(strongest))).append(") · 🔴 point faible : **")
                .append(weakest.serviceName()).append("** (").append(fmt(extractor.apply(weakest))).append(")\n");
    }

    private String presenceLabel(Double v) {
        if (v == null) return "Présence : donnée non disponible.";
        if (v >= 95) return "Présence excellente (" + fmt(v) + ").";
        if (v >= 85) return "Présence correcte (" + fmt(v) + ").";
        return "Présence en risque, sous le seuil de 85 % (" + fmt(v) + ").";
    }

    private String qualityLabel(Double v) {
        if (v == null) return "Qualité : aucune évaluation ce mois.";
        if (v >= 85) return "Score qualité bon (" + fmt(v) + ").";
        if (v >= 70) return "Score qualité à surveiller (" + fmt(v) + ").";
        return "Score qualité critique, sous 70 % (" + fmt(v) + ").";
    }

    private String perfLabel(Double v) {
        if (v == null) return "Performance globale : non calculable (Score QA manquant pour au moins un agent).";
        if (v >= 85) return "Performance globale solide (" + fmt(v) + ").";
        if (v >= 70) return "Performance globale moyenne (" + fmt(v) + ").";
        return "Performance globale faible, sous 70 % (" + fmt(v) + ").";
    }

    /**
     * Détail par agent d'une équipe (ou de tout le monde si team vide) — pour le clic
     * "voir les détails par agent" depuis le tableau de bord. Même logique de filtrage que
     * analyze() (libellé brut exact OU code canonique TeamClassifier).
     */
    @Transactional(readOnly = true)
    public List<PerformanceResponse> agentsForTeam(YearMonth month, String team) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        return agentsForRange(reportingService.teamSummary(targetMonth), team);
    }

    /** Granularité jour/semaine — même détail par agent, sur une plage de dates arbitraire. */
    @Transactional(readOnly = true)
    public List<PerformanceResponse> agentsForTeam(java.time.LocalDate from, java.time.LocalDate to, String periodLabel, String team) {
        return agentsForRange(reportingService.teamSummary(from, to, periodLabel, null), team);
    }

    private List<PerformanceResponse> agentsForRange(List<PerformanceResponse> allRows, String team) {
        if (team == null || team.isBlank()) return allRows;
        boolean isCanonicalTeamCode = isKnownTeamCode(team);
        return allRows.stream()
                .filter(r -> isCanonicalTeamCode
                        ? team.equalsIgnoreCase(com.ecobank.rccportal.util.TeamClassifier.classify(r.activity()).name())
                        : team.equalsIgnoreCase(r.activity()))
                .sorted(java.util.Comparator.comparing(PerformanceResponse::userFullName, java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** true si la chaîne correspond exactement à un des 5 codes TeamClassifier.Team (peu coûteux, 5 valeurs). */
    private boolean isKnownTeamCode(String value) {
        for (com.ecobank.rccportal.util.TeamClassifier.Team t : com.ecobank.rccportal.util.TeamClassifier.Team.values()) {
            if (t.name().equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    /** Libellé de regroupement — toujours l'une des 4 équipes canoniques (+ "Non classée"),
     *  jamais la valeur brute d'ACTIVITY : sinon "INBOUND VOICE" et "inbound voice" (deux
     *  saisies différentes du même champ) se retrouvaient comptées comme deux équipes
     *  distinctes, et la vue globale affichait une vingtaine de libellés illisibles. */
    private String teamLabel(PerformanceResponse r) {
        var team = com.ecobank.rccportal.util.TeamClassifier.classify(r.activity());
        return team == com.ecobank.rccportal.util.TeamClassifier.Team.OTHER ? "Non classée" : team.label;
    }

    private String fmt(Double v) {
        return v != null ? v + " %" : "non renseigné";
    }

    private Double avg(List<PerformanceResponse> rows, java.util.function.Function<PerformanceResponse, Double> extractor) {
        List<Double> values = rows.stream().map(extractor).filter(Objects::nonNull).toList();
        if (values.isEmpty()) return null;
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        return round2(sum / values.size());
    }

    private Double avgMetric(List<PerformanceResponse> rows, String metricCode) {
        List<Double> values = rows.stream()
                .map(r -> r.kpiMetrics() != null ? r.kpiMetrics().get(metricCode) : null)
                .filter(Objects::nonNull).toList();
        if (values.isEmpty()) return null;
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        return round2(sum / values.size());
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
