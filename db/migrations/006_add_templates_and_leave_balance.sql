-- Modèles de demande (RequestTemplates) et soldes de congés annuels (LeaveBalances).
-- Le SLA (workflow.sla.thresholdHours) ne nécessite pas de nouvelle table — il est
-- stocké dans la table SiteSettings déjà existante et calculé à la volée sur
-- WorkflowRequests (voir WorkflowService.getSlaOverview).
--
-- A executer une seule fois contre la base existante, apres 005_add_quiz_questions.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'RequestTemplates' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.RequestTemplates (
        TemplateId INT IDENTITY(1,1) PRIMARY KEY,
        Name NVARCHAR(150) NOT NULL,
        Type VARCHAR(30) NOT NULL CONSTRAINT CK_RequestTemplates_Type CHECK (Type IN ('LEAVE','PROCEDURE_CHANGE','ACCESS')),
        DefaultTitle NVARCHAR(200) NOT NULL,
        DefaultDetails NVARCHAR(2000) NULL,
        DefaultAssignedTeam VARCHAR(10) NULL,
        Active BIT NOT NULL CONSTRAINT DF_RequestTemplates_Active DEFAULT 1,
        CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RequestTemplates_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RequestTemplates_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'LeaveBalances' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.LeaveBalances (
        BalanceId INT IDENTITY(1,1) PRIMARY KEY,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        Year INT NOT NULL,
        AllocatedDays INT NOT NULL CONSTRAINT DF_LeaveBalances_Allocated DEFAULT 24,
        UpdatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_LeaveBalances_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_LeaveBalances_UpdatedAt DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_LeaveBalances_User_Year UNIQUE (UserId, Year)
    );
END
GO
