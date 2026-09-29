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
    private final com.ecobank.rccportal.service.MonRccService monRccService;

    public AuditController(AuditService auditService,
                           com.ecobank.rccportal.service.NotificationService notificationService,
                           com.ecobank.rccportal.repository.UserRepository userRepository,
                           com.ecobank.rccportal.service.MonRccService monRccService) {
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.monRccService = monRccService;
    }

    /** Aperçu de la cible d'une diffusion : nombre de destinataires actifs, avec e-mail, par filiale. */
    @GetMapping("/audience")
    public java.util.Map<String, Object> audience(@AuthenticationPrincipal AuthenticatedUser requester,
                                                  @RequestParam(required = false) String countryCode,
                                                  @RequestParam(required = false) String serviceCode,
                                                  @RequestParam(required = false) String activity) {
        requireAdmin(requester);
        var users = monRccService.broadcastAudience(countryCode, serviceCode, activity);
        java.util.Map<String, Integer> byCountry = new java.util.TreeMap<>();
        int withEmail = 0;
        for (var u : users) {
            byCountry.merge(com.ecobank.rccportal.service.HrOrganizationService.countryOf(u.getAffiliateBranch()), 1, Integer::sum);
            if (u.getEmail() != null && !u.getEmail().isBlank()) withEmail++;
        }
        return java.util.Map.of("total", users.size(), "withEmail", withEmail, "byCountry", byCountry);
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

        List<String> recipients = monRccService.broadcastAudience(request.countryCode(), request.serviceCode(), request.activity()).stream()
                .map(com.ecobank.rccportal.model.User::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .distinct()
                .toList();

        int sent = notificationService.sendBroadcastEmail(recipients, request.subject(), request.body());
        return java.util.Map.of("recipientCount", recipients.size(), "sentCount", sent);
    }

    /** DTO local — usage unique, ne mérite pas son propre fichier. */
    public record BroadcastEmailRequest(String subject, String body, String countryCode, String serviceCode, String activity) {
    }

    private void requireAdmin(AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator can view the login audit log.");
        }
    }
}
