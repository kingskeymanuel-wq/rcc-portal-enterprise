package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CoachingPlanRequest;
import com.ecobank.rccportal.dto.CoachingPlanResponse;
import com.ecobank.rccportal.model.CoachingPlan;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CoachingPlanRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CoachingPlanService {

    private static final List<String> VALID_STATUSES = List.of("todo", "in_progress", "done");

    private final CoachingPlanRepository coachingPlanRepository;
    private final UserRepository userRepository;

    public CoachingPlanService(CoachingPlanRepository coachingPlanRepository, UserRepository userRepository) {
        this.coachingPlanRepository = coachingPlanRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<CoachingPlanResponse> listAll() {
        return coachingPlanRepository.findAllByOrderByDueDateAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<CoachingPlanResponse> listForAgent(String username) {
        User agent = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        return coachingPlanRepository.findByAgentOrderByDueDateAsc(agent).stream().map(this::toResponse).toList();
    }

    @Transactional
    public CoachingPlanResponse create(CoachingPlanRequest request) {
        if (request. agentMatricule() == null || request. agentMatricule().isBlank()) {
            throw ApiException.badRequest(" agentMatricule is required.");
        }
        if (request.axis() == null || request.axis().isBlank()) throw ApiException.badRequest("axis is required.");
        if (request.dueDate() == null) throw ApiException.badRequest("dueDate is required.");

        User agent = userRepository.findFirstByUsernameIgnoreCase(request. agentMatricule())
                .orElseThrow(() -> ApiException.badRequest("Unknown agent."));

        CoachingPlan plan = CoachingPlan.builder()
                .agent(agent).axis(request.axis().trim()).dueDate(request.dueDate())
                .status(request.status() != null ? validateStatus(request.status()) : "todo")
                .note(request.note())
                .build();
        return toResponse(coachingPlanRepository.save(plan));
    }

    @Transactional
    public CoachingPlanResponse update(Integer id, CoachingPlanRequest request) {
        CoachingPlan plan = coachingPlanRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Coaching plan not found."));

        if (request.axis() != null) plan.setAxis(request.axis().trim());
        if (request.dueDate() != null) plan.setDueDate(request.dueDate());
        if (request.status() != null) plan.setStatus(validateStatus(request.status()));
        if (request.note() != null) plan.setNote(request.note());

        return toResponse(coachingPlanRepository.save(plan));
    }

    @Transactional
    public void remove(Integer id) {
        if (!coachingPlanRepository.existsById(id)) throw ApiException.notFound("Coaching plan not found.");
        coachingPlanRepository.deleteById(id);
    }

    private String validateStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be one of: " + VALID_STATUSES);
        }
        return status;
    }

    private CoachingPlanResponse toResponse(CoachingPlan p) {
        return new CoachingPlanResponse(p.getCoachingPlanId(), p.getAgent().getUsername(), p.getAgent().getName(),
                p.getAxis(), p.getDueDate(), p.getStatus(), p.getNote(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
