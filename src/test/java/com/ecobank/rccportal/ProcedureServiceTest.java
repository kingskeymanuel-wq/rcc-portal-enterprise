package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.ProcedureRequest;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureStep;
import com.ecobank.rccportal.model.ProcedureZone;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.KpiEventService;
import com.ecobank.rccportal.service.ProcedureService;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcedureServiceTest {

    @Mock
    private ProcedureRepository procedureRepository;

    @Mock
    private ProcedureStepRepository procedureStepRepository;

    @Mock
    private ProcedureZoneRepository procedureZoneRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KpiEventService kpiEventService;

    @Mock
    private com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;

    @Mock
    private com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;

    @Mock
    private com.ecobank.rccportal.repository.FavoriteProcedureRepository favoriteProcedureRepository;

    @Mock
    private com.ecobank.rccportal.repository.ProcedureWorkflowNodeRepository procedureWorkflowNodeRepository;

    @Mock
    private com.ecobank.rccportal.service.ImageStorageService imageStorageService;

    @Mock
    private com.ecobank.rccportal.repository.FavoriteAttachmentRepository favoriteAttachmentRepository;

    private ProcedureService service;
    @Mock
    private AttachmentRepository attachmentRepository;
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProcedureService(
                procedureRepository,
                procedureStepRepository,
                procedureZoneRepository,
                rccServiceRepository,
                userServiceAssignmentRepository,
                userRepository,
                attachmentRepository,
                kpiEventService,
                favoriteProcedureRepository,
                procedureWorkflowNodeRepository,
                imageStorageService,
                favoriteAttachmentRepository
        );
    }

    @Test
    void createRejectsAnEmptyStepsList() {

        ProcedureRequest request =
                new ProcedureRequest(
                        "front",
                        "Titre",
                        List.of()
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(
                                request,
                                new AuthenticatedUser(
                                        "kone.aissatou",
                                        "agent",
                                        "Inbound",
                                        "Test"
                                )
                        )
                );

        assertTrue(
                ex.getMessage().contains("step")
        );
    }

    @Test
    void createRejectsABlankStep() {

        ProcedureRequest request =
                new ProcedureRequest(
                        "front",
                        "Titre",
                        List.of(
                                "Étape 1",
                                "   "
                        )
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(
                                request,
                                new AuthenticatedUser(
                                        "kone.aissatou",
                                        "agent",
                                        "Inbound",
                                        "Test"
                                )
                        )
                );

        assertTrue(
                ex.getMessage().contains("empty")
        );
    }

    @Test
    void createSavesStepsInOrderStartingAtOne() {

        ProcedureZone zone =
                ProcedureZone.builder()
                        .zoneId(1)
                        .code("front")
                        .label("Front Office")
                        .build();

        User author =
                User.builder()
                        .id(1L)
                        .username("kone.aissatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .build();

        when(
                procedureZoneRepository.findByCode("front")
        ).thenReturn(
                Optional.of(zone)
        );

        when(
                userRepository.findFirstByUsernameIgnoreCase(
                        "kone.aissatou"
                )
        ).thenReturn(
                Optional.of(author)
        );

        Procedure saved =
                Procedure.builder()
                        .procedureId(10)
                        .zone(zone)
                        .title("Titre")
                        .createdBy(author)
                        .build();

        when(
                procedureRepository.save(any())
        ).thenReturn(saved);

        when(
                procedureStepRepository
                        .findByProcedureOrderByStepNumberAsc(saved)
        ).thenReturn(
                List.of(
                        ProcedureStep.builder()
                                .stepNumber(1)
                                .content("Étape 1")
                                .build(),

                        ProcedureStep.builder()
                                .stepNumber(2)
                                .content("Étape 2")
                                .build()
                )
        );

        ProcedureRequest request =
                new ProcedureRequest(
                        "front",
                        "Titre",
                        List.of(
                                "Étape 1",
                                "Étape 2"
                        )
                );

        var response =
                service.create(
                        request,
                        new AuthenticatedUser(
                                "kone.aissatou",
                                "agent",
                                "Inbound",
                                "Test"
                        )
                );

        assertEquals(
                List.of(
                        "Étape 1",
                        "Étape 2"
                ),
                response.steps()
        );

        verify(
                procedureStepRepository,
                times(2)
        ).save(any());
    }

    @Test
    void aNonAuthorCannotUpdateSomeoneElsesProcedure() {

        ProcedureZone zone =
                ProcedureZone.builder()
                        .zoneId(1)
                        .code("front")
                        .label("Front")
                        .build();

        User author =
                User.builder()
                        .id(1L)
                        .username("kone.aissatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        Procedure procedure =
                Procedure.builder()
                        .procedureId(1)
                        .zone(zone)
                        .title("x")
                        .createdBy(author)
                        .build();

        when(
                procedureRepository.findById(1)
        ).thenReturn(
                Optional.of(procedure)
        );

        ProcedureRequest request =
                new ProcedureRequest(
                        null,
                        "nouveau titre",
                        null
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.update(
                                1,
                                request,
                                new AuthenticatedUser(
                                        "diallo.thierno",
                                        "agent",
                                        "Inbound",
                                        "Test"
                                )
                        )
                );

        assertEquals(
                403,
                ex.getStatus().value()
        );
    }
}