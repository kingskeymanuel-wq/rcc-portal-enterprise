package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PlanningComplianceResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Alertes de shift — absences, dépassements de pause, débordements (production au-delà de la fin prévue) —
 * que le Team Leader justifie agent par agent. Le Superviseur (et RH, admin) les consulte, justifiées ou non.
 * Les incidents sont calculés à partir de la pointeuse et du planning ; seules les justifications sont stockées
 * (dbo.ShiftIncidentJustifications, une par agent, jour et type).
 */
@Service
public class ShiftIncidentService {

    /** Débordement signalé au-delà de 15 min après la fin prévue (même tolérance que le départ anticipé). */
    static final int OVERFLOW_TOLERANCE_MINUTES = 15;
    public static final List<String> TYPES = List.of("ABSENCE", "DEBORDEMENT", "PAUSE");
    public static final List<String> REASONS = List.of("Maladie / raison médicale", "Congé ou absence autorisée non saisi",
            "Incident technique (poste, réseau, téléphonie)", "Client en ligne / pic d'appels", "Réunion, formation ou mission",
            "Oubli de pointage (fin de shift, pause)", "Raison personnelle validée", "Absence injustifiée", "Autre");

    public record Justification(String reason, String comment, String by, LocalDateTime at) {}

    public record Incident(String key, LocalDate date, String username, String name, String team, String type,
                           long minutes, String detail, boolean ongoing, Justification justification) {}

    public record Summary(LocalDate from, LocalDate to, int total, int toJustify, Map<String, Integer> byType, List<Incident> incidents) {}

    private final PlanningComplianceService compliance;
    private final ShiftService shifts;
    private final JdbcTemplate jdbc;
    private volatile boolean schemaReady;

    public ShiftIncidentService(PlanningComplianceService compliance, ShiftService shifts, JdbcTemplate jdbc) {
        this.compliance = compliance;
        this.shifts = shifts;
        this.jdbc = jdbc;
    }

    // ── Lecture ──────────────────────────────────────────────────────────

    /** Incidents de la période, dans le périmètre de l'utilisateur (Team Leader : son équipe ; Superviseur, RH, admin : tout). */
    public Summary incidents(AuthenticatedUser requester, LocalDate from, LocalDate to, String team) {
        LocalDate today = LocalDate.now();
        LocalDate end = to == null || to.isAfter(today) ? today : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) start = end;
        if (Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() > 62) start = end.minusDays(62);

