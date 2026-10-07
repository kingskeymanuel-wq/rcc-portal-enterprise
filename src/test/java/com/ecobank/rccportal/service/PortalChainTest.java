package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AccessResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Chaîne complète, sur une base en mémoire : Administration (menu Accès) → USERS / USER_ROLES / USER_SERVICES →
 * profil réel (AccessResolver) → portail d'atterrissage (UserService.teamStatus). Données de départ réalistes :
 * ancien agent Inbound Mail avec le service Agent Inbound, le rôle de base AGENT et une « équipe menée » restée.
 */
class PortalChainTest {

    final AtomicLong ids = new AtomicLong(1000);
    final Map<Long, User> users = new HashMap<>();
    final List<Role> roles = new ArrayList<>();
    final List<RccService> services = new ArrayList<>();
    final List<UserRole> userRoles = new ArrayList<>();
    final List<UserServiceAssignment> userServices = new ArrayList<>();

    UserRepository userRepo = mock(UserRepository.class);
    RoleRepository roleRepo = mock(RoleRepository.class);
    RccServiceRepository serviceRepo = mock(RccServiceRepository.class);
    UserRoleRepository userRoleRepo = mock(UserRoleRepository.class);
    UserServiceAssignmentRepository usRepo = mock(UserServiceAssignmentRepository.class);

    AdministrationService admin;
    UserService userService;
    AccessResolver resolver;

