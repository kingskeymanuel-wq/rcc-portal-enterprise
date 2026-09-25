package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AssignedTaskRequest;
import com.ecobank.rccportal.dto.AssignedTaskResponse;
import com.ecobank.rccportal.model.AssignedTask;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AssignedTaskRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Notes / tâches — onglet "Notes" de Workflow. Ouvert à tout utilisateur pour une tâche
 * personnelle (assignedToUsername et assignedToTeamCode absents). Assigner une tâche à
 * quelqu'un d'autre (un individu ou toute une équipe) est réservé QA/Admin — la personne
 * ciblée (ou toute l'équipe) reçoit une notification en app + un e-mail (reçu dans Outlook).
 */
@Service
public class AssignedTaskService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssignedTaskService.class);

    private final AssignedTaskRepository assignedTaskRepository;
    private final UserRepository userRepository;
    private final RccNotificationRepository rccNotificationRepository;
    private final NotificationService notificationService;

    public AssignedTaskService(AssignedTaskRepository assignedTaskRepository, UserRepository userRepository,
                               RccNotificationRepository rccNotificationRepository, NotificationService notificationService) {
        this.assignedTaskRepository = assignedTaskRepository;
        this.userRepository = userRepository;
        this.rccNotificationRepository = rccNotificationRepository;
        this.notificationService = notificationService;
    }

    /** Toutes les tâches visibles par cet utilisateur : les siennes en propre + celles assignées à lui + celles de son équipe. */
    @Transactional(readOnly = true)
    public List<AssignedTaskResponse> myTasks(String username) {
        User me = findUser(username);
        java.util.LinkedHashMap<Integer, AssignedTask> byId = new java.util.LinkedHashMap<>();

        assignedTaskRepository.findByCreatedByUserAndAssignedToIsNullAndAssignedToTeamCodeIsNullOrderByCreatedAtDesc(me)
                .forEach(t -> byId.put(t.getTaskId(), t));
        assignedTaskRepository.findByAssignedToOrderByCreatedAtDesc(me)
                .forEach(t -> byId.put(t.getTaskId(), t));
        if (me.getActivity() != null && !me.getActivity().isBlank()) {
            assignedTaskRepository.findByAssignedToTeamCodeOrderByCreatedAtDesc(me.getActivity())
                    .forEach(t -> byId.put(t.getTaskId(), t));
        }

        return byId.values().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Suivi présence — créé par un Team Leader (ou QA/Admin) pour marquer le retard/absence
     * d'un agent de SON équipe, justifié ou non, avec la raison si justifié. Génère une
     * tâche à faire signer par l'agent (voir markDone — DONE = signé/attesté) et remonte
     * automatiquement chez RH et le Superviseur (voir oversightTasks()).
     */
    @Transactional
    public AssignedTaskResponse createAttendanceFollowUp(String teamLeaderUsername, com.ecobank.rccportal.dto.AttendanceFollowUpRequest request) {
        User teamLeader = findUser(teamLeaderUsername);
        User agent = userRepository.findFirstByUsernameIgnoreCase(request.agentUsername())
                .orElseThrow(() -> ApiException.notFound("Agent inconnu : " + request.agentUsername()));

        boolean isLate = "LATE".equalsIgnoreCase(request.type());
        boolean justified = Boolean.TRUE.equals(request.justified());
        String title = (isLate ? "Retard" : "Absence") + " " + (justified ? "justifié" : "injustifié")
                + (request.relatedDate() != null ? " — " + request.relatedDate() : "");
        String description = justified
                ? "Motif : " + (request.reason() != null && !request.reason().isBlank() ? request.reason() : "(non précisé)")
                : "Aucune justification fournie. Merci de signer pour attester en avoir été informé.";

        AssignedTask task = assignedTaskRepository.save(AssignedTask.builder()
                .title(title)
                .description(description)
                .assignedTo(agent)
                .createdByUser(teamLeader)
                .priority(justified ? "NORMAL" : "HIGH")
                .status("OPEN")
                .category(isLate ? "ATTENDANCE_LATE" : "ATTENDANCE_ABSENCE")
                .justified(request.justified())
                .relatedDate(request.relatedDate())
                .build());

        notifyRecipients(task, teamLeader);
        return toResponse(task);
    }

    /**
     * Entretien qualité — le Team Leader programme un échange avec un agent (bonne ou
     * mauvaise note QA) pour discuter des axes d'amélioration. L'agent signe (DONE) pour
     * attester avoir été entretenu ; visible chez RH et le Superviseur.
     */
    @Transactional
    public AssignedTaskResponse createQaCoaching(String teamLeaderUsername, com.ecobank.rccportal.dto.QaCoachingRequest request) {
        User teamLeader = findUser(teamLeaderUsername);
        User agent = userRepository.findFirstByUsernameIgnoreCase(request.agentUsername())
                .orElseThrow(() -> ApiException.notFound("Agent inconnu : " + request.agentUsername()));

        String title = "Entretien qualité" + (request.relatedDate() != null ? " — " + request.relatedDate() : "");

        AssignedTask task = assignedTaskRepository.save(AssignedTask.builder()
                .title(title)
                .description(request.notes())
                .assignedTo(agent)
                .createdByUser(teamLeader)
                .priority("NORMAL")
                .status("OPEN")
                .category("QA_COACHING")
                .relatedDate(request.relatedDate())
                .build());

        notifyRecipients(task, teamLeader);
        return toResponse(task);
    }

    /**
     * Vue de contrôle RH/Superviseur — TOUTES les tâches de suivi présence/coaching QA,
     * toutes équipes confondues (pas juste "mes tâches"). Filtrable par catégorie.
     */
    @Transactional(readOnly = true)
    public List<AssignedTaskResponse> oversightTasks(String category) {
        List<AssignedTask> all = assignedTaskRepository.findByCategoryIsNotNullOrderByCreatedAtDesc();
        return all.stream()
                .filter(t -> category == null || category.isBlank() || category.equalsIgnoreCase(t.getCategory()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AssignedTaskResponse create(String creatorUsername, AssignedTaskRequest request, boolean canAssignOthers) {
        if (request.title() == null || request.title().isBlank()) {
            throw ApiException.badRequest("Le titre est requis.");
        }
        User creator = findUser(creatorUsername);

        boolean targetsOthers = (request.assignedToUsername() != null && !request.assignedToUsername().isBlank())
                || (request.assignedToTeamCode() != null && !request.assignedToTeamCode().isBlank());
        if (targetsOthers && !canAssignOthers) {
            throw ApiException.forbidden("Seuls QA, un Team Leader ou un administrateur peuvent assigner une tâche à quelqu'un d'autre.");
        }

        User assignedTo = null;
        if (request.assignedToUsername() != null && !request.assignedToUsername().isBlank()) {
            assignedTo = userRepository.findFirstByUsernameIgnoreCase(request.assignedToUsername())
                    .orElseThrow(() -> ApiException.notFound("Agent inconnu : " + request.assignedToUsername()));
        }

        AssignedTask task = assignedTaskRepository.save(AssignedTask.builder()
                .title(request.title())
                .description(request.description())
                .assignedTo(assignedTo)
                .assignedToTeamCode(assignedTo == null ? request.assignedToTeamCode() : null)
                .createdByUser(creator)
                .dueDate(request.dueDate())
                .priority(normalizePriority(request.priority()))
                .status("OPEN")
                .build());

        if (targetsOthers) {
            notifyRecipients(task, creator);
        }

        return toResponse(task);
    }

    /** Notification en app + e-mail (best-effort) aux destinataires — jamais bloquant si le SMTP n'est pas configuré. */
    private void notifyRecipients(AssignedTask task, User creator) {
        List<User> recipients;
        if (task.getAssignedTo() != null) {
            recipients = List.of(task.getAssignedTo());
        } else {
            recipients = userRepository.findAll().stream()
                    .filter(u -> task.getAssignedToTeamCode().equalsIgnoreCase(u.getActivity()))
                    .toList();
        }

        String creatorLabel = creator.getName() != null ? creator.getName() : creator.getUsername();
        String content = "Nouvelle tâche assignée par " + creatorLabel + " : " + task.getTitle() +
                (task.getDueDate() != null ? " (échéance " + task.getDueDate() + ")" : "");

        for (User recipient : recipients) {
            rccNotificationRepository.save(RccNotification.builder()
                    .targetUser(recipient)
                    .content(content)
                    .isRead(false)
                    .build());
        }

        try {
            List<String> emails = recipients.stream().map(User::getEmail).filter(Objects::nonNull).filter(e -> !e.isBlank()).toList();
            if (!emails.isEmpty()) {
                notificationService.sendBroadcastEmail(emails, "RCC Portal — Nouvelle tâche assignée",
                        content + (task.getDescription() != null ? "\n\n" + task.getDescription() : ""));
            }
        } catch (ApiException e) {
            log.warn("Tâche assignée : e-mail non envoyé (SMTP non configuré), notification en app conservée : {}", e.getMessage());
        }
    }

    @Transactional
    public AssignedTaskResponse markDone(Integer taskId, String username) {
        AssignedTask task = assignedTaskRepository.findById(taskId).orElseThrow(() -> ApiException.notFound("Tâche inconnue."));
        assertCanManage(task, username);
        if (task.getRelatedMeetingId() != null) {
            throw ApiException.badRequest("Tâche de meeting : utilisez « Remplir le compte rendu » ou « Lire et approuver ».");
        }
        task.setStatus("DONE".equals(task.getStatus()) ? "OPEN" : "DONE");
        return toResponse(assignedTaskRepository.save(task));
    }

    @Transactional
    public void delete(Integer taskId, String username) {
        AssignedTask task = assignedTaskRepository.findById(taskId).orElseThrow(() -> ApiException.notFound("Tâche inconnue."));
        assertCanManage(task, username);
        if (task.getRelatedMeetingId() != null && "OPEN".equals(task.getStatus())) {
            throw ApiException.badRequest("Tâche de meeting en cours : elle se clôture avec le compte rendu (ou l'annulation du meeting).");
        }
        assignedTaskRepository.delete(task);
    }

    private void assertCanManage(AssignedTask task, String username) {
        boolean isCreator = task.getCreatedByUser() != null && username.equalsIgnoreCase(task.getCreatedByUser().getUsername());
        boolean isAssignee = task.getAssignedTo() != null && username.equalsIgnoreCase(task.getAssignedTo().getUsername());
        if (!isCreator && !isAssignee) {
            throw ApiException.forbidden("Vous ne pouvez gérer que vos propres tâches.");
        }
    }

    private String normalizePriority(String raw) {
        if (raw == null) return "NORMAL";
        String upper = raw.trim().toUpperCase();
        return (upper.equals("LOW") || upper.equals("HIGH")) ? upper : "NORMAL";
    }

    private User findUser(String username) {
        return userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
    }

    private AssignedTaskResponse toResponse(AssignedTask t) {
        User assignedTo = t.getAssignedTo();
        User createdBy = t.getCreatedByUser();
        return new AssignedTaskResponse(
                t.getTaskId(), t.getTitle(), t.getDescription(),
                assignedTo != null ? assignedTo.getUsername() : null,
                assignedTo != null ? assignedTo.getName() : null,
                t.getAssignedToTeamCode(),
                createdBy != null ? createdBy.getUsername() : null,
                createdBy != null ? createdBy.getName() : null,
                t.getDueDate(), t.getPriority() == null ? "NORMAL" : t.getPriority(), t.getStatus(), t.getCreatedAt(),
                t.getCategory(), t.getJustified(), t.getRelatedDate(), t.getRelatedMeetingId());
    }
}
