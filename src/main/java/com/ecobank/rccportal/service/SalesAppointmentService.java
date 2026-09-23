package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.Appointment;
import com.ecobank.rccportal.model.SalesRecord;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AppointmentRepository;
import com.ecobank.rccportal.repository.SalesRecordRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ventes et rendez-vous des conseillers Outbound (télévendeurs). Un agent ne voit et ne
 * modifie que les siens ; son Team Leader (User.ledTeam == OUTBOUND) voit toute son équipe ;
 * QA/Admin/Superviseur voient tout, tous accès confondus.
 */
@Service
public class SalesAppointmentService {

    private final SalesRecordRepository salesRecordRepository;
    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;

    public SalesAppointmentService(SalesRecordRepository salesRecordRepository,
                                    AppointmentRepository appointmentRepository,
                                    UserRepository userRepository) {
        this.salesRecordRepository = salesRecordRepository;
        this.appointmentRepository = appointmentRepository;
        this.userRepository = userRepository;
    }

    // ═══════════════════════════════════════════════════════════════════
    // VENTES
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public SalesRecordResponse createSale(AuthenticatedUser requester, SalesRecordRequest request) {
        User agent = requireUser(requester);
        if (request.productName() == null || request.productName().isBlank()) {
            throw ApiException.badRequest("Le produit est obligatoire.");
        }
        SalesRecord sale = SalesRecord.builder()
                .agentUserId(agent.getId())
                .productName(request.productName().trim())
                .clientName(blankToNull(request.clientName()))
                .clientPhone(blankToNull(request.clientPhone()))
                .amount(request.amount())
                .saleDate(request.saleDate() != null ? request.saleDate() : LocalDate.now())
                .status(request.status() != null && !request.status().isBlank() ? request.status().toUpperCase() : "CONFIRMED")
                .notes(blankToNull(request.notes()))
                .build();
        sale = salesRecordRepository.save(sale);
        return toSalesResponse(sale, agent);
    }

    @Transactional(readOnly = true)
    public List<SalesRecordResponse> mySales(AuthenticatedUser requester) {
        User agent = requireUser(requester);
        return salesRecordRepository.findByAgentUserIdOrderBySaleDateDesc(agent.getId())
                .stream().map(s -> toSalesResponse(s, agent)).toList();
    }

    /** Ventes de l'équipe menée (Team Leader OUTBOUND) ou de tout le monde (QA/Admin/Superviseur). */
    @Transactional(readOnly = true)
    public List<SalesRecordResponse> teamSales(AuthenticatedUser requester, LocalDate from, LocalDate to) {
        List<User> teamAgents = resolveVisibleAgents(requester, TeamClassifier.Team.OUTBOUND);
        List<Long> ids = teamAgents.stream().map(u -> u.getId()).toList();
        if (ids.isEmpty()) return List.of();
        List<SalesRecord> sales = salesRecordRepository.findByAgentUserIdInAndSaleDateBetweenOrderBySaleDateDesc(ids, from, to);
        return sales.stream().map(s -> toSalesResponse(s, userById(teamAgents, s.getAgentUserId()))).toList();
    }

    @Transactional
    public void deleteSale(AuthenticatedUser requester, Integer saleId) {
        SalesRecord sale = salesRecordRepository.findById(saleId)
                .orElseThrow(() -> ApiException.notFound("Vente introuvable."));
        User agent = requireUser(requester);
        boolean isOwner = sale.getAgentUserId().equals(agent.getId());
        if (!isOwner && !canManageOutboundTeam(requester)) {
            throw ApiException.forbidden("Vous ne pouvez supprimer que vos propres ventes.");
        }
        salesRecordRepository.delete(sale);
    }