    @BeforeEach
    void setUp() {
        when(userRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(users.get((Long) i.getArgument(0))));
        when(userRepo.findFirstByUsernameIgnoreCase(anyString())).thenAnswer(i -> users.values().stream()
                .filter(u -> u.getUsername().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(userRepo.save(any(User.class))).thenAnswer(i -> { User u = i.getArgument(0); if (u.getId() == null) u.setId(ids.incrementAndGet()); users.put(u.getId(), u); return u; });
        when(userRepo.findAll()).thenAnswer(i -> new ArrayList<>(users.values()));

        when(roleRepo.findByNameIgnoreCase(anyString())).thenAnswer(i -> roles.stream().filter(r -> r.getName().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(roleRepo.save(any(Role.class))).thenAnswer(i -> { Role r = i.getArgument(0); if (r.getId() == null) r.setId(ids.incrementAndGet()); roles.add(r); return r; });
        when(roleRepo.findById(anyLong())).thenAnswer(i -> roles.stream().filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(roleRepo.findAll()).thenAnswer(i -> new ArrayList<>(roles));

        when(serviceRepo.findByCodeIgnoreCase(anyString())).thenAnswer(i -> services.stream().filter(s -> s.getCode().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(serviceRepo.findById(anyLong())).thenAnswer(i -> services.stream().filter(s -> s.getId().equals(i.getArgument(0))).findFirst());
        when(serviceRepo.findAll()).thenAnswer(i -> new ArrayList<>(services));

        when(userRoleRepo.findByUser_Id(anyLong())).thenAnswer(i -> userRoles.stream().filter(ur -> ur.getUser().getId().equals(i.getArgument(0))).toList());
        when(userRoleRepo.findRolesByUserId(anyLong())).thenAnswer(i -> userRoles.stream().filter(ur -> ur.getUser().getId().equals(i.getArgument(0))).toList());
        when(userRoleRepo.existsByUser_IdAndRole_Id(anyLong(), anyLong())).thenAnswer(i -> userRoles.stream()
                .anyMatch(ur -> ur.getUser().getId().equals(i.getArgument(0)) && ur.getRole().getId().equals(i.getArgument(1))));
        when(userRoleRepo.save(any(UserRole.class))).thenAnswer(i -> { UserRole ur = i.getArgument(0); if (ur.getId() == null) ur.setId(ids.incrementAndGet()); userRoles.add(ur); return ur; });
        doAnswer(i -> { userRoles.remove((UserRole) i.getArgument(0)); return null; }).when(userRoleRepo).delete(any(UserRole.class));

        when(usRepo.findByUserId(anyLong())).thenAnswer(i -> userServices.stream().filter(a -> a.getUser().getId().equals(i.getArgument(0))).toList());
        when(usRepo.findServicesByUserId(anyLong())).thenAnswer(i -> userServices.stream().filter(a -> a.getUser().getId().equals(i.getArgument(0))).toList());
        when(usRepo.existsByUserIdAndServiceId(anyLong(), anyLong())).thenAnswer(i -> userServices.stream()
                .anyMatch(a -> a.getUser().getId().equals(i.getArgument(0)) && a.getService().getId().equals(i.getArgument(1))));
        when(usRepo.save(any(UserServiceAssignment.class))).thenAnswer(i -> { UserServiceAssignment a = i.getArgument(0); if (a.getId() == null) a.setId(ids.incrementAndGet()); userServices.add(a); return a; });
        doAnswer(i -> { userServices.remove((UserServiceAssignment) i.getArgument(0)); return null; }).when(usRepo).delete(any(UserServiceAssignment.class));

        for (String[] s : new String[][]{{"AGENT_INBOUND", "Agent Inbound"}, {"AGENT_INBOUND_MAIL", "Agent Inbound Mail"}, {"AGENT_TCHAT", "Agent Réseaux sociaux"},
                {"AGENT_RAFIKI", "Agent Rafiki"}, {"AGENT_CIB", "Agent CIB"}, {"AGENT_OUTBOUND", "Agent Outbound"}, {"AGENT_TELEVENTE", "Agent Télévente"},
                {"AGENT_DIGITALISATION", "Agent Digitalisation"}, {"TEAM_LEADER_INBOUND_MAIL", "Team Leader Inbound Mail"}, {"TEAM_LEADER_TCHAT", "Team Leader Réseaux sociaux"},
                {"TEAM_LEADER_RAFIKI", "Team Leader Rafiki"}, {"QUALITY_ASSURANCE", "Quality Assurance"}, {"TEAM_LEADER_INBOUND_VOICE", "Team Leader Inbound Voice"}}) {
            services.add(RccService.builder().id(ids.incrementAndGet()).code(s[0]).name(s[1]).enabled(true).build());
        }
        for (String r : List.of("AGENT", "Agent Inbound Voice", "Team Leader Inbound Mail")) roles.add(Role.builder().id(ids.incrementAndGet()).name(r).build());

        admin = new AdministrationService(roleRepo, serviceRepo, userRepo, userRoleRepo, usRepo, null, null, null, null, null, null, null, null);
        userService = new UserService(userRepo, userRoleRepo, usRepo, roleRepo, serviceRepo, null, null, null);
        resolver = new AccessResolver(userRepo, userRoleRepo, usRepo);
    }

    /** Ancien agent : rôle AGENT, service Agent Inbound, activité INBOUND MAIL et équipe menée restée. */
    User legacyAgent(String username, String ledTeam) {
        User u = User.builder().id(ids.incrementAndGet()).username(username).name(username).activity("INBOUND MAIL").ledTeam(ledTeam).accountEnabled(true).build();
        users.put(u.getId(), u);
        userRoles.add(UserRole.builder().id(ids.incrementAndGet()).user(u).role(roles.get(0)).build());
        userServices.add(UserServiceAssignment.builder().id(ids.incrementAndGet()).user(u).service(services.get(0)).build());
        return u;
    }

    String portal(User u) { return userService.teamStatus(u.getUsername()).redirectTo(); }
    String profile(User u) { return resolver.compute(users.get(u.getId())).role(); }

    @Test
    void inboundMailPoleAgentsLandOnTheirPortal() {
        User tchat = legacyAgent("tchat.agent", "INBOUND_MAIL");
        admin.setAccess(tchat.getId(), "AGENT", "TCHAT");
        assertEquals("AGENT", profile(tchat));
        assertEquals("/portail-tchat", portal(tchat));

        User rafiki = legacyAgent("rafiki.agent", null);
        admin.setAccess(rafiki.getId(), "AGENT", "RAFIKI");
        assertEquals("/portail-rafiki", portal(rafiki));

        User mail = legacyAgent("mail.agent", "INBOUND_MAIL");
        admin.setAccess(mail.getId(), "AGENT", "INBOUND_MAIL");
        assertEquals("AGENT", profile(mail));
        assertNull(users.get(mail.getId()).getLedTeam());
        assertEquals("/portail-mail", portal(mail));

        // Ancien agent jamais resynchronisé (rôle AGENT + service Agent Inbound, activité INBOUND MAIL) : portail Mail.
        User legacy = legacyAgent("legacy.mail", null);
        assertEquals("/portail-mail", portal(legacy));
    }

    @Test
    void portalFollowsTheRealProfile() {
        // Team Leader par son seul rôle (sans service) : portail Team Leader, pas l'accueil agent.
        User byRole = legacyAgent("tl.rafiki", null);
        Role tlRafiki = Role.builder().id(ids.incrementAndGet()).name("Team Leader Rafiki").build();
        roles.add(tlRafiki);
        userRoles.add(UserRole.builder().id(ids.incrementAndGet()).user(byRole).role(tlRafiki).build());
        assertEquals("TEAM_LEADER", profile(byRole));
        assertEquals("/team-leader", portal(byRole));

        // Team Leader par la seule « équipe menée » : même portail que son profil.
        User byLed = legacyAgent("tl.led", "INBOUND_MAIL");
        assertEquals("TEAM_LEADER", profile(byLed));
        assertEquals("/team-leader", portal(byLed));
    }

    @Test
    void teamLeadersOfThePole() {
        User tl = legacyAgent("tl.mail", null);
        admin.setAccess(tl.getId(), "TEAM_LEADER", "INBOUND_MAIL");
        assertEquals("TEAM_LEADER", profile(tl));
        assertEquals("/team-leader", portal(tl));

        // Ancien Team Leader repassé agent Réseaux sociaux : plus aucun accès Team Leader.
        admin.setAccess(tl.getId(), "AGENT", "TCHAT");
        assertEquals("AGENT", profile(tl));
        assertEquals("/portail-tchat", portal(tl));
    }

    @Test
    void inboundMailLeadershipPatch() {
        User loum = legacyAgent("oloum", null);
        loum.setName("LOUM Olivia");
        User tlTest = legacyAgent("teamleader.inboundmail", "INBOUND_MAIL");
        User agentTest = legacyAgent("agent.mail", null);
        DataPatchService patch = new DataPatchService(null, null, admin, userRepo, null);
        String out = patch.inboundMailLeadership();
        assertTrue(out.contains("LOUM Olivia"), out);
        assertEquals("/team-leader", portal(loum));
        assertEquals("INBOUND_MAIL", users.get(loum.getId()).getLedTeam());
        assertEquals("/team-leader", portal(tlTest));
        assertEquals("/portail-mail", portal(agentTest));
    }

    @Test
    void togoTeamIsReorganisedWithoutDuplicates() {
        // Déjà en base, rattaché par erreur à la Côte d'Ivoire : réorganisé, pas dupliqué.
        User existing = legacyAgent("koamouzou", null);
        existing.setName("AMOUZOU Kossi Bernard");
        existing.setAffiliateBranch("K01");
        int before = users.size();
        DataPatchService patch = new DataPatchService(null, null, admin, userRepo, null);
        String out = patch.togoTeam();
        assertTrue(out.contains("1 compte(s) existant(s) réorganisé(s), 8 créé(s)"), out);
        assertEquals(before + 8, users.size());
        assertEquals("TG", users.get(existing.getId()).getAffiliateBranch());
        User tl = users.values().stream().filter(u -> "TFAHE".equals(u.getUsername())).findFirst().orElseThrow();
        assertEquals("INBOUND_VOICE", tl.getLedTeam());
        assertEquals("/team-leader", portal(tl));
        User chat = users.values().stream().filter(u -> "JLASSEY".equals(u.getUsername())).findFirst().orElseThrow();
        assertEquals("/portail-tchat", portal(chat));
        // Rejouer le correctif ne crée rien de plus.
        patch.togoTeam();
        assertEquals(before + 8, users.size());
    }

    @Test
    void filialeRules() {
        assertEquals("RCC ECI", com.ecobank.rccportal.util.Filiale.label("K01"));
        assertEquals("RCC ETG", com.ecobank.rccportal.util.Filiale.label("TG"));
        var rh = new com.ecobank.rccportal.security.AuthenticatedUser("rh", "RH", "RH", "RH");
        var agent = new com.ecobank.rccportal.security.AuthenticatedUser("a", "AGENT", "AGENT_INBOUND", "A");
        try {
            com.ecobank.rccportal.util.Filiale.set(agent, "TG");
            assertNull(com.ecobank.rccportal.util.Filiale.current(), "un agent ne bascule pas de filiale");
            com.ecobank.rccportal.util.Filiale.set(rh, "TG");
            assertEquals("TG", com.ecobank.rccportal.util.Filiale.current());
            assertTrue(com.ecobank.rccportal.util.Filiale.matches("TG"));
            assertFalse(com.ecobank.rccportal.util.Filiale.matches("K01"));
            assertTrue(com.ecobank.rccportal.util.Filiale.sql("u").contains("'TG'"));
            com.ecobank.rccportal.util.Filiale.set(rh, "CI");
            assertTrue(com.ecobank.rccportal.util.Filiale.matches(null), "sans filiale = Côte d'Ivoire (siège)");
            assertTrue(com.ecobank.rccportal.util.Filiale.matches("K01"));
        } finally {
            com.ecobank.rccportal.util.Filiale.clear();
        }
    }

    @Test
    void togoAndIvoryCoastUsersAreNeverMixed() {
        try {
            // Agent de Lomé : enfermé dans RCC ETG, ne voit aucun collaborateur de Côte d'Ivoire.
            com.ecobank.rccportal.util.Filiale.setOwn("TG");
            assertTrue(com.ecobank.rccportal.util.Filiale.visibleInDirectory("TG", "AGENT"));
            assertFalse(com.ecobank.rccportal.util.Filiale.visibleInDirectory("K01", "AGENT"));
            assertFalse(com.ecobank.rccportal.util.Filiale.visibleInDirectory(null, "TEAM_LEADER"));
            // L'encadrement commun reste joignable.
            assertTrue(com.ecobank.rccportal.util.Filiale.visibleInDirectory("K01", "SUPERVISOR"));
            // Et inversement côté RCC ECI.
            com.ecobank.rccportal.util.Filiale.setOwn("K01");
            assertFalse(com.ecobank.rccportal.util.Filiale.visibleInDirectory("TG", "AGENT"));
            assertTrue(com.ecobank.rccportal.util.Filiale.visibleInDirectory("CI", "AGENT"));
            // Team Leader d'un agent : toujours dans la filiale de l'agent.
            var leaders = java.util.List.of(new String[]{"tl.ci", "K01"}, new String[]{"tl.tg", "TG"});
            assertEquals(java.util.List.of("tl.tg"), com.ecobank.rccportal.util.Filiale.sameFiliale(leaders, l -> l[1], "TGO")
                    .stream().map(l -> l[0]).toList());
            assertEquals(java.util.List.of("tl.ci"), com.ecobank.rccportal.util.Filiale.sameFiliale(leaders, l -> l[1], null)
                    .stream().map(l -> l[0]).toList());
        } finally {
            com.ecobank.rccportal.util.Filiale.clear();
        }
    }
}
