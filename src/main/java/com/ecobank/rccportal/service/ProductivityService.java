package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.ProductivityAgentResponse;
import com.ecobank.rccportal.dto.ProductivityRankingEntry;
import com.ecobank.rccportal.model.ManualKpiEntry;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ManualKpiEntryRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Productivité — classement des agents sur une métrique de volume (interactions, dossiers
 * traités, ventes...), pour une période donnée. Aucune IA : agrégation locale pure sur les
 * saisies KPI déjà importées (ManualKpiEntry), même source de vérité que ReportingService.
 * Prend systématiquement la DERNIÈRE saisie (par CreatedAt) de chaque agent pour la période —
 * jamais de somme/moyenne sur plusieurs imports du même mois, cohérent avec PerformanceService.
 */
@Service
public class ProductivityService {

    /** Codes de métrique reconnus comme "productivité" — voir seedGameDefinitionsIfEmpty /
     *  PER_MONTH_SUBMETRIC_CODES pour la convention de nommage déjà en place dans les imports. */
    private static final List<String> DEFAULT_PRODUCTIVITY_CODES = List.of("PRODUCTIVITE", "INTERACTIONS", "CAS_CREES", "DOSSIERS_TRAITES");

    private final ManualKpiEntryRepository manualKpiEntryRepository;
    private final UserRepository userRepository;

    public ProductivityService(ManualKpiEntryRepository manualKpiEntryRepository, UserRepository userRepository) {
        this.manualKpiEntryRepository = manualKpiEntryRepository;
        this.userRepository = userRepository;
    }

    /** Historique de productivité d'un agent — une valeur par mois, code métrique choisi ou déduit. */
    @Transactional(readOnly = true)
    public ProductivityAgentResponse forAgent(String username, String metricCode) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Agent introuvable : " + username));

        List<ManualKpiEntry> entries = manualKpiEntryRepository.findBySubjectOrderByPeriodDateDesc(user);
        String resolvedCode = resolveMetricCode(entries, metricCode);

        // Dernière saisie par (mois, CreatedAt) — même règle que PerformanceService.latestMetricsInRange().
        Map<YearMonth, ManualKpiEntry> latestByMonth = new TreeMap<>();
        for (ManualKpiEntry e : entries) {
            if (!e.getMetricCode().equalsIgnoreCase(resolvedCode)) continue;
            YearMonth ym = YearMonth.from(e.getPeriodDate());
            ManualKpiEntry current = latestByMonth.get(ym);
            if (current == null || e.getCreatedAt().isAfter(current.getCreatedAt())) {
                latestByMonth.put(ym, e);
            }
        }

        List<ProductivityAgentResponse.MonthlyValue> history = latestByMonth.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> new ProductivityAgentResponse.MonthlyValue(en.getKey().toString(), en.getValue().getMetricValue()))
                .toList();

        return new ProductivityAgentResponse(user.getUsername(), user.getName(), resolvedCode, history);
    }

    /** Classement de l'équipe sur une métrique de productivité, pour un mois donné — meilleur en premier. */
    @Transactional(readOnly = true)
    public List<ProductivityRankingEntry> ranking(YearMonth month, String team, String metricCode) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        LocalDate periodStart = targetMonth.atDay(1);
        LocalDate periodEnd = targetMonth.atEndOfMonth();

        // Toutes les entrées de tous les agents, filtrées sur le mois demandé.
        List<ManualKpiEntry> allEntries = manualKpiEntryRepository.findAllByOrderByPeriodDateDesc().stream()
                .filter(e -> !e.getPeriodDate().isBefore(periodStart) && !e.getPeriodDate().isAfter(periodEnd))
                .toList();

        String resolvedCode = resolveMetricCode(allEntries, metricCode);

        // Dernière saisie par agent (par CreatedAt) pour ce code métrique précis.
        Map<Long, ManualKpiEntry> latestByAgent = new HashMap<>();
        for (ManualKpiEntry e : allEntries) {
            if (!e.getMetricCode().equalsIgnoreCase(resolvedCode)) continue;
            User agent = e.getSubject();
            if (team != null && !team.isBlank()
                    && TeamClassifier.classify(agent.getActivity()) != TeamClassifier.classify(team)) continue;
            ManualKpiEntry current = latestByAgent.get(agent.getId());
            if (current == null || e.getCreatedAt().isAfter(current.getCreatedAt())) {
                latestByAgent.put(agent.getId(), e);
            }
        }

        List<ProductivityRankingEntry> ranking = new ArrayList<>();
        for (ManualKpiEntry e : latestByAgent.values()) {
            User agent = e.getSubject();
            ranking.add(new ProductivityRankingEntry(
                    agent.getUsername(), agent.getName() != null ? agent.getName() : agent.getUsername(),
                    TeamClassifier.classify(agent.getActivity()).name(), e.getMetricValue(), 0));
        }
        ranking.sort((a, b) -> b.value().compareTo(a.value())); // meilleur en premier

        // Rang final attribué après tri (le record est immuable — reconstruit avec le bon rang).
        List<ProductivityRankingEntry> withRank = new ArrayList<>();
        for (int i = 0; i < ranking.size(); i++) {
            ProductivityRankingEntry r = ranking.get(i);
            withRank.add(new ProductivityRankingEntry(r.username(), r.fullName(), r.team(), r.value(), i + 1));
        }
        return withRank;
    }

    /** Si aucun code n'est fourni explicitement, déduit celui qui ressemble le plus à un volume de production. */
    private String resolveMetricCode(List<ManualKpiEntry> entries, String requestedCode) {
        if (requestedCode != null && !requestedCode.isBlank()) return requestedCode.trim().toUpperCase();
        Set<String> availableCodes = new HashSet<>();
        entries.forEach(e -> availableCodes.add(e.getMetricCode().toUpperCase()));
        for (String candidate : DEFAULT_PRODUCTIVITY_CODES) {
            if (availableCodes.contains(candidate)) return candidate;
        }
        // Repli — le premier code disponible plutôt qu'une erreur, jamais de résultat vide sans explication.
        return availableCodes.stream().findFirst().orElse("PRODUCTIVITE");
    }
}
