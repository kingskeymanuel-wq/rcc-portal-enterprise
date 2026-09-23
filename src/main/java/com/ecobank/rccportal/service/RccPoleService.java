package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.RccPoleRequest;
import com.ecobank.rccportal.dto.RccPoleResponse;
import com.ecobank.rccportal.dto.SlaRuleRequest;
import com.ecobank.rccportal.dto.SlaRuleResponse;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.RccPole;
import com.ecobank.rccportal.model.SlaRule;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.RccPoleRepository;
import com.ecobank.rccportal.repository.SlaRuleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Fiches pôle RCC (Inbound, Outbound, Résolution, Opérations, Business, Agences) —
 * organigramme cliquable : chaque pôle porte un manager/contact et la liste de ses
 * activités/délais (voir SlaRule.pole). Lecture ouverte à tout agent authentifié ;
 * écriture (fiche + activités) réservée à QA/ADMIN (voir requireQaOrAdmin).
 *
 * L'assignation/alerte à un manager de pôle crée une notification in-app (RccNotification,
 * même mécanisme que WorkflowService) ET tente un message Teams + e-mail Outlook réels via
 * MicrosoftGraphClient (même client que GraphController) — un échec de l'un des deux canaux
 * n'empêche pas l'autre ni la notification in-app, qui reste la trace garantie de l'alerte.
 */
@Service
public class RccPoleService {

    private final RccPoleRepository poleRepository;
    private final SlaRuleRepository slaRuleRepository;
    private final UserRepository userRepository;
    private final RccNotificationRepository notificationRepository;
    private final MicrosoftGraphClient graphClient;

    public RccPoleService(RccPoleRepository poleRepository, SlaRuleRepository slaRuleRepository,
                           UserRepository userRepository, RccNotificationRepository notificationRepository,
                           MicrosoftGraphClient graphClient) {
        this.poleRepository = poleRepository;
        this.slaRuleRepository = slaRuleRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.graphClient = graphClient;
    }

    // ---------- Fiches pôle ----------

