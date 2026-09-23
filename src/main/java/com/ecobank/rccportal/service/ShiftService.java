package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.RecordShiftEventRequest;
import com.ecobank.rccportal.dto.ShiftEventResponse;
import com.ecobank.rccportal.dto.ShiftStatusResponse;
import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.ShiftEventRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.ShiftTimeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Journal de shift (pause / pause déjeuner / fin de shift) — append-only, voir
 * ShiftEvent. L'agent ne peut qu'ajouter un événement respectant la machine à
 * états ci-dessous ; il ne peut jamais modifier/supprimer un événement passé.
 * QA/admin consultent la chronologie complète (ShiftController.forDate).
 */
@Slf4j
@Service
public class ShiftService {

    /** État courant -> événements autorisés à partir de cet état. */
    private static final Map<String, List<String>> ALLOWED_TRANSITIONS = Map.of(
            "WORKING", List.of("PAUSE_START", "LUNCH_START", "TRAINING_START", "MEETING_START", "SHIFT_END"),
            "ON_PAUSE", List.of("PAUSE_END", "SHIFT_END"),
            "ON_LUNCH", List.of("LUNCH_END", "SHIFT_END"),
            "ON_TRAINING", List.of("TRAINING_END", "SHIFT_END"),
            "ON_MEETING", List.of("MEETING_END", "SHIFT_END"),
            "SHIFT_ENDED", List.of(),
            // DISCONNECTED ne se rencontre pas ici : recordEvent() enregistre d'abord une
            // reconnexion (LOGIN) et repart de l'état restauré — voir recordEvent().
            // NOT_STARTED (aucun événement LOGIN aujourd'hui) — le frontend affiche déjà les
            // mêmes boutons que WORKING dans ce cas (voir applyShiftUi()) ; le backend doit
            // accepter les mêmes transitions, sinon tout clic échoue systématiquement pour
            // un agent sans LOGIN enregistré ce jour-là (bug réel : "Formation"/"Réunion" — et
            // en fait Pause/Déjeuner aussi — rejetés avec "Action non autorisée").
            "NOT_STARTED", List.of("PAUSE_START", "LUNCH_START", "TRAINING_START", "MEETING_START", "SHIFT_END")
    );

    private final ShiftEventRepository shiftEventRepository;
    private final UserRepository userRepository;
    private final UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final com.ecobank.rccportal.repository.WorkflowRequestRepository workflowRequestRepository;
    private final com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository;

