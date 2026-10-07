package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Catalogue des shifts (dbo.SHIFT_CODES) — une seule définition des horaires, modifiable dans Administration :
 * M 07h-16h, M2 08h-17h, M3 09h-18h, M4 10h-19h, A 12h-21h, N 21h-07h au départ.
 * Utilisé par le planning du Team Leader, l'import du planning (codes absents du fichier), l'export Excel et
 * le suivi de présence. Modifier un horaire peut aussi mettre à jour les plannings déjà saisis à partir d'une date.
 */
@Service
@lombok.extern.slf4j.Slf4j
public class ShiftCatalogService {

    public record ShiftCode(String code, String label, String start, String end, boolean overnight, String color, int sortOrder, boolean active) {}

    public record SaveRequest(String label, String start, String end, String color, Integer sortOrder, Boolean active,
                              Boolean applyToPlanned, String applyFrom) {}

    public record SaveResult(ShiftCode shift, int planningUpdated) {}

    /** Horaires de départ (demande de l'administration). */
    static final List<Object[]> SEED = List.of(
            new Object[]{"M", "Matin 07h-16h", "07:00", "16:00", "#BFD8F5", 1},
            new Object[]{"M2", "Matin 08h-17h", "08:00", "17:00", "#8FB8EC", 2},
            new Object[]{"M3", "Matin 09h-18h", "09:00", "18:00", "#6FA0E0", 3},
            new Object[]{"M4", "Matin 10h-19h", "10:00", "19:00", "#2F5FA8", 4},
            new Object[]{"A", "Après-midi 12h-21h", "12:00", "21:00", "#8FE0C2", 5},
            new Object[]{"N", "Nuit 21h-07h", "21:00", "07:00", "#1E2761", 6});

    private final JdbcTemplate jdbc;
    private volatile Map<String, ShiftCode> cache;

    public ShiftCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Crée la table et les 6 shifts de départ s'ils n'existent pas (jamais d'écrasement d'un horaire modifié). */
    public void ensure() {
        jdbc.execute("""
                IF OBJECT_ID('dbo.SHIFT_CODES', 'U') IS NULL
                CREATE TABLE dbo.SHIFT_CODES (
                    CODE NVARCHAR(10) NOT NULL PRIMARY KEY,
                    LABEL NVARCHAR(100) NOT NULL,
                    START_TIME TIME NOT NULL,
                    END_TIME TIME NOT NULL,
                    COLOR NVARCHAR(7) NULL,
                    SORT_ORDER INT NOT NULL DEFAULT 0,
                    ACTIVE BIT NOT NULL DEFAULT 1,
                    MODIFIED_AT DATETIME2 NULL,
                    MODIFIED_BY NVARCHAR(100) NULL
                )""");
        for (Object[] s : SEED) {
            jdbc.update("""
                    IF NOT EXISTS (SELECT 1 FROM dbo.SHIFT_CODES WHERE CODE = ?)
                    INSERT INTO dbo.SHIFT_CODES (CODE, LABEL, START_TIME, END_TIME, COLOR, SORT_ORDER, ACTIVE) VALUES (?, ?, ?, ?, ?, ?, 1)""",
                    s[0], s[0], s[1], s[2], s[3], s[4], s[5]);
        }
        cache = null;
    }

    private static String hhmm(Object t) {
        if (t == null) return null;
        String s = t.toString();
        return s.length() >= 5 ? s.substring(0, 5) : s;
    }

