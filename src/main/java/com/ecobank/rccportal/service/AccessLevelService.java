package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.RccService;
import com.ecobank.rccportal.model.Role;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.RccServiceRepository;
import com.ecobank.rccportal.repository.RoleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.security.AccessResolver;
import com.ecobank.rccportal.util.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Administration — changement de niveau d'accès en UN geste (organigramme et annuaire) : Team Leader → Agent,
 * Agent → Team Leader, Agent → QA, changement d'équipe…
 * <p>
 * Retirer un rôle puis ajouter un service laissait des restes qui gardaient l'ancien accès (équipe menée
 * LED_TEAM toujours renseignée, service Team Leader oublié, deux services d'équipe…) : AccessResolver prenant le
 * plus élevé, un « Team Leader repassé agent » restait Team Leader. Ici tout ce qui donne un portail (rôles et
 * services de portail, équipe menée) est remplacé d'un bloc par ce que demande le nouveau niveau ; les autres
 * rôles/services (Communication, Head Outbound…) sont conservés. AccessResolver est ensuite vidé : chaque portail
 * applique le nouvel accès à la requête suivante (menus, redirections, données visibles).
 */
@Slf4j
@Service
public class AccessLevelService {

    /** Niveaux proposés dans l'Administration (mêmes codes que l'organigramme). */
    public static final List<String> LEVELS = List.of("AGENT", "TEAM_LEADER", "QA", "FORMATEUR", "HEAD_QA", "SUPERVISEUR", "RH", "AGENCE", "ADMIN");

    /** Équipes opérationnelles (valeurs de LED_TEAM / TeamClassifier). */
    public static final List<String> TEAMS = List.of("INBOUND_VOICE", "INBOUND_MAIL", "TCHAT", "RAFIKI", "CIB", "OUTBOUND",
            "TELEVENTE", "DIGITALISATION");

    private static final Map<String, String> AGENT_SERVICE = Map.of(
            "INBOUND_VOICE", "AGENT_INBOUND", "INBOUND_MAIL", "AGENT_INBOUND_MAIL", "TCHAT", "AGENT_TCHAT",
            "RAFIKI", "AGENT_RAFIKI", "CIB", "AGENT_CIB", "OUTBOUND", "AGENT_OUTBOUND",
            "TELEVENTE", "AGENT_TELEVENTE", "DIGITALISATION", "AGENT_DIGITALISATION");

    /** Même libellé que AdministrationService.SERVICE_CODE_TO_ACTIVITY : TeamClassifier range la personne dans la bonne équipe. */
    private static final Map<String, String> TEAM_ACTIVITY = Map.of(
            "INBOUND_VOICE", "INBOUND VOICE", "INBOUND_MAIL", "INBOUND MAIL", "TCHAT", "INBOUND TCHAT",
            "RAFIKI", "INBOUND RAFIKI", "CIB", "CIB", "OUTBOUND", "OUTBOUND",
            "TELEVENTE", "OUTBOUND TELEVENTE", "DIGITALISATION", "OUTBOUND DIGITALISATION");

    private static final Map<String, String> TEAM_LEADER_ROLE = Map.of(
            "INBOUND_VOICE", "Team Leader Inbound Voice", "INBOUND_MAIL", "Team Leader Inbound Mail", "TCHAT", "Team Leader Tchat",
            "RAFIKI", "Team Leader Rafiki", "CIB", "Team Leader CIB", "OUTBOUND", "Team Leader Outbound",
            "TELEVENTE", "Team Leader Télévente", "DIGITALISATION", "Team Leader Digitalisation");

    /** Services qui donnent un portail ou un profil : remplacés à chaque changement de niveau. */
    static final Set<String> PORTAL_SERVICES = new HashSet<>(List.of(
            "RH", "SUPERVISEUR", "SUPERVISEUR_QA", "QUALITY_ASSURANCE", "FORMATEUR",
            "AGENCE", "AGENCE_CAISSIER", "AGENCE_GESTIONNAIRE"));

    static {
        AGENT_SERVICE.values().forEach(PORTAL_SERVICES::add);
        TEAMS.forEach(t -> PORTAL_SERVICES.add("TEAM_LEADER_" + t));
    }

