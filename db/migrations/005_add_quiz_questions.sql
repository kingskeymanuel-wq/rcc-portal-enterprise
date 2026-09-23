-- Banque de questions centrale et enrichie (theme, difficulte, type, explication,
-- image, tags, points, temps limite) — alimente l'onglet "Questions" et servira
-- de reservoir commun aux jeux. Voir QuizQuestion / QuizQuestionService.
--
-- A executer une seule fois contre la base existante, apres 004_add_training_scheduling.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'QuizQuestions' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.QuizQuestions (
        QuestionId INT IDENTITY(1,1) PRIMARY KEY,
        QuestionText NVARCHAR(1000) NOT NULL,
        Type VARCHAR(20) NOT NULL CONSTRAINT DF_QuizQuestions_Type DEFAULT 'MCQ'
            CONSTRAINT CK_QuizQuestions_Type CHECK (Type IN ('MCQ','TRUE_FALSE','MULTI_SELECT')),
        Difficulty VARCHAR(20) NOT NULL CONSTRAINT DF_QuizQuestions_Difficulty DEFAULT 'MEDIUM'
            CONSTRAINT CK_QuizQuestions_Difficulty CHECK (Difficulty IN ('EASY','MEDIUM','HARD','EXPERT')),
        Category NVARCHAR(100) NULL,
        OptionsJson NVARCHAR(MAX) NULL,
        CorrectOptionIndex INT NULL,
        CorrectIndexesJson NVARCHAR(500) NULL,
        Explanation NVARCHAR(MAX) NULL,
        ImageUrl NVARCHAR(500) NULL,
        Tags NVARCHAR(300) NULL,
        Points INT NOT NULL CONSTRAINT DF_QuizQuestions_Points DEFAULT 10,
        TimeLimitSeconds INT NULL,
        Active BIT NOT NULL CONSTRAINT DF_QuizQuestions_Active DEFAULT 1,
        CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        UsageCount INT NOT NULL CONSTRAINT DF_QuizQuestions_Usage DEFAULT 0,
        CorrectAnswerCount INT NOT NULL CONSTRAINT DF_QuizQuestions_CorrectCount DEFAULT 0,
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_QuizQuestions_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_QuizQuestions_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_QuizQuestions_Category')
    CREATE INDEX IX_QuizQuestions_Category ON dbo.QuizQuestions(Category);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_QuizQuestions_Difficulty')
    CREATE INDEX IX_QuizQuestions_Difficulty ON dbo.QuizQuestions(Difficulty);
GO
