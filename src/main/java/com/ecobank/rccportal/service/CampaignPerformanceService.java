package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CampaignFieldDto;
import com.ecobank.rccportal.model.Campaign;
import com.ecobank.rccportal.model.CampaignContact;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CampaignContactRepository;
import com.ecobank.rccportal.repository.CampaignRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Suivi de performance d'une campagne Outbound pour le Team Leader : avancement global, par
 * agent (contacts affectés, appelés, joints, RDV, sans réponse, appels de la période), activité
 * jour par jour (journal dbo.CampaignCallLogs : appels passés dans le portail + historique des
 * fichiers importés) et répartition des réponses du questionnaire.
 */
@Service
public class CampaignPerformanceService {

    public record AgentPerf(Long agentId, String name, int assigned, int called, int reached, int appointments, int noAnswer,
                            int pending, int callsInPeriod, LocalDateTime lastActivity) {}

    public record DayPoint(LocalDate day, int calls, int reached) {}

    public record OptionCount(String option, int count) {}

    public record QuestionStat(String id, String label, String type, int answered, List<OptionCount> options) {}

    public record Performance(String campaignName, int total, int called, int reached, int appointments, int noAnswer, int pending,
                              int unassigned, int callsInPeriod, Integer days, List<AgentPerf> agents, List<DayPoint> daily,
                              List<QuestionStat> questions) {}

    private final CampaignService campaigns;
    private final CampaignRepository campaignRepository;
    private final CampaignContactRepository contactRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    public CampaignPerformanceService(CampaignService campaigns, CampaignRepository campaignRepository,
                                      CampaignContactRepository contactRepository, UserRepository userRepository, JdbcTemplate jdbc) {
        this.campaigns = campaigns;
        this.campaignRepository = campaignRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
    }

    record LogRow(Long agentId, LocalDate day, String status, int count) {}

    @Transactional(readOnly = true)
    public Performance performance(AuthenticatedUser requester, Integer campaignId, Integer days) {
        campaigns.requireCanManage(requester);
        Campaign campaign = campaignRepository.findById(campaignId).orElseThrow(() -> ApiException.notFound("Campagne introuvable."));
        List<CampaignContact> contacts = contactRepository.findByCampaignIdOrderByClientNameAsc(campaignId);
        Integer period = days == null || days <= 0 ? null : Math.min(days, 365);
        LocalDateTime from = period == null ? LocalDateTime.of(2000, 1, 1, 0, 0) : LocalDate.now().minusDays(period - 1L).atStartOfDay();

        List<LogRow> logs;
        try {
            logs = jdbc.query("SELECT AgentUserId, CAST(CalledAt AS DATE) AS D, CallStatus, COUNT(*) AS N FROM dbo.CampaignCallLogs "
                            + "WHERE CampaignId = ? AND CalledAt >= ? GROUP BY AgentUserId, CAST(CalledAt AS DATE), CallStatus",
                    (rs, i) -> new LogRow((Long) rs.getObject("AgentUserId", Long.class), rs.getDate("D").toLocalDate(), rs.getString("CallStatus"), rs.getInt("N")),
                    campaignId, java.sql.Timestamp.valueOf(from));
        } catch (RuntimeException e) {
            logs = List.of(); // journal pas encore créé : les indicateurs d'état restent disponibles
        }
        return build(campaign, contacts, logs, period);
    }