    /**
     * Ce que demande un niveau : pour chaque rôle, les noms acceptés (le premier existant en base est pris),
     * les services à attribuer, l'équipe menée et l'équipe (ACTIVITY, null = inchangée).
     */
    public record Plan(List<List<String>> roles, List<String> services, String ledTeam, String activity) {}

    public record Result(Long userId, String level, String team, List<String> roles, List<String> services) {}

    private final UserRepository users;
    private final RoleRepository roles;
    private final RccServiceRepository services;
    private final UserRoleRepository userRoles;
    private final UserServiceAssignmentRepository userServices;
    private final AccessResolver accessResolver;
    private final AuditLogService auditLog;
    private final JdbcTemplate jdbc;

    public AccessLevelService(UserRepository users, RoleRepository roles, RccServiceRepository services,
                              UserRoleRepository userRoles, UserServiceAssignmentRepository userServices,
                              AccessResolver accessResolver, AuditLogService auditLog, JdbcTemplate jdbc) {
        this.users = users;
        this.roles = roles;
        this.services = services;
        this.userRoles = userRoles;
        this.userServices = userServices;
        this.accessResolver = accessResolver;
        this.auditLog = auditLog;
        this.jdbc = jdbc;
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Traduit un niveau (et une équipe pour Agent / Team Leader, un poste pour Agence) en rôles et services. */
    static Plan plan(String level, String team) {
        String l = up(level), t = up(team);
        switch (l) {
            case "AGENT", "TEAM_LEADER" -> {
                if (!TEAMS.contains(t)) throw ApiException.badRequest("Choisissez l'équipe (Inbound Voix, Inbound Mail, Tchat, Rafiki, CIB, Outbound, Télévente, Digitalisation).");
                if (l.equals("AGENT")) {
                    List<String> names = switch (t) {
                        case "INBOUND_VOICE" -> List.of("Agent Inbound", "agent");
                        case "OUTBOUND" -> List.of("Agent Outbound", "agent");
                        default -> List.of("agent");
                    };
                    return new Plan(List.of(names), List.of(AGENT_SERVICE.get(t)), null, TEAM_ACTIVITY.get(t));
                }
                return new Plan(List.of(List.of(TEAM_LEADER_ROLE.get(t), "team_leader")), List.of("TEAM_LEADER_" + t), t, TEAM_ACTIVITY.get(t));
            }
            case "QA" -> { return new Plan(List.of(List.of("Quality Assurance")), List.of("QUALITY_ASSURANCE"), null, null); }
            case "FORMATEUR" -> { return new Plan(List.of(List.of("Formateur")), List.of("FORMATEUR"), null, null); }
            case "HEAD_QA" -> { return new Plan(List.of(List.of("Superviseur Qualité Assurance")), List.of("SUPERVISEUR_QA", "QUALITY_ASSURANCE"), null, null); }
            case "SUPERVISEUR" -> { return new Plan(List.of(List.of("Head RCC (Superviseur)", "supervisor")), List.of("SUPERVISEUR"), null, null); }
            case "RH" -> { return new Plan(List.of(List.of("rh")), List.of("RH"), null, null); }
            case "AGENCE" -> {
                return new Plan(List.of(), List.of(t.equals("GESTIONNAIRE") ? "AGENCE_GESTIONNAIRE" : "AGENCE_CAISSIER"), null, null);
            }
            case "ADMIN" -> { return new Plan(List.of(List.of("admin")), List.of(), null, null); }
            default -> throw ApiException.badRequest("Niveau d'accès inconnu : " + level);
        }
    }

    /** Rôle qui donne un portail ou un profil (Agent, Team Leader…, RH, Superviseur, QA, Formateur, Admin, Agence). */
    static boolean isPortalRole(String name) {
        return AccessResolver.roleOfName(name) != null;
    }

    static boolean isPortalService(String code) {
        return PORTAL_SERVICES.contains(up(code));
    }

    @Transactional
    public Result change(Long userId, String level, String team, String requesterUsername) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur inconnu."));
        Plan plan = plan(level, team);
        String l = up(level);

        boolean wasAdmin = userRoles.findByUser_Id(userId).stream()
                .anyMatch(ur -> ur.getRole() != null && "ADMIN".equals(AccessResolver.roleOfName(ur.getRole().getName())));
        if (wasAdmin && !l.equals("ADMIN")) {
            if (requesterUsername != null && requesterUsername.equalsIgnoreCase(user.getUsername())) {
                throw ApiException.badRequest("Vous ne pouvez pas retirer vos propres droits d'administrateur.");
            }
            if (otherAdmins(userId) == 0) throw ApiException.badRequest("C'est le dernier administrateur : nommez-en un autre d'abord.");
        }

        // Résolution AVANT toute suppression : un rôle ou service manquant n'enlève rien à la personne.
        List<Role> newRoles = new ArrayList<>();
        for (List<String> candidates : plan.roles()) {
            Role r = candidates.stream().map(roles::findByNameIgnoreCase).flatMap(Optional::stream).findFirst().orElse(null);
            if (r == null && l.equals("ADMIN")) throw ApiException.badRequest("Rôle « admin » introuvable en base.");
            if (r != null) newRoles.add(r);
        }
        List<RccService> newServices = new ArrayList<>();
        for (String code : plan.services()) {
            newServices.add(services.findByCodeIgnoreCase(code)
                    .orElseThrow(() -> ApiException.badRequest("Service « " + code + " » introuvable : créez-le dans « Services du portail ».")));
        }

        List<UserRole> oldRoles = userRoles.findByUser_Id(userId).stream()
                .filter(ur -> ur.getRole() == null || isPortalRole(ur.getRole().getName())).toList();
        userRoles.deleteAll(oldRoles);
        List<UserServiceAssignment> oldServices = userServices.findByUserId(userId).stream()
                .filter(us -> us.getService() == null || isPortalService(us.getService().getCode())).toList();
        userServices.deleteAll(oldServices);
        userRoles.flush();
        userServices.flush();

        Set<Long> keptRoles = new HashSet<>();
        userRoles.findByUser_Id(userId).forEach(ur -> { if (ur.getRole() != null) keptRoles.add(ur.getRole().getId()); });
        for (Role r : newRoles) {
            if (keptRoles.add(r.getId())) userRoles.save(UserRole.builder().user(user).role(r).build());
        }
        Set<Long> keptServices = new HashSet<>();
        userServices.findByUserId(userId).forEach(us -> { if (us.getService() != null) keptServices.add(us.getService().getId()); });
        for (RccService s : newServices) {
            if (keptServices.add(s.getId())) userServices.save(UserServiceAssignment.builder().user(user).service(s).build());
        }

        user.setLedTeam(plan.ledTeam());
        if (plan.activity() != null) user.setActivity(plan.activity());
        users.save(user);
        accessResolver.evictAll();

        List<String> roleNames = newRoles.stream().map(Role::getName).toList();
        List<String> serviceCodes = newServices.stream().map(RccService::getCode).toList();
        String t = plan.ledTeam() != null ? plan.ledTeam() : (TEAMS.contains(up(team)) ? up(team) : null);
        log.info("Niveau d'accès changé (userId={}, niveau={}, équipe={}, par={})", userId, l, t, requesterUsername);
        auditLog.record(requesterUsername, "CHANGE_ACCESS_LEVEL", user.getUsername() + " → " + l + (t != null ? " (" + t + ")" : "")
                + " · retirés : " + oldRoles.stream().filter(ur -> ur.getRole() != null).map(ur -> ur.getRole().getName()).toList()
                + " " + oldServices.stream().filter(us -> us.getService() != null).map(us -> us.getService().getCode()).toList());
        return new Result(userId, l, t, roleNames, serviceCodes);
    }

    /** Autres comptes actifs ayant un rôle administrateur. */
    private int otherAdmins(Long userId) {
        Set<Long> ids = new HashSet<>();
        jdbc.query("SELECT ur.USERS_ID, r.NAME, u.ACCOUNT_ENABLED FROM dbo.USER_ROLES ur JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID "
                + "JOIN dbo.USERS u ON u.ID = ur.USERS_ID", rs -> {
            Object enabled = rs.getObject("ACCOUNT_ENABLED");
            boolean active = enabled == null || (enabled instanceof Boolean b ? b : ((Number) enabled).intValue() != 0);
            long id = rs.getLong("USERS_ID");
            if (active && id != userId && "ADMIN".equals(AccessResolver.roleOfName(rs.getString("NAME")))) ids.add(id);
        });
        return ids.size();
    }
}
