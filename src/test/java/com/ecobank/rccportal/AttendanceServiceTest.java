package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.AttendanceRecordResponse;
import com.ecobank.rccportal.model.AttendanceRecord;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AttendanceRecordRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.service.AttendanceService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceServiceTest {

    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    @Mock
    private UserRepository userRepository;

    private AttendanceService service;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        service = new AttendanceService(
                attendanceRecordRepository,
                userRepository
        );
    }

    /**
     * Vérifie qu'un premier pointage crée correctement
     * un AttendanceRecord avec une heure d'arrivée.
     */
    @Test
    void clockInCreatesARecordWithArrivalTimeWhenNoneExistsYet() {

        User user = User.builder()
                .id(1L)
                .username("kone.aissatou")
                .name("Koné Aïssatou")
                .build();

        LocalDate today = LocalDate.now();

        when(
                attendanceRecordRepository
                        .findByUserAndWorkDate(user, today)
        ).thenReturn(Optional.empty());

        service.clockIn(user);

        ArgumentCaptor<AttendanceRecord> captor =
                ArgumentCaptor.forClass(
                        AttendanceRecord.class
                );

        verify(attendanceRecordRepository)
                .save(captor.capture());

        AttendanceRecord savedRecord =
                captor.getValue();

        assertNotNull(savedRecord);

        assertEquals(
                "present",
                savedRecord.getStatus()
        );

        assertNotNull(
                savedRecord.getArrivalTime()
        );

        assertEquals(
                today,
                savedRecord.getWorkDate()
        );

        assertEquals(
                user,
                savedRecord.getUser()
        );
    }

    /**
     * Vérifie qu'une deuxième connexion le même jour
     * ne modifie pas l'heure d'arrivée initiale.
     */
    @Test
    void clockInDoesNotOverwriteAnAlreadyRecordedArrivalTime() {

        User user = User.builder()
                .id(1L)
                .username("kone.aissatou")
                .name("Koné Aïssatou")
                .build();

        LocalDate today = LocalDate.now();

        LocalTime firstArrival =
                LocalTime.of(8, 2);

        AttendanceRecord existing =
                AttendanceRecord.builder()
                        .attendanceId(10)
                        .user(user)
                        .workDate(today)
                        .status("present")
                        .arrivalTime(firstArrival)
                        .build();

        when(
                attendanceRecordRepository
                        .findByUserAndWorkDate(
                                user,
                                today
                        )
        ).thenReturn(
                Optional.of(existing)
        );

        /*
         * Deuxième connexion le même jour.
         */
        service.clockIn(user);

        ArgumentCaptor<AttendanceRecord> captor =
                ArgumentCaptor.forClass(
                        AttendanceRecord.class
                );

        verify(attendanceRecordRepository)
                .save(captor.capture());

        AttendanceRecord savedRecord =
                captor.getValue();

        assertEquals(
                firstArrival,
                savedRecord.getArrivalTime(),
                "L'heure d'arrivée initiale ne doit pas être écrasée."
        );

        assertEquals(
                "present",
                savedRecord.getStatus()
        );
    }

    /**
     * Vérifie que le pointage de sortie
     * enregistre uniquement l'heure de départ.
     */
    @Test
    void clockOutKeepsStatusPresentAndOnlyRecordsDepartureTime() {

        User user = User.builder()
                .id(1L)
                .username("kone.aissatou")
                .name("Koné Aïssatou")
                .build();

        LocalDate today = LocalDate.now();

        AttendanceRecord existing =
                AttendanceRecord.builder()
                        .attendanceId(10)
                        .user(user)
                        .workDate(today)
                        .status("present")
                        .arrivalTime(
                                LocalTime.of(8, 2)
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

        when(
                attendanceRecordRepository
                        .findByUserAndWorkDate(
                                user,
                                today
                        )
        ).thenReturn(
                Optional.of(existing)
        );

        service.clockOut(
                "kone.aissatou"
        );

        ArgumentCaptor<AttendanceRecord> captor =
                ArgumentCaptor.forClass(
                        AttendanceRecord.class
                );

        verify(attendanceRecordRepository)
                .save(captor.capture());

        AttendanceRecord savedRecord =
                captor.getValue();

        assertEquals(
                "present",
                savedRecord.getStatus(),
                "Un agent qui pointe son départ reste considéré présent pour la journée."
        );

        assertNotNull(
                savedRecord.getDepartureTime()
        );

        assertEquals(
                LocalTime.of(8, 2),
                savedRecord.getArrivalTime()
        );
    }

    /**
     * Vérifie qu'un utilisateur sans pointage
     * est considéré absent.
     */
    @Test
    void todayReturnsAbsentByDefaultWhenNoRecordExists() {

        User user = User.builder()
                .id(1L)
                .username("kone.aissatou")
                .name("Koné Aïssatou")
                .build();

        LocalDate today = LocalDate.now();

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "kone.aissatou"
                        )
        ).thenReturn(
                Optional.of(user)
        );

        when(
                attendanceRecordRepository
                        .findByUserAndWorkDate(
                                user,
                                today
                        )
        ).thenReturn(
                Optional.empty()
        );

        AttendanceRecordResponse response =
                service.today(
                        "kone.aissatou"
                );

        assertNotNull(response);

        assertEquals(
                "absent",
                response.status()
        );

        assertNull(
                response.id()
        );

        assertEquals(
                "kone.aissatou",
                response.matricule()
        );

        assertEquals(
                "Koné Aïssatou",
                response.name()
        );

        assertEquals(
                today,
                response.workDate()
        );

        /*
         * Le service RCC n'est pas encore récupéré
         * dans AttendanceService.
         */
        assertNull(
                response.team()
        );
    }
}