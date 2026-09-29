package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.LiveShiftStatusResponse;
import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.TrainingProgress;
import com.ecobank.rccportal.model.WorkflowRequest;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.TrainingProgressRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Portail RH, données calculées côté serveur à partir des vraies sources RCC — rien n'est saisi deux fois :
 * <ul>
 *   <li><b>Indicateurs</b> (tuiles « Indicateurs RH » et « Centre de contrôle RH ») : effectif, rotation, absentéisme,
 *       formation, contrats, congés et performance de la filiale choisie ;</li>
 *   <li><b>Plannings &amp; shifts en direct</b> : pour chaque équipe, le shift planifié de chaque collaborateur et son
 *       statut réel (en poste, en pause, déconnecté, en retard, en congé…) déduit de la pointeuse.</li>
 * </ul>
 */
@Service
public class HrLiveService {

    public record Indicators(String country, String month, int headcount, int active, int inactive, int toAssign,
                             int departures12m, int departuresMonth, Double turnoverRate,
                             int onLeaveToday, int absentToday, Double absenteeismRate,
                             int trainedPeople, Double trainingRate, Double avgTrainingProgress,
                             int contracts, int contractsExpiring30, int contractsExpired, int contractsMissing,
                             int leavePending, int leaveApprovedMonth,
                             Double performanceAvg, int performanceMeasured, LocalDateTime generatedAt) {}

    public record LivePerson(Long userId, String name, String username, String role, String shiftCode, String shiftLabel,
                             LocalTime start, LocalTime end, String status, LocalDateTime statusSince, long absenceMinutes,
                             List<String> week) {}

    public record LiveTeam(String population, String team, String label, Map<String, Integer> counts, List<LivePerson> people) {}

    public record ShiftSlot(String code, String label, LocalTime start, LocalTime end, int planned, int present) {}

    public record LivePlanning(String country, LocalDate date, boolean today, LocalDateTime generatedAt, Map<String, Integer> summary,
                               List<ShiftSlot> shifts, List<LocalDate> weekDays, List<LiveTeam> teams) {}

    /** Statuts affichés, dans l'ordre des compteurs. */
    public static final List<String> STATUSES = List.of("EN_POSTE", "EN_PAUSE", "DECONNECTE", "EN_RETARD", "A_VENIR", "TERMINE",
            "NON_POINTE", "PLANIFIE", "CONGE", "ABSENT", "REPOS", "NON_PLANIFIE");

    static final Set<String> ABSENT_CODES = Set.of("ABS", "AB", "ABSENT", "MAL", "MALADIE", "AM");
    static final Set<String> REST_CODES = Set.of("OFF", "RM", "R", "REPOS", "RH", "RE", "RC");
    static final Set<String> LEAVE_CODES = Set.of("C", "CP", "CONGE", "CONGES", "CA");

    private final HrOrganizationService hr;
    private final AgentScheduleRepository schedules;
    private final ShiftService shifts;
    private final WorkflowRequestRepository requests;
    private final TrainingProgressRepository training;
    private final JdbcTemplate jdbc;

    public HrLiveService(HrOrganizationService hr, AgentScheduleRepository schedules, ShiftService shifts,
                         WorkflowRequestRepository requests, TrainingProgressRepository training, JdbcTemplate jdbc) {
        this.hr = hr;
        this.schedules = schedules;
        this.shifts = shifts;
        this.requests = requests;
        this.training = training;
        this.jdbc = jdbc;
    }

    private static void requireRead(AuthenticatedUser u) {
        if (!HrOrganizationService.canRead(u)) throw ApiException.forbidden("Réservé aux Ressources Humaines, au Superviseur et à l'administrateur.");
    }

    // ── Indicateurs ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Indicators indicators(AuthenticatedUser requester, String countryParam, String month) {
        requireRead(requester);
        String country = HrOrganizationService.normalizeCountry(countryParam);
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        LocalDate today = LocalDate.now();
        List<HrOrganizationService.Person> people = hr.people(country);
        List<HrOrganizationService.Person> active = people.stream().filter(HrOrganizationService.Person::active).toList();
        Set<Long> activeIds = new HashSet<>();
        Set<Long> allIds = new HashSet<>();
        for (var p : people) { allIds.add(p.userId()); if (p.active()) activeIds.add(p.userId()); }

