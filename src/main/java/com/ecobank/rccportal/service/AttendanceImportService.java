package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AttendanceImportResult;
import com.ecobank.rccportal.model.AttendanceRecord;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AttendanceRecordRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import Excel de la présence (feuille d'émargement) — même principe que
 * ManualKpiEntryService : colonne agent (matricule ou nom complet),
 * reconnaissance de l'en-tête quelle que soit sa formulation exacte, et
 * tolérance aux cellules vides/mal typées plutôt que de faire échouer tout
 * l'import pour une seule ligne suspecte.
 *
 * Colonnes attendues (n'importe quel ordre après la première, reconnues par
 * mot-clé dans l'en-tête) : Nom/Agent, Statut (présent/absent), Arrivée, Départ.
 * Toutes les lignes du fichier sont pour la date choisie dans le formulaire —
 * contrairement aux KPI, une feuille de présence ne mélange en général pas
 * plusieurs jours dans le même import.
 */
@Service
public class AttendanceImportService {

    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})(:(\\d{2}))?$");

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final UserRepository userRepository;

    public AttendanceImportService(AttendanceRecordRepository attendanceRecordRepository, UserRepository userRepository) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public AttendanceImportResult importFromExcel(MultipartFile file, LocalDate workDate) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Le fichier est requis.");
        if (workDate == null) throw ApiException.badRequest("La date est requise.");

        Map<String, User> usersByNormalizedName = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getName() != null && !u.getName().isBlank()) {
                usersByNormalizedName.put(normalizeName(u.getName()), u);
            }
        }

        int rowsProcessed = 0;
        int recordsCreated = 0;
        Set<String> unresolvedNames = new LinkedHashSet<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            // Repère la ligne d'en-tête (celle où l'on reconnaît "statut"/"present"/"absent"/"arrivee"/"depart"
            // dans une des cellules) et la position de chaque colonne utile.
            int headerRowIndex = -1;
            int statusCol = -1, arrivalCol = -1, departureCol = -1;
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 5); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    String text = normalizeHeader(cleanText(row.getCell(c)));
                    if (text.contains("statut") || text.contains("presence")) { statusCol = c; headerRowIndex = r; }
                    else if (text.contains("arrivee") || text.contains("entree")) { arrivalCol = c; headerRowIndex = r; }
                    else if (text.contains("depart") || text.contains("sortie")) { departureCol = c; headerRowIndex = r; }
                }
                if (headerRowIndex >= 0) break;
            }
            if (headerRowIndex < 0) {
                throw ApiException.badRequest(
                        "Aucune colonne reconnue (attendu : une colonne \"Statut\", \"Arrivée\" ou \"Départ\"). " +
                        "Vérifiez les en-têtes de colonnes du fichier.");
            }

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String firstCell = cleanText(row.getCell(0));
                if (firstCell.isBlank()) continue;

                rowsProcessed++;
                User user = resolveUser(firstCell, usersByNormalizedName);
                if (user == null) {
                    unresolvedNames.add(firstCell);
                    continue;
                }

                String statusText = statusCol >= 0 ? cleanText(row.getCell(statusCol)).toLowerCase() : "";
                String status = statusText.contains("absent") ? "absent" : "present";

                LocalTime arrival = arrivalCol >= 0 ? cellToTime(row.getCell(arrivalCol)) : null;
                LocalTime departure = departureCol >= 0 ? cellToTime(row.getCell(departureCol)) : null;

                AttendanceRecord record = attendanceRecordRepository.findByUserAndWorkDate(user, workDate)
                        .orElseGet(() -> AttendanceRecord.builder().user(user).workDate(workDate).build());
                record.setStatus(status);
                if (arrival != null) record.setArrivalTime(arrival);
                if (departure != null) record.setDepartureTime(departure);
                attendanceRecordRepository.save(record);
                recordsCreated++;
            }
        } catch (IOException e) {
            throw ApiException.badRequest("Impossible de lire le fichier Excel : " + e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier Excel invalide ou mal formé : " + e.getMessage());
        }

        return new AttendanceImportResult(rowsProcessed, recordsCreated, new ArrayList<>(unresolvedNames));
    }

    private String normalizeHeader(String text) {
        return stripAccents(text.toLowerCase());
    }

    /** Même principe que ManualKpiEntryService.resolveUser — crée le compte agent s'il n'existe pas encore. */
    private User resolveUser(String cellValue, Map<String, User> usersByNormalizedName) {
        User byUsername = userRepository.findFirstByUsernameIgnoreCase(cellValue).orElse(null);
        if (byUsername != null) return byUsername;

        String normalized = normalizeName(cellValue);
        User existing = usersByNormalizedName.get(normalized);
        if (existing != null) return existing;

        User created = User.builder()
                .username(generateUsername(cellValue))
                .name(cellValue.trim().length() > 200 ? cellValue.trim().substring(0, 200) : cellValue.trim())
                .status("APPROVED")
                .accountEnabled(true)
                .accountLocked(false)
                .accountExpired(false)
                .credentialsExpired(false)
                .failedAttempts(0)
                .build();
        created = userRepository.save(created);
        usersByNormalizedName.put(normalized, created);
        return created;
    }

    /** USERNAME fait 35 caractères en base — on plafonne à 30 pour garder de la place à un éventuel suffixe de collision. */
    private String generateUsername(String fullName) {
        String base = stripAccents(fullName.trim().toLowerCase())
                .replaceAll("[^a-z0-9\\s]", "")
                .trim()
                .replaceAll("\\s+", ".");
        if (base.isBlank()) base = "agent";
        if (base.length() > 30) base = base.substring(0, 30);
        base = base.replaceAll("\\.$", "");

        String candidate = base;
        int suffix = 2;
        while (userRepository.existsByUsernameIgnoreCase(candidate)) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private String normalizeName(String name) {
        String cleaned = stripAccents(name.trim().toLowerCase()).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) return cleaned;
        String[] words = cleaned.split(" ");
        Arrays.sort(words);
        return String.join(" ", words);
    }

    private String stripAccents(String text) {
        return text
                .replace("É", "E").replace("È", "E").replace("Ê", "E").replace("é", "e").replace("è", "e").replace("ê", "e")
                .replace("À", "A").replace("Â", "A").replace("à", "a").replace("â", "a")
                .replace("Ô", "O").replace("ô", "o").replace("Û", "U").replace("Ù", "U").replace("û", "u").replace("ù", "u")
                .replace("Î", "I").replace("Ï", "I").replace("î", "i").replace("ï", "i")
                .replace("Ç", "C").replace("ç", "c");
    }

    private String cleanText(Cell cell) {
        if (cell == null) return "";
        String raw = switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? "" : String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
        return raw.replace("\u200b", "").replace("\u00a0", " ").trim();
    }

    private LocalTime cellToTime(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalTime();
            }
            String text = cleanText(cell);
            if (text.isBlank()) return null;
            Matcher m = TIME_PATTERN.matcher(text);
            if (m.matches()) {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                int s = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
                return LocalTime.of(h, min, s);
            }
        } catch (Exception ignored) {
            // cellule non exploitable — ignorée plutôt que de faire échouer tout l'import
        }
        return null;
    }
}
