package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.dto.AgentScheduleResponse;
import com.ecobank.rccportal.dto.RalphSearchResponse;
import com.ecobank.rccportal.dto.ShiftStatusResponse;
import com.ecobank.rccportal.raf.RafDocs.*;
import com.ecobank.rccportal.raf.agent.*;
import com.ecobank.rccportal.raf.nlp.EntityExtractor;
import com.ecobank.rccportal.raf.nlp.FollowUpResolver;
import com.ecobank.rccportal.raf.nlp.IntentRouter;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RAF de bout en bout : vrais routeur, agents et orchestrateur sur un jeu de données du
 * portail en mémoire — seuls le planning et le pointage sont simulés. Aucune IA, aucun réseau.
 */
class RafOrchestratorTest {

    /** Mardi 22/09/2026 14:30. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 14, 30);

    private RafOrchestrator raf;
    private ScheduleService scheduleService;
    private ShiftService shiftService;
    private RafGapLog gapLog;

    @BeforeEach
    void setUp() {
        Snapshot data = new Snapshot(
                List.of(
                        new ProcedureDoc(1, "Opposition sur carte bancaire", "CI", "N1", "Monétique", null, List.of(
                                new StepDoc(1, "Vérifier l'identité du client (questions de sécurité)."),
                                new StepDoc(2, "Bloquer la carte dans l'outil monétique."),
                                new StepDoc(3, "Confirmer le blocage au client et proposer une nouvelle carte."),
                                new StepDoc(4, "Tracer la demande dans le CRM."))),
                        new ProcedureDoc(2, "Réclamation retrait GAB non obtenu", "CI", "N1", "Back-office", null, List.of(
                                new StepDoc(1, "Relever la date, le montant et le GAB concerné."),
                                new StepDoc(2, "Vérifier la reverse automatique sous 48h."),
                                new StepDoc(3, "Ouvrir une réclamation si aucun reverse.")))),
                List.of(
                        new SlaDoc(10, "Retrait GAB non obtenu", "Monétique", "N1", 120, "5 jours ouvrés", "Back-office monétique", null, true, null),
                        new SlaDoc(11, "Carte bloquée", "Monétique", "N1", 24, "24 heures", "Monétique", null, false, null)),
                List.of(new TermDoc(20, "RIB", "Relevé d'identité bancaire : coordonnées du compte.", "Compte"),
                        new TermDoc(21, "GAB", "Guichet automatique de billets.", "Monétique"),
                        new TermDoc(22, "OTP", "Mot de passe à usage unique.", "Sécurité")),
                List.of(new BranchDoc(30, "CI", "Abidjan", "Agence Plateau", "Avenue Terrasson de Fougères", 5.32, -4.02, "+225 00", "8h-16h", "AGENCE"),
                        new BranchDoc(31, "SN", "Dakar", "Agence Dakar Centre", "Place de l'Indépendance", 14.67, -17.43, null, null, "AGENCE")),
                List.of(new CountryDoc("CI", "Côte d'Ivoire", "🇨🇮", Set.of("cote d ivoire", "abidjan")),
                        new CountryDoc("SN", "Sénégal", "🇸🇳", Set.of("senegal", "dakar"))),
                List.of(new VerifiedQaDoc(40, "Quel est le plafond de retrait Xpress par jour ?", "500 000 FCFA", null, "Xpress", "plafond retrait")),
                List.of(new ArticleDoc(50, "Ecobank Xpress Account", "xpress compte", "Le compte Xpress s'ouvre avec une pièce d'identité.", "CI")),
                List.of(),
                List.of(new MailTemplateDoc(60, "Réclamation retrait GAB", "Bonjour [Nom], nous avons enregistré votre réclamation de [Montant] (réf. [Référence]).", "Monétique")));
        RafCatalog catalog = RafCatalog.fixed(data);

        scheduleService = mock(ScheduleService.class);
        shiftService = mock(ShiftService.class);
        PlanningComplianceService compliance = mock(PlanningComplianceService.class);
        when(compliance.forUser(any(), any())).thenReturn(Optional.empty());
        UserRepository users = mock(UserRepository.class);
        when(users.findFirstByUsernameIgnoreCase(any())).thenReturn(Optional.empty());

        ProcedureAgent procedureAgent = new ProcedureAgent(catalog);
        SlaAgent slaAgent = new SlaAgent(catalog);
        TemplateAgent templateAgent = new TemplateAgent(catalog);
        List<RafAgent> agents = List.of(
                new SmallTalkAgent(), new CallCardAgent(catalog, procedureAgent, slaAgent, templateAgent), procedureAgent, slaAgent,
                new GlossaryAgent(catalog), new BranchAgent(catalog), new MyShiftAgent(scheduleService, shiftService, compliance, users),
                templateAgent, new VerifiedQaAgent(catalog), new KnowledgeAgent(catalog));

        DataProtectionService protection = mock(DataProtectionService.class);
        when(protection.sanitize(anyString())).thenAnswer(i -> i.getArgument(0));
        gapLog = new RafGapLog();
        raf = new RafOrchestrator(agents, new IntentRouter(catalog), new EntityExtractor(catalog), new FollowUpResolver(),
                new RafConversationMemoryService(), protection, gapLog);
        raf.setClock(Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    private RalphSearchResponse ask(String q) {
        return raf.handle(q, "agent.conseiller", null, null);
    }

    @Test
    void slaAnswerUsesOnlyTheOfficialTableAndComputesTheDueDate() {
        var r = ask("délai pour un retrait GAB non obtenu");
        assertEquals("SLA", r.intent());
        assertTrue(r.explanation().contains("5 jours ouvrés"));
        // Mardi 14:30 + 5 jours ouvrés = mardi suivant 29/09/2026.
        assertTrue(r.explanation().contains("29/09/2026"), r.explanation());
        assertEquals("SLA_DUE", r.action().type());
        // Aucun nombre qui ne viendrait pas de la table ou de la date calculée.
        Matcher m = Pattern.compile("\\d+").matcher(r.explanation());
        Set<String> allowed = Set.of("5", "1", "29", "09", "2026", "14", "30"); // 1 = niveau N1 de la table
        while (m.find()) assertTrue(allowed.contains(m.group()), "nombre inventé : " + m.group() + " dans " + r.explanation());
    }

    @Test
    void unknownSlaIsNeverEstimated() {
        var r = ask("quel est le délai SLA pour un changement de nom de naissance");
        assertTrue(r.explanation().contains("ne figure pas dans le référentiel SLA officiel"), r.explanation());
    }

    @Test
    void callCardCombinesProcedureSlaRoutingAndTemplate() {
        var r = ask("que dire au client pour un retrait GAB non obtenu");
        assertEquals("CALL_CARD", r.intent());
        String e = r.explanation();
        assertTrue(e.contains("Relever la date"), e);                   // ce que je fais
        assertTrue(e.contains("5 jours ouvrés"), e);                     // ce que je dis (SLA officiel)
        assertTrue(e.contains("Back-office monétique"), e);              // où transmettre
        assertTrue(e.contains("Réclamation retrait GAB"), e);            // modèle
        assertTrue(e.contains("GAB") && e.contains("Guichet automatique"), e); // termes utiles
        assertTrue(r.suggestions().stream().anyMatch(s -> "raf:proc:2:step:1".equals(s.command())));
    }

    @Test
    void guidedModeWalksThroughStepsWithNextAndPrevious() {
        var start = raf.handle(null, "agent.conseiller", null, "raf:proc:1:step:1");
        assertEquals("GUIDED_STEP", start.action().type());
        assertTrue(start.explanation().contains("Vérifier l'identité"));
        var next = ask("suivant");
        assertTrue(next.explanation().contains("Bloquer la carte"), next.explanation());
        var back = ask("précédent");
        assertTrue(back.explanation().contains("Vérifier l'identité"));
        ask("étape 4");
        var done = ask("suivant");
        assertTrue(done.explanation().contains("Procédure terminée"), done.explanation());
    }

    @Test
    void glossaryAndFollowUpDetails() {
        var r = ask("c'est quoi un RIB");
        assertEquals("GLOSSARY", r.intent());
        assertTrue(r.explanation().contains("Relevé d'identité bancaire"));
    }

    @Test
    void branchesByCountryAndEllipticalFollowUp() {
        var ci = ask("agences à Abidjan");
        assertEquals("BRANCH", ci.intent());
        assertTrue(ci.explanation().contains("Agence Plateau"));
        var sn = ask("et pour le Sénégal ?");
        assertEquals("BRANCH", sn.intent());
        assertTrue(sn.explanation().contains("Agence Dakar Centre"), sn.explanation());
        assertFalse(sn.explanation().contains("Agence Plateau"));
    }

    @Test
    void mailDraftIsPrefilledFromTheSentence() {
        var r = ask("rédige le mail de réclamation retrait GAB pour Mme Koné, montant 50 000 FCFA, réf TRX123");
        assertEquals("TEMPLATE", r.intent());
        String body = (String) r.action().payload().get("body");
        assertTrue(body.contains("Bonjour Mme Koné"), body);
        assertTrue(body.contains("50 000 FCFA"));
        assertTrue(body.contains("TRX123"));
        assertEquals("DRAFT", r.action().type());
    }

    @Test
    void myShiftUsesOnlyTheRequestersOwnData() {
        when(scheduleService.planningForUser(eq("agent.conseiller"), any(), any())).thenReturn(List.of(new AgentScheduleResponse(
                "agent.conseiller", "Awa", "INBOUND_VOICE", NOW.toLocalDate(), LocalTime.of(8, 0), LocalTime.of(17, 0),
                "M2", "Matin", false, "APPROVED", null, "EXCELLIAM")));
        when(shiftService.getStatus("agent.conseiller")).thenReturn(new ShiftStatusResponse("ON_PAUSE", List.of(),
                NOW.minusMinutes(12), null, null, 0, 0, List.of()));
        var r = ask("mon planning aujourd'hui");
        assertEquals("MY_SHIFT", r.intent());
        assertTrue(r.explanation().contains("08:00–17:00"), r.explanation());
        assertTrue(r.explanation().contains("en pause"));
        verify(scheduleService, never()).planningForUser(argThat(u -> !"agent.conseiller".equals(u)), any(), any());
    }

    @Test
    void verifiedQaAnswer() {
        var r = ask("plafond de retrait Xpress par jour");
        assertTrue(r.explanation().contains("500 000 FCFA"), r.explanation());
    }

    @Test
    void nothingFoundIsHonestAndLoggedForQa() {
        var r = ask("météo à Tombouctou demain matin");
        assertEquals("NONE", r.source());
        assertTrue(r.explanation().contains("rien trouvé de fiable"));
        assertEquals(1, gapLog.recent().size());
    }

    @Test
    void smallTalkInSeveralLanguages() {
        assertTrue(ask("bonjour").explanation().startsWith("Bonjour"));
        assertTrue(raf.handle("hello", "x", "en", null).explanation().startsWith("Hello"));
    }

    @Test
    void responseKeepsBackwardCompatibleFields() {
        var r = ask("procédure opposition carte");
        assertNotNull(r.results());
        assertNotNull(r.webResults());
        assertNotNull(r.sourcesConsulted());
        assertFalse(r.agentsConsulted().isEmpty());
        assertTrue(r.confidencePercent() > 0);
    }
}
