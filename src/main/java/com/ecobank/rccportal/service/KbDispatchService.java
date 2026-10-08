package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.KnowledgeCategoryRequest;
import com.ecobank.rccportal.model.Attachment;
import com.ecobank.rccportal.model.KnowledgeArticle;
import com.ecobank.rccportal.model.KnowledgeCategory;
import com.ecobank.rccportal.model.KnowledgeCountry;
import com.ecobank.rccportal.repository.AttachmentRepository;
import com.ecobank.rccportal.repository.KnowledgeArticleRepository;
import com.ecobank.rccportal.repository.KnowledgeCategoryRepository;
import com.ecobank.rccportal.repository.KnowledgeCountryRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bibliothèque de dispatching de la Base de connaissances : la QA dépose un ZIP, puis « Dispatching » range chaque
 * fichier dans sa rubrique (et sa filiale) d'après l'arborescence du ZIP.
 *
 * <pre>
 *   Rubrique/fichier.pdf                    → rubrique « Rubrique », toutes filiales
 *   Rubrique/Côte d'Ivoire/fichier.pdf      → même rubrique, filiale CI   (ou Filiale/Rubrique/…)
 *   CIB/Rubrique/fichier.pdf                → base CIB
 *   fichier.pdf (à la racine)               → rubrique dont le nom ressemble le plus au titre du fichier, sinon non classé
 * </pre>
 *
 * Mise à jour réelle, par titre : un fichier porte une « clé de titre » (nom sans extension, sans version ni date :
 * « Procédure carte v2.pdf » = « Procédure carte (MAJ 2026).docx »). Même clé dans la même rubrique et filiale →
 * le fichier en place est remplacé si son contenu a changé, laissé tel quel s'il est identique ; sinon il est ajouté.
 * Une rubrique inconnue est créée. Option : retirer les fichiers déjà dispatchés qui ne figurent plus dans le ZIP.
 * Chaque ZIP est conservé (bibliothèque) avec le compte rendu de son dernier dispatching.
 */
@Slf4j
@Service
public class KbDispatchService {

    static final String ENTITY = "KnowledgeArticle";
    static final Set<String> ALLOWED = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "png", "jpg", "jpeg");
    static final long MAX_FILE = 20L * 1024 * 1024;
    static final int MAX_ENTRIES = 5000;
    static final long MAX_TOTAL = 2L * 1024 * 1024 * 1024;

    public record Item(String path, String fileName, String space, Integer categoryId, String category, boolean newCategory,
                       String country, String action, String detail) {}

    public record Report(Integer packageId, String packageName, boolean applied, int added, int updated, int unchanged,
                         int skipped, int removed, int newCategories, List<Item> items, String at, String by) {}

    public record Package(Integer id, String fileName, long sizeBytes, int fileCount, String uploadedBy, LocalDateTime uploadedAt,
                          LocalDateTime lastDispatchAt, String lastDispatchBy, String lastSummary) {}

    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledge;
    private final KnowledgeCategoryRepository categories;
    private final KnowledgeCountryRepository countries;
    private final KnowledgeArticleRepository articles;
    private final AttachmentRepository attachments;
    private final AttachmentService attachmentService;
    private final DocumentStorageService storage;
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean ready;

    @Value("${rcc.uploads.kb-files-dir}")
    private String kbFilesDir;

    public KbDispatchService(JdbcTemplate jdbc, KnowledgeService knowledge, KnowledgeCategoryRepository categories,
                             KnowledgeCountryRepository countries, KnowledgeArticleRepository articles,
                             AttachmentRepository attachments, AttachmentService attachmentService, DocumentStorageService storage) {
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.categories = categories;
        this.countries = countries;
        this.articles = articles;
        this.attachments = attachments;
        this.attachmentService = attachmentService;
        this.storage = storage;
    }

    // ── Schéma ───────────────────────────────────────────────────────────────────────

    private void ensureSchema() {
        if (ready) return;
        jdbc.execute("IF OBJECT_ID('dbo.KbDispatchPackages','U') IS NULL CREATE TABLE dbo.KbDispatchPackages ("
                + "Id INT IDENTITY PRIMARY KEY, FileName NVARCHAR(260) NOT NULL, StoredName NVARCHAR(100) NOT NULL, SizeBytes BIGINT NOT NULL,"
                + "FileCount INT NOT NULL, UploadedBy NVARCHAR(100) NULL, UploadedAt DATETIME2 NOT NULL DEFAULT SYSDATETIME(),"
                + "LastDispatchAt DATETIME2 NULL, LastDispatchBy NVARCHAR(100) NULL, LastSummary NVARCHAR(400) NULL, LastReport NVARCHAR(MAX) NULL)");
        // Fichiers posés par le dispatching : empreinte du contenu (détection des changements) et origine.
        jdbc.execute("IF OBJECT_ID('dbo.KbDispatchFiles','U') IS NULL CREATE TABLE dbo.KbDispatchFiles ("
                + "AttachmentId INT PRIMARY KEY, CategoryId INT NOT NULL, CountryCode NVARCHAR(2) NULL, TitleKey NVARCHAR(260) NOT NULL,"
                + "Sha256 NVARCHAR(64) NOT NULL, PackageId INT NULL, UpdatedAt DATETIME2 NOT NULL DEFAULT SYSDATETIME())");
        ready = true;
    }

    private Path libraryDir() throws IOException {
        // À côté du dossier des fichiers de la base, jamais dedans : /kb-files/ est servi publiquement.
        Path base = Path.of(kbFilesDir).toAbsolutePath();
        Path dir = (base.getParent() != null ? base.getParent() : base).resolve("rcc-kb-dispatch");
        Files.createDirectories(dir);
        return dir;
    }

    // ── Droits ───────────────────────────────────────────────────────────────────────

    static boolean isQa(AuthenticatedUser r) {
        return r != null && r.service() != null && "quality assurance".equals(r.service().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    static boolean isAdmin(AuthenticatedUser r) {
        return r != null && "admin".equalsIgnoreCase(r.role());
    }

    private void requireManager(AuthenticatedUser r) {
        if (!isQa(r) && !isAdmin(r)) throw ApiException.forbidden("La bibliothèque de dispatching est réservée à la Quality Assurance et à l'administrateur.");
    }

    // ── Bibliothèque ─────────────────────────────────────────────────────────────────

    public Package upload(AuthenticatedUser requester, MultipartFile zip) {
        requireManager(requester);
        ensureSchema();
        if (zip == null || zip.isEmpty()) throw ApiException.badRequest("Choisissez un fichier ZIP.");
        String name = zip.getOriginalFilename() == null ? "base.zip" : baseName(zip.getOriginalFilename());
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) throw ApiException.badRequest("Le fichier doit être une archive .zip.");
        try {
            String stored = UUID.randomUUID() + ".zip";
            Path target = libraryDir().resolve(stored);
            zip.transferTo(target.toFile());
            int count;
            try {
                count = (int) readEntries(target).stream().filter(e -> !e.directory).count();
            } catch (RuntimeException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            if (count == 0) {
                Files.deleteIfExists(target);
                throw ApiException.badRequest("Ce ZIP ne contient aucun fichier.");
            }
            jdbc.update("INSERT INTO dbo.KbDispatchPackages (FileName, StoredName, SizeBytes, FileCount, UploadedBy) VALUES (?, ?, ?, ?, ?)",
                    cut(name, 260), stored, Files.size(target), count, requester.username());
            Integer id = jdbc.queryForObject("SELECT MAX(Id) FROM dbo.KbDispatchPackages WHERE StoredName = ?", Integer.class, stored);
            log.info("[KB DISPATCH] ZIP « {} » ({} fichier(s)) déposé par {}", name, count, requester.username());
            return get(id);
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible d'enregistrer le ZIP : " + e.getMessage());
        }
    }

    public List<Package> list(AuthenticatedUser requester) {
        requireManager(requester);
        ensureSchema();
        return jdbc.query("SELECT * FROM dbo.KbDispatchPackages ORDER BY UploadedAt DESC", (rs, i) -> new Package(rs.getInt("Id"),
                rs.getString("FileName"), rs.getLong("SizeBytes"), rs.getInt("FileCount"), rs.getString("UploadedBy"),
                ts(rs.getTimestamp("UploadedAt")), ts(rs.getTimestamp("LastDispatchAt")), rs.getString("LastDispatchBy"), rs.getString("LastSummary")));
    }

    public void delete(AuthenticatedUser requester, int id) {
        requireManager(requester);
        ensureSchema();
        String stored = storedName(id);
        jdbc.update("DELETE FROM dbo.KbDispatchPackages WHERE Id = ?", id);
        try { Files.deleteIfExists(libraryDir().resolve(stored)); } catch (IOException ignore) { /* fichier déjà absent */ }
    }

    public Report lastReport(AuthenticatedUser requester, int id) {
        requireManager(requester);
        ensureSchema();
        String raw = jdbc.queryForObject("SELECT LastReport FROM dbo.KbDispatchPackages WHERE Id = ?", String.class, id);
        if (raw == null) throw ApiException.notFound("Ce ZIP n'a pas encore été dispatché.");
        try { return json.readValue(raw, Report.class); } catch (IOException e) { throw ApiException.notFound("Compte rendu illisible."); }
    }

    private Package get(Integer id) {
        return jdbc.query("SELECT * FROM dbo.KbDispatchPackages WHERE Id = ?", (rs, i) -> new Package(rs.getInt("Id"),
                rs.getString("FileName"), rs.getLong("SizeBytes"), rs.getInt("FileCount"), rs.getString("UploadedBy"),
                ts(rs.getTimestamp("UploadedAt")), ts(rs.getTimestamp("LastDispatchAt")), rs.getString("LastDispatchBy"), rs.getString("LastSummary")), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("ZIP introuvable dans la bibliothèque."));
    }

    private String storedName(int id) {
        return jdbc.query("SELECT StoredName FROM dbo.KbDispatchPackages WHERE Id = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("ZIP introuvable dans la bibliothèque."));
    }

    // ── Analyse et dispatching ───────────────────────────────────────────────────────

    /** Aperçu : ce que ferait le dispatching, sans rien écrire. */
    public Report analyse(AuthenticatedUser requester, int id, String space, boolean removeMissing) {
        return run(requester, id, space, false, removeMissing);
    }

    /** Dispatching réel. */
    public Report dispatch(AuthenticatedUser requester, int id, String space, boolean removeMissing) {
        return run(requester, id, space, true, removeMissing);
    }

    private record Planned(ZipEntryRef entry, String space, String categoryName, KnowledgeCategory category, KnowledgeCountry country,
                           String titleKey, String action, String detail, Attachment existing, String sha) {}

    private Report run(AuthenticatedUser requester, int id, String requestedSpace, boolean apply, boolean removeMissing) {
        requireManager(requester);
        ensureSchema();
        Package pkg = get(id);
        String defaultSpace = KnowledgeService.CIB.equalsIgnoreCase(requestedSpace) ? KnowledgeService.CIB : KnowledgeService.GENERAL;
        Path zipPath;
        try { zipPath = libraryDir().resolve(storedName(id)); } catch (IOException e) { throw ApiException.serviceUnavailable("Bibliothèque indisponible."); }
        if (!Files.exists(zipPath)) throw ApiException.notFound("Le fichier ZIP n'est plus sur le serveur : déposez-le à nouveau.");

        List<ZipEntryRef> entries = readEntries(zipPath).stream().filter(e -> !e.directory && !isJunk(e.name)).toList();
        List<String[]> paths = entries.stream().map(e -> splitPath(e.name)).toList();
        List<KnowledgeCategory> allCats = categories.findAll();
        List<KnowledgeCountry> allCountries = countries.findAll();
        int unwrap = commonWrapperDepth(seg -> matchCountry(seg, allCountries) != null
                || matchCategory(cleanFolder(seg), KnowledgeService.GENERAL, allCats) != null
                || matchCategory(cleanFolder(seg), KnowledgeService.CIB, allCats) != null, paths);
        Map<String, KnowledgeCategory> plannedNew = new LinkedHashMap<>();
        List<Planned> plan = new ArrayList<>();
        Map<String, Planned> byKey = new LinkedHashMap<>();

        try (ZipFile zf = openZip(zipPath)) {
            for (int n = 0; n < entries.size(); n++) {
                ZipEntryRef e = entries.get(n);
                String[] parts = paths.get(n);
                String fileName = parts[parts.length - 1];
                String space = defaultSpace;
                KnowledgeCountry country = null;
                String catName = null;
                for (int i = unwrap; i < parts.length - 1; i++) {
                    String seg = parts[i];
                    String ns = norm(seg);
                    if (ns.equals("cib") || ns.equals("base cib")) { space = KnowledgeService.CIB; continue; }
                    if (ns.equals("general") || ns.equals("base generale") || ns.equals("base de connaissance")) { space = KnowledgeService.GENERAL; continue; }
                    KnowledgeCountry c = country == null ? matchCountry(seg, allCountries) : null;
                    if (c != null) { country = c; continue; }
                    if (catName == null) catName = cleanFolder(seg);
                }
                String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                String key = titleKey(fileName);
                KnowledgeCategory cat = null;
                boolean isNew = false;
                if (catName != null) {
                    cat = matchCategory(catName, space, allCats);
                    if (cat == null) {
                        String k = space + "|" + norm(catName);
                        String title = cut(catName, 100);
                        cat = plannedNew.computeIfAbsent(k, x -> KnowledgeCategory.builder().title(title).team(KnowledgeService.CIB.equals(spaceOf(x)) ? KnowledgeService.CIB : null).build());
                        isNew = true;
                    }
                } else {
                    cat = guessCategory(key, space, allCats);
                }
                if (cat != null && cat.getCategoryId() != null) space = isCib(cat) ? KnowledgeService.CIB : KnowledgeService.GENERAL;

                String action, detail = null;
                Attachment existing = null;
                String sha = null;
                if (!ALLOWED.contains(ext)) { action = "SKIP"; detail = "Format non pris en charge (." + ext + ")"; }
                else if (e.size > MAX_FILE) { action = "SKIP"; detail = "Fichier trop lourd (" + (e.size / 1024 / 1024) + " Mo, maximum 20 Mo)"; }
                else if (cat == null) { action = "SKIP"; detail = "Non classé : placez-le dans un dossier au nom de sa rubrique"; }
                else if (!isQa(requester) && !KnowledgeService.CIB.equals(space)) { action = "SKIP"; detail = "Base générale : dispatching réservé à la Quality Assurance"; }
                else {
                    byte[] bytes = readEntry(zf, e);
                    sha = sha256(bytes);
                    existing = cat.getCategoryId() == null ? null : findExisting(cat, country, key);
                    if (existing == null) action = "ADD";
                    else {
                        String old = currentHash(existing);
                        if (sha.equals(old)) { action = "UNCHANGED"; detail = "Contenu identique à « " + existing.getFileName() + " »"; }
                        else { action = "UPDATE"; detail = "Remplace « " + existing.getFileName() + " »"; }
                    }
                }
                Planned p = new Planned(e, space, cat == null ? null : cat.getTitle(), cat, country, key, action, detail, existing, sha);
                // Deux fichiers du ZIP pour le même titre au même endroit : le dernier modifié l'emporte.
                if (!"SKIP".equals(action)) {
                    String slot = (cat.getCategoryId() != null ? "c" + cat.getCategoryId() : "n" + cat.getTitle()) + "|" + (country == null ? "" : country.getCountryCode()) + "|" + key;
                    Planned prev = byKey.get(slot);
                    if (prev != null) {
                        Planned older = prev.entry.time <= e.time ? prev : p;
                        Planned newer = older == prev ? p : prev;
                        plan.set(plan.indexOf(older), withAction(older, "SKIP", "Doublon de « " + newer.entry.name + " » (version la plus récente gardée)"));
                        byKey.put(slot, newer);
                        if (older == p) { plan.add(withAction(p, "SKIP", "Doublon de « " + prev.entry.name + " » (version la plus récente gardée)")); continue; }
                    } else byKey.put(slot, p);
                }
                plan.add(p);
            }

            List<Item> items = new ArrayList<>();
            int added = 0, updated = 0, unchanged = 0, skipped = 0, removed = 0;
            Map<String, KnowledgeCategory> created = new HashMap<>();
            Map<Integer, int[]> perCategory = new LinkedHashMap<>();
            Map<Integer, KnowledgeArticle> lastContainer = new HashMap<>();
            Map<Integer, String> catTitles = new HashMap<>();
            Set<Integer> keptAttachments = new HashSet<>();
            Set<String> touched = new HashSet<>();
            for (Planned p : plan) {
                String action = p.action;
                String detail = p.detail;
                KnowledgeCategory cat = p.category;
                if (apply && !"SKIP".equals(action)) {
                    try {
                        if (cat.getCategoryId() == null) cat = created.computeIfAbsent(p.space + "|" + norm(cat.getTitle()), k -> createCategory(p.category, p.space));
                        touched.add(cat.getCategoryId() + "|" + (p.country == null ? "" : p.country.getCountryCode()));
                        String name = baseName(p.entry.name);
                        if ("ADD".equals(action)) {
                            KnowledgeArticle container = knowledge.findOrCreateContainer(cat.getCategoryId(), null,
                                    p.country == null ? null : p.country.getCountryCode(), null);
                            String url = storage.store(new BytesFile(name, readEntry(zf, p.entry)));
                            var att = attachmentService.attachLocalFile(ENTITY, container.getArticleId(), name, mime(name), url, requester.username());
                            track(att.id(), cat, p.country, p.titleKey, p.sha, id);
                            keptAttachments.add(att.id());
                            lastContainer.put(cat.getCategoryId(), container);
                        } else if ("UPDATE".equals(action)) {
                            String url = storage.store(new BytesFile(name, readEntry(zf, p.entry)));
                            Attachment a = attachments.findById(p.existing.getAttachmentId()).orElseThrow();
                            a.setFileName(cut(name, 260));
                            a.setMimeType(mime(name));
                            a.setStorageUrl(url);
                            attachments.save(a);
                            track(a.getAttachmentId(), cat, p.country, p.titleKey, p.sha, id);
                            keptAttachments.add(a.getAttachmentId());
                            KnowledgeCategory cc = cat;
                            articles.findById(a.getEntityId()).ifPresent(art -> lastContainer.put(cc.getCategoryId(), art));
                        } else {
                            keptAttachments.add(p.existing.getAttachmentId());
                            track(p.existing.getAttachmentId(), cat, p.country, p.titleKey, p.sha, id);
                        }
                    } catch (RuntimeException ex) {
                        action = "SKIP";
                        detail = "Échec : " + ex.getMessage();
                        log.warn("[KB DISPATCH] {} : {}", p.entry.name, ex.getMessage());
                    }
                }
                switch (action) {
                    case "ADD" -> added++;
                    case "UPDATE" -> updated++;
                    case "UNCHANGED" -> unchanged++;
                    default -> skipped++;
                }
                if (cat != null && cat.getCategoryId() != null && ("ADD".equals(action) || "UPDATE".equals(action))) {
                    int[] c = perCategory.computeIfAbsent(cat.getCategoryId(), k -> new int[2]);
                    c["ADD".equals(action) ? 0 : 1]++;
                    catTitles.put(cat.getCategoryId(), cat.getTitle());
                }
                items.add(new Item(p.entry.name, baseName(p.entry.name), p.space,
                        cat == null ? null : cat.getCategoryId(), cat == null ? null : cat.getTitle(),
                        p.category != null && p.category.getCategoryId() == null, p.country == null ? null : p.country.getLabel(), action, detail));
            }

            // Fichiers déjà dispatchés qui ne figurent plus dans le ZIP (seulement dans les rubriques / filiales du ZIP).
            if (removeMissing) {
                Set<String> scope = new HashSet<>(touched);
                if (!apply) for (Planned p : plan) if (p.category != null && p.category.getCategoryId() != null && !"SKIP".equals(p.action))
                    scope.add(p.category.getCategoryId() + "|" + (p.country == null ? "" : p.country.getCountryCode()));
                Set<Integer> keep = new HashSet<>(keptAttachments);
                if (!apply) for (Planned p : plan) if (p.existing != null) keep.add(p.existing.getAttachmentId());
                for (Map<String, Object> r : jdbc.queryForList("SELECT AttachmentId, CategoryId, CountryCode, TitleKey FROM dbo.KbDispatchFiles")) {
                    int attId = ((Number) r.get("AttachmentId")).intValue();
                    String sc = r.get("CategoryId") + "|" + (r.get("CountryCode") == null ? "" : r.get("CountryCode"));
                    if (!scope.contains(sc) || keep.contains(attId)) continue;
                    Optional<Attachment> a = attachments.findById(attId);
                    if (a.isEmpty()) { jdbc.update("DELETE FROM dbo.KbDispatchFiles WHERE AttachmentId = ?", attId); continue; }
                    if (apply) {
                        attachments.delete(a.get());
                        jdbc.update("DELETE FROM dbo.KbDispatchFiles WHERE AttachmentId = ?", attId);
                    }
                    removed++;
                    KnowledgeCategory c = categories.findById(((Number) r.get("CategoryId")).intValue()).orElse(null);
                    items.add(new Item(null, a.get().getFileName(), c != null && isCib(c) ? KnowledgeService.CIB : KnowledgeService.GENERAL,
                            c == null ? null : c.getCategoryId(), c == null ? null : c.getTitle(), false, null, "REMOVE", "Absent du nouveau ZIP"));
                }
            }

            if (apply) {
                for (var en : perCategory.entrySet()) {
                    KnowledgeArticle container = lastContainer.get(en.getKey());
                    if (container == null) continue;
                    int[] c = en.getValue();
                    String what = (c[0] > 0 ? c[0] + " nouveau(x) fichier(s)" : "") + (c[0] > 0 && c[1] > 0 ? " et " : "") + (c[1] > 0 ? c[1] + " fichier(s) mis à jour" : "");
                    knowledge.notifyDispatch(container, "📚 Base de connaissances mise à jour : " + what + " dans « " + catTitles.get(en.getKey()) + " ».");
                }
            }

            Report report = new Report(id, pkg.fileName(), apply, added, updated, unchanged, skipped, removed, plannedNew.size(), items,
                    LocalDateTime.now().toString(), requester.username());
            if (apply) {
                String summary = added + " ajouté(s), " + updated + " mis à jour, " + unchanged + " inchangé(s), " + skipped + " ignoré(s)"
                        + (removed > 0 ? ", " + removed + " retiré(s)" : "") + (plannedNew.isEmpty() ? "" : ", " + plannedNew.size() + " rubrique(s) créée(s)");
                jdbc.update("UPDATE dbo.KbDispatchPackages SET LastDispatchAt = SYSDATETIME(), LastDispatchBy = ?, LastSummary = ?, LastReport = ? WHERE Id = ?",
                        requester.username(), cut(summary, 400), toJson(report), id);
                log.info("[KB DISPATCH] « {} » dispatché par {} : {}", pkg.fileName(), requester.username(), summary);
            }
            return report;
        } catch (IOException e) {
            throw ApiException.badRequest("Lecture du ZIP impossible : " + e.getMessage());
        }
    }

    private static Planned withAction(Planned p, String action, String detail) {
        return new Planned(p.entry, p.space, p.categoryName, p.category, p.country, p.titleKey, action, detail, null, p.sha);
    }

    private static String spaceOf(String plannedKey) {
        return plannedKey.substring(0, plannedKey.indexOf('|'));
    }

    private KnowledgeCategory createCategory(KnowledgeCategory draft, String space) {
        String base = norm(draft.getTitle()).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (base.isBlank()) base = "RUBRIQUE";
        if (base.length() > 40) base = base.substring(0, 40);
        String team = KnowledgeService.CIB.equals(space) ? KnowledgeService.CIB : null;
        String prefix = team != null && !base.startsWith("CIB_") ? "CIB_" : "";
        String code = prefix + base;
        for (int i = 2; categories.findByCodeIgnoreCase(code).isPresent(); i++) code = prefix + base + "_" + i;
        Integer max = jdbc.queryForObject("SELECT ISNULL(MAX(SortOrder), 0) FROM dbo.KnowledgeCategories", Integer.class);
        var r = knowledge.createCategory(new KnowledgeCategoryRequest(code, draft.getTitle(), "bi-folder2-open", (max == null ? 0 : max) + 1, team));
        return categories.findById(r.categoryId()).orElseThrow();
    }

    private void track(Integer attachmentId, KnowledgeCategory cat, KnowledgeCountry country, String key, String sha, int packageId) {
        jdbc.update("""
                MERGE dbo.KbDispatchFiles AS t USING (SELECT ? AS AttachmentId) AS s ON t.AttachmentId = s.AttachmentId
                WHEN MATCHED THEN UPDATE SET CategoryId = ?, CountryCode = ?, TitleKey = ?, Sha256 = ?, PackageId = ?, UpdatedAt = SYSDATETIME()
                WHEN NOT MATCHED THEN INSERT (AttachmentId, CategoryId, CountryCode, TitleKey, Sha256, PackageId) VALUES (?, ?, ?, ?, ?, ?);""",
                attachmentId, cat.getCategoryId(), country == null ? null : country.getCountryCode(), cut(key, 260), sha, packageId,
                attachmentId, cat.getCategoryId(), country == null ? null : country.getCountryCode(), cut(key, 260), sha, packageId);
    }

    /** Fichier déjà présent dans la rubrique (même filiale) avec la même clé de titre — quel que soit son dossier. */
    private Attachment findExisting(KnowledgeCategory cat, KnowledgeCountry country, String key) {
        List<KnowledgeArticle> list = country == null
                ? articles.findByCategoryAndCountryIsNullOrderBySortOrderAsc(cat)
                : articles.findByCategoryAndCountry_CountryCodeOrderBySortOrderAsc(cat, country.getCountryCode());
        for (KnowledgeArticle a : list)
            for (Attachment att : attachments.findByEntityTypeAndEntityId(ENTITY, a.getArticleId()))
                if (att.getFileName() != null && key.equals(titleKey(att.getFileName()))) return att;
        return null;
    }

    private String currentHash(Attachment a) {
        List<String> known = jdbc.query("SELECT Sha256 FROM dbo.KbDispatchFiles WHERE AttachmentId = ?", (rs, i) -> rs.getString(1), a.getAttachmentId());
        if (!known.isEmpty()) return known.get(0);
        try {
            if (a.getStorageUrl() != null && a.getStorageUrl().startsWith("/kb-files/")) return sha256(storage.open(a.getStorageUrl(), a.getFileName()).getBytes());
        } catch (Exception ignore) { /* fichier absent du disque : considéré comme différent */ }
        return "";
    }

    // ── Correspondances ──────────────────────────────────────────────────────────────

    static boolean isCib(KnowledgeCategory c) {
        return KnowledgeService.CIB.equalsIgnoreCase(c.getTeam());
    }

    private static boolean inSpace(KnowledgeCategory c, String space) {
        return KnowledgeService.CIB.equals(space) == isCib(c);
    }

    /** Rubrique existante de l'espace : titre ou code identiques (sans accents ni ponctuation), sinon mots en commun. */
    static KnowledgeCategory matchCategory(String folder, String space, List<KnowledgeCategory> all) {
        String n = norm(folder);
        String asCode = n.replace(' ', '_');
        for (KnowledgeCategory c : all) {
            if (!inSpace(c, space)) continue;
            String code = c.getCode() == null ? "" : c.getCode().toLowerCase(Locale.ROOT);
            if (n.equals(norm(c.getTitle())) || asCode.equals(code) || ("cib_" + asCode).equals(code)) return c;
        }
        KnowledgeCategory best = null;
        double bestScore = 0;
        for (KnowledgeCategory c : all) {
            if (!inSpace(c, space)) continue;
            double s = similarity(n, norm(c.getTitle()));
            if (s > bestScore) { bestScore = s; best = c; }
        }
        return bestScore >= 0.6 ? best : null;
    }

    /** Fichier à la racine du ZIP : rubrique dont le titre partage le plus de mots avec le titre du fichier. */
    static KnowledgeCategory guessCategory(String key, String space, List<KnowledgeCategory> all) {
        Set<String> words = words(key);
        KnowledgeCategory best = null;
        double bestScore = 0;
        for (KnowledgeCategory c : all) {
            if (!inSpace(c, space)) continue;
            Set<String> cw = words(norm(c.getTitle()));
            if (cw.isEmpty()) continue;
            long common = cw.stream().filter(words::contains).count();
            double s = (double) common / cw.size();
            if (common > 0 && s > bestScore) { bestScore = s; best = c; }
        }
        return bestScore >= 0.5 ? best : null;
    }

    static KnowledgeCountry matchCountry(String folder, List<KnowledgeCountry> all) {
        String n = norm(folder).replaceFirst("^(rcc|filiale|ecobank)\\s+", "").trim();
        String compact = n.replace(" ", "");
        for (KnowledgeCountry c : all) {
            String code = c.getCountryCode().toLowerCase(Locale.ROOT);
            if (compact.equals(code) || compact.equals("e" + code) || n.equals(norm(c.getLabel()))) return c;
        }
        String iso = switch (compact) {
            case "civ", "cotedivoire", "ivoire" -> "ci";
            case "tgo" -> "tg";
            default -> null;
        };
        if (iso != null) for (KnowledgeCountry c : all) if (c.getCountryCode().equalsIgnoreCase(iso)) return c;
        return null;
    }

    static final Set<String> STOP = Set.of("de", "du", "des", "la", "le", "les", "et", "a", "au", "aux", "en", "d", "l", "pour", "sur", "un", "une");

    static Set<String> words(String normalized) {
        Set<String> out = new HashSet<>();
        for (String w : normalized.split(" ")) if (w.length() > 1 && !STOP.contains(w)) out.add(w.endsWith("s") && w.length() > 3 ? w.substring(0, w.length() - 1) : w);
        return out;
    }

    static double similarity(String a, String b) {
        Set<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    /** Minuscules, sans accents, ponctuation → espaces. */
    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return n.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** « 01 - Cartes bancaires » → « Cartes bancaires » (numéro de tri retiré). */
    static String cleanFolder(String s) {
        String t = s.replaceFirst("^\\s*\\d{1,3}\\s*[-_.)]\\s*", "").replace('_', ' ').trim();
        return t.isEmpty() ? s.trim() : t;
    }

    /**
     * Clé de titre d'un fichier : nom sans extension, sans version, date ni mention de copie.
     * « Procédure_carte_V2 (final) 2026-10-01.pdf » → « procedure carte ».
     */
    static String titleKey(String fileName) {
        String base = fileName.replaceAll("^.*[/\\\\]", "");
        if (base.contains(".")) base = base.substring(0, base.lastIndexOf('.'));
        String n = norm(base);
        n = n.replaceAll("\\b(19|20)\\d{2}\\s\\d{1,2}\\s\\d{1,2}\\b", " ")
                .replaceAll("\\b\\d{1,2}\\s\\d{1,2}\\s(19|20)\\d{2}\\b", " ")
                .replaceAll("\\b(19|20)\\d{6}\\b", " ")
                .replaceAll("\\b(v|version|ver|rev)\\s?\\d+(\\s\\d+)*\\b", " ")
                .replaceAll("\\b(final|finale|definitif|definitive|maj|mise a jour|nouveau|nouvelle|new|copie|copy|draft|brouillon|vf)\\b", " ")
                .replaceAll("\\b(19|20)\\d{2}\\b", " ")
                .replaceAll("\\s\\d{1,2}$", " ");
        n = n.trim().replaceAll("\\s+", " ");
        return n.isEmpty() ? norm(base) : n;
    }

    // ── ZIP ──────────────────────────────────────────────────────────────────────────

    record ZipEntryRef(String name, long size, boolean directory, long time) {}

    private static ZipFile openZip(Path p) throws IOException {
        ZipFile z = null;
        try {
            z = new ZipFile(p.toFile(), StandardCharsets.UTF_8);
            z.stream().forEach(e -> { }); // force la lecture des noms (accents)
            return z;
        } catch (IllegalArgumentException | java.util.zip.ZipException e) {
            if (z != null) try { z.close(); } catch (IOException ignore) { /* fermeture */ }
            // ZIP créé par l'Explorateur Windows : noms en IBM850
            return new ZipFile(p.toFile(), Charset.forName("IBM850"));
        }
    }

    static List<ZipEntryRef> readEntries(Path p) {
        try (ZipFile z = openZip(p)) {
            List<ZipEntryRef> out = new ArrayList<>();
            long total = 0;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (name.contains("../") || name.startsWith("/")) continue;
                out.add(new ZipEntryRef(name, Math.max(0, e.getSize()), e.isDirectory(), e.getTime()));
                total += Math.max(0, e.getSize());
                if (out.size() > MAX_ENTRIES) throw ApiException.badRequest("ZIP trop volumineux : plus de " + MAX_ENTRIES + " éléments.");
                if (total > MAX_TOTAL) throw ApiException.badRequest("ZIP trop volumineux une fois décompressé (plus de 2 Go).");
            }
            return out;
        } catch (IOException e) {
            throw ApiException.badRequest("Ce fichier n'est pas un ZIP lisible : " + e.getMessage());
        }
    }

    private static byte[] readEntry(ZipFile z, ZipEntryRef ref) throws IOException {
        ZipEntry e = z.getEntry(ref.name);
        if (e == null) throw new IOException("élément introuvable : " + ref.name);
        try (InputStream in = z.getInputStream(e)) {
            byte[] b = in.readNBytes((int) MAX_FILE + 1);
            if (b.length > MAX_FILE) throw new IOException("fichier de plus de 20 Mo : " + ref.name);
            return b;
        }
    }

    static boolean isJunk(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        String file = low.substring(low.lastIndexOf('/') + 1);
        return low.startsWith("__macosx/") || low.contains("/__macosx/") || file.startsWith(".") || file.startsWith("~$")
                || file.equals("thumbs.db") || file.equals("desktop.ini");
    }

    /** Nom du fichier sans son dossier (sans passer par le système de fichiers : accents et caractères spéciaux sûrs). */
    static String baseName(String path) {
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    static String[] splitPath(String name) {
        return Arrays.stream(name.split("/")).filter(s -> !s.isBlank()).toArray(String[]::new);
    }

    /** Dossier racine unique qui enveloppe tout le ZIP (« Base_2026/… ») : ignoré pour le classement. */
    static int commonWrapperDepth(java.util.function.Predicate<String> meaningful, List<String[]> paths) {
        int depth = 0;
        while (true) {
            String first = null;
            for (String[] p : paths) {
                if (p.length - 1 <= depth) return depth; // un fichier à ce niveau : pas d'enveloppe
                String seg = p[depth];
                if (first == null) first = seg;
                else if (!first.equals(seg)) return depth;
            }
            if (first == null) return depth;
            String n = norm(first);
            if (n.equals("cib") || n.equals("base cib") || meaningful.test(first)) return depth; // rubrique, filiale ou espace : à garder
            depth++;
            if (depth >= 2) return depth;
        }
    }

    // ── Outils ───────────────────────────────────────────────────────────────────────

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        return "application/octet-stream";
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    private static String cut(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }

    /** Fichier en mémoire transmis au stockage de la base (mêmes contrôles de format et de taille qu'un dépôt manuel). */
    record BytesFile(String name, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return mime(name); }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}
