package com.ecobank.rccportal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * ⚠️ Crée, si elles n'existent pas encore, les tables nécessaires aux modules
 * Procédures + parcours interactif + Formation + Suivi de shift — le temps de
 * ne pas dépendre d'un accès direct à SQL Server / d'un DBA. Idempotent
 * (vérifie sys.tables avant chaque création), sûr à relancer à chaque démarrage.
 *
 * À terme, une fois l'accès DBA disponible, ce composant devrait être retiré et
 * remplacé par un vrai script SQL versionné.
 */
@Slf4j
@Component
@Order(1)
public class WorkflowSchemaBootstrap implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowSchemaBootstrap(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        createIfMissing("ProcedureZones", """
                CREATE TABLE dbo.ProcedureZones (
                    ZoneId INT IDENTITY PRIMARY KEY,
                    Code NVARCHAR(20) NOT NULL,
                    Label NVARCHAR(100) NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_ProcedureZones_Code UNIQUE (Code)
                )
                """);

        createIfMissing("Procedures", """
                CREATE TABLE dbo.Procedures (
                    ProcedureId INT IDENTITY PRIMARY KEY,
                    ZoneId INT NOT NULL,
                    Title NVARCHAR(200) NOT NULL,
                    CreatedByUserId INT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT FK_Procedures_Zone FOREIGN KEY (ZoneId) REFERENCES dbo.ProcedureZones(ZoneId)
                )
                """);

        createIfMissing("ProcedureSteps", """
                CREATE TABLE dbo.ProcedureSteps (
                    ProcedureStepId INT IDENTITY PRIMARY KEY,
                    ProcedureId INT NOT NULL,
                    StepNumber INT NOT NULL,
                    Content NVARCHAR(1000) NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT FK_ProcedureSteps_Procedure FOREIGN KEY (ProcedureId) REFERENCES dbo.Procedures(ProcedureId)
                )
                """);

        createIfMissing("ProcedureWorkflowNodes", """
                CREATE TABLE dbo.ProcedureWorkflowNodes (
                    NodeId INT IDENTITY PRIMARY KEY,
                    ProcedureId INT NOT NULL,
                    QuestionText NVARCHAR(500) NOT NULL,
                    IsStart BIT NOT NULL,
                    SuggestionLabel NVARCHAR(200) NULL,
                    SuggestionUrl NVARCHAR(500) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT FK_ProcedureWorkflowNodes_Procedure
                        FOREIGN KEY (ProcedureId) REFERENCES dbo.Procedures(ProcedureId)
                )
                """);

        createIfMissing("ProcedureWorkflowOptions", """
                CREATE TABLE dbo.ProcedureWorkflowOptions (
                    OptionId INT IDENTITY PRIMARY KEY,
                    NodeId INT NOT NULL,
                    Label NVARCHAR(200) NOT NULL,
                    NextNodeId INT NULL,
                    Outcome NVARCHAR(20) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT FK_ProcedureWorkflowOptions_Node
                        FOREIGN KEY (NodeId) REFERENCES dbo.ProcedureWorkflowNodes(NodeId),
                    CONSTRAINT FK_ProcedureWorkflowOptions_NextNode
                        FOREIGN KEY (NextNodeId) REFERENCES dbo.ProcedureWorkflowNodes(NodeId)
                )
                """);

        createIfMissing("Courses", """
                CREATE TABLE dbo.Courses (
                    CourseId INT IDENTITY PRIMARY KEY,
                    Title NVARCHAR(200) NOT NULL,
                    Description NVARCHAR(1000) NULL,
                    Content NVARCHAR(MAX) NULL,
                    Type NVARCHAR(20) NOT NULL,
                    Mandatory BIT NOT NULL,
                    VideoUrl NVARCHAR(500) NULL,
                    ServiceId INT NULL,
                    CreatedByUserId INT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("CourseQuestions", """
                CREATE TABLE dbo.CourseQuestions (
                    QuestionId INT IDENTITY PRIMARY KEY,
                    CourseId INT NOT NULL,
                    QuestionText NVARCHAR(1000) NOT NULL,
                    QuestionNumber INT NOT NULL,
                    OptionsJson NVARCHAR(2000) NULL,
                    CorrectOptionIndex INT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT FK_CourseQuestions_Course FOREIGN KEY (CourseId) REFERENCES dbo.Courses(CourseId)
                )
                """);

        createIfMissing("CourseAttempts", """
                CREATE TABLE dbo.CourseAttempts (
                    AttemptId INT IDENTITY PRIMARY KEY,
                    CourseId INT NOT NULL,
                    UserId INT NOT NULL,
                    Status NVARCHAR(20) NOT NULL,
                    Score INT NULL,
                    AnswersJson NVARCHAR(2000) NULL,
                    CompletedAt DATETIME2 NULL,
                    AttemptNumber INT NOT NULL DEFAULT 1,
                    Finalized BIT NOT NULL DEFAULT 0,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT FK_CourseAttempts_Course FOREIGN KEY (CourseId) REFERENCES dbo.Courses(CourseId),
                    CONSTRAINT UQ_CourseAttempts_Course_User UNIQUE (CourseId, UserId)
                )
                """);

        createIfMissing("ShiftEvents", """
                CREATE TABLE dbo.ShiftEvents (
                    ShiftEventId INT IDENTITY PRIMARY KEY,
                    UserId INT NOT NULL,
                    EventType NVARCHAR(20) NOT NULL,
                    OccurredAt DATETIME2 NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("WorkflowRequests", """
                CREATE TABLE dbo.WorkflowRequests (
                    RequestId INT IDENTITY PRIMARY KEY,
                    Type NVARCHAR(30) NOT NULL,
                    Title NVARCHAR(200) NOT NULL,
                    Details NVARCHAR(2000) NULL,
                    PeriodType NVARCHAR(10) NOT NULL,
                    PeriodFrom DATE NOT NULL,
                    PeriodTo DATE NOT NULL,
                    AssignedTeam NVARCHAR(30) NOT NULL,
                    AssignedToUserId BIGINT NULL,
                    RequestedByUserId INT NOT NULL,
                    Status NVARCHAR(20) NOT NULL,
                    DecidedByUserId INT NULL,
                    DecisionComment NVARCHAR(500) NULL,
                    DecidedAt DATETIME2 NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("QualityCriteria", """
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
                )
                """);
        addColumnIfMissing("QualityCriteria", "Channel", "ALTER TABLE dbo.QualityCriteria ADD Channel NVARCHAR(10) NULL");

        createIfMissing("QualityMotifs", """
                CREATE TABLE dbo.QualityMotifs (
                    MotifId INT IDENTITY PRIMARY KEY,
                    Label NVARCHAR(150) NOT NULL,
                    IsActive BIT NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("QualityEvaluations", """
                CREATE TABLE dbo.QualityEvaluations (
                    EvaluationId INT IDENTITY PRIMARY KEY,
                    AgentUserId INT NOT NULL,
                    EvaluatorUserId INT NULL,
                    EvaluationDate DATE NOT NULL,
                    CallDate DATE NOT NULL,
                    RecordingRef NVARCHAR(50) NULL,
                    DurationMinutes INT NULL,
                    MotifId INT NULL,
                    Strengths NVARCHAR(500) NULL,
                    Improvements NVARCHAR(500) NULL,
                    Comment NVARCHAR(1000) NULL,
                    FeedbackStatus NVARCHAR(20) NOT NULL,
                    FeedbackDate DATE NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        addColumnIfMissing("QualityEvaluations", "Channel", "ALTER TABLE dbo.QualityEvaluations ADD Channel NVARCHAR(10) NOT NULL DEFAULT 'VOICE'");

        createIfMissing("QualityEvaluationScores", """
                CREATE TABLE dbo.QualityEvaluationScores (
                    EvaluationScoreId INT IDENTITY PRIMARY KEY,
                    EvaluationId INT NOT NULL,
                    CriterionId INT NOT NULL,
                    ScoreValue SMALLINT NULL,
                    IsNotApplicable BIT NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_QualityEvaluationScores_Evaluation_Criterion UNIQUE (EvaluationId, CriterionId)
                )
                """);

        createIfMissing("SlaRules", """
                CREATE TABLE dbo.SlaRules (
                    SlaRuleId INT IDENTITY PRIMARY KEY,
                    Motif NVARCHAR(200) NOT NULL,
                    Category NVARCHAR(100) NOT NULL,
                    Level NVARCHAR(10) NULL,
                    SlaHours INT NOT NULL,
                    SlaLabel NVARCHAR(160) NOT NULL,
                    DestinationService NVARCHAR(100) NULL,
                    Priority NVARCHAR(20) NULL,
                    AutoEscalation BIT NOT NULL DEFAULT 0,
                    Notes NVARCHAR(500) NULL,
                    IsActive BIT NOT NULL DEFAULT 1,
                    SortOrder INT NOT NULL DEFAULT 0,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        // Élargi pour porter les libellés conditionnels plus longs des fiches pôle
        // (ex. "Instantanée si reçue dans l'agence de domiciliation et 72H si transmise
        // dans une autre agence", 93 caractères) — voir RccPoleSeedBootstrap.
        widenColumnIfTooNarrow("SlaRules", "SlaLabel", 160,
                "ALTER TABLE dbo.SlaRules ALTER COLUMN SlaLabel NVARCHAR(160) NOT NULL");

        // Fiches pôle RCC (Inbound, Outbound, Résolution, Opérations, Business, Agences) —
        // manager/contact + rattachement des lignes SlaRules à leur pôle d'activité.
        createIfMissing("RccPoles", """
                CREATE TABLE dbo.RccPoles (
                    PoleId INT IDENTITY PRIMARY KEY,
                    Name NVARCHAR(150) NOT NULL,
                    ManagerUserId BIGINT NULL,
                    ContactPhone NVARCHAR(40) NULL,
                    ContactEmail NVARCHAR(150) NULL,
                    TeamContactLabel NVARCHAR(100) NULL,
                    WhoWeAre NVARCHAR(1000) NULL,
                    WhatWeDo NVARCHAR(1000) NULL,
                    IsActive BIT NOT NULL DEFAULT 1,
                    SortOrder INT NOT NULL DEFAULT 0,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        addColumnIfMissing("SlaRules", "PoleId", "ALTER TABLE dbo.SlaRules ADD PoleId INT NULL");

        // Carte des agences (BankBranch / bank-map.js) — jusqu'ici créée UNIQUEMENT par
        // db/migrations/012_add_bank_branches.sql, jamais exécutée sur les bases existantes :
        // BankBranchSeedBootstrap échouait alors en silence ("table manquante ?") et
        // /api/bank-branches répondait 500 → cartes vides. Même définition que la migration 012.
        createIfMissing("BankBranches", """
                CREATE TABLE dbo.BankBranches (
                    BranchId BIGINT IDENTITY(1,1) PRIMARY KEY,
                    CountryCode NVARCHAR(2) NOT NULL,
                    City NVARCHAR(100) NOT NULL,
                    Name NVARCHAR(200) NOT NULL,
                    Address NVARCHAR(500) NULL,
                    Latitude FLOAT NULL,
                    Longitude FLOAT NULL,
                    Phone NVARCHAR(50) NULL,
                    Email NVARCHAR(150) NULL,
                    OpeningHours NVARCHAR(200) NULL,
                    ManagerName NVARCHAR(150) NULL,
                    BranchType NVARCHAR(50) NULL,
                    IsActive BIT NOT NULL DEFAULT 1,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Disponibilité des cartes (onglet de la Base de connaissances) — cartes proposées par
        // filiale et leur disponibilité par ville, cochée par QA. Même définition que la migration 014.
        createIfMissing("CardProducts", """
                CREATE TABLE dbo.CardProducts (
                    CardProductId BIGINT IDENTITY(1,1) PRIMARY KEY,
                    CountryCode NVARCHAR(2) NOT NULL,
                    Name NVARCHAR(150) NOT NULL,
                    Category NVARCHAR(80) NULL,
                    Details NVARCHAR(1000) NULL,
                    SortOrder INT NOT NULL DEFAULT 0,
                    IsActive BIT NOT NULL DEFAULT 1,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        createIfMissing("CardAvailability", """
                CREATE TABLE dbo.CardAvailability (
                    CardAvailabilityId BIGINT IDENTITY(1,1) PRIMARY KEY,
                    CardProductId BIGINT NOT NULL,
                    City NVARCHAR(100) NOT NULL,
                    Available BIT NOT NULL DEFAULT 0,
                    Note NVARCHAR(500) NULL,
                    UpdatedBy NVARCHAR(150) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_CardAvailability_CardCity UNIQUE (CardProductId, City)
                )
                """);

        // Point de disponibilité des cartes par agence (tableau quotidien de la filiale).
        createIfMissing("CardAgencyStatus", """
                CREATE TABLE dbo.CardAgencyStatus (
                    CardAgencyStatusId BIGINT IDENTITY(1,1) PRIMARY KEY,
                    CountryCode NVARCHAR(2) NOT NULL,
                    ReportDate DATE NOT NULL,
                    Agency NVARCHAR(150) NOT NULL,
                    AgencyCode NVARCHAR(20) NULL,
                    CardStatus NVARCHAR(20) NOT NULL,
                    PinStatus NVARCHAR(20) NOT NULL,
                    CardTypes NVARCHAR(400) NULL,
                    Note NVARCHAR(500) NULL,
                    UpdatedBy NVARCHAR(150) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_CardAgencyStatus UNIQUE (CountryCode, ReportDate, Agency)
                )
                """);

        // Certificats de formation — demandés par l'agent (parcours terminé / évaluation réussie),
        // validés ou refusés par QA, vérifiables par leur numéro.
        createIfMissing("TrainingCertificates", """
                CREATE TABLE dbo.TrainingCertificates (
                    CertificateId BIGINT IDENTITY(1,1) PRIMARY KEY,
                    CertificateNumber NVARCHAR(40) NULL,
                    UserId BIGINT NOT NULL,
                    SourceType NVARCHAR(20) NOT NULL,
                    SourceId INT NOT NULL,
                    Title NVARCHAR(250) NOT NULL,
                    Score INT NULL,
                    Status NVARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    RequestedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    DecidedAt DATETIME2 NULL,
                    DecidedBy NVARCHAR(150) NULL,
                    DecisionNote NVARCHAR(500) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("RefreshTokens", """
                CREATE TABLE dbo.RefreshTokens (
                    RefreshTokenId INT IDENTITY PRIMARY KEY,
                    Jti UNIQUEIDENTIFIER NOT NULL UNIQUE,
                    UserId INT NOT NULL,
                    ExpiresAt DATETIME2 NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        addColumnIfMissing("RefreshTokens", "GatewayAccessToken", "ALTER TABLE dbo.RefreshTokens ADD GatewayAccessToken NVARCHAR(4000) NULL");

        createIfMissing("UserProfiles", """
                CREATE TABLE dbo.UserProfiles (
                    UserProfileId INT IDENTITY PRIMARY KEY,
                    UserId INT NOT NULL UNIQUE,
                    PhotoUrl NVARCHAR(500) NULL,
                    ChatBackgroundUrl NVARCHAR(500) NULL,
                    Birthdate DATE NULL,
                    Phone NVARCHAR(30) NULL,
                    Bio NVARCHAR(1000) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("RccNotifications", """
                CREATE TABLE dbo.RccNotifications (
                    NotificationId INT IDENTITY PRIMARY KEY,
                    TargetUserId INT NULL,
                    Content NVARCHAR(500) NOT NULL,
                    IsRead BIT NOT NULL,
                    ActionType NVARCHAR(50) NULL,
                    ActionTarget NVARCHAR(100) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("RccPosts", """
                CREATE TABLE dbo.RccPosts (
                    PostId INT IDENTITY PRIMARY KEY,
                    AuthorUserId INT NULL,
                    AuthorLabel NVARCHAR(150) NOT NULL,
                    Content NVARCHAR(MAX) NOT NULL,
                    ImageUrl NVARCHAR(500) NULL,
                    ViewCount INT NOT NULL DEFAULT 0,
                    PublishedAt DATETIME2 NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("RccPostComments", """
                CREATE TABLE dbo.RccPostComments (
                    CommentId INT IDENTITY PRIMARY KEY,
                    PostId INT NOT NULL,
                    AuthorUserId INT NULL,
                    AuthorLabel NVARCHAR(150) NOT NULL,
                    Content NVARCHAR(1000) NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("RccPostLikes", """
                CREATE TABLE dbo.RccPostLikes (
                    RccPostLikeId INT IDENTITY PRIMARY KEY,
                    PostId INT NOT NULL,
                    UserId INT NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_RccPostLikes_Post_User UNIQUE (PostId, UserId)
                )
                """);

        createIfMissing("RccStories", """
                CREATE TABLE dbo.RccStories (
                    StoryId INT IDENTITY PRIMARY KEY,
                    AuthorLabel NVARCHAR(150) NOT NULL,
                    Content NVARCHAR(500) NULL,
                    ImageUrl NVARCHAR(500) NULL,
                    PublishedAt DATETIME2 NOT NULL,
                    ExpiresAt DATETIME2 NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("RccCommunityFollows", """
                CREATE TABLE dbo.RccCommunityFollows (
                    RccCommunityFollowId INT IDENTITY PRIMARY KEY,
                    UserId INT NOT NULL,
                    CommunityKey NVARCHAR(50) NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_RccCommunityFollows_User_Community UNIQUE (UserId, CommunityKey)
                )
                """);

        createIfMissing("UserFeaturePermissions", """
                CREATE TABLE dbo.UserFeaturePermissions (
                    UserFeaturePermissionId INT IDENTITY PRIMARY KEY,
                    UserId BIGINT NOT NULL,
                    FeatureCode NVARCHAR(50) NOT NULL,
                    IsAllowed BIT NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_UserFeaturePermissions_User_Feature UNIQUE (UserId, FeatureCode)
                )
                """);

        addColumnIfMissing("Courses", "ServiceId", "ALTER TABLE dbo.Courses ADD ServiceId INT NULL");
        // L'assignation des cours par équipe se base désormais sur les équipes de l'onglet
        // Shift (dbo.Teams / User.Activity), pas sur RccService — ServiceId reste en base
        // pour compatibilité mais n'est plus utilisé par CourseService.filterVisible().
        addColumnIfMissing("Courses", "TeamId", "ALTER TABLE dbo.Courses ADD TeamId INT NULL");
        addColumnIfMissing("Procedures", "ServiceId", "ALTER TABLE dbo.Procedures ADD ServiceId INT NULL");
        addColumnIfMissing("CourseAttempts", "AttemptNumber", "ALTER TABLE dbo.CourseAttempts ADD AttemptNumber INT NOT NULL DEFAULT 1");
        addColumnIfMissing("CourseAttempts", "Finalized", "ALTER TABLE dbo.CourseAttempts ADD Finalized BIT NOT NULL DEFAULT 0");
        addColumnIfMissing("WorkflowRequests", "AssignedToUserId", "ALTER TABLE dbo.WorkflowRequests ADD AssignedToUserId BIGINT NULL");
        addColumnIfMissing("WorkflowRequests", "RelatedServiceId", "ALTER TABLE dbo.WorkflowRequests ADD RelatedServiceId INT NULL");
        // Demandes d'aide agent (accès/outils/matériel/difficulté) : urgence, prise en charge TL, escalade.
        addColumnIfMissing("WorkflowRequests", "Priority", "ALTER TABLE dbo.WorkflowRequests ADD Priority NVARCHAR(20) NULL");
        addColumnIfMissing("WorkflowRequests", "AcknowledgedAt", "ALTER TABLE dbo.WorkflowRequests ADD AcknowledgedAt DATETIME2 NULL");
        addColumnIfMissing("WorkflowRequests", "AcknowledgedBy", "ALTER TABLE dbo.WorkflowRequests ADD AcknowledgedBy NVARCHAR(150) NULL");
        addColumnIfMissing("WorkflowRequests", "AcknowledgementNote", "ALTER TABLE dbo.WorkflowRequests ADD AcknowledgementNote NVARCHAR(500) NULL");
        addColumnIfMissing("WorkflowRequests", "EscalatedAt", "ALTER TABLE dbo.WorkflowRequests ADD EscalatedAt DATETIME2 NULL");
        addColumnIfMissing("USERS", "GENDER", "ALTER TABLE dbo.USERS ADD GENDER NVARCHAR(1) NULL");
        addColumnIfMissing("USERS", "CONTRACT_TYPE", "ALTER TABLE dbo.USERS ADD CONTRACT_TYPE NVARCHAR(30) NULL");
        addColumnIfMissing("USERS", "CONTRACT_STATUS", "ALTER TABLE dbo.USERS ADD CONTRACT_STATUS NVARCHAR(10) NULL");
        addColumnIfMissing("USERS", "CONTRACT_START_DATE", "ALTER TABLE dbo.USERS ADD CONTRACT_START_DATE DATE NULL");
        addColumnIfMissing("USERS", "CONTRACT_END_DATE", "ALTER TABLE dbo.USERS ADD CONTRACT_END_DATE DATE NULL");
        addColumnIfMissing("USERS", "ACTIVITY", "ALTER TABLE dbo.USERS ADD ACTIVITY NVARCHAR(100) NULL");
        addColumnIfMissing("USERS", "RESIDENCE_PLACE", "ALTER TABLE dbo.USERS ADD RESIDENCE_PLACE NVARCHAR(200) NULL");
        addColumnIfMissing("USERS", "LED_TEAM", "ALTER TABLE dbo.USERS ADD LED_TEAM NVARCHAR(30) NULL");
        addColumnIfMissing("USERS", "TEAM_ASSIGNMENT_LOCKED", "ALTER TABLE dbo.USERS ADD TEAM_ASSIGNMENT_LOCKED BIT NOT NULL DEFAULT 0");
        // Les comptes de test/démo (agent.conseiller, agent.qa, agent.admin, it.admin, rh.test)
        // existaient avant cette fonctionnalité — ils sont exemptés explicitement à CHAQUE
        // démarrage, sans dépendre d'une condition qui peut cesser d'être vraie (l'ancien
        // mécanisme "tant que personne n'a soumis" s'arrêtait dès la première vraie soumission,
        // laissant potentiellement ces comptes bloqués pour toujours).
        int exempted = jdbcTemplate.update(
                "UPDATE dbo.USERS SET TEAM_ASSIGNMENT_LOCKED = 1 WHERE LOWER(USERNAME) IN " +
                "('agent.conseiller', 'agent.qa', 'agent.admin', 'it.admin', 'rh.test') AND TEAM_ASSIGNMENT_LOCKED = 0");
        if (exempted > 0) {
            log.warn("⚠ [SCHEMA BOOTSTRAP] {} compte(s) de test exempté(s) de la configuration initiale filiale/service/équipe.", exempted);
        }
        widenColumnIfTooNarrow("ProcedureZones", "Code", 30, "ALTER TABLE dbo.ProcedureZones ALTER COLUMN Code NVARCHAR(30) NOT NULL");
        widenColumnIfTooNarrow("ProcedureWorkflowNodes", "QuestionText", 1000, "ALTER TABLE dbo.ProcedureWorkflowNodes ALTER COLUMN QuestionText NVARCHAR(1000) NOT NULL");
        widenColumnIfTooNarrow("USERS", "CONTRACT_STATUS", 50, "ALTER TABLE dbo.USERS ALTER COLUMN CONTRACT_STATUS NVARCHAR(50) NULL");
        widenColumnIfTooNarrow("Courses", "Content", 100000, "ALTER TABLE dbo.Courses ALTER COLUMN Content NVARCHAR(MAX) NULL");
        // Référentiel RCC360 (RccMotifCatalog) : codes d'équipe jusqu'à 20 caractères (ex. "MOBILE_MONEY_SUPPORT"),
        // bien au-delà des 10 prévus initialement pour "QA"/"ADMIN" — élargi une fois pour toutes à 30.
        widenColumnIfTooNarrow("WorkflowRequests", "AssignedTeam", 30, "ALTER TABLE dbo.WorkflowRequests ALTER COLUMN AssignedTeam NVARCHAR(30) NOT NULL");
        widenColumnIfTooNarrow("RequestTemplates", "DefaultAssignedTeam", 30, "ALTER TABLE dbo.RequestTemplates ALTER COLUMN DefaultAssignedTeam VARCHAR(30) NULL");
        widenColumnIfTooNarrow("SlaTargets", "Team", 30, """
                IF EXISTS (SELECT 1 FROM sys.objects WHERE name = 'UQ_SlaTargets_Team_Type')
                    ALTER TABLE dbo.SlaTargets DROP CONSTRAINT UQ_SlaTargets_Team_Type;
                ALTER TABLE dbo.SlaTargets ALTER COLUMN Team NVARCHAR(30) NOT NULL;
                ALTER TABLE dbo.SlaTargets ADD CONSTRAINT UQ_SlaTargets_Team_Type UNIQUE (Team, Type);
                """);
        addColumnIfMissing("ChatMessages", "MediaUrl", "ALTER TABLE dbo.ChatMessages ADD MediaUrl NVARCHAR(500) NULL");
        addColumnIfMissing("ChatMessages", "ExpiresAt", "ALTER TABLE dbo.ChatMessages ADD ExpiresAt DATETIME2 NULL");
        addColumnIfMissing("Procedures", "CountryCode", "ALTER TABLE dbo.Procedures ADD CountryCode NVARCHAR(3) NULL");
        addColumnIfMissing("Procedures", "SlaDelay", "ALTER TABLE dbo.Procedures ADD SlaDelay NVARCHAR(100) NULL");
        addColumnIfMissing("Procedures", "Level", "ALTER TABLE dbo.Procedures ADD [Level] NVARCHAR(20) NULL");
        addColumnIfMissing("Procedures", "ResponsibleTeam", "ALTER TABLE dbo.Procedures ADD ResponsibleTeam NVARCHAR(200) NULL");
        addColumnIfMissing("ProcedureZones", "ImageUrl", "ALTER TABLE dbo.ProcedureZones ADD ImageUrl NVARCHAR(500) NULL");
        addColumnIfMissing("KnowledgeArticles", "ServiceId", "ALTER TABLE dbo.KnowledgeArticles ADD ServiceId INT NULL");
        addColumnIfMissing("UserProfiles", "ChatBackgroundUrl", "ALTER TABLE dbo.UserProfiles ADD ChatBackgroundUrl NVARCHAR(500) NULL");
        addColumnIfMissing("ManualKpiEntries", "ImportBatchId", "ALTER TABLE dbo.ManualKpiEntries ADD ImportBatchId NVARCHAR(40) NULL");
        addColumnIfMissing("USERS", "ROLE_DETAIL", "ALTER TABLE dbo.USERS ADD ROLE_DETAIL NVARCHAR(150) NULL");
        addColumnIfMissing("RccNotifications", "ActionType", "ALTER TABLE dbo.RccNotifications ADD ActionType NVARCHAR(50) NULL");
        addColumnIfMissing("RccNotifications", "ActionTarget", "ALTER TABLE dbo.RccNotifications ADD ActionTarget NVARCHAR(100) NULL");
        // Ciblage des notifications communes (« chaque portail sa notification ») + lecture individuelle.
        addColumnIfMissing("RccNotifications", "AudienceCountry", "ALTER TABLE dbo.RccNotifications ADD AudienceCountry NVARCHAR(3) NULL");
        addColumnIfMissing("RccNotifications", "AudienceRoles", "ALTER TABLE dbo.RccNotifications ADD AudienceRoles NVARCHAR(200) NULL");
        addColumnIfMissing("RccNotifications", "AudienceServiceCode", "ALTER TABLE dbo.RccNotifications ADD AudienceServiceCode NVARCHAR(50) NULL");
        createIfMissing("RccNotificationReads", """
                CREATE TABLE dbo.RccNotificationReads (
                    NotificationId INT NOT NULL,
                    UserId BIGINT NOT NULL,
                    ReadAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT PK_RccNotificationReads PRIMARY KEY (NotificationId, UserId)
                )
                """);

        createIfMissing("SiteSettings", """
                CREATE TABLE dbo.SiteSettings (
                    SettingKey NVARCHAR(100) NOT NULL PRIMARY KEY,
                    SettingValue NVARCHAR(500) NULL
                )
                """);

        createIfMissing("AssignedTasks", """
                CREATE TABLE dbo.AssignedTasks (
                    TaskId INT IDENTITY PRIMARY KEY,
                    Title NVARCHAR(200) NOT NULL,
                    Description NVARCHAR(2000) NULL,
                    AssignedToUserId INT NULL,
                    AssignedToTeamCode NVARCHAR(50) NULL,
                    CreatedByUserId INT NOT NULL,
                    DueDate DATE NULL,
                    Priority NVARCHAR(10) NOT NULL DEFAULT 'NORMAL',
                    Status NVARCHAR(20) NOT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        addColumnIfMissing("AssignedTasks", "Priority", "ALTER TABLE dbo.AssignedTasks ADD Priority NVARCHAR(10) NOT NULL DEFAULT 'NORMAL'");
        addColumnIfMissing("AssignedTasks", "Category", "ALTER TABLE dbo.AssignedTasks ADD Category NVARCHAR(30) NULL");
        addColumnIfMissing("AssignedTasks", "Justified", "ALTER TABLE dbo.AssignedTasks ADD Justified BIT NULL");
        addColumnIfMissing("AssignedTasks", "RelatedDate", "ALTER TABLE dbo.AssignedTasks ADD RelatedDate DATE NULL");

        boolean communitiesJustCreated = !tableExists("RccCommunities");
        createIfMissing("RccCommunities", """
                CREATE TABLE dbo.RccCommunities (
                    CommunityId INT IDENTITY PRIMARY KEY,
                    CommunityKey NVARCHAR(50) NOT NULL UNIQUE,
                    Label NVARCHAR(100) NOT NULL,
                    SortOrder INT NOT NULL DEFAULT 0,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        if (communitiesJustCreated) {
            jdbcTemplate.update("INSERT INTO dbo.RccCommunities (CommunityKey, Label, SortOrder) VALUES (?, ?, ?)", "INBOUND_VOICE", "Inbound Voice", 1);
            jdbcTemplate.update("INSERT INTO dbo.RccCommunities (CommunityKey, Label, SortOrder) VALUES (?, ?, ?)", "OUTBOUND_VOICE", "Outbound Voice", 2);
            jdbcTemplate.update("INSERT INTO dbo.RccCommunities (CommunityKey, Label, SortOrder) VALUES (?, ?, ?)", "RESEAUX_SOCIAUX_MAILS", "Réseaux Sociaux & Mails", 3);
            jdbcTemplate.update("INSERT INTO dbo.RccCommunities (CommunityKey, Label, SortOrder) VALUES (?, ?, ?)", "QUALITE_COACHING", "Qualité & Coaching", 4);
            jdbcTemplate.update("INSERT INTO dbo.RccCommunities (CommunityKey, Label, SortOrder) VALUES (?, ?, ?)", "SUPPORT_IT", "Support IT", 5);
        }
        addColumnIfMissing("RccPosts", "ExpiresAt", "ALTER TABLE dbo.RccPosts ADD ExpiresAt DATETIME2 NULL");
        addColumnIfMissing("KnowledgeCategories", "ImageUrl", "ALTER TABLE dbo.KnowledgeCategories ADD ImageUrl NVARCHAR(500) NULL");
        addColumnIfMissing("Courses", "ImageUrl", "ALTER TABLE dbo.Courses ADD ImageUrl NVARCHAR(500) NULL");
        addColumnIfMissing("Courses", "Category", "ALTER TABLE dbo.Courses ADD Category NVARCHAR(100) NULL");
        addColumnIfMissing("QuizQuestions", "VideoUrl", "ALTER TABLE dbo.QuizQuestions ADD VideoUrl NVARCHAR(500) NULL");
        addColumnIfMissing("MailTemplateCategories", "Team", "ALTER TABLE dbo.MailTemplateCategories ADD Team NVARCHAR(30) NULL");
        addColumnIfMissing("KnowledgeCategories", "Team", "ALTER TABLE dbo.KnowledgeCategories ADD Team NVARCHAR(30) NULL");
        addColumnIfMissing("ProcedureZones", "Team", "ALTER TABLE dbo.ProcedureZones ADD Team NVARCHAR(30) NULL");
        addColumnIfMissing("GameDefinitions", "EvaluationRound", "ALTER TABLE dbo.GameDefinitions ADD EvaluationRound INT NOT NULL DEFAULT 1");
        addColumnIfMissing("GameDefinitions", "TargetTeam", "ALTER TABLE dbo.GameDefinitions ADD TargetTeam NVARCHAR(30) NULL");
        addColumnIfMissing("GameEvaluationAttempts", "EvaluationRound", "ALTER TABLE dbo.GameEvaluationAttempts ADD EvaluationRound INT NOT NULL DEFAULT 1");
        addColumnIfMissing("Courses", "FileUrl", "ALTER TABLE dbo.Courses ADD FileUrl NVARCHAR(500) NULL");
        addColumnIfMissing("Courses", "FileName", "ALTER TABLE dbo.Courses ADD FileName NVARCHAR(255) NULL");
        // Contrôle QA de la publication des cours (Formation) — existants = PUBLISHED.
        addColumnIfMissing("Courses", "PublicationStatus", "ALTER TABLE dbo.Courses ADD PublicationStatus NVARCHAR(20) NOT NULL CONSTRAINT DF_Courses_PublicationStatus DEFAULT 'PUBLISHED'");
        addColumnIfMissing("Courses", "PublishedAt", "ALTER TABLE dbo.Courses ADD PublishedAt DATETIME2 NULL");
        addColumnIfMissing("Courses", "PublishedBy", "ALTER TABLE dbo.Courses ADD PublishedBy NVARCHAR(150) NULL");

        createIfMissing("SiteBanner", """
                CREATE TABLE dbo.SiteBanner (
                    SiteBannerId INT IDENTITY PRIMARY KEY,
                    ImageUrl NVARCHAR(500) NULL,
                    Headline NVARCHAR(200) NULL,
                    Subheadline NVARCHAR(500) NULL,
                    CtaLabel NVARCHAR(100) NULL,
                    CtaUrl NVARCHAR(500) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("AgentSchedules", """
                CREATE TABLE dbo.AgentSchedules (
                    ScheduleId INT IDENTITY PRIMARY KEY,
                    UserId BIGINT NOT NULL,
                    WorkDate DATE NOT NULL,
                    PlannedStartTime TIME NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_AgentSchedules_User_Date UNIQUE (UserId, WorkDate)
                )
                """);
        // PlannedStartTime doit accepter NULL depuis l'import du planning grand format (codes
        // OFF/ABS/RM/P/PU/Congés — un jour sans prise de poste n'a simplement pas d'heure de
        // début). Idempotent : ALTER COLUMN vers NULL sur une colonne déjà NULL ne fait rien.
        try {
            jdbcTemplate.execute("ALTER TABLE dbo.AgentSchedules ALTER COLUMN PlannedStartTime TIME NULL");
        } catch (Exception ignored) {
            // déjà nullable, ou table encore vide au tout premier démarrage — sans conséquence
        }
        addColumnIfMissing("AgentSchedules", "PlannedEndTime", "ALTER TABLE dbo.AgentSchedules ADD PlannedEndTime TIME NULL");
        addColumnIfMissing("AgentSchedules", "ShiftCode", "ALTER TABLE dbo.AgentSchedules ADD ShiftCode NVARCHAR(20) NULL");
        addColumnIfMissing("AgentSchedules", "ShiftLabel", "ALTER TABLE dbo.AgentSchedules ADD ShiftLabel NVARCHAR(100) NULL");
        addColumnIfMissing("AgentSchedules", "OvernightCrossesMidnight", "ALTER TABLE dbo.AgentSchedules ADD OvernightCrossesMidnight BIT NOT NULL DEFAULT 0");
        // Validation Team Leader du planning saisi par Excelliam (voir ScheduleService.planifyShifts) —
        // DEFAULT 'APPROVED' pour ne jamais changer le comportement des lignes déjà existantes
        // (import RH classique, planning déjà en place avant ce champ).
        addColumnIfMissing("AgentSchedules", "ApprovalStatus", "ALTER TABLE dbo.AgentSchedules ADD ApprovalStatus VARCHAR(20) NOT NULL DEFAULT 'APPROVED'");
        addColumnIfMissing("AgentSchedules", "RejectionReason", "ALTER TABLE dbo.AgentSchedules ADD RejectionReason NVARCHAR(500) NULL");
        // Qui a initié cette entrée : EXCELLIAM (flux existant, planifyShifts/import RH) ou
        // TEAM_LEADER (nouveau flux symétrique — le Team Leader planifie lui-même et envoie à
        // Excelliam pour validation). Distingue quel rôle doit décider sur une entrée PENDING,
        // et permet l'étape supplémentaire VALIDATED → APPROVED (clic "Mise à jour" du Team
        // Leader) propre au flux TEAM_LEADER — voir ScheduleService.submitTeamPlanning/
        // decideExcelliamValidation/publishTeamPlanning.
        addColumnIfMissing("AgentSchedules", "Origin", "ALTER TABLE dbo.AgentSchedules ADD Origin VARCHAR(20) NOT NULL DEFAULT 'EXCELLIAM'");

        // Permutation de shift entre agents — workflow à 2 étapes (agent visé, puis Team Leader).
        // Voir ShiftSwapRequest.java pour la sémantique complète des statuts.
        //
        // ⚠️ Les colonnes RequesterUserId / TargetUserId / DecidedByTeamLeaderUserId doivent
        // avoir EXACTEMENT le même type SQL que dbo.USERS.ID, sans quoi SQL Server refuse la
        // création de la contrainte FK ("Column ... is not the same data type as referencing
        // column ..."). Plutôt que de figer BIGINT en dur (qui ne correspond pas forcément au
        // type réel de USERS.ID sur les bases provisionnées avant ce changement), on détecte le
        // type effectif de USERS.ID à l'exécution et on l'utilise tel quel.
        createIfMissing("ShiftSwapRequests", """
                CREATE TABLE dbo.ShiftSwapRequests (
                    SwapRequestId INT IDENTITY(1,1) PRIMARY KEY,
                    RequesterUserId %1$s NOT NULL REFERENCES dbo.USERS(ID),
                    RequesterDate DATE NOT NULL,
                    TargetUserId %1$s NOT NULL REFERENCES dbo.USERS(ID),
                    TargetDate DATE NOT NULL,
                    PeerStatus VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    TeamLeaderStatus VARCHAR(20) NOT NULL DEFAULT 'NOT_SUBMITTED',
                    DecidedByTeamLeaderUserId %1$s NULL REFERENCES dbo.USERS(ID),
                    TeamLeaderComment NVARCHAR(500) NULL,
                    RequesterMessage NVARCHAR(500) NULL,
                    PeerDecidedAt DATETIME2 NULL,
                    TeamLeaderDecidedAt DATETIME2 NULL,
                    SwapApplied BIT NOT NULL DEFAULT 0,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """.formatted(resolveUsersIdSqlType()));

        createIfMissing("ROLES", """
                CREATE TABLE dbo.ROLES (
            ID INT IDENTITY PRIMARY KEY,
            NAME NVARCHAR(255) NOT NULL,
            DESCRIPTION NVARCHAR(255) NULL
                )
                """);
        seedRolesIfMissing();

        createIfMissing("Teams", """
                CREATE TABLE dbo.Teams (
            TeamId INT IDENTITY PRIMARY KEY,
            Code NVARCHAR(50) NOT NULL UNIQUE,
            Label NVARCHAR(100) NOT NULL,
            IconGlyph NVARCHAR(20) NULL,
            AccentColor NVARCHAR(10) NULL,
            IsActive BIT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("SERVICES", """
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
                )
                """);

        // Les 3 vrais services métier — RCC (centre de contact), IT, Agence. Ajoutés une seule
        // fois chacun (par CODE), sans toucher aux autres services déjà présents (ex. "Quality
        // Assurance", nécessaire au mécanisme de détection du profil QA existant).
        seedServiceIfMissing("RCC", "RCC", "Centre de contact", 1);
        seedServiceIfMissing("IT", "IT", "Service informatique", 2);
        seedServiceIfMissing("AGENCE", "Agence", "Réseau d'agences", 3);
        seedServiceIfMissing("OUTBOUND", "Outbound", "Ventes et appels sortants", 4);

        // Services métier attribuables depuis la fiche utilisateur (Administration → Services),
        // dont l'attribution pilote désormais l'atterrissage automatique sur le bon portail à la
        // connexion — voir UserService.SERVICE_PORTAL_REDIRECTS / teamStatus(). Additifs, comme
        // les autres seedServiceIfMissing ci-dessus : jamais recréés ni modifiés si déjà présents.
        seedServiceIfMissing("TEAM_LEADER_INBOUND_VOICE", "Team Leader Inbound Voice", "Responsable de l'équipe Inbound Voix", 5);
        seedServiceIfMissing("TEAM_LEADER_INBOUND_MAIL", "Team Leader Inbound Mail", "Responsable de l'équipe Inbound Mail / Rafiki", 6);
        seedServiceIfMissing("TEAM_LEADER_OUTBOUND", "Team Leader Outbound", "Responsable de l'équipe Outbound", 7);
        seedServiceIfMissing("AGENT_INBOUND", "Agent Inbound", "Conseiller clientèle — pôle Inbound", 8);
        seedServiceIfMissing("AGENT_OUTBOUND", "Agent Outbound", "Conseiller clientèle — pôle Outbound", 9);
        seedServiceIfMissing("SUPERVISEUR", "Superviseur", "Supervision globale du centre de contact", 10);
        seedServiceIfMissing("RH", "RH", "Ressources humaines", 11);
        // Déjà présent en base de prod sous ce code (détection du profil QA) — ce seed ne sert
        // qu'à garantir sa présence sur un environnement neuf (dev local, tests).
        seedServiceIfMissing("QUALITY_ASSURANCE", "Quality Assurance", "Évalue les appels/chats, gère la grille QA et la Base de connaissances", 12);
        // Déclinaisons supplémentaires du service Agent, et famille Quality Assurance élargie
        // (Formateur, Communication, Superviseur QA) — voir UserService.SERVICE_PORTAL_REDIRECTS
        // et AuthService.SERVICE_CODE_TO_BASE_ROLE pour ce que chacun déclenche à la connexion.
        seedServiceIfMissing("AGENT_INBOUND_MAIL", "Agent Inbound Mail", "Conseiller clientèle — pôle Inbound Mail / Rafiki", 13);
        seedServiceIfMissing("AGENT_CIB", "Agent CIB", "Conseiller clientèle — pôle CIB", 14);
        seedServiceIfMissing("FORMATEUR", "Formateur", "Conçoit les formations et crée les évaluations du Centre de Formation", 15);
        seedServiceIfMissing("COMMUNICATION", "Communication", "Habilité à publier des actualités sur MON RCC", 16);
        seedServiceIfMissing("SUPERVISEUR_QA", "Superviseur Qualité Assurance", "Supervision de toutes les écoutes, évaluations et formations Quality Assurance", 17);

        createIfMissing("USERS", """
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
            PASSWORD NVARCHAR(255) NULL,
            CREATED_AT DATETIMEOFFSET NULL,
            MODIFIED_AT DATETIMEOFFSET NULL
                )
                """);

        createIfMissing("USER_ROLES", """
                CREATE TABLE dbo.USER_ROLES (
            ID BIGINT IDENTITY PRIMARY KEY,
            USERS_ID BIGINT NOT NULL,
            ROLES_ID INT NOT NULL
                )
                """);

        createIfMissing("USER_SERVICES", """
                CREATE TABLE dbo.USER_SERVICES (
            ID BIGINT IDENTITY PRIMARY KEY,
            USER_ID BIGINT NOT NULL,
            SERVICE_ID INT NOT NULL
                )
                """);

        createIfMissing("TabPermissions", """
                CREATE TABLE dbo.TabPermissions (
            TabPermissionId INT IDENTITY PRIMARY KEY,
            TeamId INT NULL,
            RoleId INT NULL,
            TabCode NVARCHAR(50) NOT NULL,
            IsAllowed BIT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("Attachments", """
                CREATE TABLE dbo.Attachments (
            AttachmentId INT IDENTITY PRIMARY KEY,
            EntityType NVARCHAR(50) NOT NULL,
            EntityId INT NOT NULL,
            FileName NVARCHAR(260) NOT NULL,
            MimeType NVARCHAR(100) NULL,
            StorageUrl NVARCHAR(500) NOT NULL,
            UploadedByUserId BIGINT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("FavoriteProcedures", """
                CREATE TABLE dbo.FavoriteProcedures (
            FavoriteId INT IDENTITY PRIMARY KEY,
            UserId BIGINT NOT NULL,
            ProcedureId INT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            CONSTRAINT UQ_FavoriteProcedures_User_Procedure UNIQUE (UserId, ProcedureId)
                )
                """);

        createIfMissing("FavoriteAttachments", """
                CREATE TABLE dbo.FavoriteAttachments (
            FavoriteId INT IDENTITY PRIMARY KEY,
            UserId BIGINT NOT NULL,
            AttachmentId INT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            CONSTRAINT UQ_FavoriteAttachments_User_Attachment UNIQUE (UserId, AttachmentId)
                )
                """);

        createIfMissing("QualityCriterionAttributes", """
                CREATE TABLE dbo.QualityCriterionAttributes (
            AttributeId INT IDENTITY PRIMARY KEY,
            CriterionId INT NOT NULL,
            Label NVARCHAR(300) NOT NULL,
            SortOrder INT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("MailTemplateCategories", """
                CREATE TABLE dbo.MailTemplateCategories (
            CategoryId INT IDENTITY PRIMARY KEY,
            Code NVARCHAR(50) NOT NULL UNIQUE,
            Label NVARCHAR(100) NOT NULL,
            IconGlyph NVARCHAR(20) NULL,
            AccentColor NVARCHAR(10) NULL,
            BackgroundColor NVARCHAR(10) NULL,
            SortOrder INT NOT NULL,
            Team NVARCHAR(30) NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("MailRecipientGroups", """
                CREATE TABLE dbo.MailRecipientGroups (
            GroupId INT IDENTITY PRIMARY KEY,
            Label NVARCHAR(150) NOT NULL,
            Email NVARCHAR(254) NOT NULL UNIQUE,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("MailTemplates", """
                CREATE TABLE dbo.MailTemplates (
            TemplateId INT IDENTITY PRIMARY KEY,
            CategoryId INT NOT NULL,
            Subject NVARCHAR(300) NOT NULL,
            Body NVARCHAR(MAX) NOT NULL,
            RecipientType NVARCHAR(20) NOT NULL,
            RecipientGroupId INT NULL,
            CreatedByUserId BIGINT NULL,
            IsSystemTemplate BIT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        seedOutboundMailTemplatesIfMissing();
        seedEscalationMailTemplates();

        createIfMissing("SalesJourneys", """
                CREATE TABLE dbo.SalesJourneys (
                    JourneyId INT IDENTITY(1,1) PRIMARY KEY,
                    JourneyKey VARCHAR(60) NOT NULL UNIQUE,
                    Title NVARCHAR(150) NOT NULL,
                    Icon VARCHAR(50) NULL,
                    ColorFrom VARCHAR(20) NULL,
                    ColorTo VARCHAR(20) NULL,
                    Pitch NVARCHAR(300) NULL,
                    StepsJson NVARCHAR(MAX) NOT NULL,
                    SortOrder INT NOT NULL DEFAULT 0,
                    Active BIT NOT NULL DEFAULT 1,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        seedSalesJourneysIfEmpty();
        seedAdditionalSalesJourneysIfMissing();
        seedRealQualityCriteriaIfMissing();
        seedOutboundKnowledgeBaseIfMissing();

        // Moteur d'alertes automatique — voir AlertEngineService / PerformanceAlertScheduler.
        createIfMissing("PerformanceAlerts", """
                CREATE TABLE dbo.PerformanceAlerts (
                    AlertId INT IDENTITY(1,1) PRIMARY KEY,
                    UserId BIGINT NOT NULL,
                    PeriodMonth VARCHAR(7) NOT NULL,
                    AlertType VARCHAR(40) NOT NULL,
                    Severity VARCHAR(20) NOT NULL,
                    Message NVARCHAR(500) NOT NULL,
                    Acknowledged BIT NOT NULL DEFAULT 0,
                    AcknowledgedByUsername NVARCHAR(100) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Compétitions d'évaluation par équipe — voir GameCompetitionService.
        createIfMissing("GameCompetitions", """
                CREATE TABLE dbo.GameCompetitions (
                    CompetitionId INT IDENTITY(1,1) PRIMARY KEY,
                    GameKey VARCHAR(50) NOT NULL,
                    Title NVARCHAR(200) NOT NULL,
                    ScheduledAt DATETIME2 NOT NULL,
                    Status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
                    IsTraineeOnly BIT NOT NULL DEFAULT 0,
                    CreatedByUsername NVARCHAR(100) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("GameCompetitionTeams", """
                CREATE TABLE dbo.GameCompetitionTeams (
                    CompetitionTeamId INT IDENTITY(1,1) PRIMARY KEY,
                    CompetitionId INT NOT NULL,
                    Team VARCHAR(30) NOT NULL,
                    Validated BIT NOT NULL DEFAULT 0,
                    ValidatedAt DATETIME2 NULL,
                    ValidatedByUsername NVARCHAR(100) NULL
                )
                """);

        createIfMissing("GameCompetitionParticipants", """
                CREATE TABLE dbo.GameCompetitionParticipants (
                    ParticipantId INT IDENTITY(1,1) PRIMARY KEY,
                    CompetitionId INT NOT NULL,
                    UserId BIGINT NOT NULL,
                    Team VARCHAR(30) NOT NULL,
                    Score INT NULL,
                    CompletedAt DATETIME2 NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("KnowledgeCategories", """
                CREATE TABLE dbo.KnowledgeCategories (
            CategoryId INT IDENTITY PRIMARY KEY,
            Code NVARCHAR(50) NOT NULL UNIQUE,
            Title NVARCHAR(100) NOT NULL,
            Icon NVARCHAR(50) NULL,
            SortOrder INT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        boolean kbCategoriesEmpty = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.KnowledgeCategories", Integer.class) == 0);
        if (kbCategoriesEmpty) {
            // Les 12 vraies thématiques du document Ecobank source ("UNIFORMISATION DES
            // PROCESS DE PRISE EN CHARGE RCC") — pas le regroupement en 10 zones utilisé
            // côté Procédures, qui répond à un besoin différent (filtrage des parcours).
            String[][] kbSeed = {
                    {"COMPTE", "Compte", "bi-wallet2"},
                    {"TRANSFERT", "Transfert", "bi-arrow-left-right"},
                    {"ATTESTATION_BANCAIRE", "Attestation Bancaire", "bi-file-earmark-text"},
                    {"ASSURANCE", "Assurance", "bi-shield-check"},
                    {"ASSURANCE_SOUSCRIPTION_CONTESTEE", "Assurance - Souscription Contestée", "bi-shield-exclamation"},
                    {"CARTE_ATM", "Carte ATM", "bi-credit-card"},
                    {"CONNEXION_PRODUITS_DIGITAUX", "Connexion Produits Digitaux", "bi-phone"},
                    {"MMH", "MMH (Ecobank-Orange Money & MTN Mobile Money)", "bi-wallet"},
                    {"CASHXPRESS", "CashXpress - Demande de Solde Carte", "bi-cash"},
                    {"DEMANDE_PRET", "Demande de Prêt", "bi-cash-coin"},
                    {"CHEQUE", "Chèque - Demande de Chéquier", "bi-journal-text"},
                    {"AUTRE", "Autre - Ouverture de Relevé Bancaire", "bi-grid-3x3-gap"}
            };
            for (int i = 0; i < kbSeed.length; i++) {
                jdbcTemplate.update(
                        "INSERT INTO dbo.KnowledgeCategories (Code, Title, Icon, SortOrder) VALUES (?, ?, ?, ?)",
                        kbSeed[i][0], kbSeed[i][1], kbSeed[i][2], i + 1);
            }
            log.warn("⚠ [SCHEMA BOOTSTRAP] {} catégories Knowledge Base créées automatiquement.", kbSeed.length);
        }

        createIfMissing("KnowledgeCountries", """
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
                )
                """);

        boolean countriesEmpty = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.KnowledgeCountries", Integer.class) == 0);
        if (countriesEmpty) {
            String[][] countrySeed = {
                    {"CI", "Côte d'Ivoire", "🇨🇮", "Franc CFA (XOF)", "Zone UEMOA", "BCEAO"},
                    {"SN", "Sénégal", "🇸🇳", "Franc CFA (XOF)", "Zone UEMOA", "BCEAO"},
                    {"BJ", "Bénin", "🇧🇯", "Franc CFA (XOF)", "Zone UEMOA", "BCEAO"},
                    {"TG", "Togo", "🇹🇬", "Franc CFA (XOF)", "Zone UEMOA", "BCEAO"},
                    {"BF", "Burkina Faso", "🇧🇫", "Franc CFA (XOF)", "Zone UEMOA", "BCEAO"},
                    {"ML", "Mali", "🇲🇱", "Franc CFA (XOF)", "Zone UEMOA", "BCEAO"},
                    {"GN", "Guinée", "🇬🇳", "Franc Guinéen (GNF)", "Zone CEDEAO", "BCRG"},
                    {"CM", "Cameroun", "🇨🇲", "Franc CFA (XAF)", "Zone CEMAC", "BEAC"},
                    {"CG", "Congo Brazzaville", "🇨🇬", "Franc CFA (XAF)", "Zone CEMAC", "BEAC"},
                    {"CD", "Congo Kinshasa (RDC)", "🇨🇩", "Franc Congolais (CDF)", "—", "BCC"}
            };
            for (int i = 0; i < countrySeed.length; i++) {
                jdbcTemplate.update(
                        "INSERT INTO dbo.KnowledgeCountries (CountryCode, Label, FlagEmoji, Currency, Zone, Regulator, SortOrder) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        countrySeed[i][0], countrySeed[i][1], countrySeed[i][2], countrySeed[i][3], countrySeed[i][4], countrySeed[i][5], i + 1);
            }
            log.warn("⚠ [SCHEMA BOOTSTRAP] {} filiale(s) Ecobank créée(s) automatiquement.", countrySeed.length);
        }

        createIfMissing("NewsArticles", """
                CREATE TABLE dbo.NewsArticles (
                    NewsId INT IDENTITY PRIMARY KEY,
                    Title NVARCHAR(200) NOT NULL,
                    ContentHtml NVARCHAR(MAX) NOT NULL,
                    ImageUrl NVARCHAR(500) NULL,
                    CreatedByUserId BIGINT NULL,
                    SortOrder INT NOT NULL DEFAULT 0,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("KnowledgeArticles", """
                CREATE TABLE dbo.KnowledgeArticles (
            ArticleId INT IDENTITY PRIMARY KEY,
            CategoryId INT NOT NULL,
            CountryCode NVARCHAR(2) NULL,
            Title NVARCHAR(200) NOT NULL,
            ContentHtml NVARCHAR(MAX) NOT NULL,
            Tags NVARCHAR(300) NULL,
            SortOrder INT NOT NULL,
            CreatedByUserId INT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("Conversations", """
                CREATE TABLE dbo.Conversations (
            ConversationId INT IDENTITY PRIMARY KEY,
            Type NVARCHAR(10) NOT NULL,
            Name NVARCHAR(150) NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("ConversationParticipants", """
                CREATE TABLE dbo.ConversationParticipants (
            ParticipantId INT IDENTITY PRIMARY KEY,
            ConversationId INT NOT NULL,
            UserId BIGINT NOT NULL,
            LastReadAt DATETIME2 NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            CONSTRAINT UQ_ConversationParticipants_Conv_User UNIQUE (ConversationId, UserId)
                )
                """);

        createIfMissing("ChatMessages", """
                CREATE TABLE dbo.ChatMessages (
            MessageId INT IDENTITY PRIMARY KEY,
            ConversationId INT NOT NULL,
            SenderUserId BIGINT NOT NULL,
            Content NVARCHAR(MAX) NOT NULL,
            SentAt DATETIME2 NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("LoginAudit", """
                CREATE TABLE dbo.LoginAudit (
            LoginAuditId INT IDENTITY PRIMARY KEY,
            UserId BIGINT NOT NULL,
            EventType NVARCHAR(30) NOT NULL,
            OccurredAt DATETIME2 NOT NULL,
            PasswordAgeDays INT NULL,
            IpAddress NVARCHAR(45) NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("AttendanceRecords", """
                CREATE TABLE dbo.AttendanceRecords (
            AttendanceId INT IDENTITY PRIMARY KEY,
            UserId BIGINT NOT NULL,
            WorkDate DATE NOT NULL,
            Status NVARCHAR(20) NOT NULL,
            ArrivalTime TIME NULL,
            DepartureTime TIME NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            CONSTRAINT UQ_AttendanceRecords_User_Date UNIQUE (UserId, WorkDate)
                )
                """);

        createIfMissing("KpiEvents", """
                CREATE TABLE dbo.KpiEvents (
            KpiEventId INT IDENTITY PRIMARY KEY,
            UserId BIGINT NULL,
            EventType NVARCHAR(50) NOT NULL,
            EventKey NVARCHAR(150) NULL,
            OccurredAt DATETIME2 NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("ManualKpiEntries", """
                CREATE TABLE dbo.ManualKpiEntries (
            ManualKpiEntryId INT IDENTITY PRIMARY KEY,
            SubjectUserId BIGINT NOT NULL,
            EnteredByUserId BIGINT NOT NULL,
            MetricCode NVARCHAR(50) NOT NULL,
            MetricValue DECIMAL(18,4) NOT NULL,
            PeriodDate DATE NOT NULL,
            ImportBatchId NVARCHAR(40) NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("CoachingPlans", """
                CREATE TABLE dbo.CoachingPlans (
            CoachingPlanId INT IDENTITY PRIMARY KEY,
            AgentUserId BIGINT NOT NULL,
            Axis NVARCHAR(300) NOT NULL,
            DueDate DATE NOT NULL,
            Status NVARCHAR(20) NOT NULL,
            Note NVARCHAR(1000) NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("AgentDossiers", """
                CREATE TABLE dbo.AgentDossiers (
            DossierId INT IDENTITY PRIMARY KEY,
            LinkedUserId BIGINT NULL,
            Source NVARCHAR(100) NOT NULL,
            Payload NVARCHAR(MAX) NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("KvEntries", """
                CREATE TABLE dbo.KvEntries (
            Scope NVARCHAR(100) NOT NULL,
            [Key] NVARCHAR(200) NOT NULL,
            Value NVARCHAR(MAX) NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
            CONSTRAINT PK_KvEntries PRIMARY KEY (Scope, [Key])
                )
                """);

        // ===================== FORMATION PROGRAMMABLE (parcours anti-triche) =====================

        createIfMissing("TrainingFormations", """
                CREATE TABLE dbo.TrainingFormations (
                    FormationId INT IDENTITY(1,1) PRIMARY KEY,
                    Title NVARCHAR(200) NOT NULL,
                    Description NVARCHAR(MAX) NULL,
                    Category NVARCHAR(100) NULL,
                    ScheduledDate DATE NOT NULL,
                    ScheduledTime TIME NULL,
                    RecurrenceType VARCHAR(20) NOT NULL DEFAULT 'NONE',
                    DurationMinutes INT NULL,
                    VideoMaxPlaybackRate FLOAT NOT NULL DEFAULT 1.5,
                    CompletionThresholdPercent INT NOT NULL DEFAULT 95,
                    Status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
                    TargetTeam NVARCHAR(100) NULL,
                    Mandatory BIT NOT NULL DEFAULT 1,
                    CreatedByUserId BIGINT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("TrainingLessons", """
                CREATE TABLE dbo.TrainingLessons (
                    LessonId INT IDENTITY(1,1) PRIMARY KEY,
                    FormationId INT NOT NULL REFERENCES dbo.TrainingFormations(FormationId) ON DELETE CASCADE,
                    Title NVARCHAR(200) NOT NULL,
                    ContentHtml NVARCHAR(MAX) NULL,
                    VideoUrl NVARCHAR(500) NULL,
                    OrderIndex INT NOT NULL DEFAULT 0,
                    EstimatedMinutes INT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("TrainingProgress", """
                CREATE TABLE dbo.TrainingProgress (
                    ProgressId INT IDENTITY(1,1) PRIMARY KEY,
                    LessonId INT NOT NULL REFERENCES dbo.TrainingLessons(LessonId) ON DELETE CASCADE,
                    UserId BIGINT NOT NULL,
                    ScrollPercent INT NOT NULL DEFAULT 0,
                    VideoWatchedPercent INT NOT NULL DEFAULT 0,
                    Completed BIT NOT NULL DEFAULT 0,
                    StartedAt DATETIME2 NULL,
                    CompletedAt DATETIME2 NULL,
                    LastActivityAt DATETIME2 NULL,
                    SpeedViolationCount INT NOT NULL DEFAULT 0,
                    CONSTRAINT UQ_TrainingProgress_Lesson_User UNIQUE (LessonId, UserId)
                )
                """);

        // ===================== BANQUE DE QUESTIONS ENRICHIE =====================

        createIfMissing("QuizQuestions", """
                CREATE TABLE dbo.QuizQuestions (
                    QuestionId INT IDENTITY(1,1) PRIMARY KEY,
                    QuestionText NVARCHAR(1000) NOT NULL,
                    Type VARCHAR(20) NOT NULL DEFAULT 'MCQ',
                    Difficulty VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
                    Category NVARCHAR(100) NULL,
                    OptionsJson NVARCHAR(MAX) NULL,
                    CorrectOptionIndex INT NULL,
                    CorrectIndexesJson NVARCHAR(500) NULL,
                    Explanation NVARCHAR(MAX) NULL,
                    ImageUrl NVARCHAR(500) NULL,
                    VideoUrl NVARCHAR(500) NULL,
                    Tags NVARCHAR(300) NULL,
                    Points INT NOT NULL DEFAULT 10,
                    TimeLimitSeconds INT NULL,
                    Active BIT NOT NULL DEFAULT 1,
                    CreatedByUserId BIGINT NULL,
                    UsageCount INT NOT NULL DEFAULT 0,
                    CorrectAnswerCount INT NOT NULL DEFAULT 0,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        seedQuizQuestionsIfEmpty();

        // ===================== MODELES DE DEMANDE + SOLDE DE CONGES =====================

        createIfMissing("RequestTemplates", """
                CREATE TABLE dbo.RequestTemplates (
                    TemplateId INT IDENTITY(1,1) PRIMARY KEY,
                    Name NVARCHAR(150) NOT NULL,
                    Type VARCHAR(30) NOT NULL,
                    DefaultTitle NVARCHAR(200) NOT NULL,
                    DefaultDetails NVARCHAR(2000) NULL,
                    DefaultAssignedTeam VARCHAR(30) NULL,
                    Active BIT NOT NULL DEFAULT 1,
                    CreatedByUserId BIGINT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("LeaveBalances", """
                CREATE TABLE dbo.LeaveBalances (
                    BalanceId INT IDENTITY(1,1) PRIMARY KEY,
                    UserId BIGINT NOT NULL,
                    Year INT NOT NULL,
                    AllocatedDays INT NOT NULL DEFAULT 24,
                    UpdatedByUserId BIGINT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_LeaveBalances_User_Year UNIQUE (UserId, Year)
                )
                """);

        // ===================== SLA PERSONNALISES =====================

        createIfMissing("SlaTargets", """
                CREATE TABLE dbo.SlaTargets (
                    SlaTargetId INT IDENTITY(1,1) PRIMARY KEY,
                    Team VARCHAR(30) NOT NULL,
                    Type VARCHAR(30) NOT NULL,
                    ThresholdHours INT NOT NULL,
                    UpdatedByUserId BIGINT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT UQ_SlaTargets_Team_Type UNIQUE (Team, Type)
                )
                """);
        seedMotifSlaTargetsIfMissing();

        // ===================== CENTRE DE JEUX + VOCABULAIRE =====================

        createIfMissing("GameDefinitions", """
                CREATE TABLE dbo.GameDefinitions (
                    GameId INT IDENTITY(1,1) PRIMARY KEY,
                    GameKey VARCHAR(50) NOT NULL UNIQUE,
                    Mechanic VARCHAR(30) NOT NULL,
                    Title NVARCHAR(150) NOT NULL,
                    Description NVARCHAR(500) NULL,
                    Icon VARCHAR(50) NULL,
                    ColorFrom VARCHAR(20) NULL,
                    ColorTo VARCHAR(20) NULL,
                    ConfigJson NVARCHAR(MAX) NULL,
                    SortOrder INT NOT NULL DEFAULT 0,
                    Active BIT NOT NULL DEFAULT 1,
                    EvaluationRound INT NOT NULL DEFAULT 1,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        seedGameDefinitionsIfEmpty();
        seedTeamSetupServicesIfMissing();

        createIfMissing("GameScores", """
                CREATE TABLE dbo.GameScores (
                    ScoreId INT IDENTITY(1,1) PRIMARY KEY,
                    GameKey VARCHAR(50) NOT NULL,
                    UserId BIGINT NOT NULL,
                    Score INT NOT NULL,
                    CorrectCount INT NULL,
                    TotalCount INT NULL,
                    PlayedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Tentatives d'évaluation QCM (1re et 2e du cycle — voir games.js resultScreen()) :
        // la 1re ne révèle aucune correction, la 2e affiche le détail vert/rouge — c'est la
        // DERNIÈRE (2e tentative de la session en cours), pas de rejeu possible ensuite, sauf
        // si QA/Admin ouvre une nouvelle session (EvaluationRound incrémenté — voir
        // GameService.startNewEvaluationRound()). La plus récente des deux remonte dans le
        // Reporting (ReportingService).
        createIfMissing("GameEvaluationAttempts", """
                CREATE TABLE dbo.GameEvaluationAttempts (
                    AttemptId INT IDENTITY(1,1) PRIMARY KEY,
                    GameKey VARCHAR(50) NOT NULL,
                    UserId BIGINT NOT NULL,
                    AttemptNumber INT NOT NULL DEFAULT 1,
                    EvaluationRound INT NOT NULL DEFAULT 1,
                    Score INT NOT NULL,
                    CorrectCount INT NULL,
                    TotalCount INT NULL,
                    AnswersJson NVARCHAR(MAX) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Déblocages individuels — voir GameEvaluationUnlock.java / GameService.evaluationStatus().
        createIfMissing("GameEvaluationUnlocks", """
                CREATE TABLE dbo.GameEvaluationUnlocks (
                    UnlockId INT IDENTITY(1,1) PRIMARY KEY,
                    GameKey VARCHAR(50) NOT NULL,
                    UserId BIGINT NOT NULL,
                    UnlockedByUsername NVARCHAR(100) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Historique des analyses de données (IA ou moteur de règles automatique — voir
        // DataAnalysisService.generateRuleBasedNarrative()) : chaque génération est enregistrée,
        // consultable ensuite sans avoir à la relancer.
        createIfMissing("DataAnalysisSnapshots", """
                CREATE TABLE dbo.DataAnalysisSnapshots (
                    SnapshotId INT IDENTITY(1,1) PRIMARY KEY,
                    PeriodMonth VARCHAR(7) NOT NULL,
                    TeamFilter NVARCHAR(100) NULL,
                    Narrative NVARCHAR(MAX) NOT NULL,
                    GeneratedByAi BIT NOT NULL DEFAULT 0,
                    TotalAgents INT NULL,
                    AvgPresenceRate FLOAT NULL,
                    AvgQualityScore FLOAT NULL,
                    AvgPerformanceGlobale FLOAT NULL,
                    GeneratedByUsername NVARCHAR(100) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Module Outbound — CRM d'appels (campagnes de contacts, Portail Team Leader / tableau de bord Outbound).
        createIfMissing("Campaigns", """
                CREATE TABLE dbo.Campaigns (
                    CampaignId INT IDENTITY(1,1) PRIMARY KEY,
                    Name NVARCHAR(200) NOT NULL,
                    Description NVARCHAR(1000) NULL,
                    CreatedByUserId BIGINT NOT NULL,
                    StartDate DATE NULL,
                    EndDate DATE NULL,
                    Status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Numéro de compte TOUJOURS masqué avant insertion (voir CampaignService.maskAccountNumber)
        // — jamais stocké en clair, pour la confidentialité des données client.
        createIfMissing("CampaignContacts", """
                CREATE TABLE dbo.CampaignContacts (
                    ContactId INT IDENTITY(1,1) PRIMARY KEY,
                    CampaignId INT NOT NULL,
                    AgentUserId BIGINT NULL,
                    ClientName NVARCHAR(200) NOT NULL,
                    ClientPhone NVARCHAR(50) NULL,
                    MaskedAccountNumber NVARCHAR(30) NULL,
                    CallStatus VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    Notes NVARCHAR(1000) NULL,
                    LastCalledAt DATETIME2 NULL,
                    AppointmentId INT NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Différenciation Service Digital / Télévente + modèle de questions par campagne
        // (onglet "Campagne" agent, remplace la Base de connaissance pour ces 2 sous-services
        // Outbound — voir CampaignService.resolveAgentSubService/activeCampaignsForAgent).
        addColumnIfMissing("Campaigns", "TargetService", "ALTER TABLE dbo.Campaigns ADD TargetService VARCHAR(20) NULL");
        addColumnIfMissing("Campaigns", "IconClass", "ALTER TABLE dbo.Campaigns ADD IconClass NVARCHAR(50) NULL");
        addColumnIfMissing("Campaigns", "ColorFrom", "ALTER TABLE dbo.Campaigns ADD ColorFrom NVARCHAR(10) NULL");
        addColumnIfMissing("Campaigns", "ColorTo", "ALTER TABLE dbo.Campaigns ADD ColorTo NVARCHAR(10) NULL");
        addColumnIfMissing("Campaigns", "FieldsJson", "ALTER TABLE dbo.Campaigns ADD FieldsJson NVARCHAR(4000) NULL");
        addColumnIfMissing("Campaigns", "CoverImageUrl", "ALTER TABLE dbo.Campaigns ADD CoverImageUrl NVARCHAR(500) NULL");
        addColumnIfMissing("CampaignContacts", "AnswersJson", "ALTER TABLE dbo.CampaignContacts ADD AnswersJson NVARCHAR(4000) NULL");
        seedOutboundCampaignTemplatesIfMissing();

        // Module Outbound — ventes et rendez-vous (Portail Team Leader / tableau de bord Outbound).
        createIfMissing("SalesRecords", """
                CREATE TABLE dbo.SalesRecords (
                    SaleId INT IDENTITY(1,1) PRIMARY KEY,
                    AgentUserId BIGINT NOT NULL,
                    ProductName NVARCHAR(150) NOT NULL,
                    ClientName NVARCHAR(200) NULL,
                    ClientPhone NVARCHAR(50) NULL,
                    Amount FLOAT NULL,
                    SaleDate DATE NOT NULL,
                    Status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
                    Notes NVARCHAR(1000) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("Appointments", """
                CREATE TABLE dbo.Appointments (
                    AppointmentId INT IDENTITY(1,1) PRIMARY KEY,
                    AgentUserId BIGINT NOT NULL,
                    ClientName NVARCHAR(200) NOT NULL,
                    ClientPhone NVARCHAR(50) NULL,
                    Purpose NVARCHAR(300) NULL,
                    ScheduledAt DATETIME2 NOT NULL,
                    Status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
                    Notes NVARCHAR(1000) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        createIfMissing("WordTerms", """
                CREATE TABLE dbo.WordTerms (
                    TermId INT IDENTITY(1,1) PRIMARY KEY,
                    Term NVARCHAR(100) NOT NULL,
                    Definition NVARCHAR(500) NOT NULL,
                    Category NVARCHAR(100) NULL,
                    Active BIT NOT NULL DEFAULT 1,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);
        seedWordTermsIfEmpty();

        // ===================== PAGE DE CONNEXION — CARTES THEME =====================

        createIfMissing("LoginFeatureCards", """
                CREATE TABLE dbo.LoginFeatureCards (
                    CardId INT IDENTITY(1,1) PRIMARY KEY,
                    Icon NVARCHAR(50) NOT NULL,
                    Title NVARCHAR(100) NOT NULL,
                    Subtitle NVARCHAR(200) NULL,
                    SortOrder INT NOT NULL DEFAULT 0,
                    Active BIT NOT NULL DEFAULT 1,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    UpdatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // ===================== JOURNAL D'AUDIT / TRAÇABILITÉ =====================

        createIfMissing("ActionAuditLogs", """
                CREATE TABLE dbo.ActionAuditLogs (
                    AuditLogId INT IDENTITY(1,1) PRIMARY KEY,
                    Username NVARCHAR(100) NOT NULL,
                    Action VARCHAR(50) NOT NULL,
                    Details NVARCHAR(2000) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // KPI agrégé équipe/pôle (fichiers type "FOCUS PAR PÔLE") — voir TeamKpiImportService.
        createIfMissing("TeamKpiEntries", """
                CREATE TABLE dbo.TeamKpiEntries (
                    TeamKpiEntryId INT IDENTITY(1,1) PRIMARY KEY,
                    Team VARCHAR(30) NOT NULL,
                    MetricCode NVARCHAR(50) NOT NULL,
                    MetricValue DECIMAL(18,4) NOT NULL,
                    PeriodDate DATE NOT NULL,
                    EnteredByUsername NVARCHAR(100) NULL,
                    ImportBatchId NVARCHAR(40) NULL,
                    CreatedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
                )
                """);

        // Rubriques Formation avec image — équivalent KnowledgeCategory pour la Formation.
        createIfMissing("CourseCategories", """
                CREATE TABLE dbo.CourseCategories (
                    CourseCategoryId INT IDENTITY(1,1) PRIMARY KEY,
                    Title NVARCHAR(100) NOT NULL UNIQUE,
                    ImageUrl NVARCHAR(500) NULL,
                    SortOrder INT NOT NULL DEFAULT 0
                )
                """);
        boolean courseCategoriesEmpty = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.CourseCategories", Integer.class) == 0);
        if (courseCategoriesEmpty) {
            // Mêmes 12 thématiques que la Base de connaissances (voir seed KnowledgeCategories
            // plus haut) — même affichage en rubriques pour la Formation, comme demandé. Table
            // séparée et indépendante (voir CourseCategory.java) : les photos se gèrent ensuite
            // séparément pour chaque module, jamais partagées de force.
            String[] courseCategorySeed = {
                    "Compte", "Transfert", "Attestation Bancaire", "Assurance",
                    "Assurance - Souscription Contestée", "Carte ATM", "Connexion Produits Digitaux",
                    "MMH (Ecobank-Orange Money & MTN Mobile Money)", "CashXpress - Demande de Solde Carte",
                    "Demande de Prêt", "Chèque - Demande de Chéquier", "Autre - Ouverture de Relevé Bancaire"
            };
            for (int i = 0; i < courseCategorySeed.length; i++) {
                jdbcTemplate.update(
                        "INSERT INTO dbo.CourseCategories (Title, SortOrder) VALUES (?, ?)",
                        courseCategorySeed[i], i + 1);
            }
            log.warn("⚠ [SCHEMA BOOTSTRAP] {} rubriques Formation créées automatiquement.", courseCategorySeed.length);
        }

        // Verrouillage vidéo strict (regarder du début à la fin, sans avance rapide ni retour
        // en arrière) — même principe que TrainingProgress, appliqué désormais aux Course.
        addColumnIfMissing("CourseAttempts", "VideoWatchedPercent", "ALTER TABLE dbo.CourseAttempts ADD VideoWatchedPercent INT NOT NULL DEFAULT 0");
        addColumnIfMissing("CourseAttempts", "SeekViolationCount", "ALTER TABLE dbo.CourseAttempts ADD SeekViolationCount INT NOT NULL DEFAULT 0");
    }

    /** Crée les 10 jeux par défaut si la table vient d'être créée (jamais si QA a déjà personnalisé). */
    /**
     * Options du champ "Service" au tout premier login (team-setup.html) : uniquement RCC et
     * Agence — voir WorkflowService.submitTeamAssignment(), qui exige un SERVICES.CODE existant.
     * N'affecte pas les autres services déjà présents (mail-templates, jeux...) : on ajoute
     * seulement ces deux-là s'ils manquent, jamais de suppression.
     */
    private void seedTeamSetupServicesIfMissing() {
        seedServiceIfMissing("RCC", "RCC");
        seedServiceIfMissing("AGENCE", "Agence");
    }

    /**
     * Rôles métier demandés (agents Inbound/Outbound, Team Leader par équipe, Formateur,
     * Quality Assurance, Head RCC/Superviseur) — table dbo.ROLES / USER_ROLES, un système
     * réellement utilisé ailleurs (voir hasRole() dans QualityEvaluationService,
     * GameCompetitionService...) mais jamais alimenté avec des lignes de départ jusqu'ici.
     * Additif : un rôle déjà présent (recherché par nom, insensible à la casse) n'est jamais
     * recréé ni modifié — sûr à rejouer à chaque démarrage.
     */
    private void seedRolesIfMissing() {
        String[][] roles = {
                {"Agent Inbound", "Conseiller clientèle — pôle Inbound (Voix ou Mail/Rafiki)"},
                {"Agent Outbound", "Conseiller clientèle — pôle Outbound (appels sortants, vente)"},
                {"Team Leader", "Responsable d'équipe — accès générique, toutes équipes"},
                {"Team Leader Inbound Voice", "Responsable de l'équipe Inbound Voix"},
                {"Team Leader Inbound Mail", "Responsable de l'équipe Inbound Mail / Rafiki"},
                {"Team Leader Outbound", "Responsable de l'équipe Outbound"},
                {"Formateur", "Conçoit et anime les formations, cours et évaluations du Centre de Formation"},
                {"Quality Assurance", "Évalue les appels/chats, gère la grille QA et la Base de connaissances"},
                {"Superviseur Qualité Assurance", "Supervise toutes les écoutes, évaluations et formations de la Quality Assurance"},
                {"Head RCC (Superviseur)", "Supervision globale du centre de contact, toutes équipes et filiales"},
                {"Head Outbound", "Responsable du pôle Outbound"},
                {"Head CIB-CMB", "Responsable du pôle CIB/CMB"},
                {"Head Resolution", "Responsable du pôle Résolution"}
        };
        for (String[] r : roles) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.ROLES WHERE LOWER(NAME) = LOWER(?)", Integer.class, r[0]);
            if (count != null && count > 0) continue;
            jdbcTemplate.update("INSERT INTO dbo.ROLES (NAME, DESCRIPTION) VALUES (?, ?)", r[0], r[1]);
            log.info("[ROLES] Rôle '{}' créé.", r[0]);
        }
    }

    private void seedServiceIfMissing(String code, String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dbo.SERVICES WHERE CODE = ?", Integer.class, code);
        if (count != null && count > 0) return;
        jdbcTemplate.update(
                "INSERT INTO dbo.SERVICES (NAME, CODE, ENABLED, DISPLAY_ORDER, OPEN_IN_NEW_TAB, IS_PORTAL_APP, STATUS) " +
                        "VALUES (?, ?, 1, 0, 0, 0, 'En service')",
                name, code);
        log.info("[TeamSetup] Service '{}' ({}) créé pour le formulaire de première connexion.", name, code);
    }

    /**
     * Masques d'ESCALADE (tableaux envoyés aux équipes back-office : reset PIN, DSD GAB,
     * linkage Orange Money…). Chaque masque = un mail. Format du corps :
     * « === TITRE === » puis des lignes « LIBELLÉ : [BALISE] » — l'écran « Utiliser » les copie
     * sous forme de VRAI tableau (en-tête coloré) collable dans Outlook. Additif : n'ajoute
     * que les masques absents (même sujet), sans toucher à ceux modifiés par la QA.
     */
    private void seedEscalationMailTemplates() {
        try {
            Integer categoryId = jdbcTemplate.query("SELECT CategoryId FROM dbo.MailTemplateCategories WHERE Code = 'ESCALADE'",
                    rs -> rs.next() ? rs.getInt(1) : null);
            if (categoryId == null) {
                jdbcTemplate.update("INSERT INTO dbo.MailTemplateCategories (Code, Label, IconGlyph, AccentColor, SortOrder, Team) "
                        + "VALUES ('ESCALADE', 'Escalades back-office', 'bi-table', '#2E7D32', 0, 'EMAIL')");
                categoryId = jdbcTemplate.queryForObject("SELECT CategoryId FROM dbo.MailTemplateCategories WHERE Code = 'ESCALADE'", Integer.class);
            }
            int added = 0;
            for (String[] t : ESCALATION_TEMPLATES) {
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dbo.MailTemplates WHERE CategoryId = ? AND Subject = ?", Integer.class, categoryId, t[0]);
                if (exists != null && exists > 0) continue;
                jdbcTemplate.update("INSERT INTO dbo.MailTemplates (CategoryId, Subject, Body, RecipientType, IsSystemTemplate) VALUES (?, ?, ?, 'person', 1)",
                        categoryId, t[0], t[1]);
                added++;
            }
            if (added > 0) log.info("[Masques] {} masque(s) d'escalade back-office ajouté(s).", added);
        } catch (Exception e) {
            log.warn("[Masques] Masques d'escalade non semés : {}", e.getMessage());
        }
    }

    private static final String ESCALATION_SIGNATURE = "\n\nCordialement,\n[NOM_AGENT]\nRelation Client Centre — Ecobank";

    static final String[][] ESCALATION_TEMPLATES = {
            {"RESET PIN APPLICATION MOBILE — [NOM_DU_CLIENT]",
                    "Bonjour Team,\n\nMerci de prendre en charge la demande de reset PIN de l'application mobile du client ci-dessous svp.\n\n"
                            + "=== RESET PIN APPLICATION MOBILE ===\n"
                            + "NOM DU CLIENT : [NOM_DU_CLIENT]\n"
                            + "NUMÉRO DE COMPTE : [NUMERO_DE_COMPTE]\n"
                            + "ADRESSE EMAIL : [ADRESSE_EMAIL]\n"
                            + "CONTACT : [CONTACT]\n"
                            + "QUESTION DE SÉCURITÉ : AUTHENTIFIÉ" + ESCALATION_SIGNATURE},
            {"DSD GAB ECOBANK — [NOM_DU_CLIENT]",
                    "Bonjour Team,\n\nMerci de prendre en charge la réclamation ci-dessous qui a fait un débit à tort sur un guichet ECOBANK.\n"
                            + "Prière effectuer les vérifications s'il vous plaît.\n\n"
                            + "=== DSD GAB ECOBANK ===\n"
                            + "NOM DU CLIENT : [NOM_DU_CLIENT]\n"
                            + "NUMÉRO DE COMPTE : [NUMERO_DE_COMPTE]\n"
                            + "NUMÉRO DE CARTE : [NUMERO_DE_CARTE]\n"
                            + "TYPE DE CARTE : [TYPE_DE_CARTE]\n"
                            + "LIEU : [LIEU]\n"
                            + "DATE : [DATE]\n"
                            + "MONTANT DE LA TRANSACTION : [MONTANT_TRANSACTION]\n"
                            + "RÉFÉRENCE : [REFERENCE]\n"
                            + "CONTACT : [CONTACT]\n"
                            + "CLIENT : AUTHENTIFIÉ" + ESCALATION_SIGNATURE},
            {"RESET PIN CODE ATM CARD — [NOM_DU_CLIENT]",
                    "Bonjour Team,\n\nMerci de prendre en charge la demande de reset PIN code de la carte magnétique du client ci-dessous svp.\n\n"
                            + "=== RESET PIN CODE ATM CARD ===\n"
                            + "NOM DU CLIENT : [NOM_DU_CLIENT]\n"
                            + "PIN MASQUÉ : [PIN_MASQUE]\n"
                            + "NUMÉRO DE COMPTE : [NUMERO_DE_COMPTE]\n"
                            + "ADRESSE EMAIL : [ADRESSE_EMAIL]\n"
                            + "CONTACT : [CONTACT]\n"
                            + "TYPE DE CARTE : [TYPE_DE_CARTE]\n"
                            + "QUESTION DE SÉCURITÉ : AUTHENTIFIÉ" + ESCALATION_SIGNATURE},
            {"RESET PIN CODE CARTE PRÉPAYÉE — [NOM_DU_CLIENT]",
                    "Bonjour Team,\n\nMerci de prendre en charge la demande de reset PIN code de la carte prépayée du client ci-dessous svp.\n\n"
                            + "=== RESET PIN CODE CARTE PRÉPAYÉE ===\n"
                            + "NOM DU CLIENT : [NOM_DU_CLIENT]\n"
                            + "PIN MASQUÉ : [PIN_MASQUE]\n"
                            + "ID CARTE : [ID_CARTE]\n"
                            + "ADRESSE EMAIL : [ADRESSE_EMAIL]\n"
                            + "CONTACT : [CONTACT]\n"
                            + "QUESTION DE SÉCURITÉ : AUTHENTIFIÉ" + ESCALATION_SIGNATURE},
            {"AJOUT DE COMPTE MOBILE APP — [NOM_DU_CLIENT]",
                    "Bonjour Team,\n\nMerci de prendre en charge la demande d'ajout de compte sur l'application mobile du client ci-dessous svp.\n\n"
                            + "=== AJOUT DE COMPTE MOBILE APP ===\n"
                            + "NOM DU CLIENT : [NOM_DU_CLIENT]\n"
                            + "NUMÉRO DE COMPTE : [NUMERO_DE_COMPTE]\n"
                            + "COMPTE À AJOUTER : [COMPTE_A_AJOUTER]\n"
                            + "ADRESSE EMAIL : [ADRESSE_EMAIL]\n"
                            + "CONTACT : [CONTACT]\n"
                            + "QUESTION DE SÉCURITÉ : AUTHENTIFIÉ" + ESCALATION_SIGNATURE},
            {"LINKAGE MMH ORANGE MONEY — [NOM_DU_CLIENT]",
                    "Bonjour Team,\n\nMerci de prendre en charge la demande de linkage Mobile Money (Orange Money) du client ci-dessous svp.\n\n"
                            + "=== LINKAGE MMH ORANGE MONEY ===\n"
                            + "NOM DU CLIENT : [NOM_DU_CLIENT]\n"
                            + "NUMÉRO DE COMPTE : [NUMERO_DE_COMPTE]\n"
                            + "NUMÉRO ORANGE : [NUMERO_ORANGE]\n"
                            + "CLÉ D'ACTIVATION : [CLE_ACTIVATION]\n"
                            + "CONTACT : [CONTACT]\n"
                            + "QUESTION DE SÉCURITÉ : AUTHENTIFIÉ" + ESCALATION_SIGNATURE},
    };

    /**
     * 3 catégories de masques dédiées à l'équipe Outbound (Digitalisation, Prêt, Assurance),
     * synchronisées avec les produits vendus (voir sales-journeys-data.js côté frontend) —
     * n'ajoute rien si au moins une catégorie "OUTBOUND" existe déjà (pour ne pas re-semer
     * après que QA/Admin ait personnalisé ces modèles).
     */
    private void seedOutboundMailTemplatesIfMissing() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dbo.MailTemplateCategories WHERE Team = 'OUTBOUND'", Integer.class);
        if (count != null && count > 0) return;

        String[][] categories = {
                {"OUTBOUND_DIGITAL", "Digitalisation", "bi-phone-fill", "#0057B8"},
                {"OUTBOUND_PRET", "Prêts & Crédits", "bi-piggy-bank-fill", "#F72585"},
                {"OUTBOUND_ASSURANCE", "Assurance", "bi-shield-check", "#00A651"}
        };
        String[][] templates = {
                {"OUTBOUND_DIGITAL", "Activation de votre service digital Ecobank",
                        "Bonjour [NOM],\n\nSuite à notre échange téléphonique, voici comment activer votre service : [PRODUIT].\n" +
                                "Un conseiller reste disponible pour vous accompagner si besoin.\n\nCordialement,\nVotre conseiller Ecobank"},
                {"OUTBOUND_PRET", "Votre simulation de prêt Ecobank",
                        "Bonjour [NOM],\n\nComme convenu, voici le récapitulatif de votre simulation de prêt : [DETAILS].\n" +
                                "N'hésitez pas à me contacter pour toute question avant votre rendez-vous en agence.\n\nCordialement,\nVotre conseiller Ecobank"},
                {"OUTBOUND_ASSURANCE", "Votre offre d'assurance Ecobank",
                        "Bonjour [NOM],\n\nVoici le détail de l'offre d'assurance évoquée ensemble : [DETAILS].\n" +
                                "Je reste à votre disposition pour finaliser votre souscription.\n\nCordialement,\nVotre conseiller Ecobank"}
        };

        for (int i = 0; i < categories.length; i++) {
            jdbcTemplate.update(
                    "INSERT INTO dbo.MailTemplateCategories (Code, Label, IconGlyph, AccentColor, SortOrder, Team) " +
                            "VALUES (?, ?, ?, ?, ?, 'OUTBOUND')",
                    categories[i][0], categories[i][1], categories[i][2], categories[i][3], i);
        }
        for (String[] t : templates) {
            Integer categoryId = jdbcTemplate.queryForObject(
                    "SELECT CategoryId FROM dbo.MailTemplateCategories WHERE Code = ?", Integer.class, t[0]);
            jdbcTemplate.update(
                    "INSERT INTO dbo.MailTemplates (CategoryId, Subject, Body, RecipientType, IsSystemTemplate) " +
                            "VALUES (?, ?, ?, 'person', 1)",
                    categoryId, t[1], t[2]);
        }
        log.info("[Outbound] 3 catégories de masques de mail + modèles créés (Digitalisation, Prêt, Assurance).");
    }

    /**
     * Migration des 6 parcours de vente écrits initialement en dur dans
     * sales-journeys-data.js (Ecobank Mobile, CASHXPRESS, Mobile Money, Carte prépayée,
     * e-Statement, Prêts & Crédits) — désormais administrables par QA/Admin/Team Leader
     * Outbound depuis l'interface (voir SalesJourneyController). N'ajoute rien si la table
     * contient déjà des parcours (pour ne pas écraser des modifications faites depuis).
     */
    /**
     * 3 rubriques de Knowledge Base dédiées à l'équipe Outbound (Prêts &amp; Crédits, Produits
     * Digitaux, Assurance) — n'ajoute rien si au moins une catégorie taguée OUTBOUND existe
     * déjà (pour ne pas re-semer après que QA ait personnalisé ce contenu). Complète, pour la
     * référence détaillée, les parcours de vente interactifs déjà semés (seedSalesJourneysIfEmpty).
     */
    /**
     * Enrichit la banque de parcours de vente avec de nouveaux produits, SANS toucher à ceux
     * déjà présents (édités ou non par QA) — contrairement à seedSalesJourneysIfEmpty() qui ne
     * s'exécute qu'une fois sur une table vide, celle-ci tourne à chaque démarrage et n'ajoute
     * que les clés (JourneyKey) réellement absentes.
     */
    /**
     * Vrais critères d'évaluation QA Ecobank — grille officielle (voir capture fournie par
     * l'utilisateur : sections Accueil, Compréhension, Efficacité, Engagement, Excellence,
     * Empathie, Résolution, Communication, Authentification, Documentation, Clôture,
     * Conformité). Additif : chaque critère n'est créé QUE si son code n'existe pas encore —
     * ne touche jamais à un critère déjà personnalisé par QA depuis l'admin.
     *
     * Notation 0-2 par critère (voir QualityScoreCalculator) ; le critère "ENGKO" est
     * éliminatoire (Autofail sur la grille réelle) — noté 0, il fait échouer l'évaluation
     * entière quel que soit le score global par ailleurs.
     */
    private void seedRealQualityCriteriaIfMissing() {
        record Criterion(String code, String section, String name, String description, int weight, boolean knockOut, String channel, java.util.List<String> attributes) {}

        // Poids repris du barème officiel Ecobank (voir grille source) : Accueil 10, Compréhension 10,
        // Efficacité 15, Engagement 5, Excellence 5, Empathie 10, Résolution 10, Communication 5,
        // Authentification 5, Clôture 5 (poids identique sur les deux variantes Voice/Chat d'un même
        // critère — une seule des deux s'applique par évaluation selon le canal, jamais les deux à la
        // fois). Le dernier bloc (Documentation/Conformité, 5 points dans la grille d'origine) est
        // réparti entre les 3 critères qui coexistent désormais dans la même évaluation :
        // Documentation 2 (Voice/Chat), Conformité du cas 1, Engagement Ecobank 2 (éliminatoire).
        // Total pour une évaluation Voice ou Chat : 85, identique à la grille d'origine.
        java.util.List<Criterion> criteria = java.util.List.of(
                new Criterion("GREET", "ACCUEIL", "Salutations & présentation",
                        "L'agent a-t-il salué le client et utilisé son nom dès la phase d'accueil ?", 10, false, "BOTH",
                        java.util.List.of("L'agent a-t-il salué le client, a-t-il utilisé le nom du client à la phase d'accueil ?")),
                new Criterion("RECOGNIZE", "COMPREHENSION", "La reconnaître et adapter notre offre",
                        "L'agent a-t-il compris le besoin du client et adapté sa réponse en conséquence ?", 10, false, "BOTH",
                        java.util.List.of("Dans sa réponse, l'agent a-t-il compris les besoins du client ? A-t-il adapté sa réponse de façon appropriée à la question ?")),
                new Criterion("ACCESS", "EFFICACITE", "Être accessible et efficace",
                        "L'agent a-t-il été réactif (temps de réponse) ?", 15, false, "BOTH",
                        java.util.List.of("L'agent a-t-il été réactif (temps de réponse) ?")),
                new Criterion("DOWHATSAY", "ENGAGEMENT", "Faire ce que nous promettons de faire",
                        "L'agent a-t-il donné suite aux promesses faites au client ?", 5, false, "BOTH",
                        java.util.List.of("L'agent a-t-il donné suite aux promesses faites au client ?")),
                new Criterion("EXTRAMILE", "EXCELLENCE", "Aller au-delà",
                        "L'agent a-t-il confirmé la résolution du problème et proposé une assistance supplémentaire ?", 5, false, "BOTH",
                        java.util.List.of("L'agent a-t-il fourni un service excellent en confirmant que le problème du client a été résolu ? A-t-il proposé une assistance supplémentaire par rapport aux autres plateformes/canaux disponibles ?")),
                new Criterion("EMPATHY", "EMPATHIE", "Voir les choses du point de vue du client",
                        "L'agent a-t-il été à l'écoute et empathique envers le point de vue du client ?", 10, false, "BOTH",
                        java.util.List.of("L'agent a-t-il été à l'écoute et empathique envers le point de vue du client ?")),
                new Criterion("RESOLVE", "RESOLUTION", "Prendre nos responsabilités et résoudre le problème immédiatement",
                        "L'agent a-t-il résolu au premier contact ? Sinon, escalade correcte et communiquée au client ?", 10, false, "BOTH",
                        java.util.List.of("L'agent a-t-il résolu la première fois ? Sinon, la requête a-t-elle été escaladée à la bonne référence ? La référence a-t-elle été communiquée au client ?")),
                new Criterion("KEEPINFO", "COMMUNICATION", "Les tenir informés",
                        "L'agent a-t-il informé proactivement le client sur les délais/SLA de sa réclamation ?", 5, false, "BOTH",
                        java.util.List.of("L'agent a-t-il informé proactivement le client sur le délai (SLA) de sa réclamation ?")),
                // Authentification/Documentation/Clôture — séparées Voice/Chat, la grille réelle
                // ayant une version distincte de chacune selon le canal de l'interaction.
                new Criterion("AUTHCUST", "SECURITE", "Authentification client (Voice)",
                        "L'agent a-t-il vérifié rapidement et avec précision les informations du client (appel) ?", 5, false, "VOICE",
                        java.util.List.of("L'agent a-t-il pris le temps nécessaire pour vérifier avec précision les informations et l'identité du client ?")),
                new Criterion("CHAT_AUTH", "SECURITE", "Authentification client (Chat)",
                        "L'agent a-t-il vérifié rapidement et avec précision les informations du client, sans perdre de temps sur le chat ?", 5, false, "CHAT",
                        java.util.List.of("L'agent a-t-il pris le temps de vérifier avec précision les informations du client et les options disponibles, sans perdre de temps ?")),
                new Criterion("CASEDOC", "DOCUMENTATION", "Documentation du dossier (Voice)",
                        "La description et la chronologie du cas ont-elles été correctement renseignées ?", 2, false, "VOICE",
                        java.util.List.of("Le détail du cas et la chronologie (Timeline) ont-ils été correctement capturés ?")),
                new Criterion("CHAT_DOC", "DOCUMENTATION", "Documentation du dossier (Chat)",
                        "La description et la chronologie du cas ont-elles été correctement renseignées dans le chat ?", 2, false, "CHAT",
                        java.util.List.of("Les détails du cas ont-ils été correctement capturés dans la description et la Timeline du chat ?")),
                new Criterion("CHATCLOSE", "CLOTURE", "Clôture de l'interaction (Voice)",
                        "L'agent a-t-il correctement clôturé l'échange (signature de fin) ?", 5, false, "VOICE",
                        java.util.List.of("L'agent a-t-il correctement signé/clôturé l'interaction avec le client ?")),
                new Criterion("CHAT_CLOSE", "CLOTURE", "Clôture de l'interaction (Chat)",
                        "L'agent a-t-il correctement clôturé le chat (introduction et fin) ?", 5, false, "CHAT",
                        java.util.List.of("L'agent a-t-il utilisé le nom du client en début de chat, puis correctement pris congé/clôturé le chat en fin d'échange ?")),
                new Criterion("CASECOMPLY", "CONFORMITE", "Conformité du cas",
                        "Cohérence entre la demande du client, ce qui a été enregistré, et les outils utilisés.", 1, false, "BOTH",
                        java.util.List.of("Conformité entre ce que le client dit et ce que l'agent enregistre, conformité du cas créé, conformité de l'utilisation des outils nécessaires à la demande du client.")),
                new Criterion("ENGKO", "CONFORMITE", "Engagement Ecobank (catégorie / FCR)",
                        "L'interaction a-t-elle été enregistrée avec la bonne catégorie, sous-catégorie et un FCR correct ? — ÉLIMINATOIRE.", 2, true, "BOTH",
                        java.util.List.of("L'interaction a-t-elle été enregistrée en respectant le bon type, la bonne catégorie et un FCR correct ?"))
        );

        int nextSortOrder = 1;
        for (Criterion c : criteria) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.QualityCriteria WHERE Code = ?", Integer.class, c.code());
            if (exists != null && exists > 0) { nextSortOrder++; continue; }

            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            String finalCode = c.code(), finalSection = c.section(), finalName = c.name(), finalDescription = c.description();
            boolean finalKnockOut = c.knockOut();
            int finalSortOrder = nextSortOrder;
            int finalWeight = c.weight();
            String finalChannel = c.channel();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO dbo.QualityCriteria (Code, Section, Name, Description, Weight, IsKnockOut, SortOrder, Channel) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, finalCode);
                ps.setString(2, finalSection);
                ps.setString(3, finalName);
                ps.setString(4, finalDescription);
                ps.setInt(5, finalWeight);
                ps.setBoolean(6, finalKnockOut);
                ps.setInt(7, finalSortOrder);
                ps.setString(8, finalChannel);
                return ps;
            }, keyHolder);
            Integer criterionId = keyHolder.getKey() != null ? keyHolder.getKey().intValue() : null;
            if (criterionId == null) {
                log.error("[QualityCriteria] Impossible de récupérer l'ID généré pour le critère '{}' — attributs non créés.", c.code());
                nextSortOrder++;
                continue;
            }

            int attrOrder = 1;
            for (String attr : c.attributes()) {
                jdbcTemplate.update(
                        "INSERT INTO dbo.QualityCriterionAttributes (CriterionId, Label, SortOrder) VALUES (?, ?, ?)",
                        criterionId, attr, attrOrder++);
            }
            log.info("[QualityCriteria] Critère '{}' ({}) ajouté (grille QA réelle Ecobank).", c.code(), c.name());
            nextSortOrder++;
        }
    }

    private void seedAdditionalSalesJourneysIfMissing() {
        record Step(String title, java.util.List<String> content) {}
        record Journey(String key, String title, String icon, String colorFrom, String colorTo, String pitch, java.util.List<Step> steps) {}

        java.util.List<Journey> journeys = java.util.List.of(
                new Journey("assurance", "Assurance (prévoyance / emprunteur)", "bi-shield-check", "#00A651", "#7B2FF7",
                        "Protéger le client et ses proches face aux imprévus.", java.util.List.of(
                        new Step("1. Découverte", java.util.List.of(
                                "Avez-vous déjà une couverture en cas d'imprévu (maladie, accident, décès) pour vous ou votre famille ?",
                                "Avez-vous des personnes à charge qui dépendraient de vos revenus en cas de coup dur ?",
                                "Avez-vous un crédit en cours ? Est-il couvert par une assurance emprunteur ?")),
                        new Step("2. Présentation — bénéfices, pas fonctionnalités", java.util.List.of(
                                "Vos proches sont protégés financièrement si quelque chose vous arrive — pas de rupture brutale de revenu pour eux.",
                                "En cas d'hospitalisation ou d'incapacité, une partie de vos charges reste couverte pendant que vous vous rétablissez.",
                                "Si vous avez un crédit, l'assurance emprunteur prend le relais du remboursement en cas de coup dur — vos proches n'héritent pas de la dette.",
                                "Cotisation ajustée à votre situation — vous ne payez pas pour une couverture dont vous n'avez pas besoin.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« Je n'en ai pas besoin, je suis en bonne santé » → C'est justement le bon moment pour souscrire — les tarifs sont plus avantageux tant qu'on n'a pas de problème de santé déclaré.",
                                "« Ça coûte cher » → Comparer au coût réel d'un imprévu non couvert (perte de revenu, dette qui reste, soins non pris en charge) plutôt qu'au coût de la cotisation seule.",
                                "« Je dois en parler avec ma famille » → Tout à fait légitime — proposer un rendez-vous de suivi précis pour finaliser une fois la discussion faite, plutôt que de laisser filer.")),
                        new Step("4. Closing", java.util.List.of(
                                "« Sur la base de ce que vous m'avez dit, voici la formule qui protège le mieux votre situation... » (reformuler le besoin avant de conclure).",
                                "Si accord : programmer immédiatement le rendez-vous de souscription en agence.")))),
                new Journey("epargne", "Épargne / Compte à terme", "bi-piggy-bank-fill", "#0057B8", "#F5A623",
                        "Faire fructifier l'argent qui dort, sans complexité.", java.util.List.of(
                        new Step("1. Découverte", java.util.List.of(
                                "Avez-vous de l'argent de côté que vous n'utilisez pas au quotidien ?",
                                "Avez-vous un projet à moyen terme (achat, études, événement familial) pour lequel vous mettez de l'argent de côté ?",
                                "Votre argent est-il aujourd'hui sur un compte qui ne rapporte rien ?")),
                        new Step("2. Présentation — bénéfices, pas fonctionnalités", java.util.List.of(
                                "Votre argent inutilisé travaille pour vous au lieu de dormir sur un compte courant.",
                                "Vous savez exactement combien vous aurez à l'échéance — pas de surprise, pas de risque de marché.",
                                "Durée adaptée à votre projet — vous choisissez l'échéance qui correspond à votre besoin réel.",
                                "Mise en place rapide, avec l'argent déjà disponible sur votre compte.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« Je préfère garder mon argent disponible » → Proposer de n'immobiliser qu'une partie, en gardant le reste accessible sur le compte courant.",
                                "« Le taux ne m'intéresse pas assez » → Rappeler que le compte courant, lui, ne rapporte rien du tout — même un rendement modeste vaut mieux que zéro.",
                                "« Je dois réfléchir » → Proposer une simulation chiffrée précise à envoyer, avec un rendez-vous de suivi pour en discuter.")),
                        new Step("4. Closing", java.util.List.of(
                                "« Avec le montant que vous m'avez indiqué, voici ce que ça donnerait à l'échéance... » (chiffrer concrètement avant de conclure).",
                                "Si accord : enclencher l'ouverture immédiatement, l'argent étant déjà sur le compte du client.")))));

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Integer maxOrder = jdbcTemplate.queryForObject("SELECT ISNULL(MAX(SortOrder), -1) FROM dbo.SalesJourneys", Integer.class);
        int nextOrder = (maxOrder != null ? maxOrder : -1) + 1;

        for (Journey j : journeys) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.SalesJourneys WHERE JourneyKey = ?", Integer.class, j.key());
            if (exists != null && exists > 0) continue;
            try {
                String stepsJson = mapper.writeValueAsString(j.steps());
                jdbcTemplate.update(
                        "INSERT INTO dbo.SalesJourneys (JourneyKey, Title, Icon, ColorFrom, ColorTo, Pitch, StepsJson, SortOrder, Active) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                        j.key(), j.title(), j.icon(), j.colorFrom(), j.colorTo(), j.pitch(), stepsJson, nextOrder++);
                log.info("[SalesJourneys] Parcours '{}' ajouté (enrichissement additif).", j.key());
            } catch (Exception e) {
                log.error("[SalesJourneys] Erreur sérialisation du parcours '{}' : {}", j.key(), e.getMessage());
            }
        }
    }

    private void seedOutboundKnowledgeBaseIfMissing() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dbo.KnowledgeCategories WHERE Team = 'OUTBOUND'", Integer.class);
        if (count != null && count > 0) return;

        record Cat(String code, String title, String icon, String articleTitle, String articleHtml) {}

        java.util.List<Cat> categories = java.util.List.of(
                new Cat("OUTBOUND_PRET", "Prêts & Crédits", "bi-piggy-bank-fill",
                        "Vue d'ensemble — Prêts & Crédits Ecobank",
                        "<p>Solutions de financement proposées par les conseillers Outbound : prêt personnel, " +
                        "crédit auto, crédit consommation.</p>" +
                        "<p><strong>Points clés à retenir :</strong></p>" +
                        "<ul>" +
                        "<li>Toujours partir du besoin réel du client (projet, montant, durée souhaitée) avant de proposer un produit.</li>" +
                        "<li>Le dossier s'appuie sur les pièces déjà disponibles via le compte existant du client — pas de paperasse superflue à annoncer.</li>" +
                        "<li>Mettre en avant la mensualité plutôt que le taux nominal — c'est ce qui parle concrètement au client.</li>" +
                        "<li>Toute simulation chiffrée précise (taux, durée, mensualité exacte) doit être confirmée en agence — ne jamais s'engager sur un chiffre définitif au téléphone.</li>" +
                        "</ul>" +
                        "<p>Voir aussi le parcours de vente interactif dédié dans l'onglet \"Parcours de vente\" du tableau de bord Outbound pour l'argumentaire complet (découverte, objections, closing).</p>"),
                new Cat("OUTBOUND_DIGITAL", "Produits Digitaux", "bi-phone-fill",
                        "Vue d'ensemble — Produits digitaux Ecobank",
                        "<p>Panorama des produits digitaux à proposer en Outbound : Ecobank Mobile App, CASHXPRESS, " +
                        "Mobile Money (MMH), Carte prépayée/ATM, e-Statement.</p>" +
                        "<p><strong>Ecobank Mobile App</strong> — consultation de solde, virements et paiements de factures 24h/24, sans agence.</p>" +
                        "<p><strong>CASHXPRESS</strong> — retrait et transfert d'argent sans carte, avec un code à usage unique, y compris vers un bénéficiaire sans compte.</p>" +
                        "<p><strong>Mobile Money (MMH)</strong> — passerelle directe entre le compte bancaire et le portefeuille mobile du client.</p>" +
                        "<p><strong>Carte prépayée / ATM</strong> — paiement et retrait sans compte courant obligatoire, budget maîtrisé par rechargement.</p>" +
                        "<p><strong>e-Statement</strong> — relevé de compte automatique par email, gratuit, activable immédiatement.</p>" +
                        "<p>Voir le parcours de vente interactif de chaque produit dans l'onglet \"Parcours de vente\" pour l'argumentaire détaillé (découverte, objections, closing).</p>"),
                new Cat("OUTBOUND_ASSURANCE", "Assurance", "bi-shield-check",
                        "Vue d'ensemble — Assurance Ecobank",
                        "<p>Offres d'assurance proposées en accompagnement des produits bancaires : protection du compte, " +
                        "assurance emprunteur associée aux crédits, couverture des moyens de paiement.</p>" +
                        "<p><strong>Points clés à retenir :</strong></p>" +
                        "<ul>" +
                        "<li>L'assurance se propose en complément d'un besoin déjà identifié (ex. souscription d'un crédit), jamais isolément sans contexte.</li>" +
                        "<li>Toujours présenter clairement ce qui est couvert ET ce qui ne l'est pas — la transparence évite les réclamations ultérieures.</li>" +
                        "<li>Le client doit recevoir les conditions générales avant toute confirmation d'adhésion.</li>" +
                        "</ul>" +
                        "<p>Toute question sur un cas de garantie précis doit être orientée vers le service Assurance dédié plutôt que traitée au jugé.</p>")
        );

        for (Cat c : categories) {
            jdbcTemplate.update(
                    "INSERT INTO dbo.KnowledgeCategories (Code, Title, Icon, SortOrder, Team) VALUES (?, ?, ?, ?, 'OUTBOUND')",
                    c.code(), c.title(), c.icon(), 0);
            Integer categoryId = jdbcTemplate.queryForObject(
                    "SELECT CategoryId FROM dbo.KnowledgeCategories WHERE Code = ?", Integer.class, c.code());
            jdbcTemplate.update(
                    "INSERT INTO dbo.KnowledgeArticles (CategoryId, Title, ContentHtml, SortOrder) VALUES (?, ?, ?, 0)",
                    categoryId, c.articleTitle(), c.articleHtml());
        }
        log.info("[Outbound] 3 rubriques Knowledge Base créées (Prêts & Crédits, Produits Digitaux, Assurance).");
    }

    private void seedSalesJourneysIfEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.SalesJourneys", Integer.class);
        if (count != null && count > 0) return;

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        record Step(String title, java.util.List<String> content) {}
        record Journey(String key, String title, String icon, String colorFrom, String colorTo, String pitch, java.util.List<Step> steps) {}

        java.util.List<Journey> journeys = java.util.List.of(
                new Journey("ecobank-mobile", "Ecobank Mobile App", "bi-phone-fill", "#0057B8", "#00A651",
                        "L'app pour gérer son compte sans se déplacer en agence.", java.util.List.of(
                        new Step("1. Découverte", java.util.List.of(
                                "Combien de fois par mois vous déplacez-vous en agence pour consulter votre solde ou faire un virement ?",
                                "Avez-vous déjà eu besoin de faire une opération un dimanche ou un jour férié ?",
                                "Utilisez-vous déjà une application bancaire, chez nous ou ailleurs ?")),
                        new Step("2. Présentation — bénéfices, pas fonctionnalités", java.util.List.of(
                                "Consultez votre solde et vos dernières opérations en 10 secondes, 24h/24 — plus besoin de faire la queue pour un simple contrôle.",
                                "Faites vos virements et payez vos factures depuis votre canapé — gain de temps réel, surtout en fin de mois.",
                                "Recevez une alerte immédiate à chaque mouvement sur le compte — sécurité et tranquillité d'esprit.",
                                "Gratuite, disponible sur Android et iOS, activable en quelques minutes avec votre numéro de compte.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« Je n'ai pas de smartphone performant » → L'appli est légère, elle fonctionne sur tous les smartphones récents, même d'entrée de gamme.",
                                "« J'ai peur de la sécurité en ligne » → Chaque connexion est protégée par un code confidentiel + une vérification supplémentaire (OTP) à chaque opération sensible.",
                                "« Je préfère aller en agence, c'est plus sûr » → L'appli ne remplace pas l'agence, elle évite les déplacements pour les opérations simples.")),
                        new Step("4. Closing", java.util.List.of(
                                "« Je peux vous accompagner maintenant pour l'activer, ça prend 3 minutes — vous voulez qu'on le fasse ensemble tout de suite ? »",
                                "Si hésitation : proposer d'envoyer le lien de téléchargement par SMS et reprendre contact dans 2-3 jours.")))),
                new Journey("cashxpress", "CASHXPRESS", "bi-cash-coin", "#F5A623", "#F72585",
                        "Retrait et transfert d'argent sans carte, avec un simple code.", java.util.List.of(
                        new Step("1. Découverte", java.util.List.of(
                                "Vous arrive-t-il d'envoyer de l'argent à un proche qui n'a pas de compte bancaire ?",
                                "Avez-vous déjà été bloqué parce que vous n'aviez pas votre carte sur vous ?",
                                "Connaissez-vous quelqu'un dans votre entourage qui a besoin de recevoir de l'argent rapidement, sans compte ?")),
                        new Step("2. Présentation — bénéfices, pas fonctionnalités", java.util.List.of(
                                "Envoyez de l'argent à n'importe qui, même sans compte bancaire — la personne le retire avec juste un code.",
                                "Utile en dépannage : plus besoin d'avoir sa carte sur soi pour retirer, le code suffit.",
                                "Rapide à mettre en place depuis l'appli ou en agence, disponible immédiatement pour le bénéficiaire.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« C'est compliqué à utiliser » → Trois étapes seulement : montant, numéro du bénéficiaire, code généré automatiquement.",
                                "« Les frais sont trop élevés » → Comparer avec le coût d'un déplacement ou d'un envoi par un tiers informel.",
                                "« Le code peut être volé » → Le code est à usage unique et expire après un délai donné.")),
                        new Step("4. Closing", java.util.List.of(
                                "« La prochaine fois que vous devez dépanner quelqu'un rapidement, pensez-y — je peux vous montrer comment ça marche en 2 minutes maintenant. »")))),
                new Journey("mobile-money", "Mobile Money (MMH)", "bi-wallet2", "#00A651", "#0057B8",
                        "Le porte-monnaie mobile relié directement au compte bancaire.", java.util.List.of(
                        new Step("1. Découverte", java.util.List.of(
                                "Utilisez-vous déjà un service de mobile money pour vos achats du quotidien ?",
                                "Savez-vous que vous pouvez relier votre compte bancaire directement à votre mobile money ?",
                                "Combien de temps passez-vous à faire la queue pour recharger votre compte mobile money ?")),
                        new Step("2. Présentation — bénéfices, pas fonctionnalités", java.util.List.of(
                                "Transférez de l'argent entre votre compte bancaire et votre portefeuille mobile en quelques secondes.",
                                "Payez vos achats du quotidien directement depuis votre compte, sans retirer d'espèces au préalable.",
                                "Une seule interface pour gérer votre argent, que ce soit sur votre compte ou dans votre mobile money.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« J'ai déjà un mobile money qui marche bien » → On le connecte à votre compte, vous ne changez rien à vos habitudes.",
                                "« Il y a des frais de transfert » → Comparer avec le coût cumulé des déplacements chez un agent pour recharger manuellement.",
                                "« Je ne fais pas confiance aux transferts numériques » → Chaque transfert est confirmé par un code de sécurité.")),
                        new Step("4. Closing", java.util.List.of(
                                "« On peut l'activer ensemble maintenant, ça ne prend que quelques minutes et ça change votre quotidien immédiatement. »")))),
                new Journey("carte-prepayee", "Carte prépayée / Carte ATM", "bi-credit-card-fill", "#7B2FF7", "#F72585",
                        "Une carte simple, sans compte courant obligatoire, pour payer et retirer partout.", java.util.List.of(
                        new Step("1. Découverte", java.util.List.of(
                                "Avez-vous déjà une carte pour payer vos achats ou retirer de l'argent ?",
                                "Voyagez-vous parfois, ou avez-vous besoin de payer en ligne ?",
                                "Avez-vous un compte courant, ou cherchez-vous une solution plus simple pour gérer votre budget ?")),
                        new Step("2. Présentation — bénéfices, pas fonctionnalités", java.util.List.of(
                                "Payez partout, en magasin comme en ligne, sans avoir besoin d'un compte courant complet.",
                                "Rechargez uniquement le montant que vous voulez dépenser — un excellent outil pour maîtriser son budget.",
                                "Acceptée dans le réseau international — utile pour les achats en ligne ou les voyages.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« Je n'ai pas besoin d'une carte, je paie en espèces » → Pratique pour les paiements en ligne ou en cas d'urgence.",
                                "« J'ai peur de perdre le contrôle de mes dépenses » → Vous ne pouvez dépenser que ce que vous avez rechargé.",
                                "« Les cartes coûtent cher » → Pas besoin d'un compte courant associé — coût d'entrée réduit.")),
                        new Step("4. Closing", java.util.List.of(
                                "« Je peux lancer votre demande maintenant, la carte vous sera livrée sous quelques jours — on démarre ? »")))),
                new Journey("e-statement", "e-Statement", "bi-file-earmark-text-fill", "#0057B8", "#7B2FF7",
                        "Le relevé de compte envoyé automatiquement par email, sans passage en agence.", java.util.List.of(
                        new Step("1. Découverte", java.util.List.of(
                                "Comment récupérez-vous actuellement vos relevés de compte ?",
                                "Avez-vous déjà eu besoin d'un relevé en urgence (visa, dossier de prêt...) sans l'avoir sous la main ?",
                                "Avez-vous une adresse email que vous consultez régulièrement ?")),
                        new Step("2. Présentation — bénéfices, pas fonctionnalités", java.util.List.of(
                                "Recevez votre relevé automatiquement par email chaque mois — plus besoin de vous déplacer.",
                                "Retrouvez facilement un ancien relevé dans votre boîte mail pour un dossier administratif ou une demande de visa.",
                                "Gratuit et activable immédiatement avec l'adresse email déjà enregistrée sur votre compte.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« Je préfère le papier » → Le e-Statement peut être imprimé à tout moment.",
                                "« Est-ce que c'est sécurisé par email ? » → Le document est protégé, seul un code lié à votre compte permet de l'ouvrir.",
                                "« Je n'ai pas d'email » → C'est le moment idéal pour en créer un.")),
                        new Step("4. Closing", java.util.List.of(
                                "« C'est gratuit et ça prend 30 secondes à activer maintenant, avec l'email que vous avez déjà chez nous — on active ? »")))),
                new Journey("pret-credit", "Prêts & Crédits", "bi-piggy-bank-fill", "#F72585", "#0057B8",
                        "Financer un projet personnel, un véhicule, ou des besoins ponctuels.", java.util.List.of(
                        new Step("1. Découverte — la plus importante de tout l'appel", java.util.List.of(
                                "Avez-vous un projet en cours (véhicule, travaux, événement familial...) que vous financez déjà ou envisagez de financer ?",
                                "Comment financez-vous habituellement vos besoins ponctuels importants ?",
                                "Quel serait le montant qui vous faciliterait la vie sur ce projet ?",
                                "Sur quelle durée seriez-vous à l'aise pour rembourser ?")),
                        new Step("2. Présentation — adaptée à ce qui a été découvert", java.util.List.of(
                                "Ne jamais présenter un prêt générique — reformuler le besoin exprimé puis présenter la solution qui y répond précisément.",
                                "Mettre en avant la simplicité du dossier (pièces déjà disponibles en agence via le compte existant).",
                                "Insister sur la mensualité plutôt que sur le taux — c'est ce qui parle concrètement au client.")),
                        new Step("3. Objections fréquentes", java.util.List.of(
                                "« Je ne veux pas m'endetter » → Recentrer sur le projet précis évoqué en découverte.",
                                "« Le taux est trop élevé » → Comparer au coût de reporter le projet ; rappeler la transparence totale sur le coût total du crédit.",
                                "« Je dois réfléchir » → Ne jamais insister lourdement — proposer un rendez-vous de suivi précis.")),
                        new Step("4. Closing", java.util.List.of(
                                "« Sur la base de ce que vous m'avez dit, voici ce que je peux vous proposer... » (reformuler le besoin avant de conclure).",
                                "Si accord : enclencher immédiatement la prise de rendez-vous en agence pour la signature.",
                                "Si hésitation : programmer un rendez-vous de rappel plutôt que de laisser filer le contact.")))));

        int order = 0;
        for (Journey j : journeys) {
            try {
                String stepsJson = mapper.writeValueAsString(j.steps());
                jdbcTemplate.update(
                        "INSERT INTO dbo.SalesJourneys (JourneyKey, Title, Icon, ColorFrom, ColorTo, Pitch, StepsJson, SortOrder, Active) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                        j.key(), j.title(), j.icon(), j.colorFrom(), j.colorTo(), j.pitch(), stepsJson, order++);
            } catch (Exception e) {
                log.error("[SalesJourneys] Erreur sérialisation du parcours '{}' : {}", j.key(), e.getMessage());
            }
        }
        log.info("[SalesJourneys] {} parcours de vente créés (migration depuis sales-journeys-data.js).", journeys.size());
    }

    private void seedGameDefinitionsIfEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.GameDefinitions", Integer.class);
        if (count != null && count > 0) return;
        String sql = "INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, "quiz-eclair", "MCQ_STANDARD", "Quiz Éclair", "Un quiz rapide et efficace pour réviser tous les thèmes en quelques minutes.", "bi-lightning-charge", "#0057B8", "#00A651", "{\"questionCount\":8}", 1);
        jdbcTemplate.update(sql, "millions-ecobank", "MCQ_LADDER", "Qui Veut Gagner des Millions Ecobank", "Grimpez les paliers de difficulté avec vos deux jokers — sécurisez vos points ou tentez le tout pour le tout.", "bi-cash-coin", "#F5A623", "#8B5E00", "{\"questionCount\":10}", 2);
        jdbcTemplate.update(sql, "vrai-faux-chrono", "TRUE_FALSE_RAPID", "Vrai ou Faux Chrono", "Répondez vrai ou faux le plus vite possible et enchaînez les combos avant la fin du chrono.", "bi-toggle2-on", "#7B2FF7", "#F72585", "{\"timeLimitSeconds\":30}", 3);
        jdbcTemplate.update(sql, "roue-fortune", "WHEEL", "La Roue de la Fortune Ecobank", "Lancez la roue pour choisir votre thème, puis répondez pour multiplier vos gains.", "bi-disc", "#00A651", "#0057B8", "{}", 4);
        jdbcTemplate.update(sql, "joligo-ecobank", "WORD_GUESS", "JoliGo Ecobank", "Devinez le terme bancaire lettre par lettre à partir de sa définition.", "bi-alphabet-uppercase", "#D93025", "#F5A623", "{\"maxAttempts\":6}", 5);
        jdbcTemplate.update(sql, "duel-chrono", "MCQ_DUEL", "Duel Chrono", "Battez le score à battre avant la fin du temps imparti — face à vous-même ou vos collègues.", "bi-people-fill", "#0057B8", "#7B2FF7", "{\"timeLimitSeconds\":60}", 6);
        jdbcTemplate.update(sql, "memoire-ecobank", "MEMORY", "Mémoire Ecobank", "Retrouvez les paires terme / définition en un minimum de coups.", "bi-grid-3x3-gap-fill", "#00A651", "#7B2FF7", "{\"pairCount\":6}", 7);
        jdbcTemplate.update(sql, "pendu-bancaire", "HANGMAN", "Le Pendu Bancaire", "Devinez le mot avant d'épuiser vos essais.", "bi-emoji-frown", "#6b7280", "#D93025", "{\"maxWrong\":6}", 8);
        jdbcTemplate.update(sql, "chrono-challenge", "MCQ_SURVIVAL", "Chrono Challenge 60s", "Enchaînez un maximum de bonnes réponses en 60 secondes — la difficulté augmente au fil du jeu.", "bi-stopwatch", "#F72585", "#7B2FF7", "{\"timeLimitSeconds\":60}", 9);
        jdbcTemplate.update(sql, "parcours-client", "PROCESS_ORDER", "Puzzle du Parcours Client", "Remettez dans le bon ordre les vraies étapes d'un parcours de traitement Ecobank.", "bi-signpost-split", "#0057B8", "#00A651", "{}", 10);
        log.warn("⚠ [SCHEMA BOOTSTRAP] 10 jeux créés automatiquement (Centre de jeux).");
    }

    /** Crée le vocabulaire bancaire par défaut si la table vient d'être créée. */
    private void seedWordTermsIfEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.WordTerms", Integer.class);
        if (count != null && count > 0) return;
        String sql = "INSERT INTO dbo.WordTerms (Term, Definition, Category) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, "RIB", "Relevé d'Identité Bancaire — document qui identifie un compte bancaire (banque, guichet, numéro de compte, clé).", "COMPTE");
        jdbcTemplate.update(sql, "OTP", "One Time Password — code à usage unique envoyé pour valider une connexion ou une transaction sécurisée.", "CONNEXION PRODUITS DIGITAUX");
        jdbcTemplate.update(sql, "DAB", "Distributeur Automatique de Billets — permet de retirer de l'argent sans passer par un caissier.", "CARTE ATM");
        jdbcTemplate.update(sql, "GAB", "Guichet Automatique Bancaire — équivalent du DAB, parfois utilisé pour désigner des opérations plus larges (dépôt, consultation).", "CARTE ATM");
        jdbcTemplate.update(sql, "TPE", "Terminal de Paiement Électronique — appareil utilisé en magasin pour régler un achat par carte bancaire.", "CARTE ATM");
        jdbcTemplate.update(sql, "KYC", "Know Your Customer — processus de vérification de l'identité du client, obligatoire avant certaines opérations bancaires.", "AUTRE");
        jdbcTemplate.update(sql, "VBV", "Verified by Visa — sécurité supplémentaire (code ou OTP) demandée lors d'un paiement en ligne par carte Visa.", "CARTE ATM");
        jdbcTemplate.update(sql, "SWIFT", "Réseau international sécurisé de messagerie interbancaire ; chaque banque possède un code SWIFT/BIC qui l'identifie pour les virements internationaux.", "TRANSFERT");
        jdbcTemplate.update(sql, "CASHXPRESS", "Carte prépayée permettant de retirer et de payer sans nécessiter un compte bancaire classique.", "CASHXPRESS");
        jdbcTemplate.update(sql, "MMH", "Mobile Money Hub — passerelle qui relie un compte bancaire à un portefeuille mobile money.", "MMH");
        jdbcTemplate.update(sql, "B2W", "Bank to Wallet — transfert d'argent d'un compte bancaire vers un portefeuille mobile money.", "MMH");
        jdbcTemplate.update(sql, "W2B", "Wallet to Bank — transfert d'argent d'un portefeuille mobile money vers un compte bancaire.", "MMH");
        jdbcTemplate.update(sql, "GIM UEMOA", "Groupement Interbancaire Monétique de l'UEMOA — réseau régional permettant d'utiliser sa carte dans les distributeurs d'autres banques de la zone.", "CARTE ATM");
        jdbcTemplate.update(sql, "DAT", "Dépôt à Terme — placement d'argent bloqué pendant une durée fixée, généralement rémunéré par un taux d'intérêt.", "COMPTE");
        jdbcTemplate.update(sql, "ECOBANK ONLINE", "Service de banque à distance accessible depuis un navigateur web.", "CONNEXION PRODUITS DIGITAUX");
        jdbcTemplate.update(sql, "ECOBANK MOBILE APP", "Application mobile bancaire permettant de consulter ses comptes et d'effectuer des opérations depuis un smartphone.", "CONNEXION PRODUITS DIGITAUX");
        jdbcTemplate.update(sql, "ECOBANK PAY", "Solution de paiement mobile proposée par la banque.", "CONNEXION PRODUITS DIGITAUX");
        jdbcTemplate.update(sql, "E-ALERT", "Service de notification automatique (SMS ou email) des mouvements sur un compte.", "CONNEXION PRODUITS DIGITAUX");
        jdbcTemplate.update(sql, "E-STATEMENT", "Relevé de compte transmis électroniquement plutôt qu'en version papier.", "CONNEXION PRODUITS DIGITAUX");
        jdbcTemplate.update(sql, "RAPIDTRANSFER", "Service de transfert d'argent rapide entre particuliers.", "TRANSFERT");
        jdbcTemplate.update(sql, "CHEQUE", "Moyen de paiement écrit ordonnant à la banque de verser une somme déterminée à un bénéficiaire.", "CHEQUE");
        jdbcTemplate.update(sql, "CHEQUIER", "Carnet contenant plusieurs chèques remis au titulaire d'un compte.", "CHEQUE");
        jdbcTemplate.update(sql, "OPPOSITION", "Démarche permettant de bloquer l'utilisation d'un chèque ou d'une carte, généralement en cas de perte ou de vol.", "CARTE ATM");
        jdbcTemplate.update(sql, "PROCURATION", "Autorisation donnée par le titulaire d'un compte à une autre personne pour effectuer des opérations en son nom.", "COMPTE");
        jdbcTemplate.update(sql, "COMPTE DORMANT", "Compte resté inactif (sans opération) pendant une longue période, généralement soumis à des restrictions.", "COMPTE");
        jdbcTemplate.update(sql, "SUCCESSION", "Ensemble des démarches bancaires réalisées après le décès du titulaire d'un compte.", "COMPTE");
        jdbcTemplate.update(sql, "VIREMENT", "Opération consistant à transférer une somme d'argent d'un compte vers un autre.", "TRANSFERT");
        jdbcTemplate.update(sql, "PRELEVEMENT", "Paiement automatique, récurrent ou ponctuel, autorisé par le titulaire du compte au profit d'un tiers.", "COMPTE");
        log.warn("⚠ [SCHEMA BOOTSTRAP] {} terme(s) de vocabulaire bancaire créé(s) automatiquement.", 28);
    }

    /** Crée les questions de départ (banque de questions) si la table vient d'être créée. */
    private void seedQuizQuestionsIfEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.QuizQuestions", Integer.class);
        if (count != null && count > 0) return;
        String sql = "INSERT INTO dbo.QuizQuestions (QuestionText, Type, Difficulty, Category, OptionsJson, CorrectOptionIndex, Explanation, Points) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, "Avant de communiquer une information confidentielle (solde, coordonnées...), que doit toujours faire l'agent ?", "MCQ", "EASY", "AUTRE", "[\"Authentifier le client (identité vérifiée)\",\"Lui demander son numéro de téléphone uniquement\",\"Rien, on peut répondre directement\",\"Transférer l'appel sans vérification\"]", 0, "L'authentification du client est une étape obligatoire avant toute divulgation d'information sur un compte.", 10);
        jdbcTemplate.update(sql, "Que signifie l'acronyme RIB ?", "MCQ", "EASY", "COMPTE", "[\"Relevé d'Identité Bancaire\",\"Registre Interne Bancaire\",\"Rapport d'Information Bancaire\",\"Réserve Interbancaire\"]", 0, "Le RIB identifie un compte bancaire précis (banque, guichet, numéro, clé).", 10);
        jdbcTemplate.update(sql, "Que signifie OTP dans le contexte d'une transaction sécurisée ?", "MCQ", "EASY", "CONNEXION PRODUITS DIGITAUX", "[\"One Time Password (code à usage unique)\",\"Online Transfer Protocol\",\"Official Transaction Paper\",\"Open Terminal Payment\"]", 0, "L'OTP est un code envoyé une seule fois pour valider une opération sensible.", 10);
        jdbcTemplate.update(sql, "Un client signale la perte de sa carte bancaire. Quelle est la toute première action à effectuer ?", "MCQ", "MEDIUM", "CARTE ATM", "[\"Faire opposition / bloquer immédiatement la carte\",\"Lui demander de rappeler plus tard\",\"Attendre la confirmation par écrit\",\"Ne rien faire, la carte se bloque automatiquement\"]", 0, "Le blocage immédiat limite le risque d'utilisation frauduleuse pendant que le dossier est traité.", 15);
        jdbcTemplate.update(sql, "Que désigne un \"compte dormant\" ?", "MCQ", "MEDIUM", "COMPTE", "[\"Un compte resté inactif pendant une longue période\",\"Un compte tout juste ouvert\",\"Un compte réservé aux mineurs\",\"Un compte en devise étrangère\"]", 0, "Un compte dormant n'a enregistré aucune opération depuis longtemps ; il est généralement soumis à des restrictions.", 15);
        jdbcTemplate.update(sql, "Quel est le rôle du code SWIFT/BIC dans un virement international ?", "MCQ", "HARD", "TRANSFERT", "[\"Identifier la banque destinataire dans le réseau interbancaire international\",\"Identifier uniquement le client\",\"Servir de mot de passe pour la banque en ligne\",\"Remplacer le RIB\"]", 0, "Le code SWIFT/BIC identifie une banque précise pour acheminer correctement un virement international.", 20);
        jdbcTemplate.update(sql, "Un client conteste un prélèvement qu'il ne reconnaît pas. Quelle est la bonne attitude ?", "MCQ", "MEDIUM", "COMPTE", "[\"Enregistrer la contestation et l'orienter vers le circuit de traitement dédié\",\"Lui dire que rien ne peut être fait\",\"Rembourser immédiatement sans vérification\",\"Ignorer la demande si le montant est faible\"]", 0, "Toute contestation doit être tracée et suivre le circuit de vérification prévu, quel que soit le montant.", 15);
        jdbcTemplate.update(sql, "Que permet le service B2W (Bank to Wallet) ?", "MCQ", "MEDIUM", "MMH", "[\"Transférer de l'argent d'un compte bancaire vers un portefeuille mobile money\",\"Payer une facture d'électricité\",\"Ouvrir un compte bancaire\",\"Commander une carte bancaire\"]", 0, "B2W déplace des fonds du compte bancaire vers un portefeuille mobile money.", 15);
        jdbcTemplate.update(sql, "Qu'est-ce qu'une carte CASHXPRESS ?", "MCQ", "EASY", "CASHXPRESS", "[\"Une carte prépayée utilisable sans compte bancaire classique\",\"Une carte réservée aux entreprises\",\"Un chéquier électronique\",\"Un service de virement uniquement\"]", 0, "CASHXPRESS est une carte prépayée, indépendante d'un compte bancaire traditionnel.", 10);
        jdbcTemplate.update(sql, "Quel est l'objectif principal du KYC (\"Know Your Customer\") ?", "MCQ", "MEDIUM", "AUTRE", "[\"Vérifier l'identité du client avant certaines opérations\",\"Calculer les intérêts d'un compte\",\"Envoyer les relevés de compte\",\"Gérer le planning des agents\"]", 0, "Le KYC est un processus de vérification d'identité, pilier de la conformité bancaire.", 15);
        jdbcTemplate.update(sql, "Un client insiste pour obtenir une information sur le compte d'un tiers. Que doit faire l'agent ?", "MCQ", "EASY", "AUTRE", "[\"Refuser poliment — le secret bancaire protège les informations d'un tiers\",\"Donner l'information si le client insiste\",\"Transférer l'appel sans explication\",\"Donner une partie de l'information seulement\"]", 0, "Le secret bancaire interdit de communiquer des informations sur le compte d'une personne à un tiers non autorisé.", 10);
        jdbcTemplate.update(sql, "Que signifie l'acronyme TPE ?", "MCQ", "EASY", "CARTE ATM", "[\"Terminal de Paiement Électronique\",\"Traitement Prioritaire Express\",\"Transfert Postal Électronique\",\"Ticket de Paiement Externe\"]", 0, "Le TPE est l'appareil utilisé en magasin pour régler un achat par carte.", 10);
        jdbcTemplate.update(sql, "Vrai ou faux : un virement et un prélèvement désignent la même opération.", "TRUE_FALSE", "EASY", "TRANSFERT", "[\"Vrai\",\"Faux\"]", 1, "Un virement est initié par le titulaire du compte débité ; un prélèvement est autorisé à l'avance au profit d'un tiers qui déclenche l'opération.", 10);
        jdbcTemplate.update(sql, "Vrai ou faux : il faut toujours vérifier l'identité d'un client avant de traiter une demande sensible, même s'il semble pressé.", "TRUE_FALSE", "EASY", "AUTRE", "[\"Vrai\",\"Faux\"]", 0, "L'urgence ressentie par le client ne dispense jamais de la vérification d'identité.", 10);
        jdbcTemplate.update(sql, "Vrai ou faux : une procuration permet à une autre personne d'effectuer des opérations sur le compte du titulaire.", "TRUE_FALSE", "MEDIUM", "COMPTE", "[\"Vrai\",\"Faux\"]", 0, "La procuration autorise une personne désignée à agir sur le compte au nom du titulaire.", 10);
        jdbcTemplate.update(sql, "Vrai ou faux : un Dépôt à Terme (DAT) peut être retiré à tout moment sans aucune condition.", "TRUE_FALSE", "MEDIUM", "COMPTE", "[\"Vrai\",\"Faux\"]", 1, "Un DAT est bloqué pendant une durée fixée à l'avance ; un retrait anticipé implique généralement des conditions particulières.", 15);
        jdbcTemplate.update(sql, "Vrai ou faux : le code VBV (Verified by Visa) sert à sécuriser un paiement en ligne.", "TRUE_FALSE", "MEDIUM", "CARTE ATM", "[\"Vrai\",\"Faux\"]", 0, "VBV ajoute une étape de vérification (code/OTP) lors d'un paiement en ligne par carte Visa.", 10);
        jdbcTemplate.update(sql, "Que faire en priorité si un client déclare une transaction qu'il ne reconnaît pas sur son compte ?", "MCQ", "HARD", "CARTE ATM", "[\"Sécuriser le moyen de paiement concerné puis ouvrir une contestation tracée\",\"Lui conseiller d'appeler sa banque plus tard\",\"Ignorer si le montant est faible\",\"Fermer le compte immédiatement\"]", 0, "La priorité est d'éviter tout dommage supplémentaire (blocage si nécessaire) puis de tracer la contestation pour investigation.", 20);
        jdbcTemplate.update(sql, "Qu'est-ce qu'un e-Statement ?", "MCQ", "EASY", "CONNEXION PRODUITS DIGITAUX", "[\"Un relevé de compte transmis électroniquement\",\"Un extrait de casier judiciaire\",\"Une attestation de domicile\",\"Un formulaire de procuration\"]", 0, "L'e-Statement remplace l'envoi papier du relevé de compte par une version électronique.", 10);
        jdbcTemplate.update(sql, "Quelle attitude adopter face à un client mécontent qui hausse le ton ?", "MCQ", "MEDIUM", "AUTRE", "[\"Rester calme, reformuler sa demande et se concentrer sur la solution\",\"Hausser le ton également pour se faire entendre\",\"Raccrocher immédiatement\",\"Le faire attendre longtemps sans explication\"]", 0, "Le calme et l'écoute active permettent de désamorcer la tension et d'avancer vers une solution.", 15);
        log.warn("⚠ [SCHEMA BOOTSTRAP] {} question(s) de départ créée(s) automatiquement.", 20);
    }

    /**
     * Sème un SlaTarget(team, type, thresholdHours) par motif du référentiel RCC360
     * (RccMotifCatalog) qui a un SLA défini dans le document source — pour que le tableau
     * "Temps moyen de traitement" et les seuils personnalisés soient utilisables dès le
     * démarrage, sans étape manuelle. Additif et idempotent : ne touche jamais une ligne déjà
     * présente, y compris si QA/Admin l'a modifiée depuis (INSERT ... WHERE NOT EXISTS).
     * Les motifs sans SLA défini dans le document (slaHours == null) sont volontairement
     * ignorés ici — le seuil global (WorkflowService.DEFAULT_SLA_THRESHOLD_HOURS) s'applique
     * pour eux tant qu'aucune règle n'est ajoutée manuellement.
     */
    private void seedMotifSlaTargetsIfMissing() {
        int inserted = 0;
        for (RccMotifCatalog.MotifEntry entry : RccMotifCatalog.ENTRIES) {
            if (entry.slaHours() == null) continue;
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.SlaTargets WHERE UPPER(Team) = ? AND UPPER(Type) = ?",
                    Integer.class, entry.teamCode().toUpperCase(), entry.code().toUpperCase());
            if (count != null && count > 0) continue;
            jdbcTemplate.update(
                    "INSERT INTO dbo.SlaTargets (Team, Type, ThresholdHours) VALUES (?, ?, ?)",
                    entry.teamCode().toUpperCase(), entry.code().toUpperCase(), entry.slaHours());
            inserted++;
        }
        if (inserted > 0) {
            log.warn("⚠ [SCHEMA BOOTSTRAP] {} seuil(s) SLA du référentiel RCC360 semé(s) automatiquement.", inserted);
        }
    }

    private void createIfMissing(String tableName, String createSql) {
        if (tableExists(tableName)) {
            return;
        }
        log.warn("⚠ [SCHEMA BOOTSTRAP] Création de dbo.{} (table absente).", tableName);
        jdbcTemplate.execute(createSql);
    }

    /**
     * Retourne le type SQL exact (avec précision/échelle pour les types numériques et
     * longueur pour les types texte) de la colonne dbo.USERS.ID telle qu'elle existe
     * réellement sur la base cible, afin que toute nouvelle FK vers USERS(ID) soit
     * garantie type-compatible — quel que soit le type réellement provisionné
     * (BIGINT, INT, NUMERIC(x,y), etc.), plutôt que de supposer BIGINT en dur.
     * Si dbo.USERS n'existe pas encore (première installation), BIGINT est utilisé
     * par défaut car c'est le type avec lequel USERS est créée plus bas dans run().
     */
    private String resolveUsersIdSqlType() {
        String sql = """
                SELECT t.name AS type_name, c.max_length, c.precision, c.scale
                FROM sys.columns c
                JOIN sys.types t ON c.user_type_id = t.user_type_id
                WHERE c.object_id = OBJECT_ID('dbo.USERS') AND c.name = 'ID'
                """;
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (!rs.next()) {
                    log.warn("⚠ [SCHEMA BOOTSTRAP] dbo.USERS introuvable, BIGINT utilisé par défaut pour les FK vers USERS(ID).");
                    return "BIGINT";
                }
                String typeName = rs.getString("type_name").toUpperCase();
                int precision = rs.getInt("precision");
                int scale = rs.getInt("scale");
                String resolved = switch (typeName) {
                    case "NUMERIC", "DECIMAL" -> typeName + "(" + precision + "," + scale + ")";
                    default -> typeName; // BIGINT, INT, SMALLINT, TINYINT, etc.
                };
                log.warn("⚠ [SCHEMA BOOTSTRAP] Type détecté pour dbo.USERS.ID : {} (utilisé pour ShiftSwapRequests).", resolved);
                return resolved;
            });
        } catch (Exception e) {
            log.error("⚠ [SCHEMA BOOTSTRAP] Impossible de détecter le type de dbo.USERS.ID, BIGINT utilisé par défaut.", e);
            return "BIGINT";
        }
    }

    /**
     * 2 modèles de campagne réels de départ pour l'onglet "Campagne" agent (Service Digital /
     * Télévente) — repris de vrais formulaires de campagne fournis par l'utilisateur (captures
     * Microsoft Forms) : "Prêt Scolaire & Conso" (Télévente) et "Carte Bancaire" (Digital).
     * Idempotent (recherché par nom), ne s'exécute que si dbo.Campaigns existe déjà ET qu'au
     * moins un utilisateur est en base (sert de CreatedByUserId — sinon retenté au prochain
     * démarrage, ex. tout premier déploiement avant la création du compte admin).
     */
    private void seedOutboundCampaignTemplatesIfMissing() {
        if (!tableExists("Campaigns") || !columnExists("Campaigns", "TargetService")) return;
        Long creatorId;
        try {
            creatorId = jdbcTemplate.queryForObject("SELECT TOP 1 ID FROM dbo.USERS ORDER BY ID", Long.class);
        } catch (Exception e) {
            return;
        }
        if (creatorId == null) return;

        String pretFields = "[" +
                "{\"id\":\"typePret\",\"label\":\"Type de prêt\",\"type\":\"RADIO\",\"required\":true," +
                "\"options\":[\"Conso\",\"Scolaire\"]}," +
                "{\"id\":\"interesse\",\"label\":\"Le client est-il intéressé par le prêt ?\",\"type\":\"SELECT\",\"required\":true," +
                "\"options\":[\"Oui\",\"Non\",\"Besoin de réfléchir\"]}," +
                "{\"id\":\"sinonPourquoi\",\"label\":\"Si non, pourquoi ?\",\"type\":\"SELECT\",\"required\":false," +
                "\"options\":[\"Peur de ne pas pouvoir rembourser les mensualités\",\"Volonté d'éviter toute dette supplémentaire\"," +
                "\"Expériences négatives passées avec un crédit\",\"Le client estime que le coût du prêt est trop important\"," +
                "\"Il préfère attendre une offre plus avantageuse\",\"Crainte que ses revenus futurs ne lui permettent pas de respecter ses engagements\"," +
                "\"Emploi précaire ou activité professionnelle irrégulière\",\"Le client n'a pas de projet nécessitant un financement\"," +
                "\"Il préfère utiliser ses propres économies\",\"Durée de remboursement trop courte ou trop longue\"," +
                "\"Montant proposé inférieur ou supérieur à son besoin\",\"Garanties demandées trop contraignantes\"," +
                "\"Trop de documents à fournir\",\"Autre\"]}," +
                "{\"id\":\"montant\",\"label\":\"Montant du prêt souhaité\",\"type\":\"TEXT\",\"required\":false,\"options\":[]}," +
                "{\"id\":\"quandContacter\",\"label\":\"Quand souhaitez-vous être recontacté ?\",\"type\":\"DATE\",\"required\":false,\"options\":[]}" +
                "]";
        seedCampaignIfMissing(creatorId, "Prêt Scolaire & Conso 2026", "TELEVENTE",
                "bi-cash-coin", "#7C3AED", "#4C1D95",
                "Campagne d'appels pour la souscription de prêts scolaires et de consommation.", pretFields);

        String carteFields = "[" +
                "{\"id\":\"villeAgence\",\"label\":\"Ville / agence de rattachement\",\"type\":\"SELECT\",\"required\":false," +
                "\"options\":[\"Siège\",\"Abidjan - Plateau\",\"Abidjan - Cocody\",\"Abidjan - Marcory\",\"Abidjan - Treichville\"," +
                "\"Abidjan - Koumassi\",\"Abidjan - Yopougon\",\"Abidjan - Zone 4\",\"Bouaké\",\"Daloa\",\"San-Pédro\",\"Korhogo\"," +
                "\"Yamoussoukro\",\"Man\",\"Gagnoa\",\"Abengourou\",\"Grand-Bassam\",\"Aboisso\",\"Soubré\",\"Autre (préciser en commentaire)\"]}," +
                "{\"id\":\"typeCarte\",\"label\":\"Type de carte\",\"type\":\"RADIO\",\"required\":true," +
                "\"options\":[\"CLASSIC\",\"GOLD\",\"INFINITE\",\"PLATINUM\",\"ELITE\",\"MIX\",\"Autre\"]}," +
                "{\"id\":\"interesse\",\"label\":\"Le client est-il intéressé par la carte ?\",\"type\":\"SELECT\",\"required\":true," +
                "\"options\":[\"Oui\",\"Non\"]}," +
                "{\"id\":\"sinonPourquoi\",\"label\":\"Si non, pourquoi ?\",\"type\":\"SELECT\",\"required\":false," +
                "\"options\":[\"Frais de tenue de compte trop élevés\",\"Client déjà équipé d'une carte\",\"N'utilise pas de carte bancaire\"," +
                "\"Manque d'information sur les avantages\",\"Ne se déplace pas assez souvent en agence\",\"Autre\"]}" +
                "]";
        seedCampaignIfMissing(creatorId, "Carte Bancaire", "DIGITAL",
                "bi-credit-card-2-front-fill", "#0057B8", "#00A651",
                "Campagne d'appels pour la souscription de cartes bancaires (Classic, Gold, Infinite, Platinum, Elite).", carteFields);

        // Campagne "ouverte à tous" (TargetService = null) — indispensable : sans elle, un agent
        // Outbound dont le libellé User.activity est générique (ex. simplement "OUTBOUND", sans
        // le mot-clé DIGITAL/TELEVENTE) ne voit AUCUNE campagne dans son onglet, car
        // resolveAgentSubService() renvoie null pour lui et qu'aucune campagne non ciblée
        // n'existait jusqu'ici (les 2 campagnes ci-dessus sont toutes deux ciblées). Voir
        // CampaignService.activeCampaignsForAgent/resolveAgentSubService.
        String generiqueFields = "[" +
                "{\"id\":\"interesse\",\"label\":\"Le client est-il intéressé ?\",\"type\":\"SELECT\",\"required\":true," +
                "\"options\":[\"Oui\",\"Non\",\"À rappeler plus tard\"]}," +
                "{\"id\":\"commentaire\",\"label\":\"Commentaire libre\",\"type\":\"TEXTAREA\",\"required\":false,\"options\":[]}" +
                "]";
        seedCampaignIfMissing(creatorId, "Offres Outbound Générales", null,
                "bi-megaphone-fill", "#0057B8", "#00A651",
                "Campagne générale, visible par tous les agents Outbound quel que soit leur sous-service.", generiqueFields);
    }

    private void seedCampaignIfMissing(Long creatorId, String name, String targetService, String iconClass,
                                        String colorFrom, String colorTo, String description, String fieldsJson) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dbo.Campaigns WHERE Name = ?", Integer.class, name);
        if (count != null && count > 0) return;
        log.warn("⚠ [SCHEMA BOOTSTRAP] Création du modèle de campagne '{}' ({}).", name, targetService);
        jdbcTemplate.update(
                "INSERT INTO dbo.Campaigns (Name, Description, CreatedByUserId, Status, TargetService, IconClass, ColorFrom, ColorTo, FieldsJson) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
                name, description, creatorId, targetService, iconClass, colorFrom, colorTo, fieldsJson);
    }

    /** Pour une colonne ajoutée après coup sur une table qui existe déjà (createIfMissing ne la voit pas). */
    private void addColumnIfMissing(String tableName, String columnName, String alterSql) {
        if (!tableExists(tableName) || columnExists(tableName, columnName)) {
            return;
        }
        log.warn("⚠ [SCHEMA BOOTSTRAP] Ajout de dbo.{}.{} (colonne absente).", tableName, columnName);
        jdbcTemplate.execute(alterSql);
    }

    /** Insère un service métier réel s'il n'existe pas déjà (recherché par CODE, insensible à la casse). */
    private void seedServiceIfMissing(String code, String name, String description, int displayOrder) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dbo.SERVICES WHERE UPPER(CODE) = ?", Integer.class, code.toUpperCase());
        if (count != null && count > 0) return;
        log.warn("⚠ [SCHEMA BOOTSTRAP] Ajout du service métier {} ({}).", name, code);
        jdbcTemplate.update(
                "INSERT INTO dbo.SERVICES (NAME, DESCRIPTION, CODE, ENABLED, DISPLAY_ORDER) VALUES (?, ?, ?, 1, ?)",
                name, description, code.toUpperCase(), displayOrder);
    }

    /**
     * Élargit une colonne texte existante si sa longueur réelle en base est inférieure à la
     * largeur attendue — sans jamais la rétrécir (idempotent, sûr à rejouer à chaque démarrage).
     * Cas d'usage : un code plus long que prévu initialement doit pouvoir y tenir (ex.
     * ProcedureZones.Code, dimensionné à 20 au départ, alors que certains codes réels en
     * comptent 21).
     */
    private void widenColumnIfTooNarrow(String tableName, String columnName, int minLength, String alterSql) {
        if (!tableExists(tableName) || !columnExists(tableName, columnName)) {
            return;
        }
        Integer currentLength = jdbcTemplate.queryForObject(
                "SELECT c.max_length FROM sys.columns c JOIN sys.tables t ON t.object_id = c.object_id " +
                        "WHERE t.name = ? AND c.name = ?",
                Integer.class, tableName, columnName);
        // SQL Server rapporte max_length = -1 pour NVARCHAR(MAX) — déjà illimité, rien à faire.
        if (currentLength != null && currentLength == -1) {
            return;
        }
        // NVARCHAR stocke 2 octets par caractère — max_length est en octets.
        if (currentLength != null && currentLength / 2 >= minLength) {
            return;
        }
        log.warn("⚠ [SCHEMA BOOTSTRAP] Élargissement de dbo.{}.{} (trop étroite pour les données réelles).", tableName, columnName);
        jdbcTemplate.execute(alterSql);
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.columns c JOIN sys.tables t ON t.object_id = c.object_id " +
                        "WHERE t.name = ? AND c.name = ?",
                Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }
}