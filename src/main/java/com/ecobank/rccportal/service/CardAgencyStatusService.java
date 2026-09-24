package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CardAgencyDtos.*;
import com.ecobank.rccportal.model.CardAgencyStatus;
import com.ecobank.rccportal.repository.CardAgencyStatusRepository;
import com.ecobank.rccportal.util.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * « Point de disponibilité par agence » de l'onglet Disponibilité des cartes : la filiale transmet
 * chaque jour un tableau DATE | AGENCE | CARTE | CODE | TYPE DE CARTE ; QA le colle tel quel (ou
 * saisit une ligne), les agents voient agence par agence si la carte ET le code PIN sont là.
 */
@Service
public class CardAgencyStatusService {

    private static final Logger log = LoggerFactory.getLogger(CardAgencyStatusService.class);

    static final List<String> STATUSES = List.of("OK", "FAIBLE", "RUPTURE");
    private static final Pattern DATE = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern AGENCY_CODE = Pattern.compile("^(.*?)[\\s-]+([A-Z]{1,3}\\d{1,4})$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> HEADERS = Set.of("DATE", "AGENCE", "AGENCES", "CARTE", "CARTES", "CODE", "CODES", "PIN",
            "TYPE DE CARTE", "TYPES DE CARTE", "TYPE DE CARTES", "TYPES DE CARTES", "TYPE");

    /** Point transmis le 24/09/2026 pour la Côte d'Ivoire — chargé si la filiale n'a encore aucune ligne. */
    static final String CI_INITIAL_REPORT = """
            DATE\tAGENCE\tCARTE\tCODE\tTYPE DE CARTE
            24-09-2026\tNIANGON K27\tOK\tOK\tCLASSIC, PLATINIUM, XPRESS, MX, GOLD
            \tAGHIEN K10\tOK\tOK\tCLASSIC, PLATINIUM, MX, GOLD
            \tBOUAKE K02\tOK\tOK\tCLASSIC, PLATINIUM, MX, GOLD
            \tBEL AIR K45\tOK\tOK\tCLASSIC, PLATINIUM, MX, GOLD
            """;

    private final CardAgencyStatusRepository repository;