        List<PlanningComplianceResponse> rows = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) rows.addAll(compliance.forDateScoped(requester, d, blank(team) ? null : team));
        List<ShiftService.PauseOverrun> pauses = shifts.pauseOverruns(start, end);
        List<Incident> list = detect(rows, pauses, LocalDateTime.now());
        Map<String, Justification> just = justifications(start, end);
        List<Incident> out = new ArrayList<>();
        for (Incident i : list) out.add(new Incident(i.key(), i.date(), i.username(), i.name(), i.team(), i.type(), i.minutes(), i.detail(), i.ongoing(), just.get(i.key())));
        out.sort(Comparator.comparing((Incident i) -> i.justification() != null).thenComparing(Incident::date, Comparator.reverseOrder())
                .thenComparing(Incident::name, String.CASE_INSENSITIVE_ORDER));
        Map<String, Integer> byType = new LinkedHashMap<>();
        TYPES.forEach(t -> byType.put(t, 0));
        out.forEach(i -> byType.merge(i.type(), 1, Integer::sum));
        int toJustify = (int) out.stream().filter(i -> i.justification() == null && !i.ongoing()).count();
        return new Summary(start, end, out.size(), toJustify, byType, out);
    }

    /** Calcul pur (testable) : absences et débordements d'après le planning, dépassements de pause d'après la pointeuse. */
    static List<Incident> detect(List<PlanningComplianceResponse> rows, List<ShiftService.PauseOverrun> pauses, LocalDateTime now) {
        List<Incident> out = new ArrayList<>();
        Map<String, PlanningComplianceResponse> people = new HashMap<>();
        for (PlanningComplianceResponse r : rows) {
            people.put(r.username().toLowerCase(Locale.ROOT) + "|" + r.date(), r);
            if ("ABSENT".equals(r.status())) {
                out.add(incident(r, "ABSENCE", 0, "Absent au planning " + code(r) + " — aucune connexion", false));
                continue;
            }
            if (r.overnight() || r.plannedEnd() == null || r.firstLogin() == null) continue;
            LocalDateTime plannedEnd = r.date().atTime(r.plannedEnd());
            if (r.shiftEnd() != null) {
                long over = Duration.between(plannedEnd, r.date().atTime(r.shiftEnd())).toMinutes();
                if (over > OVERFLOW_TOLERANCE_MINUTES) {
                    out.add(incident(r, "DEBORDEMENT", over, "Fin de shift à " + hhmm(r.shiftEnd()) + " pour une fin prévue à " + hhmm(r.plannedEnd()), false));
                }
            } else if (r.date().equals(now.toLocalDate()) && now.isAfter(plannedEnd.plusMinutes(OVERFLOW_TOLERANCE_MINUTES))) {
                long over = Duration.between(plannedEnd, now).toMinutes();
                out.add(incident(r, "DEBORDEMENT", over, "Toujours en poste après " + hhmm(r.plannedEnd()) + " (clôture automatique à "
                        + hhmm(r.plannedEnd().plusMinutes(com.ecobank.rccportal.util.ShiftOverflow.AUTO_CLOSE_MINUTES)) + ")", true));
            }
        }
        // Dépassements de pause : un incident par agent et par jour (cumul), seulement pour les agents du périmètre.
        Map<String, List<ShiftService.PauseOverrun>> byDay = new LinkedHashMap<>();
        for (ShiftService.PauseOverrun p : pauses) {
            if (!people.containsKey(p.username().toLowerCase(Locale.ROOT) + "|" + p.date())) continue;
            byDay.computeIfAbsent(p.username().toLowerCase(Locale.ROOT) + "|" + p.date(), k -> new ArrayList<>()).add(p);
        }
        byDay.forEach((k, list) -> {
            PlanningComplianceResponse r = people.get(k);
            long over = list.stream().mapToLong(p -> p.minutes() - p.allowed()).sum();
            String detail = String.join(" · ", list.stream().map(p -> ("LUNCH".equals(p.type()) ? "Déjeuner" : "Pause") + " de " + p.minutes()
                    + " min à " + hhmm(p.start().toLocalTime()) + " (" + p.allowed() + " min autorisées)").toList());
            out.add(incident(r, "PAUSE", over, detail, false));
        });
        return out;
    }

    private static Incident incident(PlanningComplianceResponse r, String type, long minutes, String detail, boolean ongoing) {
        return new Incident(key(r.username(), r.date(), type), r.date(), r.username(), r.fullName() == null ? r.username() : r.fullName(),
                r.team(), type, minutes, detail, ongoing, null);
    }

    static String key(String username, LocalDate date, String type) {
        return type + "|" + username.toLowerCase(Locale.ROOT) + "|" + date;
    }

    // ── Justification (Team Leader) ──────────────────────────────────────

    /** Le Team Leader (ou l'admin) justifie un incident de son équipe ; le motif et un commentaire sont obligatoires. */
    public Justification justify(AuthenticatedUser requester, String username, LocalDate date, String type, String reason, String comment) {
        String role = requester.role() == null ? "" : requester.role().toLowerCase(Locale.ROOT);
        if (!Set.of("team_leader", "admin").contains(role)) throw ApiException.forbidden("Seul le Team Leader de l'agent (ou l'administrateur) justifie un incident.");
        if (!TYPES.contains(type)) throw ApiException.badRequest("Type d'incident inconnu.");
        if (blank(reason) || !REASONS.contains(reason)) throw ApiException.badRequest("Choisissez un motif.");
        if (blank(comment) || comment.trim().length() < 5) throw ApiException.badRequest("Précisez la justification (5 caractères minimum).");
        if (date == null || date.isAfter(LocalDate.now())) throw ApiException.badRequest("Date invalide.");
        // L'incident doit exister dans le périmètre du Team Leader : on ne justifie pas un agent d'une autre équipe.
        boolean inScope = compliance.forDateScoped(requester, date, null).stream().anyMatch(r -> r.username().equalsIgnoreCase(username));
        if (!inScope) throw ApiException.forbidden("Cet agent ne fait pas partie de votre équipe ce jour-là.");
        ensureSchema();
        String c = comment.trim().length() > 1000 ? comment.trim().substring(0, 1000) : comment.trim();
        int n = jdbc.update("UPDATE dbo.ShiftIncidentJustifications SET Reason = ?, Comment = ?, JustifiedBy = ?, JustifiedAt = SYSUTCDATETIME() "
                + "WHERE Username = ? AND WorkDate = ? AND IncidentType = ?", reason, c, requester.username(), username.toLowerCase(Locale.ROOT), java.sql.Date.valueOf(date), type);
        if (n == 0) {
            jdbc.update("INSERT INTO dbo.ShiftIncidentJustifications (Username, WorkDate, IncidentType, Reason, Comment, JustifiedBy) VALUES (?, ?, ?, ?, ?, ?)",
                    username.toLowerCase(Locale.ROOT), java.sql.Date.valueOf(date), type, reason, c, requester.username());
        }
        return new Justification(reason, c, requester.username(), LocalDateTime.now());
    }

    private Map<String, Justification> justifications(LocalDate from, LocalDate to) {
        Map<String, Justification> out = new HashMap<>();
        try {
            ensureSchema();
            jdbc.query("SELECT Username, WorkDate, IncidentType, Reason, Comment, JustifiedBy, JustifiedAt FROM dbo.ShiftIncidentJustifications WHERE WorkDate BETWEEN ? AND ?",
                    rs -> {
                        java.sql.Timestamp at = rs.getTimestamp("JustifiedAt");
                        out.put(key(rs.getString("Username"), rs.getDate("WorkDate").toLocalDate(), rs.getString("IncidentType")),
                                new Justification(rs.getString("Reason"), rs.getString("Comment"), rs.getString("JustifiedBy"), at == null ? null : at.toLocalDateTime()));
                    }, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        } catch (RuntimeException e) {
            // table indisponible : incidents affichés sans justification
        }
        return out;
    }

    private void ensureSchema() {
        if (schemaReady) return;
        jdbc.execute("""
                IF OBJECT_ID('dbo.ShiftIncidentJustifications', 'U') IS NULL
                CREATE TABLE dbo.ShiftIncidentJustifications (
                    Id INT IDENTITY(1,1) PRIMARY KEY,
                    Username NVARCHAR(100) NOT NULL,
                    WorkDate DATE NOT NULL,
                    IncidentType NVARCHAR(20) NOT NULL,
                    Reason NVARCHAR(120) NOT NULL,
                    Comment NVARCHAR(1000) NOT NULL,
                    JustifiedBy NVARCHAR(100) NOT NULL,
                    JustifiedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_ShiftIncidentJustifications UNIQUE (Username, WorkDate, IncidentType)
                )""");
        schemaReady = true;
    }

    private static String code(PlanningComplianceResponse r) {
        return r.shiftCode() == null ? "" : r.shiftCode() + (r.plannedStart() != null ? " (" + hhmm(r.plannedStart()) + ")" : "");
    }

    private static String hhmm(LocalTime t) {
        return t == null ? "" : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
