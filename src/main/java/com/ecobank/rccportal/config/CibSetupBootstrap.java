package com.ecobank.rccportal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Équipe CIB (traitements des entreprises) toujours disponible dans l'Administration : rôles « Agent CIB » et
 * « Team Leader CIB », services AGENT_CIB et TEAM_LEADER_CIB, équipe CIB. Créés s'ils manquent, réactivés s'ils
 * ont été désactivés — jamais supprimés ni renommés. Passe après WorkflowSchemaBootstrap (tables déjà créées).
 */
@Slf4j
@Component
@Order(5)
public class CibSetupBootstrap implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    public CibSetupBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        try {
            ensureRole("Agent CIB", "Conseiller clientèle — CIB (traitements des entreprises)");
            ensureRole("Team Leader CIB", "Responsable de l'équipe CIB");
            ensureService("AGENT_CIB", "Agent CIB", "Conseiller clientèle — pôle CIB (traitements des entreprises)", 14);
            ensureService("TEAM_LEADER_CIB", "Team Leader CIB", "Responsable de l'équipe CIB", 24);
            ensureTeam();
            seedKnowledgeCategories();
        } catch (RuntimeException e) {
            log.warn("[CIB] Mise en place de l'équipe CIB incomplète : {}", e.getMessage());
        }
    }

    private void ensureRole(String name, String description) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.ROLES WHERE LOWER(NAME) = LOWER(?)", Integer.class, name);
        if (n != null && n > 0) return;
        jdbc.update("INSERT INTO dbo.ROLES (NAME, DESCRIPTION) VALUES (?, ?)", name, description);
        log.info("[CIB] Rôle « {} » créé.", name);
    }

    private void ensureService(String code, String name, String description, int order) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.SERVICES WHERE UPPER(CODE) = ?", Integer.class, code);
        if (n != null && n > 0) {
            if (hasColumn("SERVICES", "ENABLED")) {
                int fixed = jdbc.update("UPDATE dbo.SERVICES SET ENABLED = 1 WHERE UPPER(CODE) = ? AND (ENABLED IS NULL OR ENABLED = 0)", code);
                if (fixed > 0) log.info("[CIB] Service {} réactivé.", code);
            }
            return;
        }
        if (hasColumn("SERVICES", "DESCRIPTION")) {
            jdbc.update("INSERT INTO dbo.SERVICES (NAME, DESCRIPTION, CODE, ENABLED, DISPLAY_ORDER) VALUES (?, ?, ?, 1, ?)", name, description, code, order);
        } else {
            jdbc.update("INSERT INTO dbo.SERVICES (NAME, CODE, ENABLED, DISPLAY_ORDER) VALUES (?, ?, 1, ?)", name, code, order);
        }
        log.info("[CIB] Service {} ({}) créé.", name, code);
    }

    private void ensureTeam() {
        if (!hasColumn("Teams", "Code")) return;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.Teams WHERE UPPER(Code) = 'CIB'", Integer.class);
        if (n != null && n > 0) {
            int fixed = jdbc.update("UPDATE dbo.Teams SET IsActive = 1 WHERE UPPER(Code) = 'CIB' AND (IsActive IS NULL OR IsActive = 0)");
            if (fixed > 0) log.info("[CIB] Équipe CIB réactivée.");
            return;
        }
        jdbc.update("INSERT INTO dbo.Teams (Code, Label, IconGlyph, AccentColor, IsActive) VALUES ('CIB', 'CIB', NULL, NULL, 1)");
        log.info("[CIB] Équipe CIB créée.");
    }

    /**
     * Rubriques de départ de la base de connaissance CIB (vides : l'administrateur y dépose ses fichiers depuis
     * Administration → Base CIB, et les renomme ou les supprime à sa guise). Posées une seule fois : dès qu'une
     * rubrique CIB existe, rien n'est recréé — une rubrique supprimée ne revient pas au redémarrage.
     */
    public static final String[][] CIB_CATEGORIES = {
            {"CIB_COMPTES", "Ouverture & gestion de compte entreprise", "bi-building-add"},
            {"CIB_KYC", "KYC & mise à jour des dossiers", "bi-person-vcard"},
            {"CIB_SIGNATAIRES", "Mandataires & signatures autorisées", "bi-pen"},
            {"CIB_VIREMENTS", "Virements locaux & internationaux (SWIFT)", "bi-globe2"},
            {"CIB_PAIEMENTS_MASSE", "Paiements de masse & salaires", "bi-people"},
            {"CIB_CHEQUES", "Chèques & effets de commerce", "bi-receipt"},
            {"CIB_COMMERCE_EXT", "Commerce extérieur (crédit & remise documentaires)", "bi-box-seam"},
            {"CIB_GARANTIES", "Garanties & cautions bancaires", "bi-shield-check"},
            {"CIB_CREDITS", "Crédits & financements entreprises", "bi-cash-stack"},
            {"CIB_TRESORERIE", "Cash management & trésorerie", "bi-graph-up-arrow"},
            {"CIB_DIGITAL", "Banque en ligne entreprise (Omni)", "bi-laptop"},
            {"CIB_RELEVES", "Relevés, attestations & certificats", "bi-file-earmark-text"},
            {"CIB_TARIFS", "Tarification & conditions", "bi-percent"},
            {"CIB_RECLAMATIONS", "Réclamations & suivi des incidents", "bi-exclamation-triangle"},
            {"CIB_AUTRE", "Autres traitements", "bi-three-dots"}
    };

    private static final String SEEDED_KEY = "kb.cib.categories.seeded";

    private void seedKnowledgeCategories() {
        if (!hasColumn("KnowledgeCategories", "Team")) return;
        boolean marker = hasColumn("SiteSettings", "SettingKey");
        if (marker) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.SiteSettings WHERE SettingKey = ?", Integer.class, SEEDED_KEY);
            if (done != null && done > 0) return; // déjà posées une fois : les suppressions de l'administrateur sont respectées
        } else {
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.KnowledgeCategories WHERE UPPER(Team) = 'CIB'", Integer.class);
            if (existing != null && existing > 0) return;
        }
        int order = 0, created = 0;
        for (String[] c : CIB_CATEGORIES) {
            order++;
            Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.KnowledgeCategories WHERE UPPER(Code) = ?", Integer.class, c[0]);
            if (taken != null && taken > 0) continue;
            jdbc.update("INSERT INTO dbo.KnowledgeCategories (Code, Title, Icon, SortOrder, Team) VALUES (?, ?, ?, ?, 'CIB')", c[0], c[1], c[2], order);
            created++;
        }
        if (marker) jdbc.update("INSERT INTO dbo.SiteSettings (SettingKey, SettingValue) VALUES (?, ?)", SEEDED_KEY, java.time.LocalDate.now().toString());
        log.info("[CIB] {} rubrique(s) de la base de connaissance CIB créée(s).", created);
    }

    private boolean hasColumn(String table, String column) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sys.columns c JOIN sys.tables t ON t.object_id = c.object_id "
                + "WHERE t.name = ? AND c.name = ?", Integer.class, table, column);
        return n != null && n > 0;
    }
}
