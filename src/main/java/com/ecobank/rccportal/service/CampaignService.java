package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.Campaign;
import com.ecobank.rccportal.model.CampaignContact;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CampaignContactRepository;
import com.ecobank.rccportal.repository.CampaignRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CRM d'appels Outbound — le Team Leader crée des campagnes (avec un modèle de questions
 * propre à chaque campagne — prêt, carte bancaire, etc., voir Campaign.fieldsJson), y importe
 * des contacts (numéro de compte masqué dès l'import, jamais stocké en clair), les répartit
 * entre ses agents ; chaque agent travaille sa liste du jour et marque chaque contact :
 * PENDING (pas encore appelé) → GREEN (interaction réussie) | RED (pas de réponse) |
 * YELLOW (rendez-vous pris — voir SalesAppointmentService pour la création du RDV associé).
 * Deux sous-services Outbound (Service Digital / Télévente, voir resolveAgentSubService) ne
 * voient chacun que les campagnes qui les visent (Campaign.targetService), plus celles ouvertes
 * à toute l'équipe (targetService null).
 */
@Service
public class CampaignService {

    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "GREEN", "RED", "YELLOW");

    private final CampaignRepository campaignRepository;
    private final CampaignContactRepository campaignContactRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /** Journal des appels (dbo.CampaignCallLogs) — facultatif : absent des tests unitaires. */
    private org.springframework.jdbc.core.JdbcTemplate callLogJdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCallLogJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.callLogJdbc = jdbc;
    }

    /** Enregistre des appels : {campaignId, contactId, agentUserId, status, calledAt, source}. Meilleur effort. */
    void logCalls(List<Object[]> rows) {
        if (callLogJdbc == null || rows.isEmpty()) return;
        try {
            callLogJdbc.batchUpdate("INSERT INTO dbo.CampaignCallLogs (CampaignId, ContactId, AgentUserId, CallStatus, CalledAt, Source) VALUES (?, ?, ?, ?, ?, ?)",
                    rows.stream().map(r -> new Object[]{r[0], r[1], r[2], r[3], java.sql.Timestamp.valueOf((LocalDateTime) r[4]), r[5]}).toList());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(CampaignService.class).warn("Journal des appels non enregistré : {}", e.getMessage());
        }
    }

    /** Secret de l'empreinte des numéros de compte (même secret que les jetons de session). */
    @org.springframework.beans.factory.annotation.Value("${rcc.auth.jwt-secret:rcc-campaign-account-key}")
    private String accountKeySecret = "rcc-campaign-account-key";

    public CampaignService(CampaignRepository campaignRepository, CampaignContactRepository campaignContactRepository,
                            UserRepository userRepository, ObjectMapper objectMapper) {
        this.campaignRepository = campaignRepository;
        this.campaignContactRepository = campaignContactRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAMPAGNES
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public CampaignResponse createCampaign(AuthenticatedUser requester, CampaignRequest request) {
        requireCanManage(requester);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("Le nom de la campagne est obligatoire.");
        }
        String targetService = normalizeTargetService(request.targetService());
        User leader = requireUser(requester);
        Campaign campaign = Campaign.builder()
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .createdByUserId(leader.getId())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .status("ACTIVE")
                .targetService(targetService)
                .iconClass(blankToNull(request.iconClass()) != null ? request.iconClass().trim() : "bi-megaphone-fill")
                .colorFrom(blankToNull(request.colorFrom()) != null ? request.colorFrom().trim() : "#0057B8")
                .colorTo(blankToNull(request.colorTo()) != null ? request.colorTo().trim() : "#00A651")
                .coverImageUrl(blankToNull(request.coverImageUrl()))
                .fieldsJson(serializeFields(request.fields()))
                .build();
        campaign = campaignRepository.save(campaign);
        return toResponse(campaign, List.of());
    }

    @Transactional(readOnly = true)
    public List<CampaignResponse> listCampaigns(AuthenticatedUser requester) {
        requireCanManage(requester);
        return campaignRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(c -> toResponse(c, campaignContactRepository.findByCampaignIdOrderByClientNameAsc(c.getCampaignId())))
                .toList();
    }

    /**
     * Campagnes visibles dans l'onglet "Campagne" d'un agent Outbound — actives uniquement,
     * et filtrées par sous-service (Digital/Télévente) : une campagne ciblée ne sort que pour
     * le sous-service concerné, une campagne sans cible (targetService null) sort pour tous.
     */
    @Transactional(readOnly = true)
    public List<CampaignResponse> activeCampaignsForAgent(AuthenticatedUser requester) {
        User agent = requireUser(requester);
        String subService = resolveAgentSubService(agent);
        return campaignRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .filter(c -> c.getTargetService() == null || c.getTargetService().isBlank()
                        || c.getTargetService().equalsIgnoreCase(subService))
                .map(c -> toResponse(c, campaignContactRepository.findByCampaignIdAndAgentUserIdOrderByCallStatusAscClientNameAsc(
                        c.getCampaignId(), agent.getId())))
                .toList();
    }

    @Transactional
    public void closeCampaign(AuthenticatedUser requester, Integer campaignId) {
        requireCanManage(requester);
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiException.notFound("Campagne introuvable."));
        campaign.setStatus("CLOSED");
        campaignRepository.save(campaign);
    }

    // ═══════════════════════════════════════════════════════════════════
    // IMPORT DE CONTACTS — accepte n'importe quel fichier Excel : aucune position de colonne
    // n'est imposée. L'utilisateur prévisualise d'abord le fichier (previewImport), choisit
    // quelle colonne correspond à quoi (CampaignImportMappingDto), puis confirme l'import avec
    // ce mapping. Sans mapping fourni (compatibilité ascendante / self-import rapide), on
    // retombe sur l'ancienne disposition par défaut : A=nom, B=téléphone, C=compte,
    // D=agent (optionnel) — complétée par une détection automatique des colonnes
    // additionnelles par en-tête (voir buildDefaultMapping).
    // ═══════════════════════════════════════════════════════════════════

    /** Lit uniquement les en-têtes + une ligne d'exemple du fichier, propose un mapping par
     *  défaut (détection par mots-clés sur les en-têtes) — n'importe rien en base. */
    @Transactional(readOnly = true)
    public CampaignImportPreviewResponse previewImport(AuthenticatedUser requester, Integer campaignId, MultipartFile file) {
        requireCanManage(requester);
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiException.notFound("Campagne introuvable."));
        List<CampaignFieldDto> fields = deserializeFields(campaign.getFieldsJson());
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw ApiException.badRequest("Le fichier est vide.");
            List<String> headers = readHeaders(headerRow);
            Set<Integer> filled = filledColumns(sheet, headers.size());

            // Ligne d'exemple : la première ligne la plus remplie parmi les premières (un export
            // Forms commence souvent par des appels sans réponse aux questions).
            Row sampleDataRow = null;
            int best = -1;
            for (int rowIndex = 1; rowIndex <= Math.min(sheet.getLastRowNum(), 60); rowIndex++) {
                Row candidate = sheet.getRow(rowIndex);
                if (candidate == null) continue;
                int count = 0;
                for (int col = 0; col < headers.size(); col++) if (!cellToString(candidate.getCell(col)).isBlank()) count++;
                if (count > best) { best = count; sampleDataRow = candidate; }
            }
            List<String> sampleRow = new ArrayList<>();
            for (int col = 0; col < headers.size(); col++) {
                String v = sampleDataRow == null ? "" : cellToString(sampleDataRow.getCell(col));
                sampleRow.add(v.length() > 60 ? v.substring(0, 60) + "…" : v);
            }

            Map<Integer, List<String>> samples = columnSamples(sheet, headers.size());
            CampaignImportMappingDto suggested = buildSuggestedMapping(headers, fields, filled, samples);
            Set<String> matchedIds = new java.util.HashSet<>(suggested.fieldColumns().values());
            List<String> unmatchedQuestions = fields.stream().filter(f -> !matchedIds.contains(f.id())).map(CampaignFieldDto::label).toList();
            Set<Integer> mappedCols = new java.util.HashSet<>(suggested.fieldColumns().keySet());
            for (Integer c : java.util.Arrays.asList(suggested.nameColumn(), suggested.phoneColumn(), suggested.accountColumn(),
                    suggested.agentColumn(), suggested.statusColumn(), suggested.callDateColumn())) if (c != null) mappedCols.add(c);
            List<String> unmatchedColumns = new ArrayList<>();
            for (int col = 0; col < headers.size(); col++) {
                if (!mappedCols.contains(col) && filled.contains(col) && !headers.get(col).isBlank()
                        && !FORMS_METADATA.contains(normalizeForMatch(headers.get(col)))) unmatchedColumns.add(headers.get(col));
            }
            // Import immédiat possible : vraie colonne « client/nom » renseignée et modèle retrouvé (au moins la moitié des questions).
            String nameHeader = suggested.nameColumn() == null ? "" : normalizeForMatch(headers.get(suggested.nameColumn()));
            boolean nameSure = suggested.nameColumn() != null && filled.contains(suggested.nameColumn())
                    && (nameHeader.contains("client") || nameHeader.contains("nom") || nameHeader.contains("name") || nameHeader.contains("raisonsociale"));
            boolean confident = nameSure && (fields.isEmpty() || matchedIds.size() * 2 >= fields.size());
            return new CampaignImportPreviewResponse(headers, sampleRow, suggested, fields, matchedIds.size(),
                    unmatchedQuestions, unmatchedColumns, confident);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier illisible : " + e.getMessage());
        }
    }

    List<String> readHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        if (headerRow == null) return headers;
        for (int col = 0; col < headerRow.getLastCellNum(); col++) headers.add(cellToString(headerRow.getCell(col)));
        return headers;
    }

    /** Colonnes réellement renseignées sur les 200 premières lignes (un export Microsoft Forms
     *  contient par ex. une colonne « Nom » toujours vide, à ne jamais prendre pour le nom du client). */
    Set<Integer> filledColumns(Sheet sheet, int columnCount) {
        Set<Integer> filled = new java.util.HashSet<>();
        for (int rowIndex = 1; rowIndex <= Math.min(sheet.getLastRowNum(), 200); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int col = 0; col < columnCount; col++) {
                if (filled.contains(col)) continue;
                String v = cellToString(row.getCell(col));
                if (!v.isBlank() && !v.equalsIgnoreCase("anonymous")) filled.add(col);
            }
        }
        return filled;
    }

    /** Jusqu'à 40 valeurs distinctes par colonne (300 premières lignes) — pour reconnaître une
     *  question du modèle à ses réponses (Oui/Non, dates…) quand son libellé diffère. */
    Map<Integer, List<String>> columnSamples(Sheet sheet, int columnCount) {
        Map<Integer, java.util.LinkedHashSet<String>> acc = new java.util.HashMap<>();
        for (int rowIndex = 1; rowIndex <= Math.min(sheet.getLastRowNum(), 300); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int col = 0; col < columnCount; col++) {
                String v = cellToString(row.getCell(col));
                if (v.isBlank()) continue;
                var set = acc.computeIfAbsent(col, k -> new java.util.LinkedHashSet<>());
                if (set.size() < 40) set.add(v);
            }
        }
        Map<Integer, List<String>> out = new java.util.HashMap<>();
        acc.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    /** Métadonnées d'un export Microsoft Forms, jamais utiles à l'agent. */
    static final Set<String> FORMS_METADATA = Set.of("id", "heurededebut", "heuredefin", "adressedemessagerie",
            "heuredeladernieremodification", "starttime", "completiontime", "email", "lastmodifiedtime", "name");

    /** Propose un mapping par défaut à partir des en-têtes réels du fichier fourni — l'utilisateur
     *  peut ensuite tout corriger dans la modale avant de confirmer, ce n'est qu'une suggestion. */
    CampaignImportMappingDto buildSuggestedMapping(List<String> headers, List<CampaignFieldDto> fields, Set<Integer> filled) {
        return buildSuggestedMapping(headers, fields, filled, Map.of());
    }

    CampaignImportMappingDto buildSuggestedMapping(List<String> headers, List<CampaignFieldDto> fields, Set<Integer> filled,
                                                   Map<Integer, List<String>> samples) {
        Integer nameCol = null, phoneCol = null, accountCol = null, agentCol = null, statusCol = null, dateCol = null;
        int nameScore = 0;
        for (int col = 0; col < headers.size(); col++) {
            String h = normalizeForMatch(headers.get(col));
            if (h.isBlank() || (filled != null && !filled.contains(col))) continue;
            boolean shortHeader = h.length() <= 30;
            boolean aboutAgent = h.contains("agent") || h.contains("conseiller") || h.contains("teleconseiller");
            // Nom du client : « Nom du client » / « Client » / « Raison sociale » l'emportent sur un simple « Nom ».
            if (shortHeader && !aboutAgent && !h.contains("agence")) {
                int score = 0;
                if (h.contains("nomduclient") || h.contains("nomclient") || h.contains("clientname") || h.contains("customername")
                        || h.contains("raisonsociale") || h.contains("nomprenom") || h.contains("nometprenom")) score = 3;
                else if (h.contains("client") || h.contains("customer")) score = 2;
                else if (h.equals("nom") || h.equals("name") || h.startsWith("nom") || h.contains("fullname")) score = 1;
                if (score > nameScore) { nameScore = score; nameCol = col; continue; }
            }
            if (!shortHeader) continue;
            if (phoneCol == null && (h.contains("tel") || h.contains("phone") || h.contains("gsm") || h.contains("mobile") || h.equals("contact") || h.equals("contacts")
                    || h.contains("cellulaire") || (h.contains("numero") && h.contains("appel")))) { phoneCol = col; continue; }
            if (accountCol == null && !h.contains("agence") && (h.contains("compte") || h.contains("account"))) { accountCol = col; continue; }
            if (agentCol == null && (aboutAgent || h.contains("matricule") || h.contains("username"))) { agentCol = col; continue; }
            if (statusCol == null && (h.contains("statut") || h.contains("status") || h.contains("resultat") || h.contains("issue"))) { statusCol = col; continue; }
            // « Date d'appel » (dernier appel) — pas « Date de rappel » / « à recontacter » (une question du questionnaire).
            if (dateCol == null && h.contains("date") && (h.contains("appel") || h.contains("call"))
                    && !h.contains("rappel") && !h.contains("recontact") && !h.contains("callback")) { dateCol = col; }
        }
        if (accountCol == null) {
            for (int col = 0; col < headers.size(); col++) {
                if (normalizeForMatch(headers.get(col)).contains("cif")) { accountCol = col; break; }
            }
        }
        // Repli si rien n'a été identifié par mots-clés : ancienne disposition par défaut (A=nom, B=téléphone, C=compte).
        if (nameCol == null && !headers.isEmpty()) nameCol = 0;
        if (phoneCol == null && headers.size() > 1 && !Objects.equals(nameCol, 1)) phoneCol = 1;
        if (accountCol == null && headers.size() > 2 && !Objects.equals(nameCol, 2)) accountCol = 2;

        Set<Integer> used = new java.util.HashSet<>();
        for (Integer c : java.util.Arrays.asList(nameCol, phoneCol, accountCol, agentCol, statusCol, dateCol)) if (c != null) used.add(c);
        Map<Integer, String> fieldCols = matchFieldColumns(headers, fields, used, samples);
        return new CampaignImportMappingDto(nameCol, phoneCol, accountCol, agentCol, fieldCols, statusCol, dateCol, true, false);
    }

    /**
     * Aligne les colonnes du fichier sur le modèle de questions de la campagne (préparé par le
     * Team Leader) :
     * <ol>
     *   <li>libellé identique, ou mêmes mots à 80 % en tolérant les fautes de frappe
     *       (« cllient » = « client ») et les abréviations (« RDV » = « rendez-vous ») ;</li>
     *   <li>sinon libellé proche ET réponses compatibles avec la question (Oui/Non/… pour un
     *       choix, dates pour une question Date).</li>
     * </ol>
     * Une question ne reçoit qu'une colonne, la meilleure.
     */
    Map<Integer, String> matchFieldColumns(List<String> headers, List<CampaignFieldDto> fields, Set<Integer> used) {
        return matchFieldColumns(headers, fields, used, Map.of());
    }

    Map<Integer, String> matchFieldColumns(List<String> headers, List<CampaignFieldDto> fields, Set<Integer> used,
                                           Map<Integer, List<String>> samples) {
        record Candidate(int col, String fieldId, double score) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int col = 0; col < headers.size(); col++) {
            if (used.contains(col) || headers.get(col).isBlank()) continue;
            List<String> values = samples == null ? List.of() : samples.getOrDefault(col, List.of());
            for (CampaignFieldDto f : fields) {
                double label = labelSimilarity(headers.get(col), f.label());
                if (label >= 0.79) { candidates.add(new Candidate(col, f.id(), 1 + label)); continue; }
                if (label < 0.3 || values.isEmpty()) continue;
                double compat = valueCompatibility(f, values);
                double score = 0.6 * label + 0.4 * compat;
                if (compat >= 0.6 && score >= 0.55) candidates.add(new Candidate(col, f.id(), score));
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(Candidate::score).reversed().thenComparingInt(Candidate::col));
        Map<Integer, String> out = new LinkedHashMap<>();
        Set<String> takenFields = new java.util.HashSet<>();
        for (Candidate c : candidates) {
            if (out.containsKey(c.col()) || takenFields.contains(c.fieldId())) continue;
            out.put(c.col(), c.fieldId());
            takenFields.add(c.fieldId());
        }
        return out;
    }

    /** Part des valeurs de la colonne acceptables pour la question (0..1). */
    double valueCompatibility(CampaignFieldDto f, List<String> values) {
        if (values.isEmpty()) return 0;
        String type = f.type() == null ? "TEXT" : f.type();
        long ok;
        if (("RADIO".equals(type) || "SELECT".equals(type)) && f.options() != null && !f.options().isEmpty()) {
            ok = values.stream().filter(v -> !matchOption(f, v).equals(v) || f.options().contains(v)).count();
        } else if ("DATE".equals(type)) {
            ok = values.stream().filter(v -> v.matches("\\d{2}/\\d{2}/\\d{4}.*|\\d{4}-\\d{2}-\\d{2}.*")).count();
        } else {
            // Texte libre : compatible avec des phrases, pas avec une colonne de simples Oui/Non ni de dates.
            ok = values.stream().filter(v -> !v.matches("(?i)oui|non|yes|no|\\d{2}/\\d{2}/\\d{4}.*")).count();
        }
        return (double) ok / values.size();
    }

    double labelSimilarity(String header, String label) {
        if (header == null || label == null) return 0;
        String a = normalizeForMatch(header), b = normalizeForMatch(label);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        List<String> ta = new ArrayList<>(tokens(header)), tb = new ArrayList<>(tokens(label));
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        if (String.join("", ta).equals(String.join("", tb))) return 0.999;
        List<String> small = ta.size() <= tb.size() ? ta : tb, big = small == ta ? tb : ta;
        long common = small.stream().filter(t -> big.stream().anyMatch(o -> sameWord(t, o))).count();
        // Libellé court (« Package », « Raison de l'inactivité ») : ne suffit jamais seul, il faut aussi des réponses compatibles.
        if (small.size() < 3) return common == small.size() && big.size() <= small.size() + 1 ? 0.8 : (double) common / small.size() * 0.7;
        return (double) common / small.size() * 0.999; // < 1 : un libellé identique passe toujours devant
    }

    /** Même mot, à une faute de frappe près pour les mots d'au moins 5 lettres. */
    static boolean sameWord(String a, String b) {
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) < 5 || Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (a.length() > b.length()) i++; else if (b.length() > a.length()) j++; else { i++; j++; }
        }
        return edits + (a.length() - i) + (b.length() - j) <= 1;
    }

    private static final Map<String, String> SYNONYMS = Map.of("rdv", "rendez vous", "tel", "telephone", "tél", "telephone",
            "num", "numero", "no", "numero", "cpt", "compte", "cli", "client", "comment", "commentaire", "commentaires", "commentaire");

    Set<String> tokens(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("(?<=[a-z])(?=\\d)|(?<=\\d)(?=[a-z])", " "); // « zone3 » = « zone 3 »
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String t : noAccents.split("[^a-z0-9]+")) {
            String expanded = SYNONYMS.getOrDefault(t, t);
            for (String e : expanded.split(" ")) if (e.length() >= 3 || e.matches("\\d+") || "rendez vous".equals(expanded)) out.add(e);
        }
        return out;
    }

    @Transactional
    public CampaignImportReport importContacts(AuthenticatedUser requester, Integer campaignId, MultipartFile file, CampaignImportMappingDto mapping) {
        requireCanManage(requester);
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiException.notFound("Campagne introuvable."));
        return readContactsFile(file, campaignId, mapping, null);
    }

    /**
     * Auto-import — l'agent Outbound charge directement sa propre liste d'appels, sans
     * dépendre d'un Team Leader. Crée (ou réutilise) automatiquement une campagne
     * personnelle pour cet agent ; chaque contact importé lui est assigné directement.
     */
    @Transactional
    public CampaignImportReport selfImportContacts(AuthenticatedUser requester, MultipartFile file, CampaignImportMappingDto mapping) {
        User agent = requireUser(requester);
        Campaign personalCampaign = campaignRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> c.getCreatedByUserId().equals(agent.getId()) && "Mes contacts".equals(c.getName()) && "ACTIVE".equals(c.getStatus()))
                .findFirst()
                .orElseGet(() -> campaignRepository.save(Campaign.builder()
                        .name("Mes contacts")
                        .description("Import personnel — " + (agent.getName() != null ? agent.getName() : agent.getUsername()))
                        .createdByUserId(agent.getId())
                        .status("ACTIVE")
                        .build()));
        return readContactsFile(file, personalCampaign.getCampaignId(), mapping, agent.getId());
    }

    private CampaignImportReport readContactsFile(MultipartFile file, Integer campaignId, CampaignImportMappingDto mapping, Long forcedAgentUserId) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }
        List<CampaignFieldDto> fields = campaignRepository.findById(campaignId)
                .map(c -> deserializeFields(c.getFieldsJson()))
                .orElse(List.of());
        Map<String, CampaignFieldDto> fieldsById = new LinkedHashMap<>();
        fields.forEach(f -> fieldsById.put(f.id(), f));

        int imported = 0, skippedDup = 0, skippedRecall = 0, skippedNoName = 0, assigned = 0, updated = 0, unchanged = 0;
        Set<String> matchedFieldIds = new java.util.HashSet<>();
        List<Object[]> callLog = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> matchedAgents = new java.util.TreeMap<>();
        Map<String, Integer> unmatchedAgents = new java.util.TreeMap<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = readHeaders(sheet.getRow(0));

            // Pas de mapping fourni (appel direct, ancien client) : on en déduit un à partir
            // des en-têtes du fichier lui-même, exactement comme le ferait la prévisualisation.
            CampaignImportMappingDto m = mapping;
            if (m == null) m = buildSuggestedMapping(headers, fields, filledColumns(sheet, headers.size()), columnSamples(sheet, headers.size()));
            if (m.nameColumn() == null) {
                throw ApiException.badRequest("Impossible de déterminer la colonne du nom du client — précisez le mapping des colonnes.");
            }
            int nameCol = m.nameColumn();
            Integer phoneCol = m.phoneColumn(), accountCol = m.accountColumn(), statusCol = m.statusColumn(), dateCol = m.callDateColumn();
            Integer agentCol = forcedAgentUserId != null ? null : m.agentColumn();
            Map<Integer, String> fieldCols = m.fieldColumns() != null ? m.fieldColumns() : Map.of();
            fieldCols.values().stream().filter(fieldsById::containsKey).forEach(matchedFieldIds::add);
            boolean skipDuplicates = !Boolean.FALSE.equals(m.skipDuplicates());
            boolean onlyToRecall = Boolean.TRUE.equals(m.onlyToRecall());

            // Toute colonne du fichier qui n'est NI le nom, NI le téléphone, NI le compte, NI
            // l'agent, NI une question du modèle de campagne — conservée telle quelle dans
            // extraDataJson (clé = en-tête réel du fichier), pour qu'aucune donnée ne se perde
            // (hors métadonnées d'un export Forms : ID, heures de début/fin, messagerie…).
            Set<Integer> mappedCols = new java.util.HashSet<>(fieldCols.keySet());
            for (Integer c : java.util.Arrays.asList(nameCol, phoneCol, accountCol, agentCol, statusCol, dateCol)) if (c != null) mappedCols.add(c);
            List<Integer> extraCols = new ArrayList<>();
            for (int col = 0; col < headers.size(); col++) {
                if (!mappedCols.contains(col) && !headers.get(col).isBlank() && !FORMS_METADATA.contains(normalizeForMatch(headers.get(col)))) extraCols.add(col);
            }

            AgentResolver agents = new AgentResolver(userRepository.findAll());
            // Contacts déjà dans la campagne : un réimport les SYNCHRONISE (mise à jour) au lieu de les recréer.
            // Reconnaissance : empreinte du compte complet d'abord ; pour les contacts importés avant
            // l'empreinte, nom + compte masqué (ou téléphone) seulement s'il n'y a aucune ambiguïté.
            Map<String, CampaignContact> existingByKey = new java.util.HashMap<>();
            Map<String, Integer> legacyCount = new java.util.HashMap<>();
            List<CampaignContact> existingContacts = campaignContactRepository.findByCampaignIdOrderByClientNameAsc(campaignId);
            for (CampaignContact existing : existingContacts) {
                if (existing.getAccountKey() != null) { existingByKey.putIfAbsent("K:" + existing.getAccountKey(), existing); continue; }
                for (String k : legacyKeys(existing.getClientName(), existing.getMaskedAccountNumber(), existing.getClientPhone())) legacyCount.merge(k, 1, Integer::sum);
            }
            for (CampaignContact existing : existingContacts) {
                if (existing.getAccountKey() != null) continue;
                for (String k : legacyKeys(existing.getClientName(), existing.getMaskedAccountNumber(), existing.getClientPhone())) {
                    if (legacyCount.get(k) == 1) existingByKey.put(k, existing);
                }
            }
            Set<CampaignContact> syncedThisImport = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Map<String, Integer> lastRowByKey = new java.util.HashMap<>();
            Map<String, Long> bestScoreByKey = new java.util.HashMap<>();
            if (skipDuplicates) {
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    String n = cellToString(row.getCell(nameCol));
                    String acc = accountCol == null ? "" : cellToString(row.getCell(accountCol));
                    if (looksSwapped(n, acc)) acc = n;
                    String st = statusCol == null ? "PENDING" : mapPreviousStatus(cellToString(row.getCell(statusCol)));
                    if ("SKIP".equals(st)) continue;
                    String key = dedupeKey(acc, phoneCol == null ? List.of() : normalizePhones(cellToString(row.getCell(phoneCol))));
                    if (key == null) continue;
                    // Meilleur appel : joint/RDV avant non joint, puis le plus complet, puis le plus récent.
                    int filledCount = 0;
                    for (int col = 0; col < headers.size(); col++) if (!cellToString(row.getCell(col)).isBlank()) filledCount++;
                    long score = STATUS_RANK.getOrDefault(st, 0) * 1_000_000L + filledCount * 10_000L + rowIndex;
                    if (score > bestScoreByKey.getOrDefault(key, -1L)) { bestScoreByKey.put(key, score); lastRowByKey.put(key, rowIndex); }
                }
            }

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;

                String clientName = cellToString(row.getCell(nameCol));
                String rawAccountNumber = accountCol == null ? "" : cellToString(row.getCell(accountCol));
                if (looksSwapped(clientName, rawAccountNumber)) { String t = clientName; clientName = rawAccountNumber; rawAccountNumber = t; }
                if (clientName.isBlank()) { if (rowHasData(row, headers.size())) skippedNoName++; continue; }
                String masked = maskAccountNumber(rawAccountNumber);
                List<String> phones = phoneCol == null ? List.of() : normalizePhones(cellToString(row.getCell(phoneCol)));

                String previousStatusLabel = statusCol == null ? "" : cellToString(row.getCell(statusCol));
                String mapped = statusCol == null ? "PENDING" : mapPreviousStatus(previousStatusLabel);
                if ("SKIP".equals(mapped)) { skippedDup++; continue; }
                if (skipDuplicates) {
                    // Même compte (ou, sans compte, même téléphone) rappelé plusieurs fois : on garde
                    // le DERNIER appel du fichier ; un contact déjà présent dans la campagne n'est pas recréé.
                    String key = dedupeKey(rawAccountNumber, phones);
                    if (key != null && lastRowByKey.getOrDefault(key, rowIndex) != rowIndex) { skippedDup++; continue; }
                }
                String accountKey = accountKey(rawAccountNumber);
                CampaignContact existing = accountKey != null ? existingByKey.get("K:" + accountKey) : null;
                if (existing == null) {
                    for (String k : legacyKeys(clientName, masked, phones.isEmpty() ? null : phones.get(0))) {
                        CampaignContact candidate = existingByKey.get(k);
                        if (candidate != null && candidate.getAccountKey() == null && !syncedThisImport.contains(candidate)) { existing = candidate; break; }
                    }
                }
                boolean reached = !("RED".equals(mapped) || "PENDING".equals(mapped));
                if (existing == null && onlyToRecall && reached) { skippedRecall++; continue; }

                Long agentUserId = forcedAgentUserId;
                if (agentUserId == null && agentCol != null) {
                    String agentIdentifier = cellToString(row.getCell(agentCol));
                    if (!agentIdentifier.isBlank()) {
                        User agent = agents.resolve(agentIdentifier);
                        if (agent != null) {
                            agentUserId = agent.getId();
                            matchedAgents.merge(agentIdentifier.toUpperCase(), 1, Integer::sum);
                        } else {
                            unmatchedAgents.merge(agentIdentifier.toUpperCase(), 1, Integer::sum);
                        }
                    }
                }

                Map<String, String> prefilledAnswers = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> entry : fieldCols.entrySet()) {
                    CampaignFieldDto field = fieldsById.get(entry.getValue());
                    String value = field != null && "DATE".equals(field.type())
                            ? cellToIsoDate(row.getCell(entry.getKey()))
                            : cellToString(row.getCell(entry.getKey()));
                    if (isNoAnswer(value)) continue;
                    prefilledAnswers.put(entry.getValue(), field != null ? matchOption(field, value) : value);
                }

                Map<String, String> extraData = new LinkedHashMap<>();
                if (phones.size() > 1) extraData.put("Autres numéros", String.join(" / ", phones.subList(1, phones.size())));
                for (Integer col : extraCols) {
                    String value = cellToString(row.getCell(col));
                    if (!value.isBlank() && !value.equalsIgnoreCase("anonymous")) extraData.put(headers.get(col), value);
                }

                LocalDateTime previousCall = dateCol == null ? null : cellToDateTime(row.getCell(dateCol));
                String notes = null;
                if (!previousStatusLabel.isBlank()) {
                    String by = agentCol == null ? "" : cellToString(row.getCell(agentCol));
                    notes = "Appel précédent : " + previousStatusLabel
                            + (previousCall != null ? " le " + previousCall.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "")
                            + (!by.isBlank() ? " (" + by + ")" : "");
                }
                String status = onlyToRecall && !reached ? "PENDING" : mapped;

                if (existing != null) {
                    syncedThisImport.add(existing);
                    boolean keyAdded = existing.getAccountKey() == null && accountKey != null;
                    if (keyAdded) existing.setAccountKey(accountKey);
                    LocalDateTime callBefore = existing.getLastCalledAt();
                    if (syncExisting(existing, status, previousCall, notes, prefilledAnswers, agentUserId, phones, extraData) || keyAdded) {
                        campaignContactRepository.save(existing);
                        if (existing.getLastCalledAt() != null && !existing.getLastCalledAt().equals(callBefore)
                                && !"PENDING".equals(existing.getCallStatus()) && existing.getContactId() != null) {
                            callLog.add(new Object[]{campaignId, existing.getContactId(), agentUserId, existing.getCallStatus(), existing.getLastCalledAt(), "FILE"});
                        }
                        updated++;
                    } else {
                        unchanged++;
                    }
                    continue;
                }
                if (agentUserId != null) assigned++;
                statusCounts.merge(status, 1, Integer::sum);

                CampaignContact created = campaignContactRepository.save(CampaignContact.builder()
                        .campaignId(campaignId)
                        .agentUserId(agentUserId)
                        .clientName(limit(clientName, 200))
                        .clientPhone(phones.isEmpty() ? null : phones.get(0))
                        .maskedAccountNumber(masked)
                        .accountKey(accountKey)
                        .answersJson(serializeLimited(prefilledAnswers))
                        .extraDataJson(serializeLimited(extraData))
                        .notes(limit(notes, 1000))
                        .lastCalledAt(onlyToRecall || "PENDING".equals(status) ? null : previousCall)
                        .callStatus(status)
                        .build());
                // Historique du fichier : l'appel déjà passé compte dans le suivi de performance (même en mode « à rappeler »).
                if (previousCall != null && !"PENDING".equals(mapped) && created != null && created.getContactId() != null) {
                    callLog.add(new Object[]{campaignId, created.getContactId(), agentUserId, mapped, previousCall, "FILE"});
                }
                imported++;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier illisible : " + e.getMessage());
        }
        logCalls(callLog);
        List<String> unmatchedQuestions = fields.stream().filter(f -> !matchedFieldIds.contains(f.id())).map(CampaignFieldDto::label).toList();
        return new CampaignImportReport(imported, skippedDup, skippedRecall, skippedNoName, assigned, statusCounts, matchedAgents, unmatchedAgents,
                updated, unchanged, matchedFieldIds.size(), fields.size(), unmatchedQuestions);
    }

    /**
     * Synchronise un contact déjà présent avec la ligne du fichier. Le fichier l'emporte s'il est
     * plus récent que le dernier appel enregistré dans le portail (ou si le contact n'a jamais été
     * appelé depuis le portail) ; sinon on ne fait que compléter ce qui manque (réponses vides,
     * agent, téléphone, informations). Retourne true si quelque chose a changé.
     */
    private boolean syncExisting(CampaignContact c, String status, LocalDateTime fileCall, String notes, Map<String, String> answers,
                                 Long agentUserId, List<String> phones, Map<String, String> extra) {
        boolean portalUntouched = c.getLastCalledAt() == null && "PENDING".equals(c.getCallStatus());
        boolean fileNewer = fileCall != null && (c.getLastCalledAt() == null || fileCall.isAfter(c.getLastCalledAt()));
        boolean fileWins = portalUntouched || fileNewer;
        boolean changed = false;
        if (fileWins && !"PENDING".equals(status) && !status.equals(c.getCallStatus())) {
            c.setCallStatus(status);
            changed = true;
        }
        if (fileWins && fileCall != null && !"PENDING".equals(status) && !fileCall.equals(c.getLastCalledAt())) {
            c.setLastCalledAt(fileCall);
            changed = true;
        }
        if (fileWins && notes != null && !notes.equals(c.getNotes())) {
            String kept = c.getNotes() == null ? "" : c.getNotes().replaceAll("(?m)^Appel précédent :.*$\\n?", "").trim();
            c.setNotes(limit(kept.isEmpty() ? notes : notes + "\n" + kept, 1000));
            changed = true;
        }
        Map<String, String> current = new LinkedHashMap<>(deserializeAnswers(c.getAnswersJson()));
        for (Map.Entry<String, String> e : answers.entrySet()) {
            String old = current.get(e.getKey());
            if (old == null || old.isBlank() || (fileWins && !old.equals(e.getValue()))) {
                if (!e.getValue().equals(old)) { current.put(e.getKey(), e.getValue()); changed = true; }
            }
        }
        if (changed) c.setAnswersJson(serializeLimited(current));
        if (c.getAgentUserId() == null && agentUserId != null) { c.setAgentUserId(agentUserId); changed = true; }
        if (c.getClientPhone() == null && !phones.isEmpty()) { c.setClientPhone(phones.get(0)); changed = true; }
        Map<String, String> info = new LinkedHashMap<>(deserializeAnswers(c.getExtraDataJson()));
        boolean infoChanged = false;
        for (Map.Entry<String, String> e : extra.entrySet()) if (!info.containsKey(e.getKey())) { info.put(e.getKey(), e.getValue()); infoChanged = true; }
        if (infoChanged) { c.setExtraDataJson(serializeLimited(info)); changed = true; }
        return changed;
    }

    private List<String> legacyKeys(String name, String masked, String phone) {
        List<String> keys = new ArrayList<>();
        String n = normalizeForMatch(name);
        if (masked != null) keys.add("M:" + n + "|" + masked);
        if (phone != null) keys.add("P:" + phone + "|" + n);
        return keys;
    }

    /** Empreinte HMAC-SHA256 (hex, 64 car.) du numéro de compte complet ; null sans numéro exploitable. */
    String accountKey(String rawAccount) {
        String digits = rawAccount == null ? "" : rawAccount.replaceAll("\\D", "");
        if (digits.length() < 6) return null;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(accountKeySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(digits.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Map<String, Integer> STATUS_RANK = Map.of("YELLOW", 3, "GREEN", 2, "PENDING", 1, "RED", 0);

    private static String dedupeKey(String rawAccount, List<String> phones) {
        String digits = rawAccount == null ? "" : rawAccount.replaceAll("\\D", "");
        if (digits.length() >= 6) return "A:" + digits;
        return phones.isEmpty() ? null : "P:" + phones.get(0);
    }

    /** Nom et numéro de compte inversés à la saisie (compte dans « Nom du client », nom dans « Numéro de compte »). */
    static boolean looksSwapped(String name, String account) {
        if (name == null || account == null) return false;
        return name.matches("[\\d\\s-]{8,}") && account.matches(".*\\p{L}.*");
    }

    private boolean rowHasData(Row row, int columns) {
        for (int col = 0; col < columns; col++) if (!cellToString(row.getCell(col)).isBlank()) return true;
        return false;
    }

    /**
     * Statut d'appel d'un fichier existant (export Forms : « SONNE DANS LE VIDE », « CLIENT
     * ENTRETENU »…) → statut portail : GREEN (joint), RED (non joint), YELLOW (RDV), PENDING
     * (à appeler / rappel demandé), SKIP (doublon signalé par l'agent).
     */
    static String mapPreviousStatus(String raw) {
        if (raw == null || raw.isBlank()) return "PENDING";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase();
        if (s.contains("DOUBLON")) return "SKIP";
        if (s.contains("RDV") || s.contains("RENDEZ")) return "YELLOW";
        if (s.contains("RAPPEL") || s.contains("INTERROMPU") || s.contains("INAUDIBLE")) return "PENDING";
        if (s.contains("ENTRETENU") || s.contains("JOINT") || s.contains("INTERACTION") || s.contains("CONTACTE")) return "GREEN";
        if (s.contains("VIDE") || s.contains("INACCESSIBLE") || s.contains("MESSAGERIE") || s.contains("REPONDEUR")
                || s.contains("INCONNU") || s.contains("AUCUN") || s.contains("OCCUPE") || s.contains("ERRONE")
                || s.contains("FAUX") || s.contains("PAS DE REPONSE") || s.contains("NRP") || s.contains("INJOIGNABLE")) return "RED";
        return "PENDING";
    }

    /** « +2250505051986 », « 2252720324831,2250707201078 », « 0707842929 », « N/A » → numéros
     *  ivoiriens à 10 chiffres (préfixe 225 retiré), dans l'ordre, sans doublon. */
    static List<String> normalizePhones(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,;/|]+|\\s{2,}|\\s+-\\s+")) {
            String digits = part.replaceAll("\\D", "");
            if (digits.length() < 8) continue;
            if (digits.startsWith("00225")) digits = digits.substring(5);
            // « 225 » parfois saisi deux fois (+2252250576788063) : retiré tant qu'il reste plus de 10 chiffres.
            while (digits.startsWith("225") && digits.length() > 10) digits = digits.substring(3);
            if (!out.contains(digits)) out.add(digits);
        }
        return out;
    }

    private static final Set<String> YES = Set.of("o", "oui", "yes", "y", "ok", "daccord");
    private static final Set<String> NO = Set.of("n", "non", "no");

    /**
     * Range une réponse libre du fichier dans l'option du modèle qui lui correspond :
     * « LE CLIENT SOUHAITE CLÔTURER SON COMPTE. » → « Souhaite clôturer son compte »,
     * « ECI- AGENCE MARCORY MARCHE » → « Marcory Marché », « EMPLOI PERDU » → « Client au
     * chômage / emploi perdu ». Tous les mots de l'option (ou d'une de ses variantes séparées
     * par « / ») doivent se retrouver dans la réponse, à une faute près et à 2/3 au moins ; à
     * égalité, l'option la plus précise l'emporte. Sans correspondance sûre, la réponse est
     * gardée telle quelle (visible par l'agent).
     */
    String matchOption(CampaignFieldDto field, String value) {
        if (field.options() == null || field.options().isEmpty() || value == null) return value;
        String v = normalizeForMatch(value);
        for (String option : field.options()) if (normalizeForMatch(option).equals(v)) return option;
        for (String option : field.options()) {
            String o = normalizeForMatch(option);
            if ((o.equals("oui") && YES.contains(v)) || (o.equals("non") && NO.contains(v))) return option;
        }
        List<String> valueTokens = new ArrayList<>(tokens(value));
        String best = null;
        double bestScore = 0;
        int bestSize = 0;
        for (String option : field.options()) {
            if (normalizeForMatch(option).equals("autre")) continue;
            for (String variant : option.split("/")) {
                List<String> ot = new ArrayList<>(tokens(variant));
                if (ot.isEmpty()) continue;
                long found = ot.stream().filter(t -> valueTokens.stream().anyMatch(w -> sameWord(t, w))).count();
                double score = (double) found / ot.size();
                if (score < 0.66) continue;
                if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) < 1e-9 && ot.size() > bestSize)) {
                    best = option; bestScore = score; bestSize = ot.size();
                }
            }
        }
        return best != null ? best : value;
    }

    /** « N/A », « - », « néant » : pas une réponse. */
    static boolean isNoAnswer(String value) {
        return value == null || value.isBlank() || value.trim().matches("(?i)n\\s*/\\s*a|na|-+|n[ée]ant|\\?+");
    }

    /** Reconnaît l'agent par identifiant, ou par nom complet quel que soit l'ordre des mots
     *  (« HOURI SAMUEL » = « Samuel Houri »), accents et espaces insécables ignorés. */
    static final class AgentResolver {
        private final Map<String, User> byUsername = new java.util.HashMap<>();
        private final Map<String, User> byName = new java.util.HashMap<>();
        private final List<User> users;
        private final Map<String, User> cache = new java.util.HashMap<>();

        AgentResolver(List<User> users) {
            this.users = users;
            for (User u : users) {
                if (u.getUsername() != null) byUsername.putIfAbsent(u.getUsername().toLowerCase(), u);
                if (u.getName() != null && !u.getName().isBlank()) byName.putIfAbsent(nameKey(u.getName()), u);
            }
        }

        User resolve(String identifier) {
            String id = identifier.replace(' ', ' ').trim();
            if (cache.containsKey(id)) return cache.get(id);
            User u = byUsername.get(id.toLowerCase());
            if (u == null) u = byName.get(nameKey(id));
            if (u == null) {
                // Nom du fichier contenu dans un nom plus long (« HOURI SAMUEL » ↔ « HOURI Kouadio Samuel ») — si unique.
                Set<String> wanted = new java.util.HashSet<>(List.of(nameKey(id).split(" ")));
                List<User> hits = users.stream().filter(x -> x.getName() != null
                        && new java.util.HashSet<>(List.of(nameKey(x.getName()).split(" "))).containsAll(wanted)).toList();
                if (wanted.size() >= 2 && hits.size() == 1) u = hits.get(0);
            }
            cache.put(id, u);
            return u;
        }

        static String nameKey(String name) {
            String n = Normalizer.normalize(name.replace(' ', ' '), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase();
            return java.util.Arrays.stream(n.split("[^A-Z]+")).filter(t -> !t.isEmpty()).sorted().collect(java.util.stream.Collectors.joining(" "));
        }
    }

    String normalizeForMatch(String s) {
        if (s == null) return "";
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Ne conserve JAMAIS le numéro complet — 3 premiers et 3 derniers chiffres visibles,
     *  le reste en astérisques (ex. 1234567890123 → 123*******123). Sous 7 chiffres, montrer
     *  3+3 reviendrait à tout révéler (voire se chevaucher) : le numéro est alors masqué en
     *  totalité par prudence. */
    private String maskAccountNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digitsOnly = raw.replaceAll("\\D", "");
        if (digitsOnly.isEmpty()) return null;
        if (digitsOnly.length() <= 6) return "*".repeat(digitsOnly.length());
        String first = digitsOnly.substring(0, 3);
        String last = digitsOnly.substring(digitsOnly.length() - 3);
        return first + "*".repeat(digitsOnly.length() - 6) + last;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ASSIGNATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Répartition en un clic des contacts NON assignés entre les agents choisis, en équilibrant
     * la charge (chaque contact va à l'agent qui en a le moins sur cette campagne). Seuls les
     * contacts « À appeler » sont répartis par défaut.
     */
    @Transactional
    public Map<String, Object> distributeUnassigned(AuthenticatedUser requester, Integer campaignId, List<Long> agentUserIds, boolean includeCalled) {
        requireCanManage(requester);
        campaignRepository.findById(campaignId).orElseThrow(() -> ApiException.notFound("Campagne introuvable."));
        if (agentUserIds == null || agentUserIds.isEmpty()) throw ApiException.badRequest("Choisissez au moins un agent.");
        List<Long> agentsList = agentUserIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long id : agentsList) {
            if (userRepository.findById(id).isEmpty()) throw ApiException.badRequest("Agent inconnu : " + id);
        }
        List<CampaignContact> contacts = campaignContactRepository.findByCampaignIdOrderByClientNameAsc(campaignId);
        Map<Long, Integer> load = new LinkedHashMap<>();
        agentsList.forEach(id -> load.put(id, 0));
        for (CampaignContact c : contacts) if (c.getAgentUserId() != null && load.containsKey(c.getAgentUserId())) load.merge(c.getAgentUserId(), 1, Integer::sum);
        List<CampaignContact> changed = new ArrayList<>();
        for (CampaignContact c : contacts) {
            if (c.getAgentUserId() != null) continue;
            if (!includeCalled && !"PENDING".equals(c.getCallStatus())) continue;
            Long target = load.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
            c.setAgentUserId(target);
            load.merge(target, 1, Integer::sum);
            changed.add(c);
        }
        campaignContactRepository.saveAll(changed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assigned", changed.size());
        out.put("perAgent", load);
        return out;
    }

    @Transactional
    public void assignContact(AuthenticatedUser requester, Integer contactId, Long agentUserId) {
        requireCanManage(requester);
        CampaignContact contact = campaignContactRepository.findById(contactId)
                .orElseThrow(() -> ApiException.notFound("Contact introuvable."));
        contact.setAgentUserId(agentUserId);
        campaignContactRepository.save(contact);
    }

    @Transactional(readOnly = true)
    public List<CampaignContactResponse> contactsForCampaign(AuthenticatedUser requester, Integer campaignId) {
        requireCanManage(requester);
        return campaignContactRepository.findByCampaignIdOrderByClientNameAsc(campaignId).stream()
                .map(this::toContactResponse).toList();
    }

    // ═══════════════════════════════════════════════════════════════════
    // VUE AGENT — sa propre liste d'appels
    // ═══════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<CampaignContactResponse> myContacts(AuthenticatedUser requester) {
        User agent = requireUser(requester);
        return campaignContactRepository.findByAgentUserIdOrderByCallStatusAscClientNameAsc(agent.getId()).stream()
                .map(this::toContactResponse).toList();
    }

    /** Mes contacts d'UNE campagne précise — utilisé par l'onglet "Campagne" (défilement séquentiel). */
    @Transactional(readOnly = true)
    public List<CampaignContactResponse> myContactsForCampaign(AuthenticatedUser requester, Integer campaignId) {
        User agent = requireUser(requester);
        return campaignContactRepository.findByCampaignIdAndAgentUserIdOrderByCallStatusAscClientNameAsc(campaignId, agent.getId()).stream()
                .map(this::toContactResponse).toList();
    }

    @Transactional
    public CampaignContactResponse updateCallStatus(AuthenticatedUser requester, Integer contactId, UpdateCallStatusRequest request) {
        User agent = requireUser(requester);
        CampaignContact contact = campaignContactRepository.findById(contactId)
                .orElseThrow(() -> ApiException.notFound("Contact introuvable."));
        boolean isOwner = contact.getAgentUserId() != null && contact.getAgentUserId().equals(agent.getId());
        if (!isOwner && !canManageOutboundTeam(requester)) {
            throw ApiException.forbidden("Ce contact ne vous est pas assigné.");
        }
        String status = request.callStatus() == null ? "" : request.callStatus().toUpperCase();
        if (!VALID_STATUSES.contains(status)) {
            throw ApiException.badRequest("Statut invalide : " + request.callStatus());
        }
        contact.setCallStatus(status);
        if (request.notes() != null) contact.setNotes(blankToNull(request.notes()));
        if (request.answers() != null) contact.setAnswersJson(serializeAnswers(request.answers()));
        contact.setLastCalledAt(LocalDateTime.now());
        contact = campaignContactRepository.save(contact);
        if (!"PENDING".equals(status)) {
            List<Object[]> log = new ArrayList<>();
            log.add(new Object[]{contact.getCampaignId(), contact.getContactId(), agent.getId(), status, contact.getLastCalledAt(), "PORTAL"});
            logCalls(log);
        }
        return toContactResponse(contact);
    }

    @Transactional
    public void linkAppointment(Integer contactId, Integer appointmentId) {
        campaignContactRepository.findById(contactId).ifPresent(c -> {
            c.setAppointmentId(appointmentId);
            campaignContactRepository.save(c);
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    User requireUser(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    void requireCanManage(AuthenticatedUser requester) {
        if (!canManageOutboundTeam(requester)) {
            throw ApiException.forbidden("Réservé au Team Leader de l'équipe Outbound, à QA ou à l'admin.");
        }
    }

    /** Même logique que SalesAppointmentService.canManageOutboundTeam — QA/Admin/Superviseur, ou Team Leader OUTBOUND. */
    private boolean canManageOutboundTeam(AuthenticatedUser requester) {
        if (requester == null) return false;
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isAdmin || isSupervisor || isQa) return true;
        if ("team_leader".equalsIgnoreCase(requester.role())) {
            User u = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
            return u != null && "OUTBOUND".equalsIgnoreCase(u.getLedTeam());
        }
        return false;
    }

    /**
     * Sous-service Outbound d'un agent, déduit de User.activity (même champ libre que
     * TeamClassifier) — "DIGITAL" ou "TELEVENTE"/"TÉLÉVENDEUR" selon les mots-clés présents.
     * Null si aucun des deux mots-clés n'est présent (agent Outbound non encore classé, ou
     * autre équipe) — dans ce cas il ne voit que les campagnes ouvertes à tous (targetService null).
     */
    private String resolveAgentSubService(User user) {
        String activity = user.getActivity();
        if (activity == null || activity.isBlank()) return null;
        String a = activity.toUpperCase();
        if (a.contains("DIGITAL")) return "DIGITAL";
        if (a.contains("TELEVENTE") || a.contains("TÉLÉVENTE") || a.contains("TELEVENDEUR") || a.contains("TÉLÉVENDEUR")) return "TELEVENTE";
        return null;
    }

    String normalizeTargetService(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase();
        if (!v.equals("DIGITAL") && !v.equals("TELEVENTE")) {
            throw ApiException.badRequest("Service ciblé invalide : " + raw + " (attendu DIGITAL, TELEVENTE, ou vide pour toute l'équipe).");
        }
        return v;
    }

    CampaignResponse toResponse(Campaign c, List<CampaignContact> contacts) {
        int calls = (int) contacts.stream().filter(x -> !"PENDING".equals(x.getCallStatus())).count();
        int contacted = (int) contacts.stream().filter(x -> "GREEN".equals(x.getCallStatus())).count();
        int appointments = (int) contacts.stream().filter(x -> "YELLOW".equals(x.getCallStatus())).count();
        int unassigned = (int) contacts.stream().filter(x -> x.getAgentUserId() == null).count();
        return new CampaignResponse(c.getCampaignId(), c.getName(), c.getDescription(), c.getStartDate(), c.getEndDate(),
                c.getStatus(), c.getTargetService(), c.getIconClass(), c.getColorFrom(), c.getColorTo(), c.getCoverImageUrl(),
                deserializeFields(c.getFieldsJson()), c.getCreatedAt(),
                contacts.size(), calls, contacted, appointments, unassigned);
    }

    private CampaignContactResponse toContactResponse(CampaignContact c) {
        Campaign campaign = campaignRepository.findById(c.getCampaignId()).orElse(null);
        User agent = c.getAgentUserId() != null ? userRepository.findById(c.getAgentUserId()).orElse(null) : null;
        return new CampaignContactResponse(c.getContactId(), c.getCampaignId(),
                campaign != null ? campaign.getName() : "—",
                c.getAgentUserId(), agent != null ? (agent.getName() != null ? agent.getName() : agent.getUsername()) : null,
                c.getClientName(), c.getClientPhone(), c.getMaskedAccountNumber(), c.getCallStatus(), c.getNotes(),
                deserializeAnswers(c.getAnswersJson()),
                deserializeAnswers(c.getExtraDataJson()),
                c.getLastCalledAt(), c.getAppointmentId());
    }

    String serializeFields(List<CampaignFieldDto> fields) {
        if (fields == null || fields.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw ApiException.badRequest("Modèle de questions invalide : " + e.getMessage());
        }
    }

    List<CampaignFieldDto> deserializeFields(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new ArrayList<>(List.of(objectMapper.readValue(json, CampaignFieldDto[].class)));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String serializeAnswers(Map<String, String> answers) {
        if (answers == null || answers.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(answers);
        } catch (Exception e) {
            return null;
        }
    }

    Map<String, String> deserializeAnswers(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static final java.time.format.DateTimeFormatter FR_DATE = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final java.time.format.DateTimeFormatter FR_DATE_TIME = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Texte d'une cellule : espaces insécables et tabulations nettoyés, dates au format français. */
    String cellToString(Cell cell) {
        if (cell == null) return "";
        String v;
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (type) {
            case STRING -> v = cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime d = cell.getLocalDateTimeCellValue();
                    v = d.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? d.format(FR_DATE) : d.format(FR_DATE_TIME);
                } else {
                    double n = cell.getNumericCellValue();
                    v = n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n);
                }
            }
            case BOOLEAN -> v = String.valueOf(cell.getBooleanCellValue());
            case BLANK, ERROR, _NONE -> v = "";
            default -> v = cell.toString();
        }
        return v.replace('\u00A0', ' ').replace('\t', ' ').replaceAll(" {2,}", " ").trim();
    }

    LocalDateTime cellToDateTime(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue();
            String s = cellToString(cell);
            if (s.matches("\\d{2}/\\d{2}/\\d{4}.*")) return java.time.LocalDate.parse(s.substring(0, 10), FR_DATE).atStartOfDay();
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return java.time.LocalDate.parse(s.substring(0, 10)).atStartOfDay();
        } catch (Exception ignored) {
            // date illisible : simplement non reprise
        }
        return null;
    }

    /** Valeur pour une question de type DATE : yyyy-MM-dd (format du champ date du navigateur). */
    private String cellToIsoDate(Cell cell) {
        LocalDateTime d = cellToDateTime(cell);
        return d != null ? d.toLocalDate().toString() : cellToString(cell);
    }

    /** JSON borné à la taille de la colonne (4000) : valeurs longues tronquées, dernières clés
     *  abandonnées si nécessaire — un import ne doit jamais échouer sur une cellule trop longue. */
    private String serializeLimited(Map<String, String> values) {
        if (values == null || values.isEmpty()) return null;
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            kept.put(limit(e.getKey(), 200), limit(e.getValue(), 600));
            String json = serializeAnswers(kept);
            if (json != null && json.length() > 3900) { kept.remove(limit(e.getKey(), 200)); break; }
        }
        return serializeAnswers(kept);
    }

    private static String limit(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
