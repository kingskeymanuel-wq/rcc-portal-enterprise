package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.LoginAuditResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AuditService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Journal de connexions — réservé aux administrateurs. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;
    private final com.ecobank.rccportal.service.NotificationService notificationService;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;

    public AuditController(AuditService auditService,
                           com.ecobank.rccportal.service.NotificationService notificationService,
                           com.ecobank.rccportal.repository.UserRepository userRepository) {
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @GetMapping("/logins")
    public List<LoginAuditResponse> logins(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) Integer limit) {
        requireAdmin(requester);
        return auditService.listRecent(limit);
    }

    /** Diffusion réelle par e-mail (SMTP) à tous les utilisateurs ayant une adresse renseignée. */
    @org.springframework.web.bind.annotation.PostMapping("/broadcast-email")
    public java.util.Map<String, Object> broadcastEmail(
            @org.springframework.web.bind.annotation.RequestBody BroadcastEmailRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        if (request.subject() == null || request.subject().isBlank() || request.body() == null || request.body().isBlank()) {
            throw ApiException.badRequest("Subject and body are required.");
        }

        List<String> recipients = userRepository.findAll().stream()
                .map(com.ecobank.rccportal.model.User::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .distinct()
                .toList();

        int sent = notificationService.sendBroadcastEmail(recipients, request.subject(), request.body());
        return java.util.Map.of("recipientCount", recipients.size(), "sentCount", sent);
    }

    /** DTO local — usage unique, ne mérite pas son propre fichier. */
    public record BroadcastEmailRequest(String subject, String body) {
    }

    private void requireAdmin(AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator can view the login audit log.");
        }
    }
}
