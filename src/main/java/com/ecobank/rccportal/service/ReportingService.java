package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vue d'équipe agrégée : les indicateurs de chaque agent (voir
 * PerformanceService, une seule source de calcul pour ne jamais avoir deux
 * chiffres différents pour la même donnée) pour un mois donné, plus un export
 * CSV des mêmes données.
 */
@Service
public class ReportingService {

    private static final List<String> KNOWN_METRIC_CODES = List.of(
            "INTERACTIONS", "PRODUCTIVITE", "SCORE_QA", "NOTE_QA", "TARGET", "SCORE",
            "CUMUL_INTERACTIONS", "CUMUL_PRODUCTIVITE", "CUMUL_SCORE_QA", "SCORE_EVALUATION"
    );

    private final UserRepository userRepository;
    private final PerformanceService performanceService;
    private final com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository;

    public ReportingService(UserRepository userRepository, PerformanceService performanceService,
                             com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.performanceService = performanceService;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public List<PerformanceResponse> teamSummary(YearMonth month) {
        return teamSummary(month, null);
    }

    /** countryCode optionnel — filtre sur une filiale précise (ex. "CI"), null = toutes filiales. */
    @Transactional(readOnly = true)
    public List<PerformanceResponse> teamSummary(YearMonth month, String countryCode) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        return teamSummary(targetMonth.atDay(1), targetMonth.atEndOfMonth(), targetMonth.toString(), countryCode);
    }

    private static final java.util.concurrent.ExecutorService TEAM_SUMMARY_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(8);

    /**
     * Version généralisée — même agrégation que teamSummary(YearMonth, String), mais sur une
     * plage de dates arbitraire (jour, semaine, mois...) — voir Portail Superviseur / Reporting,
     * sélecteur de granularité.
     *
     * Calcul PARALLÉLISÉ (8 threads) — computeFor() fait ~5 requêtes DB par agent ; en
     * séquentiel sur 150+ agents ça pouvait prendre plusieurs dizaines de secondes. Chaque
     * appel a sa propre transaction (computeFor() est @Transactional), donc sûr à paralléliser.
     */
    @Transactional(readOnly = true)
    public List<PerformanceResponse> teamSummary(java.time.LocalDate periodStart, java.time.LocalDate periodEnd,
                                                   String periodLabel, String countryCode) {
        List<User> users = userRepository.findAll().stream()
                .filter(u -> countryCode == null || countryCode.isBlank() || countryCode.equalsIgnoreCase(u.getAffiliateBranch()))
                .toList();

        List<java.util.concurrent.CompletableFuture<PerformanceResponse>> futures = users.stream()
                .map(u -> java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> performanceService.computeFor(u.getUsername(), periodStart, periodEnd, periodLabel),
                        TEAM_SUMMARY_EXECUTOR))
                .toList();

        List<PerformanceResponse> rows = futures.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .filter(row -> !isExcludedFromKpi(row))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        rows.sort(Comparator.comparing(PerformanceResponse::userFullName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return rows;
    }

    /**
     * Ne concerne que les agents qui prennent des appels/chats clients — Team Leader,
     * Superviseur (Head RCC), Superviseur QA, Quality Assurance, Formateur, Communication,
     * Admin/IT et RH ne sont jamais notés sur des KPI d'agent (interactions, productivité...),
     * qui n'auraient aucun sens pour eux. Exclus des écrans de reporting KPI (Superviseur,
     * Team Leader, page Reporting générale) — voir teamSummary().
     */
    private boolean isExcludedFromKpi(PerformanceResponse row) {
        String activity = row.activity() != null ? row.activity().toUpperCase() : "";
        String service = row.serviceName() != null ? row.serviceName().toUpperCase() : "";
        String[] excludedKeywords = { "QUALITY ASSURANCE", "SUPERVISEUR", "TEAM LEADER", "FORMATEUR", "COMMUNICATION" };
        for (String keyword : excludedKeywords) {
            if (activity.contains(keyword) || service.contains(keyword)) return true;
        }
        if (activity.equals("IT") || activity.equals("RH") || service.equals("IT") || service.equals("RH")) return true;

        if (row.userId() != null) {
            boolean isAdmin = userRoleRepository.findRolesByUserId(row.userId()).stream()
                    .anyMatch(ur -> ur.getRole() != null && "ADMIN".equalsIgnoreCase(ur.getRole().getName()));
            if (isAdmin) return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public String exportCsv(YearMonth month) {
        List<PerformanceResponse> rows = teamSummary(month);

        // N'inclut que les codes métriques réellement présents dans les données du mois,
        // dans un ordre stable — évite des colonnes vides pour des métriques jamais saisies.
        Set<String> presentCodes = new LinkedHashSet<>();
        for (String code : KNOWN_METRIC_CODES) {
            for (PerformanceResponse row : rows) {
                if (row.kpiMetrics().containsKey(code)) {
                    presentCodes.add(code);
                    break;
                }
            }
        }

        StringBuilder csv = new StringBuilder();
        csv.append("Username;Nom;Presence(%);ScoreQualite(%);NbEvaluations");
        for (String code : presentCodes) {
            csv.append(';').append(code);
        }
        csv.append(";PerformanceGlobale(%)\n");

        for (PerformanceResponse row : rows) {
            csv.append(csvEscape(row.username())).append(';')
                    .append(csvEscape(row.userFullName())).append(';')
                    .append(row.presenceRate()).append(';')
                    .append(row.avgQualityScore() != null ? row.avgQualityScore() : "").append(';')
                    .append(row.evaluationCount());
            for (String code : presentCodes) {
                Double value = row.kpiMetrics().get(code);
                csv.append(';').append(value != null ? value : "");
            }
            csv.append(';').append(row.performanceGlobale() != null ? row.performanceGlobale() : "");
            csv.append('\n');
        }

        return csv.toString();
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
