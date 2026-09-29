package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AccessResolver;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * « Accès effectif » affiché dans la fiche utilisateur de l'Administration : ce que la personne obtient VRAIMENT
 * avec les rôles et services cochés (profil, équipe menée, portail d'arrivée), et ce qui manque pour que ce soit
 * complet (ex. un Team Leader sans équipe n'a pas de portail Team Leader utilisable).
 */
@Service
public class EffectiveAccessService {

    public record EffectiveAccess(String profile, String profileLabel, String service, String ledTeam, String ledTeamLabel,
                                  String team, String portal, boolean active, List<String> warnings) {}

    private static final Map<String, String> PROFILE_LABELS = Map.of("ADMIN", "Administrateur", "RH", "RH", "SUPERVISOR", "Superviseur",
            "TEAM_LEADER", "Team Leader", "AGENCE", "Agence", "AGENT", "Agent", "EXCELLIAM", "Excelliam (portail supprimé)");

    private final UserRepository users;
    private final AccessResolver access;
    private final UserService userService;

    public EffectiveAccessService(UserRepository users, AccessResolver access, UserService userService) {
        this.users = users;
        this.access = access;
        this.userService = userService;
    }

    public EffectiveAccess of(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur inconnu."));
        AccessResolver.Access a = access.compute(u);
        String role = a.role() == null ? "AGENT" : a.role().toUpperCase();
        String service = a.service() == null ? "" : a.service().toUpperCase();
        String profile = role;
        if ("AGENT".equals(role) && service.equals("SUPERVISEUR_QA")) profile = "QA_SUPERVISOR";
        else if ("AGENT".equals(role) && service.equals("QUALITY_ASSURANCE")) profile = "QA";
        else if ("AGENT".equals(role) && service.equals("FORMATEUR")) profile = "FORMATEUR";
        String label = switch (profile) {
            case "QA_SUPERVISOR" -> "Head QA";
            case "QA" -> "Quality Assurance";
            case "FORMATEUR" -> "Formateur";
            default -> PROFILE_LABELS.getOrDefault(profile, profile);
        };
        String portal = null;
        try {
            portal = userService.teamStatus(u.getUsername()).redirectTo();
        } catch (RuntimeException ignored) {
            // pas de redirection calculable
        }
        if (portal == null) portal = "/dashboard";

        List<String> warnings = new ArrayList<>();
        if (!a.enabled()) warnings.add("Compte désactivé : aucun accès au portail.");
        if ("TEAM_LEADER".equals(role) && (u.getLedTeam() == null || u.getLedTeam().isBlank())) {
            warnings.add("Team Leader sans équipe : ajoutez un service « Team Leader … » (Inbound Voix, Inbound Mail, Réseaux sociaux, Rafiki, Outbound, CIB) "
                    + "ou choisissez l'« Équipe dirigée » — sinon son portail reste vide.");
        }
        if ("AGENT".equals(profile) && TeamClassifier.classify(u.getActivity()) == TeamClassifier.Team.OTHER) {
            warnings.add("Agent sans équipe : attribuez un service « Agent … » ou renseignez son équipe (Activité) pour son planning, ses KPI et son Team Leader.");
        }
        if ("EXCELLIAM".equals(role)) warnings.add("Le portail Excelliam a été supprimé : ce compte ne peut plus se connecter.");
        String led = u.getLedTeam() == null ? null : u.getLedTeam().toUpperCase();
        return new EffectiveAccess(profile, label, a.service(), led, led == null ? null : PlanningExportService.label(led), u.getActivity(), portal,
                a.enabled(), warnings);
    }
}
