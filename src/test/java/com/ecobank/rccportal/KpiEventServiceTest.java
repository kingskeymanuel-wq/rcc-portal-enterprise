package com.ecobank.rccportal;

import com.ecobank.rccportal.model.KpiEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.KpiEventRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.service.KpiEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KpiEventServiceTest {

    @Mock
    private KpiEventRepository kpiEventRepository;

    @Mock
    private UserRepository userRepository;

    private KpiEventService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        service = new KpiEventService(
                kpiEventRepository,
                userRepository
        );
    }

    @Test
    void recordResolvesTheUserFromTheMatricule() {

        User user = User.builder()
                .id(1L)
                .username("kone.aissatou")
                .name("Koné Aïssatou")
                .status("APPROVED")
                .failedAttempts(0)
                .loginCount(0)
                .build();

        when(
                userRepository.findFirstByUsernameIgnoreCase(
                        "kone.aissatou"
                )
        ).thenReturn(
                Optional.of(user)
        );

        service.record(
                "kone.aissatou",
                "proc_view",
                "front"
        );

        ArgumentCaptor<KpiEvent> captor =
                ArgumentCaptor.forClass(KpiEvent.class);

        verify(
                kpiEventRepository
        ).save(
                captor.capture()
        );

        KpiEvent savedEvent = captor.getValue();

        assertEquals(
                user,
                savedEvent.getUser()
        );

        assertEquals(
                "proc_view",
                savedEvent.getEventType()
        );

        assertEquals(
                "front",
                savedEvent.getEventKey()
        );

        assertNotNull(
                savedEvent.getOccurredAt()
        );
    }

    @Test
    void recordToleratesANullMatricule() {

        service.record(
                null,
                "system_event",
                null
        );

        ArgumentCaptor<KpiEvent> captor =
                ArgumentCaptor.forClass(KpiEvent.class);

        verify(
                kpiEventRepository
        ).save(
                captor.capture()
        );

        assertNull(
                captor.getValue().getUser()
        );

        verifyNoInteractions(
                userRepository
        );
    }

    @Test
    void recordToleratesAnUnknownMatriculeWithoutThrowing() {

        when(
                userRepository.findFirstByUsernameIgnoreCase(
                        "ghost"
                )
        ).thenReturn(
                Optional.empty()
        );

        service.record(
                "ghost",
                "proc_view",
                "front"
        );

        ArgumentCaptor<KpiEvent> captor =
                ArgumentCaptor.forClass(KpiEvent.class);

        verify(
                kpiEventRepository
        ).save(
                captor.capture()
        );

        assertNull(
                captor.getValue().getUser()
        );
    }
}