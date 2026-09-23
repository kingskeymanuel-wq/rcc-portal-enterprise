package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PlanningComplianceResponse;
import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.ShiftEventRepository;
import com.ecobank.rccportal.repository.ShiftSwapRequestRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.ShiftTimeline;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retards et absences calculés à partir du PLANNING PROPRE À CHAQUE AGENT (AgentSchedule
 * validé du jour — heures de début/fin de son shift, permutations validées déjà appliquées),
 * et non d'un horaire commun à toute l'équipe.
 *
 * <p>Pour chaque agent planifié (ou connecté sans planning) un jour donné :</p>
 * <ul>
 *   <li>congé approuvé (WorkflowRequest LEAVE) ou code congé/maladie → LEAVE, jamais « absent » ;</li>
 *   <li>jour sans prise de poste (OFF, repos) → OFF ; code ABS → PLANNED_ABSENCE ;</li>
 *   <li>shift avec heure de début : première connexion au-delà de {@value #LATE_THRESHOLD_MINUTES} min
 *       → LATE (minutes de retard), sinon ON_TIME ; aucune connexion
 *       {@value #ABSENCE_GRACE_MINUTES} min après l'heure de début → ABSENT, avant → NOT_YET ;</li>
 *   <li>fin de shift pointée plus de {@value #EARLY_LEAVE_THRESHOLD_MINUTES} min avant l'heure de
 *       fin planifiée → départ anticipé ;</li>
 *   <li>connecté un jour sans planning validé → UNPLANNED (à régulariser par Excelliam / le TL).</li>
 * </ul>
 * Seuls les plannings APPROVED comptent : un planning en attente de validation ne peut pas
 * rendre un agent « en retard ».
 */
@Service
public class PlanningComplianceService {

    static final int LATE_THRESHOLD_MINUTES = 5;
    static final int ABSENCE_GRACE_MINUTES = 60;
    static final int EARLY_LEAVE_THRESHOLD_MINUTES = 15;

    /** Ordre d'affichage : ce qui demande une action du Team Leader d'abord. */
    private static final List<String> SEVERITY_ORDER = List.of(
            "ABSENT", "LATE", "UNPLANNED", "NOT_YET", "PLANNED_ABSENCE", "ON_TIME", "LEAVE", "OFF");

    private final AgentScheduleRepository agentScheduleRepository;
    private final ShiftEventRepository shiftEventRepository;
    private final WorkflowRequestRepository workflowRequestRepository;
    private final ShiftSwapRequestRepository shiftSwapRequestRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    private Clock clock = Clock.systemDefaultZone();

    public PlanningComplianceService(AgentScheduleRepository agentScheduleRepository,
                                     ShiftEventRepository shiftEventRepository,
                                     WorkflowRequestRepository workflowRequestRepository,
                                     ShiftSwapRequestRepository shiftSwapRequestRepository,
                                     UserRepository userRepository,
                                     UserRoleRepository userRoleRepository) {
        this.agentScheduleRepository = agentScheduleRepository;
        this.shiftEventRepository = shiftEventRepository;
        this.workflowRequestRepository = workflowRequestRepository;
        this.shiftSwapRequestRepository = shiftSwapRequestRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /** Tests uniquement — fige « maintenant ». */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Accès (mêmes règles que le planning d'équipe)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * QA/RH/Superviseur/Admin/Excelliam : toute équipe (team vide = toutes). Team Leader :
     * uniquement l'équipe qu'il dirige. Agent : uniquement lui-même.
     */
    @Transactional(readOnly = true)
    public List<PlanningComplianceResponse> forDateScoped(AuthenticatedUser requester, LocalDate date, String requestedTeam) {
        String role = requester.role() == null ? "" : requester.role().toLowerCase(Locale.ROOT);
        boolean broad = Set.of("admin", "rh", "excelliam", "supervisor").contains(role)
                || (requester.service() != null
                    && Set.of("quality assurance", "superviseur qa").contains(requester.service().toLowerCase(Locale.ROOT).replace('_', ' ')));
        if (broad) return forDate(date, requestedTeam);

        User self = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        if ("team_leader".equals(role)) {
            String team = self.getLedTeam() != null && !self.getLedTeam().isBlank() ? self.getLedTeam() : self.getActivity();
            if (team == null || team.isBlank()) return List.of();
            return forDate(date, team);
        }
        return forUser(self, date).map(List::of).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public java.util.Optional<PlanningComplianceResponse> forUser(User user, LocalDate date) {
        AgentSchedule schedule = agentScheduleRepository.findByUserAndWorkDate(user, date)
                .filter(s -> "APPROVED".equals(s.getApprovalStatus()))
                .orElse(null);
        List<ShiftEvent> events = shiftEventRepository.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(
                user, date.atStartOfDay(), windowEnd(date, schedule));
        Set<Long> onLeave = usersOnLeave(date);
        Set<Long> swapped = swappedUsers(date);
        return java.util.Optional.ofNullable(evaluate(user, date, schedule, events,
                onLeave.contains(user.getId()), swapped.contains(user.getId())));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Calcul
    // ══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<PlanningComplianceResponse> forDate(LocalDate date, String team) {
        Map<Long, AgentSchedule> scheduleByUser = new LinkedHashMap<>();
        for (AgentSchedule s : agentScheduleRepository.findByWorkDate(date)) {
            if (!"APPROVED".equals(s.getApprovalStatus())) continue;
            scheduleByUser.put(s.getUser().getId(), s);
        }

        // Fenêtre élargie au lendemain matin pour les shifts de nuit (21h-06h).
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atTime(12, 0);
        Map<Long, List<ShiftEvent>> eventsByUser = new HashMap<>();
        Map<Long, User> users = new LinkedHashMap<>();
        for (ShiftEvent e : shiftEventRepository.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(from, to)) {
            User u = e.getUser();
            AgentSchedule s = scheduleByUser.get(u.getId());
            if (e.getOccurredAt().isAfter(windowEnd(date, s)) || e.getOccurredAt().equals(windowEnd(date, s))) continue;
            eventsByUser.computeIfAbsent(u.getId(), k -> new ArrayList<>()).add(e);
            users.putIfAbsent(u.getId(), u);
        }
        scheduleByUser.values().forEach(s -> users.putIfAbsent(s.getUser().getId(), s.getUser()));

        Set<String> leaders = teamLeaderUsernames();
        Set<Long> onLeave = usersOnLeave(date);
        Set<Long> swapped = swappedUsers(date);

        List<PlanningComplianceResponse> out = new ArrayList<>();
        for (User u : users.values()) {
            if (team != null && !team.isBlank() && !team.equalsIgnoreCase(u.getActivity())) continue;
            // Un Team Leader n'est pas un agent suivi (même règle que le Suivi de shift).
            if (u.getUsername() != null && leaders.contains(u.getUsername().toLowerCase(Locale.ROOT))) continue;
            PlanningComplianceResponse r = evaluate(u, date, scheduleByUser.get(u.getId()),
                    eventsByUser.getOrDefault(u.getId(), List.of()), onLeave.contains(u.getId()), swapped.contains(u.getId()));
            if (r != null) out.add(r);
        }
        out.sort(Comparator.comparingInt((PlanningComplianceResponse r) -> SEVERITY_ORDER.indexOf(r.status()))
                .thenComparing(r -> r.lateMinutes() == null ? 0 : -r.lateMinutes())
                .thenComparing(r -> Objects.requireNonNullElse(r.fullName(), r.username()), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** null = rien à signaler (ni planning validé ni connexion ce jour-là). */
    PlanningComplianceResponse evaluate(User user, LocalDate date, AgentSchedule schedule, List<ShiftEvent> events,
                                        boolean onApprovedLeave, boolean swapped) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime firstLogin = events.stream().filter(e -> "LOGIN".equals(e.getEventType()))
                .map(ShiftEvent::getOccurredAt).min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime shiftEnd = events.stream().filter(e -> "SHIFT_END".equals(e.getEventType()))
                .map(ShiftEvent::getOccurredAt).max(Comparator.naturalOrder()).orElse(null);
        long disconnected = ShiftTimeline.of(events.stream()
                .map(e -> new ShiftTimeline.Event(e.getEventType(), e.getOccurredAt())).toList())
                .absenceMinutes(now);

        if (schedule == null && firstLogin == null && !onApprovedLeave) return null;

        String code = schedule != null ? schedule.getShiftCode() : null;
        String label = schedule != null ? schedule.getShiftLabel() : null;
        LocalTime start = schedule != null ? schedule.getPlannedStartTime() : null;
        LocalTime end = schedule != null ? schedule.getPlannedEndTime() : null;
        boolean overnight = schedule != null && schedule.isOvernightCrossesMidnight();

        String status;
        Integer late = null;
        Integer early = null;
        String detail;

        if (onApprovedLeave || isLeaveCode(code, label)) {
            status = "LEAVE";
            detail = "En congé" + (label != null ? " (" + label + ")" : "")
                    + (firstLogin != null ? " — connecté quand même à " + hhmm(firstLogin) : "");
        } else if (schedule == null) {
            status = "UNPLANNED";
            detail = "Connecté à " + hhmm(firstLogin) + " sans planning validé pour ce jour.";
        } else if (start == null) {
            if ("ABS".equalsIgnoreCase(code)) {
                status = "PLANNED_ABSENCE";
                detail = "Absence prévue au planning.";
            } else {
                status = "OFF";
                detail = "Repos planifié" + (code != null ? " (" + code + ")" : "")
                        + (firstLogin != null ? " — connecté quand même à " + hhmm(firstLogin) : "") + ".";
            }
        } else {
            LocalDateTime plannedStart = date.atTime(start);
            if (firstLogin == null) {
                boolean graceOver = now.isAfter(plannedStart.plusMinutes(ABSENCE_GRACE_MINUTES));
                status = graceOver ? "ABSENT" : "NOT_YET";
                detail = graceOver
                        ? "Aucune connexion — shift prévu à " + hhmm(plannedStart) + "."
                        : (now.isBefore(plannedStart) ? "Shift prévu à " + hhmm(plannedStart) + "."
                            : "Pas encore connecté — shift prévu à " + hhmm(plannedStart) + ".");
            } else {
                long minutes = Duration.between(plannedStart, firstLogin).toMinutes();
                if (minutes > LATE_THRESHOLD_MINUTES) {
                    status = "LATE";
                    late = (int) minutes;
                    detail = "Arrivé à " + hhmm(firstLogin) + " pour un shift à " + hhmm(plannedStart)
                            + " — " + formatMinutes(minutes) + " de retard.";
                } else {
                    status = "ON_TIME";
                    detail = "Arrivé à " + hhmm(firstLogin) + " (shift à " + hhmm(plannedStart) + ").";
                }
            }
            if (shiftEnd != null && end != null) {
                LocalDateTime plannedEnd = (overnight ? date.plusDays(1) : date).atTime(end);
                long before = Duration.between(shiftEnd, plannedEnd).toMinutes();
                if (before > EARLY_LEAVE_THRESHOLD_MINUTES) {
                    early = (int) before;
                    detail += " Fin de shift à " + hhmm(shiftEnd) + ", " + formatMinutes(before) + " avant l'heure prévue.";
                }
            }
        }
        if (disconnected > 0) detail += " Déconnexions pendant le shift : " + formatMinutes(disconnected) + ".";
        if (swapped) detail += " Shift issu d'une permutation validée.";

        return new PlanningComplianceResponse(user.getUsername(), user.getName(), user.getActivity(), date,
                code, label, start, end, overnight,
                firstLogin != null ? firstLogin.toLocalTime().withNano(0) : null,
                shiftEnd != null ? shiftEnd.toLocalTime().withNano(0) : null,
                status, late, early, disconnected, swapped, detail.trim());
    }

    // ══════════════════════════════════════════════════════════════════════

    private LocalDateTime windowEnd(LocalDate date, AgentSchedule schedule) {
        // Shift de nuit : la fin de shift pointée le lendemain matin compte pour ce jour-là.
        return schedule != null && schedule.isOvernightCrossesMidnight()
                ? date.plusDays(1).atTime(12, 0)
                : date.plusDays(1).atStartOfDay();
    }

    static boolean isLeaveCode(String code, String label) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String l = label == null ? "" : java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return c.equals("C") || c.equals("CP") || c.startsWith("CONG") || c.equals("RM")
                || l.contains("conge") || l.contains("maladie");
    }

    private Set<Long> usersOnLeave(LocalDate date) {
        Set<Long> ids = new HashSet<>();
        workflowRequestRepository.findByTypeAndStatus("LEAVE", "APPROVED").forEach(r -> {
            try {
                if (r.getRequestedBy() != null && r.getPeriodFrom() != null && r.getPeriodTo() != null
                        && !date.isBefore(r.getPeriodFrom()) && !date.isAfter(r.getPeriodTo())) {
                    ids.add(r.getRequestedBy().getId());
                }
            } catch (jakarta.persistence.EntityNotFoundException ignored) {
                // demandeur supprimé — ignoré
            }
        });
        return ids;
    }

    private Set<Long> swappedUsers(LocalDate date) {
        Set<Long> ids = new HashSet<>();
        shiftSwapRequestRepository.findByTeamLeaderStatusOrderByCreatedAtAsc("APPROVED").forEach(s -> {
            if (!s.isSwapApplied()) return;
            if (date.equals(s.getRequesterDate())) ids.add(s.getRequester().getId());
            if (date.equals(s.getTargetDate())) ids.add(s.getTargetUser().getId());
        });
        return ids;
    }

    /** Optionnel (injection par setter) : exempte aussi le personnel du portail QA du suivi de présence. */
    private com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUserServiceAssignmentRepository(com.ecobank.rccportal.repository.UserServiceAssignmentRepository repo) {
        this.userServiceAssignmentRepository = repo;
    }

    private Set<String> teamLeaderUsernames() {
        Set<String> out = new HashSet<>(ShiftService.qaStaffUsernames(userServiceAssignmentRepository));
        userRoleRepository.findByRoleNameIgnoreCase("TEAM_LEADER").forEach(ur -> {
            if (ur.getUser() != null && ur.getUser().getUsername() != null) {
                out.add(ur.getUser().getUsername().toLowerCase(Locale.ROOT));
            }
        });
        return out;
    }

    private static String hhmm(LocalDateTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String formatMinutes(long minutes) {
        return minutes < 60 ? minutes + " min" : (minutes / 60) + "h" + String.format("%02d", minutes % 60);
    }
}
