package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.LeaveBalanceResponse;
import com.ecobank.rccportal.model.LeaveBalance;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.WorkflowRequest;
import com.ecobank.rccportal.repository.LeaveBalanceRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Solde de congés annuel. AllocatedDays est saisi par RH/QA/Admin (LeaveBalance) ;
 * UsedDays est toujours recalculé à la volée à partir des WorkflowRequest de type
 * LEAVE déjà approuvées qui chevauchent l'année demandée — jamais stocké, pour
 * qu'un solde ne puisse jamais se désynchroniser des vraies demandes.
 */
@Service
public class LeaveBalanceService {

    private static final int DEFAULT_ALLOCATED_DAYS = 24;

    private final LeaveBalanceRepository leaveBalanceRepository;
    private final WorkflowRequestRepository workflowRequestRepository;
    private final UserRepository userRepository;

    public LeaveBalanceService(LeaveBalanceRepository leaveBalanceRepository,
                                WorkflowRequestRepository workflowRequestRepository,
                                UserRepository userRepository) {
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.workflowRequestRepository = workflowRequestRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public LeaveBalanceResponse getBalance(Long userId, int year) {
        try {
            User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable."));
            return toResponse(user, year);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            User user = userRepository.findById(userId).orElse(null);
            String name = user != null ? user.getName() : null;
            String username = user != null ? user.getUsername() : null;
            return new LeaveBalanceResponse(userId, name, username, null, year, DEFAULT_ALLOCATED_DAYS, 0, DEFAULT_ALLOCATED_DAYS);
        }
    }

    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> getAllBalances(int year) {
        try {
            return userRepository.findAll().stream()
                    .map(u -> toResponse(u, year))
                    .sorted((a, b) -> a.remainingDays().compareTo(b.remainingDays()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public LeaveBalanceResponse setAllocated(Long userId, int year, int allocatedDays, Long updatedByUserId) {
        if (allocatedDays < 0) throw ApiException.badRequest("Le nombre de jours alloués ne peut pas être négatif.");
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable."));
        LeaveBalance balance = leaveBalanceRepository.findByUserIdAndYear(userId, year)
                .orElseGet(() -> LeaveBalance.builder().userId(userId).year(year).build());
        balance.setAllocatedDays(allocatedDays);
        balance.setUpdatedByUserId(updatedByUserId);
        leaveBalanceRepository.save(balance);
        return toResponse(user, year);
    }

    private LeaveBalanceResponse toResponse(User user, int year) {
        int allocated = leaveBalanceRepository.findByUserIdAndYear(user.getId(), year)
                .map(LeaveBalance::getAllocatedDays)
                .orElse(DEFAULT_ALLOCATED_DAYS);

        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);

        List<WorkflowRequest> approvedLeaves = workflowRequestRepository.findByTypeAndStatus("LEAVE", "APPROVED");
        int used = approvedLeaves.stream()
                .filter(r -> r.getRequestedBy() != null && user.getId().equals(safeUserId(r)))
                .mapToInt(r -> overlappingDays(r.getPeriodFrom(), r.getPeriodTo(), yearStart, yearEnd))
                .sum();

        return new LeaveBalanceResponse(user.getId(), user.getName(), user.getUsername(), user.getActivity(),
                year, allocated, used, allocated - used);
    }

    private Long safeUserId(WorkflowRequest r) {
        try {
            return r.getRequestedBy().getId();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    /** Nombre de jours (inclusif) de [from, to] qui tombent dans [yearStart, yearEnd]. */
    private int overlappingDays(LocalDate from, LocalDate to, LocalDate yearStart, LocalDate yearEnd) {
        if (from == null || to == null) return 0;
        LocalDate effectiveFrom = from.isBefore(yearStart) ? yearStart : from;
        LocalDate effectiveTo = to.isAfter(yearEnd) ? yearEnd : to;
        if (effectiveFrom.isAfter(effectiveTo)) return 0;
        return (int) ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1;
    }
}
