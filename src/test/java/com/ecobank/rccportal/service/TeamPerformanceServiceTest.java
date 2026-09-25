package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.TeamClassifier;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TeamPerformanceServiceTest {

    private final TeamPerformanceService svc = new TeamPerformanceService(null, null, null, null);

    private static PerformanceResponse agent(long id, String username, Map<String, Double> kpis, double presence) {
        return new PerformanceResponse(username, username.toUpperCase(), "2026-09", 0, null, kpis, presence, 80.0, "CI", null, null, id);
    }

    @Test
    void eachTeamHasItsOwnIndicators() {
        List<String> voice = TeamPerformanceService.columns(TeamClassifier.Team.INBOUND_VOICE).stream().map(TeamPerformanceService.Column::label).toList();
        List<String> mail = TeamPerformanceService.columns(TeamClassifier.Team.INBOUND_MAIL).stream().map(TeamPerformanceService.Column::label).toList();
        List<String> out = TeamPerformanceService.columns(TeamClassifier.Team.OUTBOUND).stream().map(TeamPerformanceService.Column::label).toList();
        assertTrue(voice.containsAll(List.of("Appels traités", "DMT", "Taux de décroché")));
        assertTrue(mail.containsAll(List.of("Mails / tickets traités", "Délai moyen de réponse", "Respect du SLA", "Score QA écrit")));
        assertTrue(out.containsAll(List.of("Appels émis", "Clients joints", "Taux de joignabilité", "RDV pris", "Ventes", "Taux de transformation")));
        assertFalse(voice.contains("Ventes"));
        assertFalse(mail.contains("DMT"));
    }

    @Test
    void importedKpisAreFoundUnderTheirDifferentNames() {
        assertEquals(412.0, TeamPerformanceService.fromKpis(Map.of("MAILS_TRAITES", 412.0), List.of("MAILS_TRAITES", "INTERACTIONS")));
        assertEquals(95.5, TeamPerformanceService.fromKpis(Map.of("TAUX_RESPECT_DU_SLA", 95.5, "OBJECTIF_SLA", 90.0), List.of("RESPECT_SLA", "~SLA")));
        assertEquals(180.0, TeamPerformanceService.fromKpis(Map.of("DUREE_MOYENNE_APPEL", 180.0), List.of("DMT", "~DUREE+MOYENNE")));
        assertNull(TeamPerformanceService.fromKpis(Map.of("OBJECTIF_SLA", 90.0), List.of("~SLA")));   // un objectif n'est pas une réalisation
    }

    @Test
    void outboundIndicatorsComeFromCallsAndSales() {
        var src = new TeamPerformanceService.Sources(Map.of("yao", new double[]{160, 2}), Map.of(),
                Map.of(5L, new long[]{200, 80, 12}, 6L, new long[]{100, 20, 3}), Map.of(5L, 16L));
        var p = svc.build(TeamClassifier.Team.OUTBOUND, "2026-09",
                List.of(agent(5, "yao", Map.of(), 95), agent(6, "awa", Map.of("SCORE_QA", 70.0), 88)), src);
        var yao = p.rows().stream().filter(r -> r.username().equals("yao")).findFirst().orElseThrow().values();
        assertEquals(200.0, yao.get("calls"));
        assertEquals(40.0, yao.get("reachRate"));
        assertEquals(12.0, yao.get("appointments"));
        assertEquals(16.0, yao.get("sales"));
        assertEquals(20.0, yao.get("conversion"));
        assertEquals(80.0, yao.get("qaVoice"));                  // moyenne des écoutes de la période
        var awa = p.rows().stream().filter(r -> r.username().equals("awa")).findFirst().orElseThrow().values();
        assertEquals(0.0, awa.get("sales"));
        assertEquals(70.0, awa.get("qaVoice"));                  // pas d'écoute : Score QA importé
        assertEquals(300.0, p.teamValues().get("calls"));
        assertEquals(33.3, p.teamValues().get("reachRate"));    // 100 joints / 300 appels, pas la moyenne des taux
        assertEquals(16.0, p.teamValues().get("conversion"));
    }

    @Test
    void mailTeamUsesWrittenEvaluations() {
        var src = new TeamPerformanceService.Sources(Map.of("fatou", new double[]{50, 1}), Map.of("fatou", new double[]{180, 2}), Map.of(), Map.of());
        var p = svc.build(TeamClassifier.Team.INBOUND_MAIL, "2026-09",
                List.of(agent(9, "fatou", Map.of("TICKETS_TRAITES", 300.0, "DMR", 42.0), 97)), src);
        var v = p.rows().get(0).values();
        assertEquals(300.0, v.get("mails"));
        assertEquals(42.0, v.get("responseTime"));
        assertEquals(90.0, v.get("qaWritten"));                 // écrits seulement, pas l'écoute à 50 %
        assertEquals(97.0, v.get("presence"));
    }

    @Test
    void qaTeamActivityIsForHeadsOnly() {
        assertTrue(QaTeamActivityService.canView(new AuthenticatedUser("h", "QA", "SUPERVISEUR_QA", null)));
        assertTrue(QaTeamActivityService.canView(new AuthenticatedUser("s", "SUPERVISOR", "SUPERVISEUR", null)));
        assertTrue(QaTeamActivityService.canView(new AuthenticatedUser("a", "ADMIN", null, null)));
        assertFalse(QaTeamActivityService.canView(new AuthenticatedUser("q", "QA", "QUALITY_ASSURANCE", null)));
        assertFalse(QaTeamActivityService.canView(new AuthenticatedUser("t", "TEAM_LEADER", "TEAM_LEADER_INBOUND_VOICE", null)));
    }
}
