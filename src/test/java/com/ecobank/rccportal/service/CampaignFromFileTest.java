package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CampaignFieldDto;
import com.ecobank.rccportal.model.Campaign;
import com.ecobank.rccportal.model.CampaignContact;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CampaignContactRepository;
import com.ecobank.rccportal.repository.CampaignRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Une autre campagne, au format « liste de clients » (pas Microsoft Forms) : même transformation. */
class CampaignFromFileTest {

    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CampaignContactRepository contacts = mock(CampaignContactRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CampaignService svc = new CampaignService(campaigns, contacts, users, new ObjectMapper());
    private final CampaignFileModelService files = new CampaignFileModelService(svc, campaigns);
    private final List<CampaignContact> saved = new ArrayList<>();
    private static final AuthenticatedUser TL = new AuthenticatedUser("admin", "ADMIN", null, "Admin");
    private Campaign created;

    CampaignFromFileTest() {
        User yao = new User(); yao.setId(5L); yao.setUsername("ykouassi"); yao.setName("Yao Kouassi");
        when(users.findAll()).thenReturn(List.of(yao));
        when(users.findFirstByUsernameIgnoreCase(any())).thenAnswer(i -> "admin".equals(i.getArgument(0)) ? Optional.of(admin()) : Optional.empty());
        when(campaigns.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(campaigns.save(any())).thenAnswer(i -> { Campaign c = i.getArgument(0); c.setCampaignId(42); created = c; return c; });
        when(campaigns.findById(42)).thenAnswer(i -> Optional.ofNullable(created));
        when(contacts.findByCampaignIdOrderByClientNameAsc(42)).thenReturn(List.of());
        when(contacts.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
    }

    private static User admin() { User u = new User(); u.setId(1L); u.setUsername("admin"); return u; }

    static byte[] xlsx(String[] headers, Object[][] rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Liste");
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Row h = sh.createRow(0);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sh.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v == null) continue;
                    Cell cell = row.createCell(c);
                    if (v instanceof LocalDateTime d) { cell.setCellValue(d); cell.setCellStyle(date); } else cell.setCellValue(v.toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static final String[] HEADERS = {"CIF", "NOM & PRENOMS", "CONTACT", "AGENCE", "SEGMENT", "CONSEILLER", "INTERESSE (O/N)", "MOTIF REFUS", "DATE RAPPEL", "OBSERVATIONS"};

    private MockMultipartFile file() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        String[] motifs = {"DEJA EQUIPE D'UNE CARTE", "Déjà équipé d'une carte", "FRAIS TROP ELEVES", "FRAIS TROP ÉLEVÉS", "N'UTILISE PAS DE CARTE"};
        for (int i = 0; i < 12; i++) {
            boolean yes = i % 3 == 0;
            rows.add(new Object[]{"10020" + i, "CLIENT " + (char) ('A' + i), "07070707" + String.format("%02d", i), "ECI- AGENCE KOUMASSI", "PARTICULIER",
                    i < 6 ? "YAO KOUASSI" : "INCONNU X", yes ? (i % 2 == 0 ? "O" : "OUI") : "N", yes ? null : motifs[i % motifs.length],
                    yes ? LocalDateTime.of(2026, 10, 1 + i, 0, 0) : null, "Client joint le matin, souhaite plus d'informations sur l'offre " + i});
        }
        return new MockMultipartFile("file", "LISTE_CARTE_PREMIUM_OCT2026_jean.xlsx", "application/octet-stream", xlsx(HEADERS, rows.toArray(new Object[0][])));
    }

    @Test
    void analysisBuildsTheQuestionnaireFromTheAnswers() throws Exception {
        var a = files.analyze(TL, file());
        assertEquals("Liste carte premium", a.suggestedName());
        assertEquals(12, a.rows());
        assertEquals(1, a.mapping().nameColumn());
        assertEquals(2, a.mapping().phoneColumn());
        assertEquals(0, a.mapping().accountColumn());
        assertEquals(5, a.mapping().agentColumn());
        Map<String, CampaignFieldDto> byHeader = new HashMap<>();
        a.fields().forEach(f -> byHeader.put(f.header(), f.field()));
        assertEquals(Set.of("INTERESSE (O/N)", "MOTIF REFUS", "DATE RAPPEL", "OBSERVATIONS"), byHeader.keySet()); // AGENCE, SEGMENT = informations
        assertEquals("SELECT", byHeader.get("INTERESSE (O/N)").type());
        assertEquals(List.of("Oui", "Non"), byHeader.get("INTERESSE (O/N)").options());
        CampaignFieldDto motif = byHeader.get("MOTIF REFUS");
        assertEquals("SELECT", motif.type());
        assertEquals(3, motif.options().size(), motif.options().toString());        // variantes d'écriture regroupées
        assertEquals("DATE", byHeader.get("DATE RAPPEL").type());
        assertEquals("TEXTAREA", byHeader.get("OBSERVATIONS").type());
    }

    @Test
    void createBuildsTheCampaignAndImportsLikeTheDormantCampaign() throws Exception {
        var a = files.analyze(TL, file());
        var fields = a.fields().stream().map(CampaignFileModelService.InferredField::field).toList();
        var r = files.create(TL, file(), new CampaignFileModelService.CreateFromFileRequest("Carte Premium Octobre", null, null, fields, a.mapping()));
        assertEquals("Carte Premium Octobre", created.getName());
        assertEquals(12, r.report().imported());
        assertEquals(6, r.report().assigned());                 // « YAO KOUASSI » reconnu par son nom
        assertEquals(Map.of("INCONNU X", 6), r.report().unmatchedAgents());
        assertEquals(4, r.report().matchedQuestions());
        String interestId = a.fields().stream().filter(f -> f.header().startsWith("INTERESSE")).findFirst().orElseThrow().field().id();
        assertTrue(saved.get(0).getAnswersJson().contains("\"" + interestId + "\":\"Oui\""), saved.get(0).getAnswersJson()); // « O » → Oui
        assertTrue(saved.get(0).getExtraDataJson().contains("Koumassi") || saved.get(0).getExtraDataJson().contains("KOUMASSI"));
    }

    @Test
    void agencyOptionsLoseTheirSharedPrefix() {
        List<String> answers = new ArrayList<>();
        for (String ag : new String[]{"ECI- AGENCE KOUMASSI", "ECI-AGENCE KOUMASSI", "ECI- AGENCE MARCORY MARCHE", "ECI-AGENCE MARCORY MARCHE",
                "ECI -SAN PEDRO", "ECI -SAN PEDRO", "ECI-AGENCE ZONE3", "ECI-AGENCE ZONE3", "ECI-AGENCE DALOA", "ECI-AGENCE DALOA"}) answers.add(ag);
        CampaignFieldDto f = files.inferField("AGENCE DE RDV", answers, new HashSet<>(), List.of("AGENCE DE RDV"), 0);
        assertEquals("SELECT", f.type());
        assertTrue(f.options().containsAll(List.of("Koumassi", "Marcory Marche", "San Pedro", "Zone3", "Daloa")), f.options().toString());
    }

    @Test
    void labelsAndNames() {
        assertEquals("Si non, pourquoi ? (2)", files.cleanLabel("si non, pourquoi??2", List.of(), 0));
        assertEquals("Interesse (O/N)", files.cleanLabel("INTERESSE (O/N)", List.of(), 0));
        assertEquals("Agence de RDV", files.cleanLabel("AGENCE DE RDV", List.of(), 0));
        assertEquals("Proposez le package. Le cllient est il interessé ?", files.cleanLabel("Proposez le package . Le cllient est il interessé ? ", List.of(), 0));
        assertEquals("Campagne r activation de comptes dormants",
                CampaignFileModelService.suggestName("313ea906-CAMPAGNE_R_ACTIVATION_DE__COMPTES_DORMANTS_paul_hyacinthe_21-5000_10.xlsx"));
    }

    @Test
    void performanceByAgentDayAndAnswer() throws Exception {
        String fields = new ObjectMapper().writeValueAsString(List.of(new CampaignFieldDto("interesse", "Intéressé ?", "SELECT", List.of("Oui", "Non"), true)));
        Campaign c = Campaign.builder().campaignId(7).name("Test").createdByUserId(1L).fieldsJson(fields).build();
        List<CampaignContact> list = List.of(
                CampaignContact.builder().campaignId(7).agentUserId(5L).clientName("A").callStatus("GREEN").answersJson("{\"interesse\":\"Oui\"}").lastCalledAt(LocalDateTime.of(2026, 9, 20, 10, 0)).build(),
                CampaignContact.builder().campaignId(7).agentUserId(5L).clientName("B").callStatus("YELLOW").answersJson("{\"interesse\":\"Oui\"}").lastCalledAt(LocalDateTime.of(2026, 9, 22, 10, 0)).build(),
                CampaignContact.builder().campaignId(7).agentUserId(6L).clientName("C").callStatus("RED").lastCalledAt(LocalDateTime.of(2026, 9, 22, 11, 0)).build(),
                CampaignContact.builder().campaignId(7).agentUserId(6L).clientName("D").callStatus("PENDING").build(),
                CampaignContact.builder().campaignId(7).clientName("E").callStatus("PENDING").answersJson("{\"interesse\":\"Peut-être\"}").build());
        User a = new User(); a.setId(5L); a.setName("Yao Kouassi");
        User b = new User(); b.setId(6L); b.setUsername("zie");
        when(users.findAllById(any())).thenReturn(List.of(a, b));
        var perf = new CampaignPerformanceService(svc, campaigns, contacts, users, null).build(c, list, List.of(
                new CampaignPerformanceService.LogRow(5L, LocalDate.of(2026, 9, 20), "GREEN", 1),
                new CampaignPerformanceService.LogRow(5L, LocalDate.of(2026, 9, 22), "YELLOW", 1),
                new CampaignPerformanceService.LogRow(6L, LocalDate.of(2026, 9, 22), "RED", 2)), null);
        assertEquals(5, perf.total());
        assertEquals(3, perf.called());
        assertEquals(2, perf.reached());
        assertEquals(1, perf.appointments());
        assertEquals(1, perf.unassigned());
        assertEquals(4, perf.callsInPeriod());
        assertEquals("Yao Kouassi", perf.agents().get(0).name());
        assertEquals(2, perf.agents().get(0).reached());
        assertEquals(2, perf.agents().get(1).callsInPeriod());
        assertEquals(3, perf.daily().size());                    // 20, 21 (vide), 22 septembre
        assertEquals(3, perf.daily().get(2).calls());
        var q = perf.questions().get(0);
        assertEquals(3, q.answered());
        assertEquals("Oui", q.options().get(0).option());
        assertEquals(2, q.options().get(0).count());
        assertEquals("Autres réponses", q.options().get(1).option());
    }
}