    private Map<String, ShiftCode> load() {
        Map<String, ShiftCode> c = cache;
        if (c != null) return c;
        Map<String, ShiftCode> m = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT CODE, LABEL, START_TIME, END_TIME, COLOR, SORT_ORDER, ACTIVE FROM dbo.SHIFT_CODES ORDER BY SORT_ORDER, CODE", rs -> {
                String start = hhmm(rs.getString("START_TIME")), end = hhmm(rs.getString("END_TIME"));
                m.put(rs.getString("CODE").toUpperCase(Locale.ROOT), new ShiftCode(rs.getString("CODE").toUpperCase(Locale.ROOT), rs.getString("LABEL"),
                        start, end, !LocalTime.parse(end).isAfter(LocalTime.parse(start)), rs.getString("COLOR"), rs.getInt("SORT_ORDER"), rs.getBoolean("ACTIVE")));
            });
        } catch (RuntimeException e) {
            // Table pas encore créée (tout premier démarrage) : horaires de départ.
            for (Object[] s : SEED) {
                m.put((String) s[0], new ShiftCode((String) s[0], (String) s[1], (String) s[2], (String) s[3],
                        !LocalTime.parse((String) s[3]).isAfter(LocalTime.parse((String) s[2])), (String) s[4], (Integer) s[5], true));
            }
            return m;
        }
        cache = m;
        return m;
    }

    public List<ShiftCode> all() {
        return new ArrayList<>(load().values());
    }

    /** Shifts actifs, dans l'ordre d'affichage. */
    public List<ShiftCode> active() {
        return load().values().stream().filter(ShiftCode::active).toList();
    }

    public Optional<ShiftCode> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(load().get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /** Horaires actifs sous la forme utilisée par l'import et le planning (ScheduleService). */
    Map<String, ScheduleService.ShiftDef> activeDefs() {
        Map<String, ScheduleService.ShiftDef> m = new LinkedHashMap<>();
        for (ShiftCode s : active()) {
            m.put(s.code(), new ScheduleService.ShiftDef(s.label(), LocalTime.parse(s.start()), LocalTime.parse(s.end()), s.overnight()));
        }
        return m;
    }

    static String defaultLabel(String code, String start, String end) {
        String prefix = code.startsWith("M") ? "Matin " : code.equals("A") ? "Après-midi " : code.equals("N") ? "Nuit " : "";
        return prefix + start.substring(0, 2) + "h" + (start.endsWith(":00") ? "" : start.substring(3)) + "-"
                + end.substring(0, 2) + "h" + (end.endsWith(":00") ? "" : end.substring(3));
    }

    /**
     * Crée ou modifie un shift. Avec applyToPlanned, les plannings déjà saisis avec ce code à partir de applyFrom
     * (aujourd'hui par défaut) prennent les nouveaux horaires — sinon seuls les plannings à venir les utilisent.
     */
    @Transactional
    public SaveResult save(String rawCode, SaveRequest r, String by) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9]{0,5}")) throw ApiException.badRequest("Code de shift invalide (lettres et chiffres, 6 caractères max., ex. M5).");
        if (Set.of("OFF", "C", "RM", "ABS", "CP").contains(code)) throw ApiException.badRequest("« " + code + " » est un code d'absence, pas un shift.");
        LocalTime start, end;
        try {
            start = LocalTime.parse(r.start());
            end = LocalTime.parse(r.end());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Heures attendues au format HH:MM (ex. 07:00).");
        }
        if (start.equals(end)) throw ApiException.badRequest("L'heure de fin doit différer de l'heure de début.");
        String s = start.toString(), e = end.toString();
        String label = r.label() == null || r.label().isBlank() ? defaultLabel(code, s, e) : r.label().trim();
        Optional<ShiftCode> before = find(code);
        int sort = r.sortOrder() != null ? r.sortOrder() : before.map(ShiftCode::sortOrder).orElse(load().size() + 1);
        boolean active = r.active() == null || r.active();
        String color = r.color() == null || r.color().isBlank() ? before.map(ShiftCode::color).orElse("#8FB8EC") : r.color().trim();
        jdbc.update("""
                MERGE dbo.SHIFT_CODES AS t USING (SELECT ? AS CODE) AS src ON t.CODE = src.CODE
                WHEN MATCHED THEN UPDATE SET LABEL = ?, START_TIME = ?, END_TIME = ?, COLOR = ?, SORT_ORDER = ?, ACTIVE = ?, MODIFIED_AT = SYSDATETIME(), MODIFIED_BY = ?
                WHEN NOT MATCHED THEN INSERT (CODE, LABEL, START_TIME, END_TIME, COLOR, SORT_ORDER, ACTIVE, MODIFIED_AT, MODIFIED_BY)
                     VALUES (?, ?, ?, ?, ?, ?, ?, SYSDATETIME(), ?);""",
                code, label, s, e, color, sort, active, by, code, label, s, e, color, sort, active, by);
        cache = null;
        int updated = 0;
        if (Boolean.TRUE.equals(r.applyToPlanned())) {
            LocalDate from = r.applyFrom() == null || r.applyFrom().isBlank() ? LocalDate.now() : LocalDate.parse(r.applyFrom());
            updated = jdbc.update("""
                    UPDATE dbo.AgentSchedules SET PlannedStartTime = ?, PlannedEndTime = ?, ShiftLabel = ?, OvernightCrossesMidnight = ?
                    WHERE UPPER(ShiftCode) = ? AND WorkDate >= ?""", s, e, label, !end.isAfter(start), code, from);
        }
        log.info("[SHIFTS] {} = {} {}-{} (par {}, {} planning(s) mis à jour)", code, label, s, e, by, updated);
        return new SaveResult(find(code).orElseThrow(), updated);
    }
}
