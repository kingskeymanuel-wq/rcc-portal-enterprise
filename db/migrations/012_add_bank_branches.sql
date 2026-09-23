-- Table des agences Ecobank par filiale/ville — alimente l'onglet "Carte des
-- banques" de la Base de connaissance (voir BankBranch.java / bank-map.js).
-- Idempotent : peut être exécuté plusieurs fois sans risque.
-- A exécuter une seule fois contre la base existante, après 011_add_campaign_contact_extra_data.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'BankBranches' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.BankBranches (
        BranchId      BIGINT IDENTITY(1,1) PRIMARY KEY,
        CountryCode   NVARCHAR(2) NOT NULL,
        City          NVARCHAR(100) NOT NULL,
        Name          NVARCHAR(200) NOT NULL,
        Address       NVARCHAR(500) NULL,
        Latitude      FLOAT NULL,
        Longitude     FLOAT NULL,
        Phone         NVARCHAR(50) NULL,
        Email         NVARCHAR(150) NULL,
        OpeningHours  NVARCHAR(200) NULL,
        ManagerName   NVARCHAR(150) NULL,
        BranchType    NVARCHAR(50) NULL,
        IsActive      BIT NOT NULL CONSTRAINT DF_BankBranches_IsActive DEFAULT 1,
        CreatedAt     DATETIME2 NOT NULL CONSTRAINT DF_BankBranches_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt     DATETIME2 NOT NULL CONSTRAINT DF_BankBranches_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
    CREATE INDEX IX_BankBranches_CountryCode ON dbo.BankBranches(CountryCode);
    CREATE INDEX IX_BankBranches_CountryCity ON dbo.BankBranches(CountryCode, City);
END
GO
