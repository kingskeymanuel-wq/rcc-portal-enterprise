package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CampaignFieldDto;
import com.ecobank.rccportal.dto.CampaignImportMappingDto;
import com.ecobank.rccportal.dto.CampaignImportPreviewResponse;
import com.ecobank.rccportal.dto.CampaignImportReport;
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
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Import d'un export Microsoft Forms « Campagne réactivation de comptes dormants » (mêmes en-têtes que le fichier réel). */
class CampaignDormantImportTest {

    static final String[] HEADERS = {"ID", "Heure de début", "Heure de fin", "Adresse de messagerie", "Nom", "Heure de la dernière modification",
            "Nom de l'agent", "Nom du client ", "Numéro de compte ", "Numéro de téléphone  ", "Agence d'ouverture de compte ",
            "Date d appel", "Statut d'appel",
            "Il nous a été donné de constater que votre compte est resté plusieurs mois  sans mouvements. Quelles en sont les raisons svp ?",
            "Le client est il intéressé par la réactivation du compte ?? ", "Proposez le package . Le cllient est il interessé ? ",
            "Proposez la carte  . Le cllient est il interessé ? ", "Proposez la migration de compte  . Le cllient est il interessé ? ",
            "si non, pourquoi??", "si non, pourquoi??2", "si non, pourquoi??3", "AGENCE DE RDV",
            "Quand est ce que souhaitez vous que l on vous recontacte relativement à cela ?? ", "Commentaires", "Quand souhaitez-vous passer en agence ? "};

