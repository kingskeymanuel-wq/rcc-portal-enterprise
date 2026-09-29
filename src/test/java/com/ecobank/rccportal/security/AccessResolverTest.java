package com.ecobank.rccportal.security;

import com.ecobank.rccportal.model.Role;
import com.ecobank.rccportal.model.RccService;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ce que l'admin choisit s'applique vraiment : le rôle le plus élevé l'emporte, relu en base à chaque requête. */
class AccessResolverTest {

    @Test
    void anAgentGivenTheTeamLeaderServiceReallyBecomesTeamLeader() {
        assertEquals("TEAM_LEADER", AccessResolver.primaryRole(List.of("agent"), List.of("TEAM_LEADER_TCHAT"), null));
        assertEquals("TEAM_LEADER", AccessResolver.primaryRole(List.of("Team Leader Inbound Voice"), List.of(), null));   // libellé métier
        assertEquals("TEAM_LEADER", AccessResolver.primaryRole(List.of("agent"), List.of(), "RAFIKI"));                   // équipe dirigée choisie
        assertEquals("SUPERVISOR", AccessResolver.primaryRole(List.of("Head RCC (Superviseur)"), List.of(), null));
        assertEquals("AGENT", AccessResolver.primaryRole(List.of("Superviseur Qualité Assurance"), List.of("SUPERVISEUR_QA"), null));
        assertEquals("ADMIN", AccessResolver.primaryRole(List.of("agent", "admin"), List.of("TEAM_LEADER_TCHAT"), "TCHAT"));
        assertEquals("AGENT", AccessResolver.primaryRole(List.of("agent"), List.of(), null));                             // retrait : redevient agent
        assertEquals("SUPERVISEUR_QA", AccessResolver.primaryService(List.of("QUALITY_ASSURANCE", "SUPERVISEUR_QA")));
    }

    @Test
    void changesAreReadLiveAndCacheIsClearedOnAdminAction() {
        UserRepository users = mock(UserRepository.class);
        UserRoleRepository roles = mock(UserRoleRepository.class);
        UserServiceAssignmentRepository services = mock(UserServiceAssignmentRepository.class);
        User u = User.builder().id(3L).username("awa").accountEnabled(true).build();
        when(users.findFirstByUsernameIgnoreCase("awa")).thenReturn(Optional.of(u));
        List<UserRole> rs = new ArrayList<>(List.of(UserRole.builder().user(u).role(Role.builder().name("agent").build()).build()));
        List<UserServiceAssignment> ss = new ArrayList<>();
        when(roles.findRolesByUserId(3L)).thenReturn(rs);
        when(services.findServicesByUserId(3L)).thenAnswer(i -> ss);
        AccessResolver r = new AccessResolver(users, roles, services);
        assertEquals("AGENT", r.resolve("awa").role());

        RccService tl = new RccService();
        tl.setCode("TEAM_LEADER_INBOUND_MAIL");
        ss.add(UserServiceAssignment.builder().user(u).service(tl).build());
        assertEquals("AGENT", r.resolve("awa").role());          // cache de quelques secondes…
        r.evictAll();                                             // … vidé par l'action de l'admin
        assertEquals("TEAM_LEADER", r.resolve("awa").role());

        u.setAccountEnabled(false);
        r.evictAll();
        assertFalse(r.resolve("awa").enabled());                  // désactivé : plus d'accès
    }
}
