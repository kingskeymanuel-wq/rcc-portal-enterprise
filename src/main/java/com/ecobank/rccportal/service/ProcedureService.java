package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureStep;
import com.ecobank.rccportal.model.ProcedureZone;
import com.ecobank.rccportal.model.RccService;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fiches procédure + étapes normalisées — remplace eco_procs (tableau JSON
 * steps[] côté frontend original). Toute mise à jour des étapes remplace la
 * liste entière (delete + re-insert) : plus simple et plus sûr qu'un diff fin
 * pour un objet aussi court (quelques étapes par fiche).
 *
 * Visibilité par équipe : une procédure peut être rattachée à un service
 * (RccService — même notion "service = équipe" que partout ailleurs sur le
 * site). Une procédure sans service est générique, visible par tout le monde.
 * QA/admin voient toujours tout (nécessaire pour gérer les fiches de toutes
 * les équipes) ; un agent ne voit que les fiches génériques + celles de son
 * propre service.
 */
@Service
public class ProcedureService {
    private final AttachmentRepository attachmentRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureStepRepository procedureStepRepository;
    private final ProcedureZoneRepository procedureZoneRepository;
    private final RccServiceRepository rccServiceRepository;
    private final UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final UserRepository userRepository;
    private final KpiEventService kpiEventService;
    private final FavoriteProcedureRepository favoriteProcedureRepository;
    private final ProcedureWorkflowNodeRepository procedureWorkflowNodeRepository;
    private final com.ecobank.rccportal.service.ImageStorageService imageStorageService;
    private final com.ecobank.rccportal.repository.FavoriteAttachmentRepository favoriteAttachmentRepository;

    public ProcedureService(
            ProcedureRepository procedureRepository,
            ProcedureStepRepository procedureStepRepository,
            ProcedureZoneRepository procedureZoneRepository,
            RccServiceRepository rccServiceRepository,
            UserServiceAssignmentRepository userServiceAssignmentRepository,
            UserRepository userRepository,
            AttachmentRepository attachmentRepository,
            KpiEventService kpiEventService,
            FavoriteProcedureRepository favoriteProcedureRepository,
            ProcedureWorkflowNodeRepository procedureWorkflowNodeRepository,
            com.ecobank.rccportal.service.ImageStorageService imageStorageService,
            com.ecobank.rccportal.repository.FavoriteAttachmentRepository favoriteAttachmentRepository) {

        this.procedureRepository = procedureRepository;
        this.procedureStepRepository = procedureStepRepository;
        this.procedureZoneRepository = procedureZoneRepository;
        this.rccServiceRepository = rccServiceRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.userRepository = userRepository;
        this.attachmentRepository = attachmentRepository;
        this.kpiEventService = kpiEventService;
        this.favoriteProcedureRepository = favoriteProcedureRepository;
        this.procedureWorkflowNodeRepository = procedureWorkflowNodeRepository;
        this.imageStorageService = imageStorageService;
        this.favoriteAttachmentRepository = favoriteAttachmentRepository;
    }

    @Transactional(readOnly = true)
    public List<ProcedureZoneResponse> listZones() {
        return listZones(null);
    }

    /** team non nul : ne renvoie que les zones de cette équipe (+ zones historiques sans
     *  équipe, traitées comme INBOUND_VOICE). */
    @Transactional(readOnly = true)
    public List<ProcedureZoneResponse> listZones(String team) {
        return procedureZoneRepository.findAll().stream()
                .filter(z -> team == null || team.isBlank()
                        || team.equalsIgnoreCase(z.getTeam())
                        || (z.getTeam() == null && ("INBOUND_VOICE".equalsIgnoreCase(team) || "INBOUND_MAIL".equalsIgnoreCase(team))))
                .map(z -> new ProcedureZoneResponse(z.getZoneId(), z.getCode(), z.getLabel(), z.getImageUrl(), z.getTeam()))
                .toList();
    }

