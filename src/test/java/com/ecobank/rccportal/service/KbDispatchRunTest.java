package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AttachmentResponse;
import com.ecobank.rccportal.dto.KnowledgeCategoryRequest;
import com.ecobank.rccportal.dto.KnowledgeCategoryResponse;
import com.ecobank.rccportal.model.Attachment;
import com.ecobank.rccportal.model.KnowledgeArticle;
import com.ecobank.rccportal.model.KnowledgeCategory;
import com.ecobank.rccportal.model.KnowledgeCountry;
import com.ecobank.rccportal.repository.AttachmentRepository;
import com.ecobank.rccportal.repository.KnowledgeArticleRepository;
import com.ecobank.rccportal.repository.KnowledgeCategoryRepository;
import com.ecobank.rccportal.repository.KnowledgeCountryRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dispatching réel de bout en bout (base simulée en mémoire) : ajout, rubrique créée, mise à jour par titre, fichier inchangé. */
class KbDispatchRunTest {

    @TempDir Path tmp;
    final AuthenticatedUser qa = new AuthenticatedUser("qa.user", "agent", "QUALITY_ASSURANCE", "QA");
    final List<KnowledgeCategory> cats = new ArrayList<>();
    final List<KnowledgeArticle> arts = new ArrayList<>();
    final List<Attachment> atts = new ArrayList<>();
    final Map<Integer, String> hashes = new HashMap<>();
    final Map<Integer, Object[]> packages = new HashMap<>();
    KbDispatchService service;

