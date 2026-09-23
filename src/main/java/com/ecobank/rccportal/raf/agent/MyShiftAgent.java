package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.AgentScheduleResponse;
import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.RafText;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.service.PlanningComplianceService;
import com.ecobank.rccportal.service.ScheduleService;
import com.ecobank.rccportal.service.ShiftService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Agent « Mon shift » — le planning et la situation de L'AGENT CONNECTÉ uniquement (jamais
 * ceux d'un collègue, même nommé dans la question) : shift du jour ou d'une date, retard
 * éventuel par rapport à SON planning, statut en cours (en pause depuis…), déconnexions.
 */
@Component
public class MyShiftAgent implements RafAgent {

    private final ScheduleService scheduleService;
    private final ShiftService shiftService;
    private final PlanningComplianceService complianceService;
    private final UserRepository userRepository;

    public MyShiftAgent(ScheduleService scheduleService, ShiftService shiftService,
                        PlanningComplianceService complianceService, UserRepository userRepository) {
        this.scheduleService = scheduleService;
        this.shiftService = shiftService;
        this.complianceService = complianceService;
        this.userRepository = userRepository;
    }

    public String id() { return "myshift"; }

    public String label() { return "Mon planning & shift"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.MY_SHIFT); }

    private static final java.util.Map<String, String> STATES = java.util.Map.of(
            "WORKING", "en poste", "ON_PAUSE", "en pause", "ON_LUNCH", "en pause déjeuner", "ON_TRAINING", "en formation",
            "ON_MEETING", "en réunion", "DISCONNECTED", "déconnecté", "SHIFT_ENDED", "shift terminé", "NOT_STARTED", "shift non démarré");

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        if (request.username() == null) {
            return new AgentAnswer(id(), RafIntent.MY_SHIFT, 0.5, true, RafText.get(request.lang(), "login.required"),
                    null, List.of(), List.of(), null, null, List.of("utilisateur non identifié"));
        }
        LocalDate today = request.now().toLocalDate();
        LocalDate date = request.entities().date() != null ? request.entities().date() : today;
        try {
            StringBuilder md = new StringBuilder();
            List<AgentScheduleResponse> planning = scheduleService.planningForUser(request.username(), date, date);
            String dayLabel = date.equals(today) ? "Aujourd'hui" : date.equals(today.plusDays(1)) ? "Demain"
                    : date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE dd/MM", java.util.Locale.FRENCH));
            if (planning.isEmpty()) {
                md.append("**🗓 ").append(dayLabel).append("** : aucun planning validé pour toi.");
            } else {
                AgentScheduleResponse p = planning.get(0);
                md.append("**🗓 ").append(dayLabel).append("** : ");
                if (p.startTime() != null) {
                    md.append("shift ").append(p.shiftCode() != null ? p.shiftCode() + " " : "")
                            .append(p.startTime().toString(), 0, 5).append(p.endTime() != null ? "–" + p.endTime().toString().substring(0, 5) : "");
                } else {
                    md.append(p.shiftLabel() != null ? p.shiftLabel() : p.shiftCode() != null ? p.shiftCode() : "pas de prise de poste");
                }
            }
            if (date.equals(today)) {
                var user = userRepository.findFirstByUsernameIgnoreCase(request.username()).orElse(null);
                if (user != null) {
                    complianceService.forUser(user, today).ifPresent(c -> {
                        if ("LATE".equals(c.status()) || "ABSENT".equals(c.status()) || c.earlyLeaveMinutes() != null) {
                            md.append("\n⚠ ").append(c.detail());
                        } else if ("ON_TIME".equals(c.status())) {
                            md.append("\n✅ ").append(c.detail());
                        }
                    });
                }
                var status = shiftService.getStatus(request.username());
                String state = STATES.getOrDefault(status.currentState(), status.currentState());
                md.append("\nStatut actuel : **").append(state).append("**");
                if (status.currentStateSince() != null) {
                    long min = Math.max(0, Duration.between(status.currentStateSince(), request.now()).toMinutes());
                    md.append(" depuis ").append(min < 60 ? min + " min" : (min / 60) + "h" + String.format("%02d", min % 60));
                }
                if (status.absenceMinutesToday() > 0) {
                    md.append("\nDéconnexions aujourd'hui : ").append(status.absenceMinutesToday()).append(" min.");
                }
            }
            return new AgentAnswer(id(), RafIntent.MY_SHIFT, 0.9, true, md.toString(), null,
                    List.of(new RalphResultItem("SCHEDULE", null, "Mon planning", dayLabel)), List.of(), null, null,
                    List.of("planning et pointage de " + request.username() + " uniquement"));
        } catch (Exception e) {
            return AgentAnswer.notFound(id(), RafIntent.MY_SHIFT, "planning indisponible : " + e.getMessage());
        }
    }
}
