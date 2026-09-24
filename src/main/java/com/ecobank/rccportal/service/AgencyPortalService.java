package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.BankBranchResponse;
import com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRow;
import com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRowRequest;
import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portail Agence : chaque agent d'agence (rôle AGENCE) est rattaché à UNE agence et dispose de
 * SON portail — il coche la disponibilité des cartes / codes PIN et tient à jour les coordonnées
 * de SON agence uniquement. Ces informations servent au traitement des demandes clients et sont
 * visibles partout sur le site (Base de connaissances, Portail Agence, RAF).
 *
 * <p>Aucune donnée client : seules des informations d'agence sont enregistrées ; une remarque
 * contenant un numéro de compte, un téléphone ou un e-mail est refusée.</p>
 */
@Service
public class AgencyPortalService {

    public record AgencyMe(BankBranchResponse branch, AgencyRow cards, boolean assigned, boolean canChoose,
                           boolean canEdit, String assignedBy, List<String> cardTypeChoices) {}

    public record AvailabilityRequest(String cardStatus, String pinStatus, List<String> cardTypes, String note) {}

    public record BranchInfoRequest(String phone, String email, String openingHours, String managerName) {}

    public record Assignment(String username, String name, Long branchId, String branchName, String assignedBy) {}

    /** Gammes proposées à la coche (complétées par celles déjà publiées). */
    static final List<String> STANDARD_CARD_TYPES = List.of("CLASSIC", "GOLD", "PLATINIUM", "XPRESS", "MX", "PRÉPAYÉE", "VIRTUELLE");

    private static final Pattern CODE = Pattern.compile("\\(([A-Z]{1,3}\\d{1,4})\\)\\s*$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final BankBranchRepository branchRepository;
    private final CardAgencyStatusService cardService;
    private final DataProtectionService dataProtection;

    public AgencyPortalService(JdbcTemplate jdbc, UserRepository userRepository, BankBranchRepository branchRepository,
                               CardAgencyStatusService cardService, DataProtectionService dataProtection) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.branchRepository = branchRepository;
        this.cardService = cardService;
        this.dataProtection = dataProtection;
    }

    static boolean isAgencyStaff(AuthenticatedUser u) {
        return u != null && "AGENCE".equalsIgnoreCase(u.role());
    }

    static boolean isAdmin(AuthenticatedUser u) {
        return u != null && "ADMIN".equalsIgnoreCase(u.role());
    }

    static String agencyCode(String branchName) {
        if (branchName == null) return null;
        Matcher m = CODE.matcher(branchName.trim().toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1) : null;
    }

