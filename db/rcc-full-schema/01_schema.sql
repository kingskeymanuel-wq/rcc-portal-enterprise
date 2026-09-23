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
   1. RÉFÉRENTIELS SANS DÉPENDANCE
   ============================================================================ */

CREATE TABLE dbo.ROLES (
    ID INT IDENTITY PRIMARY KEY,
    NAME NVARCHAR(255) NOT NULL,
    DESCRIPTION NVARCHAR(255) NULL
);

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

CREATE TABLE dbo.ProcedureZones (
    ZoneId INT IDENTITY PRIMARY KEY,
    Code NVARCHAR(30) NOT NULL UNIQUE,
    Label NVARCHAR(100) NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

CREATE TABLE dbo.MailRecipientGroups (
    GroupId INT IDENTITY PRIMARY KEY,
    Label NVARCHAR(150) NOT NULL,
    Email NVARCHAR(254) NOT NULL UNIQUE,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

CREATE TABLE dbo.QualityMotifs (
    MotifId INT IDENTITY PRIMARY KEY,
    Label NVARCHAR(150) NOT NULL,
    IsActive BIT NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.KnowledgeCategories (
    CategoryId INT IDENTITY PRIMARY KEY,
    Code NVARCHAR(50) NOT NULL UNIQUE,
    Title NVARCHAR(100) NOT NULL,
    Icon NVARCHAR(50) NULL,
    SortOrder INT NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

/* Store clé/valeur générique — reçoit tout ce qui n'a pas encore sa propre table. */
CREATE TABLE dbo.KvEntries (
    Scope NVARCHAR(100) NOT NULL,
    [Key] NVARCHAR(200) NOT NULL,
    Value NVARCHAR(MAX) NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_KvEntries PRIMARY KEY (Scope, [Key])
);

/* ============================================================================
   2. UTILISATEURS ET ACCÈS
   ============================================================================ */

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
    GENDER NVARCHAR(1) NULL,
    CONTRACT_TYPE NVARCHAR(30) NULL,
    CONTRACT_STATUS NVARCHAR(10) NULL,
    CONTRACT_START_DATE DATE NULL,
    ACTIVITY NVARCHAR(100) NULL,
    /* Ne jamais stocker le vrai mot de passe AD ici — conservé pour compatibilité de schéma uniquement. */
    PASSWORD NVARCHAR(255) NULL,
    CREATED_AT DATETIMEOFFSET NULL,
    MODIFIED_AT DATETIMEOFFSET NULL
);

CREATE TABLE dbo.USER_ROLES (
    ID BIGINT IDENTITY PRIMARY KEY,
    USERS_ID BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    ROLES_ID INT NOT NULL REFERENCES dbo.ROLES(ID)
);

CREATE TABLE dbo.USER_SERVICES (
    ID BIGINT IDENTITY PRIMARY KEY,
    USER_ID BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    SERVICE_ID INT NOT NULL REFERENCES dbo.SERVICES(ID)
);

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

CREATE TABLE dbo.RefreshTokens (
    RefreshTokenId INT IDENTITY PRIMARY KEY,
    Jti UNIQUEIDENTIFIER NOT NULL UNIQUE,
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    ExpiresAt DATETIME2 NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

CREATE TABLE dbo.TabPermissions (
    TabPermissionId INT IDENTITY PRIMARY KEY,
    TeamId INT NULL REFERENCES dbo.Teams(TeamId),
    RoleId INT NULL REFERENCES dbo.ROLES(ID),
    TabCode NVARCHAR(50) NOT NULL,
    IsAllowed BIT NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

/* Dérogation explicite par utilisateur — prime sur TabPermissions/le rôle quand elle existe. */
CREATE TABLE dbo.UserFeaturePermissions (
    UserFeaturePermissionId INT IDENTITY PRIMARY KEY,
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    FeatureCode NVARCHAR(50) NOT NULL,
    IsAllowed BIT NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_UserFeaturePermissions_User_Feature UNIQUE (UserId, FeatureCode)
);

/* ============================================================================
   3. SHIFT, PRÉSENCE, WORKFLOW DE DEMANDES
   ============================================================================ */

CREATE TABLE dbo.ShiftEvents (
    ShiftEventId INT IDENTITY PRIMARY KEY,
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    /* LOGIN | PAUSE_START | PAUSE_END | LUNCH_START | LUNCH_END | SHIFT_END */
    EventType NVARCHAR(20) NOT NULL,
    OccurredAt DATETIME2 NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

/* Planning prévisionnel par agent — heures de prise de poste variables selon l'agent. */
CREATE TABLE dbo.AgentSchedules (
    ScheduleId INT IDENTITY PRIMARY KEY,
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    WorkDate DATE NOT NULL,
    PlannedStartTime TIME NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_AgentSchedules_User_Date UNIQUE (UserId, WorkDate)
);

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
    AssignedToUserId BIGINT NULL REFERENCES dbo.USERS(ID),
    RequestedByUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    /* PENDING | APPROVED | REJECTED */
    Status NVARCHAR(20) NOT NULL,
    DecidedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
    DecisionComment NVARCHAR(500) NULL,
    DecidedAt DATETIME2 NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

/* ============================================================================
   4. PROCÉDURES (fichiers partagés + parcours interactif)
   ============================================================================ */

CREATE TABLE dbo.Procedures (
    ProcedureId INT IDENTITY PRIMARY KEY,
    ZoneId INT NOT NULL REFERENCES dbo.ProcedureZones(ZoneId),
    /* Service/équipe concerné — null = procédure générique, visible par toutes les équipes. */
    ServiceId INT NULL REFERENCES dbo.SERVICES(ID),
    Title NVARCHAR(200) NOT NULL,
    CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.ProcedureSteps (
    ProcedureStepId INT IDENTITY PRIMARY KEY,
    ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
    StepNumber INT NOT NULL,
    Content NVARCHAR(1000) NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

CREATE TABLE dbo.FavoriteProcedures (
    FavoriteId INT IDENTITY PRIMARY KEY,
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_FavoriteProcedures_User_Procedure UNIQUE (UserId, ProcedureId)
);

/* Pièce jointe générique — association polymorphe résolue côté service (EntityType/EntityId),
   pas de FK SQL vers la table cible car elle varie (Procedure, AgentDossier, MailTemplate...). */
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

/* ============================================================================
   5. FORMATION
   ============================================================================ */

CREATE TABLE dbo.Courses (
    CourseId INT IDENTITY PRIMARY KEY,
    Title NVARCHAR(200) NOT NULL,
    Description NVARCHAR(1000) NULL,
    Content NVARCHAR(4000) NULL,
    /* STANDARD | SELF_ASSESSMENT */
    Type NVARCHAR(20) NOT NULL,
    Mandatory BIT NOT NULL,
    VideoUrl NVARCHAR(500) NULL,
    /* Service/équipe ciblé — null = cours générique, assigné à tout le monde. */
    ServiceId INT NULL REFERENCES dbo.SERVICES(ID),
    CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

CREATE TABLE dbo.CourseAttempts (
    AttemptId INT IDENTITY PRIMARY KEY,
    CourseId INT NOT NULL REFERENCES dbo.Courses(CourseId),
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    /* TODO | IN_PROGRESS | DONE */
    Status NVARCHAR(20) NOT NULL,
    Score INT NULL,
    AnswersJson NVARCHAR(2000) NULL,
    CompletedAt DATETIME2 NULL,
    /* 1 = première tentative, 2 = rattrapage (maximum). */
    AttemptNumber INT NOT NULL DEFAULT 1,
    /* Une fois vrai, plus aucune nouvelle tentative n'est possible. */
    Finalized BIT NOT NULL DEFAULT 0,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_CourseAttempts_Course_User UNIQUE (CourseId, UserId)
);

/* ============================================================================
   6. QUALITÉ (CLAIRAUDIO)
   ============================================================================ */

CREATE TABLE dbo.QualityCriterionAttributes (
    AttributeId INT IDENTITY PRIMARY KEY,
    CriterionId INT NOT NULL REFERENCES dbo.QualityCriteria(CriterionId),
    Label NVARCHAR(300) NOT NULL,
    SortOrder INT NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

CREATE TABLE dbo.KpiEvents (
    KpiEventId INT IDENTITY PRIMARY KEY,
    UserId BIGINT NULL REFERENCES dbo.USERS(ID),
    EventType NVARCHAR(50) NOT NULL,
    EventKey NVARCHAR(150) NULL,
    OccurredAt DATETIME2 NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

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

/* ============================================================================
   7. MASQUES DE MAIL
   ============================================================================ */

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

/* ============================================================================
   8. KNOWLEDGE BASE
   ============================================================================ */

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

/* ============================================================================
   9. MESSAGERIE INTERNE
   ============================================================================ */

CREATE TABLE dbo.Conversations (
    ConversationId INT IDENTITY PRIMARY KEY,
    /* DM (exactement 2 participants) ou GROUP */
    Type NVARCHAR(10) NOT NULL,
    Name NVARCHAR(150) NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.ConversationParticipants (
    ParticipantId INT IDENTITY PRIMARY KEY,
    ConversationId INT NOT NULL REFERENCES dbo.Conversations(ConversationId),
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    LastReadAt DATETIME2 NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_ConversationParticipants_Conv_User UNIQUE (ConversationId, UserId)
);

CREATE TABLE dbo.ChatMessages (
    MessageId INT IDENTITY PRIMARY KEY,
    ConversationId INT NOT NULL REFERENCES dbo.Conversations(ConversationId),
    SenderUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    Content NVARCHAR(MAX) NOT NULL,
    SentAt DATETIME2 NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

/* ============================================================================
   10. MON RCC (réseau social interne)
   ============================================================================ */

CREATE TABLE dbo.RccPosts (
    PostId INT IDENTITY PRIMARY KEY,
    /* null si l'auteur est un compte de service. */
    AuthorUserId BIGINT NULL REFERENCES dbo.USERS(ID),
    AuthorLabel NVARCHAR(150) NOT NULL,
    Content NVARCHAR(MAX) NOT NULL,
    ImageUrl NVARCHAR(500) NULL,
    ViewCount INT NOT NULL DEFAULT 0,
    PublishedAt DATETIME2 NOT NULL,
    /* Fin de visibilité dans le fil principal — après, bascule en historique. Suppression définitive 2 mois après PublishedAt. */
    ExpiresAt DATETIME2 NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.RccPostComments (
    CommentId INT IDENTITY PRIMARY KEY,
    PostId INT NOT NULL REFERENCES dbo.RccPosts(PostId),
    AuthorUserId BIGINT NULL REFERENCES dbo.USERS(ID),
    AuthorLabel NVARCHAR(150) NOT NULL,
    Content NVARCHAR(1000) NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.RccPostLikes (
    RccPostLikeId INT IDENTITY PRIMARY KEY,
    PostId INT NOT NULL REFERENCES dbo.RccPosts(PostId),
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_RccPostLikes_Post_User UNIQUE (PostId, UserId)
);

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

CREATE TABLE dbo.RccCommunityFollows (
    RccCommunityFollowId INT IDENTITY PRIMARY KEY,
    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
    CommunityKey NVARCHAR(50) NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_RccCommunityFollows_User_Community UNIQUE (UserId, CommunityKey)
);

CREATE TABLE dbo.RccNotifications (
    NotificationId INT IDENTITY PRIMARY KEY,
    /* null = notification globale (visible par tous). */
    TargetUserId BIGINT NULL REFERENCES dbo.USERS(ID),
    Content NVARCHAR(500) NOT NULL,
    IsRead BIT NOT NULL,
    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);

/* ============================================================================
   FIN DU SCHÉMA — 48 tables.
   ============================================================================ */
