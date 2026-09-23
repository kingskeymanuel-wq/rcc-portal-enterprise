package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.TabPermissionRequest;
import com.ecobank.rccportal.model.Role;
import com.ecobank.rccportal.model.Team;
import com.ecobank.rccportal.model.TabPermission;
import com.ecobank.rccportal.repository.RoleRepository;
import com.ecobank.rccportal.repository.TabPermissionRepository;
import com.ecobank.rccportal.repository.TeamRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TabPermissionService;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TabPermissionServiceTest {

    @Mock
    private TabPermissionRepository tabPermissionRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private RoleRepository roleRepository;

    private TabPermissionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        service = new TabPermissionService(
                tabPermissionRepository,
                teamRepository,
                roleRepository
        );
    }

    @Test
    void resolveForUserReturnsOnlyDeniedTabCodes() {

        Team qa = Team.builder()
                .teamId(1)
                .code("Quality Assurance")
                .build();

        TabPermission denied = TabPermission.builder()
                .tabPermissionId(1)
                .team(qa)
                .tabCode("admin-panel")
                .isAllowed(false)
                .build();

        when(
                tabPermissionRepository.findDeniedRulesForTeamOrRole(
                        "Quality Assurance",
                        "agent"
                )
        ).thenReturn(List.of(denied));

        var result = service.resolveForUser(
                new AuthenticatedUser(
                        "kone.aissatou",
                        "agent",
                        "Quality Assurance",
                        "Test"
                )
        );

        assertEquals(
                List.of("admin-panel"),
                result.deniedTabCodes()
        );
    }

    @Test
    void resolveForUserRejectsNullUser() {

        assertThrows(
                ApiException.class,
                () -> service.resolveForUser(null)
        );
    }

    @Test
    void upsertRejectsBothTeamCodeAndRoleCodeTogether() {

        TabPermissionRequest request =
                new TabPermissionRequest(
                        "Quality Assurance",
                        "agent",
                        "clairaudio",
                        false
                );

        ApiException ex = assertThrows(
                ApiException.class,
                () -> service.upsert(request)
        );

        assertTrue(
                ex.getMessage().contains("exactly one")
        );
    }

    @Test
    void upsertRejectsNeitherTeamCodeNorRoleCode() {

        TabPermissionRequest request =
                new TabPermissionRequest(
                        null,
                        null,
                        "clairaudio",
                        false
                );

        ApiException ex = assertThrows(
                ApiException.class,
                () -> service.upsert(request)
        );

        assertTrue(
                ex.getMessage().contains("exactly one")
        );
    }

    @Test
    void upsertRejectsNullRequest() {

        assertThrows(
                ApiException.class,
                () -> service.upsert(null)
        );
    }

    @Test
    void upsertRejectsBlankTabCode() {

        TabPermissionRequest request =
                new TabPermissionRequest(
                        "Communication",
                        null,
                        "   ",
                        false
                );

        assertThrows(
                ApiException.class,
                () -> service.upsert(request)
        );
    }

    @Test
    void upsertRejectsAnUnknownTeamCode() {

        when(
                tabPermissionRepository.findExistingRule(
                        "Nope",
                        null,
                        "clairaudio"
                )
        ).thenReturn(Optional.empty());

        when(
                teamRepository.findByCode("Nope")
        ).thenReturn(Optional.empty());

        TabPermissionRequest request =
                new TabPermissionRequest(
                        "Nope",
                        null,
                        "clairaudio",
                        false
                );

        assertThrows(
                ApiException.class,
                () -> service.upsert(request)
        );
    }

    @Test
    void upsertSucceedsWithAValidTeamRestriction() {

        Team comm = Team.builder()
                .teamId(2)
                .code("Communication")
                .build();

        when(
                tabPermissionRepository.findExistingRule(
                        "Communication",
                        null,
                        "clairaudio"
                )
        ).thenReturn(Optional.empty());

        when(
                teamRepository.findByCode("Communication")
        ).thenReturn(Optional.of(comm));

        when(
                tabPermissionRepository.save(any(TabPermission.class))
        ).thenAnswer(invocation -> {

            TabPermission permission =
                    invocation.getArgument(0);

            permission.setTabPermissionId(99);

            return permission;
        });

        var response = service.upsert(
                new TabPermissionRequest(
                        "Communication",
                        null,
                        "clairaudio",
                        false
                )
        );

        assertEquals(
                "Communication",
                response.teamCode()
        );

        assertNull(response.roleCode());

        assertEquals(
                "clairaudio",
                response.tabCode()
        );

        assertFalse(response.isAllowed());
    }

    @Test
    void upsertReusesTheExistingRuleInsteadOfInsertingADuplicate() {

        Team comm = Team.builder()
                .teamId(2)
                .code("Communication")
                .build();

        TabPermission existing =
                TabPermission.builder()
                        .tabPermissionId(7)
                        .team(comm)
                        .tabCode("clairaudio")
                        .isAllowed(false)
                        .build();

        when(
                tabPermissionRepository.findExistingRule(
                        "Communication",
                        null,
                        "clairaudio"
                )
        ).thenReturn(Optional.of(existing));

        when(
                teamRepository.findByCode("Communication")
        ).thenReturn(Optional.of(comm));

        when(
                tabPermissionRepository.save(any(TabPermission.class))
        ).thenAnswer(invocation ->
                invocation.getArgument(0)
        );

        var response = service.upsert(
                new TabPermissionRequest(
                        "Communication",
                        null,
                        "clairaudio",
                        true
                )
        );

        assertEquals(
                7,
                response.id(),
                "doit mettre à jour la ligne existante, pas en créer une nouvelle"
        );

        assertTrue(response.isAllowed());

        verify(
                tabPermissionRepository
        ).save(existing);
    }

    @Test
    void upsertSucceedsWithAValidRoleRestriction() {

        Role agentRole = Role.builder()
                .id(1L)
                .name("agent")
                .description("Agent RCC")
                .build();

        when(
                tabPermissionRepository.findExistingRule(
                        null,
                        "agent",
                        "admin-panel"
                )
        ).thenReturn(Optional.empty());

        when(
                roleRepository.findByNameIgnoreCase("agent")
        ).thenReturn(Optional.of(agentRole));

        when(
                tabPermissionRepository.save(any(TabPermission.class))
        ).thenAnswer(invocation -> {

            TabPermission permission =
                    invocation.getArgument(0);

            permission.setTabPermissionId(100);

            return permission;
        });

        var response = service.upsert(
                new TabPermissionRequest(
                        null,
                        "agent",
                        "admin-panel",
                        false
                )
        );

        assertEquals(100, response.id());

        assertNull(response.teamCode());

        assertEquals(
                "agent",
                response.roleCode()
        );

        assertEquals(
                "admin-panel",
                response.tabCode()
        );

        assertFalse(response.isAllowed());

        verify(
                roleRepository
        ).findByNameIgnoreCase("agent");
    }

    @Test
    void upsertRejectsAnUnknownRole() {

        when(
                tabPermissionRepository.findExistingRule(
                        null,
                        "unknown-role",
                        "admin-panel"
                )
        ).thenReturn(Optional.empty());

        when(
                roleRepository.findByNameIgnoreCase(
                        "unknown-role"
                )
        ).thenReturn(Optional.empty());

        TabPermissionRequest request =
                new TabPermissionRequest(
                        null,
                        "unknown-role",
                        "admin-panel",
                        false
                );

        assertThrows(
                ApiException.class,
                () -> service.upsert(request)
        );
    }

    @Test
    void removeRejectsNullId() {

        assertThrows(
                ApiException.class,
                () -> service.remove(null)
        );
    }

    @Test
    void removeRejectsUnknownPermission() {

        when(
                tabPermissionRepository.existsById(999)
        ).thenReturn(false);

        assertThrows(
                ApiException.class,
                () -> service.remove(999)
        );

        verify(
                tabPermissionRepository,
                never()
        ).deleteById(anyInt());
    }

    @Test
    void removeDeletesExistingPermission() {

        when(
                tabPermissionRepository.existsById(10)
        ).thenReturn(true);

        service.remove(10);

        verify(
                tabPermissionRepository
        ).deleteById(10);
    }
}