    static final List<CampaignFieldDto> FIELDS = List.of(
            new CampaignFieldDto("r1", "Votre compte est resté plusieurs mois sans mouvements : quelles en sont les raisons ?", "TEXTAREA", List.of(), false),
            new CampaignFieldDto("r2", "Le client est-il intéressé par la réactivation du compte ?", "RADIO", List.of("Oui", "Non", "Besoin de réfléchir"), true),
            new CampaignFieldDto("r3", "Proposez le package. Le client est-il intéressé ?", "RADIO", List.of("Oui", "Non", "Besoin de réfléchir"), false),
            new CampaignFieldDto("r4", "Proposez la carte. Le client est-il intéressé ?", "RADIO", List.of("Oui", "Non", "Besoin de réfléchir"), false),
            new CampaignFieldDto("r5", "Proposez la migration de compte. Le client est-il intéressé ?", "RADIO", List.of("Oui", "Non", "Besoin de réfléchir"), false),
            new CampaignFieldDto("r6", "Si non, pourquoi ?", "TEXTAREA", List.of(), false),
            new CampaignFieldDto("r7", "Agence de RDV", "TEXT", List.of(), false),
            new CampaignFieldDto("r8", "Quand souhaitez-vous passer en agence ?", "DATE", List.of(), false),
            new CampaignFieldDto("r9", "Quand souhaitez-vous que l'on vous recontacte ?", "DATE", List.of(), false),
            new CampaignFieldDto("r10", "Commentaires", "TEXTAREA", List.of(), false));

    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CampaignContactRepository contacts = mock(CampaignContactRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CampaignService svc = new CampaignService(campaigns, contacts, users, new ObjectMapper());
    private final List<CampaignContact> saved = new ArrayList<>();
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser("admin", "ADMIN", null, "Admin");

    CampaignDormantImportTest() throws Exception {
        Campaign c = Campaign.builder().campaignId(9).name("Réactivation des comptes dormants").createdByUserId(1L)
                .fieldsJson(new ObjectMapper().writeValueAsString(FIELDS)).build();
        when(campaigns.findById(9)).thenReturn(Optional.of(c));
        User houri = new User(); houri.setId(21L); houri.setUsername("s.houri"); houri.setName("Samuel Houri");
        when(users.findAll()).thenReturn(List.of(houri));
        when(users.findFirstByUsernameIgnoreCase(any())).thenReturn(Optional.empty());
        when(contacts.findByCampaignIdOrderByClientNameAsc(9)).thenReturn(List.of());
        when(contacts.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
    }

    static byte[] workbook(Object[][] rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Sheet1");
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Row h = sh.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) h.createCell(i).setCellValue(HEADERS[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sh.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v == null) continue;
                    Cell cell = row.createCell(c);
                    if (v instanceof LocalDateTime d) { cell.setCellValue(d); cell.setCellStyle(date); }
                    else if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                    else cell.setCellValue(v.toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static Object[] row(int id, String agent, String client, String account, String phone, String status, String... answers) {
        Object[] r = new Object[HEADERS.length];
        r[0] = id; r[3] = "anonymous"; r[6] = agent; r[7] = client; r[8] = account; r[9] = phone;
        r[10] = "ECI AGENCE PRINCIPALE (CAISTAB)\tK01"; r[11] = LocalDateTime.of(2026, 4, 13, 0, 0); r[12] = status;
        for (int i = 0; i < answers.length; i++) r[13 + i] = answers[i];
        return r;
    }

    private MockMultipartFile file() throws Exception {
        Object[] withRdv = row(3, "HOURI SAMUEL ", "KONE ADAMA", "120031684002", "2250707986474", "CLIENT ENTRETENU",
                "ses services ne marchent pas ", "OUI", "OUI", null, null, null, null, null, "ECI-AGENCE CAISTAB", null, "SE RENDRA EN AGENCE", null);
        withRdv[24] = LocalDateTime.of(2026, 4, 15, 0, 0);
        return new MockMultipartFile("file", "f.xlsx", "application/octet-stream", workbook(new Object[][]{
                row(1, "HOURI SAMUEL ", "S E K A D ", "120001035013", "+2250505051986", "APPEL INTERROMPU/INAUDIBLE/HACHURE/CLIENT SOUHAITE ETRE RAPPELE"),
                row(2, "MBAYE MAGUETE", "KOUAME ATTOUBE CHRISTIAN MR", "120003402007", "2252720324831,2250707201078", "SONNE DANS LE VIDE "),
                withRdv,
                row(4, "SILUE REMY", "KONE ADAMA", "120031684002", "0707986474", "CLIENT ENTRETENU"),
                row(5, "SILUE REMY", "BAMBA AWA", "120099999001", "N/A", "DOUBLON"),
                row(6, "SILUE REMY", "121429358001\t", "O'CAJOUX\t", "+2252250788463633,+2252250747121293", "INACCESSIBLE"),
                row(7, "SILUE REMY", "KONE ADAMA", "120555555001", "0101010101", "SONNE DANS LE VIDE")}));
    }

    @Test
    void previewPicksTheClientNameNotTheEmptyFormsNameColumnAndMatchesTheQuestionnaire() throws Exception {
        CampaignImportPreviewResponse p = svc.previewImport(ADMIN, 9, file());
        CampaignImportMappingDto m = p.suggestedMapping();
        assertEquals(7, m.nameColumn());
        assertEquals(9, m.phoneColumn());
        assertEquals(8, m.accountColumn());
        assertEquals(6, m.agentColumn());
        assertEquals(12, m.statusColumn());
        assertEquals(11, m.callDateColumn());
        Map<Integer, String> f = m.fieldColumns();
        assertEquals("r1", f.get(13)); assertEquals("r2", f.get(14)); assertEquals("r3", f.get(15)); assertEquals("r4", f.get(16));
        assertEquals("r5", f.get(17)); assertEquals("r6", f.get(18)); assertEquals("r7", f.get(21)); assertEquals("r9", f.get(22));
        assertEquals("r10", f.get(23)); assertEquals("r8", f.get(24));
        assertNull(f.get(19)); assertNull(f.get(10));
        assertEquals("KONE ADAMA", p.sampleRow().get(7)); // ligne d'exemple = la plus remplie
        assertEquals(10, p.matchedQuestions());
        assertTrue(p.confident());
    }

    @Test
    void importKeepsStatusesAgentsAndAnswersAndSkipsDuplicates() throws Exception {
        CampaignImportReport r = svc.importContacts(ADMIN, 9, file(), null);
        assertEquals(5, r.imported());                   // homonyme KONE ADAMA (autre compte) conservé
        assertEquals(2, r.skippedDuplicates());          // même compte (on garde l'appel abouti le plus complet) + « DOUBLON »
        assertEquals(Map.of("PENDING", 1, "RED", 3, "GREEN", 1), r.statusCounts());
        assertEquals(2, r.assigned());                    // HOURI SAMUEL ↔ « Samuel Houri »
        assertEquals(Map.of("MBAYE MAGUETE", 1, "SILUE REMY", 2), r.unmatchedAgents());

        CampaignContact swapped = saved.get(3);
        assertEquals("O'CAJOUX", swapped.getClientName());
        assertEquals("121******001", swapped.getMaskedAccountNumber());
        assertEquals("0788463633", swapped.getClientPhone());

        CampaignContact kouame = saved.get(1);
        assertEquals("0505051986", saved.get(0).getClientPhone());
        assertEquals("2720324831", kouame.getClientPhone());
        assertTrue(kouame.getExtraDataJson().contains("0707201078"));
        assertTrue(kouame.getExtraDataJson().contains("K01"));
        assertFalse(kouame.getExtraDataJson().contains("anonymous"));
        assertTrue(kouame.getNotes().startsWith("Appel précédent : SONNE DANS LE VIDE le 13/04/2026"));
        assertEquals("S E K A D", saved.get(0).getClientName());
        assertEquals("120******013", saved.get(0).getMaskedAccountNumber());

        CampaignContact kone = saved.get(2);
        assertEquals("GREEN", kone.getCallStatus());
        assertEquals(21L, kone.getAgentUserId());
        assertTrue(kone.getAnswersJson().contains("\"r2\":\"Oui\""), kone.getAnswersJson());
        assertTrue(kone.getAnswersJson().contains("\"r8\":\"2026-04-15\""), kone.getAnswersJson());
        assertTrue(kone.getAnswersJson().contains("ECI-AGENCE CAISTAB"));
        assertNotNull(kone.getLastCalledAt());
    }

    @Test
    void recallModeKeepsOnlyUnreachedContactsAsToCall() throws Exception {
        var base = svc.previewImport(ADMIN, 9, file()).suggestedMapping();
        var m = new CampaignImportMappingDto(base.nameColumn(), base.phoneColumn(), base.accountColumn(), base.agentColumn(),
                base.fieldColumns(), base.statusColumn(), base.callDateColumn(), true, true);
        CampaignImportReport r = svc.importContacts(ADMIN, 9, file(), m);
        assertEquals(4, r.imported());
        assertEquals(1, r.skippedNotToRecall());
        assertTrue(saved.stream().allMatch(c -> "PENDING".equals(c.getCallStatus()) && c.getLastCalledAt() == null));
    }

    @Test
    void reimportSynchronisesContactsAlreadyInTheCampaign() throws Exception {
        CampaignContact untouched = CampaignContact.builder().contactId(50).campaignId(9)
                .clientName("KOUAME ATTOUBE CHRISTIAN MR").maskedAccountNumber("120******007").callStatus("PENDING").build();
        CampaignContact calledInPortalLater = CampaignContact.builder().contactId(51).campaignId(9).clientName("S E K A D")
                .maskedAccountNumber("120******013").callStatus("GREEN").lastCalledAt(LocalDateTime.of(2026, 5, 2, 10, 0))
                .answersJson("{\"r2\":\"Non\"}").build();
        when(contacts.findByCampaignIdOrderByClientNameAsc(9)).thenReturn(List.of(untouched, calledInPortalLater));
        CampaignImportReport r = svc.importContacts(ADMIN, 9, file(), null);
        assertEquals(3, r.imported());
        assertEquals(2, r.updated());
        assertEquals(0, r.unchanged());
        assertEquals("RED", untouched.getCallStatus());             // le fichier (13/04) complète un contact jamais appelé
        assertEquals(LocalDateTime.of(2026, 4, 13, 0, 0), untouched.getLastCalledAt());
        assertNull(untouched.getAgentUserId());                      // MBAYE introuvable : reste non assigné
        assertEquals("GREEN", calledInPortalLater.getCallStatus()); // appel du portail (02/05) plus récent : gardé
        assertTrue(calledInPortalLater.getAnswersJson().contains("Non"));
        assertEquals(21L, calledInPortalLater.getAgentUserId());    // seul ce qui manquait est complété
        assertTrue(saved.stream().noneMatch(c -> c.getContactId() == null && c.getClientName().startsWith("KOUAME")));
        assertEquals(10, r.totalQuestions());
        assertEquals(10, r.matchedQuestions());
    }

    @Test
    void importAlignsOnAModelWordedDifferentlyFromTheFile() throws Exception {
        List<CampaignFieldDto> teamModel = List.of(
                new CampaignFieldDto("q1", "Raison de l'inactivité", "TEXTAREA", List.of(), false),
                new CampaignFieldDto("q2", "Intéressé par la réactivation ?", "RADIO", List.of("Oui", "Non", "Besoin de réfléchir"), true),
                new CampaignFieldDto("q3", "Package", "RADIO", List.of("Oui", "Non", "Besoin de réfléchir"), false),
                new CampaignFieldDto("q4", "Agence du rendez-vous", "TEXT", List.of(), false),
                new CampaignFieldDto("q5", "Date de passage en agence", "DATE", List.of(), false),
                new CampaignFieldDto("q6", "Commentaire", "TEXTAREA", List.of(), false));
        when(campaigns.findById(9)).thenReturn(Optional.of(Campaign.builder().campaignId(9).name("REACTIVATION CPTES DORMANTS")
                .createdByUserId(1L).fieldsJson(new ObjectMapper().writeValueAsString(teamModel)).build()));
        CampaignImportPreviewResponse p = svc.previewImport(ADMIN, 9, file());
        Map<Integer, String> f = p.suggestedMapping().fieldColumns();
        assertEquals("q1", f.get(13));
        assertEquals("q2", f.get(14));
        assertEquals("q3", f.get(15));
        assertEquals("q4", f.get(21));
        assertEquals("q5", f.get(24));
        assertEquals("q6", f.get(23));
        assertEquals(6, p.matchedQuestions());
        assertTrue(p.confident());
        CampaignImportReport r = svc.importContacts(ADMIN, 9, file(), null);
        CampaignContact kone = saved.stream().filter(c -> "GREEN".equals(c.getCallStatus())).findFirst().orElseThrow();
        assertTrue(kone.getAnswersJson().contains("\"q5\":\"2026-04-15\""), kone.getAnswersJson());
        assertTrue(kone.getAnswersJson().contains("\"q3\":\"Oui\""), kone.getAnswersJson());
        assertEquals(6, r.matchedQuestions());
    }

    @Test
    void importFollowsTheLongStandingTemplateFormatAndItsChoiceLists() throws Exception {
        when(campaigns.findById(9)).thenReturn(Optional.of(Campaign.builder().campaignId(9)
                .name(com.ecobank.rccportal.config.OutboundCampaignTemplates.DORMANT_NAME).createdByUserId(1L)
                .fieldsJson(com.ecobank.rccportal.config.OutboundCampaignTemplates.DORMANT_FIELDS_JSON).build()));
        Object[] refus = row(8, "HOURI SAMUEL", "YAO KOFFI", "120777777001", "0707070707", "CLIENT ENTRETENU",
                "EMPLOI PERDU", "NON", "NON", null, null, "LE CLIENT SOUHAITE CLÔTURER SON COMPTE COURANT ET OUVRIR UN COMPTE EPARGNE.", null, null,
                "ECI- AGENCE ZONE3", null, "N/A", null);
        Object[] rdv = row(9, "HOURI SAMUEL", "ADOU MARIE", "120888888001", "0505050505", "CLIENT ENTRETENU",
                "DIFFICULTÉS FINANCIÈRES", "OUI", "BESOIN DE REFLECHIR", null, null, null, null, null, "ECI- AGENCE MARCORY MARCHE", null, "VIENDRA", null);
        rdv[22] = LocalDateTime.of(2026, 5, 4, 0, 0);
        MockMultipartFile f = new MockMultipartFile("file", "f.xlsx", "application/octet-stream", workbook(new Object[][]{refus, rdv}));
        CampaignImportPreviewResponse p = svc.previewImport(ADMIN, 9, f);
        assertEquals(10, p.matchedQuestions(), "questions non retrouvées : " + p.unmatchedQuestions());
        assertTrue(p.confident());
        svc.importContacts(ADMIN, 9, f, null);
        String a = saved.get(0).getAnswersJson(), b = saved.get(1).getAnswersJson();
        assertTrue(a.contains("\"raisonInactivite\":\"Client au chômage / emploi perdu\""), a);
        assertTrue(a.contains("\"interesse\":\"Non\""), a);
        assertTrue(a.contains("\"sinonPourquoi\":\"Souhaite clôturer et ouvrir un compte épargne\""), a);
        assertTrue(a.contains("\"agenceRdv\":\"Zone 3\""), a);
        assertFalse(a.contains("N/A"), a);
        assertTrue(b.contains("\"interessePackage\":\"Besoin de réfléchir\""), b);
        assertTrue(b.contains("\"agenceRdv\":\"Marcory Marché\""), b);
        assertTrue(b.contains("\"quandContacter\":\"2026-05-04\""), b);
        assertTrue(b.contains("\"commentaire\":\"VIENDRA\""), b);
    }

    @Test
    void v188IdsAreAllMappedToTheTemplate() throws Exception {
        var ids = new java.util.HashSet<String>();
        for (var f : new ObjectMapper().readValue(com.ecobank.rccportal.config.OutboundCampaignTemplates.DORMANT_FIELDS_JSON, CampaignFieldDto[].class)) ids.add(f.id());
        assertEquals(10, ids.size());
        for (String[] pair : com.ecobank.rccportal.config.OutboundCampaignTemplates.DORMANT_V188_IDS) assertTrue(ids.contains(pair[1]), pair[1]);
        assertEquals("r10", com.ecobank.rccportal.config.OutboundCampaignTemplates.DORMANT_V188_IDS[0][0]); // r10 avant r1
    }

    @Test
    void statusAndPhoneRules() {
        assertEquals("RED", CampaignService.mapPreviousStatus("INCONNU AU NUMERO"));
        assertEquals("RED", CampaignService.mapPreviousStatus("MESSAGERIE"));
        assertEquals("RED", CampaignService.mapPreviousStatus("AUCUN CONTACT"));
        assertEquals("GREEN", CampaignService.mapPreviousStatus("CLIENT ENTRETENU"));
        assertEquals("SKIP", CampaignService.mapPreviousStatus("DOUBLON"));
        assertEquals("PENDING", CampaignService.mapPreviousStatus(""));
        assertEquals(List.of("0707842929"), CampaignService.normalizePhones("0707842929 "));
        assertEquals(List.of(), CampaignService.normalizePhones("N/A"));
        assertEquals(List.of("0576788063", "0778741166"), CampaignService.normalizePhones("+2252250576788063,+2252250778741166"));
        assertTrue(CampaignService.looksSwapped("121429358001", "O'CAJOUX"));
        assertFalse(CampaignService.looksSwapped("KONE ADAMA", "120031684002"));
    }

    @Test
    void distributionBalancesUnassignedPendingContacts() {
        List<CampaignContact> list = new ArrayList<>();
        for (int i = 0; i < 7; i++) list.add(CampaignContact.builder().contactId(i).campaignId(9).clientName("C" + i).callStatus(i == 6 ? "GREEN" : "PENDING").build());
        list.get(0).setAgentUserId(21L);
        when(contacts.findByCampaignIdOrderByClientNameAsc(9)).thenReturn(list);
        when(users.findById(any())).thenAnswer(i -> Optional.of(new User()));
        var out = svc.distributeUnassigned(ADMIN, 9, List.of(21L, 22L), false);
        assertEquals(5, out.get("assigned"));
        assertEquals(Map.of(21L, 3, 22L, 3), out.get("perAgent"));
        assertNull(list.get(6).getAgentUserId()); // déjà appelé : pas réparti par défaut
    }
}
