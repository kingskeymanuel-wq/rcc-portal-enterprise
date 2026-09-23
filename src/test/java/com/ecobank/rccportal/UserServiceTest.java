package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.PendingAccountResponse;
import com.ecobank.rccportal.dto.UserResponse;
import com.ecobank.rccportal.model.Role;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.service.NotificationService;
import com.ecobank.rccportal.service.UserService;
import com.ecobank.rccportal.util.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;

    @Mock
    private com.ecobank.rccportal.repository.RoleRepository roleRepository;

    @Mock
    private com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;

    @Mock
    private com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository;

    private UserService service;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        service = new UserService(
                userRepository,
                userRoleRepository,
                userServiceAssignmentRepository,
                roleRepository,
                rccServiceRepository,
                userProfileRepository,
                notificationService
        );
    }

    @Test
    void listAllMapsAndSortsUsersByName() {

        Role agentRole = Role.builder()
                .id(1L)
                .name("agent")
                .description("Agent RCC")
                .build();

        User zed = User.builder()
                .id(1L)
                .username("zed.zorro")
                .name("Zed Zorro")
                .loginCount(3)
                .status("APPROVED")
                .failedAttempts(0)
                .build();

        User aya = User.builder()
                .id(2L)
                .username("kone.aissatou")
                .name("Aya Koné")
                .loginCount(5)
                .status("APPROVED")
                .failedAttempts(0)
                .build();

        UserRole zedRole = UserRole.builder()
                .id(1L)
                .user(zed)
                .role(agentRole)
                .build();

        UserRole ayaRole = UserRole.builder()
                .id(2L)
                .user(aya)
                .role(agentRole)
                .build();

        when(userRepository.findAll())
                .thenReturn(List.of(zed, aya));

        when(userRoleRepository.findRolesByUserId(1L))
                .thenReturn(List.of(zedRole));

        when(userRoleRepository.findRolesByUserId(2L))
                .thenReturn(List.of(ayaRole));

        List<UserResponse> result = service.listAll();

        assertEquals(2, result.size());

        assertEquals(
                "Aya Koné",
                result.get(0).name(),
                "doit être trié par nom"
        );

        assertEquals(
                "Zed Zorro",
                result.get(1).name()
        );

        assertEquals(
                "agent",
                result.get(0).role()
        );

        assertEquals(
                5,
                result.get(0).loginCount()
        );

        /*
         * USER_SERVICES n'est pas encore branché
         * dans UserService.
         *
         * Le champ correspondant à l'ancien team
         * reste temporairement null.
         */
        assertNull(
                result.get(0).team()
        );
    }

    @Test
    void listAllToleratesAUserWithoutRole() {

        User admin = User.builder()
                .id(10L)
                .username("edoudou")
                .name("Edoudou")
                .loginCount(0)
                .status("APPROVED")
                .failedAttempts(0)
                .build();

        when(userRepository.findAll())
                .thenReturn(List.of(admin));

        when(userRoleRepository.findRolesByUserId(10L))
                .thenReturn(List.of());

        List<UserResponse> result = service.listAll();

        assertEquals(
                1,
                result.size()
        );

        /*
         * UserResponse conserve pour le moment
         * le nom historique matricule.
         *
         * La valeur provient bien de USERS.USERNAME.
         */
        assertEquals(
                "edoudou",
                result.get(0).matricule()
        );

        assertEquals(
                "Edoudou",
                result.get(0).name()
        );

        assertNull(
                result.get(0).role()
        );

        assertNull(
                result.get(0).team()
        );
    }

    @Test
    void listPendingReturnsOnlyPendingAccounts() {

        OffsetDateTime createdAt =
                OffsetDateTime.now().minusHours(1);

        User pending = User.builder()
                .id(20L)
                .username("new.agent")
                .name("New Agent")
                .email("new.agent@ecobank.com")
                .status("PENDING")
                .failedAttempts(0)
                .loginCount(0)
                .createdAt(createdAt)
                .build();

        when(userRepository.findByStatus("PENDING"))
                .thenReturn(List.of(pending));

        List<PendingAccountResponse> result =
                service.listPending();

        assertEquals(
                1,
                result.size()
        );

        /*
         * PendingAccountResponse utilise encore
         * le nom historique matricule.
         *
         * La valeur vient de USERS.USERNAME.
         */
        assertEquals(
                "new.agent",
                result.get(0).matricule()
        );

        assertEquals(
                "New Agent",
                result.get(0).name()
        );

        assertEquals(
                "new.agent@ecobank.com",
                result.get(0).email()
        );

        assertNull(
                result.get(0).team()
        );
    }

    @Test
    void approveActivatesAccountAndNotifiesTheAgent() {

        User pending = User.builder()
                .id(30L)
                .username("new.agent")
                .name("New Agent")
                .email("new.agent@ecobank.com")
                .status("PENDING")
                .failedAttempts(3)
                .loginCount(0)
                .accountEnabled(false)
                .accountLocked(true)
                .lockedUntil(
                        OffsetDateTime.now()
                                .plusMinutes(30)
                )
                .build();

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase("new.agent")
        ).thenReturn(
                Optional.of(pending)
        );

        service.approve(
                "new.agent"
        );

        assertEquals(
                "APPROVED",
                pending.getStatus()
        );

        assertEquals(
                0,
                pending.getFailedAttempts()
        );

        assertTrue(
                Boolean.TRUE.equals(
                        pending.getAccountEnabled()
                )
        );

        assertFalse(
                Boolean.TRUE.equals(
                        pending.getAccountLocked()
                )
        );

        assertNull(
                pending.getLockedUntil()
        );

        assertNotNull(
                pending.getValidatedAt()
        );

        verify(
                userRepository
        ).save(
                pending
        );

        verify(
                notificationService
        ).sendAccountStatusEmail(
                "new.agent",
                "new.agent@ecobank.com",
                true
        );
    }

    @Test
    void rejectMarksAccountRejectedAndNotifiesTheAgent() {

        User pending = User.builder()
                .id(40L)
                .username("new.agent")
                .name("New Agent")
                .email("new.agent@ecobank.com")
                .status("PENDING")
                .failedAttempts(0)
                .loginCount(0)
                .build();

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase("new.agent")
        ).thenReturn(
                Optional.of(pending)
        );

        service.reject(
                "new.agent"
        );

        assertEquals(
                "REJECTED",
                pending.getStatus()
        );

        assertNotNull(
                pending.getValidatedAt()
        );

        verify(
                userRepository
        ).save(
                pending
        );

        verify(
                notificationService
        ).sendAccountStatusEmail(
                "new.agent",
                "new.agent@ecobank.com",
                false
        );
    }

    @Test
    void approveUnknownUsernameThrowsNotFound() {

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase("ghost")
        ).thenReturn(
                Optional.empty()
        );

        assertThrows(
                ApiException.class,
                () -> service.approve(
                        "ghost"
                )
        );

        verify(
                userRepository,
                never()
        ).save(
                any(User.class)
        );

        verifyNoInteractions(
                notificationService
        );
    }

    @Test
    void resetTotpClearsTheSecretAndConfirmationSoTheAgentCanReEnroll() {

        User user = User.builder()
                .id(50L)
                .username("kone.aissatou")
                .name("Koné")
                .status("APPROVED")
                .failedAttempts(0)
                .loginCount(0)
                .totpSecret("OLDSECRETXXXX")
                .totpConfirmedAt(
                        OffsetDateTime.now()
                                .minusMonths(1)
                )
                .build();

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "kone.aissatou"
                        )
        ).thenReturn(
                Optional.of(user)
        );

        service.resetTotp(
                "kone.aissatou"
        );

        assertNull(
                user.getTotpSecret(),
                "Le secret TOTP doit être supprimé."
        );

        assertNull(
                user.getTotpConfirmedAt(),
                "La confirmation TOTP doit être supprimée."
        );

        verify(
                userRepository
        ).save(
                user
        );
    }

    @Test
    void approveWithoutEmailDoesNotSendNotification() {

        User user = User.builder()
                .id(60L)
                .username("agent.noemail")
                .name("Agent Without Email")
                .email(null)
                .status("PENDING")
                .failedAttempts(0)
                .loginCount(0)
                .build();

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "agent.noemail"
                        )
        ).thenReturn(
                Optional.of(user)
        );

        service.approve(
                "agent.noemail"
        );

        assertEquals(
                "APPROVED",
                user.getStatus()
        );

        verify(
                userRepository
        ).save(
                user
        );

        verifyNoInteractions(
                notificationService
        );
    }

    @Test
    void resetTotpUnknownUsernameThrowsNotFound() {

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "unknown"
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThrows(
                ApiException.class,
                () -> service.resetTotp(
                        "unknown"
                )
        );

        verify(
                userRepository,
                never()
        ).save(
                any(User.class)
        );
    }
}