        // Rotation : sorties enregistrées sur 12 mois glissants ÷ effectif moyen (actifs + sortis).
        int dep12 = countDepartures(country, today.minusMonths(12), today);
        int depMonth = countDepartures(country, ym.atDay(1), ym.atEndOfMonth());
        int inactive = people.size() - active.size();
        Double turnover = active.isEmpty() && dep12 == 0 ? null : pct(dep12, active.size() + dep12);

        // Absentéisme du jour : congés approuvés + absences au planning.
        Set<Long> onLeave = new HashSet<>();
        int leavePending = 0, leaveApprovedMonth = 0;
        for (WorkflowRequest r : requests.findByTypeOrderByCreatedAtDesc("LEAVE")) {
            Long uid = r.getRequestedBy() == null ? null : r.getRequestedBy().getId();
            if (uid == null || !allIds.contains(uid)) continue;
            String st = r.getStatus() == null ? "" : r.getStatus().toUpperCase(Locale.ROOT);
            if (st.equals("PENDING")) leavePending++;
            if (st.equals("APPROVED")) {
                if (covers(r, today) && activeIds.contains(uid)) onLeave.add(uid);
                if (r.getPeriodFrom() != null && r.getPeriodTo() != null
                        && !r.getPeriodTo().isBefore(ym.atDay(1)) && !r.getPeriodFrom().isAfter(ym.atEndOfMonth())) leaveApprovedMonth++;
            }
        }
        Set<Long> absent = new HashSet<>();
        for (AgentSchedule s : schedules.findByWorkDate(today)) {
            Long uid = s.getUser() == null ? null : s.getUser().getId();
            if (uid == null || !activeIds.contains(uid) || onLeave.contains(uid)) continue;
            String code = norm(s.getShiftCode());
            if (LEAVE_CODES.contains(code)) onLeave.add(uid);
            else if (ABSENT_CODES.contains(code)) absent.add(uid);
        }
        Double absenteeism = active.isEmpty() ? null : pct(onLeave.size() + absent.size(), active.size());

        // Formation : part des actifs ayant terminé au moins une leçon, et progression moyenne des parcours commencés.
        Map<Long, List<TrainingProgress>> progress = new HashMap<>();
        try {
            for (TrainingProgress tp : training.findAll()) {
                if (tp.getUserId() != null && activeIds.contains(tp.getUserId())) progress.computeIfAbsent(tp.getUserId(), k -> new ArrayList<>()).add(tp);
            }
        } catch (RuntimeException ignored) {
            // module formation indisponible : indicateurs à vide
        }
        int trained = 0;
        List<Double> perPerson = new ArrayList<>();
        for (List<TrainingProgress> list : progress.values()) {
            if (list.stream().anyMatch(tp -> Boolean.TRUE.equals(tp.getCompleted()))) trained++;
            perPerson.add(list.stream().mapToDouble(HrLiveService::progressOf).average().orElse(0));
        }
        Double trainingRate = active.isEmpty() ? null : pct(trained, active.size());
        Double avgProgress = perPerson.isEmpty() ? null : round1(perPerson.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        // Contrats.
        int expiring = 0, expired = 0, missing = 0;
        for (var p : active) {
            if (p.contractType() == null || p.contractType().isBlank()) missing++;
            if (p.contractEnd() != null) {
                if (p.contractEnd().isBefore(today)) expired++;
                else if (!p.contractEnd().isAfter(today.plusDays(30))) expiring++;
            }
        }

        // Performance : moyenne des agents mesurés dans le reporting du mois.
        Double perf = null;
        int measured = 0;
        try {
            double sum = 0;
            for (var t : hr.performance(requester, country, ym.toString()).teams()) {
                for (var a : t.agents()) if (a.performance() != null) { sum += a.performance(); measured++; }
            }
            perf = measured == 0 ? null : round1(sum / measured);
        } catch (RuntimeException ignored) {
            // reporting indisponible
        }

        int toAssign = (int) active.stream().filter(p -> p.team() == null).count();
        return new Indicators(country, ym.toString(), people.size(), active.size(), inactive, toAssign, dep12, depMonth, turnover,
                onLeave.size(), absent.size(), absenteeism, trained, trainingRate, avgProgress,
                active.size(), expiring, expired, missing, leavePending, leaveApprovedMonth, perf, measured, LocalDateTime.now());
    }

