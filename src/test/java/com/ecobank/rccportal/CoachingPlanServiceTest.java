package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.CoachingPlanRequest;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CoachingPlanRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.service.CoachingPlanService;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoachingPlanServiceTest {

    @Mock
    private CoachingPlanRepository coachingPlanRepository;

    @Mock
    private UserRepository userRepository;

    private CoachingPlanService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        service = new CoachingPlanService(
                coachingPlanRepository,
                userRepository
        );
    }

    @Test
    void createRejectsAnInvalidStatus() {

        User agent = User.builder()
                .id(1L)
                .username("kone.aissatou")
                .name("Koné")
                .status("APPROVED")
                .failedAttempts(0)
                .loginCount(0)
                .build();

        when(
                userRepository.findFirstByUsernameIgnoreCase(
                        "kone.aissatou"
                )
        ).thenReturn(
                Optional.of(agent)
        );

        CoachingPlanRequest request =
                new CoachingPlanRequest(
                        "kone.aissatou",
                        "Écoute active",
                        LocalDate.now(),
                        "not_valid",
                        null
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(request)
                );

        assertTrue(
                ex.getMessage().contains("status")
        );
    }

    @Test
    void createDefaultsStatusToTodoWhenNotProvided() {

        User agent = User.builder()
                .id(1L)
                .username("kone.aissatou")
                .name("Koné")
                .status("APPROVED")
                .failedAttempts(0)
                .loginCount(0)
                .build();

        when(
                userRepository.findFirstByUsernameIgnoreCase(
                        "kone.aissatou"
                )
        ).thenReturn(
                Optional.of(agent)
        );

        when(
                coachingPlanRepository.save(any())
        ).thenAnswer(invocation -> {

            var plan = invocation.getArgument(
                    0,
                    com.ecobank.rccportal.model.CoachingPlan.class
            );

            plan.setCoachingPlanId(1);

            return plan;
        });

        CoachingPlanRequest request =
                new CoachingPlanRequest(
                        "kone.aissatou",
                        "Écoute active",
                        LocalDate.now(),
                        null,
                        null
                );

        var response =
                service.create(request);

        assertEquals(
                "todo",
                response.status()
        );
    }

    @Test
    void createRejectsAMissingAxis() {

        CoachingPlanRequest request =
                new CoachingPlanRequest(
                        "kone.aissatou",
                        "  ",
                        LocalDate.now(),
                        null,
                        null
                );

        assertThrows(
                ApiException.class,
                () -> service.create(request)
        );
    }
}