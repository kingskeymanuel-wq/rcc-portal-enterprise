package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fichiers de performance hebdomadaires par équipe (ex. « Performances MAILS — du 21 - 27 Septembre » :
 * une ligne par agent, une colonne par indicateur). La QA choisit l'équipe : les indicateurs PROPRES
 * à cette équipe sont reconnus dans le fichier (Excel, CSV, PowerPoint du rapport hebdo, PDF, capture
 * si l'OCR local est configuré), les indicateurs calculés sont complétés quand le fichier ne les donne
 * pas (Total activités, Moy / jour, Productivité…), chaque ligne est rattachée à l'agent du portail.
 * Chaque agent voit ensuite ses propres chiffres, son rang et la moyenne de son équipe.
 */
@Service
public class TeamPerfFileService {

    /** unit : COUNT (somme) | RATE (moyenne) | PCT | SECONDS | TEXT. */
    public record Field(String key, String label, String unit, boolean higherIsBetter, boolean computed, String formula, List<String> aliases) {}

    public record TeamDef(String code, String label, String volumeKey, List<Field> fields) {}

    public record AgentLine(String agentName, Long userId, String username, String portalName, Map<String, Object> values,
                            String level, List<String> notes) {}

    public record ImportReport(String team, String teamLabel, LocalDate from, LocalDate to, boolean periodDetected, String detectedPeriod,
                               List<Field> fields, Map<String, String> recognizedColumns, List<String> ignoredColumns, List<String> missingFields,
                               List<AgentLine> lines, int matched, int unmatched, boolean saved, String batchId, int replaced) {}

    public record Period(LocalDate from, LocalDate to, int agents, String batchId) {}

    public record TeamSheet(String team, String teamLabel, LocalDate from, LocalDate to, List<Field> fields, List<AgentLine> rows,
                            Map<String, Object> totals, List<Period> periods) {}

    public record MyWeek(LocalDate from, LocalDate to, Map<String, Object> values, String level, Integer rank, int teamSize,
                         Map<String, Object> teamAverages) {}

    public record MyPerformance(String team, String teamLabel, List<Field> fields, List<MyWeek> weeks) {}

    /** Cumul d'une période (mois) par agent — pour le reporting d'équipe (TeamPerformanceService). */
    public record Aggregate(Map<Long, Map<String, Double>> byUser, Map<String, Map<String, Double>> byName) {
        public Map<String, Double> find(Long userId, String name) {
            Map<String, Double> v = userId == null ? null : byUser.get(userId);
            return v != null ? v : byName.get(nameKey(name));
        }
    }

    // ───────────── Catalogue des indicateurs par équipe ─────────────

    private static Field f(String key, String label, String unit, boolean higher, String... aliases) {
        return new Field(key, label, unit, higher, false, null, List.of(aliases));
    }

    private static Field c(String key, String label, String unit, boolean higher, String formula, String... aliases) {
        return new Field(key, label, unit, higher, true, formula, List.of(aliases));
    }

    private static final Field DAYS = f("daysWorked", "Jours travaillés", "COUNT", true, "JOURS_TRAVAILLES", "JOURS_TRAVAILLE", "JOURS", "NB_JOURS", "NOMBRE_DE_JOURS", "~JOUR+TRAVAIL");
    private static final Field TARGET = f("targetPerDay", "Target / jour", "RATE", true, "TARGET_JOUR", "TARGET", "OBJECTIF_JOUR", "OBJECTIF_JOURNALIER", "OBJECTIF", "~TARGET", "~OBJECTIF");
    private static final Field PRODUCTIVITY = c("productivity", "Productivité", "PCT", true, "Moy / jour ÷ Target / jour", "PRODUCTIVITE", "TAUX_PRODUCTIVITE", "~PRODUCTIVIT");
    private static final Field QUALITY = f("quality", "Qualité", "PCT", true, "QUALITE", "SCORE_QUALITE", "SCORE_QA", "NOTE_QUALITE", "QUALITY");
    private static final Field SENIORITY = f("seniority", "Ancienneté", "TEXT", true, "ANCIENNETE", "~ANCIENNET");
    private static final Field WEEKLY_TARGET = f("weeklyTarget", "Target hebdo", "RATE", true, "TARGET_HEBDO", "TARGET_HEBDOMADAIRE", "OBJECTIF_HEBDO",
            "OBJECTIF_HEBDOMADAIRE", "TARGET", "OBJECTIF", "~TARGET+HEBDO", "~OBJECTIF+HEBDO");
    private static final Field OTHER_ACTIVITIES = f("otherActivities", "Activités annexes", "COUNT", true, "AUTRES_ACTIVITES", "ACTIVITES_ANNEXES",
            "AUTRE_ACTIVITE", "ACTIVITE_ANNEXE", "~AUTRE+ACTIVIT", "~ACTIVIT+ANNEXE");
    private static final Field PRESENCE = c("presence", "Taux de présence", "PCT", true, "Jours travaillés ÷ 5", "TAUX_DE_PRESENCE", "TAUX_PRESENCE",
            "PRESENCE", "~PRESENCE");
    private static final Field REMARK = f("remark", "Arrêt maladie / remarque", "TEXT", true, "ARRET_MALADIE", "COMMENTAIRE", "COMMENTAIRES",
            "OBSERVATION", "OBSERVATIONS", "REMARQUE", "~ARRET");

    private static Field avgPerDay(String volumeLabel) {
        return c("avgPerDay", "Moy / jour", "RATE", true, volumeLabel + " ÷ Jours travaillés", "MOY_JOUR", "MOYENNE_JOUR", "MOYENNE_JOURNALIERE", "MOY_JOURNALIERE", "~MOY+JOUR");
    }

    public static final Map<String, TeamDef> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("INBOUND_MAIL", new TeamDef("INBOUND_MAIL", "Inbound Mail", "totalActivities", List.of(
                DAYS,
                f("outboundCalls", "Appels sortants", "COUNT", true, "APPELS_SORTANT", "APPELS_SORTANTS", "APPEL_SORTANT", "~APPEL+SORTANT"),
                f("cis", "CIS", "COUNT", true, "CIS", "NOMBRE_CIS"),
                f("mails", "Mails assistés", "COUNT", true, "MAILS_ASSIST", "MAILS_ASSISTES", "MAILS_ASSISTE", "MAIL_ASSIST", "MAILS_TRAITES", "MAILS", "~MAIL+ASSIST", "~MAIL+TRAIT"),
                f("requests", "Sollicitations", "COUNT", true, "SOLLICITATIONS", "SOLLICITATION", "~SOLLICITATION"),
                c("totalActivities", "Total activités", "COUNT", true, "CIS + Mails assistés + Sollicitations", "TOTAL_ACTIVITES", "TOTAL_ACTIVITE", "TOTAL", "~TOTAL+ACTIVIT"),
                avgPerDay("Total activités"), TARGET, PRODUCTIVITY, QUALITY, SENIORITY)));
        // Rapport « PERFORMANCE AGENTS CHATS » : production = Live Chat + autres activités, rapportée au target de la semaine.
        CATALOG.put("TCHAT", new TeamDef("TCHAT", "Réseaux sociaux", "totalProduction", List.of(
                f("liveChat", "Live Chat", "COUNT", true, "LIVE_CHAT", "LIVE_CHATS", "CHATS", "CHATS_TRAITES", "NOMBRE_CHATS", "~LIVE+CHAT", "~CHAT+TRAIT"),
                OTHER_ACTIVITIES, DAYS, WEEKLY_TARGET,
                c("totalProduction", "Prod globale", "COUNT", true, "Live Chat + Activités annexes", "PROD_GLOBALE", "PRODUCTION_GLOBALE", "~PROD+GLOBAL"),
                PRESENCE,
                c("avgPerDay", "Prod moyenne / jour", "RATE", true, "Prod globale ÷ Jours travaillés", "PROD_MOYENNE", "PRODUCTION_MOYENNE", "MOY_JOUR", "~PROD+MOYENNE"),
                c("productivity", "Taux d'atteinte du target", "PCT", true, "Prod globale ÷ Target hebdo", "TX_ATTEINTE_TARGET", "TAUX_ATTEINTE_TARGET",
                        "TAUX_D_ATTEINTE", "TX_ATTEINTE", "~ATTEINTE", "~PRODUCTIVIT"),
                f("aht", "DMT", "SECONDS", false, "DMT", "AHT", "DUREE_MOYENNE_TRAITEMENT", "~DUREE+MOYENNE"),
                QUALITY, REMARK)));
        // Rapport Rafiki (réseaux sociaux : Facebook, Instagram, X) : conversations résolues + activités annexes.
        CATALOG.put("RAFIKI", new TeamDef("RAFIKI", "Rafiki", "totalProduction", List.of(
                f("firstResponse", "First Response Time", "SECONDS", false, "FIRST_RESPONSE_TIME", "FRT", "DELAI_PREMIERE_REPONSE", "~FIRST+RESPONSE", "~PREMIERE+REPONSE"),
                WEEKLY_TARGET, DAYS,
                f("resolved", "Conversations résolues", "COUNT", true, "RESOLVED_CONVERSATIONS", "CONVERSATIONS_RESOLUES", "CONVERSATIONS", "~RESOLVED", "~CONVERSATION"),
                OTHER_ACTIVITIES, PRESENCE,
                c("productivity", "Taux de productivité", "PCT", true, "Performance globale ÷ Target hebdo", "TAUX_DE_PRODUCTIVITE", "TAUX_PRODUCTIVITE", "~PRODUCTIVIT", "~ATTEINTE"),
                c("totalProduction", "Performance globale", "COUNT", true, "Conversations résolues + Activités annexes", "PERFORMANCE_GLOBALE", "PROD_GLOBALE", "~PERFORMANCE+GLOBAL"),
                QUALITY, REMARK)));
        CATALOG.put("INBOUND_VOICE", new TeamDef("INBOUND_VOICE", "Inbound Voix", "calls", List.of(
                DAYS,
                f("calls", "Appels traités", "COUNT", true, "APPELS_TRAITES", "APPELS_RECUS", "APPELS_ENTRANTS", "APPELS_DECROCHES", "APPELS_REPONDUS", "NOMBRE_APPELS", "APPELS", "~APPEL+TRAIT", "~APPEL+ENTRANT"),
                f("aht", "DMT", "SECONDS", false, "DMT", "AHT", "DUREE_MOYENNE_TRAITEMENT", "~DUREE+MOYENNE"),
                f("pickup", "Taux de décroché", "PCT", true, "TAUX_DECROCHE", "TAUX_DE_DECROCHE", "~DECROCH"),
                f("fcr", "Résolution 1er contact", "PCT", true, "FCR", "TAUX_FCR", "~RESOLUTION+PREMIER"),
                avgPerDay("Appels traités"), TARGET, PRODUCTIVITY, QUALITY, SENIORITY)));
        CATALOG.put("OUTBOUND", new TeamDef("OUTBOUND", "Outbound / Digitalisation", "calls", List.of(
                DAYS,
                f("calls", "Appels émis", "COUNT", true, "APPELS_EMIS", "APPELS_PASSES", "APPELS_SORTANTS", "NOMBRE_APPELS", "APPELS", "~APPEL+EMIS", "~APPEL+PASS"),
                f("reached", "Clients joints", "COUNT", true, "CLIENTS_JOINTS", "JOINTS", "CONTACTS_ABOUTIS", "~JOINT"),
                f("appointments", "RDV", "COUNT", true, "RDV", "RDV_PRIS", "RENDEZ_VOUS", "~RDV"),
                f("sales", "Ventes", "COUNT", true, "VENTES", "NOMBRE_VENTES", "SOUSCRIPTIONS", "~VENTE"),
                c("reachRate", "Taux de joignabilité", "PCT", true, "Clients joints ÷ Appels émis", "TAUX_JOIGNABILITE", "~JOIGNABILIT"),
                c("conversion", "Taux de transformation", "PCT", true, "Ventes ÷ Clients joints", "TAUX_TRANSFORMATION", "TAUX_CONVERSION", "~TRANSFORMATION"),
                avgPerDay("Appels émis"), TARGET, PRODUCTIVITY, QUALITY, SENIORITY)));
        CATALOG.put("CIB", new TeamDef("CIB", "CIB", "interactions", List.of(
                DAYS,
                f("interactions", "Interactions", "COUNT", true, "INTERACTIONS", "INTERACTIONS_TRAITEES", "NOMBRE_INTERACTIONS", "~INTERACTION"),
                f("cases", "Cas créés", "COUNT", true, "CAS_CREES", "NOMBRE_CAS_CREES", "~CAS+CREE"),
                f("responseTime", "Délai moyen de réponse", "SECONDS", false, "DELAI_MOYEN_REPONSE", "DMR", "~DELAI"),
                avgPerDay("Interactions"), TARGET, PRODUCTIVITY, QUALITY, SENIORITY)));
    }

    private static final List<String> NAME_HEADERS = List.of("AGENT", "AGENTS", "AGENT_NAME", "NOM_AGENT", "NOM_DE_L_AGENT", "NOM_DES_AGENTS", "NOM", "NOMS", "NOM_ET_PRENOMS", "NOM_PRENOMS", "NOM_PRENOM",
            "COLLABORATEUR", "COLLABORATEURS", "CONSEILLER", "CONSEILLERS", "CONSEILLER_CLIENTELE", "PRENOMS_ET_NOM", "NOM_COMPLET");

    private static final Map<String, Integer> MONTHS = new HashMap<>();

    static {
        String[][] names = {{"JANVIER", "JANV", "JAN"}, {"FEVRIER", "FEVR", "FEV"}, {"MARS", "MAR"}, {"AVRIL", "AVR"}, {"MAI"}, {"JUIN"},
                {"JUILLET", "JUIL"}, {"AOUT"}, {"SEPTEMBRE", "SEPT", "SEP"}, {"OCTOBRE", "OCT"}, {"NOVEMBRE", "NOV"}, {"DECEMBRE", "DEC"}};
        for (int i = 0; i < names.length; i++) for (String n : names[i]) MONTHS.put(n, i + 1);
    }

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuditLogService audit;
    private final TeamLeaderService teamLeaders;
    private final LocalOcrClient ocr;
    private final com.ecobank.rccportal.repository.RccNotificationRepository notifications;
    private final ObjectMapper json = new ObjectMapper();

    public TeamPerfFileService(JdbcTemplate jdbc, UserRepository users, AuditLogService audit, TeamLeaderService teamLeaders, LocalOcrClient ocr,
                               com.ecobank.rccportal.repository.RccNotificationRepository notifications) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.teamLeaders = teamLeaders;
        this.ocr = ocr;
        this.notifications = notifications;
    }

    /** Équipes proposées : toutes pour la QA / le Superviseur, celles de SON équipe pour un Team Leader. */
    public List<TeamDef> catalog(AuthenticatedUser requester) {
        if (requester != null && !canImport(requester) && "TEAM_LEADER".equalsIgnoreCase(requester.role())) {
            Set<String> mine = ledFileTeams(requester);
            return CATALOG.values().stream().filter(t -> mine.contains(t.code())).toList();
        }
        return List.copyOf(CATALOG.values());
    }

    /** Fichiers d'équipe gérés par le Team Leader d'une équipe du portail (Inbound Mail couvre aussi Tchat et Rafiki). */
    static Set<String> fileTeamsFor(TeamClassifier.Team led) {
        if (led == null) return Set.of();
        return switch (led) {
            case INBOUND_MAIL -> Set.of("INBOUND_MAIL", "TCHAT", "RAFIKI");
            case INBOUND_VOICE -> Set.of("INBOUND_VOICE");
            case OUTBOUND -> Set.of("OUTBOUND");
            case CIB -> Set.of("CIB");
            default -> Set.of();
        };
    }

    private Set<String> ledFileTeams(AuthenticatedUser u) {
        try {
            return fileTeamsFor(teamLeaders.ledTeamCode(u));
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** Fichiers gérés selon le code d'équipe menée : un Team Leader Tchat ou Rafiki n'importe que son canal (Télévente, Digitalisation : fichier Outbound). */
    static Set<String> fileTeamsFor(String ledTeamCode) {
        if (TeamClassifier.isChannel(ledTeamCode) && CATALOG.containsKey(ledTeamCode.trim().toUpperCase(Locale.ROOT))) {
            return Set.of(ledTeamCode.trim().toUpperCase(Locale.ROOT));
        }
        return fileTeamsFor(TeamClassifier.teamOf(ledTeamCode));
    }

    /** La QA importe pour toutes les équipes ; un Team Leader uniquement pour la sienne. */
    boolean canImportTeam(AuthenticatedUser u, String teamCode) {
        if (canImport(u)) return true;
        return u != null && "TEAM_LEADER".equalsIgnoreCase(u.role()) && ledFileTeams(u).contains(teamCode);
    }

    static TeamDef team(String code) {
        TeamDef t = CATALOG.get(code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
        if (t == null) throw ApiException.badRequest("Équipe inconnue : " + String.join(", ", CATALOG.keySet()) + ".");
        return t;
    }

    // ───────────── Droits ─────────────

    static boolean canImport(AuthenticatedUser u) {
        if (u == null) return false;
        String role = u.role() == null ? "" : u.role().toUpperCase(Locale.ROOT);
        String service = u.service() == null ? "" : u.service().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return Set.of("ADMIN", "QA", "QA_SUPERVISOR", "SUPERVISOR").contains(role) || Set.of("QUALITY_ASSURANCE", "SUPERVISEUR_QA").contains(service);
    }

    private void requireCanView(AuthenticatedUser u, TeamDef t) {
        if (u == null) throw ApiException.unauthorized("Non connecté.");
        if (canImport(u) || "RH".equalsIgnoreCase(u.role())) return;
        if ("TEAM_LEADER".equalsIgnoreCase(u.role()) && ledFileTeams(u).contains(t.code())) return;
        throw ApiException.forbidden("Réservé à la QA, au Superviseur, au RH et au Team Leader de l'équipe.");
    }

    // ───────────── Import ─────────────

    @Transactional
    public ImportReport importFile(AuthenticatedUser requester, MultipartFile file, String teamCode, String from, String to,
                                   String countryCode, boolean dryRun) {
        TeamDef t = team(teamCode);
        if (!canImportTeam(requester, t.code())) {
            throw ApiException.forbidden("Import réservé à la QA, au Superviseur et au Team Leader de l'équipe « " + t.label() + " ».");
        }
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Le fichier est requis.");
        Workbook wb;
        try {
            KpiFileReader.Result read = KpiFileReader.read(file.getBytes(), file.getOriginalFilename(), file.getContentType());
            if (read.kind() == KpiFileReader.Kind.IMAGE) {
                if (!ocr.isConfigured()) {
                    throw ApiException.badRequest("Une capture d'écran ne peut être lue que si l'OCR local est configuré. Importez plutôt le fichier "
                            + "Excel, CSV ou la présentation PowerPoint du rapport hebdo.");
                }
                wb = ManualKpiEntryService.buildWorkbookFromOcrGrid(ocr.extractRawJson(file));
            } else {
                wb = read.workbook();
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Impossible de lire ce fichier : " + e.getMessage());
        }
        Parsed p;
        try (wb) {
            p = parse(wb, t, LocalDate.now());
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("Impossible de lire ce fichier : " + e.getMessage());
        }

        LocalDate start = parseDate(from), end = parseDate(to);
        boolean detected = p.from() != null;
        if (start == null) start = p.from();
        if (end == null) end = p.to();
        if (start != null && end == null) end = start.plusDays(6);
        if (start != null && end.isBefore(start)) throw ApiException.badRequest("La date de fin précède la date de début.");

        List<AgentLine> lines = matchAgents(p.lines());
        int matched = (int) lines.stream().filter(l -> l.userId() != null).count();
        String batchId = null;
        int replaced = 0;
        boolean save = !dryRun;
        if (save) {
            if (start == null) throw ApiException.badRequest("Indiquez la période (du … au …) : elle n'a pas été trouvée dans le fichier.");
            if (lines.isEmpty()) throw ApiException.badRequest("Aucune ligne agent reconnue dans ce fichier.");
            batchId = UUID.randomUUID().toString();
            // Réimporter la même semaine remplace la version précédente (jamais de doublon).
            replaced = jdbc.update("DELETE FROM dbo.TeamPerfRecords WHERE Team = ? AND PeriodStart = ? AND PeriodEnd = ?",
                    t.code(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
            String country = countryCode == null || countryCode.isBlank() ? null : countryCode.trim().toUpperCase(Locale.ROOT);
            for (AgentLine l : lines) {
                jdbc.update("INSERT INTO dbo.TeamPerfRecords (Team, PeriodStart, PeriodEnd, UserId, AgentName, NameKey, MetricsJson, CountryCode, BatchId, ImportedBy) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        t.code(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end), l.userId(), l.agentName(), nameKey(l.agentName()),
                        write(l.values()), country, batchId, requester.username());
            }
            notifyAgents(t, start, end, lines);
            audit.record(requester.username(), "IMPORT_TEAM_PERF_FILE", t.label() + " — " + start + " au " + end + " — " + lines.size()
                    + " agent(s), " + matched + " rattaché(s) — fichier " + Objects.toString(file.getOriginalFilename(), "(sans nom)"));
        }
        return new ImportReport(t.code(), t.label(), start, end, detected, p.periodText(), t.fields(), p.recognized(), p.ignored(), p.missing(),
                lines, matched, lines.size() - matched, save, batchId, replaced);
    }

    /** Mise à jour automatique côté agent : chacun est prévenu que ses chiffres de la semaine sont publiés. */
    private void notifyAgents(TeamDef t, LocalDate start, LocalDate end, List<AgentLine> lines) {
        if (notifications == null) return;
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM");
        for (AgentLine l : lines) {
            if (l.userId() == null) continue;
            users.findById(l.userId()).ifPresent(u -> {
                Double p = d(l.values(), "productivity");
                try {
                    notifications.save(com.ecobank.rccportal.model.RccNotification.builder().targetUser(u).isRead(false)
                            .content("Vos performances " + t.label() + " du " + start.format(f) + " au " + end.format(f) + " sont disponibles"
                                    + (p != null ? " : " + fmt(p) + " % de l'objectif." : ".") + " Consultez « Ma Performance ».")
                            .build());
                } catch (RuntimeException ignored) {
                    // la notification ne doit jamais bloquer l'import
                }
            });
        }
    }

    record Parsed(List<AgentLine> lines, Map<String, String> recognized, List<String> ignored, List<String> missing,
                  LocalDate from, LocalDate to, String periodText) {}

    /** Lecture du classeur : ligne d'en-tête, colonne des noms, valeurs, indicateurs calculés, période. */
    static Parsed parse(Workbook wb, TeamDef t, LocalDate today) {
        DataFormatter fmt = new DataFormatter(Locale.FRANCE);
        FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
        List<List<String>> grid = new ArrayList<>();
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int col = 0; col < Math.max(0, row.getLastCellNum()); col++) {
                        Cell cell = row.getCell(col);
                        String v;
                        try {
                            v = cellText(cell, eval, fmt);
                        } catch (RuntimeException e) {
                            v = cell == null ? "" : cell.toString();
                        }
                        cells.add(v.replace(' ', ' ').replace("​", "").replace('\n', ' ').trim());
                    }
                }
                grid.add(cells);
            }
            grid.add(List.of());
        }

        // En-tête : la ligne qui reconnaît le plus d'indicateurs de l'équipe.
        int headerRow = -1;
        Map<Integer, Field> best = Map.of();
        for (int r = 0; r < grid.size(); r++) {
            Map<Integer, Field> m = mapColumns(grid.get(r), t);
            if (m.size() > best.size()) { best = m; headerRow = r; }
        }
        if (headerRow < 0 || best.size() < 2) {
            throw ApiException.badRequest("Les colonnes des indicateurs « " + t.label() + " » n'ont pas été reconnues dans ce fichier. Attendu par exemple : "
                    + String.join(", ", t.fields().stream().filter(x -> !x.computed()).map(Field::label).toList()) + ".");
        }
        List<String> header = grid.get(headerRow);
        Map<String, String> recognized = new LinkedHashMap<>();
        best.forEach((col, fld) -> recognized.put(header.get(col), fld.label()));

        int nameCol = findNameColumn(grid, headerRow, best.keySet());
        List<String> ignored = new ArrayList<>();
        for (int col = 0; col < header.size(); col++) {
            if (col != nameCol && !best.containsKey(col) && !header.get(col).isBlank()) ignored.add(header.get(col));
        }

        // Pourcentages écrits en fraction (1,75 au lieu de 175 %) : détectés colonne par colonne.
        Map<Integer, Boolean> fraction = new HashMap<>();
        for (Map.Entry<Integer, Field> e : best.entrySet()) {
            if (!"PCT".equals(e.getValue().unit())) continue;
            boolean allSmall = true, any = false;
            for (int r = headerRow + 1; r < grid.size() && r < headerRow + 400; r++) {
                String raw = cell(grid.get(r), e.getKey());
                if (raw.isBlank() || raw.contains("%")) continue;
                Double d = number(raw);
                if (d == null) continue;
                any = true;
                if (d > 2.5) allSmall = false;
            }
            fraction.put(e.getKey(), any && allSmall);
        }

        List<AgentLine> lines = new ArrayList<>();
        int blanks = 0;
        for (int r = headerRow + 1; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            String name = cell(row, nameCol);
            boolean empty = row.stream().allMatch(String::isBlank);
            if (empty) { if (++blanks >= 2 && !lines.isEmpty()) break; continue; }
            blanks = 0;
            if (name.isBlank() || isTotal(name) || number(name) != null) continue;
            if (mapColumns(row, t).size() >= 2) break; // un autre tableau commence
            Map<String, Object> values = new LinkedHashMap<>();
            List<String> notes = new ArrayList<>();
            for (Map.Entry<Integer, Field> e : best.entrySet()) {
                Field fld = e.getValue();
                String raw = cell(row, e.getKey());
                if (raw.isBlank()) continue;
                if ("TEXT".equals(fld.unit())) { values.put(fld.key(), raw); continue; }
                Double v = "SECONDS".equals(fld.unit()) ? seconds(raw) : number(raw);
                if (v == null) { notes.add(fld.label() + " illisible : « " + raw + " »"); continue; }
                if ("PCT".equals(fld.unit()) && Boolean.TRUE.equals(fraction.get(e.getKey()))) v = v * 100;
                values.put(fld.key(), round1(v));
            }
            if (values.values().stream().noneMatch(v -> v instanceof Double)) continue;
            complete(t, values, notes);
            lines.add(new AgentLine(name, null, null, null, values, level(values), notes));
        }

        Collection<Field> found = best.values();
        List<String> missing = t.fields().stream()
                .filter(fld -> !found.contains(fld))
                .map(fld -> fld.label() + (fld.computed() ? " (calculé : " + fld.formula() + ")" : ""))
                .toList();

        String allText = String.join(" ", grid.stream().limit(Math.max(headerRow, 1) + 1L).flatMap(List::stream).toList());
        LocalDate[] period = detectPeriod(allText, today);
        return new Parsed(lines, recognized, ignored, missing, period == null ? null : period[0], period == null ? null : period[1],
                period == null ? null : period[0] + " → " + period[1]);
    }

    /**
     * Texte d'une cellule sans ambiguïté : les nombres gardent leur valeur exacte (point décimal), un
     * pourcentage Excel (1,75 affiché « 175 % ») devient « 175% », une durée devient « h:mm:ss ».
     */
    static String cellText(Cell cell, FormulaEvaluator eval, DataFormatter fmt) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                type = eval.evaluateFormulaCell(cell);
            } catch (RuntimeException e) {
                type = cell.getCachedFormulaResultType();
            }
        }
        if (type == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            String format = cell.getCellStyle() == null ? null : cell.getCellStyle().getDataFormatString();
            if (format != null && format.contains("%")) return plain(v * 100) + "%";
            if (DateUtil.isCellDateFormatted(cell)) {
                if (v < 1) {
                    long s = Math.round(v * 86400);
                    return s / 3600 + ":" + String.format("%02d:%02d", (s % 3600) / 60, s % 60);
                }
                return fmt.formatCellValue(cell, eval);
            }
            return plain(v);
        }
        if (type == CellType.STRING) return cell.getStringCellValue();
        if (type == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        return "";
    }

    private static String plain(double v) {
        return java.math.BigDecimal.valueOf(Math.round(v * 10000) / 10000.0).stripTrailingZeros().toPlainString();
    }

    /** Colonnes reconnues : correspondance exacte d'abord, puis par mots-clés (« ~A+B »). */
    static Map<Integer, Field> mapColumns(List<String> header, TeamDef t) {
        Map<Integer, Field> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int col = 0; col < header.size(); col++) {
                if (out.containsKey(col)) continue;
                String code = code(header.get(col));
                if (code.isEmpty() || code.length() > 60) continue;
                for (Field fld : t.fields()) {
                    if (used.contains(fld.key())) continue;
                    boolean hit = false;
                    for (String a : fld.aliases()) {
                        if (pass == 0 && !a.startsWith("~") && a.equals(code)) { hit = true; break; }
                        if (pass == 1 && a.startsWith("~") && Arrays.stream(a.substring(1).split("\\+")).allMatch(code::contains)) { hit = true; break; }
                    }
                    if (hit) { out.put(col, fld); used.add(fld.key()); break; }
                }
            }
        }
        return out;
    }

    private static int findNameColumn(List<List<String>> grid, int headerRow, Set<Integer> metricCols) {
        List<String> header = grid.get(headerRow);
        for (int col = 0; col < header.size(); col++) {
            if (!metricCols.contains(col) && NAME_HEADERS.contains(code(header.get(col)))) return col;
        }
        // Sinon : la première colonne (hors indicateurs) remplie de texte sous l'en-tête — la colonne des noms est souvent sans titre.
        int bestCol = 0, bestScore = -1;
        int width = grid.subList(headerRow, Math.min(grid.size(), headerRow + 60)).stream().mapToInt(List::size).max().orElse(1);
        for (int col = 0; col < width; col++) {
            if (metricCols.contains(col)) continue;
            int score = 0;
            for (int r = headerRow + 1; r < Math.min(grid.size(), headerRow + 60); r++) {
                String v = cell(grid.get(r), col);
                if (!v.isBlank() && number(v) == null && v.length() > 3) score++;
            }
            if (score > bestScore) { bestScore = score; bestCol = col; }
        }
        return bestCol;
    }

    /** Indicateurs calculés quand le fichier ne les donne pas — mêmes règles que les rapports hebdo. */
    static void complete(TeamDef t, Map<String, Object> v, List<String> notes) {
        if ("INBOUND_MAIL".equals(t.code()) && !v.containsKey("totalActivities")) {
            Double cis = d(v, "cis"), mails = d(v, "mails"), req = d(v, "requests");
            if (cis != null || mails != null || req != null) v.put("totalActivities", round1(z(cis) + z(mails) + z(req)));
        }
        if (("TCHAT".equals(t.code()) || "RAFIKI".equals(t.code())) && !v.containsKey("totalProduction")) {
            Double main = d(v, "TCHAT".equals(t.code()) ? "liveChat" : "resolved"), other = d(v, "otherActivities");
            if (main != null || other != null) v.put("totalProduction", round1(z(main) + z(other)));
        }
        if ("OUTBOUND".equals(t.code())) {
            Double calls = d(v, "calls"), reached = d(v, "reached"), sales = d(v, "sales");
            if (!v.containsKey("reachRate") && calls != null && calls > 0 && reached != null) v.put("reachRate", round1(reached * 100 / calls));
            if (!v.containsKey("conversion") && reached != null && reached > 0 && sales != null) v.put("conversion", round1(sales * 100 / reached));
        }
        Double volume = d(v, t.volumeKey()), days = d(v, "daysWorked");
        if (has(t, "avgPerDay") && !v.containsKey("avgPerDay") && volume != null && days != null && days > 0) v.put("avgPerDay", round1(volume / days));
        if (has(t, "presence") && !v.containsKey("presence") && days != null) v.put("presence", (double) Math.round(Math.min(100, days * 20)));
        Double computed = null;
        Double avg = d(v, "avgPerDay"), target = d(v, "targetPerDay"), weekly = d(v, "weeklyTarget");
        if (has(t, "targetPerDay") && avg != null && target != null && target > 0) computed = (double) Math.round(avg * 100 / target);
        else if (has(t, "weeklyTarget") && volume != null && weekly != null && weekly > 0) computed = (double) Math.round(volume * 100 / weekly);
        if (computed != null) {
            Double given = d(v, "productivity");
            if (given == null) v.put("productivity", computed);
            else if (notes != null && has(t, "targetPerDay") && Math.abs(given - computed) > 3) {
                notes.add("Productivité du fichier (" + fmt(given) + " %) différente du calcul Moy/jour ÷ Target (" + fmt(computed) + " %)");
            }
        }
    }

    private static boolean has(TeamDef t, String key) {
        return t.fields().stream().anyMatch(f -> f.key().equals(key));
    }

    /** ≥ 100 % : objectif atteint (vert) · 90 à 99 % : proche (orange) · < 90 % : à accompagner (rouge). */
    static String level(Map<String, Object> v) {
        Double p = d(v, "productivity"), days = d(v, "daysWorked");
        if (p == null || (days != null && days == 0)) return null; // absent toute la semaine : non évalué
        return p >= 100 ? "GOOD" : p >= 90 ? "WARN" : "BAD";
    }

    // ───────────── Période ─────────────

    private static final Pattern DAY_DAY_MONTH = Pattern.compile("(?i)(\\d{1,2})\\s*(?:-|–|au|a)\\s*(\\d{1,2})\\s+([A-Z]{3,9})\\.?(?:\\s+(20\\d{2}))?");
    private static final Pattern DAY_MONTH_DAY_MONTH = Pattern.compile("(?i)(\\d{1,2})\\s+([A-Z]{3,9})\\.?\\s*(?:-|–|au|a)\\s*(\\d{1,2})\\s+([A-Z]{3,9})\\.?(?:\\s+(20\\d{2}))?");
    private static final Pattern NUMERIC = Pattern.compile("(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\s*(?:-|–|au|a)\\s*(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?");

    static LocalDate[] detectPeriod(String text, LocalDate today) {
        String s = strip(text).toUpperCase(Locale.ROOT);
        try {
            Matcher m = DAY_MONTH_DAY_MONTH.matcher(s);
            while (m.find()) {
                Integer m1 = MONTHS.get(m.group(2)), m2 = MONTHS.get(m.group(4));
                if (m1 == null || m2 == null) continue;
                int y2 = m.group(5) != null ? Integer.parseInt(m.group(5)) : yearFor(m2, today);
                int y1 = m1 > m2 ? y2 - 1 : y2;
                return new LocalDate[]{LocalDate.of(y1, m1, Integer.parseInt(m.group(1))), LocalDate.of(y2, m2, Integer.parseInt(m.group(3)))};
            }
            m = DAY_DAY_MONTH.matcher(s);
            while (m.find()) {
                Integer mo = MONTHS.get(m.group(3));
                if (mo == null) continue;
                int y = m.group(4) != null ? Integer.parseInt(m.group(4)) : yearFor(mo, today);
                int d1 = Integer.parseInt(m.group(1)), d2 = Integer.parseInt(m.group(2));
                LocalDate end = LocalDate.of(y, mo, d2);
                LocalDate start = d1 <= d2 ? LocalDate.of(y, mo, d1) : end.minusMonths(1).withDayOfMonth(d1);
                return new LocalDate[]{start, end};
            }
            m = NUMERIC.matcher(s);
            if (m.find()) {
                int y2 = m.group(6) != null ? fullYear(m.group(6)) : m.group(3) != null ? fullYear(m.group(3)) : yearFor(Integer.parseInt(m.group(5)), today);
                int y1 = m.group(3) != null ? fullYear(m.group(3)) : (Integer.parseInt(m.group(2)) > Integer.parseInt(m.group(5)) ? y2 - 1 : y2);
                return new LocalDate[]{LocalDate.of(y1, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))),
                        LocalDate.of(y2, Integer.parseInt(m.group(5)), Integer.parseInt(m.group(4)))};
            }
        } catch (java.time.DateTimeException ignored) {
            // date impossible (ex. 31 septembre) : la QA saisit la période
        }
        return null;
    }

    private static int yearFor(int month, LocalDate today) {
        return month > today.getMonthValue() + 1 ? today.getYear() - 1 : today.getYear();
    }

    private static int fullYear(String y) {
        int v = Integer.parseInt(y);
        return v < 100 ? 2000 + v : v;
    }

    // ───────────── Rattachement des agents ─────────────

    List<AgentLine> matchAgents(List<AgentLine> lines) {
        List<User> all = users.findAll();
        List<AgentLine> out = new ArrayList<>();
        for (AgentLine l : lines) {
            User u = matchUser(l.agentName(), all);
            List<String> notes = new ArrayList<>(l.notes());
            if (u == null) notes.add(0, "Agent non trouvé dans le portail : la ligne est gardée et sera rattachée dès que le nom correspondra.");
            out.add(new AgentLine(l.agentName(), u == null ? null : u.getId(), u == null ? null : u.getUsername(),
                    u == null ? null : (u.getName() != null ? u.getName() : u.getUsername()), l.values(), l.level(), notes));
        }
        return out;
    }

    /** Même personne malgré l'ordre des noms, les accents ou un prénom en plus/en moins (« TIERO Hulda » ↔ « Hulda Chance Eunice TIERO »). */
    static User matchUser(String fileName, List<User> all) {
        Set<String> tokens = tokens(fileName);
        if (tokens.isEmpty()) return null;
        String key = nameKey(fileName);
        List<User> subset = new ArrayList<>();
        User bestOverlap = null;
        int bestScore = 0;
        boolean tie = false;
        for (User u : all) {
            if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(fileName.trim())) return u;
            if (u.getName() == null || u.getName().isBlank()) continue;
            if (nameKey(u.getName()).equals(key)) return u;
            Set<String> ut = tokens(u.getName());
            Set<String> common = new HashSet<>(ut);
            common.retainAll(tokens);
            if (common.size() >= 2 && (tokens.containsAll(ut) || ut.containsAll(tokens))) subset.add(u);
            if (common.size() >= 2) {
                if (common.size() > bestScore) { bestScore = common.size(); bestOverlap = u; tie = false; }
                else if (common.size() == bestScore) tie = true;
            }
        }
        if (subset.size() == 1) return subset.get(0);
        if (subset.size() > 1) {
            List<User> active = subset.stream().filter(u -> !Boolean.FALSE.equals(u.getAccountEnabled())).toList();
            return active.size() == 1 ? active.get(0) : null;
        }
        return tie ? null : bestOverlap;
    }

    static String nameKey(String name) {
        return String.join(" ", new TreeSet<>(tokens(name)));
    }

    private static Set<String> tokens(String name) {
        if (name == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String w : strip(name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")) {
            if (w.length() > 1) out.add(w);
        }
        return out;
    }

    // ───────────── Lecture ─────────────

    record Rec(long id, String team, LocalDate from, LocalDate to, Long userId, String agentName, String nameKey,
               Map<String, Object> values, String batchId) {}

    private List<Rec> records(String sql, Object... args) {
        List<Rec> out = new ArrayList<>();
        try {
            jdbc.query(sql, rs -> {
                long uid = rs.getLong("UserId");
                Long userId = rs.wasNull() ? null : uid;
                out.add(new Rec(rs.getLong("RecordId"), rs.getString("Team"), rs.getDate("PeriodStart").toLocalDate(), rs.getDate("PeriodEnd").toLocalDate(),
                        userId, rs.getString("AgentName"), rs.getString("NameKey"), read(rs.getString("MetricsJson")), rs.getString("BatchId")));
            }, args);
        } catch (org.springframework.dao.DataAccessException e) {
            // table pas encore créée
        }
        return out;
    }

    private static final String COLS = "SELECT RecordId, Team, PeriodStart, PeriodEnd, UserId, AgentName, NameKey, MetricsJson, BatchId FROM dbo.TeamPerfRecords ";

    public List<Period> periods(AuthenticatedUser requester, String teamCode) {
        TeamDef t = team(teamCode);
        requireCanView(requester, t);
        return periods(t.code());
    }

    private List<Period> periods(String team) {
        List<Period> out = new ArrayList<>();
        try {
            jdbc.query("SELECT PeriodStart, PeriodEnd, COUNT(*) AS N, MAX(BatchId) AS B FROM dbo.TeamPerfRecords WHERE Team = ? "
                            + "GROUP BY PeriodStart, PeriodEnd ORDER BY PeriodStart DESC",
                    rs -> { out.add(new Period(rs.getDate("PeriodStart").toLocalDate(), rs.getDate("PeriodEnd").toLocalDate(), rs.getInt("N"), rs.getString("B"))); }, team);
        } catch (org.springframework.dao.DataAccessException ignored) {
            // table pas encore créée
        }
        return out;
    }

    /** Tableau de l'équipe pour une période (la plus récente par défaut), trié par productivité comme le rapport hebdo. */
    public TeamSheet sheet(AuthenticatedUser requester, String teamCode, String from, String to) {
        TeamDef t = team(teamCode);
        requireCanView(requester, t);
        List<Period> periods = periods(t.code());
        LocalDate start = parseDate(from), end = parseDate(to);
        if (start == null && !periods.isEmpty()) { start = periods.get(0).from(); end = periods.get(0).to(); }
        if (start == null) return new TeamSheet(t.code(), t.label(), null, null, t.fields(), List.of(), Map.of(), periods);
        if (end == null) end = start.plusDays(6);
        Map<Long, User> byId = new HashMap<>();
        users.findAll().forEach(u -> byId.put(u.getId(), u));
        List<AgentLine> rows = new ArrayList<>();
        for (Rec r : records(COLS + "WHERE Team = ? AND PeriodStart = ? AND PeriodEnd = ?", t.code(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end))) {
            User u = r.userId() == null ? null : byId.get(r.userId());
            if (u != null && !com.ecobank.rccportal.util.Filiale.matches(u.getAffiliateBranch())) continue; // agent de l'autre filiale
            rows.add(new AgentLine(r.agentName(), r.userId(), u == null ? null : u.getUsername(), u == null ? null : u.getName(), r.values(), level(r.values()),
                    u == null ? List.of("Agent non rattaché à un compte du portail") : List.of()));
        }
        rows.sort(Comparator.comparing((AgentLine l) -> d(l.values(), "productivity"), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentLine::agentName, String.CASE_INSENSITIVE_ORDER));
        return new TeamSheet(t.code(), t.label(), start, end, t.fields(), rows, totals(t, rows.stream().map(AgentLine::values).toList()), periods);
    }

    /** Totaux (volumes) et moyennes (taux) d'une liste de lignes. */
    static Map<String, Object> totals(TeamDef t, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field fld : t.fields()) {
            if ("TEXT".equals(fld.unit())) continue;
            List<Double> vals = rows.stream().map(v -> d(v, fld.key())).filter(Objects::nonNull).toList();
            if (vals.isEmpty()) continue;
            double sum = vals.stream().mapToDouble(Double::doubleValue).sum();
            out.put(fld.key(), "COUNT".equals(fld.unit()) && !"daysWorked".equals(fld.key()) ? round1(sum) : round1(sum / vals.size()));
        }
        return out;
    }

    public void deleteBatch(AuthenticatedUser requester, String batchId) {
        List<String> teams = jdbc.queryForList("SELECT DISTINCT Team FROM dbo.TeamPerfRecords WHERE BatchId = ?", String.class, batchId);
        if (teams.isEmpty()) throw ApiException.notFound("Import introuvable.");
        if (!canImportTeam(requester, teams.get(0))) throw ApiException.forbidden("Réservé à la QA et au Team Leader de l'équipe.");
        int n = jdbc.update("DELETE FROM dbo.TeamPerfRecords WHERE BatchId = ?", batchId);
        if (n == 0) throw ApiException.notFound("Import introuvable.");
        audit.record(requester.username(), "DELETE_TEAM_PERF_FILE", "Import " + batchId + " supprimé (" + n + " ligne(s))");
    }

    /** Les performances de l'agent connecté : ses semaines, son rang et la moyenne de son équipe. */
    public MyPerformance mine(AuthenticatedUser requester) {
        if (requester == null) throw ApiException.unauthorized("Non connecté.");
        User me = users.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
        if (me == null) return new MyPerformance(null, null, List.of(), List.of());
        String key = nameKey(me.getName() == null ? me.getUsername() : me.getName());
        List<Rec> mineRecs = records(COLS + "WHERE UserId = ? OR (UserId IS NULL AND NameKey = ?) ORDER BY PeriodStart DESC", me.getId(), key);
        if (mineRecs.isEmpty()) return new MyPerformance(null, null, List.of(), List.of());
        String teamCode = mineRecs.get(0).team();
        TeamDef t = CATALOG.get(teamCode);
        List<MyWeek> weeks = new ArrayList<>();
        for (Rec r : mineRecs.stream().filter(x -> x.team().equals(teamCode)).limit(12).toList()) {
            List<Rec> team = records(COLS + "WHERE Team = ? AND PeriodStart = ? AND PeriodEnd = ?", r.team(), java.sql.Date.valueOf(r.from()), java.sql.Date.valueOf(r.to()));
            Double mine = d(r.values(), "productivity");
            Integer rank = mine == null ? null : 1 + (int) team.stream().map(x -> d(x.values(), "productivity")).filter(p -> p != null && p > mine).count();
            weeks.add(new MyWeek(r.from(), r.to(), r.values(), level(r.values()), rank, team.size(), totalsAsAverages(t, team)));
        }
        return new MyPerformance(t.code(), t.label(), t.fields(), weeks);
    }

    /** Moyenne par agent de l'équipe (pour se comparer : pas le total de l'équipe). */
    private static Map<String, Object> totalsAsAverages(TeamDef t, List<Rec> team) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field fld : t.fields()) {
            if ("TEXT".equals(fld.unit())) continue;
            List<Double> vals = team.stream().map(x -> d(x.values(), fld.key())).filter(Objects::nonNull).toList();
            if (!vals.isEmpty()) out.put(fld.key(), round1(vals.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        }
        return out;
    }

    /** Cumul des semaines commençant dans [start, end] : volumes additionnés, Moy / jour et Productivité recalculées. */
    public Aggregate aggregate(String teamCode, LocalDate start, LocalDate end) {
        TeamDef t = CATALOG.get(teamCode);
        Map<Long, Map<String, Double>> byUser = new HashMap<>();
        Map<String, Map<String, Double>> byName = new HashMap<>();
        if (t == null) return new Aggregate(byUser, byName);
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        Map<String, Long> ids = new HashMap<>();
        for (Rec r : records(COLS + "WHERE Team = ? AND PeriodStart >= ? AND PeriodStart <= ?", t.code(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end))) {
            String k = r.userId() != null ? "#" + r.userId() : r.nameKey();
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(r.values());
            if (r.userId() != null) ids.put(k, r.userId());
        }
        groups.forEach((k, list) -> {
            Map<String, Object> sum = new LinkedHashMap<>();
            for (Field fld : t.fields()) {
                // Volumes additionnés, taux du fichier moyennés ; Moy / jour, Productivité et taux calculés refaits sur les cumuls.
                if ("TEXT".equals(fld.unit()) || fld.computed() && !"totalActivities".equals(fld.key())) continue;
                List<Double> vals = list.stream().map(v -> d(v, fld.key())).filter(Objects::nonNull).toList();
                if (vals.isEmpty()) continue;
                double s = vals.stream().mapToDouble(Double::doubleValue).sum();
                sum.put(fld.key(), "COUNT".equals(fld.unit()) ? round1(s) : round1(s / vals.size()));
            }
            complete(t, sum, null);
            if (list.size() == 1) { // une seule semaine : valeurs du fichier telles quelles
                list.get(0).forEach((key, val) -> { if (val instanceof Number) sum.put(key, ((Number) val).doubleValue()); });
            }
            Map<String, Double> out = new LinkedHashMap<>();
            sum.forEach((key, val) -> { if (val instanceof Number n) out.put(key, n.doubleValue()); });
            if (ids.containsKey(k)) byUser.put(ids.get(k), out);
            else byName.put(k, out);
        });
        return new Aggregate(byUser, byName);
    }

    // ───────────── Utilitaires ─────────────

    private static boolean isTotal(String name) {
        String c = code(name);
        return c.startsWith("TOTAL") || c.startsWith("MOYENNE") || c.equals("SOUS_TOTAL") || c.startsWith("PERFORMANCES");
    }

    static String code(String header) {
        return strip(header == null ? "" : header).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String strip(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static String cell(List<String> row, int col) {
        return col >= 0 && col < row.size() && row.get(col) != null ? row.get(col) : "";
    }

    /** « 175% », « 1 234 », « 12,5 », « 64,404 » (milliers) → nombre ; « 6 mois » → null. */
    static Double number(String raw) {
        if (raw == null) return null;
        String s = raw.replace(' ', ' ').replace(" ", "").trim();
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("N/A") || s.startsWith("#")) return null;
        s = s.replace("%", "").replace(" ", "");
        int comma = s.lastIndexOf(',');
        if (comma != -1 && s.indexOf('.') == -1 && s.length() - comma - 1 == 3 && s.chars().filter(ch -> ch == ',').count() >= 1 && !s.startsWith("0,")) {
            s = s.replace(",", "");
        } else {
            s = s.replace(",", ".");
        }
        if (!s.matches("-?\\d+(\\.\\d+)?")) return null;
        return Double.parseDouble(s);
    }

    /** « 0:03:25 », « 03:25 » ou un nombre de secondes. */
    static Double seconds(String raw) {
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp][Mm])?$").matcher(raw.trim());
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1));
            String meridiem = m.group(4);
            if (meridiem != null) { // heure Excel affichée « 12:16:00 AM » = 0 h 16 min
                if (meridiem.equalsIgnoreCase("AM") && a == 12) a = 0;
                else if (meridiem.equalsIgnoreCase("PM") && a < 12) a += 12;
            }
            return m.group(3) != null || meridiem != null
                    ? a * 3600.0 + Integer.parseInt(m.group(2)) * 60 + (m.group(3) == null ? 0 : Integer.parseInt(m.group(3)))
                    : a * 60.0 + Integer.parseInt(m.group(2));
        }
        return number(raw);
    }

    static Double d(Map<String, Object> v, String key) {
        Object o = v == null || key == null ? null : v.get(key);
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static double z(Double v) {
        return v == null ? 0 : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Date invalide : " + s + " (format AAAA-MM-JJ).");
        }
    }

    private String write(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> read(String s) {
        try {
            Map<String, Object> raw = json.readValue(s == null ? "{}" : s, new TypeReference<LinkedHashMap<String, Object>>() {});
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v instanceof Number n ? (Object) n.doubleValue() : v));
            return out;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Équipe du reporting correspondant à un code de fichier (même codes que TeamClassifier). */
    static TeamClassifier.Team classifierTeam(String code) {
        try {
            return TeamClassifier.Team.valueOf(code);
        } catch (IllegalArgumentException e) {
            return TeamClassifier.Team.OTHER;
        }
    }
}
