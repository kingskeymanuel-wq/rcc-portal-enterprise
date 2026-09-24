package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Disponibilité des GAB par agence — cochée par l'agence elle-même (Portail Agence), visible
 * partout : Portail Agence, Base de connaissances, réponses RAF. Une ligne = l'état COURANT des
 * GAB d'une agence (pas de donnée client).
 */
@Service
public class AtmStatusService {

    /** État d'ensemble du parc de GAB de l'agence. */
    public static final List<String> STATUSES = List.of("EN_SERVICE", "PARTIEL", "SANS_BILLETS", "HORS_SERVICE");
    /** Services proposés à la coche. */
    public static final List<String> SERVICES = List.of("RETRAIT", "DEPOT", "SANS_CARTE", "SOLDE", "PIN", "VISA_MASTERCARD");

    public record AtmStatus(String countryCode, String agencyCode, String agency, String status, List<String> services,
                            Integer gabTotal, Integer gabWorking, String note, String updatedBy, LocalDateTime updatedAt) {}

    public record AtmRequest(String status, List<String> services, Integer gabTotal, Integer gabWorking, String note) {}

    private final JdbcTemplate jdbc;

    public AtmStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AtmStatus> list(String countryCode) {
        String c = country(countryCode);
        try {
            return jdbc.query("SELECT * FROM dbo.AtmAgencyStatus WHERE CountryCode = ? ORDER BY Agency", (rs, i) -> new AtmStatus(
                    rs.getString("CountryCode"), rs.getString("AgencyCode"), rs.getString("Agency"), rs.getString("Status"),
                    split(rs.getString("Services")), (Integer) rs.getObject("GabTotal"), (Integer) rs.getObject("GabWorking"),
                    rs.getString("Note"), rs.getString("UpdatedBy"),
                    rs.getTimestamp("UpdatedAt") == null ? null : rs.getTimestamp("UpdatedAt").toLocalDateTime()), c);
        } catch (RuntimeException e) {
            return List.of(); // table pas encore créée
        }
    }

    public Map<String, AtmStatus> byCode(String countryCode) {
        Map<String, AtmStatus> out = new HashMap<>();
        for (AtmStatus s : list(countryCode)) out.put(s.agencyCode().toUpperCase(Locale.ROOT), s);
        return out;
    }

    public Optional<AtmStatus> current(String countryCode, String agencyCode) {
        if (agencyCode == null) return Optional.empty();
        return Optional.ofNullable(byCode(countryCode).get(agencyCode.toUpperCase(Locale.ROOT)));
    }

    /** Publication par l'agence (validation stricte, jamais de donnée client). */
    @Transactional
    public AtmStatus save(String countryCode, String agencyCode, String agency, AtmRequest r, String updatedBy) {
        String c = country(countryCode);
        String status = normalizeStatus(r.status());
        List<String> services = r.services() == null ? List.of() : r.services().stream()
                .filter(Objects::nonNull).map(x -> x.trim().toUpperCase(Locale.ROOT)).filter(SERVICES::contains).distinct().toList();
        Integer total = r.gabTotal(), working = r.gabWorking();
        if (total != null && (total < 0 || total > 50)) throw ApiException.badRequest("Nombre de GAB invalide (0 à 50).");
        if (working != null && (working < 0 || (total != null && working > total))) {
            throw ApiException.badRequest("Le nombre de GAB en service ne peut pas dépasser le nombre total.");
        }
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        String code = agencyCode.toUpperCase(Locale.ROOT);
        String joined = services.isEmpty() ? null : String.join(",", services);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int updated = jdbc.update("UPDATE dbo.AtmAgencyStatus SET Agency = ?, Status = ?, Services = ?, GabTotal = ?, GabWorking = ?, Note = ?, UpdatedBy = ?, UpdatedAt = ? "
                + "WHERE CountryCode = ? AND AgencyCode = ?", agency, status, joined, total, working, note, updatedBy, now, c, code);
        if (updated == 0) {
            jdbc.update("INSERT INTO dbo.AtmAgencyStatus (CountryCode, AgencyCode, Agency, Status, Services, GabTotal, GabWorking, Note, UpdatedBy, UpdatedAt) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", c, code, agency, status, joined, total, working, note, updatedBy, now);
        }
        return new AtmStatus(c, code, agency, status, services, total, working, note, updatedBy, now.toLocalDateTime());
    }

    static String normalizeStatus(String raw) {
        String s = raw == null ? "" : java.text.Normalizer.normalize(raw.trim().toUpperCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace(' ', '_').replace('-', '_');
        return switch (s) {
            case "EN_SERVICE", "OK", "DISPONIBLE" -> "EN_SERVICE";
            case "PARTIEL", "SERVICE_PARTIEL", "DEGRADE" -> "PARTIEL";
            case "SANS_BILLETS", "SANS_BILLET", "VIDE" -> "SANS_BILLETS";
            case "HORS_SERVICE", "HS", "KO", "PANNE", "EN_PANNE" -> "HORS_SERVICE";
            default -> throw ApiException.badRequest("État GAB inconnu : « " + raw + " » (EN_SERVICE, PARTIEL, SANS_BILLETS ou HORS_SERVICE).");
        };
    }

    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case "EN_SERVICE" -> "en service";
            case "PARTIEL" -> "service partiel";
            case "SANS_BILLETS" -> "sans billets";
            case "HORS_SERVICE" -> "hors service";
            default -> "inconnu";
        };
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String country(String c) {
        if (c == null || c.isBlank()) throw ApiException.badRequest("Filiale obligatoire.");
        return c.trim().toUpperCase(Locale.ROOT);
    }
}
