package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AttendanceRecordResponse;
import com.ecobank.rccportal.model.AttendanceRecord;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AttendanceRecordRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final UserRepository userRepository;

    public AttendanceService(
            AttendanceRecordRepository attendanceRecordRepository,
            UserRepository userRepository) {

        this.attendanceRecordRepository = attendanceRecordRepository;
        this.userRepository = userRepository;
    }

    /**
     * Enregistre automatiquement l'arrivée de l'utilisateur.
     *
     * Cette méthode est idempotente :
     * si l'utilisateur a déjà pointé aujourd'hui,
     * son heure d'arrivée initiale est conservée.
     */
    @Transactional
    public void clockIn(User user) {

        if (user == null || user.getId() == null) {
            throw ApiException.badRequest("Invalid user.");
        }

        LocalDate today = LocalDate.now();

        AttendanceRecord record =
                attendanceRecordRepository
                        .findByUserAndWorkDate(user, today)
                        .orElseGet(() ->
                                AttendanceRecord.builder()
                                        .user(user)
                                        .workDate(today)
                                        .arrivalTime(
                                                LocalTime.now()
                                                        .withNano(0)
                                        )
                                        .build()
                        );

        record.setStatus("present");

        // Reconnexion le même jour : l'agent est de nouveau présent, l'heure de départ
        // (dernière déconnexion) est effacée jusqu'à sa prochaine déconnexion. L'heure
        // d'arrivée (première connexion du jour) est conservée ; le détail des absences
        // intermédiaires est tracé dans le suivi de shift (événements LOGOUT/LOGIN).
        record.setDepartureTime(null);

        if (record.getArrivalTime() == null) {
            record.setArrivalTime(
                    LocalTime.now().withNano(0)
            );
        }

        attendanceRecordRepository.save(record);
    }

    /**
     * Enregistre l'heure de départ.
     *
     * L'ancien paramètre "username" est remplacé
     * par USERS.USERNAME.
     */
    @Transactional
    public void clockOut(String username) {

        User user = findUserByUsername(username);

        attendanceRecordRepository
                .findByUserAndWorkDate(
                        user,
                        LocalDate.now()
                )
                .ifPresent(record -> {

                    record.setDepartureTime(
                            LocalTime.now().withNano(0)
                    );

                    attendanceRecordRepository.save(record);
                });
    }

    /**
     * Retourne le pointage du jour de l'utilisateur.
     */
    @Transactional(readOnly = true)
    public AttendanceRecordResponse today(String username) {

        User user = findUserByUsername(username);

        LocalDate today = LocalDate.now();

        return attendanceRecordRepository
                .findByUserAndWorkDate(user, today)
                .map(this::toResponse)
                .orElseGet(() ->
                        new AttendanceRecordResponse(
                                null,
                                user.getUsername(),
                                user.getName(),

                                /*
                                 * Anciennement TEAM.
                                 *
                                 * Le service RCC sera récupéré
                                 * via USER_SERVICES -> SERVICES.
                                 */
                                null,

                                today,
                                "absent",
                                null,
                                null
                        )
                );
    }

    /**
     * Vue globale des pointages pour une date.
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecordResponse> forDate(
            LocalDate date) {

        if (date == null) {
            date = LocalDate.now();
        }

        return attendanceRecordRepository
                .findByWorkDateOrderByUser_NameAsc(date)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Historique des pointages d'un utilisateur.
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecordResponse> historyFor(
            String username) {

        User user = findUserByUsername(username);

        return attendanceRecordRepository
                .findByUserOrderByWorkDateDesc(user)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Recherche un utilisateur via dbo.USERS.USERNAME.
     */
    private User findUserByUsername(String username) {

        if (username == null || username.isBlank()) {
            throw ApiException.badRequest(
                    "Username is required."
            );
        }

        return userRepository
                .findFirstByUsernameIgnoreCase(
                        username.trim()
                )
                .orElseThrow(
                        () -> ApiException.notFound(
                                "Unknown user."
                        )
                );
    }

    /**
     * Conversion AttendanceRecord -> DTO.
     */
    private AttendanceRecordResponse toResponse(
            AttendanceRecord record) {

        User user = record.getUser();

        String username =
                user != null
                        ? user.getUsername()
                        : null;

        String name =
                user != null
                        ? user.getName()
                        : null;

        String team = user != null ? com.ecobank.rccportal.util.TeamClassifier.classify(user.getActivity()).name() : null;

        return new AttendanceRecordResponse(
                record.getAttendanceId(),
                username,
                name,
                team,
                record.getWorkDate(),
                record.getStatus(),
                record.getArrivalTime(),
                record.getDepartureTime()
        );
    }
}