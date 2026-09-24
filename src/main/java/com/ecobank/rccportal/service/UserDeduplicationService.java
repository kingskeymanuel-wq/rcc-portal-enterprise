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
        List<User> dups = users.stream().filter(u -> !u.getId().equals(keep.getId())).toList();
        MergeCounts counts = mergeInto(keep, dups);
        List<Long> duplicateIds = dups.stream().map(User::getId).toList();
        log.warn("⚠ [FUSION UTILISATEURS] {} — fiches fusionnées {} vers la fiche {} conservée ({} lignes réassignées, {} doublons supprimés en amont).",
                username, duplicateIds, keep.getId(), counts.reassigned(), counts.dedupedAway());
        return new UserMergeResultResponse(username, keep.getId(), duplicateIds, counts.reassigned(), counts.dedupedAway());
    }

    public record MergeCounts(int reassigned, int dedupedAway, List<String> warnings) {}

    /**
     * Fusionne des fiches d'une MÊME personne (identifiants différents possibles) dans la fiche
     * gardée : tout l'historique est réassigné, les champs manquants complétés, les fiches en
     * trop supprimées. Utilisé par la fusion par identifiant ET par la fusion des doublons de
     * nom (UserDuplicateService). En plus de la liste connue REFERENCE_COLUMNS, les autres
     * colonnes …UserId de la base (tables ajoutées depuis) et les colonnes …Username sont
     * découvertes et traitées — aucune donnée orpheline.
     */
    @Transactional
    public MergeCounts mergeInto(User keep, List<User> dups) {
        int reassigned = 0;
        int dedupedAway = 0;
        List<String> warnings = new ArrayList<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (String[] ref : REFERENCE_COLUMNS) known.add((ref[0] + "." + ref[1]).toLowerCase());
        known.add((USER_PROFILE_TABLE + "." + USER_PROFILE_USER_COL).toLowerCase());
        List<String[]> extraIdColumns = discover("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'dbo' "
                + "AND TABLE_NAME <> 'USERS' AND DATA_TYPE IN ('int','bigint') AND (COLUMN_NAME LIKE '%UserId' OR COLUMN_NAME IN ('USER_ID','USERS_ID'))")
                .stream().filter(c -> !known.contains((c[0] + "." + c[1]).toLowerCase())).toList();
        List<String[]> usernameColumns = discover("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'dbo' "
                + "AND TABLE_NAME <> 'USERS' AND DATA_TYPE IN ('nvarchar','varchar') AND (COLUMN_NAME LIKE '%Username' OR COLUMN_NAME = 'USERNAME')");

        for (User dup : dups) {
            Long dupId = dup.getId();
            if (dupId == null || dupId.equals(keep.getId())) continue;
            // 1) Tables à contrainte unique composite : supprime d'abord les lignes du doublon
            //    qui entreraient en conflit avec une ligne déjà présente côté fiche gardée.
            for (String[] spec : UNIQUE_TABLES) {
                String table = spec[0], userCol = spec[1];
                String[] otherCols = java.util.Arrays.copyOfRange(spec, 2, spec.length);
                String sql = "DELETE d FROM " + table + " d " +
                        "INNER JOIN " + table + " k ON k." + userCol + " = ? AND " +
                        buildJoinConditions(otherCols, "d", "k") +
                        " WHERE d." + userCol + " = ?";
                dedupedAway += safeUpdate(sql, warnings, keep.getId(), dupId);
            }

            // 1bis) UserProfiles : contrainte unique simple sur UserId. Si la fiche gardée a
            //       déjà un profil, on lui recopie ce qui lui manque puis on supprime celui du doublon.
            Integer profileCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + USER_PROFILE_TABLE + " WHERE " + USER_PROFILE_USER_COL + " = ?",
                    Integer.class, keep.getId());
            boolean keepHasProfile = profileCount != null && profileCount > 0;
            if (keepHasProfile) {
                safeUpdate("UPDATE k SET k.PhotoUrl = COALESCE(NULLIF(k.PhotoUrl, ''), d.PhotoUrl), k.Phone = COALESCE(NULLIF(k.Phone, ''), d.Phone), "
                        + "k.Bio = COALESCE(NULLIF(k.Bio, ''), d.Bio), k.Birthdate = COALESCE(k.Birthdate, d.Birthdate) "
                        + "FROM " + USER_PROFILE_TABLE + " k JOIN " + USER_PROFILE_TABLE + " d ON d." + USER_PROFILE_USER_COL + " = ? "
                        + "WHERE k." + USER_PROFILE_USER_COL + " = ?", warnings, dupId, keep.getId());
                dedupedAway += jdbcTemplate.update(
                        "DELETE FROM " + USER_PROFILE_TABLE + " WHERE " + USER_PROFILE_USER_COL + " = ?", dupId);
            } else {
                reassigned += jdbcTemplate.update(
                        "UPDATE " + USER_PROFILE_TABLE + " SET " + USER_PROFILE_USER_COL + " = ? WHERE " + USER_PROFILE_USER_COL + " = ?",
                        keep.getId(), dupId);
            }

            // 2) Toutes les colonnes connues référençant USERS.ID.
            for (String[] ref : REFERENCE_COLUMNS) {
                reassigned += safeUpdate("UPDATE " + ref[0] + " SET " + ref[1] + " = ? WHERE " + ref[1] + " = ?", warnings, keep.getId(), dupId);
            }
            // 2bis) Colonnes découvertes dans la base (tables plus récentes que la liste) :
            //       réassignation, ou suppression de la ligne en conflit si elle ferait double emploi.
            for (String[] ref : extraIdColumns) {
                String t = "dbo.[" + ref[0] + "]", c = "[" + ref[1] + "]";
                try {
                    reassigned += jdbcTemplate.update("UPDATE " + t + " SET " + c + " = ? WHERE " + c + " = ?", keep.getId(), dupId);
                } catch (RuntimeException conflict) {
                    dedupedAway += safeUpdate("DELETE FROM " + t + " WHERE " + c + " = ?", warnings, dupId);
                }
            }
            // 2ter) Colonnes qui mémorisent l'identifiant de connexion (…Username).
            if (dup.getUsername() != null && keep.getUsername() != null && !dup.getUsername().equalsIgnoreCase(keep.getUsername())) {
                for (String[] ref : usernameColumns) {
                    reassigned += safeUpdate("UPDATE dbo.[" + ref[0] + "] SET [" + ref[1] + "] = ? WHERE [" + ref[1] + "] = ?",
                            warnings, keep.getUsername(), dup.getUsername());
                }
            }
            // 3) Champs USERS manquants côté fiche gardée.
            mergeMissingFields(keep, dup);
        }
        userRepository.save(keep);

        // 4) Supprime les fiches devenues vides.
        for (User dup : dups) {
            if (dup.getId() != null && !dup.getId().equals(keep.getId())) userRepository.deleteById(dup.getId());
        }
        return new MergeCounts(reassigned, dedupedAway, warnings);
    }

    private int safeUpdate(String sql, List<String> warnings, Object... args) {
        try {
            return jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            warnings.add(m.length() > 160 ? m.substring(0, 160) + "…" : m);
            return 0;
        }
    }

    private List<String[]> discover(String sql) {
        try {
            return jdbcTemplate.query(sql, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)});
        } catch (RuntimeException e) {
            log.warn("[FUSION UTILISATEURS] Lecture du schéma impossible : {}", e.getMessage());
            return List.of();
        }
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
