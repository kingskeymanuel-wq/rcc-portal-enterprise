-- Disponibilité des cartes — onglet de la Base de connaissances (voir CardProduct.java,
-- CardAvailability.java, card-availability.js). Les cartes proposées par filiale et leur
-- disponibilité par ville sont cochées par QA. Idempotent.
-- (Les tables sont aussi créées au démarrage par WorkflowSchemaBootstrap si absentes.)

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'CardProducts' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.CardProducts (
        CardProductId BIGINT IDENTITY(1,1) PRIMARY KEY,
        CountryCode   NVARCHAR(2) NOT NULL,
        Name          NVARCHAR(150) NOT NULL,
        Category      NVARCHAR(80) NULL,
        Details       NVARCHAR(1000) NULL,
        SortOrder     INT NOT NULL CONSTRAINT DF_CardProducts_SortOrder DEFAULT 0,
        IsActive      BIT NOT NULL CONSTRAINT DF_CardProducts_IsActive DEFAULT 1,
        CreatedAt     DATETIME2 NOT NULL CONSTRAINT DF_CardProducts_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt     DATETIME2 NOT NULL CONSTRAINT DF_CardProducts_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
    CREATE INDEX IX_CardProducts_CountryCode ON dbo.CardProducts(CountryCode);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'CardAvailability' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.CardAvailability (
        CardAvailabilityId BIGINT IDENTITY(1,1) PRIMARY KEY,
        CardProductId      BIGINT NOT NULL,
        City               NVARCHAR(100) NOT NULL,
        Available          BIT NOT NULL CONSTRAINT DF_CardAvailability_Available DEFAULT 0,
        Note               NVARCHAR(500) NULL,
        UpdatedBy          NVARCHAR(150) NULL,
        CreatedAt          DATETIME2 NOT NULL CONSTRAINT DF_CardAvailability_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt          DATETIME2 NOT NULL CONSTRAINT DF_CardAvailability_UpdatedAt DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_CardAvailability_CardCity UNIQUE (CardProductId, City)
    );
END
GO
