/* ============================================================================
   RCC PORTAL ENTERPRISE — SCHÉMA SQL COMPLET
   ----------------------------------------------------------------------------
   Généré à partir des entités JPA réelles (src/main/java/.../model,
   src/main/java/.../entity) au 04/08/2026. Toutes les colonnes, tailles et
   contraintes ci-dessous correspondent exactement aux annotations @Column,
   @Table, @JoinColumn et @UniqueConstraint du code Java — ce script ne
   réinvente rien, il documente ce que Hibernate attend déjà.

   Ordre d'exécution : les tables sont créées dans l'ordre de leurs
   dépendances (une table n'apparaît qu'après celles qu'elle référence en FK).
   Exécuter ce fichier une seule fois sur une base vide.

   Voir aussi :
   - 02_documentation.md (même dossier) : explication de chaque table, des
     relations entre modules, et comment lire/interroger la base.
   - com.ecobank.rccportal.config.WorkflowSchemaBootstrap : mécanisme de
     secours qui recrée automatiquement au démarrage toute table manquante
     parmi un sous-ensemble (Courses, ShiftEvents, WorkflowRequests, Quality*,
     RefreshTokens, UserProfiles, RccNotifications, RccPosts*, RccStories,
     RccCommunityFollows) — utile en développement, mais CE script reste la
     référence complète et doit être utilisé pour tout environnement réel.
   ============================================================================ */

/* ============================================================================
   VERSION IDEMPOTENTE — sûre à exécuter sur une base qui a déjà des données.
   Chaque table n'est créée que si elle n'existe pas encore (IF OBJECT_ID ...
   IS NULL) — aucune table existante n'est touchée, aucun DROP nulle part.
   Peut être relancée autant de fois que nécessaire sans effet de bord.
   ============================================================================ */

/* ============================================================================
   1. RÉFÉRENTIELS SANS DÉPENDANCE
   ============================================================================ */

