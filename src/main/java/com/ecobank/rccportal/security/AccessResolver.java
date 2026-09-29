package com.ecobank.rccportal.security;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accès RÉEL d'un utilisateur, relu en base : rôle de portail, service principal, compte actif.
 * <ul>
 *   <li>Une seule règle pour tout le portail (connexion, jeton, chaque requête) : le rôle le plus élevé l'emporte,
 *       qu'il vienne d'un rôle (« Team Leader Inbound Voice », « Head RCC (Superviseur) »…), d'un service
 *       (« Team Leader Tchat », « RH »…) ou de l'équipe menée. Un agent à qui l'admin donne le service Team Leader
 *       devient donc vraiment Team Leader, même s'il garde son rôle « agent ».</li>
 *   <li>Relu à chaque requête (cache de quelques secondes, vidé à chaque action de l'administrateur) : un
 *       changement fait dans Administration s'applique tout de suite, sans attendre une reconnexion, et un
 *       compte désactivé perd l'accès immédiatement.</li>
 * </ul>
 */
@Component
public class AccessResolver {

    public record Access(String role, String service, boolean enabled) {}

    /** Du plus élevé au plus bas. */
    static final List<String> PRIORITY = List.of("ADMIN", "RH", "EXCELLIAM", "SUPERVISOR", "TEAM_LEADER", "AGENCE", "AGENT");

    static final Map<String, String> SERVICE_TO_ROLE = new LinkedHashMap<>();

    static {
        SERVICE_TO_ROLE.put("RH", "RH");
        SERVICE_TO_ROLE.put("SUPERVISEUR", "SUPERVISOR");
        for (String t : List.of("INBOUND_VOICE", "INBOUND_MAIL", "OUTBOUND", "TCHAT", "RAFIKI", "CIB")) SERVICE_TO_ROLE.put("TEAM_LEADER_" + t, "TEAM_LEADER");
        for (String a : List.of("AGENT_INBOUND", "AGENT_OUTBOUND", "AGENT_TCHAT", "AGENT_RAFIKI", "AGENT_INBOUND_MAIL", "AGENT_CIB")) SERVICE_TO_ROLE.put(a, "AGENT");
        SERVICE_TO_ROLE.put("AGENCE_CAISSIER", "AGENCE");
        SERVICE_TO_ROLE.put("AGENCE_GESTIONNAIRE", "AGENCE");
        SERVICE_TO_ROLE.put("AGENCE", "AGENCE");
    }

    /** Service « principal » quand plusieurs sont attribués : la famille Quality Assurance d'abord. */
    static final List<String> SERVICE_PRIORITY = List.of("SUPERVISEUR_QA", "QUALITY_ASSURANCE", "FORMATEUR", "COMMUNICATION");

    private static final long TTL_MS = 10_000;

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final UserServiceAssignmentRepository userServices;
    private final Map<String, CachedAccess> cache = new ConcurrentHashMap<>();

    private record CachedAccess(Access access, long at) {}

    public AccessResolver(UserRepository users, UserRoleRepository userRoles, UserServiceAssignmentRepository userServices) {
        this.users = users;
        this.userRoles = userRoles;
        this.userServices = userServices;
    }

    /** Rôle de base correspondant au NOM d'un rôle (base ou libellé métier), null s'il n'en désigne aucun. */
    public static String roleOfName(String name) {
        if (name == null || name.isBlank()) return null;
        String n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        if (n.equals("ADMIN") || n.startsWith("ADMINISTRAT")) return "ADMIN";
        if (n.equals("RH") || n.contains("RESSOURCES HUMAINES")) return "RH";
        if (n.equals("EXCELLIAM")) return "EXCELLIAM";
        if (n.contains("TEAM LEADER")) return "TEAM_LEADER";
        if (n.contains("QUALIT")) return "AGENT"; // Quality Assurance / Superviseur QA : profil donné par le service
        if (n.equals("SUPERVISOR") || n.contains("SUPERVISEUR") || n.contains("HEAD RCC")) return "SUPERVISOR";
        if (n.startsWith("AGENCE")) return "AGENCE";
        if (n.equals("AGENT") || n.startsWith("AGENT ") || n.contains("CONSEILLER") || n.equals("FORMATEUR")) return "AGENT";
        return null;
    }

    /** Rôle de portail : le plus élevé parmi les rôles, les services et l'équipe menée. */
    public static String primaryRole(Collection<String> roleNames, Collection<String> serviceCodes, String ledTeam) {
        Set<String> found = new HashSet<>();
        if (roleNames != null) for (String r : roleNames) { String b = roleOfName(r); if (b != null) found.add(b); }
        if (serviceCodes != null) for (String s : serviceCodes) {
            if (s == null) continue;
            String b = SERVICE_TO_ROLE.get(s.trim().toUpperCase(Locale.ROOT));
            if (b != null) found.add(b);
        }
        if (ledTeam != null && !ledTeam.isBlank()) found.add("TEAM_LEADER");
        for (String p : PRIORITY) if (found.contains(p)) return p;
        // Aucun rôle reconnu : repli sur le premier nom de rôle (comportement historique).
        return roleNames == null ? null : roleNames.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    public static String primaryService(List<String> serviceCodes) {
        if (serviceCodes == null || serviceCodes.isEmpty()) return null;
        for (String p : SERVICE_PRIORITY) for (String s : serviceCodes) if (p.equalsIgnoreCase(s)) return p;
        return serviceCodes.get(0);
    }

    /** Accès calculé depuis la base pour un utilisateur donné. */
    public Access compute(User u) {
        List<String> roles = new ArrayList<>();
        for (UserRole ur : userRoles.findRolesByUserId(u.getId())) if (ur.getRole() != null) roles.add(ur.getRole().getName());
        List<String> services = new ArrayList<>();
        for (UserServiceAssignment a : userServices.findServicesByUserId(u.getId())) {
            if (a.getService() == null) continue;
            services.add(a.getService().getCode() != null && !a.getService().getCode().isBlank() ? a.getService().getCode() : a.getService().getName());
        }
        // Seule la désactivation par l'admin/le RH coupe l'accès ; un verrouillage temporaire (mots de passe erronés)
        // n'éjecte pas une session déjà ouverte.
        boolean enabled = !Boolean.FALSE.equals(u.getAccountEnabled());
        return new Access(primaryRole(roles, services, u.getLedTeam()), primaryService(services), enabled);
    }

    /** Accès actuel (cache court) — null si l'utilisateur n'existe pas en base. */
    public Access resolve(String username) {
        if (username == null) return null;
        String key = username.toLowerCase(Locale.ROOT);
        CachedAccess c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < TTL_MS) return c.access();
        Access a = users.findFirstByUsernameIgnoreCase(username).map(this::compute).orElse(null);
        if (a != null) cache.put(key, new CachedAccess(a, now));
        return a;
    }

    /** Appelé après chaque action de l'administrateur : tous les portails voient le changement tout de suite. */
    public void evictAll() {
        cache.clear();
    }
}
