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
            int lastCol = headerRow.getLastCellNum();
            List<String> headers = new ArrayList<>();
            for (int col = 0; col < lastCol; col++) headers.add(cellToString(headerRow.getCell(col)).trim());

            Row sampleDataRow = null;
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row candidate = sheet.getRow(rowIndex);
                if (candidate != null) { sampleDataRow = candidate; break; }
            }
            List<String> sampleRow = new ArrayList<>();
            for (int col = 0; col < lastCol; col++) {
                sampleRow.add(sampleDataRow == null ? "" : cellToString(sampleDataRow.getCell(col)).trim());
            }

            CampaignImportMappingDto suggested = buildSuggestedMapping(headers, fields);
            return new CampaignImportPreviewResponse(headers, sampleRow, suggested, fields);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier illisible : " + e.getMessage());
        }
    }

    /** Propose un mapping par défaut à partir des en-têtes réels du fichier fourni — l'utilisateur
     *  peut ensuite tout corriger dans la modale avant de confirmer, ce n'est qu'une suggestion. */
    private CampaignImportMappingDto buildSuggestedMapping(List<String> headers, List<CampaignFieldDto> fields) {
        Integer nameCol = null, phoneCol = null, accountCol = null, agentCol = null;
        Map<Integer, String> fieldCols = new LinkedHashMap<>();
        for (int col = 0; col < headers.size(); col++) {
            String h = normalizeForMatch(headers.get(col));
            if (h.isBlank()) continue;
            if (nameCol == null && (h.contains("nom") || h.contains("client") || h.contains("name") || h.contains("customer"))) { nameCol = col; continue; }
            if (phoneCol == null && (h.contains("tel") || h.contains("phone") || h.contains("gsm") || (h.contains("numero") && h.contains("appel")))) { phoneCol = col; continue; }
            if (accountCol == null && (h.contains("compte") || h.contains("account"))) { accountCol = col; continue; }
            if (agentCol == null && (h.contains("agent") || h.contains("matricule") || h.contains("username"))) { agentCol = col; continue; }
            String matchedFieldId = matchFieldByLabel(headers.get(col), fields);
            if (matchedFieldId != null) fieldCols.put(col, matchedFieldId);
        }
        // Repli CIF pour la colonne compte, seulement si "compte/account" n'a matché nulle part —
        // priorité au vrai numéro de compte quand les deux sont présents (ex. CustomerCif +
        // AccountNumber dans le même fichier).
        if (accountCol == null) {
            for (int col = 0; col < headers.size(); col++) {
                String h = normalizeForMatch(headers.get(col));
                if (h.contains("cif")) { accountCol = col; break; }
            }
        }
        // Repli si rien n'a été identifié par mots-clés : ancienne disposition par défaut
        // (A=nom, B=téléphone, C=compte) — mieux qu'un mapping vide sur un fichier sans en-têtes utiles.
        if (nameCol == null && !headers.isEmpty()) nameCol = 0;
        if (phoneCol == null && headers.size() > 1) phoneCol = 1;
        if (accountCol == null && headers.size() > 2) accountCol = 2;
        return new CampaignImportMappingDto(nameCol, phoneCol, accountCol, agentCol, fieldCols);
    }

    @Transactional
    public int importContacts(AuthenticatedUser requester, Integer campaignId, MultipartFile file, CampaignImportMappingDto mapping) {
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
    public int selfImportContacts(AuthenticatedUser requester, MultipartFile file, CampaignImportMappingDto mapping) {
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

    private int readContactsFile(MultipartFile file, Integer campaignId, CampaignImportMappingDto mapping, Long forcedAgentUserId) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }
        List<CampaignFieldDto> fields = campaignRepository.findById(campaignId)
                .map(c -> deserializeFields(c.getFieldsJson()))
                .orElse(List.of());

        int imported = 0;
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            Row headerRow = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            if (headerRow != null) {
                int lastCol = headerRow.getLastCellNum();
                for (int col = 0; col < lastCol; col++) headers.add(cellToString(headerRow.getCell(col)).trim());
            }

            // Pas de mapping fourni (appel direct, ancien client) : on en déduit un à partir
            // des en-têtes du fichier lui-même, exactement comme le ferait la prévisualisation.
            CampaignImportMappingDto effectiveMapping = mapping;
            if (effectiveMapping == null) {
                effectiveMapping = buildSuggestedMapping(headers, fields);
            }
            if (effectiveMapping.nameColumn() == null) {
                throw ApiException.badRequest("Impossible de déterminer la colonne du nom du client — précisez le mapping des colonnes.");
            }

            int nameCol = effectiveMapping.nameColumn();
            Integer phoneCol = effectiveMapping.phoneColumn();
            Integer accountCol = effectiveMapping.accountColumn();
            Integer agentCol = forcedAgentUserId != null ? null : effectiveMapping.agentColumn();
            Map<Integer, String> fieldCols = effectiveMapping.fieldColumns() != null ? effectiveMapping.fieldColumns() : Map.of();

            // Toute colonne du fichier qui n'est NI le nom, NI le téléphone, NI le compte, NI
            // l'agent, NI une question du modèle de campagne — conservée telle quelle dans
            // extraDataJson (clé = en-tête réel du fichier), pour qu'aucune donnée ne se perde,
            // quel que soit le type de fichier d'appel importé (voir demande utilisateur).
            Set<Integer> mappedCols = new java.util.HashSet<>(fieldCols.keySet());
            mappedCols.add(nameCol);
            if (phoneCol != null) mappedCols.add(phoneCol);
            if (accountCol != null) mappedCols.add(accountCol);
            if (agentCol != null) mappedCols.add(agentCol);
            List<Integer> extraCols = new ArrayList<>();
            for (int col = 0; col < headers.size(); col++) {
                if (!mappedCols.contains(col) && !headers.get(col).isBlank()) extraCols.add(col);
            }

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;

                String clientName = cellToString(row.getCell(nameCol)).trim();
                if (clientName.isBlank()) continue;
                String clientPhone = phoneCol == null ? "" : cellToString(row.getCell(phoneCol)).trim();
                String rawAccountNumber = accountCol == null ? "" : cellToString(row.getCell(accountCol)).trim();

                Long agentUserId = forcedAgentUserId;
                if (agentUserId == null && agentCol != null) {
                    String agentIdentifier = cellToString(row.getCell(agentCol)).trim();
                    if (!agentIdentifier.isBlank()) {
                        User agent = userRepository.findFirstByUsernameIgnoreCase(agentIdentifier).orElse(null);
                        if (agent != null) agentUserId = agent.getId();
                    }
                }

                Map<String, String> prefilledAnswers = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> entry : fieldCols.entrySet()) {
                    String value = cellToString(row.getCell(entry.getKey())).trim();
                    if (!value.isBlank()) prefilledAnswers.put(entry.getValue(), value);
                }

                Map<String, String> extraData = new LinkedHashMap<>();
                for (Integer col : extraCols) {
                    String value = cellToString(row.getCell(col)).trim();
                    if (!value.isBlank()) extraData.put(headers.get(col), value);
                }

                campaignContactRepository.save(CampaignContact.builder()
                        .campaignId(campaignId)
                        .agentUserId(agentUserId)
                        .clientName(clientName)
                        .clientPhone(blankToNull(clientPhone))
                        .maskedAccountNumber(maskAccountNumber(rawAccountNumber))
                        .answersJson(prefilledAnswers.isEmpty() ? null : serializeAnswers(prefilledAnswers))
                        .extraDataJson(extraData.isEmpty() ? null : serializeAnswers(extraData))
                        .callStatus("PENDING")
                        .build());
                imported++;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier illisible : " + e.getMessage());
        }
        return imported;
    }

    /** Compare l'en-tête d'une colonne au libellé de chaque question du modèle (insensible à la
     *  casse, aux accents et à la ponctuation) — renvoie l'id de la question correspondante, ou
     *  null si aucune ne correspond (la colonne reste alors une information non exploitée). */
    private String matchFieldByLabel(String header, List<CampaignFieldDto> fields) {
        String normalizedHeader = normalizeForMatch(header);
        for (CampaignFieldDto field : fields) {
            if (field.label() != null && normalizeForMatch(field.label()).equals(normalizedHeader)) {
                return field.id();
            }
        }
        return null;
    }

    private String normalizeForMatch(String s) {
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

    private User requireUser(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    private void requireCanManage(AuthenticatedUser requester) {
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

    private String normalizeTargetService(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase();
        if (!v.equals("DIGITAL") && !v.equals("TELEVENTE")) {
            throw ApiException.badRequest("Service ciblé invalide : " + raw + " (attendu DIGITAL, TELEVENTE, ou vide pour toute l'équipe).");
        }
        return v;
    }

    private CampaignResponse toResponse(Campaign c, List<CampaignContact> contacts) {
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

    private String serializeFields(List<CampaignFieldDto> fields) {
        if (fields == null || fields.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw ApiException.badRequest("Modèle de questions invalide : " + e.getMessage());
        }
    }

    private List<CampaignFieldDto> deserializeFields(String json) {
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

    private Map<String, String> deserializeAnswers(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String cellToString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> cell.toString();
        };
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