    // ═══════════════════════════════════════════════════════════════════
    // RENDEZ-VOUS
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public AppointmentResponse createAppointment(AuthenticatedUser requester, AppointmentRequest request) {
        User agent = requireUser(requester);
        if (request.clientName() == null || request.clientName().isBlank()) {
            throw ApiException.badRequest("Le nom du client est obligatoire.");
        }
        if (request.scheduledAt() == null) {
            throw ApiException.badRequest("La date/heure du rendez-vous est obligatoire.");
        }
        Appointment appointment = Appointment.builder()
                .agentUserId(agent.getId())
                .clientName(request.clientName().trim())
                .clientPhone(blankToNull(request.clientPhone()))
                .purpose(blankToNull(request.purpose()))
                .scheduledAt(request.scheduledAt())
                .status(request.status() != null && !request.status().isBlank() ? request.status().toUpperCase() : "PLANNED")
                .notes(blankToNull(request.notes()))
                .build();
        appointment = appointmentRepository.save(appointment);
        return toAppointmentResponse(appointment, agent);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> myAppointments(AuthenticatedUser requester) {
        User agent = requireUser(requester);
        return appointmentRepository.findByAgentUserIdOrderByScheduledAtDesc(agent.getId())
                .stream().map(a -> toAppointmentResponse(a, agent)).toList();
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> teamAppointments(AuthenticatedUser requester, LocalDateTime from, LocalDateTime to) {
        List<User> teamAgents = resolveVisibleAgents(requester, TeamClassifier.Team.OUTBOUND);
        List<Long> ids = teamAgents.stream().map(u -> u.getId()).toList();
        if (ids.isEmpty()) return List.of();
        List<Appointment> appointments = appointmentRepository.findByAgentUserIdInAndScheduledAtBetweenOrderByScheduledAtAsc(ids, from, to);
        return appointments.stream().map(a -> toAppointmentResponse(a, userById(teamAgents, a.getAgentUserId()))).toList();
    }

    @Transactional
    public AppointmentResponse updateAppointmentStatus(AuthenticatedUser requester, Integer appointmentId, String status) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> ApiException.notFound("Rendez-vous introuvable."));
        User agent = requireUser(requester);
        boolean isOwner = appointment.getAgentUserId().equals(agent.getId());
        if (!isOwner && !canManageOutboundTeam(requester)) {
            throw ApiException.forbidden("Vous ne pouvez modifier que vos propres rendez-vous.");
        }
        appointment.setStatus(status.toUpperCase());
        appointment = appointmentRepository.save(appointment);
        User owner = userRepository.findById(appointment.getAgentUserId()).orElse(null);
        return toAppointmentResponse(appointment, owner);
    }

    @Transactional
    public void deleteAppointment(AuthenticatedUser requester, Integer appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> ApiException.notFound("Rendez-vous introuvable."));
        User agent = requireUser(requester);
        boolean isOwner = appointment.getAgentUserId().equals(agent.getId());
        if (!isOwner && !canManageOutboundTeam(requester)) {
            throw ApiException.forbidden("Vous ne pouvez supprimer que vos propres rendez-vous.");
        }
        appointmentRepository.delete(appointment);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private User requireUser(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    /** true si l'appelant est QA/Admin/Superviseur, ou Team Leader de l'équipe OUTBOUND. */
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

    /** Agents visibles pour l'appelant sur l'équipe donnée — lui seul si simple agent, toute
     *  l'équipe si Team Leader de cette équipe ou QA/Admin/Superviseur. */
    private List<User> resolveVisibleAgents(AuthenticatedUser requester, TeamClassifier.Team team) {
        if (!canManageOutboundTeam(requester)) {
            throw ApiException.forbidden("Réservé au Team Leader de l'équipe ou à QA/Admin/Superviseur.");
        }
        return userRepository.findAll().stream()
                .filter(u -> TeamClassifier.classify(u.getActivity()) == team)
                .toList();
    }

    private User userById(List<User> pool, Long userId) {
        return pool.stream().filter(u -> u.getId().equals(userId)).findFirst()
                .orElseGet(() -> userRepository.findById(userId).orElse(null));
    }

    private SalesRecordResponse toSalesResponse(SalesRecord s, User agent) {
        String name = agent != null ? (agent.getName() != null ? agent.getName() : agent.getUsername()) : "—";
        return new SalesRecordResponse(s.getSaleId(), s.getAgentUserId(), name, s.getProductName(),
                s.getClientName(), s.getClientPhone(), s.getAmount(), s.getSaleDate(), s.getStatus(),
                s.getNotes(), s.getCreatedAt());
    }

    private AppointmentResponse toAppointmentResponse(Appointment a, User agent) {
        String name = agent != null ? (agent.getName() != null ? agent.getName() : agent.getUsername()) : "—";
        return new AppointmentResponse(a.getAppointmentId(), a.getAgentUserId(), name, a.getClientName(),
                a.getClientPhone(), a.getPurpose(), a.getScheduledAt(), a.getStatus(), a.getNotes(), a.getCreatedAt());
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
