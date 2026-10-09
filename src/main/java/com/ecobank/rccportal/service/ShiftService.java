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

    /** Planning du jour : fin prévue du shift (débordement, clôture automatique). Facultatif pour les tests. */
    private com.ecobank.rccportal.repository.AgentScheduleRepository schedules;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSchedules(com.ecobank.rccportal.repository.AgentScheduleRepository schedules) {
        this.schedules = schedules;
    }

    @org.springframework.beans.factory.annotation.Value("${rcc.shift.default-duration-hours:9}")
    private int defaultShiftHours = com.ecobank.rccportal.util.ShiftOverflow.DEFAULT_SHIFT_HOURS;

    /** Plannings du jour, par identifiant d'utilisateur (une seule requête pour les vues « En direct »). */
    private java.util.Map<Long, com.ecobank.rccportal.model.AgentSchedule> todaySchedules() {
        java.util.Map<Long, com.ecobank.rccportal.model.AgentSchedule> out = new java.util.HashMap<>();
        if (schedules == null) return out;
        try {
            for (var s : schedules.findByWorkDate(LocalDate.now())) {
                if (s.getUser() == null || "REJECTED".equalsIgnoreCase(s.getApprovalStatus())) continue;
                out.put(s.getUser().getId(), s);
            }
        } catch (RuntimeException e) {
            log.debug("Planning du jour indisponible : {}", e.getMessage());
        }
        return out;
    }

    /**
     * Fin prévue du shift : planning du jour où le shift a commencé (code de shift travaillé ; un shift de nuit
     * finit le lendemain), sinon première connexion + durée par défaut — aussi pour un agent connecté un jour de
     * repos ou de congé, ou connecté après la fin prévue, pour que son shift soit toujours clôturé.
     */
    LocalDateTime plannedEndOf(com.ecobank.rccportal.model.AgentSchedule s, List<ShiftEvent> shiftEvents) {
        LocalDateTime firstLogin = shiftEvents.stream().filter(e -> "LOGIN".equals(e.getEventType()))
                .map(ShiftEvent::getOccurredAt).findFirst()
                .orElse(shiftEvents.isEmpty() ? null : shiftEvents.get(0).getOccurredAt());
        LocalDate day = firstLogin != null ? firstLogin.toLocalDate() : LocalDate.now();
        if (s != null && s.getShiftCode() != null && !s.getShiftCode().isBlank()
                && HrLiveService.isWorkingCode(s.getShiftCode()) && s.getPlannedEndTime() != null) {
            LocalDateTime end = com.ecobank.rccportal.util.ShiftOverflow.plannedEnd(day, s.getPlannedStartTime(), s.getPlannedEndTime(), firstLogin, defaultShiftHours);
            if (firstLogin == null || end.isAfter(firstLogin)) return end;
        }
        return com.ecobank.rccportal.util.ShiftOverflow.plannedEnd(day, null, null, firstLogin, defaultShiftHours);
    }

    /** Planning de l'agent pour un jour donné (null : aucun, ou planning indisponible). */
    private com.ecobank.rccportal.model.AgentSchedule scheduleOf(User user, LocalDate day) {
        if (schedules == null || user == null) return null;
        try {
            return schedules.findByUserAndWorkDate(user, day)
                    .filter(s -> !"REJECTED".equalsIgnoreCase(s.getApprovalStatus())).orElse(null);
        } catch (RuntimeException ignored) {
            return null; // planning indisponible : durée par défaut
        }
    }

    private LocalDateTime plannedEndOf(User user, List<ShiftEvent> shiftEvents) {
        LocalDate day = shiftEvents.isEmpty() ? LocalDate.now() : shiftEvents.get(0).getOccurredAt().toLocalDate();
        return plannedEndOf(scheduleOf(user, day), shiftEvents);
    }

    /** Début prévu du shift travaillé du jour au planning, null pour un repos, un congé ou sans planning. */
    private static LocalTime plannedStartOf(com.ecobank.rccportal.model.AgentSchedule s) {
        if (s == null || s.getShiftCode() == null || !HrLiveService.isWorkingCode(s.getShiftCode())) return null;
        return s.getPlannedStartTime();
    }

    /** Shift terminé : la prochaine connexion ouvre-t-elle un nouveau shift (planning du jour, voir ShiftSession) ? */
    private boolean newShiftDue(List<ShiftEvent> session, com.ecobank.rccportal.model.AgentSchedule today, LocalDateTime now) {
        if (session.isEmpty()) return true;
        return com.ecobank.rccportal.util.ShiftSession.mayStartNewShift(session.get(0).getOccurredAt(), now, plannedStartOf(today));
    }

    /**
     * Shift affiché : un shift terminé reste affiché « Shift terminé » jusqu'à l'ouverture du shift suivant du
     * planning ; ensuite l'agent repart de « Shift non démarré ».
     */
    private List<ShiftEvent> displayed(List<ShiftEvent> session, com.ecobank.rccportal.model.AgentSchedule today, LocalDateTime now) {
        if (ShiftTimeline.SHIFT_ENDED.equals(timelineOf(session).state()) && newShiftDue(session, today, now)) return List.of();
        return session;
    }

    /**
     * Clôture automatique des shifts restés ouverts 1 h 30 après leur fin prévue (oubli de « Fin de shift ») :
     * un événement SHIFT_END est posé à fin prévue + 1 h 30 — le temps compté s'arrête là. Appelé chaque
     * minute par ShiftOverflowJob. Renvoie le nombre de shifts clôturés.
     */
    @Transactional
    public int autoCloseOverflows() {
        LocalDateTime now = LocalDateTime.now();
        int closed = 0;
        for (List<ShiftEvent> all : recentEventsByUser(now).values()) {
            List<ShiftEvent> events = currentShift(all);
            if (events.isEmpty()) continue;
            User user = events.get(0).getUser();
            String state = timelineOf(events).state();
            if (!com.ecobank.rccportal.util.ShiftOverflow.isActive(state)) continue;
            LocalDateTime end = plannedEndOf(user, events);
            LocalDateTime closeAt = com.ecobank.rccportal.util.ShiftOverflow.autoCloseAt(end);
            if (closeAt == null || now.isBefore(closeAt)) continue;
            LocalDateTime last = events.get(events.size() - 1).getOccurredAt();
            LocalDateTime at = closeAt.isAfter(last) ? closeAt : last.plusSeconds(1);
            shiftEventRepository.save(ShiftEvent.builder().user(user).eventType("SHIFT_END").occurredAt(at).build());
            if (user.getUsername() != null) endedCache.remove(user.getUsername().toLowerCase());
            log.info("Shift clôturé automatiquement (username={}, fin prévue={}, clôture={})", user.getUsername(), end, at);
            closed++;
        }
        return closed;
    }

    /** Usernames ayant le rôle TEAM_LEADER — un Team Leader dirige une équipe mais n'en est pas
     *  un membre à suivre : il ne doit jamais apparaître dans les vues "Équipe"/"En direct" du
     *  Suivi de shift (voir demande utilisateur), seulement dans sa propre vue "Moi-même". */
    private java.util.Set<String> teamLeaderUsernames() {
        java.util.Set<String> out = userRoleRepository.findByRoleNameIgnoreCase("TEAM_LEADER").stream()
                .map(ur -> ur.getUser().getUsername())
                .filter(java.util.Objects::nonNull)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        // Portail QA (Quality Assurance, Superviseur QA, Formateur) : pas de shift à suivre.
        out.addAll(qaStaffUsernames(userServiceAssignmentRepository));
        return out;
    }

    /** Services du portail QA — exemptés du suivi de shift (pas de planning d'appels). */
    public static final java.util.Set<String> QA_SERVICE_CODES = java.util.Set.of("QUALITY_ASSURANCE", "SUPERVISEUR_QA", "FORMATEUR");

    public static java.util.Set<String> qaStaffUsernames(com.ecobank.rccportal.repository.UserServiceAssignmentRepository repo) {
        if (repo == null) return java.util.Set.of();
        try {
            return repo.findAll().stream()
                    .filter(a -> a.getService() != null && a.getService().getCode() != null
                            && QA_SERVICE_CODES.contains(a.getService().getCode().toUpperCase()))
                    .map(a -> a.getUser() != null ? a.getUser().getUsername() : null)
                    .filter(java.util.Objects::nonNull)
                    .map(String::toLowerCase)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    private boolean isQaStaff(User user) {
        if (user == null || user.getId() == null) return false;
        try {
            return userServiceAssignmentRepository.findByUserId(user.getId()).stream()
                    .anyMatch(a -> a.getService() != null && a.getService().getCode() != null
                            && QA_SERVICE_CODES.contains(a.getService().getCode().toUpperCase()));
        } catch (Exception e) {
            return false;
        }
    }

    private List<ShiftEventResponse> excludingTeamLeaders(List<ShiftEventResponse> events) {
        java.util.Set<String> leaders = teamLeaderUsernames();
        if (leaders.isEmpty()) return events;
        return events.stream()
                .filter(e -> e.username() == null || !leaders.contains(e.username().toLowerCase()))
                .toList();
    }

    /**
     * Connexion (« Se connecter ») : reprend le shift en cours, ou ouvre le nouveau shift du planning. Après un
     * shift terminé (fin de shift, ou clôture automatique avec déconnexion), une connexion pour ce même shift
     * n'en ouvre pas un autre : le shift suivant commence selon le planning (voir ShiftSession).
     */
    @Transactional
    public void recordLogin(User user) {
        if (isQaStaff(user)) return; // portail QA : aucun shift enregistré
        List<ShiftEvent> session = shiftEventsFor(user);
        if (ShiftTimeline.SHIFT_ENDED.equals(timelineOf(session).state())
                && !newShiftDue(session, scheduleOf(user, LocalDate.now()), LocalDateTime.now())) {
            log.info("Connexion après la fin du shift (username={}) : pas de nouveau shift avant le prochain shift du planning", user.getUsername());
            return;
        }
        saveEvent(user, "LOGIN");
    }

    /**
     * Agent déjà connecté quand son shift suivant s'ouvre (connecté plus de 2 h avant son début) : le shift
     * démarre à l'ouverture d'une page du portail, selon son planning.
     */
    @Transactional
    public void openPlannedShiftIfDue(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
        if (user == null || isQaStaff(user)) return;
        List<ShiftEvent> session = shiftEventsFor(user);
        if (ShiftTimeline.SHIFT_ENDED.equals(timelineOf(session).state())
                && newShiftDue(session, scheduleOf(user, LocalDate.now()), LocalDateTime.now())) {
            saveEvent(user, "LOGIN");
        }
    }

    /** username (minuscules) → {heure de fin du shift terminé (null : shift en cours), lu à} — cache court. */
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> endedCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Heure de fin du dernier shift si l'agent n'a plus de shift en cours (fin de shift ou clôture automatique),
     * sinon null. Une session ouverte avant cette heure est déconnectée (voir JwtAuthenticationFilter).
     */
    public LocalDateTime shiftEndedAt(String username) {
        if (username == null) return null;
        String key = username.toLowerCase();
        Object[] c = endedCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - (long) c[1] < 30_000) return (LocalDateTime) c[0];
        LocalDateTime end = null;
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
        if (user != null) {
            List<ShiftEvent> session = shiftEventsFor(user);
            if (!session.isEmpty() && "SHIFT_END".equals(session.get(session.size() - 1).getEventType())) {
                end = session.get(session.size() - 1).getOccurredAt();
            }
        }
        endedCache.put(key, new Object[]{end, now});
        return end;
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
        String state = timelineOf(shiftEventsFor(user)).state();
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

        List<ShiftEvent> session = shiftEventsFor(user);
        String currentState = computeState(session);
        if (ShiftTimeline.SHIFT_ENDED.equals(currentState) && newShiftDue(session, scheduleOf(user, LocalDate.now()), LocalDateTime.now())) {
            saveEvent(user, "LOGIN"); // shift suivant du planning
            currentState = computeState(shiftEventsFor(user));
        }
        if (ShiftTimeline.DISCONNECTED.equals(currentState)) {
            // Action depuis une session encore ouverte (autre onglet) après une déconnexion :
            // c'est une reconnexion de fait — on la trace avant d'appliquer l'action.
            saveEvent(user, "LOGIN");
            currentState = computeState(shiftEventsFor(user));
        }
        List<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(currentState, List.of());

        if (!allowed.contains(eventType)) {
            throw ApiException.badRequest(
                    "Action non autorisée depuis l'état actuel (" + currentState + ").");
        }

        saveEvent(user, eventType);
        endedCache.remove(user.getUsername() == null ? "" : user.getUsername().toLowerCase());
        return getStatus(username);
    }

    @Transactional(readOnly = true)
    public ShiftStatusResponse getStatus(String username) {
        User user = findUser(username);
        LocalDateTime now = LocalDateTime.now();
        List<ShiftEvent> events = displayed(shiftEventsFor(user), scheduleOf(user, LocalDate.now()), now);
        ShiftTimeline timeline = timelineOf(events);
        List<ShiftEventResponse> responses = events.stream().map(this::toResponse).toList();
        LocalDateTime plannedEnd = com.ecobank.rccportal.util.ShiftOverflow.isActive(timeline.state()) ? plannedEndOf(user, events) : null;
        return new ShiftStatusResponse(timeline.state(), responses, timeline.stateSince(),
                timeline.lastDisconnectedAt(), timeline.lastReconnectedAt(),
                timeline.absenceMinutes(now), timeline.absenceMinutesInCurrentState(now),
                absencesOf(timeline, now), plannedEnd, com.ecobank.rccportal.util.ShiftOverflow.autoCloseAt(plannedEnd));
    }

    /** Statut en direct de chaque agent (déduit du dernier événement du jour) — pour le
     *  bouton "En direct" du suivi de shift (RH/Superviseur/QA/Admin/Team Leader). Un Team
     *  Leader dirige une équipe mais n'en est pas un membre à suivre : exclu de cette liste
     *  (voir teamLeaderUsernames), quelle que soit l'équipe qui consulte. */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.LiveShiftStatusResponse> liveStatusForAllUsers() {
        java.util.Set<String> leaders = teamLeaderUsernames();
        java.util.Map<Long, com.ecobank.rccportal.model.AgentSchedule> plan = todaySchedules();
        List<com.ecobank.rccportal.dto.LiveShiftStatusResponse> out = new java.util.ArrayList<>();
        for (User user : userRepository.findAll()) {
            if (user.getUsername() != null && leaders.contains(user.getUsername().toLowerCase())) continue;
            LocalDateTime now = LocalDateTime.now();
            List<ShiftEvent> events = displayed(shiftEventsFor(user), plan.get(user.getId()), now);
            ShiftTimeline timeline = timelineOf(events);
            String team = com.ecobank.rccportal.util.TeamClassifier.classify(user.getActivity()).name();
            out.add(new com.ecobank.rccportal.dto.LiveShiftStatusResponse(
                    user.getUsername(), user.getName() != null ? user.getName() : user.getUsername(), team,
                    timeline.state(), timeline.stateSince(), timeline.lastDisconnectedAt(),
                    timeline.lastReconnectedAt(), timeline.absenceMinutes(now), timeline.absences().size(),
                    com.ecobank.rccportal.util.ShiftOverflow.isActive(timeline.state()) ? plannedEndOf(user, events) : null));
        }
        return out;
    }

    /**
     * Statut en direct de tous ceux qui ont pointé aujourd'hui, en une seule requête (clé : identifiant en
     * minuscules) — pour la vue « Plannings &amp; shifts en direct » du portail RH.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, com.ecobank.rccportal.dto.LiveShiftStatusResponse> liveStatusByUsername() {
        LocalDateTime now = LocalDateTime.now();
        java.util.Map<Long, com.ecobank.rccportal.model.AgentSchedule> plan = todaySchedules();
        java.util.Map<String, com.ecobank.rccportal.dto.LiveShiftStatusResponse> out = new java.util.HashMap<>();
        recentEventsByUser(now).forEach((username, all) -> {
            User user = all.get(0).getUser();
            List<ShiftEvent> events = displayed(currentShift(all), plan.get(user.getId()), now);
            if (events.isEmpty()) return; // shift précédent terminé, shift du jour pas encore commencé
            ShiftTimeline t = timelineOf(events);
            out.put(username, new com.ecobank.rccportal.dto.LiveShiftStatusResponse(user.getUsername(),
                    user.getName() != null ? user.getName() : user.getUsername(), null, t.state(), t.stateSince(),
                    t.lastDisconnectedAt(), t.lastReconnectedAt(), t.absenceMinutes(now), t.absences().size(),
                    com.ecobank.rccportal.util.ShiftOverflow.isActive(t.state()) ? plannedEndOf(user, events) : null));
        });
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
    /** Dépassement de pause (pause > 15 min, déjeuner > 60 min) : agent, jour, type, durée réelle et durée autorisée. */
    public record PauseOverrun(String username, LocalDate date, String type, LocalDateTime start, long minutes, long allowed) {}

    /** Tous les dépassements de pause d'une période (une requête), pour les alertes de shift du Team Leader. */
    @Transactional(readOnly = true)
    public List<PauseOverrun> pauseOverruns(LocalDate from, LocalDate to) {
        List<PauseOverrun> out = new java.util.ArrayList<>();
        java.util.Map<String, Object[]> open = new java.util.HashMap<>(); // username → {type, start}
        for (ShiftEvent e : shiftEventRepository.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
            String u;
            try {
                u = e.getUser() == null ? null : e.getUser().getUsername();
            } catch (jakarta.persistence.EntityNotFoundException orphan) {
                continue; // pointage d'un compte supprimé
            }
            if (u == null || e.getEventType() == null) continue;
            switch (e.getEventType()) {
                case "PAUSE_START" -> open.put(u, new Object[]{"PAUSE", e.getOccurredAt()});
                case "LUNCH_START" -> open.put(u, new Object[]{"LUNCH", e.getOccurredAt()});
                case "PAUSE_END", "LUNCH_END" -> {
                    Object[] o = open.remove(u);
                    if (o == null || !e.getEventType().startsWith((String) o[0])) break;
                    LocalDateTime start = (LocalDateTime) o[1];
                    long minutes = java.time.Duration.between(start, e.getOccurredAt()).toMinutes();
                    long allowed = "PAUSE".equals(o[0]) ? PAUSE_OVERRUN_MINUTES : LUNCH_OVERRUN_MINUTES;
                    if (minutes > allowed) out.add(new PauseOverrun(u, start.toLocalDate(), (String) o[0], start, minutes, allowed));
                }
                default -> { }
            }
        }
        return out;
    }

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

    /**
     * Événements du shift en cours (ou du dernier shift terminé) : depuis la dernière « Fin de shift », et non
     * depuis minuit — un shift de nuit reste un seul shift (voir ShiftSession).
     */
    private List<ShiftEvent> shiftEventsFor(User user) {
        LocalDateTime now = LocalDateTime.now();
        return currentShift(shiftEventRepository.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(user,
                now.minusHours(com.ecobank.rccportal.util.ShiftSession.LOOKBACK_HOURS), now.plusDays(1)));
    }

    private static List<ShiftEvent> currentShift(List<ShiftEvent> events) {
        return com.ecobank.rccportal.util.ShiftSession.current(events, ShiftEvent::getEventType);
    }

    /** Pointages récents de tous les agents (fenêtre d'un shift de nuit), par identifiant en minuscules. */
    private java.util.Map<String, List<ShiftEvent>> recentEventsByUser(LocalDateTime now) {
        java.util.Map<String, List<ShiftEvent>> byUser = new java.util.LinkedHashMap<>();
        for (ShiftEvent e : shiftEventRepository.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(
                now.minusHours(com.ecobank.rccportal.util.ShiftSession.LOOKBACK_HOURS), now.plusDays(1))) {
            if (e.getUser() == null || e.getUser().getUsername() == null) continue;
            byUser.computeIfAbsent(e.getUser().getUsername().toLowerCase(), k -> new java.util.ArrayList<>()).add(e);
        }
        return byUser;
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