    /** QA/Admin voient toutes les équipes ; un agent classique ne voit que la sienne — même
     *  logique que KnowledgeService.listCategoriesFor(). */
    @Transactional(readOnly = true)
    public List<ProcedureZoneResponse> listZonesFor(com.ecobank.rccportal.security.AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isAdmin || isQa || requester == null) {
            return listZones();
        }
        var user = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
        String myTeam = user != null
                ? com.ecobank.rccportal.util.TeamClassifier.classify(user.getActivity()).name()
                : null;
        if (myTeam == null || "OTHER".equals(myTeam)) {
            return listZones();
        }
        return listZones(myTeam);
    }

    /** Ouvert à tous (contrairement à AdministrationController.services, réservé admin) — juste pour un sélecteur. */
    @Transactional(readOnly = true)
    public List<RccServiceResponse> listServices() {
        return rccServiceRepository.findAll().stream()
                .map(s -> new RccServiceResponse(s.getId(), s.getName(), s.getDescription(), s.getPath(), s.getCode(),
                        s.getIcon(), s.getColor(), s.getStatus(), s.getEnabled(),
                        s.getDisplayOrder(), s.getOpenInNewTab(), s.getPortalApp(), s.getProxyCode()))
                .toList();
    }

    /**
     * Crée une nouvelle zone/catégorie (ex. "COMPTE", "TRANSFERT"...) — réservé aux
     * QA/admin côté contrôleur. Idempotent sur le code : si la zone existe déjà,
     * renvoie une erreur explicite plutôt qu'un doublon silencieux.
     */
    @Transactional
    public ProcedureZoneResponse createZone(CreateProcedureZoneRequest request) {
        String code = request.code() == null ? "" : request.code().trim();
        String label = request.label() == null ? "" : request.label().trim();

        if (code.isBlank()) {
            throw ApiException.badRequest("Zone code is required.");
        }
        if (label.isBlank()) {
            throw ApiException.badRequest("Zone label is required.");
        }
        if (procedureZoneRepository.findByCode(code).isPresent()) {
            throw ApiException.badRequest("A zone with this code already exists.");
        }

        ProcedureZone zone = procedureZoneRepository.save(
                ProcedureZone.builder().code(code).label(label)
                        .team(request.team() != null && !request.team().isBlank() ? request.team().trim().toUpperCase() : null)
                        .build());

        return new ProcedureZoneResponse(zone.getZoneId(), zone.getCode(), zone.getLabel(), zone.getImageUrl(), zone.getTeam());
    }

    /** Upload réel — réservé à l'administrateur (voir ProcedureController). */
    @Transactional
    public ProcedureZoneResponse updateZoneImage(Integer zoneId, org.springframework.web.multipart.MultipartFile file) {
        ProcedureZone zone = procedureZoneRepository.findById(zoneId)
                .orElseThrow(() -> ApiException.notFound("Unknown zone."));
        zone.setImageUrl(imageStorageService.store(file));
        procedureZoneRepository.save(zone);
        return new ProcedureZoneResponse(zone.getZoneId(), zone.getCode(), zone.getLabel(), zone.getImageUrl(), zone.getTeam());
    }

    @Transactional(readOnly = true)
    public List<ProcedureSummaryResponse> listAll(AuthenticatedUser requester) {
        List<ProcedureSummaryResponse> all = toSummaries(procedureRepository.findAllByOrderByTitleAsc(), requester);
        return filterVisible(all, requester);
    }

    @Transactional(readOnly = true)
    public List<ProcedureSummaryResponse> listByZone(String zoneCode, AuthenticatedUser requester) {
        var zone = procedureZoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> ApiException.badRequest("Unknown zone."));
        List<ProcedureSummaryResponse> byZone = toSummaries(procedureRepository.findByZoneOrderByTitleAsc(zone), requester);
        return filterVisible(byZone, requester);
    }

    @Transactional
    public ProcedureResponse getById(Integer id, String viewerusername) {
        Procedure procedure = procedureRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));
        kpiEventService.record(viewerusername, "proc_view", procedure.getZone().getCode());
        return toResponse(procedure, viewerusername);
    }

    private void assertValid(String zoneCode, String title, List<String> steps) {
        if (zoneCode == null || zoneCode.isBlank()) throw ApiException.badRequest("zoneCode is required.");
        if (title == null || title.isBlank()) throw ApiException.badRequest("Title is required.");
        if (steps == null || steps.isEmpty()) throw ApiException.badRequest("At least one step is required.");
        if (steps.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw ApiException.badRequest("Steps cannot be empty.");
        }
    }

    @Transactional
    public ProcedureResponse create(ProcedureRequest request, AuthenticatedUser requester) {
        assertValid(request.zoneCode(), request.title(), request.steps());
        var zone = procedureZoneRepository.findByCode(request.zoneCode())
                .orElseThrow(() -> ApiException.badRequest("Unknown zone."));
        User author = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        RccService service = resolveService(request.serviceCode());
        String countryCode = (request.countryCode() != null && !request.countryCode().isBlank())
                ? request.countryCode().trim().toUpperCase() : null;

        Procedure procedure = procedureRepository.save(Procedure.builder()
                .zone(zone).service(service).countryCode(countryCode).title(request.title().trim())
                .slaDelay(request.slaDelay()).level(request.level()).responsibleTeam(request.responsibleTeam())
                .createdBy(author).build());
        saveSteps(procedure, request.steps());
        kpiEventService.record(requester.username(), "proc_add", zone.getCode());

        return toResponse(procedure, requester.username());
    }

    /**
     * Trouve ou crée le "dossier conteneur" pour un triplet (zone, service, filiale) — utilisé par
     * l'import rapide depuis une vignette de thématique (l'admin/QA n'a pas besoin de créer une
     * fiche au préalable, juste de déposer un fichier). Pas de nouvelle colonne : le conteneur est
     * repéré par son titre, généré de façon déterministe à partir du triplet, donc toujours le même
     * pour un même choix de filiale/service — un second import réutilise le même dossier plutôt que
     * d'en recréer un.
     */
    @Transactional
    public Procedure findOrCreateContainer(String zoneCode, String serviceCode, String countryCode, AuthenticatedUser requester) {
        var zone = procedureZoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> ApiException.badRequest("Unknown zone."));
        RccService service = resolveService(serviceCode);
        String normalizedCountry = (countryCode != null && !countryCode.isBlank())
                ? countryCode.trim().toUpperCase() : null;

        String containerTitle = buildContainerTitle(zone.getLabel(), service, normalizedCountry);

        for (Procedure candidate : procedureRepository.findByZoneOrderByTitleAsc(zone)) {
            if (containerTitle.equalsIgnoreCase(candidate.getTitle().trim())) {
                return candidate;
            }
        }

        User author = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        Procedure procedure = procedureRepository.save(Procedure.builder()
                .zone(zone).service(service).countryCode(normalizedCountry).title(containerTitle).createdBy(author).build());
        saveSteps(procedure, List.of("Dossier de fichiers — voir les pièces jointes ci-dessous."));
        kpiEventService.record(requester.username(), "proc_add", zone.getCode());
        return procedure;
    }

    private String buildContainerTitle(String zoneLabel, RccService service, String countryCode) {
        StringBuilder sb = new StringBuilder("Documents — ").append(zoneLabel);
        if (service != null) sb.append(" (").append(service.getName()).append(")");
        if (countryCode != null) sb.append(" [").append(countryCode).append("]");
        return sb.toString();
    }

    /** Seuls l'auteur ou un admin peuvent modifier/supprimer une fiche (même règle que les masques mail). */
    @Transactional
    public ProcedureResponse update(Integer id, ProcedureRequest request, AuthenticatedUser requester) {
        Procedure procedure = procedureRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));
        assertAuthorOrAdmin(procedure, requester);

        if (request.zoneCode() != null) {
            procedure.setZone(procedureZoneRepository.findByCode(request.zoneCode())
                    .orElseThrow(() -> ApiException.badRequest("Unknown zone.")));
        }
        if (request.serviceCode() != null) {
            // Chaîne vide = retirer l'affectation (redevient une fiche générique).
            procedure.setService(request.serviceCode().isBlank() ? null : resolveService(request.serviceCode()));
        }
        if (request.countryCode() != null) {
            procedure.setCountryCode(request.countryCode().isBlank() ? null : request.countryCode().trim().toUpperCase());
        }
        if (request.title() != null) procedure.setTitle(request.title().trim());
        if (request.slaDelay() != null) procedure.setSlaDelay(request.slaDelay().isBlank() ? null : request.slaDelay().trim());
        if (request.level() != null) procedure.setLevel(request.level().isBlank() ? null : request.level().trim());
        if (request.responsibleTeam() != null) procedure.setResponsibleTeam(request.responsibleTeam().isBlank() ? null : request.responsibleTeam().trim());
        procedureRepository.save(procedure);

        if (request.steps() != null) {
            if (request.steps().stream().anyMatch(s -> s == null || s.isBlank())) {
                throw ApiException.badRequest("Steps cannot be empty.");
            }
            procedureStepRepository.deleteByProcedure(procedure);
            saveSteps(procedure, request.steps());
        }

        return toResponse(procedure, requester.username());
    }

    @Transactional
    public void remove(Integer id, AuthenticatedUser requester) {
        Procedure procedure = procedureRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));
        assertAuthorOrAdmin(procedure, requester);
        procedureRepository.delete(procedure); // cascade sur ProcedureSteps (ON DELETE CASCADE en base)
    }

    private void assertAuthorOrAdmin(Procedure procedure, AuthenticatedUser requester) {
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isQa) return;
        String authorusername = procedure.getCreatedBy() != null ? procedure.getCreatedBy().getUsername() : null;
        if (!requester.username().equals(authorusername)) {
            throw ApiException.forbidden("You can only modify your own procedures.");
        }
    }

    private RccService resolveService(String serviceCode) {
        if (serviceCode == null || serviceCode.isBlank()) return null;
        return rccServiceRepository.findByCodeIgnoreCase(serviceCode.trim())
                .orElseThrow(() -> ApiException.badRequest("Unknown service: " + serviceCode));
    }

    /**
     * QA/admin voient tout (ils gèrent les fiches de toutes les équipes). Un agent
     * ne voit que les fiches génériques (sans service) et celles de son propre
     * service — c'est le cœur de la demande : chaque équipe ne voit que ce qui la
     * concerne.
     */
    private List<ProcedureSummaryResponse> filterVisible(List<ProcedureSummaryResponse> summaries, AuthenticatedUser requester) {
        if (requester == null) return summaries;
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isAdmin || isQa) return summaries;

        String myServiceCode = currentUserServiceCode(requester.username());
        return summaries.stream()
                .filter(p -> p.serviceCode() == null || p.serviceCode().equalsIgnoreCase(myServiceCode))
                .toList();
    }

    private String currentUserServiceCode(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
        if (user == null || user.getId() == null) return null;
        List<UserServiceAssignment> assignments = userServiceAssignmentRepository.findServicesByUserId(user.getId());
        if (assignments == null || assignments.isEmpty() || assignments.get(0).getService() == null) return null;
        return assignments.get(0).getService().getCode();
    }

    private void saveSteps(Procedure procedure, List<String> steps) {
        int stepNumber = 1;
        for (String content : steps) {
            procedureStepRepository.save(ProcedureStep.builder()
                    .procedure(procedure).stepNumber(stepNumber++).content(content.trim()).build());
        }
    }

    /** Charge le nombre d'étapes de toutes les fiches en une seule requête (évite le N+1 par fiche). */
    private List<ProcedureSummaryResponse> toSummaries(List<Procedure> procedures, AuthenticatedUser requester) {
        if (procedures.isEmpty()) return List.of();
        Map<Integer, Long> stepCountByProcedureId = procedureStepRepository.findByProcedureIn(procedures).stream()
                .collect(Collectors.groupingBy(s -> s.getProcedure().getProcedureId(), Collectors.counting()));

        User me = (requester != null)
                ? userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null)
                : null;

        return procedures.stream()
                .map(p -> new ProcedureSummaryResponse(p.getProcedureId(), p.getZone().getCode(),
                        p.getService() != null ? p.getService().getCode() : null,
                        p.getService() != null ? p.getService().getName() : null,
                        p.getCountryCode(),
                        p.getTitle(),
                        stepCountByProcedureId.getOrDefault(p.getProcedureId(), 0L).intValue(),
                        !procedureWorkflowNodeRepository.findByProcedure(p).isEmpty(),
                        me != null && favoriteProcedureRepository.existsByUserAndProcedure(me, p),
                        p.getCreatedBy() != null ? p.getCreatedBy().getUsername() : null, p.getCreatedAt(), p.getUpdatedAt()))
                .toList();
    }
    private ProcedureResponse toResponse(Procedure p, String viewerusername) {

        List<String> steps = procedureStepRepository
                .findByProcedureOrderByStepNumberAsc(p)
                .stream()
                .map(ProcedureStep::getContent)
                .toList();

        User viewer = viewerusername != null
                ? userRepository.findFirstByUsernameIgnoreCase(viewerusername).orElse(null) : null;

        List<AttachmentResponse> attachments = attachmentRepository
                .findByEntityTypeAndEntityId("Procedure", p.getProcedureId())
                .stream()
                .map(a -> new AttachmentResponse(
                        a.getAttachmentId(),
                        a.getEntityType(),
                        a.getEntityId(),
                        a.getFileName(),
                        a.getMimeType(),
                        a.getStorageUrl(),
                        a.getUploadedBy() != null ? a.getUploadedBy().getUsername() : null,
                        a.getCreatedAt(),
                        viewer != null && favoriteAttachmentRepository.existsByUserAndAttachment(viewer, a)
                ))
                .toList();

        return new ProcedureResponse(
                p.getProcedureId(),
                p.getZone().getCode(),
                p.getService() != null ? p.getService().getCode() : null,
                p.getService() != null ? p.getService().getName() : null,
                p.getCountryCode(),
                p.getTitle(),
                p.getSlaDelay(),
                p.getLevel(),
                p.getResponsibleTeam(),
                steps,
                attachments,
                p.getCreatedBy() != null ? p.getCreatedBy().getUsername() : null,
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public ProcedureDashboardResponse dashboard() {

        long procedures = procedureRepository.count();
        long categories = procedureZoneRepository.count();
        long workflows = procedureWorkflowNodeRepository.countByIsStartTrue();
        long favorites = favoriteProcedureRepository.count();

        return new ProcedureDashboardResponse(
                procedures,
                categories,
                workflows,
                0,
                0,
                0,
                favorites
        );
    }

    @Transactional(readOnly = true)
    public List<ProcedureSummaryResponse> search(String keyword, AuthenticatedUser requester) {

        if (keyword == null || keyword.isBlank()) {
            return listAll(requester);
        }

        List<ProcedureSummaryResponse> results = toSummaries(
                procedureRepository.findByTitleContainingIgnoreCaseOrderByTitleAsc(keyword.trim()), requester
        );
        return filterVisible(results, requester);
    }

}