    public CardAgencyStatusService(CardAgencyStatusRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedCoteDIvoire() {
        try {
            if (repository.countByCountryCodeIgnoreCase("CI") == 0) {
                PasteResult r = importPaste(new PasteRequest("CI", CI_INITIAL_REPORT, null), "Point filiale CI");
                log.info("Disponibilité des cartes : point CI du {} chargé ({} agences).", r.reportDate(), r.imported());
            }
        } catch (RuntimeException e) {
            log.warn("Disponibilité des cartes par agence : chargement initial CI impossible ({}).", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public AgencyReport report(String countryCode, LocalDate date) {
        String country = country(countryCode);
        List<CardAgencyStatus> all = repository.findByCountryCodeIgnoreCaseOrderByReportDateDescAgencyAsc(country);
        List<LocalDate> dates = all.stream().map(CardAgencyStatus::getReportDate).distinct().toList();
        LocalDate selected = date != null && dates.contains(date) ? date : (dates.isEmpty() ? null : dates.get(0));
        List<AgencyRow> rows = all.stream().filter(s -> s.getReportDate().equals(selected)).map(CardAgencyStatusService::toRow).toList();
        TreeSet<String> types = new TreeSet<>();
        all.forEach(s -> types.addAll(splitTypes(s.getCardTypes())));
        return new AgencyReport(country, selected, dates, rows, new ArrayList<>(types));
    }

    @Transactional
    public AgencyRow save(Long id, AgencyRowRequest request, String updatedBy) {
        String country = country(request.countryCode());
        String[] agency = splitAgency(request.agency());
        String code = request.agencyCode() != null && !request.agencyCode().isBlank() ? request.agencyCode().trim().toUpperCase() : agency[1];
        CardAgencyStatus row = id != null
                ? repository.findById(id).orElseThrow(() -> ApiException.notFound("Ligne introuvable."))
                : repository.findByCountryCodeIgnoreCaseAndReportDateAndAgencyIgnoreCase(country, request.reportDate(), agency[0])
                        .orElseGet(CardAgencyStatus::new);
        row.setCountryCode(country);
        row.setReportDate(request.reportDate());
        row.setAgency(agency[0]);
        row.setAgencyCode(code);
        row.setCardStatus(status(request.cardStatus()));
        row.setPinStatus(status(request.pinStatus()));
        row.setCardTypes(joinTypes(request.cardTypes()));
        row.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        row.setUpdatedBy(updatedBy);
        CardAgencyStatus saved = repository.save(row);
        if (saved.getUpdatedAt() == null) saved.setUpdatedAt(LocalDateTime.now());
        return toRow(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) throw ApiException.notFound("Ligne introuvable.");
        repository.deleteById(id);
    }

    /** Import d'un tableau collé : une cellule par ligne ou séparées par tabulations / « ; » / « | ». */
    @Transactional
    public PasteResult importPaste(PasteRequest request, String updatedBy) {
        String country = country(request.countryCode());
        List<String> tokens = tokenize(request.text());
        List<String> warnings = new ArrayList<>();
        LocalDate current = request.defaultDate();
        LocalDate first = null;
        int imported = 0;
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            LocalDate d = parseDate(t);
            if (d != null) { current = d; i++; continue; }
            if (HEADERS.contains(t.toUpperCase(Locale.ROOT))) { i++; continue; }
            if (i + 2 >= tokens.size()) { warnings.add("Ligne incomplète ignorée : « " + t + " »."); break; }
            String card = tokens.get(i + 1), pin = tokens.get(i + 2);
            if (!isStatus(card) || !isStatus(pin)) {
                warnings.add("« " + t + " » ignoré : statuts CARTE/CODE attendus (OK, FAIBLE, RUPTURE), reçu « " + card + " » / « " + pin + " ».");
                i++;
                continue;
            }
            String types = null;
            int consumed = 3;
            if (i + 3 < tokens.size() && looksLikeTypes(tokens.get(i + 3))) {
                types = tokens.get(i + 3);
                consumed = 4;
            }
            if (current == null) { warnings.add("Aucune date pour « " + t + " » : ligne ignorée."); i += consumed; continue; }
            save(null, new AgencyRowRequest(country, current, t, null, card, pin, splitTypes(types), null), updatedBy);
            if (first == null) first = current;
            imported++;
            i += consumed;
        }
        if (imported == 0 && warnings.isEmpty()) warnings.add("Aucune ligne reconnue — format attendu : DATE | AGENCE | CARTE | CODE | TYPE DE CARTE.");
        return new PasteResult(imported, first, warnings);
    }

    // ── Analyse ────────────────────────────────────────────────────────

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) return tokens;
        for (String line : text.split("\\r?\\n")) {
            for (String cell : line.split("\\t|;|\\|")) {
                String c = cell.trim().replaceAll("\\s+", " ");
                if (!c.isEmpty()) tokens.add(c);
            }
        }
        return tokens;
    }

    static LocalDate parseDate(String token) {
        String t = token.trim();
        if (ISO_DATE.matcher(t).matches()) return LocalDate.parse(t);
        Matcher m = DATE.matcher(t);
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(3));
        if (year < 100) year += 2000;
        try {
            return LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isStatus(String token) {
        try { status(token); return true; } catch (ApiException e) { return false; }
    }

    static String status(String raw) {
        String s = raw == null ? "" : java.text.Normalizer.normalize(raw.trim().toUpperCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return switch (s) {
            case "OK", "DISPONIBLE", "DISPO", "OUI", "YES", "✓", "✔" -> "OK";
            case "FAIBLE", "STOCK FAIBLE", "LIMITE", "BAS" -> "FAIBLE";
            case "KO", "NON", "NO", "RUPTURE", "INDISPONIBLE", "NOK", "EPUISE", "X", "✗" -> "RUPTURE";
            default -> throw ApiException.badRequest("Statut inconnu : « " + raw + " » (OK, FAIBLE ou RUPTURE).");
        };
    }

    private static boolean looksLikeTypes(String token) {
        if (parseDate(token) != null || isStatus(token)) return false;
        String u = token.toUpperCase(Locale.ROOT);
        return u.contains(",") || u.matches(".*\\b(CLASSIC|GOLD|PLATIN\\w*|XPRESS|MX|PREPAY\\w*|PREPAID|VISA|MASTERCARD|GIM|INFINITE|SIGNATURE|BUSINESS)\\b.*");
    }

    /** « NIANGON K27 » → { « NIANGON », « K27 » }. */
    static String[] splitAgency(String raw) {
        String a = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (a.isEmpty()) throw ApiException.badRequest("Agence obligatoire.");
        Matcher m = AGENCY_CODE.matcher(a);
        if (m.matches() && !m.group(1).isBlank()) return new String[]{m.group(1).trim().toUpperCase(Locale.ROOT), m.group(2).toUpperCase(Locale.ROOT)};
        return new String[]{a.toUpperCase(Locale.ROOT), null};
    }

    static List<String> splitTypes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : raw.split("[,/+]")) {
            String c = t.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (!c.isEmpty()) out.add(c);
        }
        return new ArrayList<>(out);
    }

    private static String joinTypes(List<String> types) {
        if (types == null || types.isEmpty()) return null;
        String joined = String.join(", ", splitTypes(String.join(",", types)));
        return joined.length() > 400 ? joined.substring(0, 400) : joined;
    }

    private static String country(String code) {
        if (code == null || code.isBlank()) throw ApiException.badRequest("Filiale obligatoire.");
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.length() > 2 ? c.substring(0, 2) : c;
    }

    private static AgencyRow toRow(CardAgencyStatus s) {
        return new AgencyRow(s.getId(), s.getReportDate(), s.getAgency(), s.getAgencyCode(), s.getCardStatus(), s.getPinStatus(),
                splitTypes(s.getCardTypes()), s.getNote(), s.getUpdatedBy(), s.getUpdatedAt());
    }

    static String format(LocalDate d) {
        return d == null ? "" : d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }
}
