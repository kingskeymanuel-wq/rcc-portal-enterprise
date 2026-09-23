-- Garantit que dbo.CampaignContacts existe avec la colonne ExtraDataJson, quel que soit
-- l'état actuel de la base :
--   1. Si la table n'existe pas du tout, elle est créée en entier (fidèle à
--      CampaignContact.java) — rattrape le fait qu'elle n'avait jamais été scriptée dans ce
--      dépôt jusqu'ici.
--   2. Si la table existe déjà (le cas le plus probable) mais sans ExtraDataJson, seule la
--      colonne est ajoutée — AUCUNE donnée existante n'est touchée.
--
-- ExtraDataJson conserve toute colonne d'un fichier d'appel importé qui ne correspond à aucun
-- champ connu (nom/téléphone/compte/agent/question de campagne) — ex. CIF, région, segment,
-- branche... (fichier "dossier PI Select"). Garantit qu'aucune donnée du fichier source n'est
-- perdue à l'import. Voir CampaignContact.java / CampaignService.readContactsFile.
--
-- Idempotent : peut être exécuté plusieurs fois sans risque.
-- A executer une seule fois contre la base existante, apres 010_add_login_feature_cards.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'CampaignContacts' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.CampaignContacts (
        ContactId           INT IDENTITY(1,1) PRIMARY KEY,
        CampaignId          INT NOT NULL,
        AgentUserId         BIGINT NULL,
        ClientName          NVARCHAR(200) NOT NULL,
        ClientPhone         NVARCHAR(50) NULL,
        MaskedAccountNumber NVARCHAR(30) NULL,
        CallStatus          NVARCHAR(20) NOT NULL CONSTRAINT DF_CampaignContacts_CallStatus DEFAULT 'PENDING',
        Notes               NVARCHAR(1000) NULL,
        AnswersJson         NVARCHAR(4000) NULL,
        ExtraDataJson       NVARCHAR(4000) NULL,
        LastCalledAt        DATETIME2 NULL,
        AppointmentId       INT NULL,
        CreatedAt           DATETIME2 NOT NULL CONSTRAINT DF_CampaignContacts_CreatedAt DEFAULT SYSUTCDATETIME(),
        CONSTRAINT FK_CampaignContacts_Campaign FOREIGN KEY (CampaignId) REFERENCES dbo.Campaigns(CampaignId)
    );
    CREATE INDEX IX_CampaignContacts_CampaignId ON dbo.CampaignContacts(CampaignId);
    CREATE INDEX IX_CampaignContacts_AgentUserId ON dbo.CampaignContacts(AgentUserId);
END
ELSE IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.CampaignContacts') AND name = 'ExtraDataJson'
)
BEGIN
    ALTER TABLE dbo.CampaignContacts ADD ExtraDataJson NVARCHAR(4000) NULL;
END
GO