    public ShiftService(ShiftEventRepository shiftEventRepository, UserRepository userRepository,
                        UserServiceAssignmentRepository userServiceAssignmentRepository,
                        com.ecobank.rccportal.repository.WorkflowRequestRepository workflowRequestRepository,
                        com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository) {
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.shiftEventRepository = shiftEventRepository;
        this.userRepository = userRepository;
        this.workflowRequestRepository = workflowRequestRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /** Usernames ayant le rôle TEAM_LEADER — un Team Leader dirige une équipe mais n'en est pas
     *  un membre à suivre : il ne doit jamais apparaître dans les vues "Équipe"/"En direct" du
     *  Suivi de shift (voir demande utilisateur), seulement dans sa propre vue "Moi-même". */
    private java.util.Set<String> teamLeaderUsernames() {
        return userRoleRepository.findByRoleNameIgnoreCase("TEAM_LEADER").stream()
                .map(ur -> ur.getUser().getUsername())
                .filter(java.util.Objects::nonNull)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<ShiftEventResponse> excludingTeamLeaders(List<ShiftEventResponse> events) {
        java.util.Set<String> leaders = teamLeaderUsernames();
        if (leaders.isEmpty()) return events;
        return events.stream()
                .filter(e -> e.username() == null || !leaders.contains(e.username().toLowerCase()))
                .toList();
    }

    @Transactional
    public void recordLogin(User user) {
        saveEvent(user, "LOGIN");
    }

    /**
     * Déconnexion (bouton Déconnexion, sans « Fin de shift ») — enregistre l'heure de
     * déconnexion, sauf si le shift n'a pas commencé, est déjà terminé ou déjà déconnecté.
     * Transaction séparée : un échec ici ne doit jamais faire échouer la déconnexion.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordLogout(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
        if (user == null) return;
        String state = timelineOf(todayEventsFor(user)).state();
        if (ShiftTimeline.NOT_STARTED.equals(state) || ShiftTimeline.SHIFT_ENDED.equals(state)
                || ShiftTimeline.DISCONNECTED.equals(state)) {
            return;
        }
        saveEvent(user, "LOGOUT");
    }

    @Transactional
    public ShiftStatusResponse recordEvent(String username, RecordShiftEventRequest request) {
        User user = findUser(username);
        String eventType = request.eventType() == null ? "" : request.eventType().toUpperCase();

        String currentState = computeState(todayEventsFor(user));
        if (ShiftTimeline.DISCONNECTED.equals(currentState)) {
            // Action depuis une session encore ouverte (autre onglet) après une déconnexion :
            // c'est une reconnexion de fait — on la trace avant d'appliquer l'action.
            saveEvent(user, "LOGIN");
            currentState = computeState(todayEventsFor(user));
        }
        List<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(currentState, List.of());

        if (!allowed.contains(eventType)) {
            throw ApiException.badRequest(
                    "Action non autorisée depuis l'état actuel (" + currentState + ").");
        }

        saveEvent(user, eventType);
        return getStatus(username);
    }

    @Transactional(readOnly = true)
    public ShiftStatusResponse getStatus(String username) {
        User user = findUser(username);
        List<ShiftEvent> events = todayEventsFor(user);
        ShiftTimeline timeline = timelineOf(events);
        LocalDateTime now = LocalDateTime.now();
        List<ShiftEventResponse> responses = events.stream().map(this::toResponse).toList();
        return new ShiftStatusResponse(timeline.state(), responses, timeline.stateSince(),
                timeline.lastDisconnectedAt(), timeline.lastReconnectedAt(),
                timeline.absenceMinutes(now), timeline.absenceMinutesInCurrentState(now),
                absencesOf(timeline, now));
    }

    /** Statut en direct de chaque agent (déduit du dernier événement du jour) — pour le
     *  bouton "En direct" du suivi de shift (RH/Superviseur/QA/Admin/Team Leader). Un Team
     *  Leader dirige une équipe mais n'en est pas un membre à suivre : exclu de cette liste
     *  (voir teamLeaderUsernames), quelle que soit l'équipe qui consulte. */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.LiveShiftStatusResponse> liveStatusForAllUsers() {
        java.util.Set<String> leaders = teamLeaderUsernames();
        List<com.ecobank.rccportal.dto.LiveShiftStatusResponse> out = new java.util.ArrayList<>();
        for (User user : userRepository.findAll()) {
            if (user.getUsername() != null && leaders.contains(user.getUsername().toLowerCase())) continue;
            List<ShiftEvent> events = todayEventsFor(user);
            ShiftTimeline timeline = timelineOf(events);
            LocalDateTime now = LocalDateTime.now();
            String team = com.ecobank.rccportal.util.TeamClassifier.classify(user.getActivity()).name();
            out.add(new com.ecobank.rccportal.dto.LiveShiftStatusResponse(
                    user.getUsername(), user.getName() != null ? user.getName() : user.getUsername(), team,
                    timeline.state(), timeline.stateSince(), timeline.lastDisconnectedAt(),
                    timeline.lastReconnectedAt(), timeline.absenceMinutes(now), timeline.absences().size()));
        }
        return out;
    }

    /** Vue globale QA/admin — tous les agents, pour un jour donné. */
    @Transactional(readOnly = true)
    public List<ShiftEventResponse> forDate(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        return shiftEventRepository.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(from, to).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Les propres événements de l'agent pour un jour donné — pour sa bande personnelle dans Ma Performance. */
    @Transactional(readOnly = true)
    public List<ShiftEventResponse> forDate(String username, LocalDate date) {
        return forRange(username, date, date);
    }

    /** Même principe, sur une plage de dates — pour les vues semaine/mois de la bande personnelle. */
    @Transactional(readOnly = true)
    public List<ShiftEventResponse> forRange(String username, LocalDate from, LocalDate to) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return shiftEventRepository.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(user, start, end).stream()
                .map(this::toResponse)
                .toList();
    }

    private static final java.util.Set<String> VALID_EVENT_TYPES =
            java.util.Set.of("LOGIN", "LOGOUT", "PAUSE_START", "PAUSE_END", "LUNCH_START", "LUNCH_END",
                    "TRAINING_START", "TRAINING_END", "MEETING_START", "MEETING_END", "SHIFT_END");

    /**
     * Correction manuelle — QA/Admin/RH ajoute un événement pour un agent qui a oublié de
     * pointer, à l'heure réelle (pas forcément "maintenant"). Contrairement à recordEvent
     * (auto-pointage de l'agent), ne vérifie pas l'enchaînement d'états : une correction peut
     * légitimement combler un trou dans l'historique sans respecter la même séquence stricte.
     */
    @Transactional
    public ShiftEventResponse recordManualEvent(String subjectUsername, String eventType, LocalDateTime occurredAt) {
        if (!VALID_EVENT_TYPES.contains(eventType)) {
            throw ApiException.badRequest("Type d'événement inconnu. Valeurs possibles : " + VALID_EVENT_TYPES);
        }
        if (occurredAt == null) {
            throw ApiException.badRequest("occurredAt is required.");
        }
        User user = userRepository.findFirstByUsernameIgnoreCase(subjectUsername)
                .orElseThrow(() -> ApiException.badRequest("Unknown user: " + subjectUsername));

        ShiftEvent saved = shiftEventRepository.save(ShiftEvent.builder()
                .user(user).eventType(eventType).occurredAt(occurredAt).build());
        log.info("Shift event manually recorded (username={}, event={}, at={})", user.getUsername(), eventType, occurredAt);
        return toResponse(saved);
    }

    /**
     * Seuils de dépassement — pause courte au-delà de 15 min, pause déjeuner au-delà de 60 min.
     * Choix par défaut documentés ici ; à ajuster si la vraie politique Ecobank diffère.
     */
    private static final int PAUSE_OVERRUN_MINUTES = 15;
    private static final int LUNCH_OVERRUN_MINUTES = 60;

    /**
     * Nombre de pauses/pauses déjeuner dépassant le seuil, sur une plage — utilisé par
     * l'Analyse de données pour identifier une cause réelle d'impact sur les objectifs
     * (jamais une valeur inventée : compté directement depuis les événements de pointage).
     */
    @Transactional(readOnly = true)
    public int countPauseOverruns(String username, LocalDate from, LocalDate to) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
        if (user == null) return 0;
        List<ShiftEvent> events = shiftEventRepository.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(
                user, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        int overruns = 0;
        LocalDateTime pauseStart = null;
        String pauseType = null;
        for (ShiftEvent e : events) {
            String type = e.getEventType();
            if ("PAUSE_START".equals(type)) { pauseStart = e.getOccurredAt(); pauseType = "PAUSE"; }
            else if ("LUNCH_START".equals(type)) { pauseStart = e.getOccurredAt(); pauseType = "LUNCH"; }
            else if ("PAUSE_END".equals(type) && pauseStart != null && "PAUSE".equals(pauseType)) {
                if (java.time.Duration.between(pauseStart, e.getOccurredAt()).toMinutes() > PAUSE_OVERRUN_MINUTES) overruns++;
                pauseStart = null;
            } else if ("LUNCH_END".equals(type) && pauseStart != null && "LUNCH".equals(pauseType)) {
                if (java.time.Duration.between(pauseStart, e.getOccurredAt()).toMinutes() > LUNCH_OVERRUN_MINUTES) overruns++;
                pauseStart = null;
            }
        }
        return overruns;
    }

    /** Minutes réellement travaillées sur une plage — somme des segments entre reprise et pause/fin. */
    @Transactional(readOnly = true)
    public long totalWorkedMinutes(String username, LocalDate from, LocalDate to) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
        if (user == null) return 0;
        List<ShiftEvent> events = shiftEventRepository.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(
                user, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        // Jour par jour via ShiftTimeline : une reconnexion (2e LOGIN) ne fait plus perdre le
        // temps travaillé avant la déconnexion, et l'absence n'est jamais comptée.
        long minutes = 0;
        java.util.Map<LocalDate, List<ShiftTimeline.Event>> byDay = new java.util.TreeMap<>();
        for (ShiftEvent e : events) {
            byDay.computeIfAbsent(e.getOccurredAt().toLocalDate(), d -> new java.util.ArrayList<>())
                    .add(new ShiftTimeline.Event(e.getEventType(), e.getOccurredAt()));
        }
        for (List<ShiftTimeline.Event> day : byDay.values()) {
            minutes += ShiftTimeline.of(day).workedMinutes(null);
        }
        return minutes;
    }

    /** Jours de congé/absence approuvés de l'agent, sur une plage — pour griser sa bande personnelle. */
    @Transactional(readOnly = true)
    public java.util.Set<LocalDate> approvedLeaveDates(String username, LocalDate from, LocalDate to) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        return workflowRequestRepository.findByTypeAndStatus("LEAVE", "APPROVED").stream()
                .filter(r -> {
                    try {
                        return r.getRequestedBy() != null && user.getId().equals(r.getRequestedBy().getId());
                    } catch (jakarta.persistence.EntityNotFoundException e) {
                        return false;
                    }
                })
                .filter(r -> r.getPeriodFrom() != null && r.getPeriodTo() != null)
                .flatMap(r -> {
                    LocalDate start = r.getPeriodFrom().isBefore(from) ? from : r.getPeriodFrom();
                    LocalDate end = r.getPeriodTo().isAfter(to) ? to : r.getPeriodTo();
                    if (start.isAfter(end)) return java.util.stream.Stream.empty();
                    return start.datesUntil(end.plusDays(1));
                })
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Identifiants des agents en congé/absence approuvé un jour donné — pour la vue globale QA. */
    @Transactional(readOnly = true)
    public java.util.Set<String> usersOnApprovedLeave(LocalDate date) {
        return workflowRequestRepository.findByTypeAndStatus("LEAVE", "APPROVED").stream()
                .filter(r -> r.getPeriodFrom() != null && r.getPeriodTo() != null
                        && !date.isBefore(r.getPeriodFrom()) && !date.isAfter(r.getPeriodTo()))
                .map(r -> {
                    try {
                        return r.getRequestedBy() != null ? r.getRequestedBy().getUsername() : null;
                    } catch (jakarta.persistence.EntityNotFoundException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void saveEvent(User user, String eventType) {
        shiftEventRepository.save(ShiftEvent.builder()
                .user(user)
                .eventType(eventType)
                .occurredAt(LocalDateTime.now())
                .build());
        log.info("Shift event recorded (username={}, event={})", user.getUsername(), eventType);
    }

    private List<ShiftEvent> todayEventsFor(User user) {
        LocalDateTime from = LocalDate.now().atStartOfDay();
        LocalDateTime to = LocalDate.now().atTime(LocalTime.MAX);
        return shiftEventRepository.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(user, from, to);
    }

    /** État courant du jour — déconnexion/reconnexion prises en compte (voir ShiftTimeline). */
    private String computeState(List<ShiftEvent> todayEvents) {
        return timelineOf(todayEvents).state();
    }

    private ShiftTimeline timelineOf(List<ShiftEvent> events) {
        return ShiftTimeline.of(events.stream()
                .map(e -> new ShiftTimeline.Event(e.getEventType(), e.getOccurredAt()))
                .toList());
    }

    private List<com.ecobank.rccportal.dto.ShiftAbsenceResponse> absencesOf(ShiftTimeline timeline, LocalDateTime now) {
        return timeline.absences().stream()
                .map(a -> new com.ecobank.rccportal.dto.ShiftAbsenceResponse(a.disconnectedAt(), a.reconnectedAt(), a.minutes(now)))
                .toList();
    }

    private User findUser(String username) {
        return userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    /**
     * Shift d'une équipe pour un jour donné — ouvert à tout utilisateur connecté. Sans
     * paramètre "team", scope automatiquement sur SA PROPRE équipe (un conseiller ne voit que
     * la sienne) ; QA/Admin/RH peuvent préciser n'importe quelle équipe.
     */
    @Transactional(readOnly = true)
    public List<ShiftEventResponse> forTeam(String username, LocalDate date, String team) {
        String targetTeam = team;
        if (targetTeam == null || targetTeam.isBlank()) {
            User caller = userRepository.findFirstByUsernameIgnoreCase(username)
                    .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
            targetTeam = caller.getActivity();
            if (targetTeam == null || targetTeam.isBlank()) return List.of(); // pas d'équipe renseignée
        }
        final String team2 = targetTeam;
        return excludingTeamLeaders(forDate(date).stream()
                .filter(e -> team2.equalsIgnoreCase(e.activity()))
                .toList());
    }

    /** Vue globale QA/admin — tous les agents, sur une plage de dates (vues semaine/mois de Suivi de shift). */
    @Transactional(readOnly = true)
    public List<ShiftEventResponse> forRangeAll(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return excludingTeamLeaders(shiftEventRepository.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(start, end).stream()
                .map(this::toResponse)
                .toList());
    }

    /** Même principe que forTeam, sur une plage de dates — vues semaine/mois de l'onglet Équipe. */
    @Transactional(readOnly = true)
    public List<ShiftEventResponse> forTeamRange(String username, LocalDate from, LocalDate to, String team) {
        String targetTeam = team;
        if (targetTeam == null || targetTeam.isBlank()) {
            User caller = userRepository.findFirstByUsernameIgnoreCase(username)
                    .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
            targetTeam = caller.getActivity();
            if (targetTeam == null || targetTeam.isBlank()) return List.of();
        }
        final String team2 = targetTeam;
        return forRangeAll(from, to).stream()
                .filter(e -> team2.equalsIgnoreCase(e.activity()))
                .toList();
    }

    /**
     * Référence pour le taux de remplissage exporté — un shift standard de 8h. Faute de durée
     * planifiée par agent facilement exploitable ici, ce dénominateur fixe donne un pourcentage
     * de repère (comme demandé) plutôt qu'un export sans aucun taux.
     */
    private static final int REFERENCE_SHIFT_MINUTES = 8 * 60;

    /**
     * Export Excel du Suivi de shift (bouton "Exporter Excel") — un onglet, une ligne par agent,
     * une colonne par jour de la période choisie (jour/semaine/mois/année), cellule = durée
     * travaillée + taux de remplissage vs un shift de 8h de référence. Réutilise forRange
     * (Moi-même) / forTeamRange (Équipe) — jamais de nouvelle requête ad hoc, mêmes données que
     * celles affichées à l'écran.
     */
    @Transactional(readOnly = true)
    public byte[] exportExcel(String username, boolean team, LocalDate from, LocalDate to, String teamCode) {
        List<ShiftEventResponse> events = team
                ? forTeamRange(username, from, to, teamCode)
                : forRange(username, from, to);

        java.util.Map<String, java.util.List<ShiftEventResponse>> byUser = new java.util.LinkedHashMap<>();
        for (ShiftEventResponse e : events) {
            byUser.computeIfAbsent(e.username(), k -> new java.util.ArrayList<>()).add(e);
        }
        if (byUser.isEmpty() && !team) {
            // Vue "Moi-même" : au moins ma propre ligne, même sans aucun événement sur la période.
            byUser.put(username, new java.util.ArrayList<>());
        }

        List<LocalDate> days = new java.util.ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) days.add(d);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);

            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Suivi de shift");
            int r = 0;
            org.apache.poi.ss.usermodel.Row title = sheet.createRow(r++);
            org.apache.poi.ss.usermodel.Cell titleCell = title.createCell(0);
            titleCell.setCellValue("Suivi de shift — " + from + " au " + to);
            titleCell.setCellStyle(headerStyle);
            r++;

            org.apache.poi.ss.usermodel.Row header = sheet.createRow(r++);
            header.createCell(0).setCellValue("Agent");
            header.getCell(0).setCellStyle(headerStyle);
            for (int i = 0; i < days.size(); i++) {
                org.apache.poi.ss.usermodel.Cell c = header.createCell(i + 1);
                c.setCellValue(days.get(i).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")));
                c.setCellStyle(headerStyle);
            }

            for (var entry : byUser.entrySet()) {
                java.util.List<ShiftEventResponse> userEvents = entry.getValue();
                String fullName = userEvents.stream().map(ShiftEventResponse::userFullName)
                        .filter(java.util.Objects::nonNull).findFirst().orElse(entry.getKey());
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(fullName);

                java.util.Map<LocalDate, java.util.List<ShiftEventResponse>> byDay = new java.util.HashMap<>();
                for (ShiftEventResponse e : userEvents) {
                    LocalDate d = e.occurredAt().toLocalDate();
                    byDay.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(e);
                }

                for (int i = 0; i < days.size(); i++) {
                    java.util.List<ShiftEventResponse> dayEvents = byDay.getOrDefault(days.get(i), List.of());
                    int workedMinutes = workedMinutesForDay(dayEvents);
                    int pct = Math.min(100, Math.round(100f * workedMinutes / REFERENCE_SHIFT_MINUTES));
                    String value = workedMinutes <= 0 ? "—"
                            : (workedMinutes / 60) + "h" + String.format("%02d", workedMinutes % 60) + " (" + pct + "%)";
                    // Déconnexions en cours de shift, pour les RH / Team Leaders.
                    ShiftTimeline timeline = ShiftTimeline.of(dayEvents.stream()
                            .map(e -> new ShiftTimeline.Event(e.eventType(), e.occurredAt())).toList());
                    if (!timeline.absences().isEmpty()) {
                        long absence = timeline.absenceMinutes(days.get(i).equals(LocalDate.now()) ? LocalDateTime.now() : null);
                        value += " — " + timeline.absences().size() + " déconnexion(s)"
                                + (absence > 0 ? ", " + (absence / 60) + "h" + String.format("%02d", absence % 60) : "");
                    }
                    row.createCell(i + 1).setCellValue(value);
                }
            }

            sheet.autoSizeColumn(0);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw ApiException.serviceUnavailable("Impossible de générer le fichier Excel : " + e.getMessage());
        }
    }

    /**
     * Minutes réellement travaillées un jour donné, à partir de sa séquence d'événements — même
     * logique que buildSegments()/workedMinutesForDay() côté client (shift.js), portée en Java
     * pour l'export serveur : LOGIN/PAUSE_END/LUNCH_END ouvrent du temps de travail,
     * PAUSE_START/LUNCH_START l'interrompent, SHIFT_END le clôt.
     */
    private int workedMinutesForDay(java.util.List<ShiftEventResponse> dayEvents) {
        return (int) ShiftTimeline.of(dayEvents.stream()
                .map(e -> new ShiftTimeline.Event(e.eventType(), e.occurredAt()))
                .toList()).workedMinutes(null);
    }

    private ShiftEventResponse toResponse(ShiftEvent e) {
        User user = e.getUser();
        String service = getPrimaryService(user);
        return new ShiftEventResponse(e.getShiftEventId(), user.getUsername(), user.getName(),
                service, user.getAffiliateBranch(), user.getActivity(), e.getEventType(), e.getOccurredAt());
    }

    private String getPrimaryService(User user) {
        if (user == null || user.getId() == null) return null;
        List<UserServiceAssignment> assignments = userServiceAssignmentRepository.findServicesByUserId(user.getId());
        if (assignments == null || assignments.isEmpty() || assignments.get(0).getService() == null) return null;
        return assignments.get(0).getService().getName();
    }

    /**
     * Taux de présence sur une période : jours distincts avec une connexion réelle
     * (événement LOGIN) ÷ jours ouvrés de la période (tous les jours de semaine,
     * sans calendrier de jours fériés pour l'instant — à affiner si besoin).
     */
    @Transactional(readOnly = true)
    public double computePresenceRate(String username, java.time.LocalDate from, java.time.LocalDate to) {
        User user = findUser(username);
        long loginDays = shiftEventRepository.countDistinctLoginDays(
                user, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        long workingDays = 0;
        for (java.time.LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            java.time.DayOfWeek dow = d.getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                workingDays++;
            }
        }

        if (workingDays == 0) return 0.0;
        return Math.min(100.0, (loginDays * 100.0) / workingDays);
    }
}