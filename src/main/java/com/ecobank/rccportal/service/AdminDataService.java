package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Administration — édition directe des tables des comptes, sans passer par SQL Server Management Studio :
 * dbo.USERS, dbo.USER_ROLES, dbo.USER_SERVICES, dbo.ROLES, dbo.SERVICES.
 * <ul>
 *   <li>Colonnes lues dans le schéma réel de la base (INFORMATION_SCHEMA) : toute colonne ajoutée en base apparaît.
 *       Seules ces 5 tables sont accessibles ; un nom de colonne n'est accepté que s'il existe dans la table
 *       (jamais de SQL construit avec une saisie libre) et les valeurs passent en paramètres.</li>
 *   <li>Liaisons (USER_ROLES / USER_SERVICES) : nom de la personne et du rôle/service affichés à côté des ID ;
 *       ajout et retrait passent par les mêmes règles que l'organigramme (service Team Leader → équipe menée…).</li>
 *   <li>Colonnes sensibles (mot de passe, secret MFA) jamais renvoyées ni modifiables ici.</li>
 * </ul>
 * Chaque écriture est aussitôt en base ; l'accès des personnes est relu immédiatement et le journal
 * « Modifications enregistrées en base » la trace (voir JwtAuthenticationFilter / AdminChangeJournal).
 */
@Service
@lombok.extern.slf4j.Slf4j
public class AdminDataService {

    public record Column(String name, String type, Integer maxLength, boolean nullable, boolean editable, String label) {}

    public record Page(String table, List<Column> columns, List<Map<String, Object>> rows, int total, int page, int size) {}

    private record Table(String name, String title, Set<String> readOnly) {}

    private static final Set<String> HIDDEN = Set.of("PASSWORD", "TOTP_SECRET", "TOTPSECRET", "TOTP_SECRET_PENDING");

