-- Formation programmable (jour/semaine/mois) + suivi de parcours (scroll + video
-- plafonnee a 1.5x) par agent, avec statistiques equipe/individu pour le QA.
-- Voir TrainingFormation / TrainingLesson / TrainingProgress + TrainingService.
--
-- A executer une seule fois contre la base existante, apres 003_add_chat.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'TrainingFormations' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.TrainingFormations (
        FormationId INT IDENTITY(1,1) PRIMARY KEY,
        Title NVARCHAR(200) NOT NULL,
        Description NVARCHAR(MAX) NULL,
        Category NVARCHAR(100) NULL,
        ScheduledDate DATE NOT NULL,
        ScheduledTime TIME NULL,
        RecurrenceType VARCHAR(20) NOT NULL CONSTRAINT DF_TrainingFormations_Recurrence DEFAULT 'NONE'
            CONSTRAINT CK_TrainingFormations_Recurrence CHECK (RecurrenceType IN ('NONE','WEEKLY','MONTHLY')),
        DurationMinutes INT NULL,
        VideoMaxPlaybackRate FLOAT NOT NULL CONSTRAINT DF_TrainingFormations_MaxRate DEFAULT 1.5,
        CompletionThresholdPercent INT NOT NULL CONSTRAINT DF_TrainingFormations_Threshold DEFAULT 95,
        Status VARCHAR(20) NOT NULL CONSTRAINT DF_TrainingFormations_Status DEFAULT 'PLANNED',
        TargetTeam NVARCHAR(100) NULL,
        Mandatory BIT NOT NULL CONSTRAINT DF_TrainingFormations_Mandatory DEFAULT 1,
        CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_TrainingFormations_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_TrainingFormations_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'TrainingLessons' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.TrainingLessons (
        LessonId INT IDENTITY(1,1) PRIMARY KEY,
        FormationId INT NOT NULL CONSTRAINT FK_TrainingLessons_Formation REFERENCES dbo.TrainingFormations(FormationId) ON DELETE CASCADE,
        Title NVARCHAR(200) NOT NULL,
        ContentHtml NVARCHAR(MAX) NULL,
        VideoUrl NVARCHAR(500) NULL,
        OrderIndex INT NOT NULL CONSTRAINT DF_TrainingLessons_Order DEFAULT 0,
        EstimatedMinutes INT NULL,
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_TrainingLessons_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_TrainingLessons_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'TrainingProgress' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.TrainingProgress (
        ProgressId INT IDENTITY(1,1) PRIMARY KEY,
        LessonId INT NOT NULL CONSTRAINT FK_TrainingProgress_Lesson REFERENCES dbo.TrainingLessons(LessonId) ON DELETE CASCADE,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        ScrollPercent INT NOT NULL CONSTRAINT DF_TrainingProgress_Scroll DEFAULT 0,
        VideoWatchedPercent INT NOT NULL CONSTRAINT DF_TrainingProgress_Video DEFAULT 0,
        Completed BIT NOT NULL CONSTRAINT DF_TrainingProgress_Completed DEFAULT 0,
        StartedAt DATETIME2 NULL,
        CompletedAt DATETIME2 NULL,
        LastActivityAt DATETIME2 NULL,
        SpeedViolationCount INT NOT NULL CONSTRAINT DF_TrainingProgress_SpeedViol DEFAULT 0,
        CONSTRAINT UQ_TrainingProgress_Lesson_User UNIQUE (LessonId, UserId)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_TrainingLessons_Formation')
    CREATE INDEX IX_TrainingLessons_Formation ON dbo.TrainingLessons(FormationId);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_TrainingProgress_User')
    CREATE INDEX IX_TrainingProgress_User ON dbo.TrainingProgress(UserId);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_TrainingFormations_ScheduledDate')
    CREATE INDEX IX_TrainingFormations_ScheduledDate ON dbo.TrainingFormations(ScheduledDate);
GO
