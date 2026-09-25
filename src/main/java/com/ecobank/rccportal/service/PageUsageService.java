package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Utilisation des onglets du portail (Audit → Utilisation des onglets).
 *
 * <p>Le navigateur envoie une « visite » à l'ouverture d'un onglet puis le temps actif passé
 * dessus (session.js). On cumule par utilisateur / jour / onglet, on ne garde que
 * {@value #RETENTION_DAYS} jours (purge quotidienne) et l'administrateur consulte la
 * répartition en pourcentage, organisée par équipe.</p>
 */
@Service
public class PageUsageService {

    private static final Logger log = LoggerFactory.getLogger(PageUsageService.class);
    public static final int RETENTION_DAYS = 60;
    private static final Pattern PAGE = Pattern.compile("^/[a-z0-9-]{1,60}$");
    private static final int MAX_SECONDS_PER_PING = 300;

    public record PingRequest(String page, Integer seconds, Boolean visit) {}

    public record PageStat(String page, long seconds, long visits) {}

    public record UserUsage(Long id, String username, String name, String service, long seconds, long visits,
                            LocalDateTime lastSeen, List<PageStat> pages) {}

    public record TeamUsage(String team, String label, long seconds, long visits, List<PageStat> pages, List<UserUsage> users) {}

    public record UsageReport(int days, LocalDate from, LocalDate to, long seconds, long visits, int activeUsers,
                              List<PageStat> pages, List<TeamUsage> teams) {}

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    public PageUsageService(JdbcTemplate jdbc, UserRepository userRepository) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
    }

    /** Onglet normalisé (premier segment du chemin), ou null s'il n'est pas mesurable. */
    static String normalizePage(String page) {
        if (page == null) return null;
        String p = page.trim().toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.isEmpty() || p.equals("/")) p = "/dashboard";
        int second = p.indexOf('/', 1);
        if (second > 0) p = p.substring(0, second);
        if (p.endsWith(".html")) p = p.substring(0, p.length() - 5);
        if (!PAGE.matcher(p).matches() || p.equals("/login") || p.equals("/api") || p.equals("/error")) return null;
        return p;
    }

    public void record(AuthenticatedUser requester, PingRequest r) {
        if (requester == null || r == null) return;
        String page = normalizePage(r.page());
        if (page == null) return;
        int seconds = r.seconds() == null ? 0 : Math.max(0, Math.min(MAX_SECONDS_PER_PING, r.seconds()));
        int visits = Boolean.TRUE.equals(r.visit()) ? 1 : 0;
        if (seconds == 0 && visits == 0) return;
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
        if (me == null) return;
        LocalDate today = LocalDate.now();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int updated = jdbc.update("UPDATE dbo.PageUsageDaily SET Seconds = Seconds + ?, Visits = Visits + ?, LastSeenAt = ? "
                + "WHERE UserId = ? AND UsageDate = ? AND PageKey = ?", seconds, visits, now, me.getId(), java.sql.Date.valueOf(today), page);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO dbo.PageUsageDaily (UserId, UsageDate, PageKey, Seconds, Visits, LastSeenAt) VALUES (?, ?, ?, ?, ?, ?)",
                        me.getId(), java.sql.Date.valueOf(today), page, seconds, visits, now);
            } catch (org.springframework.dao.DuplicateKeyException race) {
                jdbc.update("UPDATE dbo.PageUsageDaily SET Seconds = Seconds + ?, Visits = Visits + ?, LastSeenAt = ? "
                        + "WHERE UserId = ? AND UsageDate = ? AND PageKey = ?", seconds, visits, now, me.getId(), java.sql.Date.valueOf(today), page);
            }
        }
    }

    /** Conservation limitée à 2 mois. */
    @Scheduled(cron = "0 20 3 * * *")
    public void purge() {
        try {
            int n = jdbc.update("DELETE FROM dbo.PageUsageDaily WHERE UsageDate < ?", java.sql.Date.valueOf(LocalDate.now().minusDays(RETENTION_DAYS)));
            if (n > 0) log.info("Utilisation des onglets : {} ligne(s) de plus de {} jours supprimée(s).", n, RETENTION_DAYS);
        } catch (RuntimeException e) {
            log.warn("Purge de l'utilisation des onglets impossible : {}", e.getMessage());
        }
    }

    public UsageReport report(AuthenticatedUser requester, Integer daysParam) {
        if (requester == null || !"ADMIN".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Réservé aux administrateurs.");
        }
        int days = daysParam == null ? RETENTION_DAYS : Math.max(1, Math.min(RETENTION_DAYS, daysParam));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days - 1L);

        record Raw(long userId, String page, long seconds, long visits, LocalDateTime last) {}
        List<Raw> raws = jdbc.query("SELECT UserId, PageKey, SUM(CAST(Seconds AS BIGINT)) AS S, SUM(CAST(Visits AS BIGINT)) AS V, MAX(LastSeenAt) AS L "
                        + "FROM dbo.PageUsageDaily WHERE UsageDate >= ? GROUP BY UserId, PageKey",
                (rs, i) -> new Raw(rs.getLong("UserId"), rs.getString("PageKey"), rs.getLong("S"), rs.getLong("V"),
                        rs.getTimestamp("L") == null ? null : rs.getTimestamp("L").toLocalDateTime()),
                java.sql.Date.valueOf(from));

        Map<Long, List<Raw>> byUser = new LinkedHashMap<>();
        for (Raw r : raws) byUser.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);

        Map<Long, List<String[]>> services = new HashMap<>(); // userId → [code, name]
        if (!byUser.isEmpty()) {
            jdbc.query("SELECT us.USER_ID, s.CODE, s.NAME FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID",
                    rs -> { services.computeIfAbsent(rs.getLong("USER_ID"), k -> new ArrayList<>()).add(new String[]{rs.getString("CODE"), rs.getString("NAME")}); });
        }
        Map<Long, User> users = new HashMap<>();
        userRepository.findAllById(byUser.keySet()).forEach(u -> users.put(u.getId(), u));

        Map<String, String> teamLabels = new LinkedHashMap<>();
        Map<String, List<UserUsage>> teamUsers = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Raw>> e : byUser.entrySet()) {
            User u = users.get(e.getKey());
            if (u == null) continue;
            List<String[]> svc = services.getOrDefault(u.getId(), List.of());
            TeamClassifier.Team team = TeamClassifier.classify(u.getActivity(), svc.stream().map(s -> s[0]).toList());
            String key, label;
            if (team != TeamClassifier.Team.OTHER) {
                key = team.name();
                label = team.label;
            } else if (!svc.isEmpty() && svc.get(0)[1] != null) {
                key = "SVC_" + String.valueOf(svc.get(0)[0]).toUpperCase(Locale.ROOT);
                label = svc.get(0)[1];
            } else {
                key = "OTHER";
                label = "Sans équipe";
            }
            teamLabels.putIfAbsent(key, label);
            List<PageStat> pages = e.getValue().stream().map(r -> new PageStat(r.page(), r.seconds(), r.visits()))
                    .sorted(Comparator.comparingLong(PageStat::seconds).reversed().thenComparing(Comparator.comparingLong(PageStat::visits).reversed()))
                    .toList();
            long sec = pages.stream().mapToLong(PageStat::seconds).sum();
            long vis = pages.stream().mapToLong(PageStat::visits).sum();
            LocalDateTime last = e.getValue().stream().map(Raw::last).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            String serviceNames = svc.isEmpty() ? null : String.join(", ", svc.stream().map(s -> s[1]).filter(Objects::nonNull).toList());
            teamUsers.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new UserUsage(u.getId(), u.getUsername(), u.getName(), serviceNames, sec, vis, last, pages));
        }

        List<TeamUsage> teams = new ArrayList<>();
        for (Map.Entry<String, List<UserUsage>> e : teamUsers.entrySet()) {
            List<UserUsage> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingLong(UserUsage::seconds).reversed().thenComparing(UserUsage::visits, Comparator.reverseOrder()));
            teams.add(new TeamUsage(e.getKey(), teamLabels.get(e.getKey()), list.stream().mapToLong(UserUsage::seconds).sum(),
                    list.stream().mapToLong(UserUsage::visits).sum(), merge(list.stream().flatMap(x -> x.pages().stream()).toList()), list));
        }
        teams.sort(Comparator.comparingInt((TeamUsage t) -> teamOrder(t.team())).thenComparing(TeamUsage::label, String.CASE_INSENSITIVE_ORDER));
        List<PageStat> all = merge(teams.stream().flatMap(t -> t.pages().stream()).toList());
        return new UsageReport(days, from, to, all.stream().mapToLong(PageStat::seconds).sum(), all.stream().mapToLong(PageStat::visits).sum(),
                teams.stream().mapToInt(t -> t.users().size()).sum(), all, teams);
    }

    private static int teamOrder(String key) {
        return switch (key) {
            case "INBOUND_VOICE" -> 0;
            case "INBOUND_MAIL" -> 1;
            case "CIB" -> 2;
            case "OUTBOUND" -> 3;
            case "OTHER" -> 9;
            default -> 5;
        };
    }

    private static List<PageStat> merge(List<PageStat> stats) {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (PageStat p : stats) {
            long[] a = m.computeIfAbsent(p.page(), k -> new long[2]);
            a[0] += p.seconds();
            a[1] += p.visits();
        }
        return m.entrySet().stream().map(e -> new PageStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(PageStat::seconds).reversed().thenComparing(Comparator.comparingLong(PageStat::visits).reversed()))
                .toList();
    }
}