    private static final Map<String, Table> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("USERS", new Table("USERS", "Utilisateurs", Set.of("ID", "CREATED_AT")));
        TABLES.put("USER_ROLES", new Table("USER_ROLES", "Rôles attribués", Set.of("ID")));
        TABLES.put("USER_SERVICES", new Table("USER_SERVICES", "Services attribués", Set.of("ID")));
        TABLES.put("ROLES", new Table("ROLES", "Rôles", Set.of("ID")));
        TABLES.put("SERVICES", new Table("SERVICES", "Services", Set.of("ID")));
    }

    private final JdbcTemplate jdbc;
    private final AdministrationService administration;
    private final AuditLogService audit;

    public AdminDataService(JdbcTemplate jdbc, AdministrationService administration, AuditLogService audit) {
        this.jdbc = jdbc;
        this.administration = administration;
        this.audit = audit;
    }

    /** Trace en base (journal « Modifications enregistrées en base »). */
    private void trace(String by, String text) {
        audit.record(by, AdminChangeJournal.ACTION, text.length() > 1990 ? text.substring(0, 1990) + "…" : text);
    }

    private String who(String table, long id) {
        try {
            return switch (table) {
                case "USERS" -> jdbc.queryForObject("SELECT NAME + ' (' + USERNAME + ')' FROM dbo.USERS WHERE ID = ?", String.class, id);
                case "USER_ROLES" -> jdbc.queryForObject("SELECT u.NAME + ' — ' + r.NAME FROM dbo.USER_ROLES t JOIN dbo.USERS u ON u.ID = t.USERS_ID JOIN dbo.ROLES r ON r.ID = t.ROLES_ID WHERE t.ID = ?", String.class, id);
                case "USER_SERVICES" -> jdbc.queryForObject("SELECT u.NAME + ' — ' + s.NAME FROM dbo.USER_SERVICES t JOIN dbo.USERS u ON u.ID = t.USER_ID JOIN dbo.SERVICES s ON s.ID = t.SERVICE_ID WHERE t.ID = ?", String.class, id);
                default -> jdbc.queryForObject("SELECT NAME FROM dbo." + table + " WHERE ID = ?", String.class, id);
            };
        } catch (DataAccessException e) {
            return "#" + id;
        }
    }

    public List<Map<String, String>> tables() {
        List<Map<String, String>> out = new ArrayList<>();
        TABLES.values().forEach(t -> out.add(Map.of("name", t.name(), "title", t.title())));
        return out;
    }

    private static Table table(String name) {
        Table t = name == null ? null : TABLES.get(name.trim().toUpperCase(Locale.ROOT));
        if (t == null) throw ApiException.badRequest("Table non gérée : " + name + ". Tables disponibles : " + TABLES.keySet() + ".");
        return t;
    }

    public List<Column> columns(String tableName) {
        Table t = table(tableName);
        List<Column> cols = new ArrayList<>();
        jdbc.query("""
                SELECT c.COLUMN_NAME, c.DATA_TYPE, c.CHARACTER_MAXIMUM_LENGTH, c.IS_NULLABLE,
                       COLUMNPROPERTY(OBJECT_ID('dbo.' + c.TABLE_NAME), c.COLUMN_NAME, 'IsIdentity') AS IS_IDENTITY
                FROM INFORMATION_SCHEMA.COLUMNS c
                WHERE c.TABLE_SCHEMA = 'dbo' AND c.TABLE_NAME = ?
                ORDER BY c.ORDINAL_POSITION""", rs -> {
            String n = rs.getString("COLUMN_NAME");
            if (HIDDEN.contains(n.toUpperCase(Locale.ROOT))) return;
            int len = rs.getInt("CHARACTER_MAXIMUM_LENGTH");
            boolean identity = rs.getInt("IS_IDENTITY") == 1;
            cols.add(new Column(n, rs.getString("DATA_TYPE").toLowerCase(Locale.ROOT), rs.wasNull() ? null : len,
                    "YES".equals(rs.getString("IS_NULLABLE")), !identity && !t.readOnly().contains(n.toUpperCase(Locale.ROOT)), null));
        }, t.name());
        if (cols.isEmpty()) throw ApiException.notFound("Table dbo." + t.name() + " introuvable en base.");
        return cols;
    }

    private static Column column(List<Column> cols, String name) {
        return cols.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Colonne inconnue : " + name + "."));
    }

    private static String keyOf(List<Column> cols) {
        return cols.stream().filter(c -> c.name().equalsIgnoreCase("ID")).map(Column::name).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Table sans colonne ID : édition impossible."));
    }

    /** Colonnes calculées affichées à côté des ID des tables de liaison (non modifiables). */
    private static List<String[]> joins(String table) {
        return switch (table) {
            case "USER_ROLES" -> List.of(
                    new String[]{"u.NAME AS [Personne]", "LEFT JOIN dbo.USERS u ON u.ID = t.USERS_ID"},
                    new String[]{"u.USERNAME AS [Identifiant]", ""},
                    new String[]{"r.NAME AS [Rôle]", "LEFT JOIN dbo.ROLES r ON r.ID = t.ROLES_ID"});
            case "USER_SERVICES" -> List.of(
                    new String[]{"u.NAME AS [Personne]", "LEFT JOIN dbo.USERS u ON u.ID = t.USER_ID"},
                    new String[]{"u.USERNAME AS [Identifiant]", ""},
                    new String[]{"s.NAME AS [Service]", "LEFT JOIN dbo.SERVICES s ON s.ID = t.SERVICE_ID"});
            case "USERS" -> List.of(
                    new String[]{"STUFF((SELECT ', ' + r.NAME FROM dbo.USER_ROLES ur JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID WHERE ur.USERS_ID = t.ID FOR XML PATH(''), TYPE).value('.', 'NVARCHAR(MAX)'), 1, 2, '') AS [Rôles]", ""},
                    new String[]{"STUFF((SELECT ', ' + s.NAME FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID WHERE us.USER_ID = t.ID FOR XML PATH(''), TYPE).value('.', 'NVARCHAR(MAX)'), 1, 2, '') AS [Services]", ""});
            case "ROLES" -> List.<String[]>of(new String[]{"(SELECT COUNT(*) FROM dbo.USER_ROLES ur WHERE ur.ROLES_ID = t.ID) AS [Personnes]", ""});
            case "SERVICES" -> List.<String[]>of(new String[]{"(SELECT COUNT(*) FROM dbo.USER_SERVICES us WHERE us.SERVICE_ID = t.ID) AS [Personnes]", ""});
            default -> List.of();
        };
    }

    @Transactional(readOnly = true)
    public Page rows(String tableName, String q, int page, int size, String sort, boolean desc) {
        Table t = table(tableName);
        List<Column> cols = columns(t.name());
        String key = keyOf(cols);
        List<String[]> js = joins(t.name());
        List<Column> all = new ArrayList<>(cols);
        for (String[] j : js) {
            String alias = j[0].substring(j[0].lastIndexOf('[') + 1, j[0].lastIndexOf(']'));
            all.add(new Column(alias, "computed", null, true, false, alias));
        }
        StringBuilder from = new StringBuilder(" FROM dbo.").append(t.name()).append(" t ");
        js.forEach(j -> from.append(j[1]).append(' '));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        if (q != null && !q.isBlank()) {
            List<String> ors = new ArrayList<>();
            for (Column c : cols) {
                if (c.type().contains("char") || c.type().equals("text")) { ors.add("t.[" + c.name() + "] LIKE ?"); args.add("%" + q.trim() + "%"); }
                else if (q.trim().matches("\\d+") && (c.type().contains("int"))) { ors.add("t.[" + c.name() + "] = ?"); args.add(Long.parseLong(q.trim())); }
            }
            if (t.name().startsWith("USER_")) {
                ors.add("u.NAME LIKE ?"); args.add("%" + q.trim() + "%");
                ors.add("u.USERNAME LIKE ?"); args.add("%" + q.trim() + "%");
                ors.add((t.name().equals("USER_ROLES") ? "r" : "s") + ".NAME LIKE ?"); args.add("%" + q.trim() + "%");
            }
            where.append(" WHERE ").append(String.join(" OR ", ors));
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Integer.class, args.toArray());
        String order = key;
        if (sort != null && !sort.isBlank()) order = column(cols, sort).name();
        int s = Math.max(10, Math.min(size <= 0 ? 100 : size, 500)), p = Math.max(0, page);
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> sel = new ArrayList<>();
        cols.forEach(c -> sel.add("t.[" + c.name() + "]"));
        js.forEach(j -> sel.add(j[0]));
        sql.append(String.join(", ", sel)).append(from).append(where)
                .append(" ORDER BY t.[").append(order).append("] ").append(desc ? "DESC" : "ASC")
                .append(" OFFSET ").append(p * s).append(" ROWS FETCH NEXT ").append(s).append(" ROWS ONLY");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        rows.forEach(r -> r.replaceAll((k, v) -> display(v)));
        return new Page(t.name(), all, rows, total == null ? 0 : total, p, s);
    }

    private static Object display(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        return v.toString();
    }

    /** Valeur saisie (texte) → type de la colonne ; "" ou null = NULL si la colonne l'accepte. */
    static Object convert(Column c, Object raw) {
        String s = raw == null ? null : raw.toString().trim();
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("NULL")) {
            if (!c.nullable()) throw ApiException.badRequest("La colonne " + c.name() + " ne peut pas être vide.");
            return null;
        }
        try {
            switch (c.type()) {
                case "bit":
                    if (s.matches("(?i)true|vrai|oui|1|yes")) return true;
                    if (s.matches("(?i)false|faux|non|0|no")) return false;
                    throw new IllegalArgumentException("attendu Vrai ou Faux");
                case "int": case "smallint": case "tinyint": return Integer.parseInt(s);
                case "bigint": return Long.parseLong(s);
                case "decimal": case "numeric": case "money": case "float": case "real": return new BigDecimal(s.replace(',', '.'));
                case "date": return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
                case "datetime": case "datetime2": case "smalldatetime": return parseDateTime(s);
                case "datetimeoffset": {
                    // « 2026-09-28 15:55:12.1234567 +00:00 » (affichage SQL Server) ou « 2026-09-28T15:55 » (saisie)
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*([+-]\\d{2}:\\d{2}|Z)$").matcher(s);
                    if (m.matches()) return OffsetDateTime.of(parseDateTime(m.group(1)), java.time.ZoneOffset.of(m.group(2)));
                    return OffsetDateTime.of(parseDateTime(s), java.time.ZoneOffset.UTC);
                }
                default:
                    if (c.maxLength() != null && c.maxLength() > 0 && s.length() > c.maxLength())
                        throw new IllegalArgumentException(c.maxLength() + " caractère(s) maximum");
                    return s;
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Valeur invalide pour " + c.name() + " (" + c.type() + ") : « " + s + " » — " + e.getMessage() + ".");
        }
    }

    static LocalDateTime parseDateTime(String s) {
        String v = s.trim().replaceFirst(" ", "T");
        if (v.length() == 10) v += "T00:00";
        if (v.length() == 16) v += ":00";
        return LocalDateTime.parse(v);
    }

    private static ApiException dbError(DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        String m = root != null && root.getMessage() != null ? root.getMessage() : e.getMessage();
        return ApiException.badRequest("Refusé par la base de données : " + m);
    }

    /** Modifie une cellule. Renvoie la ligne relue en base. */
    @Transactional
    public Map<String, Object> update(String tableName, long id, String columnName, Object value, String by) {
        Table t = table(tableName);
        List<Column> cols = columns(t.name());
        Column c = column(cols, columnName);
        if (!c.editable()) throw ApiException.badRequest("La colonne " + c.name() + " n'est pas modifiable.");
        Object v = convert(c, value);
        if (t.name().equals("USER_ROLES") || t.name().equals("USER_SERVICES")) checkLinkTarget(t.name(), c.name(), v);
        if (t.name().equals("USERS") && c.name().equalsIgnoreCase("USERNAME") && v != null) {
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.USERS WHERE LOWER(USERNAME) = LOWER(?) AND ID <> ?", Integer.class, v, id);
            if (dup != null && dup > 0) throw ApiException.conflict("username_taken", "Identifiant déjà utilisé par un autre compte : " + v + ".");
        }
        List<Object> old = jdbc.queryForList("SELECT [" + c.name() + "] FROM dbo." + t.name() + " WHERE [" + keyOf(cols) + "] = ?", Object.class, id);
        String extra = t.name().equals("USERS") && cols.stream().anyMatch(x -> x.name().equalsIgnoreCase("MODIFIED_AT")) && !c.name().equalsIgnoreCase("MODIFIED_AT")
                ? ", [MODIFIED_AT] = SYSDATETIMEOFFSET()" : "";
        try {
            int n = jdbc.update("UPDATE dbo." + t.name() + " SET [" + c.name() + "] = ?" + extra + " WHERE [" + keyOf(cols) + "] = ?", v, id);
            if (n == 0) throw ApiException.notFound("Ligne " + id + " introuvable dans dbo." + t.name() + ".");
        } catch (DataAccessException e) {
            throw dbError(e);
        }
        trace(by, "Base — dbo." + t.name() + " #" + id + " " + who(t.name(), id) + " : " + c.name() + " « "
                + (old.isEmpty() || old.get(0) == null ? "NULL" : display(old.get(0))) + " » → « " + (v == null ? "NULL" : v) + " »");
        return row(t.name(), id);
    }

    private void checkLinkTarget(String table, String column, Object v) {
        String col = column.toUpperCase(Locale.ROOT);
        String target = col.startsWith("USER") ? "USERS" : col.startsWith("ROLE") ? "ROLES" : col.startsWith("SERVICE") ? "SERVICES" : null;
        if (target == null || v == null) return;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo." + target + " WHERE ID = ?", Integer.class, v);
        if (n == null || n == 0) throw ApiException.badRequest("Aucune ligne " + v + " dans dbo." + target + ".");
    }

    public Map<String, Object> row(String tableName, long id) {
        Page p = rows(tableName, String.valueOf(id), 0, 500, null, false);
        return p.rows().stream().filter(r -> Objects.equals(String.valueOf(r.get("ID")), String.valueOf(id))).findFirst().orElse(Map.of());
    }

    /**
     * Nouvelle ligne. Liaisons : une personne (ou plusieurs) et un rôle/service, par les règles de l'organigramme.
     * USERS : identifiant obligatoire, compte approuvé et actif par défaut. ROLES / SERVICES : nom obligatoire.
     */
    @Transactional
    public Map<String, Object> insert(String tableName, Map<String, Object> values, String by) {
        Table t = table(tableName);
        Map<String, Object> in = new HashMap<>();
        values.forEach((k, v) -> in.put(k.toUpperCase(Locale.ROOT), v));
        try {
            switch (t.name()) {
                case "USER_ROLES", "USER_SERVICES" -> {
                    boolean roles = t.name().equals("USER_ROLES");
                    Object target = in.get(roles ? "ROLES_ID" : "SERVICE_ID");
                    if (target == null || target.toString().isBlank()) throw ApiException.badRequest("Choisissez le " + (roles ? "rôle" : "service") + ".");
                    List<Long> users = new ArrayList<>();
                    Object u = in.containsKey("USER_IDS") ? in.get("USER_IDS") : in.get(roles ? "USERS_ID" : "USER_ID");
                    if (u instanceof Collection<?> c) c.forEach(x -> users.add(Long.parseLong(x.toString())));
                    else if (u != null && !u.toString().isBlank()) users.add(Long.parseLong(u.toString().trim()));
                    if (users.isEmpty()) throw ApiException.badRequest("Choisissez au moins une personne.");
                    long targetId = Long.parseLong(target.toString().trim());
                    checkLinkTarget(t.name(), roles ? "ROLES_ID" : "SERVICE_ID", targetId);
                    int added = 0;
                    for (Long uid : users) {
                        checkLinkTarget(t.name(), "USERS_ID", uid);
                        if (roles) administration.assignRole(uid, targetId); else administration.assignService(uid, targetId);
                        added++;
                        trace(by, "Base — dbo." + t.name() + " : ajout " + who("USERS", uid) + " — " + who(roles ? "ROLES" : "SERVICES", targetId));
                    }
                    return Map.of("added", added);
                }
                case "USERS" -> {
                    String username = str(in.get("USERNAME"));
                    if (username == null) throw ApiException.badRequest("L'identifiant (USERNAME) est obligatoire.");
                    Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.USERS WHERE LOWER(USERNAME) = LOWER(?)", Integer.class, username);
                    if (dup != null && dup > 0) throw ApiException.conflict("username_taken", "Identifiant déjà utilisé : " + username + ".");
                    in.putIfAbsent("STATUS", "APPROVED");
                    in.putIfAbsent("ACCOUNT_ENABLED", true);
                    in.putIfAbsent("ACCOUNT_LOCKED", false);
                    in.putIfAbsent("ACCOUNT_EXPIRED", false);
                    in.putIfAbsent("CREDENTIALS_EXPIRED", false);
                    in.putIfAbsent("FAILED_ATTEMPTS", 0);
                    in.putIfAbsent("TEAM_ASSIGNMENT_LOCKED", true);
                    return insertRow(t, in, true, by);
                }
                default -> {
                    if (str(in.get("NAME")) == null) throw ApiException.badRequest("Le nom (NAME) est obligatoire.");
                    if (t.name().equals("SERVICES")) in.putIfAbsent("ENABLED", true);
                    return insertRow(t, in, false, by);
                }
            }
        } catch (DataAccessException e) {
            throw dbError(e);
        }
    }

    private Map<String, Object> insertRow(Table t, Map<String, Object> in, boolean stampCreated, String by) {
        List<Column> cols = columns(t.name());
        List<String> names = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Column c : cols) {
            if (!c.editable() || !in.containsKey(c.name().toUpperCase(Locale.ROOT))) continue;
            names.add("[" + c.name() + "]");
            vals.add(convert(c, in.get(c.name().toUpperCase(Locale.ROOT))));
        }
        if (stampCreated && cols.stream().anyMatch(c -> c.name().equalsIgnoreCase("CREATED_AT"))) names.add("[CREATED_AT]");
        String placeholders = String.join(", ", Collections.nCopies(vals.size(), "?"))
                + (names.size() > vals.size() ? (vals.isEmpty() ? "" : ", ") + "SYSDATETIMEOFFSET()" : "");
        // SCOPE_IDENTITY plutôt qu'OUTPUT INSERTED : fonctionne aussi si la table porte un déclencheur.
        Long id = jdbc.queryForObject("SET NOCOUNT ON; INSERT INTO dbo." + t.name() + " (" + String.join(", ", names) + ") VALUES (" + placeholders
                + "); SELECT CAST(SCOPE_IDENTITY() AS BIGINT)", Long.class, vals.toArray());
        log.info("[ADMIN DATA] dbo.{} : ligne {} créée", t.name(), id);
        trace(by, "Base — dbo." + t.name() + " : nouvelle ligne #" + id + " " + who(t.name(), id == null ? -1 : id));
        return row(t.name(), id == null ? -1 : id);
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString().trim();
    }

    /** Supprime une ligne. Liaison : retire aussi ce qu'elle donnait (ex. équipe menée), sauf si un doublon reste. */
    @Transactional
    public void delete(String tableName, long id, String by) {
        Table t = table(tableName);
        String label = who(t.name(), id);
        try {
            switch (t.name()) {
                case "USERS" -> administration.deleteUser(id);
                case "USER_ROLES", "USER_SERVICES" -> {
                    boolean roles = t.name().equals("USER_ROLES");
                    String uc = roles ? "USERS_ID" : "USER_ID", tc = roles ? "ROLES_ID" : "SERVICE_ID";
                    List<Map<String, Object>> r = jdbc.queryForList("SELECT " + uc + " AS U, " + tc + " AS T FROM dbo." + t.name() + " WHERE ID = ?", id);
                    if (r.isEmpty()) throw ApiException.notFound("Ligne " + id + " introuvable.");
                    long u = ((Number) r.get(0).get("U")).longValue(), target = ((Number) r.get(0).get("T")).longValue();
                    Integer same = jdbc.queryForObject("SELECT COUNT(*) FROM dbo." + t.name() + " WHERE " + uc + " = ? AND " + tc + " = ?", Integer.class, u, target);
                    if (same != null && same > 1) jdbc.update("DELETE FROM dbo." + t.name() + " WHERE ID = ?", id); // doublon : seule cette ligne
                    else if (roles) administration.removeRole(u, target);
                    else administration.removeService(u, target);
                }
                case "ROLES" -> {
                    Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.USER_ROLES WHERE ROLES_ID = ?", Integer.class, id);
                    if (used != null && used > 0) throw ApiException.badRequest("Rôle attribué à " + used + " personne(s) : retirez-le d'abord (onglet Rôles attribués).");
                    jdbc.update("DELETE FROM dbo.ROLES WHERE ID = ?", id);
                }
                case "SERVICES" -> administration.deleteService(id);
                default -> throw ApiException.badRequest("Suppression impossible ici.");
            }
        } catch (DataAccessException e) {
            throw dbError(e);
        }
        log.info("[ADMIN DATA] dbo.{} : ligne {} supprimée", t.name(), id);
        trace(by, "Base — dbo." + t.name() + " : ligne #" + id + " supprimée (" + label + ")");
    }

    /** Liaisons en double (même personne, même rôle/service) : garde la première, supprime les autres. */
    @Transactional
    public int removeDuplicateLinks(String by) {
        int n = jdbc.update("DELETE FROM dbo.USER_ROLES WHERE ID NOT IN (SELECT MIN(ID) FROM dbo.USER_ROLES GROUP BY USERS_ID, ROLES_ID)");
        n += jdbc.update("DELETE FROM dbo.USER_SERVICES WHERE ID NOT IN (SELECT MIN(ID) FROM dbo.USER_SERVICES GROUP BY USER_ID, SERVICE_ID)");
        n += jdbc.update("DELETE FROM dbo.USER_ROLES WHERE USERS_ID NOT IN (SELECT ID FROM dbo.USERS) OR ROLES_ID NOT IN (SELECT ID FROM dbo.ROLES)");
        n += jdbc.update("DELETE FROM dbo.USER_SERVICES WHERE USER_ID NOT IN (SELECT ID FROM dbo.USERS) OR SERVICE_ID NOT IN (SELECT ID FROM dbo.SERVICES)");
        log.info("[ADMIN DATA] {} liaison(s) en double ou orpheline(s) supprimée(s)", n);
        trace(by, "Base — " + n + " liaison(s) rôle/service en double ou orpheline(s) supprimée(s)");
        return n;
    }

    /** Listes de choix des formulaires (personnes, rôles, services). */
    public Map<String, List<Map<String, Object>>> options() {
        return Map.of(
                "users", jdbc.queryForList("SELECT ID AS id, NAME AS name, USERNAME AS username FROM dbo.USERS ORDER BY NAME"),
                "roles", jdbc.queryForList("SELECT ID AS id, NAME AS name FROM dbo.ROLES ORDER BY NAME"),
                "services", jdbc.queryForList("SELECT ID AS id, NAME AS name, CODE AS code FROM dbo.SERVICES ORDER BY NAME"));
    }
}
