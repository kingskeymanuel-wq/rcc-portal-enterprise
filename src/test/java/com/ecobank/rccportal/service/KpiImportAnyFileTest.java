package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.KpiImportResult;
import com.ecobank.rccportal.model.ManualKpiEntry;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ManualKpiEntryRepository;
import com.ecobank.rccportal.repository.RccServiceRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Import des résultats KPI « peu importe le fichier » : formats et mises en page variés. */
class KpiImportAnyFileTest {

    private ManualKpiEntryRepository entryRepository;
    private UserRepository userRepository;
    private ManualKpiEntryService service;
    private final List<ManualKpiEntry> saved = new ArrayList<>();
    private User awa, koffi;

    @BeforeEach
    void setUp() {
        entryRepository = mock(ManualKpiEntryRepository.class);
        userRepository = mock(UserRepository.class);
        ImportIntelligenceService intelligence = mock(ImportIntelligenceService.class);
        when(intelligence.isAvailable()).thenReturn(false);
        service = new ManualKpiEntryService(entryRepository, userRepository, mock(RccServiceRepository.class),
                mock(UserServiceAssignmentRepository.class), intelligence, mock(AuditLogService.class), mock(LocalOcrClient.class));

        User qa = new User(); qa.setId(9L); qa.setUsername("qa");
        awa = new User(); awa.setId(1L); awa.setUsername("E12345"); awa.setName("TRAORE Awa");
        koffi = new User(); koffi.setId(2L); koffi.setUsername("E67890"); koffi.setName("KOUASSI Koffi");
        when(userRepository.findFirstByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userRepository.findFirstByUsernameIgnoreCase("qa")).thenReturn(Optional.of(qa));
        when(userRepository.findFirstByUsernameIgnoreCase("E12345")).thenReturn(Optional.of(awa));
        when(userRepository.findFirstByUsernameIgnoreCase("E67890")).thenReturn(Optional.of(koffi));
        when(userRepository.findAll()).thenReturn(List.of(qa, awa, koffi));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(entryRepository.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
    }

    private KpiImportResult importFile(String name, byte[] content) {
        return service.importFromExcel(new MockMultipartFile("file", name, null, content),
                YearMonth.of(2026, 9), "qa", null, null, null);
    }

    private BigDecimal value(User u, String code) {
        return saved.stream().filter(e -> e.getSubject() == u && e.getMetricCode().equals(code))
                .map(ManualKpiEntry::getMetricValue).findFirst().orElse(null);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertNotNull(actual, "valeur absente, attendu " + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "attendu " + expected + " mais " + actual);
    }

    @Test
    void frenchCsvWithTitleRowsMatriculeNotInFirstColumnAndPercentages() {
        String csv = "Rapport mensuel des performances;;;;\n"
                + "Généré le 01/10/2026;;;;\n"
                + ";;;;\n"
                + "N°;Équipe;Matricule;Taux de décroché;DMT;Appels traités;Note qualité\n"
                + "1;Inbound;E12345;85,5 %;04:30;1 234;82\n"
                + "2;Inbound;E67890;92%;3:15;980;76,5\n"
                + "Total;;;88,7%;;2214;79\n";
        KpiImportResult r = importFile("export.csv", csv.getBytes(Charset.forName("windows-1252")));

        assertNum("85.5", value(awa, "TAUX_DE_DECROCHE"));
        assertEquals(0, value(awa, "DMT").compareTo(new BigDecimal("4.5")));
        assertNum("1234", value(awa, "INTERACTIONS")); // « Appels traités » → code exploité par l'analyse
        assertNum("92", value(koffi, "TAUX_DE_DECROCHE"));
        assertNull(value(awa, "N"));         // colonne descriptive ignorée
        assertNull(value(awa, "EQUIPE"));
        assertNum("82", value(awa, "SCORE_QA"));
        assertEquals(8, r.entriesCreated());
        assertTrue(r.skippedValues().isEmpty());
    }

    @Test
    void separateLastAndFirstNameColumnsAndDateColumn() {
        String tsv = "Date\tNom\tPrénom\tScore QA\tCSAT\n"
                + "15/09/2026\tTRAORE\tAwa\t78\t4,5\n"
                + "16/09/2026\tKOUASSI\tKoffi\t91\t4,8\n";
        importFile("perf.txt", tsv.getBytes(StandardCharsets.UTF_8));
        assertNum("78", value(awa, "SCORE_QA"));
        assertEquals(LocalDate.of(2026, 9, 15), saved.stream().filter(e -> e.getSubject() == awa).findFirst().get().getPeriodDate());
        assertNum("4.8", value(koffi, "CSAT"));
    }

    @Test
    void longFormatAgentIndicatorValue() {
        String csv = "Agent,Indicateur,Valeur,Mois\n"
                + "TRAORE Awa,Taux de présence,96.5,2026-08\n"
                + "TRAORE Awa,Interactions,1520,2026-08\n"
                + "Koffi KOUASSI,Taux de présence,88,2026-08\n";
        importFile("kpi.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertNum("96.5", value(awa, "TAUX_DE_PRESENCE"));
        assertNum("1520", value(awa, "INTERACTIONS"));
        assertNum("88", value(koffi, "TAUX_DE_PRESENCE")); // nom dans l'autre ordre
        assertEquals(LocalDate.of(2026, 8, 1), saved.get(0).getPeriodDate());
    }

    @Test
    void htmlTableExportedAsXls() {
        String html = "<html><body><table><tr><th>Matricule</th><th>Nom</th><th>Productivité</th></tr>"
                + "<tr><td>E12345</td><td>TRAORE Awa</td><td>97,2&nbsp;%</td></tr></table></body></html>";
        importFile("rapport.xls", html.getBytes(StandardCharsets.UTF_8));
        assertNum("97.2", value(awa, "PRODUCTIVITE"));
    }

    @Test
    void legacyLayoutStillHandledByHistoricalEngine() {
        String csv = "Septembre 2026;;\nAgent;Interactions;Score QA\nTRAORE Awa;1500;80\n";
        importFile("legacy.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertNum("1500", value(awa, "INTERACTIONS"));
    }

    @Test
    void flexibleNumbers() {
        assertEquals(new BigDecimal("1234.5"), ManualKpiEntryService.parseFlexibleNumber("1 234,5"));
        assertEquals(new BigDecimal("1234.5"), ManualKpiEntryService.parseFlexibleNumber("1.234,5"));
        assertEquals(new BigDecimal("1234.5"), ManualKpiEntryService.parseFlexibleNumber("1,234.5"));
        assertEquals(new BigDecimal("85"), ManualKpiEntryService.parseFlexibleNumber("85 %"));
        assertEquals(new BigDecimal("90"), ManualKpiEntryService.parseFlexibleNumber("1h30"));
        assertEquals(new BigDecimal("12500"), ManualKpiEntryService.parseFlexibleNumber("12 500 FCFA"));
        assertNull(ManualKpiEntryService.parseFlexibleNumber("Inbound"));
    }

    @Test
    void delimiterDetectionPrefersSemicolonOverDecimalComma() {
        assertEquals(';', KpiFileReader.detectDelimiter(List.of("Nom;Score", "A;85,5", "B;90,2")));
        assertEquals(',', KpiFileReader.detectDelimiter(List.of("Nom,Score", "A,85.5", "B,90.2")));
        assertEquals('\t', KpiFileReader.detectDelimiter(List.of("Nom\tScore", "A\t85,5")));
    }
}
