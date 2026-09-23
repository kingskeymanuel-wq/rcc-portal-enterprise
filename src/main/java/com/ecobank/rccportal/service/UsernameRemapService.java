package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.UsernameRemapResult;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Renomme en masse le USERNAME des comptes déjà existants, à partir de la liste de
 * référence RH (2 colonnes : Nom complet, Nouvel ID) — voir UsernameRemapController.
 *
 * Règles (confirmées explicitement, ne jamais dévier sans redemander) :
 *  — Ligne sans nouvel ID renseigné (colonne 2 vide) → USERNAME actuel INCHANGÉ, comptée
 *    à part (skippedBlankNewId), jamais une erreur.
 *  — Nom de la liste qui ne correspond à AUCUN compte existant → rien créé, nom listé dans
 *    unresolvedNames. Ce fichier ne crée jamais de compte, contrairement à RosterImportService.
 *  — Nouvel ID déjà pris par un AUTRE agent que celui visé par la ligne → BLOQUÉ (ni renommé,
 *    ni suffixe automatique), remonté dans conflicts pour arbitrage humain — un suffixe auto
 *    masquerait silencieusement une vraie collision de nomenclature RH.
 * Toujours transactionnel en bloc : si une seule ligne lève une exception inattendue, aucun
 * renommage de ce fichier n'est appliqué plutôt qu'un état à moitié migré.
 */
@Service
public class UsernameRemapService {

    private static final int PREVIEW_CAP = 200;

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final ImportIntelligenceService importIntelligenceService;

    public UsernameRemapService(UserRepository userRepository, AuditLogService auditLogService,
                                 ImportIntelligenceService importIntelligenceService) {
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.importIntelligenceService = importIntelligenceService;
    }

