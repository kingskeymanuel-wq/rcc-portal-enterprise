package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.RosterImportResult;
import com.ecobank.rccportal.model.Team;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.TeamRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Import du roster RH (fichier réel Ecobank : NOM ET PRENOMS, ID, GENRE, NATURE DU CONTRAT,
 * STATUT DU CONTRAT, ACTIVITE, POLE D'ACTIVITE) — alimente directement les comptes existants
 * (ou en crée de nouveaux si le matricule/nom ne matche personne), pour que Reporting, Shift
 * et l'Analyse de données groupent enfin les agents par vraie équipe au lieu de "Sans équipe".
 *
 * Mapping (confirmé par le métier — corrige un mapping inversé précédent) :
 *  — "Activité" (ex. "INBOUND VOICE", "TEAM LEADER INBOUND VOICE", "OPERATIONS") → User.activity,
 *    l'axe "équipe" utilisé partout dans le portail (Reporting, Shift, Analyse de données...).
 *  — "Pôle d'activité" (ex. "INBOUND", "OUTBOUND", "OPERATIONS", "QUALITY ASSURANCE", "RCC")
 *    → le SERVICE réel de l'agent (sous-service de MON RCC), lié via UserServiceAssignment à
 *    un vrai RccService (créé si besoin) — pas une simple chaîne de texte.
 */
@Service
public class RosterImportService {

    private final UserRepository userRepository;
    private final ImportIntelligenceService importIntelligenceService;
    private final TeamRepository teamRepository;
    private final com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;
    private final com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;

    public RosterImportService(UserRepository userRepository, ImportIntelligenceService importIntelligenceService,
                               TeamRepository teamRepository,
                               com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository,
                               com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository) {
        this.userRepository = userRepository;
        this.importIntelligenceService = importIntelligenceService;
        this.teamRepository = teamRepository;
        this.rccServiceRepository = rccServiceRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
    }

    private static final int PREVIEW_CAP = 80;

    @Transactional
    public RosterImportResult importFromExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }

        Map<String, User> usersByNormalizedName = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getName() != null && !u.getName().isBlank()) {
                usersByNormalizedName.put(normalizeName(u.getName()), u);
            }
        }

        int rowsProcessed = 0;
        int usersUpdated = 0;
        int usersCreated = 0;
        List<String> unresolvedNames = new ArrayList<>();
        List<String> unresolvedServices = new ArrayList<>();
        List<RosterImportResult.RosterPreviewRow> preview = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet.getLastRowNum() < 1) continue;

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) continue;
                ColumnMap columns = detectColumns(headerRow);
                if (columns.nameCol == -1) continue; // pas une feuille de roster reconnaissable

                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String name = cleanText(row.getCell(columns.nameCol));
                    if (name.isBlank()) continue;

                    rowsProcessed++;

                    String matricule = columns.idCol != -1 ? cleanText(row.getCell(columns.idCol)) : "";
                    String genreRaw = columns.genderCol != -1 ? cleanText(row.getCell(columns.genderCol)) : "";
                    String contractType = columns.contractTypeCol != -1 ? cleanText(row.getCell(columns.contractTypeCol)) : "";
                    String contractStatus = columns.contractStatusCol != -1 ? cleanText(row.getCell(columns.contractStatusCol)) : "";
                    String pole = columns.poleCol != -1 ? cleanText(row.getCell(columns.poleCol)) : "";
                    String activiteDetail = columns.activiteDetailCol != -1 ? cleanText(row.getCell(columns.activiteDetailCol)) : "";

                    User user = resolveUser(matricule, name, usersByNormalizedName);
                    boolean wasNew = user.getId() == null;

                    if (!genreRaw.isBlank()) user.setGender(mapGender(genreRaw));
                    if (!contractType.isBlank()) user.setContractType(contractType);
                    if (!contractStatus.isBlank()) user.setContractStatus(contractStatus);
                    // Activité = équipe (ex. "INBOUND VOICE", "TEAM LEADER INBOUND VOICE") — axe utilisé partout comme "équipe".
                    if (!activiteDetail.isBlank()) {
                        user.setActivity(activiteDetail);
                        ensureTeamExists(activiteDetail);
                    }
                    // Filiale — ce roster n'a aucune colonne dédiée (portail confirmé mono-filiale
                    // Côte d'Ivoire) ; renseignée automatiquement si absente, pour que l'agent
                    // n'ait jamais à repasser par l'écran de première connexion (voir
                    // UserService.teamStatus() : filiale ET équipe doivent être renseignées).
                    if (user.getAffiliateBranch() == null || user.getAffiliateBranch().isBlank()) {
                        user.setAffiliateBranch("CI");
                    }
                    // Pôle d'activité = vrai service RCC (ex. "INBOUND", "QUALITY ASSURANCE") — lié à un vrai RccService, pas une chaîne libre.
                    // Appliqué APRÈS la sauvegarde ci-dessous : un nouvel utilisateur n'a pas encore d'ID pour la clé étrangère avant.

                    User saved = userRepository.save(user);
                    usersByNormalizedName.put(normalizeName(saved.getName()), saved);
                    if (wasNew) usersCreated++; else usersUpdated++;
                    if (!pole.isBlank()) linkToService(saved, pole, unresolvedServices);

                    if (preview.size() < PREVIEW_CAP) {
                        preview.add(new RosterImportResult.RosterPreviewRow(
                                saved.getName(), saved.getUsername(), saved.getGender(),
                                saved.getContractType(), saved.getContractStatus(),
                                saved.getActivity(), pole.isBlank() ? null : pole));
                    }
                }
            }
        } catch (IOException e) {
            throw ApiException.badRequest("Impossible de lire le fichier Excel : " + e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier Excel invalide ou mal formé : " + e.getMessage());
        }

        return new RosterImportResult(rowsProcessed, usersUpdated, usersCreated, unresolvedNames,
                unresolvedServices, preview, rowsProcessed > preview.size());
    }

    /** Matricule (ID) exact d'abord, puis nom normalisé, puis résolution assistée Copilot Studio, puis création. */
    private User resolveUser(String matricule, String name, Map<String, User> usersByNormalizedName) {
        if (!matricule.isBlank()) {
            User byUsername = userRepository.findFirstByUsernameIgnoreCase(matricule).orElse(null);
            if (byUsername != null) return byUsername;
        }

        String normalized = normalizeName(name);
        User existing = usersByNormalizedName.get(normalized);
        if (existing != null) return existing;

        if (importIntelligenceService.isAvailable() && !usersByNormalizedName.isEmpty()) {
            List<String> candidateNames = usersByNormalizedName.values().stream()
                    .map(User::getName).filter(n -> n != null && !n.isBlank()).distinct().toList();
            Optional<String> matched = importIntelligenceService.resolveAmbiguousName(name, candidateNames);
            if (matched.isPresent()) {
                User resolvedByAi = usersByNormalizedName.get(normalizeName(matched.get()));
                if (resolvedByAi != null) return resolvedByAi;
            }
        }

        // Nouveau compte — matricule utilisé comme identifiant de connexion si fourni, sinon dérivé du nom.
        String username = !matricule.isBlank() ? matricule : name.toLowerCase().replaceAll("[^a-z0-9]+", ".");
        return User.builder()
                .username(username)
                .name(name)
                .status("APPROVED")
                .accountEnabled(true)
                .accountLocked(false)
                .accountExpired(false)
                .credentialsExpired(false)
                .failedAttempts(0)
                .build();
    }

    /** Crée l'équipe si son code n'existe pas encore — c'est ce qui alimente le sélecteur "Équipe" (Shift, Administration). */
    /** Crée l'équipe si son code n'existe pas encore — c'est ce qui alimente le sélecteur "Équipe" (Shift, Administration).
     *  Si elle existe déjà, son libellé est rafraîchi avec la valeur du dernier import (jamais de libellé figé/périmé). */
    private void ensureTeamExists(String poleLabel) {
        String code = poleLabel.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (code.isBlank()) return;
        Team existing = teamRepository.findByCode(code).orElse(null);
        if (existing != null) {
            if (!poleLabel.trim().equals(existing.getLabel())) {
                existing.setLabel(poleLabel.trim());
                teamRepository.save(existing);
            }
            return;
        }
        teamRepository.save(Team.builder()
                .code(code)
                .label(poleLabel.trim())
                .isActive(true)
                .build());
    }

    /**
     * Lie l'utilisateur à son vrai service RCC (Pôle d'activité) — le service DOIT déjà
     * exister (créé manuellement dans Administration), l'import ne crée plus de service tout
     * seul. Si aucun service ne correspond, la ligne est simplement notée comme non résolue
     * plutôt que de créer un service fantôme.
     */
    /**
     * Lie l'utilisateur à son vrai service RCC (Pôle d'activité) — crée le RccService s'il
     * n'existe pas encore (avec le code normalisé exact, ex. "QUALITY ASSURANCE" -> QUALITY_ASSURANCE),
     * et remplace toute affectation précédente par celle-ci (un service principal par
     * utilisateur, cohérent avec AuthService.getPrimaryService qui lit la première affectation).
     */
    private boolean linkToService(User user, String poleLabel, List<String> unresolvedServices) {
        String code = poleLabel.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (code.isBlank()) return false;

        com.ecobank.rccportal.model.RccService service = rccServiceRepository.findByCodeIgnoreCase(code).orElse(null);
        if (service == null) {
            service = rccServiceRepository.save(com.ecobank.rccportal.model.RccService.builder()
                    .code(code)
                    .name(poleLabel.trim())
                    .enabled(true)
                    .build());
        } else if (!poleLabel.trim().equals(service.getName())) {
            // Le service existait déjà (ex. ancien seed) — son libellé est rafraîchi avec la valeur du dernier import, jamais figé.
            service.setName(poleLabel.trim());
            service = rccServiceRepository.save(service);
        }

        boolean alreadyLinked = userServiceAssignmentRepository.existsByUserIdAndServiceId(user.getId(), service.getId());
        if (alreadyLinked) return true;

        // Retire toute affectation précédente — un seul service principal par utilisateur.
        userServiceAssignmentRepository.findByUserId(user.getId())
                .forEach(userServiceAssignmentRepository::delete);
        userServiceAssignmentRepository.save(com.ecobank.rccportal.model.UserServiceAssignment.builder()
                .user(user).service(service).build());
        return true;
    }

    private String mapGender(String raw) {
        String normalized = stripAccents(raw.trim().toLowerCase());
        if (normalized.startsWith("f")) return "F";
        if (normalized.startsWith("h") || normalized.startsWith("m")) return "M";
        return null;
    }

    private static class ColumnMap {
        int nameCol = -1, idCol = -1, genderCol = -1, contractTypeCol = -1,
                contractStatusCol = -1, poleCol = -1, activiteDetailCol = -1;
    }

    private ColumnMap detectColumns(Row headerRow) {
        ColumnMap cols = new ColumnMap();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            String h = stripAccents(cleanText(headerRow.getCell(c)).toUpperCase());
            if (h.isBlank()) continue;

            if (h.contains("NOM") && cols.nameCol == -1) cols.nameCol = c;
            else if (h.equals("ID") && cols.idCol == -1) cols.idCol = c;
            else if (h.contains("GENRE") && cols.genderCol == -1) cols.genderCol = c;
            else if (h.contains("NATURE") && cols.contractTypeCol == -1) cols.contractTypeCol = c;
            else if ((h.contains("STATUT") || h.contains("STATUS")) && h.contains("CONTRAT") && cols.contractStatusCol == -1) cols.contractStatusCol = c;
            else if (h.contains("POLE") && cols.poleCol == -1) cols.poleCol = c;
            else if (h.contains("ACTIVITE") && !h.contains("POLE") && cols.activiteDetailCol == -1) cols.activiteDetailCol = c;
        }
        return cols;
    }

    private String cleanText(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
        }
        String text = cell.toString();
        return text == null ? "" : text.replace("\u00a0", " ").replace("\u200b", "").trim();
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
                .replace("Ô", "O").replace("ô", "o")
                .replace("Û", "U").replace("Ù", "U").replace("û", "u").replace("ù", "u")
                .replace("Î", "I").replace("Ï", "I").replace("î", "i").replace("ï", "i")
                .replace("Ç", "C").replace("ç", "c");
    }
}
