package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.QualityEvaluationRequest;
import com.ecobank.rccportal.model.QualityEvaluation;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.QualityEvaluationService;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityEvaluationServiceTest {

    @Mock
    private QualityEvaluationRepository evaluationRepository;

    @Mock
    private QualityEvaluationScoreRepository scoreRepository;

    @Mock
    private QualityCriterionRepository criterionRepository;

    @Mock
    private QualityCriterionAttributeRepository criterionAttributeRepository;

    @Mock
    private QualityMotifRepository motifRepository;

    @Mock
    private UserRepository userRepository;

    private QualityEvaluationService service;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        service = new QualityEvaluationService(
                evaluationRepository,
                scoreRepository,
                criterionRepository,
                criterionAttributeRepository,
                motifRepository,
                userRepository
        );
    }

    @Test
    void createRejectsAnUnknownFeedbackStatus() {

        QualityEvaluationRequest request =
                new QualityEvaluationRequest(
                        "kone.aissatou",
                        LocalDate.now(),
                        LocalDate.now(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "not_a_valid_status",
                        null,
                        List.of()
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(
                                request,
                                new AuthenticatedUser(
                                        "traore.ali",
                                        "agent",
                                        "Quality Assurance",
                                        "T"
                                )
                        )
                );

        assertTrue(
                ex.getMessage().contains("feedbackStatus")
        );
    }

    @Test
    void createRejectsAnUnknownAgent() {

        when(
                userRepository.findFirstByUsernameIgnoreCase("ghost")
        ).thenReturn(
                Optional.empty()
        );

        QualityEvaluationRequest request =
                new QualityEvaluationRequest(
                        "ghost",
                        LocalDate.now(),
                        LocalDate.now(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of()
                );

        assertThrows(
                ApiException.class,
                () -> service.create(
                        request,
                        new AuthenticatedUser(
                                "traore.ali",
                                "agent",
                                "Quality Assurance",
                                "T"
                        )
                )
        );
    }

    @Test
    void aNonEvaluatorAgentCannotUpdateSomeoneElsesEvaluation() {

        User evaluator =
                User.builder()
                        .id(1L)
                        .username("traore.ali")
                        .name("Traoré Ali")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        User agent =
                User.builder()
                        .id(2L)
                        .username("kone.aissatou")
                        .name("Koné")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        QualityEvaluation existing =
                QualityEvaluation.builder()
                        .evaluationId(1)
                        .agent(agent)
                        .evaluator(evaluator)
                        .evaluationDate(LocalDate.now())
                        .callDate(LocalDate.now())
                        .feedbackStatus("pending")
                        .build();

        when(
                evaluationRepository.findById(1)
        ).thenReturn(
                Optional.of(existing)
        );

        when(
                scoreRepository.findByEvaluation(existing)
        ).thenReturn(
                List.of()
        );

        QualityEvaluationRequest request =
                new QualityEvaluationRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
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
                                        "T"
                                )
                        )
                );

        assertEquals(
                403,
                ex.getStatus().value()
        );
    }

    @Test
    void theOriginalEvaluatorCanUpdateTheirOwnEvaluation() {

        User evaluator =
                User.builder()
                        .id(1L)
                        .username("traore.ali")
                        .name("Traoré Ali")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        User agent =
                User.builder()
                        .id(2L)
                        .username("kone.aissatou")
                        .name("Koné")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        QualityEvaluation existing =
                QualityEvaluation.builder()
                        .evaluationId(1)
                        .agent(agent)
                        .evaluator(evaluator)
                        .evaluationDate(LocalDate.now())
                        .callDate(LocalDate.now())
                        .feedbackStatus("pending")
                        .build();

        when(
                evaluationRepository.findById(1)
        ).thenReturn(
                Optional.of(existing)
        );

        when(
                scoreRepository.findByEvaluation(existing)
        ).thenReturn(
                List.of()
        );

        when(
                evaluationRepository.save(existing)
        ).thenReturn(existing);

        QualityEvaluationRequest request =
                new QualityEvaluationRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Bon relationnel",
                        null,
                        null,
                        "done",
                        null,
                        null
                );

        var response =
                service.update(
                        1,
                        request,
                        new AuthenticatedUser(
                                "traore.ali",
                                "agent",
                                "Quality Assurance",
                                "T"
                        )
                );

        assertEquals(
                "Bon relationnel",
                response.strengths()
        );

        assertEquals(
                "done",
                response.feedbackStatus()
        );
    }
}