IF OBJECT_ID('dbo.ROLES', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ROLES (
        ID INT IDENTITY PRIMARY KEY,
        NAME NVARCHAR(255) NOT NULL,
        DESCRIPTION NVARCHAR(255) NULL
    );
    PRINT 'Créée : dbo.ROLES';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ROLES';


IF OBJECT_ID('dbo.Teams', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.Teams (
        TeamId INT IDENTITY PRIMARY KEY,
        Code NVARCHAR(50) NOT NULL UNIQUE,
        Label NVARCHAR(100) NOT NULL,
        IconGlyph NVARCHAR(20) NULL,
        AccentColor NVARCHAR(10) NULL,
        IsActive BIT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.Teams';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.Teams';


IF OBJECT_ID('dbo.SERVICES', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.SERVICES (
        ID INT IDENTITY PRIMARY KEY,
        NAME NVARCHAR(100) NOT NULL,
        DESCRIPTION NVARCHAR(500) NULL,
        E_PATH NVARCHAR(150) NULL,
        CODE NVARCHAR(50) NOT NULL,
        ICON NVARCHAR(100) NULL,
        COLOR NVARCHAR(20) NULL,
        STATUS NVARCHAR(30) NULL,
        ENABLED BIT NULL,
        DISPLAY_ORDER INT NULL,
        OPEN_IN_NEW_TAB BIT NULL,
        IS_PORTAL_APP BIT NULL,
        PROXY_CODE NVARCHAR(50) NULL
    );
    PRINT 'Créée : dbo.SERVICES';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.SERVICES';


IF OBJECT_ID('dbo.ProcedureZones', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ProcedureZones (
        ZoneId INT IDENTITY PRIMARY KEY,
        Code NVARCHAR(20) NOT NULL UNIQUE,
        Label NVARCHAR(100) NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.ProcedureZones';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ProcedureZones';


IF OBJECT_ID('dbo.MailTemplateCategories', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.MailTemplateCategories (
        CategoryId INT IDENTITY PRIMARY KEY,
        Code NVARCHAR(50) NOT NULL UNIQUE,
        Label NVARCHAR(100) NOT NULL,
        IconGlyph NVARCHAR(20) NULL,
        AccentColor NVARCHAR(10) NULL,
        BackgroundColor NVARCHAR(10) NULL,
        SortOrder INT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.MailTemplateCategories';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.MailTemplateCategories';


IF OBJECT_ID('dbo.MailRecipientGroups', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.MailRecipientGroups (
        GroupId INT IDENTITY PRIMARY KEY,
        Label NVARCHAR(150) NOT NULL,
        Email NVARCHAR(254) NOT NULL UNIQUE,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.MailRecipientGroups';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.MailRecipientGroups';


IF OBJECT_ID('dbo.QualityCriteria', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.QualityCriteria (
        CriterionId INT IDENTITY PRIMARY KEY,
        Code NVARCHAR(10) NOT NULL UNIQUE,
        Section NVARCHAR(50) NOT NULL,
        Name NVARCHAR(150) NOT NULL,
        Description NVARCHAR(500) NULL,
        Weight INT NOT NULL,
        IsKnockOut BIT NOT NULL,
        SortOrder INT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.QualityCriteria';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.QualityCriteria';


IF OBJECT_ID('dbo.QualityMotifs', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.QualityMotifs (
        MotifId INT IDENTITY PRIMARY KEY,
        Label NVARCHAR(150) NOT NULL,
        IsActive BIT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.QualityMotifs';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.QualityMotifs';


IF OBJECT_ID('dbo.KnowledgeCategories', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.KnowledgeCategories (
        CategoryId INT IDENTITY PRIMARY KEY,
        Code NVARCHAR(50) NOT NULL UNIQUE,
        Title NVARCHAR(100) NOT NULL,
        Icon NVARCHAR(50) NULL,
        SortOrder INT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.KnowledgeCategories';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.KnowledgeCategories';


IF OBJECT_ID('dbo.KnowledgeCountries', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.KnowledgeCountries (
        CountryCode NVARCHAR(2) PRIMARY KEY,
        Label NVARCHAR(100) NOT NULL,
        FlagEmoji NVARCHAR(10) NULL,
        Currency NVARCHAR(100) NULL,
        Zone NVARCHAR(100) NULL,
        Regulator NVARCHAR(100) NULL,
        AgencyCount NVARCHAR(50) NULL,
        Phone NVARCHAR(20) NULL,
        SortOrder INT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.KnowledgeCountries';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.KnowledgeCountries';


/* Store clé/valeur générique — reçoit tout ce qui n'a pas encore sa propre table. */
IF OBJECT_ID('dbo.KvEntries', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.KvEntries (
        Scope NVARCHAR(100) NOT NULL,
        [Key] NVARCHAR(200) NOT NULL,
        Value NVARCHAR(MAX) NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_KvEntries PRIMARY KEY (Scope, [Key])
    );
    PRINT 'Créée : dbo.KvEntries';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.KvEntries';


/* ============================================================================
   2. UTILISATEURS ET ACCÈS
   ============================================================================ */

IF OBJECT_ID('dbo.USERS', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.USERS (
        ID BIGINT IDENTITY PRIMARY KEY,
        USERNAME NVARCHAR(35) NOT NULL,
        NAME NVARCHAR(200) NULL,
        EMAIL NVARCHAR(255) NULL,
        STATUS NVARCHAR(20) NULL,
        ACCOUNT_ENABLED BIT NULL,
        ACCOUNT_EXPIRED BIT NULL,
        ACCOUNT_LOCKED BIT NULL,
        ACCOUNT_DATE_EXPIRED DATE NULL,
        CREDENTIALS_EXPIRED BIT NULL,
        FAILED_ATTEMPTS INT NOT NULL DEFAULT 0,
        AUTHORIZER NVARCHAR(225) NULL,
        INPUTTER NVARCHAR(20) NULL,
        AFFILIATE_ID BIGINT NULL,
        AFFILIATE_BRANCH NVARCHAR(3) NULL,
        /* Ne jamais stocker le vrai mot de passe AD ici — conservé pour compatibilité de schéma uniquement. */
        PASSWORD NVARCHAR(255) NULL,
        CREATED_AT DATETIMEOFFSET NULL,
        MODIFIED_AT DATETIMEOFFSET NULL
    );
    PRINT 'Créée : dbo.USERS';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.USERS';


IF OBJECT_ID('dbo.USER_ROLES', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.USER_ROLES (
        ID BIGINT IDENTITY PRIMARY KEY,
        USERS_ID BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        ROLES_ID INT NOT NULL REFERENCES dbo.ROLES(ID)
    );
    PRINT 'Créée : dbo.USER_ROLES';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.USER_ROLES';


IF OBJECT_ID('dbo.USER_SERVICES', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.USER_SERVICES (
        ID BIGINT IDENTITY PRIMARY KEY,
        USER_ID BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        SERVICE_ID INT NOT NULL REFERENCES dbo.SERVICES(ID)
    );
    PRINT 'Créée : dbo.USER_SERVICES';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.USER_SERVICES';


IF OBJECT_ID('dbo.UserProfiles', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.UserProfiles (
        UserProfileId INT IDENTITY PRIMARY KEY,
        UserId BIGINT NOT NULL UNIQUE REFERENCES dbo.USERS(ID),
        PhotoUrl NVARCHAR(500) NULL,
        Birthdate DATE NULL,
        Phone NVARCHAR(30) NULL,
        Bio NVARCHAR(1000) NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.UserProfiles';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.UserProfiles';


IF OBJECT_ID('dbo.RefreshTokens', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.RefreshTokens (
        RefreshTokenId INT IDENTITY PRIMARY KEY,
        Jti UNIQUEIDENTIFIER NOT NULL UNIQUE,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        ExpiresAt DATETIME2 NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.RefreshTokens';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.RefreshTokens';


IF OBJECT_ID('dbo.LoginAudit', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.LoginAudit (
        LoginAuditId INT IDENTITY PRIMARY KEY,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        /* 'login_success' | 'login_failed' | 'locked' | 'password_reset' */
        EventType NVARCHAR(30) NOT NULL,
        OccurredAt DATETIME2 NOT NULL,
        PasswordAgeDays INT NULL,
        IpAddress NVARCHAR(45) NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.LoginAudit';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.LoginAudit';


IF OBJECT_ID('dbo.TabPermissions', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.TabPermissions (
        TabPermissionId INT IDENTITY PRIMARY KEY,
        TeamId INT NULL REFERENCES dbo.Teams(TeamId),
        RoleId INT NULL REFERENCES dbo.ROLES(ID),
        TabCode NVARCHAR(50) NOT NULL,
        IsAllowed BIT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.TabPermissions';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.TabPermissions';


/* ============================================================================
   3. SHIFT, PRÉSENCE, WORKFLOW DE DEMANDES
   ============================================================================ */

IF OBJECT_ID('dbo.ShiftEvents', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ShiftEvents (
        ShiftEventId INT IDENTITY PRIMARY KEY,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        /* LOGIN | PAUSE_START | PAUSE_END | LUNCH_START | LUNCH_END | SHIFT_END */
        EventType NVARCHAR(20) NOT NULL,
        OccurredAt DATETIME2 NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.ShiftEvents';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ShiftEvents';


IF OBJECT_ID('dbo.AttendanceRecords', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.AttendanceRecords (
        AttendanceId INT IDENTITY PRIMARY KEY,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        WorkDate DATE NOT NULL,
        /* 'present' | 'absent' */
        Status NVARCHAR(20) NOT NULL,
        ArrivalTime TIME NULL,
        DepartureTime TIME NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_AttendanceRecords_User_Date UNIQUE (UserId, WorkDate)
    );
    PRINT 'Créée : dbo.AttendanceRecords';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.AttendanceRecords';


IF OBJECT_ID('dbo.WorkflowRequests', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.WorkflowRequests (
        RequestId INT IDENTITY PRIMARY KEY,
        /* LEAVE | PROCEDURE_CHANGE | ACCESS */
        Type NVARCHAR(30) NOT NULL,
        Title NVARCHAR(200) NOT NULL,
        Details NVARCHAR(2000) NULL,
        /* DAY | WEEK | MONTH — granularité choisie, informatif */
        PeriodType NVARCHAR(10) NOT NULL,
        PeriodFrom DATE NOT NULL,
        PeriodTo DATE NOT NULL,
        /* QA | ADMIN */
        AssignedTeam NVARCHAR(10) NOT NULL,
        RequestedByUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        /* PENDING | APPROVED | REJECTED */
        Status NVARCHAR(20) NOT NULL,
        DecidedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        DecisionComment NVARCHAR(500) NULL,
        DecidedAt DATETIME2 NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.WorkflowRequests';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.WorkflowRequests';


/* ============================================================================
   4. PROCÉDURES (fichiers partagés + parcours interactif)
   ============================================================================ */

IF OBJECT_ID('dbo.Procedures', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.Procedures (
        ProcedureId INT IDENTITY PRIMARY KEY,
        ZoneId INT NOT NULL REFERENCES dbo.ProcedureZones(ZoneId),
        Title NVARCHAR(200) NOT NULL,
        CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.Procedures';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.Procedures';


IF OBJECT_ID('dbo.ProcedureSteps', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ProcedureSteps (
        ProcedureStepId INT IDENTITY PRIMARY KEY,
        ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
        StepNumber INT NOT NULL,
        Content NVARCHAR(1000) NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.ProcedureSteps';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ProcedureSteps';


IF OBJECT_ID('dbo.ProcedureWorkflowNodes', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ProcedureWorkflowNodes (
        NodeId INT IDENTITY PRIMARY KEY,
        ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
        QuestionText NVARCHAR(500) NOT NULL,
        IsStart BIT NOT NULL,
        SuggestionLabel NVARCHAR(200) NULL,
        SuggestionUrl NVARCHAR(500) NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.ProcedureWorkflowNodes';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ProcedureWorkflowNodes';


IF OBJECT_ID('dbo.ProcedureWorkflowOptions', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ProcedureWorkflowOptions (
        OptionId INT IDENTITY PRIMARY KEY,
        NodeId INT NOT NULL REFERENCES dbo.ProcedureWorkflowNodes(NodeId),
        Label NVARCHAR(200) NOT NULL,
        NextNodeId INT NULL REFERENCES dbo.ProcedureWorkflowNodes(NodeId),
        /* FIDELISATION | CLOTURE — renseigné seulement si NextNodeId est vide */
        Outcome NVARCHAR(20) NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.ProcedureWorkflowOptions';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ProcedureWorkflowOptions';


IF OBJECT_ID('dbo.FavoriteProcedures', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.FavoriteProcedures (
        FavoriteId INT IDENTITY PRIMARY KEY,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_FavoriteProcedures_User_Procedure UNIQUE (UserId, ProcedureId)
    );
    PRINT 'Créée : dbo.FavoriteProcedures';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.FavoriteProcedures';


/* Pièce jointe générique — association polymorphe résolue côté service (EntityType/EntityId),
   pas de FK SQL vers la table cible car elle varie (Procedure, AgentDossier, MailTemplate...). */
IF OBJECT_ID('dbo.Attachments', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.Attachments (
        AttachmentId INT IDENTITY PRIMARY KEY,
        EntityType NVARCHAR(50) NOT NULL,
        EntityId INT NOT NULL,
        FileName NVARCHAR(260) NOT NULL,
        MimeType NVARCHAR(100) NULL,
        StorageUrl NVARCHAR(500) NOT NULL,
        UploadedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.Attachments';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.Attachments';


/* ============================================================================
   5. FORMATION
   ============================================================================ */

IF OBJECT_ID('dbo.Courses', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.Courses (
        CourseId INT IDENTITY PRIMARY KEY,
        Title NVARCHAR(200) NOT NULL,
        Description NVARCHAR(1000) NULL,
        Content NVARCHAR(4000) NULL,
        /* STANDARD | SELF_ASSESSMENT */
        Type NVARCHAR(20) NOT NULL,
        Mandatory BIT NOT NULL,
        VideoUrl NVARCHAR(500) NULL,
        CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.Courses';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.Courses';


IF OBJECT_ID('dbo.CourseQuestions', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.CourseQuestions (
        QuestionId INT IDENTITY PRIMARY KEY,
        CourseId INT NOT NULL REFERENCES dbo.Courses(CourseId),
        QuestionText NVARCHAR(1000) NOT NULL,
        QuestionNumber INT NOT NULL,
        OptionsJson NVARCHAR(2000) NULL,
        CorrectOptionIndex INT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.CourseQuestions';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.CourseQuestions';


IF OBJECT_ID('dbo.CourseAttempts', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.CourseAttempts (
        AttemptId INT IDENTITY PRIMARY KEY,
        CourseId INT NOT NULL REFERENCES dbo.Courses(CourseId),
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        /* TODO | IN_PROGRESS | DONE */
        Status NVARCHAR(20) NOT NULL,
        Score INT NULL,
        AnswersJson NVARCHAR(2000) NULL,
        CompletedAt DATETIME2 NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_CourseAttempts_Course_User UNIQUE (CourseId, UserId)
    );
    PRINT 'Créée : dbo.CourseAttempts';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.CourseAttempts';


/* ============================================================================
   6. QUALITÉ (CLAIRAUDIO)
   ============================================================================ */

IF OBJECT_ID('dbo.QualityCriterionAttributes', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.QualityCriterionAttributes (
        AttributeId INT IDENTITY PRIMARY KEY,
        CriterionId INT NOT NULL REFERENCES dbo.QualityCriteria(CriterionId),
        Label NVARCHAR(300) NOT NULL,
        SortOrder INT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.QualityCriterionAttributes';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.QualityCriterionAttributes';


IF OBJECT_ID('dbo.QualityEvaluations', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.QualityEvaluations (
        EvaluationId INT IDENTITY PRIMARY KEY,
        AgentUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        EvaluatorUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        EvaluationDate DATE NOT NULL,
        CallDate DATE NOT NULL,
        RecordingRef NVARCHAR(50) NULL,
        DurationMinutes INT NULL,
        MotifId INT NULL REFERENCES dbo.QualityMotifs(MotifId),
        Strengths NVARCHAR(500) NULL,
        Improvements NVARCHAR(500) NULL,
        Comment NVARCHAR(1000) NULL,
        /* pending | done */
        FeedbackStatus NVARCHAR(20) NOT NULL,
        FeedbackDate DATE NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.QualityEvaluations';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.QualityEvaluations';


IF OBJECT_ID('dbo.QualityEvaluationScores', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.QualityEvaluationScores (
        EvaluationScoreId INT IDENTITY PRIMARY KEY,
        EvaluationId INT NOT NULL REFERENCES dbo.QualityEvaluations(EvaluationId),
        CriterionId INT NOT NULL REFERENCES dbo.QualityCriteria(CriterionId),
        ScoreValue SMALLINT NULL,
        IsNotApplicable BIT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_QualityEvaluationScores_Evaluation_Criterion UNIQUE (EvaluationId, CriterionId)
    );
    PRINT 'Créée : dbo.QualityEvaluationScores';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.QualityEvaluationScores';


IF OBJECT_ID('dbo.CoachingPlans', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.CoachingPlans (
        CoachingPlanId INT IDENTITY PRIMARY KEY,
        AgentUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        Axis NVARCHAR(300) NOT NULL,
        DueDate DATE NOT NULL,
        /* todo | in_progress | done */
        Status NVARCHAR(20) NOT NULL,
        Note NVARCHAR(1000) NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.CoachingPlans';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.CoachingPlans';


IF OBJECT_ID('dbo.AgentDossiers', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.AgentDossiers (
        DossierId INT IDENTITY PRIMARY KEY,
        LinkedUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        Source NVARCHAR(100) NOT NULL,
        /* JSON — colonnes variables selon la source (import CSV, capture IA). Peut
           contenir des données client bancaires : accès à restreindre par rôle. */
        Payload NVARCHAR(MAX) NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.AgentDossiers';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.AgentDossiers';


IF OBJECT_ID('dbo.KpiEvents', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.KpiEvents (
        KpiEventId INT IDENTITY PRIMARY KEY,
        UserId BIGINT NULL REFERENCES dbo.USERS(ID),
        EventType NVARCHAR(50) NOT NULL,
        EventKey NVARCHAR(150) NULL,
        OccurredAt DATETIME2 NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.KpiEvents';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.KpiEvents';


IF OBJECT_ID('dbo.ManualKpiEntries', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ManualKpiEntries (
        ManualKpiEntryId INT IDENTITY PRIMARY KEY,
        SubjectUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        EnteredByUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        /* ex. CAS_CREES, MAUVAIS_ENREGISTREMENT, TAUX_ATTEINTE_OBJECTIF... */
        MetricCode NVARCHAR(50) NOT NULL,
        MetricValue DECIMAL(18,4) NOT NULL,
        PeriodDate DATE NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.ManualKpiEntries';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ManualKpiEntries';


/* ============================================================================
   7. MASQUES DE MAIL
   ============================================================================ */

IF OBJECT_ID('dbo.MailTemplates', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.MailTemplates (
        TemplateId INT IDENTITY PRIMARY KEY,
        CategoryId INT NOT NULL REFERENCES dbo.MailTemplateCategories(CategoryId),
        Subject NVARCHAR(300) NOT NULL,
        Body NVARCHAR(MAX) NOT NULL,
        /* person | service */
        RecipientType NVARCHAR(20) NOT NULL,
        RecipientGroupId INT NULL REFERENCES dbo.MailRecipientGroups(GroupId),
        CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        IsSystemTemplate BIT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.MailTemplates';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.MailTemplates';


/* ============================================================================
   8. KNOWLEDGE BASE
   ============================================================================ */

IF OBJECT_ID('dbo.KnowledgeArticles', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.KnowledgeArticles (
        ArticleId INT IDENTITY PRIMARY KEY,
        CategoryId INT NOT NULL REFERENCES dbo.KnowledgeCategories(CategoryId),
        /* null = contenu valable pour toutes les filiales ; renseigné = spécifique à un pays. */
        CountryCode NVARCHAR(2) NULL REFERENCES dbo.KnowledgeCountries(CountryCode),
        Title NVARCHAR(200) NOT NULL,
        ContentHtml NVARCHAR(MAX) NOT NULL,
        Tags NVARCHAR(300) NULL,
        SortOrder INT NOT NULL,
        CreatedByUserId INT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.KnowledgeArticles';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.KnowledgeArticles';


/* ============================================================================
   9. MESSAGERIE INTERNE
   ============================================================================ */

IF OBJECT_ID('dbo.Conversations', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.Conversations (
        ConversationId INT IDENTITY PRIMARY KEY,
        /* DM (exactement 2 participants) ou GROUP */
        Type NVARCHAR(10) NOT NULL,
        Name NVARCHAR(150) NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.Conversations';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.Conversations';


IF OBJECT_ID('dbo.ConversationParticipants', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ConversationParticipants (
        ParticipantId INT IDENTITY PRIMARY KEY,
        ConversationId INT NOT NULL REFERENCES dbo.Conversations(ConversationId),
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        LastReadAt DATETIME2 NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_ConversationParticipants_Conv_User UNIQUE (ConversationId, UserId)
    );
    PRINT 'Créée : dbo.ConversationParticipants';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ConversationParticipants';


IF OBJECT_ID('dbo.ChatMessages', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.ChatMessages (
        MessageId INT IDENTITY PRIMARY KEY,
        ConversationId INT NOT NULL REFERENCES dbo.Conversations(ConversationId),
        SenderUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        Content NVARCHAR(MAX) NOT NULL,
        SentAt DATETIME2 NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.ChatMessages';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.ChatMessages';


/* ============================================================================
   10. MON RCC (réseau social interne)
   ============================================================================ */

IF OBJECT_ID('dbo.RccPosts', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.RccPosts (
        PostId INT IDENTITY PRIMARY KEY,
        /* null si l'auteur est un compte de service. */
        AuthorUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        AuthorLabel NVARCHAR(150) NOT NULL,
        Content NVARCHAR(MAX) NOT NULL,
        ImageUrl NVARCHAR(500) NULL,
        ViewCount INT NOT NULL DEFAULT 0,
        PublishedAt DATETIME2 NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.RccPosts';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.RccPosts';


IF OBJECT_ID('dbo.RccPostComments', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.RccPostComments (
        CommentId INT IDENTITY PRIMARY KEY,
        PostId INT NOT NULL REFERENCES dbo.RccPosts(PostId),
        AuthorUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        AuthorLabel NVARCHAR(150) NOT NULL,
        Content NVARCHAR(1000) NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.RccPostComments';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.RccPostComments';


IF OBJECT_ID('dbo.RccPostLikes', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.RccPostLikes (
        RccPostLikeId INT IDENTITY PRIMARY KEY,
        PostId INT NOT NULL REFERENCES dbo.RccPosts(PostId),
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_RccPostLikes_Post_User UNIQUE (PostId, UserId)
    );
    PRINT 'Créée : dbo.RccPostLikes';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.RccPostLikes';


IF OBJECT_ID('dbo.RccStories', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.RccStories (
        StoryId INT IDENTITY PRIMARY KEY,
        AuthorLabel NVARCHAR(150) NOT NULL,
        Content NVARCHAR(500) NULL,
        ImageUrl NVARCHAR(500) NULL,
        PublishedAt DATETIME2 NOT NULL,
        ExpiresAt DATETIME2 NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.RccStories';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.RccStories';


IF OBJECT_ID('dbo.RccCommunityFollows', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.RccCommunityFollows (
        RccCommunityFollowId INT IDENTITY PRIMARY KEY,
        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
        CommunityKey NVARCHAR(50) NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_RccCommunityFollows_User_Community UNIQUE (UserId, CommunityKey)
    );
    PRINT 'Créée : dbo.RccCommunityFollows';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.RccCommunityFollows';


IF OBJECT_ID('dbo.RccNotifications', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.RccNotifications (
        NotificationId INT IDENTITY PRIMARY KEY,
        /* null = notification globale (visible par tous). */
        TargetUserId BIGINT NULL REFERENCES dbo.USERS(ID),
        Content NVARCHAR(500) NOT NULL,
        IsRead BIT NOT NULL,
        CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );
    PRINT 'Créée : dbo.RccNotifications';
END
ELSE
    PRINT 'Déjà présente, ignorée : dbo.RccNotifications';


/* ============================================================================
   FIN DU SCHÉMA — 48 tables.
   ============================================================================ */