    private User me(AuthenticatedUser requester) {
        if (requester == null) throw ApiException.unauthorized("Non connecté.");
        return userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    private Map<String, Object> assignmentOf(Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT BranchId, AssignedBy FROM dbo.UserAgency WHERE UserId = ?", userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public AgencyMe me(AuthenticatedUser requester, boolean unused) {
        User user = me(requester);
        Map<String, Object> a = assignmentOf(user.getId());
        boolean staff = isAgencyStaff(requester) || isAdmin(requester);
        if (a == null) {
            return new AgencyMe(null, null, false, staff, false, null, STANDARD_CARD_TYPES);
        }
        BankBranch branch = branchRepository.findById(((Number) a.get("BranchId")).longValue()).orElse(null);
        if (branch == null) return new AgencyMe(null, null, false, staff, false, null, STANDARD_CARD_TYPES);
        AgencyRow cards = cardService.currentFor(branch.getCountryCode(), agencyCode(branch.getName())).orElse(null);
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>(STANDARD_CARD_TYPES);
        if (cards != null && cards.cardTypes() != null) types.addAll(cards.cardTypes());
        return new AgencyMe(BankBranchResponse.from(branch), cards, true, isAdmin(requester), staff,
                a.get("AssignedBy") == null ? null : a.get("AssignedBy").toString(), List.copyOf(types));
    }

    /** Premier rattachement par l'agent lui-même ; ensuite, seul l'administrateur peut changer. */
    @Transactional
    public AgencyMe chooseMyAgency(AuthenticatedUser requester, Long branchId) {
        if (!isAgencyStaff(requester) && !isAdmin(requester)) {
            throw ApiException.forbidden("Réservé aux agents d'agence (service « Agence — Caissier / Gestionnaire »).");
        }
        User user = me(requester);
        if (assignmentOf(user.getId()) != null && !isAdmin(requester)) {
            throw ApiException.forbidden("Votre agence est déjà définie — seul l'administrateur peut la modifier.");
        }
        assign(user.getId(), branchId, requester.name() != null ? requester.name() + " (lui-même)" : requester.username());
        return me(requester, true);
    }

    /** Administration : rattacher (ou détacher avec branchId null) un agent à une agence. */
    @Transactional
    public void assignUser(AuthenticatedUser requester, String username, Long branchId) {
        if (!isAdmin(requester)) throw ApiException.forbidden("Réservé à l'administration.");
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable."));
        if (branchId == null) {
            jdbc.update("DELETE FROM dbo.UserAgency WHERE UserId = ?", user.getId());
            return;
        }
        assign(user.getId(), branchId, requester.name() != null ? requester.name() : requester.username());
    }

    @Transactional(readOnly = true)
    public List<Assignment> assignments(AuthenticatedUser requester) {
        if (!isAdmin(requester)) throw ApiException.forbidden("Réservé à l'administration.");
        return jdbc.query("SELECT u.USERNAME, u.NAME, b.BranchId, b.Name AS BranchName, a.AssignedBy FROM dbo.UserAgency a "
                        + "JOIN dbo.USERS u ON u.ID = a.UserId LEFT JOIN dbo.BankBranches b ON b.BranchId = a.BranchId ORDER BY b.Name, u.NAME",
                (rs, i) -> new Assignment(rs.getString("USERNAME"), rs.getString("NAME"), rs.getLong("BranchId"),
                        rs.getString("BranchName"), rs.getString("AssignedBy")));
    }

    private void assign(Long userId, Long branchId, String by) {
        if (branchId == null) throw ApiException.badRequest("Agence obligatoire.");
        BankBranch branch = branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Agence introuvable."));
        if (!branch.isActive()) throw ApiException.badRequest("Cette agence est désactivée.");
        jdbc.update("DELETE FROM dbo.UserAgency WHERE UserId = ?", userId);
        jdbc.update("INSERT INTO dbo.UserAgency (UserId, BranchId, AssignedBy) VALUES (?, ?, ?)", userId, branchId, by);
    }

    private BankBranch myBranchForEdit(AuthenticatedUser requester) {
        if (!isAgencyStaff(requester) && !isAdmin(requester)) {
            throw ApiException.forbidden("Seuls les agents d'agence mettent à jour leur agence.");
        }
        Map<String, Object> a = assignmentOf(me(requester).getId());
        if (a == null) throw ApiException.badRequest("Choisissez d'abord votre agence.");
        return branchRepository.findById(((Number) a.get("BranchId")).longValue())
                .orElseThrow(() -> ApiException.notFound("Agence introuvable."));
    }

    /** L'agence coche sa disponibilité du jour — visible partout sur le site. */
    @Transactional
    public AgencyRow updateMyAvailability(AuthenticatedUser requester, AvailabilityRequest request) {
        BankBranch branch = myBranchForEdit(requester);
        String code = agencyCode(branch.getName());
        if (code == null) throw ApiException.badRequest("Votre agence n'a pas de code agence (Kxx) : contactez l'administration.");
        String note = request.note() == null ? null : request.note().trim();
        if (note != null && !note.isEmpty()) {
            if (note.length() > 200) throw ApiException.badRequest("Remarque trop longue (200 caractères maximum).");
            if (dataProtection.containsSensitiveData(note)) {
                throw ApiException.badRequest("La remarque ne doit contenir aucune donnée client (numéro de compte, téléphone, e-mail).");
            }
        }
        List<String> types = request.cardTypes() == null ? List.of()
                : request.cardTypes().stream().filter(t -> t != null && !t.isBlank()).map(t -> t.trim().toUpperCase(Locale.ROOT))
                        .filter(t -> t.length() <= 30 && t.matches("[\\p{L}0-9 +'-]+")).distinct().limit(12).toList();
        // Libellé déjà publié pour ce code (point QA) — sinon le nom de l'agence.
        String label = cardService.currentFor(branch.getCountryCode(), code).map(AgencyRow::agency)
                .orElse(branch.getName().replaceAll("\\s*\\([A-Z]{1,3}\\d{1,4}\\)\\s*$", "").replaceFirst("(?i)^agence\\s+", "").toUpperCase(Locale.ROOT));
        String who = (requester.name() != null ? requester.name() : requester.username()) + " (agence " + code + ")";
        return cardService.save(null, new AgencyRowRequest(branch.getCountryCode(), LocalDate.now(), label, code,
                request.cardStatus(), request.pinStatus(), types, note == null || note.isEmpty() ? null : note), who);
    }

    /** Coordonnées de l'agence (pas du client) : téléphone, e-mail, horaires, responsable. */
    @Transactional
    public BankBranchResponse updateMyBranch(AuthenticatedUser requester, BranchInfoRequest request) {
        BankBranch branch = myBranchForEdit(requester);
        branch.setPhone(clean(request.phone(), 40, "Téléphone"));
        String email = clean(request.email(), 150, "E-mail");
        if (email != null && !EMAIL.matcher(email).matches()) throw ApiException.badRequest("E-mail de l'agence invalide.");
        branch.setEmail(email);
        branch.setOpeningHours(clean(request.openingHours(), 150, "Horaires"));
        branch.setManagerName(clean(request.managerName(), 150, "Responsable"));
        return BankBranchResponse.from(branchRepository.save(branch));
    }

    private static String clean(String v, int max, String label) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        if (t.length() > max) throw ApiException.badRequest(label + " : " + max + " caractères maximum.");
        return t;
    }
}