    Performance build(Campaign campaign, List<CampaignContact> contacts, List<LogRow> logs, Integer period) {
        int total = contacts.size(), called = 0, reached = 0, rdv = 0, noAnswer = 0, pending = 0, unassigned = 0;
        Map<Long, int[]> perAgent = new LinkedHashMap<>(); // assigned, called, reached, rdv, noAnswer, pending, callsInPeriod
        Map<Long, LocalDateTime> lastActivity = new HashMap<>();
        for (CampaignContact c : contacts) {
            String s = c.getCallStatus() == null ? "PENDING" : c.getCallStatus();
            boolean wasCalled = !"PENDING".equals(s) || c.getLastCalledAt() != null;
            if (wasCalled) called++;
            switch (s) {
                case "GREEN" -> reached++;
                case "YELLOW" -> { reached++; rdv++; }
                case "RED" -> noAnswer++;
                default -> pending++;
            }
            if (c.getAgentUserId() == null) { unassigned++; continue; }
            int[] a = perAgent.computeIfAbsent(c.getAgentUserId(), k -> new int[7]);
            a[0]++;
            if (wasCalled) a[1]++;
            if ("GREEN".equals(s) || "YELLOW".equals(s)) a[2]++;
            if ("YELLOW".equals(s)) a[3]++;
            if ("RED".equals(s)) a[4]++;
            if ("PENDING".equals(s)) a[5]++;
            if (c.getLastCalledAt() != null) lastActivity.merge(c.getAgentUserId(), c.getLastCalledAt(), (x, y) -> x.isAfter(y) ? x : y);
        }

        Map<LocalDate, int[]> daily = new TreeMap<>();
        int callsInPeriod = 0;
        for (LogRow l : logs) {
            callsInPeriod += l.count();
            int[] d = daily.computeIfAbsent(l.day(), k -> new int[2]);
            d[0] += l.count();
            if ("GREEN".equals(l.status()) || "YELLOW".equals(l.status())) d[1] += l.count();
            if (l.agentId() != null) perAgent.computeIfAbsent(l.agentId(), k -> new int[7])[6] += l.count();
        }
        List<DayPoint> series = new ArrayList<>();
        if (!daily.isEmpty()) {
            LocalDate first = ((TreeMap<LocalDate, int[]>) daily).firstKey(), last = ((TreeMap<LocalDate, int[]>) daily).lastKey();
            if (first.plusDays(180).isBefore(last)) first = last.minusDays(180);
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                int[] v = daily.getOrDefault(d, new int[2]);
                series.add(new DayPoint(d, v[0], v[1]));
            }
        }

        Map<Long, String> names = new HashMap<>();
        userRepository.findAllById(perAgent.keySet()).forEach(u -> names.put(u.getId(), label(u)));
        List<AgentPerf> agents = new ArrayList<>();
        perAgent.forEach((id, a) -> agents.add(new AgentPerf(id, names.getOrDefault(id, "Agent #" + id), a[0], a[1], a[2], a[3], a[4], a[5], a[6], lastActivity.get(id))));
        agents.sort(Comparator.comparingInt(AgentPerf::reached).reversed().thenComparing(Comparator.comparingInt(AgentPerf::called).reversed()));

        List<QuestionStat> questions = new ArrayList<>();
        List<CampaignFieldDto> fields = campaigns.deserializeFields(campaign.getFieldsJson());
        List<Map<String, String>> allAnswers = contacts.stream().map(c -> campaigns.deserializeAnswers(c.getAnswersJson())).toList();
        for (CampaignFieldDto f : fields) {
            int answered = 0;
            Map<String, Integer> counts = new LinkedHashMap<>();
            boolean choice = ("SELECT".equals(f.type()) || "RADIO".equals(f.type())) && f.options() != null && !f.options().isEmpty();
            if (choice) f.options().forEach(o -> counts.put(o, 0));
            for (Map<String, String> ans : allAnswers) {
                String v = ans.get(f.id());
                if (v == null || v.isBlank()) continue;
                answered++;
                if (choice) counts.merge(counts.containsKey(v) ? v : "Autres réponses", 1, Integer::sum);
            }
            List<OptionCount> options = counts.entrySet().stream().filter(e -> e.getValue() > 0)
                    .sorted((x, y) -> y.getValue() - x.getValue()).map(e -> new OptionCount(e.getKey(), e.getValue())).toList();
            questions.add(new QuestionStat(f.id(), f.label(), f.type(), answered, options));
        }
        return new Performance(campaign.getName(), total, called, reached, rdv, noAnswer, pending, unassigned, callsInPeriod, period,
                agents, series, questions);
    }

    private static String label(User u) {
        return u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername();
    }
}
