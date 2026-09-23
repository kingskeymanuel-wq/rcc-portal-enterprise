-- Cartes "fonctionnalité" de la page de connexion (Sécurité Enterprise, Knowledge
-- Base...), éditables depuis Administration > Apparence par QA/Admin. Le carousel
-- de photos et le style (police/interligne) réutilisent la table SiteSettings
-- déjà existante (aucune nouvelle table nécessaire pour eux).
--
-- A executer une seule fois contre la base existante, apres 009_add_sla_targets.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'LoginFeatureCards' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.LoginFeatureCards (
        CardId INT IDENTITY(1,1) PRIMARY KEY,
        Icon NVARCHAR(50) NOT NULL,
        Title NVARCHAR(100) NOT NULL,
        Subtitle NVARCHAR(200) NULL,
        SortOrder INT NOT NULL CONSTRAINT DF_LoginFeatureCards_Sort DEFAULT 0,
        Active BIT NOT NULL CONSTRAINT DF_LoginFeatureCards_Active DEFAULT 1,
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_LoginFeatureCards_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_LoginFeatureCards_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO
