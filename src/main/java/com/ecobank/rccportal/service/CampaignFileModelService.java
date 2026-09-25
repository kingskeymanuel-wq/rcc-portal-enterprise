package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CampaignFieldDto;
import com.ecobank.rccportal.dto.CampaignImportMappingDto;
import com.ecobank.rccportal.dto.CampaignImportReport;
import com.ecobank.rccportal.dto.CampaignRequest;
import com.ecobank.rccportal.dto.CampaignResponse;
import com.ecobank.rccportal.model.Campaign;
import com.ecobank.rccportal.repository.CampaignRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.util.*;

/**
 * « Nouvelle campagne depuis un fichier » : transforme n'importe quel fichier d'appels (liste
 * de clients à appeler, export Microsoft Forms d'une campagne en cours…) en campagne Outbound
 * prête à traiter, au même format que les modèles de campagne historiques :
 * <ol>
 *   <li>colonnes de base reconnues (client, téléphone, compte, agent, statut et date d'appel) ;</li>
 *   <li>chaque autre colonne renseignée devient une question du modèle, avec son type déduit
 *       des réponses : liste de choix (réponses regroupées malgré casse, accents et fautes :
 *       « LE CLIENT N'EST PLUS EN ACTIVITÉ » = « La cliente n'est plus en activité »), date,
 *       texte court ou long ;</li>
 *   <li>le Team Leader relit / ajuste, puis la campagne est créée et les contacts importés
 *       exactement comme pour « Réactivation des comptes dormants » (statuts repris, réponses
 *       rangées dans les options, agents reconnus, doublons, historique d'appels).</li>
 * </ol>
 * Si le fichier correspond déjà à une campagne existante (mêmes questions), elle est proposée
 * pour y importer plutôt que d'en créer une seconde.
 */
@Service
public class CampaignFileModelService {

    public record InferredField(CampaignFieldDto field, int column, String header, int answered, List<String> examples) {}

    public record SimilarCampaign(Integer campaignId, String name, int matchedQuestions, int totalQuestions) {}

    public record FileAnalysis(String suggestedName, int rows, List<String> headers, List<String> sampleRow,
                               CampaignImportMappingDto mapping, List<InferredField> fields, List<String> ignoredColumns,
                               List<SimilarCampaign> similarCampaigns) {}

    public record CreateFromFileRequest(String name, String description, String targetService, List<CampaignFieldDto> fields,
                                        CampaignImportMappingDto mapping) {}

    public record CreateFromFileResult(CampaignResponse campaign, CampaignImportReport report) {}

    private static final int MAX_OPTIONS = 30;
    private static final Set<String> ACRONYMS = Set.of("RDV", "CNI", "CIF", "GAB", "SMS", "O/N", "OUI/NON", "RIB", "IBAN", "ID", "TPE", "PME", "KYC", "CB");

    private final CampaignService campaigns;
    private final CampaignRepository campaignRepository;

    public CampaignFileModelService(CampaignService campaigns, CampaignRepository campaignRepository) {
        this.campaigns = campaigns;
        this.campaignRepository = campaignRepository;
    }

    // ── Analyse ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FileAnalysis analyze(AuthenticatedUser requester, MultipartFile file) {
        campaigns.requireCanManage(requester);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Le fichier est requis.");
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = campaigns.readHeaders(sheet.getRow(0));
            if (headers.isEmpty()) throw ApiException.badRequest("Le fichier est vide (aucun en-tête en première ligne).");
            Set<Integer> filled = campaigns.filledColumns(sheet, headers.size());
            CampaignImportMappingDto core = campaigns.buildSuggestedMapping(headers, List.of(), filled, Map.of());

            Set<Integer> coreCols = new HashSet<>();
            for (Integer c : Arrays.asList(core.nameColumn(), core.phoneColumn(), core.accountColumn(), core.agentColumn(),
                    core.statusColumn(), core.callDateColumn())) if (c != null) coreCols.add(c);

            Map<Integer, List<String>> values = allValues(sheet, headers.size());
            int rows = 0;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) if (sheet.getRow(r) != null) rows++;

            List<InferredField> fields = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            Set<String> usedIds = new HashSet<>();
            Map<Integer, String> fieldColumns = new LinkedHashMap<>();
            for (int col = 0; col < headers.size(); col++) {
                String header = headers.get(col);
                if (coreCols.contains(col) || header.isBlank()) continue;
                if (CampaignService.FORMS_METADATA.contains(campaigns.normalizeForMatch(header))) { ignored.add(header); continue; }
                List<String> answers = values.getOrDefault(col, List.of()).stream().filter(v -> !CampaignService.isNoAnswer(v)).toList();
                if (answers.isEmpty()) { ignored.add(header); continue; }
                if (looksLikeReferenceData(header)) continue; // agence d'ouverture, CIF… : info complémentaire, pas une question
                CampaignFieldDto field = inferField(header, answers, usedIds, headers, col);
                usedIds.add(field.id());
                fieldColumns.put(col, field.id());
                List<String> examples = answers.stream().distinct().limit(3).map(v -> v.length() > 60 ? v.substring(0, 60) + "…" : v).toList();
                fields.add(new InferredField(field, col, header, answers.size(), examples));
            }

            CampaignImportMappingDto mapping = new CampaignImportMappingDto(core.nameColumn(), core.phoneColumn(), core.accountColumn(),
                    core.agentColumn(), fieldColumns, core.statusColumn(), core.callDateColumn(), true, false);

            List<String> sample = new ArrayList<>();
            Row first = sheet.getRow(1);
            for (int col = 0; col < headers.size(); col++) {
                String v = first == null ? "" : campaigns.cellToString(first.getCell(col));
                sample.add(v.length() > 60 ? v.substring(0, 60) + "…" : v);
            }
            return new FileAnalysis(suggestName(file.getOriginalFilename()), rows, headers, sample, mapping, fields, ignored,
                    similarCampaigns(headers, fields));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier illisible : " + e.getMessage());
        }
    }

    /** Toutes les valeurs non vides de chaque colonne (plafond de 20 000 par colonne). */
    private Map<Integer, List<String>> allValues(Sheet sheet, int columns) {
        Map<Integer, List<String>> out = new HashMap<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int col = 0; col < columns; col++) {
                String v = campaigns.cellToString(row.getCell(col));
                if (v.isBlank() || v.equalsIgnoreCase("anonymous")) continue;
                List<String> list = out.computeIfAbsent(col, k -> new ArrayList<>());
                if (list.size() < 20_000) list.add(v);
            }
        }
        return out;
    }

    private boolean looksLikeReferenceData(String header) {
        String h = campaigns.normalizeForMatch(header);
        return h.length() <= 40 && (h.contains("agencedouverture") || h.equals("agence") || h.contains("cif") || h.contains("segment")
                || h.contains("gestionnaire") || h.contains("solde") || h.contains("produit") || h.contains("datedouverture"));
    }

    /** Type et réponses possibles d'une question, déduits des réponses du fichier. */
    CampaignFieldDto inferField(String header, List<String> answers, Set<String> usedIds, List<String> headers, int col) {
        String label = cleanLabel(header, headers, col);
        String id = uniqueId(label, usedIds);
        String h = campaigns.normalizeForMatch(header);
        long dates = answers.stream().filter(v -> v.matches("\\d{2}/\\d{2}/\\d{4}.*|\\d{4}-\\d{2}-\\d{2}.*")).count();
        if (dates >= answers.size() * 0.8) return new CampaignFieldDto(id, label, "DATE", List.of(), false);
        if (h.contains("commentaire") || h.contains("observation") || h.contains("remarque")) {
            return new CampaignFieldDto(id, label, "TEXTAREA", List.of(), false);
        }

        List<Cluster> clusters = cluster(answers);
        int n = answers.size();
        // Petite liste fermée (Oui / Non / Besoin de réfléchir…) : toutes les réponses deviennent des options.
        if (clusters.size() <= 6 && clusters.get(0).count >= 2) {
            return new CampaignFieldDto(id, label, "SELECT", orderOptions(clusters.stream().map(c -> c.label).toList()), false);
        }
        // Motifs récurrents : les réponses fréquentes deviennent la liste, le reste « Autre ».
        int threshold = Math.max(2, (int) Math.ceil(n * 0.005));
        List<Cluster> frequent = clusters.stream().filter(c -> c.count >= threshold).limit(MAX_OPTIONS).toList();
        int covered = frequent.stream().mapToInt(c -> c.count).sum();
        if (frequent.size() >= 2 && covered >= n * 0.6) {
            List<String> options = new ArrayList<>(frequent.stream().map(c -> c.label).toList());
            if (covered < n && options.stream().noneMatch(o -> campaigns.normalizeForMatch(o).equals("autre"))) options.add("Autre");
            return new CampaignFieldDto(id, label, "SELECT", options, false);
        }
        double avg = answers.stream().mapToInt(String::length).average().orElse(0);
        return new CampaignFieldDto(id, label, avg > 40 ? "TEXTAREA" : "TEXT", List.of(), false);
    }

    private static final class Cluster {
        final Set<String> tokens;
        final Map<String, Integer> spellings = new HashMap<>();
        int count;
        String label;

        Cluster(Set<String> tokens) { this.tokens = tokens; }
    }

    /** Regroupe les réponses équivalentes (casse, accents, ponctuation, une faute par mot). */
    private List<Cluster> cluster(List<String> answers) {
        Map<String, Integer> byNorm = new LinkedHashMap<>();
        Map<String, String> firstRaw = new HashMap<>();
        Map<String, Map<String, Integer>> rawCounts = new HashMap<>();
        for (String a : answers) {
            String k = campaigns.normalizeForMatch(a);
            if (k.isEmpty()) continue;
            byNorm.merge(k, 1, Integer::sum);
            firstRaw.putIfAbsent(k, a);
            rawCounts.computeIfAbsent(k, x -> new HashMap<>()).merge(a.trim(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byNorm.entrySet());
        sorted.sort((x, y) -> y.getValue() - x.getValue());
        List<Cluster> clusters = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            Set<String> tk = new LinkedHashSet<>(campaigns.tokens(firstRaw.get(e.getKey())));
            Cluster target = null;
            if (!tk.isEmpty() && clusters.size() < 400) {
                for (Cluster c : clusters) if (sameTokens(c.tokens, tk)) { target = c; break; }
            }
            if (target == null) { target = new Cluster(tk); clusters.add(target); }
            target.count += e.getValue();
            final Cluster into = target;
            rawCounts.get(e.getKey()).forEach((raw, cnt) -> into.spellings.merge(raw, cnt, Integer::sum));
        }
        for (Cluster c : clusters) {
            String best = c.spellings.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            String norm = campaigns.normalizeForMatch(best);
            c.label = Set.of("o", "oui", "yes", "y").contains(norm) ? "Oui" : Set.of("n", "non", "no").contains(norm) ? "Non" : prettify(best);
        }
        // Réponses Oui/Non écrites de plusieurs façons (« O », « OUI ») : un seul choix.
        Map<String, Cluster> byLabel = new LinkedHashMap<>();
        for (Cluster c : clusters) {
            Cluster same = byLabel.get(c.label);
            if (same == null) byLabel.put(c.label, c); else same.count += c.count;
        }
        clusters = new ArrayList<>(byLabel.values());
        stripSharedPrefix(clusters);
        clusters.sort((x, y) -> y.count - x.count);
        return clusters;
    }

    /** « Eci - agence Koumassi », « Eci - agence Marcory marche »… : le début commun à la plupart des
     *  réponses (« Eci - agence ») est retiré des options → « Koumassi », « Marcory marche ». */
    private void stripSharedPrefix(List<Cluster> clusters) {
        if (clusters.size() < 4) return;
        for (int pass = 0; pass < 3; pass++) {
            Map<String, Integer> firstWords = new HashMap<>();
            for (Cluster c : clusters) {
                String[] w = c.label.replace("-", " ").trim().split("\\s+");
                if (w.length > 1) firstWords.merge(w[0].toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
            Optional<Map.Entry<String, Integer>> top = firstWords.entrySet().stream().max(Map.Entry.comparingByValue());
            if (top.isEmpty() || top.get().getValue() < clusters.size() * 0.6 || top.get().getKey().length() < 2) return;
            String prefix = top.get().getKey();
            for (Cluster c : clusters) {
                String rest = c.label.replace("-", " ").trim();
                String[] w = rest.split("\\s+", 2);
                if (w.length == 2 && w[0].equalsIgnoreCase(prefix)) c.label = prettify(w[1].toUpperCase(Locale.FRENCH));
            }
        }
    }

    private static boolean sameTokens(Set<String> a, Set<String> b) {
        if (a.size() != b.size()) return false;
        for (String t : a) if (b.stream().noneMatch(o -> CampaignService.sameWord(t, o))) return false;
        return true;
    }

    /** « OUI » → « Oui », « BESOIN DE REFLECHIR » → « Besoin de reflechir », « MARCORY MARCHE » → « Marcory Marche ». */
    static String prettify(String raw) {
        String v = raw.replaceAll("\\s+", " ").replaceAll("\\s*-\\s*", " - ").replaceAll("^[\\s-]+|[\\s.;,-]+$", "").trim();
        if (v.isEmpty()) return v;
        boolean shouting = v.equals(v.toUpperCase(Locale.ROOT)) && v.chars().anyMatch(Character::isLetter);
        if (!shouting) return Character.toUpperCase(v.charAt(0)) + v.substring(1);
        String lower = v.toLowerCase(Locale.FRENCH);
        String[] words = lower.split(" ");
        if (words.length <= 3) { // noms propres courts (agences, produits)
            StringBuilder sb = new StringBuilder();
            for (String w : words) {
                if (sb.length() > 0) sb.append(' ');
                boolean small = sb.length() > 0 && Set.of("de", "des", "du", "la", "le", "les", "et", "en", "a", "au", "aux", "d", "l").contains(w);
                sb.append(w.isEmpty() || small ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
            }
            return sb.toString();
        }
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Oui / Non / Besoin de réfléchir en tête, dans cet ordre, comme dans les modèles historiques. */
    private List<String> orderOptions(List<String> options) {
        List<String> head = new ArrayList<>(), rest = new ArrayList<>();
        for (String key : List.of("oui", "non")) {
            options.stream().filter(o -> campaigns.normalizeForMatch(o).equals(key)).findFirst().ifPresent(head::add);
        }
        for (String o : options) if (!head.contains(o)) rest.add(o);
        head.addAll(rest);
        return head;
    }

    /** « Proposez le package . Le cllient est il interessé ? » → « Proposez le package. Le cllient est il interessé ? » ;
     *  colonnes répétées de Forms (« si non, pourquoi??2 ») → « Si non, pourquoi ? (2) ». */
    String cleanLabel(String header, List<String> headers, int col) {
        String h = header.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        String suffix = "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*\\?)\\s*(\\d+)$").matcher(h);
        if (m.matches()) { h = m.group(1); suffix = " (" + m.group(2) + ")"; }
        h = h.replaceAll("\\s+([.,])", "$1").replaceAll("\\?+", " ?").replaceAll("\\s+\\?", " ?").replaceAll("\\s+", " ").trim();
        // En-tête tout en majuscules (« DATE RAPPEL ») → « Date rappel », sigles conservés (RDV, CNI, O/N…).
        if (h.equals(h.toUpperCase(Locale.ROOT)) && h.chars().anyMatch(Character::isLetter)) {
            StringBuilder sb = new StringBuilder();
            for (String w : h.split(" ")) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(ACRONYMS.contains(w.replaceAll("[^A-Z/]", "")) ? w : w.toLowerCase(Locale.FRENCH));
            }
            h = sb.toString();
        }
        if (!h.isEmpty()) h = Character.toUpperCase(h.charAt(0)) + h.substring(1);
        String out = h + suffix;
        return out.length() > 200 ? out.substring(0, 199) + "…" : out;
    }

    private String uniqueId(String label, Set<String> used) {
        String base = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        StringBuilder id = new StringBuilder();
        for (String w : base.split("[^a-z0-9]+")) {
            if (w.length() < 3 || id.length() > 24) continue;
            id.append(id.length() == 0 ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        String candidate = id.length() == 0 ? "question" : id.toString();
        String unique = candidate;
        for (int i = 2; used.contains(unique); i++) unique = candidate + i;
        return unique;
    }

    /** Nom proposé à partir du nom de fichier : « CAMPAGNE_R_ACTIVATION_DE__COMPTES_DORMANTS_paul_hyacinthe_21-5000_10.xlsx »
     *  → « Campagne r activation de comptes dormants » (mots en minuscules et numéros du suffixe retirés). */
    static String suggestName(String filename) {
        if (filename == null || filename.isBlank()) return "Nouvelle campagne";
        String base = filename.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("^[0-9a-f]{8}-", "");
        List<String> words = new ArrayList<>();
        boolean hasUpper = base.chars().anyMatch(Character::isUpperCase);
        for (String w : base.split("[_\\s]+")) {
            if (w.isBlank() || w.matches(".*\\d.*")) continue;
            if (hasUpper && w.equals(w.toLowerCase(Locale.ROOT)) && words.size() >= 2) break; // suffixe « prénom nom » en minuscules
            words.add(w);
        }
        String name = String.join(" ", words).trim();
        if (name.isEmpty()) return "Nouvelle campagne";
        name = name.toLowerCase(Locale.FRENCH);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Campagnes existantes dont le modèle se retrouve dans ce fichier (au moins la moitié des questions). */
    private List<SimilarCampaign> similarCampaigns(List<String> headers, List<InferredField> inferred) {
        List<SimilarCampaign> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int col = 0; col < headers.size(); col++) {
            final int c = col;
            if (inferred.stream().noneMatch(f -> f.column() == c)) used.add(col);
        }
        for (Campaign c : campaignRepository.findAllByOrderByCreatedAtDesc()) {
            if (!"ACTIVE".equals(c.getStatus())) continue;
            List<CampaignFieldDto> model = campaigns.deserializeFields(c.getFieldsJson());
            if (model.size() < 2) continue;
            int matched = campaigns.matchFieldColumns(headers, model, used).size();
            if (matched * 2 >= model.size() && matched >= 2) out.add(new SimilarCampaign(c.getCampaignId(), c.getName(), matched, model.size()));
        }
        out.sort((a, b) -> Double.compare((double) b.matchedQuestions() / b.totalQuestions(), (double) a.matchedQuestions() / a.totalQuestions()));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    // ── Création + import ─────────────────────────────────────────────────

    @Transactional
    public CreateFromFileResult create(AuthenticatedUser requester, MultipartFile file, CreateFromFileRequest request) {
        campaigns.requireCanManage(requester);
        if (request == null || request.mapping() == null) throw ApiException.badRequest("Correspondance des colonnes manquante.");
        List<CampaignFieldDto> fields = request.fields() == null ? List.of() : request.fields();
        Set<String> ids = new HashSet<>();
        for (CampaignFieldDto f : fields) {
            if (f.id() == null || f.id().isBlank() || !ids.add(f.id())) throw ApiException.badRequest("Identifiant de question manquant ou en double.");
            if (f.label() == null || f.label().isBlank()) throw ApiException.badRequest("Chaque question doit avoir un libellé.");
        }
        Map<Integer, String> fieldColumns = new LinkedHashMap<>();
        if (request.mapping().fieldColumns() != null) {
            request.mapping().fieldColumns().forEach((col, id) -> { if (ids.contains(id)) fieldColumns.put(col, id); });
        }
        CampaignResponse created = campaigns.createCampaign(requester, new CampaignRequest(request.name(),
                request.description(), null, null, request.targetService(), "bi-file-earmark-spreadsheet", "#0057B8", "#00A651", null, fields));
        CampaignImportMappingDto m = request.mapping();
        CampaignImportMappingDto mapping = new CampaignImportMappingDto(m.nameColumn(), m.phoneColumn(), m.accountColumn(), m.agentColumn(),
                fieldColumns, m.statusColumn(), m.callDateColumn(), m.skipDuplicates(), m.onlyToRecall());
        CampaignImportReport report = campaigns.importContacts(requester, created.campaignId(), file, mapping);
        Campaign saved = campaignRepository.findById(created.campaignId()).orElseThrow();
        return new CreateFromFileResult(campaigns.toResponse(saved, List.of()), report);
    }
}