    private int countDepartures(String country, LocalDate from, LocalDate to) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.HrDepartures WHERE CountryCode = ? AND ReintegratedAt IS NULL "
                    + "AND DepartureDate BETWEEN ? AND ?", Integer.class, country, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return 0; // table pas encore créée
        }
    }

    static double progressOf(TrainingProgress tp) {
        if (Boolean.TRUE.equals(tp.getCompleted())) return 100;
        int s = tp.getScrollPercent() == null ? 0 : tp.getScrollPercent();
        int v = tp.getVideoWatchedPercent() == null ? 0 : tp.getVideoWatchedPercent();
        return Math.max(0, Math.min(100, Math.max(s, v)));
    }

    private static boolean covers(WorkflowRequest r, LocalDate d) {
        return r.getPeriodFrom() != null && r.getPeriodTo() != null && !d.isBefore(r.getPeriodFrom()) && !d.isAfter(r.getPeriodTo());
    }

    static Double pct(int part, int total) {
        return total <= 0 ? null : round1(part * 100.0 / total);
    }

    static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static String norm(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    // ── Plannings & shifts en direct ─────────────────────────────────────

    @Transactional(readOnly = true)
    public LivePlanning livePlanning(AuthenticatedUser requester, String countryParam, LocalDate dateParam) {
        requireRead(requester);
        String country = HrOrganizationService.normalizeCountry(countryParam);
        LocalDate date = dateParam == null ? LocalDate.now() : dateParam;
        boolean isToday = date.equals(LocalDate.now());
        LocalDateTime now = LocalDateTime.now();
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<LocalDate> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) week.add(monday.plusDays(i));

        List<HrOrganizationService.Person> people = hr.people(country).stream().filter(HrOrganizationService.Person::active).toList();
        Set<Long> ids = new HashSet<>();
        people.forEach(p -> ids.add(p.userId()));

        Map<Long, Map<LocalDate, AgentSchedule>> plan = new HashMap<>();
        for (AgentSchedule s : schedules.findByWorkDateBetween(monday, monday.plusDays(6))) {
            if (s.getUser() == null || !ids.contains(s.getUser().getId())) continue;
            if ("REJECTED".equalsIgnoreCase(s.getApprovalStatus())) continue;
            plan.computeIfAbsent(s.getUser().getId(), k -> new HashMap<>()).put(s.getWorkDate(), s);
        }
        Set<Long> onLeave = new HashSet<>();
        for (WorkflowRequest r : requests.findByTypeAndStatus("LEAVE", "APPROVED")) {
            if (r.getRequestedBy() != null && ids.contains(r.getRequestedBy().getId()) && covers(r, date)) onLeave.add(r.getRequestedBy().getId());
        }
        Map<String, LiveShiftStatusResponse> live = isToday ? shifts.liveStatusByUsername() : Map.of();

        Map<String, LiveTeam> teams = new LinkedHashMap<>();
        for (var pop : HrOrganizationService.POPULATIONS) {
            for (var t : pop.teams()) {
                teams.put(pop.code() + "|" + t.code(), new LiveTeam(pop.code(), t.code(), HrOrganizationService.teamLabel(pop.code(), t.code()),
                        new LinkedHashMap<>(), new ArrayList<>()));
            }
        }
        LiveTeam unassigned = new LiveTeam(null, null, "À placer", new LinkedHashMap<>(), new ArrayList<>());
        Map<String, int[]> slotCounts = new LinkedHashMap<>();
        Map<String, AgentSchedule> slotSample = new HashMap<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        STATUSES.forEach(s -> summary.put(s, 0));

        for (var p : people) {
            Map<LocalDate, AgentSchedule> mine = plan.getOrDefault(p.userId(), Map.of());
            AgentSchedule s = mine.get(date);
            LiveShiftStatusResponse st = p.username() == null ? null : live.get(p.username().toLowerCase(Locale.ROOT));
            String status = statusOf(s, onLeave.contains(p.userId()), st, isToday, date, now);
            List<String> weekCodes = new ArrayList<>();
            for (LocalDate d : week) {
                AgentSchedule w = mine.get(d);
                weekCodes.add(w == null ? null : norm(w.getShiftCode()));
            }
            LivePerson lp = new LivePerson(p.userId(), p.name(), p.username(), p.role(),
                    s == null ? null : norm(s.getShiftCode()), s == null ? null : s.getShiftLabel(),
                    s == null ? null : s.getPlannedStartTime(), s == null ? null : s.getPlannedEndTime(),
                    status, st == null ? null : st.since(), st == null ? 0 : st.absenceMinutesToday(), weekCodes);
            LiveTeam team = p.team() == null ? unassigned : teams.getOrDefault(p.population() + "|" + p.team(), unassigned);
            team.people().add(lp);
            team.counts().merge(status, 1, Integer::sum);
            summary.merge(status, 1, Integer::sum);
            if (s != null && s.getPlannedStartTime() != null && isWorkingCode(lp.shiftCode()) && !onLeave.contains(p.userId())) {
                int[] c = slotCounts.computeIfAbsent(lp.shiftCode(), k -> new int[2]);
                c[0]++;
                if (Set.of("EN_POSTE", "EN_PAUSE", "DECONNECTE", "TERMINE").contains(status)) c[1]++;
                slotSample.putIfAbsent(lp.shiftCode(), s);
            }
        }
        List<ShiftSlot> slots = new ArrayList<>();
        slotCounts.forEach((code, c) -> {
            AgentSchedule s = slotSample.get(code);
            slots.add(new ShiftSlot(code, s.getShiftLabel(), s.getPlannedStartTime(), s.getPlannedEndTime(), c[0], c[1]));
        });
        slots.sort(Comparator.comparing(ShiftSlot::start, Comparator.nullsLast(Comparator.naturalOrder())));

        List<LiveTeam> out = new ArrayList<>();
        for (LiveTeam t : teams.values()) if (!t.people().isEmpty()) out.add(sortPeople(t));
        if (!unassigned.people().isEmpty()) out.add(sortPeople(unassigned));
        return new LivePlanning(country, date, isToday, now, summary, slots, week, out);
    }

    private static LiveTeam sortPeople(LiveTeam t) {
        t.people().sort(Comparator.comparing((LivePerson p) -> STATUSES.indexOf(p.status()))
                .thenComparing(p -> p.start() == null ? LocalTime.MAX : p.start())
                .thenComparing(LivePerson::name, String.CASE_INSENSITIVE_ORDER));
        return t;
    }

    static boolean isWorkingCode(String code) {
        String c = norm(code);
        return !c.isEmpty() && !ABSENT_CODES.contains(c) && !REST_CODES.contains(c) && !LEAVE_CODES.contains(c);
    }

    /** Statut d'un collaborateur pour la journée affichée — planning + pointeuse (aujourd'hui) + congés. */
    static String statusOf(AgentSchedule s, boolean onLeave, LiveShiftStatusResponse live, boolean isToday, LocalDate date, LocalDateTime now) {
        String code = s == null ? "" : norm(s.getShiftCode());
        if (onLeave || LEAVE_CODES.contains(code)) return "CONGE";
        if (ABSENT_CODES.contains(code)) return "ABSENT";
        String state = live == null ? null : live.currentState();
        if (isToday && state != null && !"NOT_STARTED".equals(state)) {
            switch (state) {
                case "WORKING": return "EN_POSTE";
                case "ON_PAUSE": case "ON_LUNCH": case "ON_TRAINING": case "ON_MEETING": return "EN_PAUSE";
                case "DISCONNECTED": return "DECONNECTE";
                case "SHIFT_ENDED": return "TERMINE";
                default: break;
            }
        }
        if (REST_CODES.contains(code)) return "REPOS";
        if (s == null || s.getPlannedStartTime() == null) return code.isEmpty() ? "NON_PLANIFIE" : "PLANIFIE";
        if (!isToday) return "PLANIFIE";
        LocalDateTime start = date.atTime(s.getPlannedStartTime());
        LocalDateTime end = s.getPlannedEndTime() == null ? start.plusHours(8) : date.atTime(s.getPlannedEndTime());
        if (!end.isAfter(start)) end = end.plusDays(1);
        if (now.isBefore(start)) return "A_VENIR";
        if (now.isAfter(end)) return "NON_POINTE";
        return "EN_RETARD";
    }
}
