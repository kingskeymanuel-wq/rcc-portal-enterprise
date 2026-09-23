-- Seuils SLA personnalisés par équipe + type de demande — complète le seuil
-- global existant (SiteSettings). Voir WorkflowService.resolveThresholdFor.
--
-- A executer une seule fois contre la base existante, apres 008_seed_quiz_questions.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'SlaTargets' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.SlaTargets (
        SlaTargetId INT IDENTITY(1,1) PRIMARY KEY,
        Team VARCHAR(10) NOT NULL,
        Type VARCHAR(30) NOT NULL,
        ThresholdHours INT NOT NULL,
        UpdatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_SlaTargets_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_SlaTargets_UpdatedAt DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_SlaTargets_Team_Type UNIQUE (Team, Type)
    );
END
GO
