package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.ManualKpiEntryRequest;
import com.ecobank.rccportal.model.ManualKpiEntry;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ManualKpiEntryRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.service.ManualKpiEntryService;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualKpiEntryServiceTest {

    @Mock
    private ManualKpiEntryRepository manualKpiEntryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;

    @Mock
    private com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;

    @Mock
    private com.ecobank.rccportal.service.ImportIntelligenceService importIntelligenceService;

    private ManualKpiEntryService service;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        service = new ManualKpiEntryService(
                manualKpiEntryRepository,
                userRepository,
                rccServiceRepository,
                userServiceAssignmentRepository,
                importIntelligenceService
        );
    }

    @Test
    void createRejectsANegativeMetricValue() {

        ManualKpiEntryRequest request =
                new ManualKpiEntryRequest(
                        "kone.aissatou",
                        "CSAT",
                        new BigDecimal("-1"),
                        LocalDate.now()
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(
                                request,
                                "traore.ali"
                        )
                );

        assertTrue(
                ex.getMessage().contains("non-negative")
        );
    }

    @Test
    void createRejectsAMissingMetricCode() {

        ManualKpiEntryRequest request =
                new ManualKpiEntryRequest(
                        "kone.aissatou",
                        "  ",
                        new BigDecimal("4.5"),
                        LocalDate.now()
                );

        assertThrows(
                ApiException.class,
                () -> service.create(
                        request,
                        "traore.ali"
                )
        );
    }

    @Test
    void createUppercasesTheMetricCode() {

        User subject =
                User.builder()
                        .id(1L)
                        .username("kone.aissatou")
                        .name("Koné")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        User enteredBy =
                User.builder()
                        .id(2L)
                        .username("traore.ali")
                        .name("Traoré Ali")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        when(
                userRepository.findFirstByUsernameIgnoreCase(
                        "kone.aissatou"
                )
        ).thenReturn(
                Optional.of(subject)
        );

        when(
                userRepository.findFirstByUsernameIgnoreCase(
                        "traore.ali"
                )
        ).thenReturn(
                Optional.of(enteredBy)
        );

        when(
                manualKpiEntryRepository.save(any())
        ).thenAnswer(invocation -> {

            ManualKpiEntry entry =
                    invocation.getArgument(
                            0,
                            ManualKpiEntry.class
                    );

            entry.setManualKpiEntryId(1);

            return entry;
        });

        ManualKpiEntryRequest request =
                new ManualKpiEntryRequest(
                        "kone.aissatou",
                        "csat",
                        new BigDecimal("4.5"),
                        LocalDate.now()
                );

        var response =
                service.create(
                        request,
                        "traore.ali"
                );

        assertEquals(
                "CSAT",
                response.metricCode()
        );
    }
}