    @Transactional(readOnly = true)
    public List<RccPoleResponse> listActive() {
        return poleRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(p -> RccPoleResponse.from(p, slaRuleRepository.countByPole_PoleIdAndIsActiveTrue(p.getPoleId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RccPoleResponse> listAll() {
        return poleRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(p -> RccPoleResponse.from(p, slaRuleRepository.countByPole_PoleIdAndIsActiveTrue(p.getPoleId())))
                .toList();
    }

    @Transactional
    public RccPoleResponse create(RccPoleRequest request) {
        RccPole pole = new RccPole();
        apply(pole, request);
        pole = poleRepository.save(pole);
        return RccPoleResponse.from(pole, 0);
    }

    @Transactional
    public RccPoleResponse update(Integer id, RccPoleRequest request) {
        RccPole pole = poleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Pôle introuvable (id=" + id + ")."));
        apply(pole, request);
        pole = poleRepository.save(pole);
        return RccPoleResponse.from(pole, slaRuleRepository.countByPole_PoleIdAndIsActiveTrue(pole.getPoleId()));
    }

    @Transactional
    public void delete(Integer id) {
        if (!poleRepository.existsById(id)) {
            throw ApiException.notFound("Pôle introuvable (id=" + id + ").");
        }
        poleRepository.deleteById(id);
    }

    private void apply(RccPole pole, RccPoleRequest request) {
        pole.setName(request.name());
        pole.setManager(resolveManager(request.managerUsername()));
        pole.setContactPhone(request.contactPhone());
        pole.setContactEmail(request.contactEmail());
        pole.setTeamContactLabel(request.teamContactLabel());
        pole.setWhoWeAre(request.whoWeAre());
        pole.setWhatWeDo(request.whatWeDo());
        pole.setIsActive(request.isActive() == null || request.isActive());
        pole.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
    }

    private User resolveManager(String username) {
        if (username == null || username.isBlank()) return null;
        return userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.badRequest("Compte manager introuvable : " + username));
    }

    // ---------- Activités & délais du pôle (SlaRule rattachées) ----------

    @Transactional(readOnly = true)
    public List<SlaRuleResponse> listActivities(Integer poleId) {
        requirePole(poleId);
        return slaRuleRepository.findByPole_PoleIdAndIsActiveTrueOrderBySortOrderAscCategoryAscMotifAsc(poleId).stream()
                .map(SlaRuleResponse::from)
                .toList();
    }

    @Transactional
    public SlaRuleResponse addActivity(Integer poleId, SlaRuleRequest request) {
        RccPole pole = requirePole(poleId);
        SlaRule rule = new SlaRule();
        rule.setPole(pole);
        applyActivity(rule, request);
        return SlaRuleResponse.from(slaRuleRepository.save(rule));
    }

    @Transactional
    public SlaRuleResponse updateActivity(Integer poleId, Integer ruleId, SlaRuleRequest request) {
        requirePole(poleId);
        SlaRule rule = slaRuleRepository.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("Activité introuvable (id=" + ruleId + ")."));
        if (rule.getPole() == null || !rule.getPole().getPoleId().equals(poleId)) {
            throw ApiException.notFound("Cette activité n'appartient pas au pôle demandé.");
        }
        applyActivity(rule, request);
        return SlaRuleResponse.from(slaRuleRepository.save(rule));
    }

    @Transactional
    public void deleteActivity(Integer poleId, Integer ruleId) {
        SlaRule rule = slaRuleRepository.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("Activité introuvable (id=" + ruleId + ")."));
        if (rule.getPole() == null || !rule.getPole().getPoleId().equals(poleId)) {
            throw ApiException.notFound("Cette activité n'appartient pas au pôle demandé.");
        }
        slaRuleRepository.delete(rule);
    }

    private void applyActivity(SlaRule rule, SlaRuleRequest request) {
        rule.setMotif(request.motif());
        rule.setCategory(request.category());
        rule.setLevel(request.level());
        rule.setSlaHours(request.slaHours());
        rule.setSlaLabel(request.slaLabel());
        rule.setDestinationService(request.destinationService());
        rule.setPriority(request.priority());
        rule.setAutoEscalation(request.autoEscalation() != null && request.autoEscalation());
        rule.setNotes(request.notes());
        rule.setIsActive(request.isActive() == null || request.isActive());
        rule.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
    }

    private RccPole requirePole(Integer poleId) {
        return poleRepository.findById(poleId)
                .orElseThrow(() -> ApiException.notFound("Pôle introuvable (id=" + poleId + ")."));
    }

    // ---------- Assignation / alerte au manager du pôle ----------

    /**
     * Notifie le manager d'un pôle (traitement en cours à faire, réclamation reçue...).
     * Trois canaux tentés indépendamment : notification in-app (garantie, toujours créée),
     * message Teams et e-mail Outlook (best-effort — un canal externe indisponible n'empêche
     * ni l'autre ni la notification in-app, qui reste la preuve que l'alerte a été déclenchée).
     */
    @Transactional
    public void alertManager(Integer poleId, String message, AuthenticatedUser requester) {
        RccPole pole = requirePole(poleId);
        if (pole.getManager() == null) {
            throw ApiException.badRequest("Ce pôle n'a pas de manager désigné — impossible d'envoyer une alerte.");
        }
        User manager = pole.getManager();
        User from = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));

        String content = (message == null || message.isBlank())
                ? "Alerte sur le pôle " + pole.getName() + " — traitement en attente."
                : message.trim();

        notificationRepository.save(RccNotification.builder()
                .targetUser(manager)
                .content("[" + pole.getName() + "] " + content)
                .isRead(false)
                .build());

        if (graphClient.isConfigured() && from.getEmail() != null && !from.getEmail().isBlank()
                && manager.getEmail() != null && !manager.getEmail().isBlank()) {
            try {
                graphClient.sendTeamsMessage(from.getEmail(), manager.getEmail(),
                        "Alerte pôle " + pole.getName() + " — " + content);
            } catch (Exception ignored) {
                // Best-effort — la notification in-app fait déjà foi.
            }
            try {
                graphClient.sendMail(from.getEmail(), manager.getEmail(),
                        "RCC Portal — Alerte pôle " + pole.getName(), content);
            } catch (Exception ignored) {
                // Best-effort — idem.
            }
        }
    }
}
