package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.MicrosoftGraphClient;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Intégration réelle Teams/Outlook via Microsoft Graph — voir docs/GRAPH_SETUP.md pour la
 * procédure d'inscription Azure AD nécessaire côté client avant que ces endpoints fonctionnent.
 * Les e-mails source/destination sont toujours résolus côté serveur depuis la base (jamais
 * fait confiance à ce que le frontend prétend être l'adresse d'un agent).
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final MicrosoftGraphClient graphClient;
    private final UserRepository userRepository;

    public GraphController(MicrosoftGraphClient graphClient, UserRepository userRepository) {
        this.graphClient = graphClient;
        this.userRepository = userRepository;
    }

    /** true si graph.tenant-id/client-id/client-secret sont renseignés — le frontend s'en sert pour savoir s'il doit proposer l'envoi réel ou le lien classique. */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("configured", graphClient.isConfigured());
    }

    @PostMapping("/send-outlook")
    public Map<String, String> sendOutlook(@RequestBody Map<String, String> body,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        User from = findUser(requester.username());
        User to = findUser(body.get("toUsername"));
        requireEmail(from, "Votre compte n'a pas d'adresse e-mail renseignée.");
        requireEmail(to, "Le destinataire n'a pas d'adresse e-mail renseignée.");

        String subject = body.getOrDefault("subject", "Message depuis RCC Portal — " + from.getName());
        String message = body.getOrDefault("message", "");
        graphClient.sendMail(from.getEmail(), to.getEmail(), subject, message);
        return Map.of("status", "sent");
    }

    @PostMapping("/send-teams")
    public Map<String, String> sendTeams(@RequestBody Map<String, String> body,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        User from = findUser(requester.username());
        User to = findUser(body.get("toUsername"));
        requireEmail(from, "Votre compte n'a pas d'adresse e-mail renseignée.");
        requireEmail(to, "Le destinataire n'a pas d'adresse e-mail renseignée.");

        String message = body.getOrDefault("message", "");
        graphClient.sendTeamsMessage(from.getEmail(), to.getEmail(), message);
        return Map.of("status", "sent");
    }

    private User findUser(String username) {
        if (username == null || username.isBlank()) throw ApiException.badRequest("Utilisateur manquant.");
        return userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Utilisateur inconnu : " + username));
    }

    private void requireEmail(User user, String message) {
        if (user.getEmail() == null || user.getEmail().isBlank()) throw ApiException.badRequest(message);
    }
}