    static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        return out.toByteArray();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        cats.add(KnowledgeCategory.builder().categoryId(1).code("CARTES").title("Cartes bancaires").sortOrder(1).build());
        KnowledgeCountry ci = KnowledgeCountry.builder().countryCode("CI").label("Côte d'Ivoire").sortOrder(1).build();

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            Object[] a = Arrays.copyOfRange(inv.getArguments(), 1, inv.getArguments().length);
            if (sql.startsWith("INSERT INTO dbo.KbDispatchPackages")) packages.put(packages.size() + 1, a);
            if (sql.contains("MERGE dbo.KbDispatchFiles")) hashes.put((Integer) a[0], (String) a[4]);
            return 1;
        });
        when(jdbc.queryForObject(contains("MAX(Id)"), eq(Integer.class), any(Object[].class))).thenAnswer(inv -> packages.size());
        when(jdbc.queryForObject(contains("MAX(SortOrder)"), eq(Integer.class))).thenReturn(5);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            Object key = inv.getArgument(2);
            if (sql.contains("StoredName FROM")) return List.of((String) packages.get((Integer) key)[1]);
            if (sql.contains("Sha256 FROM")) return hashes.containsKey(key) ? List.of(hashes.get(key)) : List.of();
            if (sql.contains("SELECT * FROM dbo.KbDispatchPackages")) {
                Object[] p = packages.get((Integer) key);
                return List.of(new KbDispatchService.Package((Integer) key, (String) p[0], (Long) p[2], (Integer) p[3], (String) p[4], null, null, null, null));
            }
            return List.of();
        });
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        KnowledgeCategoryRepository catRepo = mock(KnowledgeCategoryRepository.class);
        when(catRepo.findAll()).thenAnswer(i -> new ArrayList<>(cats));
        when(catRepo.findById(anyInt())).thenAnswer(i -> cats.stream().filter(c -> c.getCategoryId().equals(i.getArgument(0))).findFirst());
        when(catRepo.findByCodeIgnoreCase(anyString())).thenAnswer(i -> cats.stream().filter(c -> c.getCode().equalsIgnoreCase(i.getArgument(0))).findFirst());
        KnowledgeCountryRepository ctyRepo = mock(KnowledgeCountryRepository.class);
        when(ctyRepo.findAll()).thenReturn(List.of(ci));
        KnowledgeArticleRepository artRepo = mock(KnowledgeArticleRepository.class);
        when(artRepo.findByCategoryAndCountryIsNullOrderBySortOrderAsc(any())).thenAnswer(i -> arts.stream()
                .filter(a -> a.getCategory().getCategoryId().equals(((KnowledgeCategory) i.getArgument(0)).getCategoryId()) && a.getCountry() == null).toList());
        when(artRepo.findByCategoryAndCountry_CountryCodeOrderBySortOrderAsc(any(), anyString())).thenAnswer(i -> arts.stream()
                .filter(a -> a.getCategory().getCategoryId().equals(((KnowledgeCategory) i.getArgument(0)).getCategoryId()) && a.getCountry() != null
                        && a.getCountry().getCountryCode().equals(i.getArgument(1))).toList());
        when(artRepo.findById(anyInt())).thenAnswer(i -> arts.stream().filter(a -> a.getArticleId().equals(i.getArgument(0))).findFirst());
        AttachmentRepository attRepo = mock(AttachmentRepository.class);
        when(attRepo.findByEntityTypeAndEntityId(anyString(), anyInt())).thenAnswer(i -> atts.stream().filter(a -> a.getEntityId().equals(i.getArgument(1))).toList());
        when(attRepo.findById(anyInt())).thenAnswer(i -> atts.stream().filter(a -> a.getAttachmentId().equals(i.getArgument(0))).findFirst());
        when(attRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        KnowledgeService kb = mock(KnowledgeService.class);
        when(kb.findOrCreateContainer(anyInt(), isNull(), any(), isNull())).thenAnswer(i -> {
            Integer catId = i.getArgument(0);
            String country = i.getArgument(2);
            return arts.stream().filter(a -> a.getCategory().getCategoryId().equals(catId) && Objects.equals(country, a.getCountry() == null ? null : a.getCountry().getCountryCode()))
                    .findFirst().orElseGet(() -> {
                        KnowledgeArticle a = KnowledgeArticle.builder().articleId(100 + arts.size()).title("Documents")
                                .category(cats.stream().filter(c -> c.getCategoryId().equals(catId)).findFirst().orElseThrow())
                                .country(country == null ? null : ci).contentHtml("").sortOrder(0).build();
                        arts.add(a);
                        return a;
                    });
        });
        when(kb.createCategory(any())).thenAnswer(i -> {
            KnowledgeCategoryRequest r = i.getArgument(0);
            KnowledgeCategory c = KnowledgeCategory.builder().categoryId(cats.size() + 1).code(r.code()).title(r.title()).team(r.team()).sortOrder(r.sortOrder()).build();
            cats.add(c);
            return new KnowledgeCategoryResponse(c.getCategoryId(), c.getCode(), c.getTitle(), null, null, c.getSortOrder(), c.getTeam());
        });
        AttachmentService attService = mock(AttachmentService.class);
        when(attService.attachLocalFile(anyString(), anyInt(), anyString(), any(), anyString(), anyString())).thenAnswer(i -> {
            Attachment a = Attachment.builder().attachmentId(500 + atts.size()).entityType(i.getArgument(0)).entityId(i.getArgument(1))
                    .fileName(i.getArgument(2)).storageUrl(i.getArgument(4)).build();
            atts.add(a);
            return new AttachmentResponse(a.getAttachmentId(), a.getEntityType(), a.getEntityId(), a.getFileName(), null, a.getStorageUrl(), null, null, false);
        });
        DocumentStorageService storage = mock(DocumentStorageService.class);
        when(storage.store(any())).thenAnswer(i -> "/kb-files/" + UUID.randomUUID() + ".pdf");

        service = new KbDispatchService(jdbc, kb, catRepo, ctyRepo, artRepo, attRepo, attService, storage);
        ReflectionTestUtils.setField(service, "kbFilesDir", tmp.resolve("kb-files").toString());
    }

    private KbDispatchService.Item item(KbDispatchService.Report r, String file) {
        return r.items().stream().filter(i -> i.fileName().equals(file)).findFirst().orElseThrow();
    }

    @Test
    void dispatchThenUpdateByTitle() throws Exception {
        var p1 = service.upload(qa, new MockMultipartFile("file", "base-oct.zip", "application/zip", zip(new LinkedHashMap<>(Map.of(
                "Base 2026/Cartes bancaires/Procédure carte.pdf", "version A",
                "Base 2026/Cartes bancaires/Côte d'Ivoire/Tarifs cartes.pdf", "tarifs",
                "Base 2026/03 - Réclamations/Guide réclamation.docx", "guide",
                "Base 2026/Cartes bancaires/presentation.mp4", "video")))));
        assertEquals(4, p1.fileCount());

        var preview = service.analyse(qa, p1.id(), "GENERAL", false);
        assertFalse(preview.applied());
        assertEquals(3, preview.added());
        assertTrue(atts.isEmpty(), "l'aperçu n'écrit rien");

        var r1 = service.dispatch(qa, p1.id(), "GENERAL", false);
        assertEquals(3, r1.added());
        assertEquals(1, r1.skipped());
        assertEquals(1, r1.newCategories());
        assertEquals("Cartes bancaires", item(r1, "Procédure carte.pdf").category());
        assertEquals("Côte d'Ivoire", item(r1, "Tarifs cartes.pdf").country());
        assertEquals("Réclamations", item(r1, "Guide réclamation.docx").category());
        assertTrue(item(r1, "presentation.mp4").detail().contains("Format"));
        assertEquals(3, atts.size());

        var p2 = service.upload(qa, new MockMultipartFile("file", "base-nov.zip", "application/zip", zip(new LinkedHashMap<>(Map.of(
                "Cartes bancaires/Procédure carte v2.pdf", "version B",
                "Cartes bancaires/Côte d'Ivoire/Tarifs cartes.pdf", "tarifs",
                "Cartes bancaires/Nouveau guide Visa.pdf", "visa")))));
        var r2 = service.dispatch(qa, p2.id(), "GENERAL", false);
        assertEquals("UPDATE", item(r2, "Procédure carte v2.pdf").action());
        assertEquals("UNCHANGED", item(r2, "Tarifs cartes.pdf").action());
        assertEquals("ADD", item(r2, "Nouveau guide Visa.pdf").action());
        assertEquals(4, atts.size(), "la nouvelle version remplace l'ancienne, elle ne s'ajoute pas");
        assertTrue(atts.stream().anyMatch(a -> a.getFileName().equals("Procédure carte v2.pdf")));
        assertTrue(atts.stream().noneMatch(a -> a.getFileName().equals("Procédure carte.pdf")));
    }

    @Test
    void adminCannotDispatchIntoTheGeneralBase() throws Exception {
        var admin = new AuthenticatedUser("admin", "admin", null, "Admin");
        var p = service.upload(admin, new MockMultipartFile("file", "a.zip", "application/zip", zip(Map.of("Cartes bancaires/a.pdf", "x"))));
        var r = service.dispatch(admin, p.id(), "GENERAL", false);
        assertEquals(1, r.skipped());
        assertTrue(atts.isEmpty());
    }
}
