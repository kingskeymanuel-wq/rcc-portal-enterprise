package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.DuplicateUserGroupResponse;
import com.ecobank.rccportal.dto.DuplicateUserGroupResponse.DuplicateUserEntryResponse;
import com.ecobank.rccportal.dto.UserMergeResultResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Détection et fusion des comptes USERS en doublon (même USERNAME — aucune contrainte
 * d'unicité n'existait avant cette fonctionnalité, voir aussi WorkflowSchemaBootstrap pour
 * l'ajout de la contrainte une fois le nettoyage fait).
 *
 * Règle de fusion, demandée explicitement par l'utilisateur (Sept 2026) :
 *  1. Pour un USERNAME en double, la fiche gardée (keep) est celle avec le PLUS de colonnes
 *     USERS non vides ; à égalité, la plus ancienne (ID le plus petit — généralement la
 *     première créée).
 *  2. Toute donnée liée aux fiches en trop (duplicate) — shifts, évaluations, congés,
 *     permutations, notifications, etc. — est RÉASSIGNÉE vers la fiche gardée, jamais perdue.
 *  3. Les colonnes FK vers USERS.ID sont listées explicitement en dur (REFERENCE_COLUMNS
 *     ci-dessous), relevées à la main sur chaque entité JPA du projet ayant un champ
 *     "private User ..." — le schéma de ce projet n'a pas de contrainte FK déclarée partout
 *     (voir WorkflowSchemaBootstrap, même constat), donc une découverte purement dynamique via
 *     sys.foreign_key_columns manquerait ces colonnes et laisserait des données orphelines
 *     après suppression des doublons.
 *  4. Certaines tables ont une contrainte unique composite incluant la colonne User (ex.
 *     FavoriteProcedures(UserId, ProcedureId)) : réassigner brutalement casserait dessus si la
 *     fiche gardée a déjà la même ligne. Pour celles-ci (UNIQUE_TABLES ci-dessous), les lignes
 *     du doublon qui existent déjà côté fiche gardée sont supprimées avant réassignation ; les
 *     autres sont réassignées normalement (rien n'est perdu qui ne soit pas déjà présent).
 *     UserProfiles a une contrainte unique SIMPLE sur UserId (une fiche par personne) — traité
 *     séparément : si la fiche gardée a déjà un profil, celui du doublon est supprimé (pas de
 *     perte de donnée métier, un profil ne contient que des préférences recréables).
 *  5. Rien n'est fait sans aperçu préalable — voir preview(), qui ne modifie jamais la base.
 */
@Slf4j
@Service
public class UserDeduplicationService {

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public UserDeduplicationService(UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Colonnes qui référencent USERS.ID — établi en relisant chaque entité JPA du projet ayant
     * un champ "private User ..." (voir Javadoc de classe). table = nom exact de la table SQL,
     * column = nom exact de la colonne FK.
     */
    private static final String[][] REFERENCE_COLUMNS = {
            {"AgentDossiers", "LinkedUserId"},
            {"AgentSchedules", "UserId"},
            {"AssignedTasks", "AssignedToUserId"},
            {"AssignedTasks", "CreatedByUserId"},
            {"Attachments", "UploadedByUserId"},
            {"AttendanceRecords", "UserId"},
            {"ChatMessages", "SenderUserId"},
            {"CoachingPlans", "AgentUserId"},
            {"ConversationParticipants", "UserId"},
            {"Courses", "CreatedByUserId"},
            {"CourseAttempts", "UserId"},
            {"FavoriteAttachments", "UserId"},
            {"FavoriteProcedures", "UserId"},
            {"KpiEvents", "UserId"},
            {"LoginAudit", "UserId"},
            {"MailTemplates", "CreatedByUserId"},
            {"ManualKpiEntries", "SubjectUserId"},
            {"ManualKpiEntries", "EnteredByUserId"},
            {"NewsArticles", "CreatedByUserId"},
            {"Procedures", "CreatedByUserId"},
            {"QualityEvaluations", "AgentUserId"},
            {"QualityEvaluations", "EvaluatorUserId"},
            {"RccCommunityFollows", "UserId"},
            {"RccNotifications", "TargetUserId"},
            {"RccPoles", "ManagerUserId"},
            {"RccPosts", "AuthorUserId"},
            {"RccPostComments", "AuthorUserId"},
            {"RccPostLikes", "UserId"},
            {"RefreshTokens", "UserId"},
            {"ShiftEvents", "UserId"},
            {"ShiftSwapRequests", "RequesterUserId"},
            {"ShiftSwapRequests", "TargetUserId"},
            {"ShiftSwapRequests", "DecidedByTeamLeaderUserId"},
            {"UserFeaturePermissions", "UserId"},
            {"USER_ROLES", "USERS_ID"},
            {"USER_SERVICES", "USER_ID"},
            {"WorkflowRequests", "AssignedToUserId"},
            {"WorkflowRequests", "RequestedByUserId"},
            {"WorkflowRequests", "DecidedByUserId"},
    };

    /**
     * Tables où (colonne User, une ou plusieurs autres colonnes) doivent rester uniques — voir
     * point 4 de la Javadoc de classe. Format : {table, colonneUser, autre(s) colonne(s) de la
     * contrainte unique...}. Avant réassignation, toute ligne du doublon qui entrerait en
     * conflit avec une ligne déjà existante côté fiche gardée est supprimée plutôt que
     * réassignée (la ligne gardée fait déjà foi pour cette combinaison).
     */
    private static final String[][] UNIQUE_TABLES = {
            {"FavoriteProcedures", "UserId", "ProcedureId"},
            {"FavoriteAttachments", "UserId", "AttachmentId"},
            {"RccPostLikes", "UserId", "PostId"},
            {"RccCommunityFollows", "UserId", "CommunityKey"},
            {"UserFeaturePermissions", "UserId", "FeatureCode"},
            {"ConversationParticipants", "UserId", "ConversationId"},
            {"USER_SERVICES", "USER_ID", "SERVICE_ID"},
            {"USER_ROLES", "USERS_ID", "ROLES_ID"},
            {"AgentSchedules", "UserId", "WorkDate"},
            {"AttendanceRecords", "UserId", "WorkDate"},
            {"CourseAttempts", "UserId", "CourseId"},
    };

    /** UserProfiles.UserId est UNIQUE (une seule fiche par utilisateur, @OneToOne) — pas une
     *  contrainte composite comme UNIQUE_TABLES ci-dessus, donc traité séparément dans
     *  mergeByUsername : si la fiche gardée a déjà un profil, celui du doublon est simplement
     *  supprimé (pas de perte réelle, un profil est recréable) ; sinon celui du doublon est
     *  réassigné normalement. */
    private static final String USER_PROFILE_TABLE = "UserProfiles";
    private static final String USER_PROFILE_USER_COL = "UserId";

    // ---------- Aperçu (lecture seule) ----------

    @Transactional(readOnly = true)
    public List<DuplicateUserGroupResponse> preview() {
        List<User> all = userRepository.findAll();
        Map<String, List<User>> byUsername = new LinkedHashMap<>();
        for (User u : all) {
            if (u.getUsername() == null) continue;
            byUsername.computeIfAbsent(u.getUsername().toLowerCase(), k -> new ArrayList<>()).add(u);
        }

        List<DuplicateUserGroupResponse> groups = new ArrayList<>();
        for (Map.Entry<String, List<User>> e : byUsername.entrySet()) {
            List<User> users = e.getValue();
            if (users.size() < 2) continue;

            User keep = pickKeeper(users);
            List<DuplicateUserEntryResponse> entries = users.stream()
                    .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                    .map(u -> new DuplicateUserEntryResponse(
                            u.getId(), u.getName(), u.getStatus(), u.getActivity(), u.getLedTeam(), u.getEmail(),
                            countFilledFields(u), u.getId().equals(keep.getId())))
                    .toList();

            groups.add(new DuplicateUserGroupResponse(users.get(0).getUsername(), keep.getId(), entries));
        }
        return groups;
    }

    /** Fiche gardée = le plus de champs non vides ; à égalité, l'ID le plus petit (plus ancien). */
    private User pickKeeper(List<User> users) {
        User best = null;
        int bestScore = -1;
        for (User u : users) {
            int score = countFilledFields(u);
            if (best == null || score > bestScore || (score == bestScore && u.getId() < best.getId())) {
                best = u;
                bestScore = score;
            }
        }
        return best;
    }

    private int countFilledFields(User u) {
        int count = 0;
        if (notBlank(u.getName())) count++;
        if (notBlank(u.getStatus())) count++;
        if (notBlank(u.getEmail())) count++;
        if (notBlank(u.getActivity())) count++;
        if (notBlank(u.getRoleDetail())) count++;
        if (notBlank(u.getLedTeam())) count++;
        if (notBlank(u.getAffiliateBranch())) count++;
        if (notBlank(u.getGender())) count++;
        if (notBlank(u.getContractType())) count++;
        if (notBlank(u.getContractStatus())) count++;
        if (notBlank(u.getResidencePlace())) count++;
        if (u.getAffiliateId() != null) count++;
        if (u.getContractStartDate() != null) count++;
        if (u.getContractEndDate() != null) count++;
        return count;
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ---------- Fusion (écriture — un seul username à la fois, jamais "tout d'un coup") ----------

    /**
     * Fusionne tous les doublons d'un USERNAME donné vers la fiche gardée (déterminée par
     * pickKeeper, même règle que preview()). Réassigne toute donnée liée avant de supprimer
     * les fiches en trop — rien n'est perdu qui ne soit pas un doublon strict d'une ligne déjà
     * présente côté fiche gardée (voir UNIQUE_TABLES).
     */
    @Transactional
    public UserMergeResultResponse mergeByUsername(String username) {
        List<User> users = userRepository.findAll().stream()
                .filter(u -> username.equalsIgnoreCase(u.getUsername()))
                .toList();
        if (users.size() < 2) {
            throw ApiException.badRequest("Aucun doublon trouvé pour ce nom d'utilisateur.");
        }

        User keep = pickKeeper(users);
        List<Long> duplicateIds = new ArrayList<>();
        for (User u : users) {
            if (!u.getId().equals(keep.getId())) duplicateIds.add(u.getId());
        }

        int reassigned = 0;
        int dedupedAway = 0;

        for (Long dupId : duplicateIds) {
            // 1) Tables à contrainte unique composite : supprime d'abord les lignes du doublon
            //    qui entreraient en conflit avec une ligne déjà présente côté fiche gardée.
            for (String[] spec : UNIQUE_TABLES) {
                String table = spec[0], userCol = spec[1];
                String[] otherCols = java.util.Arrays.copyOfRange(spec, 2, spec.length);
                String sql = "DELETE d FROM " + table + " d " +
                        "INNER JOIN " + table + " k ON k." + userCol + " = ? AND " +
                        buildJoinConditions(otherCols, "d", "k") +
                        " WHERE d." + userCol + " = ?";
                int deleted = jdbcTemplate.update(sql, keep.getId(), dupId);
                dedupedAway += deleted;
            }

            // 1bis) UserProfiles : contrainte unique simple sur UserId (pas composite, voir
            //       USER_PROFILE_TABLE). Si la fiche gardée a déjà un profil, celui du doublon
            //       est supprimé plutôt que réassigné (conflit sinon) ; ce n'est pas une perte
            //       de donnée utilisateur — juste des préférences d'affichage recréables.
            //       COUNT(*) plutôt qu'un CASE WHEN...THEN 1 ELSE 0 mappé en Boolean : SQL
            //       Server n'a pas de type booléen natif pour un littéral entier, un mapping
            //       direct en Boolean.class n'est pas fiable selon le driver JDBC.
            Integer profileCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + USER_PROFILE_TABLE + " WHERE " + USER_PROFILE_USER_COL + " = ?",
                    Integer.class, keep.getId());
            boolean keepHasProfile = profileCount != null && profileCount > 0;
            if (keepHasProfile) {
                dedupedAway += jdbcTemplate.update(
                        "DELETE FROM " + USER_PROFILE_TABLE + " WHERE " + USER_PROFILE_USER_COL + " = ?", dupId);
            } else {
                reassigned += jdbcTemplate.update(
                        "UPDATE " + USER_PROFILE_TABLE + " SET " + USER_PROFILE_USER_COL + " = ? WHERE " + USER_PROFILE_USER_COL + " = ?",
                        keep.getId(), dupId);
            }

            // 2) Toutes les colonnes connues référençant USERS.ID : réassigne ce qui reste du
            //    doublon vers la fiche gardée.
            for (String[] ref : REFERENCE_COLUMNS) {
                String table = ref[0], col = ref[1];
                String sql = "UPDATE " + table + " SET " + col + " = ? WHERE " + col + " = ?";
                reassigned += jdbcTemplate.update(sql, keep.getId(), dupId);
            }
        }

        // 3) Fusionne les champs USERS eux-mêmes : la fiche gardée récupère tout champ qu'elle
        //    n'a pas mais qu'une fiche en doublon avait (ne remplace jamais un champ déjà
        //    rempli côté fiche gardée — c'est la fiche la plus complète, on ne veut pas régresser).
        for (User dup : users) {
            if (dup.getId().equals(keep.getId())) continue;
            mergeMissingFields(keep, dup);
        }
        userRepository.save(keep);

        // 4) Supprime les fiches devenues vides (tout leur historique a été réassigné à l'étape 2).
        for (Long dupId : duplicateIds) {
            userRepository.deleteById(dupId);
        }

        log.warn("⚠ [FUSION UTILISATEURS] {} — fiches fusionnées {} vers la fiche {} conservée ({} lignes réassignées, {} doublons supprimés en amont).",
                username, duplicateIds, keep.getId(), reassigned, dedupedAway);

        return new UserMergeResultResponse(username, keep.getId(), duplicateIds, reassigned, dedupedAway);
    }

    private String buildJoinConditions(String[] cols, String alias1, String alias2) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(alias1).append(".").append(cols[i]).append(" = ").append(alias2).append(".").append(cols[i]);
        }
        return sb.toString();
    }

    private void mergeMissingFields(User keep, User dup) {
        if (blank(keep.getName()) && !blank(dup.getName())) keep.setName(dup.getName());
        if (blank(keep.getStatus()) && !blank(dup.getStatus())) keep.setStatus(dup.getStatus());
        if (blank(keep.getEmail()) && !blank(dup.getEmail())) keep.setEmail(dup.getEmail());
        if (blank(keep.getActivity()) && !blank(dup.getActivity())) keep.setActivity(dup.getActivity());
        if (blank(keep.getRoleDetail()) && !blank(dup.getRoleDetail())) keep.setRoleDetail(dup.getRoleDetail());
        if (blank(keep.getLedTeam()) && !blank(dup.getLedTeam())) keep.setLedTeam(dup.getLedTeam());
        if (blank(keep.getAffiliateBranch()) && !blank(dup.getAffiliateBranch())) keep.setAffiliateBranch(dup.getAffiliateBranch());
        if (blank(keep.getGender()) && !blank(dup.getGender())) keep.setGender(dup.getGender());
        if (blank(keep.getContractType()) && !blank(dup.getContractType())) keep.setContractType(dup.getContractType());
        if (blank(keep.getContractStatus()) && !blank(dup.getContractStatus())) keep.setContractStatus(dup.getContractStatus());
        if (blank(keep.getResidencePlace()) && !blank(dup.getResidencePlace())) keep.setResidencePlace(dup.getResidencePlace());
        if (keep.getAffiliateId() == null && dup.getAffiliateId() != null) keep.setAffiliateId(dup.getAffiliateId());
        if (keep.getContractStartDate() == null && dup.getContractStartDate() != null) keep.setContractStartDate(dup.getContractStartDate());
        if (keep.getContractEndDate() == null && dup.getContractEndDate() != null) keep.setContractEndDate(dup.getContractEndDate());
    }

    private boolean blank(String s) { return s == null || s.isBlank(); }

    // ---------- Verrou anti-récidive (à appeler une fois tous les doublons fusionnés) ----------

    /** Ajoute une contrainte d'unicité sur USERS.USERNAME — idempotent, sans effet si elle
     *  existe déjà. Échoue avec une erreur SQL explicite s'il reste des doublons non fusionnés :
     *  c'est volontaire, mieux vaut un échec bruyant qu'une contrainte silencieusement absente. */
    @Transactional
    public void addUniqueUsernameConstraint() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.indexes WHERE name = 'UQ_Users_Username' AND object_id = OBJECT_ID('dbo.USERS')",
                Integer.class);
        if (exists != null && exists > 0) return;
        jdbcTemplate.execute("CREATE UNIQUE INDEX UQ_Users_Username ON dbo.USERS(USERNAME)");
    }
}
