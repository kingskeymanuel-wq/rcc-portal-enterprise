package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.Campaign;
import com.ecobank.rccportal.model.CampaignContact;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CampaignContactRepository;
import com.ecobank.rccportal.repository.CampaignRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Résultats d'une campagne pour le Team Leader : entonnoir (contacts → appelés → joints → formulaire complet →
 * qualifiés), synthèse question par question (répartition des choix, moyennes, NPS, grilles, classements, verbatims),
 * score des leads, performance par agent, activité jour par jour, et export CSV de toutes les réponses.
 */
@Service
public class CampaignResultsService {

    private final CampaignRepository campaigns;
    private final CampaignContactRepository contacts;
    private final UserRepository users;
    private final CampaignService campaignService;

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    public CampaignResultsService(CampaignRepository campaigns, CampaignContactRepository contacts, UserRepository users, CampaignService campaignService) {
        this.campaigns = campaigns;
        this.contacts = contacts;
        this.users = users;
        this.campaignService = campaignService;
    }

    void setJdbc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Row(CampaignContact contact, Map<String, String> answers, CampaignFormEngine.Evaluation eval) {}

    private List<Row> rows(Campaign c, JsonNode form) {
        List<Row> out = new ArrayList<>();
        for (CampaignContact ct : contacts.findByCampaignIdOrderByClientNameAsc(c.getCampaignId())) {
            Map<String, String> a = campaignService.deserializeAnswers(ct.getAnswersJson());
            out.add(new Row(ct, a, a.isEmpty() ? null : CampaignFormEngine.evaluate(form, a)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> results(AuthenticatedUser requester, Integer campaignId) {
        campaignService.requireCanManage(requester);
        Campaign c = campaigns.findById(campaignId).orElseThrow(() -> ApiException.notFound("Campagne introuvable."));
        JsonNode form = campaignService.formOf(c);
        List<Row> rows = rows(c, form);
        int threshold = form.path("settings").path("qualifiedThreshold").asInt(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", c.getCampaignId());
        out.put("name", c.getName());
        out.put("form", form);

        // Entonnoir.
        long total = rows.size();
        long assigned = rows.stream().filter(r -> r.contact().getAgentUserId() != null).count();
        long called = rows.stream().filter(r -> !"PENDING".equals(r.contact().getCallStatus()) || r.contact().getLastCalledAt() != null).count();
        long reached = rows.stream().filter(r -> Set.of("GREEN", "YELLOW").contains(r.contact().getCallStatus())).count();
        long completed = rows.stream().filter(r -> r.eval() != null && r.eval().valid() && r.eval().answered() > 0).count();
        long qualified = rows.stream().filter(r -> qualifiedRow(r, threshold)).count();
        out.put("funnel", List.of(step("Contacts", total), step("Assignés", assigned), step("Appelés", called), step("Joints", reached),
                step("Formulaire complet", completed), step("Qualifiés", qualified)));
        Map<String, Long> statuses = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "GREEN", "RED", "YELLOW")) statuses.put(s, rows.stream().filter(r -> s.equals(r.contact().getCallStatus())).count());
        out.put("statuses", statuses);
        out.put("responses", rows.stream().filter(r -> !r.answers().isEmpty()).count());

        // Score des leads.
        List<Integer> scores = rows.stream().map(r -> r.contact().getLeadScore() != null ? r.contact().getLeadScore() : r.eval() == null ? null : r.eval().scorePct())
                .filter(Objects::nonNull).toList();
        Map<String, Object> scoring = new LinkedHashMap<>();
        scoring.put("count", scores.size());
        scoring.put("average", scores.isEmpty() ? null : Math.round(scores.stream().mapToInt(i -> i).average().orElse(0)));
        scoring.put("threshold", threshold);
        int[] buckets = new int[5];
        scores.forEach(s -> buckets[Math.min(4, s / 20)]++);
        scoring.put("buckets", List.of(bucket("0–19", buckets[0]), bucket("20–39", buckets[1]), bucket("40–59", buckets[2]),
                bucket("60–79", buckets[3]), bucket("80–100", buckets[4])));
        out.put("scoring", scoring);

        // Question par question.
        List<Map<String, Object>> questions = new ArrayList<>();
        for (JsonNode q : CampaignFormEngine.questions(form)) {
            if ("STATEMENT".equals(q.path("type").asText())) continue;
            questions.add(questionStats(q, rows));
        }
        out.put("questions", questions);

        // Par agent.
        Map<Long, List<Row>> byAgent = rows.stream().filter(r -> r.contact().getAgentUserId() != null)
                .collect(Collectors.groupingBy(r -> r.contact().getAgentUserId(), LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> agents = new ArrayList<>();
        byAgent.forEach((id, list) -> {
            User u = users.findById(id).orElse(null);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("agentUserId", id);
            a.put("name", u == null ? "#" + id : u.getName() != null ? u.getName() : u.getUsername());
            a.put("assigned", list.size());
            long cl = list.stream().filter(r -> !"PENDING".equals(r.contact().getCallStatus())).count();
            long re = list.stream().filter(r -> Set.of("GREEN", "YELLOW").contains(r.contact().getCallStatus())).count();
            a.put("called", cl);
            a.put("reached", re);
            a.put("appointments", list.stream().filter(r -> "YELLOW".equals(r.contact().getCallStatus())).count());
            a.put("qualified", list.stream().filter(r -> qualifiedRow(r, threshold)).count());
            a.put("progressPct", list.isEmpty() ? 0 : Math.round(cl * 100f / list.size()));
            a.put("reachPct", cl == 0 ? null : Math.round(re * 100f / cl));
            OptionalDouble avg = list.stream().map(r -> r.contact().getLeadScore()).filter(Objects::nonNull).mapToInt(i -> i).average();
            a.put("avgScore", avg.isPresent() ? Math.round(avg.getAsDouble()) : null);
            agents.add(a);
        });
        agents.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("called")).longValue()));
        out.put("agents", agents);

        // Leads les mieux notés à traiter en priorité.
        out.put("topLeads", rows.stream().filter(r -> r.contact().getLeadScore() != null)
                .sorted(Comparator.comparing((Row r) -> -r.contact().getLeadScore())).limit(10)
                .map(r -> Map.<String, Object>of("contactId", r.contact().getContactId(), "client", r.contact().getClientName(),
                        "score", r.contact().getLeadScore(), "status", r.contact().getCallStatus(),
                        "suggested", r.eval() == null || r.eval().suggestedLabel() == null ? "" : r.eval().suggestedLabel()))
                .toList());

        out.put("daily", daily(c.getCampaignId()));
        return out;
    }

    private static boolean qualifiedRow(Row r, int threshold) {
        if (r.eval() == null) return false;
        if (threshold > 0 && r.eval().scorePct() != null) return r.eval().scorePct() >= threshold;
        return Set.of("GREEN", "YELLOW").contains(r.eval().suggestedStatus());
    }

    private static Map<String, Object> step(String label, long n) { return Map.of("label", label, "count", n); }

    private static Map<String, Object> bucket(String label, int n) { return Map.of("label", label, "count", n); }

    Map<String, Object> questionStats(JsonNode q, List<Row> rows) {
        String id = q.path("id").asText(), type = q.path("type").asText();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", q.path("label").asText());
        m.put("type", type);
        List<String> values = rows.stream().map(r -> r.answers().get(id)).filter(v -> v != null && !v.isBlank()).toList();
        m.put("answered", values.size());
        switch (type) {
            case "SINGLE", "DROPDOWN", "YES_NO", "MULTIPLE" -> {
                Map<String, Integer> counts = new LinkedHashMap<>();
                q.path("options").forEach(o -> counts.put(o.path("label").asText(), 0));
                for (String v : values) {
                    List<String> picked = "MULTIPLE".equals(type) ? Optional.ofNullable(CampaignFormEngine.list(v)).orElse(List.of(v)) : List.of(v);
                    for (String p : picked) {
                        JsonNode o = CampaignFormEngine.findOption(q, p);
                        String key = o != null ? o.path("label").asText() : "Autre";
                        counts.merge(key, 1, Integer::sum);
                    }
                }
                m.put("options", counts.entrySet().stream().map(e -> Map.of("label", e.getKey(), "count", e.getValue(),
                        "pct", values.isEmpty() ? 0 : Math.round(e.getValue() * 100f / values.size()))).toList());
            }
            case "SCALE", "RATING", "NPS", "NUMBER", "AMOUNT" -> {
                List<BigDecimal> nums = values.stream().map(CampaignFormEngine::decimal).filter(Objects::nonNull).toList();
                if (!nums.isEmpty()) {
                    BigDecimal sum = nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    m.put("average", sum.divide(BigDecimal.valueOf(nums.size()), 2, RoundingMode.HALF_UP));
                    m.put("min", nums.stream().min(Comparator.naturalOrder()).orElse(null));
                    m.put("max", nums.stream().max(Comparator.naturalOrder()).orElse(null));
                    if ("AMOUNT".equals(type)) m.put("total", sum);
                    List<BigDecimal> sorted = nums.stream().sorted().toList();
                    m.put("median", sorted.get(sorted.size() / 2));
                }
                if (!Set.of("NUMBER", "AMOUNT").contains(type)) {
                    int min = "NPS".equals(type) ? 0 : q.path("scale").path("min").asInt(1), max = "NPS".equals(type) ? 10 : q.path("scale").path("max").asInt(5);
                    List<Map<String, Object>> dist = new ArrayList<>();
                    for (int i = min; i <= max; i++) {
                        final int k = i;
                        dist.add(Map.of("value", k, "count", nums.stream().filter(n -> n.intValue() == k).count()));
                    }
                    m.put("distribution", dist);
                }
                if ("NPS".equals(type) && !nums.isEmpty()) {
                    long pro = nums.stream().filter(n -> n.intValue() >= 9).count(), det = nums.stream().filter(n -> n.intValue() <= 6).count();
                    m.put("nps", Map.of("promoters", pro, "passives", nums.size() - pro - det, "detractors", det,
                            "score", Math.round((pro - det) * 100f / nums.size())));
                }
            }
            case "MATRIX" -> {
                List<Map<String, Object>> rowsOut = new ArrayList<>();
                for (JsonNode r : q.path("rows")) {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    q.path("options").forEach(o -> counts.put(o.path("label").asText(), 0));
                    for (String v : values) {
                        Map<String, String> mm = CampaignFormEngine.map(v);
                        String col = mm == null ? null : mm.get(r.path("label").asText());
                        if (col != null) counts.merge(col, 1, Integer::sum);
                    }
                    rowsOut.add(Map.of("row", r.path("label").asText(), "counts", counts));
                }
                m.put("matrix", rowsOut);
            }
            case "RANKING" -> {
                Map<String, double[]> ranks = new LinkedHashMap<>();
                q.path("options").forEach(o -> ranks.put(o.path("label").asText(), new double[2]));
                for (String v : values) {
                    List<String> order = CampaignFormEngine.list(v);
                    if (order == null) continue;
                    for (int i = 0; i < order.size(); i++) {
                        double[] acc = ranks.get(order.get(i));
                        if (acc != null) { acc[0] += i + 1; acc[1]++; }
                    }
                }
                m.put("ranking", ranks.entrySet().stream().filter(e -> e.getValue()[1] > 0)
                        .sorted(Comparator.comparingDouble(e -> e.getValue()[0] / e.getValue()[1]))
                        .map(e -> Map.of("label", e.getKey(), "avgRank", Math.round(e.getValue()[0] / e.getValue()[1] * 10) / 10.0)).toList());
            }
            default -> {
                // Texte, dates, contacts : dernières réponses et mots les plus fréquents.
                List<String> latest = rows.stream().filter(r -> r.answers().get(id) != null && !r.answers().get(id).isBlank())
                        .sorted(Comparator.comparing((Row r) -> r.contact().getLastCalledAt() == null ? java.time.LocalDateTime.MIN : r.contact().getLastCalledAt()).reversed())
                        .limit(15).map(r -> r.answers().get(id)).toList();
                m.put("latest", latest);
                if (Set.of("SHORT_TEXT", "LONG_TEXT").contains(type)) m.put("keywords", keywords(values));
            }
        }
        return m;
    }

    private static final Set<String> STOP = Set.of("le", "la", "les", "de", "des", "du", "un", "une", "et", "a", "au", "aux", "en", "pour", "pas",
            "ne", "que", "qui", "il", "elle", "je", "on", "est", "sont", "client", "avec", "sur", "dans", "par", "plus", "mais", "ou", "ce", "se", "sa",
            "son", "ses", "y", "d", "l", "n", "c", "j", "s", "qu", "the", "of", "to", "veut", "tres");

    static List<Map<String, Object>> keywords(List<String> values) {
        Map<String, Integer> freq = new HashMap<>();
        for (String v : values) {
            for (String w : CampaignFormTemplates.fold(v).split("[^a-z0-9]+")) {
                if (w.length() < 3 || STOP.contains(w)) continue;
                freq.merge(w, 1, Integer::sum);
            }
        }
        return freq.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                .map(e -> Map.<String, Object>of("word", e.getKey(), "count", e.getValue())).toList();
    }

    private List<Map<String, Object>> daily(Integer campaignId) {
        if (jdbc == null) return List.of();
        try {
            List<Map<String, Object>> raw = jdbc.queryForList("""
                    SELECT CAST(CalledAt AS DATE) AS D, CallStatus AS S, COUNT(*) AS N FROM dbo.CampaignCallLogs
                    WHERE CampaignId = ? AND CalledAt >= DATEADD(DAY, -45, SYSDATETIME()) GROUP BY CAST(CalledAt AS DATE), CallStatus ORDER BY D""", campaignId);
            Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
            for (Map<String, Object> r : raw) {
                String d = String.valueOf(r.get("D"));
                Map<String, Object> day = byDay.computeIfAbsent(d, k -> new LinkedHashMap<>(Map.of("date", k, "GREEN", 0L, "RED", 0L, "YELLOW", 0L, "PENDING", 0L)));
                day.put(String.valueOf(r.get("S")), ((Number) r.get("N")).longValue());
            }
            return new ArrayList<>(byDay.values());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ───────────── Export CSV (séparateur « ; », lisible directement dans Excel) ─────────────

    @Transactional(readOnly = true)
    public byte[] exportCsv(AuthenticatedUser requester, Integer campaignId) {
        campaignService.requireCanManage(requester);
        Campaign c = campaigns.findById(campaignId).orElseThrow(() -> ApiException.notFound("Campagne introuvable."));
        JsonNode form = campaignService.formOf(c);
        List<JsonNode> qs = CampaignFormEngine.questions(form).stream().filter(q -> !"STATEMENT".equals(q.path("type").asText())).toList();
        Map<Long, String> agentNames = new HashMap<>();
        StringBuilder sb = new StringBuilder("﻿");
        List<String> head = new ArrayList<>(List.of("Client", "Téléphone", "Compte", "Agent", "Statut", "Dernier appel", "Score du lead", "Issue suggérée", "Commentaire"));
        qs.forEach(q -> head.add(q.path("label").asText()));
        sb.append(head.stream().map(CampaignResultsService::csv).collect(Collectors.joining(";"))).append("\r\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        for (Row r : rows(c, form)) {
            CampaignContact ct = r.contact();
            String agent = ct.getAgentUserId() == null ? "" : agentNames.computeIfAbsent(ct.getAgentUserId(),
                    id -> users.findById(id).map(u -> u.getName() != null ? u.getName() : u.getUsername()).orElse(""));
            List<String> line = new ArrayList<>(List.of(nz(ct.getClientName()), nz(ct.getClientPhone()), nz(ct.getMaskedAccountNumber()), agent,
                    STATUS_LABELS.getOrDefault(ct.getCallStatus(), ct.getCallStatus()), ct.getLastCalledAt() == null ? "" : ct.getLastCalledAt().format(fmt),
                    ct.getLeadScore() == null ? "" : ct.getLeadScore() + " %", r.eval() == null || r.eval().suggestedLabel() == null ? "" : r.eval().suggestedLabel(),
                    nz(ct.getNotes())));
            for (JsonNode q : qs) line.add(readable(q, r.answers().get(q.path("id").asText())));
            sb.append(line.stream().map(CampaignResultsService::csv).collect(Collectors.joining(";"))).append("\r\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static final Map<String, String> STATUS_LABELS = Map.of("PENDING", "À appeler", "GREEN", "Interaction", "RED", "Pas de réponse", "YELLOW", "RDV pris");

    /** Valeur lisible : listes jointes par « , », grille « ligne : colonne ». */
    static String readable(JsonNode q, String v) {
        if (v == null) return "";
        String type = q.path("type").asText();
        if (Set.of("MULTIPLE", "RANKING").contains(type)) {
            List<String> l = CampaignFormEngine.list(v);
            return l == null ? v : String.join(", ", l);
        }
        if ("MATRIX".equals(type)) {
            Map<String, String> m = CampaignFormEngine.map(v);
            return m == null ? v : m.entrySet().stream().map(e -> e.getKey() + " : " + e.getValue()).collect(Collectors.joining(" | "));
        }
        if ("CONSENT".equals(type)) return "true".equalsIgnoreCase(v) ? "Accepté" : "";
        return v;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static String csv(String s) {
        String v = s == null ? "" : s.replace("\r", " ").replace("\n", " ");
        // Pas de formule exécutée à l'ouverture dans Excel ; un numéro « +225… » ou un montant « -5 » reste tel quel.
        if (!v.isEmpty() && ("=@".indexOf(v.charAt(0)) >= 0 || ("+-".indexOf(v.charAt(0)) >= 0 && !v.matches("[+-][\\d\\s.,]+")))) v = "'" + v;
        return v.contains(";") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    static String today() { return LocalDate.now().toString(); }
}