    @Transactional
    public UsernameRemapResult importFromExcel(MultipartFile file, String enteredByUsername) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }

        List<List<String>> grid = readGrid(file);

        Map<String, User> usersByNormalizedName = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getName() != null && !u.getName().isBlank()) {
                usersByNormalizedName.putIfAbsent(normalizeName(u.getName()), u);
            }
        }
        // Nouveaux ID déjà attribués DANS CE MÊME import — une collision entre deux lignes du
        // fichier lui-même doit être détectée elle aussi, pas seulement contre la base existante.
        Map<String, String> newUsernameClaimedByName = new HashMap<>();

        int rowsProcessed = 0, usernamesChanged = 0, skippedBlankNewId = 0;
        List<String> unresolvedNames = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<UsernameRemapResult.UsernameChangePreview> preview = new ArrayList<>();

        int startRow = looksLikeHeaderRow(grid.isEmpty() ? List.of() : grid.get(0)) ? 1 : 0;

        for (int r = startRow; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            String name = cellAt(row, 0);
            if (name.isBlank()) continue;
            rowsProcessed++;

            String newUsername = cellAt(row, 1);
            if (newUsername.isBlank()) {
                skippedBlankNewId++;
                continue;
            }
            newUsername = newUsername.trim();

            User user = usersByNormalizedName.get(normalizeName(name));
            if (user == null && importIntelligenceService.isAvailable() && !usersByNormalizedName.isEmpty()) {
                // Variante orthographique mineure entre les deux fichiers (ex. "KRYSTELLE" vs
                // "KRYSTELE") — la correspondance exacte par mots triés ne peut pas la rattraper,
                // on tente l'assistance IA avant d'abandonner sur unresolvedNames.
                List<String> candidates = usersByNormalizedName.values().stream()
                        .map(User::getName).filter(n -> n != null && !n.isBlank()).distinct().toList();
                Optional<String> matched = importIntelligenceService.resolveAmbiguousName(name, candidates);
                if (matched.isPresent()) user = usersByNormalizedName.get(normalizeName(matched.get()));
            }
            if (user == null) {
                unresolvedNames.add(name);
                continue;
            }

            if (newUsername.equalsIgnoreCase(user.getUsername())) {
                continue; // déjà à jour — rien à faire, pas un changement
            }

            // Collision contre un AUTRE agent déjà en base (jamais contre soi-même : un agent
            // qui reprend son propre futur ID n'est pas un conflit).
            Optional<User> holder = userRepository.findFirstByUsernameIgnoreCase(newUsername);
            if (holder.isPresent() && !holder.get().getId().equals(user.getId())) {
                conflicts.add(name + " → \"" + newUsername + "\" déjà utilisé par " +
                        (holder.get().getName() != null ? holder.get().getName() : holder.get().getUsername()));
                continue;
            }
            // Collision contre une autre ligne DE CE FICHIER déjà traitée.
            String claimedBy = newUsernameClaimedByName.putIfAbsent(newUsername.toLowerCase(), name);
            if (claimedBy != null && !claimedBy.equals(name)) {
                conflicts.add(name + " → \"" + newUsername + "\" déjà demandé dans ce même fichier par " + claimedBy);
                continue;
            }

            if (newUsername.length() > 35) {
                conflicts.add(name + " → \"" + newUsername + "\" dépasse 35 caractères (limite base) — corrigez le fichier.");
                continue;
            }

            String oldUsername = user.getUsername();
            user.setUsername(newUsername);
            userRepository.save(user);
            usernamesChanged++;

            if (preview.size() < PREVIEW_CAP) {
                preview.add(new UsernameRemapResult.UsernameChangePreview(user.getName(), oldUsername, newUsername));
            }
        }

        auditLogService.record(enteredByUsername, "REMAP_USERNAMES",
                "Fichier : " + (file.getOriginalFilename() != null ? file.getOriginalFilename() : "(sans nom)") + " — "
                        + rowsProcessed + " ligne(s), " + usernamesChanged + " USERNAME renommé(s), "
                        + skippedBlankNewId + " sans nouvel ID, " + unresolvedNames.size() + " nom(s) non reconnu(s), "
                        + conflicts.size() + " conflit(s) bloqué(s)");

        return new UsernameRemapResult(rowsProcessed, usernamesChanged, skippedBlankNewId,
                unresolvedNames, conflicts, preview, usernamesChanged > preview.size());
    }

    /** Vraie ligne d'en-tête (ex. "NOM"/"ID") — sinon on suppose que le fichier commence
     *  directement par les données, comme les deux captures fournies (pas d'en-tête visible). */
    private boolean looksLikeHeaderRow(List<String> firstRow) {
        String col0 = stripAccents(cellAt(firstRow, 0)).toUpperCase();
        return col0.contains("NOM") || col0.equals("AGENT") || col0.equals("NAME");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lecture Excel (.xlsx) ou CSV — même principe que ScheduleService.readGrid
    // ═══════════════════════════════════════════════════════════════════

    private List<List<String>> readGrid(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (filename.endsWith(".csv")) return readCsvGrid(file);
            return readXlsxGrid(file);
        } catch (IOException e) {
            throw ApiException.badRequest("Impossible de lire le fichier : " + e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier invalide ou mal formé : " + e.getMessage());
        }
    }

    private List<List<String>> readCsvGrid(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.indexOf('\uFFFD') >= 0) {
            content = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
        List<List<String>> grid = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) grid.add(parseCsvLine(line));
        }
        return grid;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean atFieldStart = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                    else inQuotes = false;
                } else current.append(c);
            } else {
                if (c == '"' && atFieldStart) { inQuotes = true; atFieldStart = false; }
                else if (c == ',') { cells.add(current.toString()); current.setLength(0); atFieldStart = true; }
                else { current.append(c); atFieldStart = false; }
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private List<List<String>> readXlsxGrid(MultipartFile file) throws IOException {
        List<List<String>> grid = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> line = new ArrayList<>();
                if (row != null) {
                    int lastCol = row.getLastCellNum();
                    for (int c = 0; c < lastCol; c++) line.add(cellToString(row.getCell(c)));
                }
                grid.add(line);
            }
        }
        return grid;
    }

    private String cellToString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : (cell.getNumericCellValue() == Math.floor(cell.getNumericCellValue())
                        ? String.valueOf((long) cell.getNumericCellValue()) : String.valueOf(cell.getNumericCellValue()));
            case FORMULA -> cellToStringFromFormula(cell);
            default -> cell.toString();
        };
    }

    private String cellToStringFromFormula(Cell cell) {
        try { return cell.getStringCellValue(); } catch (Exception e) {
            try { return String.valueOf(cell.getNumericCellValue()); } catch (Exception e2) { return ""; }
        }
    }

    private String cellAt(List<String> row, int col) {
        if (row == null || col < 0 || col >= row.size()) return "";
        String v = row.get(col);
        return v == null ? "" : v.trim();
    }

    private String normalizeName(String name) {
        String cleaned = stripAccents(name.trim().toLowerCase()).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) return cleaned;
        String[] words = cleaned.split(" ");
        Arrays.sort(words);
        return String.join(" ", words);
    }

    private String stripAccents(String text) {
        if (text == null) return "";
        return text
                .replace("É", "E").replace("È", "E").replace("Ê", "E").replace("é", "e").replace("è", "e").replace("ê", "e")
                .replace("À", "A").replace("Â", "A").replace("à", "a").replace("â", "a")
                .replace("Ô", "O").replace("ô", "o")
                .replace("Û", "U").replace("Ù", "U").replace("û", "u").replace("ù", "u")
                .replace("Î", "I").replace("Ï", "I").replace("î", "i").replace("ï", "i")
                .replace("Ç", "C").replace("ç", "c")
                .replace("'", " ").replace("-", " ");
    }
}
