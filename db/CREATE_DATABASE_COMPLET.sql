/* ============================================================================
   RCC PORTAL ENTERPRISE — SCRIPT MAÎTRE DE CRÉATION DE LA BASE DE DONNÉES
   ----------------------------------------------------------------------------
   Régénéré directement depuis les entités JPA réelles
   (src/main/java/com/ecobank/rccportal/model/ - fichiers .java) le 17/09/2026 — remplace
   la version du 04/08/2026, qui ne couvrait plus qu'une partie des tables
   (il manquait notamment Campaigns, CampaignContacts, Appointments,
   AssignedTasks, GameCompetitions*, SalesJourneys, SalesRecords,
   TeamKpiEntries, ShiftSwapRequests, NewsArticles, PerformanceAlerts,
   SlaRules, SiteBanner, SiteSettings, DataAnalysisSnapshots,
   FavoriteAttachments, CourseCategories, RccPoles, RccCommunities et
   plusieurs colonnes ajoutées depuis à des tables déjà existantes).

   TOUT EST IDEMPOTENT : chaque section vérifie si la table/colonne existe
   déjà avant de la créer (IF OBJECT_ID / IF NOT EXISTS). Peut être exécuté
   sur une base totalement vide OU sur une base qui a déjà une partie de ces
   éléments — rien n'est jamais écrasé ni supprimé.

   PRÉREQUIS : la base de données elle-même doit déjà exister (ex. "SAGED",
   nom utilisé dans application.yml).

   Comment exécuter :
   - sqlcmd :  sqlcmd -S <serveur> -d <base> -U <utilisateur> -P <mot_de_passe> -i CREATE_DATABASE_COMPLET.sql
   - Ou depuis un éditeur SQL connecté DIRECTEMENT au bon serveur/instance
     (vérifiez le nom d'hôte affiché, pas seulement le nom de la base —
     "SAGED@localhost" et "SAGED@10.16.1.16" sont deux bases différentes
     même si elles portent le même nom).
   ============================================================================ */

IF DB_ID('SAGED') IS NULL
    BEGIN
        CREATE DATABASE SAGED;
        PRINT 'Base de données SAGED créée.';
    END
ELSE
    PRINT 'Base de données SAGED déjà existante, ignorée.';
GO

USE SAGED;
GO

SET QUOTED_IDENTIFIER ON;
GO

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
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ROLES';
GO

IF OBJECT_ID('dbo.Teams', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.Teams (
                                   TeamId INT IDENTITY PRIMARY KEY,
                                   Code NVARCHAR(50) NOT NULL UNIQUE,
                                   Label NVARCHAR(100) NOT NULL,
                                   IconGlyph NVARCHAR(20) NULL,
                                   AccentColor NVARCHAR(10) NULL,
                                   IsActive BIT NOT NULL,
                                   CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Teams_CreatedAt DEFAULT SYSUTCDATETIME(),
                                   UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_Teams_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.Teams';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.Teams';
GO

-- RccService (entité Java) mappe la même table physique SERVICES que le référentiel
-- historique — une seule table, deux vues Java différentes (User_Services/RccService).
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
    END ELSE PRINT 'Déjà présente, ignorée : dbo.SERVICES';
GO

IF OBJECT_ID('dbo.ProcedureZones', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ProcedureZones (
                                            ZoneId INT IDENTITY PRIMARY KEY,
                                            Code NVARCHAR(30) NOT NULL UNIQUE,
                                            Label NVARCHAR(100) NOT NULL,
                                            ImageUrl NVARCHAR(500) NULL,
                                            Team NVARCHAR(30) NULL,
                                            CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureZones_CreatedAt DEFAULT SYSUTCDATETIME(),
                                            UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureZones_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ProcedureZones';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ProcedureZones';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.ProcedureZones') AND name = 'ImageUrl')
ALTER TABLE dbo.ProcedureZones ADD ImageUrl NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.ProcedureZones') AND name = 'Team')
ALTER TABLE dbo.ProcedureZones ADD Team NVARCHAR(30) NULL;
GO

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
                                                    Team NVARCHAR(30) NULL,
                                                    CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_MailTemplateCategories_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                    UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_MailTemplateCategories_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.MailTemplateCategories';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.MailTemplateCategories';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.MailTemplateCategories') AND name = 'Team')
ALTER TABLE dbo.MailTemplateCategories ADD Team NVARCHAR(30) NULL;
GO

IF OBJECT_ID('dbo.MailRecipientGroups', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.MailRecipientGroups (
                                                 GroupId INT IDENTITY PRIMARY KEY,
                                                 Label NVARCHAR(150) NOT NULL,
                                                 Email NVARCHAR(254) NOT NULL UNIQUE,
                                                 CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_MailRecipientGroups_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                 UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_MailRecipientGroups_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.MailRecipientGroups';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.MailRecipientGroups';
GO

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
                                             Channel NVARCHAR(10) NULL,
                                             CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityCriteria_CreatedAt DEFAULT SYSUTCDATETIME(),
                                             UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityCriteria_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.QualityCriteria';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.QualityCriteria';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.QualityCriteria') AND name = 'Channel')
ALTER TABLE dbo.QualityCriteria ADD Channel NVARCHAR(10) NULL;
GO

IF OBJECT_ID('dbo.QualityMotifs', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.QualityMotifs (
                                           MotifId INT IDENTITY PRIMARY KEY,
                                           Label NVARCHAR(150) NOT NULL,
                                           IsActive BIT NOT NULL,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityMotifs_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityMotifs_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.QualityMotifs';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.QualityMotifs';
GO

IF OBJECT_ID('dbo.KnowledgeCategories', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.KnowledgeCategories (
                                                 CategoryId INT IDENTITY PRIMARY KEY,
                                                 Code NVARCHAR(50) NOT NULL UNIQUE,
                                                 Title NVARCHAR(100) NOT NULL,
                                                 Icon NVARCHAR(50) NULL,
                                                 ImageUrl NVARCHAR(500) NULL,
                                                 SortOrder INT NOT NULL,
                                                 Team NVARCHAR(30) NULL,
                                                 CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_KnowledgeCategories_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                 UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_KnowledgeCategories_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.KnowledgeCategories';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.KnowledgeCategories';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.KnowledgeCategories') AND name = 'ImageUrl')
ALTER TABLE dbo.KnowledgeCategories ADD ImageUrl NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.KnowledgeCategories') AND name = 'Team')
ALTER TABLE dbo.KnowledgeCategories ADD Team NVARCHAR(30) NULL;
GO

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
                                                CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_KnowledgeCountries_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_KnowledgeCountries_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.KnowledgeCountries';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.KnowledgeCountries';
GO

IF OBJECT_ID('dbo.CourseCategories', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.CourseCategories (
                                              CourseCategoryId INT IDENTITY PRIMARY KEY,
                                              Title NVARCHAR(100) NOT NULL UNIQUE,
                                              ImageUrl NVARCHAR(500) NULL,
                                              SortOrder INT NOT NULL CONSTRAINT DF_CourseCategories_Sort DEFAULT 0
        );
        PRINT 'Créée : dbo.CourseCategories';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.CourseCategories';
GO

IF OBJECT_ID('dbo.KvEntries', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.KvEntries (
                                       Scope NVARCHAR(100) NOT NULL,
                                       [Key] NVARCHAR(200) NOT NULL,
                                       Value NVARCHAR(MAX) NOT NULL,
                                       CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_KvEntries_CreatedAt DEFAULT SYSUTCDATETIME(),
                                       UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_KvEntries_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                       CONSTRAINT PK_KvEntries PRIMARY KEY (Scope, [Key])
        );
        PRINT 'Créée : dbo.KvEntries';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.KvEntries';
GO

IF OBJECT_ID('dbo.SiteSettings', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.SiteSettings (
                                          SettingKey NVARCHAR(100) PRIMARY KEY,
                                          SettingValue NVARCHAR(500) NULL
        );
        PRINT 'Créée : dbo.SiteSettings';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.SiteSettings';
GO

IF OBJECT_ID('dbo.SiteBanner', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.SiteBanner (
                                        SiteBannerId INT IDENTITY PRIMARY KEY,
                                        ImageUrl NVARCHAR(500) NULL,
                                        Headline NVARCHAR(200) NULL,
                                        Subheadline NVARCHAR(500) NULL,
                                        CtaLabel NVARCHAR(100) NULL,
                                        CtaUrl NVARCHAR(500) NULL,
                                        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_SiteBanner_CreatedAt DEFAULT SYSUTCDATETIME(),
                                        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_SiteBanner_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.SiteBanner';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.SiteBanner';
GO

IF OBJECT_ID('dbo.LoginFeatureCards', 'U') IS NULL
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
        PRINT 'Créée : dbo.LoginFeatureCards';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.LoginFeatureCards';
GO

IF OBJECT_ID('dbo.WordTerms', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.WordTerms (
                                       TermId INT IDENTITY(1,1) PRIMARY KEY,
                                       Term NVARCHAR(100) NOT NULL,
                                       Definition NVARCHAR(500) NOT NULL,
                                       Category NVARCHAR(100) NULL,
                                       Active BIT NOT NULL CONSTRAINT DF_WordTerms_Active DEFAULT 1,
                                       CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_WordTerms_CreatedAt DEFAULT SYSUTCDATETIME(),
                                       UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_WordTerms_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.WordTerms';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.WordTerms';
GO
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
                                   GENDER NVARCHAR(1) NULL,
                                   CONTRACT_TYPE NVARCHAR(30) NULL,
                                   CONTRACT_STATUS NVARCHAR(50) NULL,
                                   CONTRACT_START_DATE DATE NULL,
                                   CONTRACT_END_DATE DATE NULL,
                                   ROLE_DETAIL NVARCHAR(150) NULL,
                                   ACTIVITY NVARCHAR(100) NULL,
                                   RESIDENCE_PLACE NVARCHAR(200) NULL,
                                   LED_TEAM NVARCHAR(30) NULL,
                                   TEAM_ASSIGNMENT_LOCKED BIT NOT NULL CONSTRAINT DF_Users_TeamAssignLocked DEFAULT 0,
            /* Ne jamais stocker le vrai mot de passe AD ici — conservé pour compatibilité de schéma uniquement. */
                                   PASSWORD NVARCHAR(255) NULL,
                                   CREATED_AT DATETIMEOFFSET NULL,
                                   MODIFIED_AT DATETIMEOFFSET NULL
        );
        PRINT 'Créée : dbo.USERS';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.USERS';
GO
-- Colonnes ajoutées après la création initiale de USERS dans certains environnements :
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'GENDER')
ALTER TABLE dbo.USERS ADD GENDER NVARCHAR(1) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'CONTRACT_TYPE')
ALTER TABLE dbo.USERS ADD CONTRACT_TYPE NVARCHAR(30) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'CONTRACT_STATUS')
ALTER TABLE dbo.USERS ADD CONTRACT_STATUS NVARCHAR(50) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'CONTRACT_START_DATE')
ALTER TABLE dbo.USERS ADD CONTRACT_START_DATE DATE NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'CONTRACT_END_DATE')
ALTER TABLE dbo.USERS ADD CONTRACT_END_DATE DATE NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'ROLE_DETAIL')
ALTER TABLE dbo.USERS ADD ROLE_DETAIL NVARCHAR(150) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'ACTIVITY')
ALTER TABLE dbo.USERS ADD ACTIVITY NVARCHAR(100) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'RESIDENCE_PLACE')
ALTER TABLE dbo.USERS ADD RESIDENCE_PLACE NVARCHAR(200) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'LED_TEAM')
ALTER TABLE dbo.USERS ADD LED_TEAM NVARCHAR(30) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'TEAM_ASSIGNMENT_LOCKED')
ALTER TABLE dbo.USERS ADD TEAM_ASSIGNMENT_LOCKED BIT NOT NULL CONSTRAINT DF_Users_TeamAssignLocked2 DEFAULT 0;
GO
-- Status de compte (migration 001) — DEFAULT 'APPROVED' pour ne jamais bloquer un compte existant.
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'STATUS')
ALTER TABLE dbo.USERS ADD STATUS NVARCHAR(20) NOT NULL CONSTRAINT DF_Users_Status DEFAULT 'APPROVED';
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_Users_Status')
ALTER TABLE dbo.USERS ADD CONSTRAINT CK_Users_Status CHECK (STATUS IN ('PENDING', 'APPROVED', 'REJECTED'));
GO
-- 2FA TOTP (migration 002)
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'TotpSecret')
ALTER TABLE dbo.USERS ADD TotpSecret VARCHAR(64) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.USERS') AND name = 'TotpConfirmedAt')
ALTER TABLE dbo.USERS ADD TotpConfirmedAt DATETIME2 NULL;
GO

IF OBJECT_ID('dbo.USER_ROLES', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.USER_ROLES (
                                        ID BIGINT IDENTITY PRIMARY KEY,
                                        USERS_ID BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                        ROLES_ID INT NOT NULL REFERENCES dbo.ROLES(ID)
        );
        PRINT 'Créée : dbo.USER_ROLES';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.USER_ROLES';
GO

IF OBJECT_ID('dbo.USER_SERVICES', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.USER_SERVICES (
                                           ID BIGINT IDENTITY PRIMARY KEY,
                                           USER_ID BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                           SERVICE_ID INT NOT NULL REFERENCES dbo.SERVICES(ID)
        );
        PRINT 'Créée : dbo.USER_SERVICES';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.USER_SERVICES';
GO

IF OBJECT_ID('dbo.UserProfiles', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.UserProfiles (
                                          UserProfileId INT IDENTITY PRIMARY KEY,
                                          UserId BIGINT NOT NULL UNIQUE REFERENCES dbo.USERS(ID),
                                          PhotoUrl NVARCHAR(500) NULL,
                                          ChatBackgroundUrl NVARCHAR(500) NULL,
                                          Birthdate DATE NULL,
                                          Phone NVARCHAR(30) NULL,
                                          Bio NVARCHAR(1000) NULL,
                                          CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_UserProfiles_CreatedAt DEFAULT SYSUTCDATETIME(),
                                          UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_UserProfiles_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.UserProfiles';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.UserProfiles';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.UserProfiles') AND name = 'ChatBackgroundUrl')
ALTER TABLE dbo.UserProfiles ADD ChatBackgroundUrl NVARCHAR(500) NULL;
GO

IF OBJECT_ID('dbo.RefreshTokens', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RefreshTokens (
                                           RefreshTokenId INT IDENTITY PRIMARY KEY,
                                           Jti UNIQUEIDENTIFIER NOT NULL UNIQUE,
                                           UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                           ExpiresAt DATETIME2 NOT NULL,
                                           GatewayAccessToken NVARCHAR(4000) NULL,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RefreshTokens_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RefreshTokens_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.RefreshTokens';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RefreshTokens';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.RefreshTokens') AND name = 'GatewayAccessToken')
ALTER TABLE dbo.RefreshTokens ADD GatewayAccessToken NVARCHAR(4000) NULL;
GO

IF OBJECT_ID('dbo.LoginAudit', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.LoginAudit (
                                        LoginAuditId INT IDENTITY PRIMARY KEY,
                                        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                        EventType NVARCHAR(30) NOT NULL,
                                        OccurredAt DATETIME2 NOT NULL,
                                        PasswordAgeDays INT NULL,
                                        IpAddress NVARCHAR(45) NULL,
                                        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_LoginAudit_CreatedAt DEFAULT SYSUTCDATETIME(),
                                        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_LoginAudit_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.LoginAudit';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.LoginAudit';
GO

IF OBJECT_ID('dbo.TabPermissions', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.TabPermissions (
                                            TabPermissionId INT IDENTITY PRIMARY KEY,
                                            TeamId INT NULL REFERENCES dbo.Teams(TeamId),
                                            RoleId INT NULL REFERENCES dbo.ROLES(ID),
                                            TabCode NVARCHAR(50) NOT NULL,
                                            IsAllowed BIT NOT NULL,
                                            CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_TabPermissions_CreatedAt DEFAULT SYSUTCDATETIME(),
                                            UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_TabPermissions_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.TabPermissions';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.TabPermissions';
GO

IF OBJECT_ID('dbo.UserFeaturePermissions', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.UserFeaturePermissions (
                                                    UserFeaturePermissionId INT IDENTITY PRIMARY KEY,
                                                    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                    FeatureCode NVARCHAR(50) NOT NULL,
                                                    IsAllowed BIT NOT NULL,
                                                    CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_UserFeaturePermissions_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                    UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_UserFeaturePermissions_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                                    CONSTRAINT UQ_UserFeaturePermissions_User_Feature UNIQUE (UserId, FeatureCode)
        );
        PRINT 'Créée : dbo.UserFeaturePermissions';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.UserFeaturePermissions';
GO
/* ============================================================================
   3. SHIFT, PRÉSENCE, WORKFLOW DE DEMANDES
   ============================================================================ */

IF OBJECT_ID('dbo.ShiftEvents', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ShiftEvents (
                                         ShiftEventId INT IDENTITY PRIMARY KEY,
                                         UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                         EventType NVARCHAR(20) NOT NULL,
                                         OccurredAt DATETIME2 NOT NULL,
                                         CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ShiftEvents_CreatedAt DEFAULT SYSUTCDATETIME(),
                                         UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ShiftEvents_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ShiftEvents';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ShiftEvents';
GO

IF OBJECT_ID('dbo.AttendanceRecords', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.AttendanceRecords (
                                               AttendanceId INT IDENTITY PRIMARY KEY,
                                               UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                               WorkDate DATE NOT NULL,
                                               Status NVARCHAR(20) NOT NULL,
                                               ArrivalTime TIME NULL,
                                               DepartureTime TIME NULL,
                                               CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_AttendanceRecords_CreatedAt DEFAULT SYSUTCDATETIME(),
                                               UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_AttendanceRecords_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                               CONSTRAINT UQ_AttendanceRecords_User_Date UNIQUE (UserId, WorkDate)
        );
        PRINT 'Créée : dbo.AttendanceRecords';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.AttendanceRecords';
GO

IF OBJECT_ID('dbo.AgentSchedules', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.AgentSchedules (
                                            ScheduleId INT IDENTITY PRIMARY KEY,
                                            UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                            WorkDate DATE NOT NULL,
                                            PlannedStartTime TIME NULL,
                                            PlannedEndTime TIME NULL,
                                            ShiftCode NVARCHAR(20) NULL,
                                            ShiftLabel NVARCHAR(100) NULL,
                                            OvernightCrossesMidnight BIT NOT NULL CONSTRAINT DF_AgentSchedules_Overnight DEFAULT 0,
                                            ApprovalStatus NVARCHAR(20) NOT NULL CONSTRAINT DF_AgentSchedules_ApprovalStatus DEFAULT 'APPROVED',
                                            RejectionReason NVARCHAR(500) NULL,
                                            Origin NVARCHAR(20) NOT NULL CONSTRAINT DF_AgentSchedules_Origin DEFAULT 'EXCELLIAM',
                                            CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_AgentSchedules_CreatedAt DEFAULT SYSUTCDATETIME(),
                                            UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_AgentSchedules_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                            CONSTRAINT UQ_AgentSchedules_User_Date UNIQUE (UserId, WorkDate)
        );
        PRINT 'Créée : dbo.AgentSchedules';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.AgentSchedules';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.AgentSchedules') AND name = 'PlannedEndTime')
ALTER TABLE dbo.AgentSchedules ADD PlannedEndTime TIME NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.AgentSchedules') AND name = 'ShiftCode')
ALTER TABLE dbo.AgentSchedules ADD ShiftCode NVARCHAR(20) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.AgentSchedules') AND name = 'ShiftLabel')
ALTER TABLE dbo.AgentSchedules ADD ShiftLabel NVARCHAR(100) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.AgentSchedules') AND name = 'OvernightCrossesMidnight')
ALTER TABLE dbo.AgentSchedules ADD OvernightCrossesMidnight BIT NOT NULL CONSTRAINT DF_AgentSchedules_Overnight2 DEFAULT 0;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.AgentSchedules') AND name = 'ApprovalStatus')
ALTER TABLE dbo.AgentSchedules ADD ApprovalStatus NVARCHAR(20) NOT NULL CONSTRAINT DF_AgentSchedules_ApprovalStatus2 DEFAULT 'APPROVED';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.AgentSchedules') AND name = 'RejectionReason')
ALTER TABLE dbo.AgentSchedules ADD RejectionReason NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.AgentSchedules') AND name = 'Origin')
ALTER TABLE dbo.AgentSchedules ADD Origin NVARCHAR(20) NOT NULL CONSTRAINT DF_AgentSchedules_Origin2 DEFAULT 'EXCELLIAM';
GO

IF OBJECT_ID('dbo.ShiftSwapRequests', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ShiftSwapRequests (
                                               SwapRequestId INT IDENTITY PRIMARY KEY,
                                               RequesterUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                               RequesterDate DATE NOT NULL,
                                               TargetUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                               TargetDate DATE NOT NULL,
                                               PeerStatus NVARCHAR(20) NOT NULL CONSTRAINT DF_ShiftSwapRequests_PeerStatus DEFAULT 'PENDING',
                                               TeamLeaderStatus NVARCHAR(20) NOT NULL CONSTRAINT DF_ShiftSwapRequests_TLStatus DEFAULT 'NOT_SUBMITTED',
                                               DecidedByTeamLeaderUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                               TeamLeaderComment NVARCHAR(500) NULL,
                                               RequesterMessage NVARCHAR(500) NULL,
                                               PeerDecidedAt DATETIME2 NULL,
                                               TeamLeaderDecidedAt DATETIME2 NULL,
                                               SwapApplied BIT NOT NULL CONSTRAINT DF_ShiftSwapRequests_Applied DEFAULT 0,
                                               CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ShiftSwapRequests_CreatedAt DEFAULT SYSUTCDATETIME(),
                                               UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ShiftSwapRequests_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ShiftSwapRequests';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ShiftSwapRequests';
GO

IF OBJECT_ID('dbo.WorkflowRequests', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.WorkflowRequests (
                                              RequestId INT IDENTITY PRIMARY KEY,
                                              Type NVARCHAR(30) NOT NULL,
                                              Title NVARCHAR(200) NOT NULL,
                                              Details NVARCHAR(2000) NULL,
                                              PeriodType NVARCHAR(10) NOT NULL,
                                              PeriodFrom DATE NOT NULL,
                                              PeriodTo DATE NOT NULL,
                                              RelatedServiceId INT NULL REFERENCES dbo.SERVICES(ID),
                                              AssignedTeam NVARCHAR(10) NOT NULL,
                                              AssignedToUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                              RequestedByUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                              Status NVARCHAR(20) NOT NULL,
                                              DecidedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                              DecisionComment NVARCHAR(500) NULL,
                                              DecidedAt DATETIME2 NULL,
                                              CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_WorkflowRequests_CreatedAt DEFAULT SYSUTCDATETIME(),
                                              UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_WorkflowRequests_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.WorkflowRequests';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.WorkflowRequests';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.WorkflowRequests') AND name = 'RelatedServiceId')
ALTER TABLE dbo.WorkflowRequests ADD RelatedServiceId INT NULL REFERENCES dbo.SERVICES(ID);
GO

IF OBJECT_ID('dbo.RequestTemplates', 'U') IS NULL
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
        PRINT 'Créée : dbo.RequestTemplates';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RequestTemplates';
GO

IF OBJECT_ID('dbo.LeaveBalances', 'U') IS NULL
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
        PRINT 'Créée : dbo.LeaveBalances';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.LeaveBalances';
GO

IF OBJECT_ID('dbo.SlaTargets', 'U') IS NULL
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
        PRINT 'Créée : dbo.SlaTargets';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.SlaTargets';
GO

/* ============================================================================
   4. PÔLES RCC (fiches Inbound/Outbound/Résolution...) + RÈGLES SLA CLIENT
   ============================================================================ */

IF OBJECT_ID('dbo.RccPoles', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccPoles (
                                      PoleId INT IDENTITY PRIMARY KEY,
                                      Name NVARCHAR(150) NOT NULL,
                                      ManagerUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                      ContactPhone NVARCHAR(40) NULL,
                                      ContactEmail NVARCHAR(150) NULL,
                                      TeamContactLabel NVARCHAR(100) NULL,
                                      WhoWeAre NVARCHAR(1000) NULL,
                                      WhatWeDo NVARCHAR(1000) NULL,
                                      IsActive BIT NOT NULL,
                                      SortOrder INT NOT NULL,
                                      CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPoles_CreatedAt DEFAULT SYSUTCDATETIME(),
                                      UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPoles_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.RccPoles';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccPoles';
GO

IF OBJECT_ID('dbo.SlaRules', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.SlaRules (
                                      SlaRuleId INT IDENTITY PRIMARY KEY,
                                      PoleId INT NULL REFERENCES dbo.RccPoles(PoleId),
                                      Motif NVARCHAR(200) NOT NULL,
                                      Category NVARCHAR(100) NOT NULL,
                                      Level NVARCHAR(10) NULL,
                                      SlaHours INT NOT NULL,
                                      SlaLabel NVARCHAR(160) NOT NULL,
                                      DestinationService NVARCHAR(100) NULL,
                                      Priority NVARCHAR(20) NULL,
                                      AutoEscalation BIT NOT NULL,
                                      Notes NVARCHAR(500) NULL,
                                      IsActive BIT NOT NULL,
                                      SortOrder INT NOT NULL,
                                      CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_SlaRules_CreatedAt DEFAULT SYSUTCDATETIME(),
                                      UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_SlaRules_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.SlaRules';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.SlaRules';
GO

/* ============================================================================
   5. PROCÉDURES (fichiers partagés + parcours interactif)
   ============================================================================ */

IF OBJECT_ID('dbo.Procedures', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.Procedures (
                                        ProcedureId INT IDENTITY PRIMARY KEY,
                                        ZoneId INT NOT NULL REFERENCES dbo.ProcedureZones(ZoneId),
                                        ServiceId INT NULL REFERENCES dbo.SERVICES(ID),
                                        CountryCode NVARCHAR(3) NULL,
                                        Title NVARCHAR(200) NOT NULL,
                                        SlaDelay NVARCHAR(100) NULL,
                                        Level NVARCHAR(20) NULL,
                                        ResponsibleTeam NVARCHAR(200) NULL,
                                        CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Procedures_CreatedAt DEFAULT SYSUTCDATETIME(),
                                        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_Procedures_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.Procedures';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.Procedures';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Procedures') AND name = 'ServiceId')
ALTER TABLE dbo.Procedures ADD ServiceId INT NULL REFERENCES dbo.SERVICES(ID);
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Procedures') AND name = 'CountryCode')
ALTER TABLE dbo.Procedures ADD CountryCode NVARCHAR(3) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Procedures') AND name = 'SlaDelay')
ALTER TABLE dbo.Procedures ADD SlaDelay NVARCHAR(100) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Procedures') AND name = 'Level')
ALTER TABLE dbo.Procedures ADD Level NVARCHAR(20) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Procedures') AND name = 'ResponsibleTeam')
ALTER TABLE dbo.Procedures ADD ResponsibleTeam NVARCHAR(200) NULL;
GO

IF OBJECT_ID('dbo.ProcedureSteps', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ProcedureSteps (
                                            ProcedureStepId INT IDENTITY PRIMARY KEY,
                                            ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
                                            StepNumber INT NOT NULL,
                                            Content NVARCHAR(1000) NOT NULL,
                                            CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureSteps_CreatedAt DEFAULT SYSUTCDATETIME(),
                                            UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureSteps_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ProcedureSteps';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ProcedureSteps';
GO

IF OBJECT_ID('dbo.ProcedureWorkflowNodes', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ProcedureWorkflowNodes (
                                                    NodeId INT IDENTITY PRIMARY KEY,
                                                    ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
                                                    QuestionText NVARCHAR(1000) NOT NULL,
                                                    IsStart BIT NOT NULL,
                                                    SuggestionLabel NVARCHAR(200) NULL,
                                                    SuggestionUrl NVARCHAR(500) NULL,
                                                    CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureWorkflowNodes_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                    UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureWorkflowNodes_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ProcedureWorkflowNodes';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ProcedureWorkflowNodes';
GO

IF OBJECT_ID('dbo.ProcedureWorkflowOptions', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ProcedureWorkflowOptions (
                                                      OptionId INT IDENTITY PRIMARY KEY,
                                                      NodeId INT NOT NULL REFERENCES dbo.ProcedureWorkflowNodes(NodeId),
                                                      Label NVARCHAR(200) NOT NULL,
                                                      NextNodeId INT NULL REFERENCES dbo.ProcedureWorkflowNodes(NodeId),
                                                      Outcome NVARCHAR(20) NULL,
                                                      CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureWorkflowOptions_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                      UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ProcedureWorkflowOptions_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ProcedureWorkflowOptions';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ProcedureWorkflowOptions';
GO

IF OBJECT_ID('dbo.FavoriteProcedures', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.FavoriteProcedures (
                                                FavoriteId INT IDENTITY PRIMARY KEY,
                                                UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                ProcedureId INT NOT NULL REFERENCES dbo.Procedures(ProcedureId),
                                                CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_FavoriteProcedures_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_FavoriteProcedures_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                                CONSTRAINT UQ_FavoriteProcedures_User_Procedure UNIQUE (UserId, ProcedureId)
        );
        PRINT 'Créée : dbo.FavoriteProcedures';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.FavoriteProcedures';
GO

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
                                         CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Attachments_CreatedAt DEFAULT SYSUTCDATETIME(),
                                         UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_Attachments_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.Attachments';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.Attachments';
GO

IF OBJECT_ID('dbo.FavoriteAttachments', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.FavoriteAttachments (
                                                 FavoriteId INT IDENTITY PRIMARY KEY,
                                                 UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                 AttachmentId INT NOT NULL REFERENCES dbo.Attachments(AttachmentId),
                                                 CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_FavoriteAttachments_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                 UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_FavoriteAttachments_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                                 CONSTRAINT UQ_FavoriteAttachments_User_Attachment UNIQUE (UserId, AttachmentId)
        );
        PRINT 'Créée : dbo.FavoriteAttachments';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.FavoriteAttachments';
GO

/* ============================================================================
   6. FORMATION
   ============================================================================ */

IF OBJECT_ID('dbo.Courses', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.Courses (
                                     CourseId INT IDENTITY PRIMARY KEY,
                                     Title NVARCHAR(200) NOT NULL,
                                     Description NVARCHAR(1000) NULL,
                                     Category NVARCHAR(100) NULL,
                                     Content NVARCHAR(MAX) NULL,
                                     Type NVARCHAR(20) NOT NULL,
                                     Mandatory BIT NOT NULL,
                                     VideoUrl NVARCHAR(500) NULL,
                                     FileUrl NVARCHAR(500) NULL,
                                     FileName NVARCHAR(255) NULL,
                                     ImageUrl NVARCHAR(500) NULL,
                                     ServiceId INT NULL REFERENCES dbo.SERVICES(ID),
                                     TeamId INT NULL REFERENCES dbo.Teams(TeamId),
                                     CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                     CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Courses_CreatedAt DEFAULT SYSUTCDATETIME(),
                                     UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_Courses_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.Courses';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.Courses';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Courses') AND name = 'Category')
ALTER TABLE dbo.Courses ADD Category NVARCHAR(100) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Courses') AND name = 'FileUrl')
ALTER TABLE dbo.Courses ADD FileUrl NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Courses') AND name = 'FileName')
ALTER TABLE dbo.Courses ADD FileName NVARCHAR(255) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Courses') AND name = 'ImageUrl')
ALTER TABLE dbo.Courses ADD ImageUrl NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Courses') AND name = 'TeamId')
ALTER TABLE dbo.Courses ADD TeamId INT NULL REFERENCES dbo.Teams(TeamId);
GO

IF OBJECT_ID('dbo.CourseQuestions', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.CourseQuestions (
                                             QuestionId INT IDENTITY PRIMARY KEY,
                                             CourseId INT NOT NULL REFERENCES dbo.Courses(CourseId),
                                             QuestionText NVARCHAR(1000) NOT NULL,
                                             QuestionNumber INT NOT NULL,
                                             OptionsJson NVARCHAR(2000) NULL,
                                             CorrectOptionIndex INT NULL,
                                             CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_CourseQuestions_CreatedAt DEFAULT SYSUTCDATETIME(),
                                             UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_CourseQuestions_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.CourseQuestions';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.CourseQuestions';
GO

IF OBJECT_ID('dbo.CourseAttempts', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.CourseAttempts (
                                            AttemptId INT IDENTITY PRIMARY KEY,
                                            CourseId INT NOT NULL REFERENCES dbo.Courses(CourseId),
                                            UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                            Status NVARCHAR(20) NOT NULL,
                                            Score INT NULL,
                                            AnswersJson NVARCHAR(2000) NULL,
                                            CompletedAt DATETIME2 NULL,
                                            AttemptNumber INT NOT NULL DEFAULT 1,
                                            Finalized BIT NOT NULL DEFAULT 0,
                                            VideoWatchedPercent INT NOT NULL CONSTRAINT DF_CourseAttempts_VideoPct DEFAULT 0,
                                            SeekViolationCount INT NOT NULL CONSTRAINT DF_CourseAttempts_SeekViol DEFAULT 0,
                                            CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_CourseAttempts_CreatedAt DEFAULT SYSUTCDATETIME(),
                                            UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_CourseAttempts_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                            CONSTRAINT UQ_CourseAttempts_Course_User UNIQUE (CourseId, UserId)
        );
        PRINT 'Créée : dbo.CourseAttempts';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.CourseAttempts';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.CourseAttempts') AND name = 'VideoWatchedPercent')
ALTER TABLE dbo.CourseAttempts ADD VideoWatchedPercent INT NOT NULL CONSTRAINT DF_CourseAttempts_VideoPct2 DEFAULT 0;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.CourseAttempts') AND name = 'SeekViolationCount')
ALTER TABLE dbo.CourseAttempts ADD SeekViolationCount INT NOT NULL CONSTRAINT DF_CourseAttempts_SeekViol2 DEFAULT 0;
GO

IF OBJECT_ID('dbo.TrainingFormations', 'U') IS NULL
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
        PRINT 'Créée : dbo.TrainingFormations';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.TrainingFormations';
GO

IF OBJECT_ID('dbo.TrainingLessons', 'U') IS NULL
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
        PRINT 'Créée : dbo.TrainingLessons';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.TrainingLessons';
GO

IF OBJECT_ID('dbo.TrainingProgress', 'U') IS NULL
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
        PRINT 'Créée : dbo.TrainingProgress';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.TrainingProgress';
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
/* ============================================================================
   7. QUALITÉ (CLAIRAUDIO)
   ============================================================================ */

IF OBJECT_ID('dbo.QualityCriterionAttributes', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.QualityCriterionAttributes (
                                                        AttributeId INT IDENTITY PRIMARY KEY,
                                                        CriterionId INT NOT NULL REFERENCES dbo.QualityCriteria(CriterionId),
                                                        Label NVARCHAR(300) NOT NULL,
                                                        SortOrder INT NOT NULL,
                                                        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityCriterionAttributes_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityCriterionAttributes_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.QualityCriterionAttributes';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.QualityCriterionAttributes';
GO

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
                                                FeedbackStatus NVARCHAR(20) NOT NULL,
                                                FeedbackDate DATE NULL,
                                                Channel NVARCHAR(10) NOT NULL CONSTRAINT DF_QualityEvaluations_Channel DEFAULT 'VOICE',
                                                CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityEvaluations_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityEvaluations_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.QualityEvaluations';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.QualityEvaluations';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.QualityEvaluations') AND name = 'Channel')
ALTER TABLE dbo.QualityEvaluations ADD Channel NVARCHAR(10) NOT NULL CONSTRAINT DF_QualityEvaluations_Channel2 DEFAULT 'VOICE';
GO

IF OBJECT_ID('dbo.QualityEvaluationScores', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.QualityEvaluationScores (
                                                     EvaluationScoreId INT IDENTITY PRIMARY KEY,
                                                     EvaluationId INT NOT NULL REFERENCES dbo.QualityEvaluations(EvaluationId),
                                                     CriterionId INT NOT NULL REFERENCES dbo.QualityCriteria(CriterionId),
                                                     ScoreValue SMALLINT NULL,
                                                     IsNotApplicable BIT NOT NULL,
                                                     CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityEvaluationScores_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                     UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_QualityEvaluationScores_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                                     CONSTRAINT UQ_QualityEvaluationScores_Evaluation_Criterion UNIQUE (EvaluationId, CriterionId)
        );
        PRINT 'Créée : dbo.QualityEvaluationScores';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.QualityEvaluationScores';
GO

IF OBJECT_ID('dbo.CoachingPlans', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.CoachingPlans (
                                           CoachingPlanId INT IDENTITY PRIMARY KEY,
                                           AgentUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                           Axis NVARCHAR(300) NOT NULL,
                                           DueDate DATE NOT NULL,
                                           Status NVARCHAR(20) NOT NULL,
                                           Note NVARCHAR(1000) NULL,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_CoachingPlans_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_CoachingPlans_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.CoachingPlans';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.CoachingPlans';
GO

IF OBJECT_ID('dbo.AgentDossiers', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.AgentDossiers (
                                           DossierId INT IDENTITY PRIMARY KEY,
                                           LinkedUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                           Source NVARCHAR(100) NOT NULL,
                                           Payload NVARCHAR(MAX) NOT NULL,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_AgentDossiers_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_AgentDossiers_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.AgentDossiers';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.AgentDossiers';
GO

IF OBJECT_ID('dbo.KpiEvents', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.KpiEvents (
                                       KpiEventId INT IDENTITY PRIMARY KEY,
                                       UserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                       EventType NVARCHAR(50) NOT NULL,
                                       EventKey NVARCHAR(150) NULL,
                                       OccurredAt DATETIME2 NOT NULL,
                                       CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_KpiEvents_CreatedAt DEFAULT SYSUTCDATETIME(),
                                       UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_KpiEvents_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.KpiEvents';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.KpiEvents';
GO

IF OBJECT_ID('dbo.ManualKpiEntries', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ManualKpiEntries (
                                              ManualKpiEntryId INT IDENTITY PRIMARY KEY,
                                              SubjectUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                              EnteredByUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                              MetricCode NVARCHAR(50) NOT NULL,
                                              MetricValue DECIMAL(18,4) NOT NULL,
                                              PeriodDate DATE NOT NULL,
                                              ImportBatchId NVARCHAR(40) NULL,
                                              CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ManualKpiEntries_CreatedAt DEFAULT SYSUTCDATETIME(),
                                              UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ManualKpiEntries_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ManualKpiEntries';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ManualKpiEntries';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.ManualKpiEntries') AND name = 'ImportBatchId')
ALTER TABLE dbo.ManualKpiEntries ADD ImportBatchId NVARCHAR(40) NULL;
GO

IF OBJECT_ID('dbo.TeamKpiEntries', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.TeamKpiEntries (
                                            TeamKpiEntryId INT IDENTITY PRIMARY KEY,
                                            Team NVARCHAR(30) NOT NULL,
                                            MetricCode NVARCHAR(50) NOT NULL,
                                            MetricValue DECIMAL(18,4) NOT NULL,
                                            PeriodDate DATE NOT NULL,
                                            EnteredByUsername NVARCHAR(100) NULL,
                                            ImportBatchId NVARCHAR(40) NULL,
                                            CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_TeamKpiEntries_CreatedAt DEFAULT SYSUTCDATETIME(),
                                            UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_TeamKpiEntries_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.TeamKpiEntries';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.TeamKpiEntries';
GO

IF OBJECT_ID('dbo.PerformanceAlerts', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.PerformanceAlerts (
                                               AlertId INT IDENTITY PRIMARY KEY,
                                               UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                               PeriodMonth NVARCHAR(7) NOT NULL,
                                               AlertType NVARCHAR(40) NOT NULL,
                                               Severity NVARCHAR(20) NOT NULL,
                                               Message NVARCHAR(500) NOT NULL,
                                               Acknowledged BIT NOT NULL CONSTRAINT DF_PerformanceAlerts_Ack DEFAULT 0,
                                               AcknowledgedByUsername NVARCHAR(100) NULL,
                                               CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_PerformanceAlerts_CreatedAt DEFAULT SYSUTCDATETIME(),
                                               UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_PerformanceAlerts_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.PerformanceAlerts';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.PerformanceAlerts';
GO

IF OBJECT_ID('dbo.DataAnalysisSnapshots', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.DataAnalysisSnapshots (
                                                   SnapshotId INT IDENTITY PRIMARY KEY,
                                                   PeriodMonth NVARCHAR(7) NOT NULL,
                                                   TeamFilter NVARCHAR(100) NULL,
                                                   Narrative NVARCHAR(MAX) NOT NULL,
                                                   GeneratedByAi BIT NOT NULL,
                                                   TotalAgents INT NULL,
                                                   AvgPresenceRate FLOAT NULL,
                                                   AvgQualityScore FLOAT NULL,
                                                   AvgPerformanceGlobale FLOAT NULL,
                                                   GeneratedByUsername NVARCHAR(100) NULL,
                                                   CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_DataAnalysisSnapshots_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                   UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_DataAnalysisSnapshots_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.DataAnalysisSnapshots';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.DataAnalysisSnapshots';
GO

IF OBJECT_ID('dbo.ActionAuditLogs', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ActionAuditLogs (
                                             AuditLogId INT IDENTITY PRIMARY KEY,
                                             Username NVARCHAR(100) NOT NULL,
                                             Action NVARCHAR(50) NOT NULL,
                                             Details NVARCHAR(2000) NULL,
                                             CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ActionAuditLogs_CreatedAt DEFAULT SYSUTCDATETIME(),
                                             UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ActionAuditLogs_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ActionAuditLogs';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ActionAuditLogs';
GO

/* ============================================================================
   8. TÂCHES ASSIGNÉES, RENDEZ-VOUS, VENTES, PARCOURS DE VENTE
   ============================================================================ */

IF OBJECT_ID('dbo.AssignedTasks', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.AssignedTasks (
                                           TaskId INT IDENTITY PRIMARY KEY,
                                           Title NVARCHAR(200) NOT NULL,
                                           Description NVARCHAR(2000) NULL,
                                           AssignedToUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                           AssignedToTeamCode NVARCHAR(50) NULL,
                                           CreatedByUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                           DueDate DATE NULL,
                                           Priority NVARCHAR(10) NOT NULL CONSTRAINT DF_AssignedTasks_Priority DEFAULT 'NORMAL',
                                           Status NVARCHAR(20) NOT NULL,
                                           Category NVARCHAR(30) NULL,
                                           Justified BIT NULL,
                                           RelatedDate DATE NULL,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_AssignedTasks_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_AssignedTasks_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.AssignedTasks';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.AssignedTasks';
GO

IF OBJECT_ID('dbo.Appointments', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.Appointments (
                                          AppointmentId INT IDENTITY PRIMARY KEY,
                                          AgentUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                          ClientName NVARCHAR(200) NOT NULL,
                                          ClientPhone NVARCHAR(50) NULL,
                                          Purpose NVARCHAR(300) NULL,
                                          ScheduledAt DATETIME2 NOT NULL,
                                          Status NVARCHAR(20) NOT NULL CONSTRAINT DF_Appointments_Status DEFAULT 'PLANNED',
                                          Notes NVARCHAR(1000) NULL,
                                          CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Appointments_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.Appointments';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.Appointments';
GO

IF OBJECT_ID('dbo.SalesRecords', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.SalesRecords (
                                          SaleId INT IDENTITY PRIMARY KEY,
                                          AgentUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                          ProductName NVARCHAR(150) NOT NULL,
                                          ClientName NVARCHAR(200) NULL,
                                          ClientPhone NVARCHAR(50) NULL,
                                          Amount FLOAT NULL,
                                          SaleDate DATE NOT NULL,
                                          Status NVARCHAR(20) NOT NULL CONSTRAINT DF_SalesRecords_Status DEFAULT 'CONFIRMED',
                                          Notes NVARCHAR(1000) NULL,
                                          CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_SalesRecords_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.SalesRecords';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.SalesRecords';
GO

IF OBJECT_ID('dbo.SalesJourneys', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.SalesJourneys (
                                           JourneyId INT IDENTITY PRIMARY KEY,
                                           JourneyKey NVARCHAR(60) NOT NULL UNIQUE,
                                           Title NVARCHAR(150) NOT NULL,
                                           Icon NVARCHAR(50) NULL,
                                           ColorFrom NVARCHAR(20) NULL,
                                           ColorTo NVARCHAR(20) NULL,
                                           Pitch NVARCHAR(300) NULL,
                                           StepsJson NVARCHAR(MAX) NOT NULL,
                                           SortOrder INT NOT NULL CONSTRAINT DF_SalesJourneys_Sort DEFAULT 0,
                                           Active BIT NOT NULL CONSTRAINT DF_SalesJourneys_Active DEFAULT 1,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_SalesJourneys_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_SalesJourneys_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.SalesJourneys';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.SalesJourneys';
GO

/* ============================================================================
   9. CAMPAGNES D'APPELS (Outbound / Inbound / Télévente)
   ============================================================================ */

IF OBJECT_ID('dbo.Campaigns', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.Campaigns (
                                       CampaignId INT IDENTITY(1,1) PRIMARY KEY,
                                       Name NVARCHAR(200) NOT NULL,
                                       Description NVARCHAR(1000) NULL,
                                       CreatedByUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                       StartDate DATE NULL,
                                       EndDate DATE NULL,
                                       Status NVARCHAR(20) NOT NULL CONSTRAINT DF_Campaigns_Status DEFAULT 'ACTIVE',
                                       TargetService NVARCHAR(20) NULL,
                                       IconClass NVARCHAR(50) NOT NULL CONSTRAINT DF_Campaigns_Icon DEFAULT 'bi-megaphone-fill',
                                       ColorFrom NVARCHAR(10) NOT NULL CONSTRAINT DF_Campaigns_ColorFrom DEFAULT '#0057B8',
                                       ColorTo NVARCHAR(10) NOT NULL CONSTRAINT DF_Campaigns_ColorTo DEFAULT '#00A651',
                                       CoverImageUrl NVARCHAR(500) NULL,
                                       FieldsJson NVARCHAR(4000) NULL,
                                       CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Campaigns_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.Campaigns';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.Campaigns';
GO

IF OBJECT_ID('dbo.CampaignContacts', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.CampaignContacts (
                                              ContactId           INT IDENTITY(1,1) PRIMARY KEY,
                                              CampaignId          INT NOT NULL CONSTRAINT FK_CampaignContacts_Campaign REFERENCES dbo.Campaigns(CampaignId),
                                              AgentUserId         BIGINT NULL,
                                              ClientName          NVARCHAR(200) NOT NULL,
                                              ClientPhone         NVARCHAR(50) NULL,
                                              MaskedAccountNumber NVARCHAR(30) NULL,
                                              CallStatus          NVARCHAR(20) NOT NULL CONSTRAINT DF_CampaignContacts_CallStatus DEFAULT 'PENDING',
                                              Notes               NVARCHAR(1000) NULL,
                                              AnswersJson         NVARCHAR(4000) NULL,
                                              ExtraDataJson       NVARCHAR(4000) NULL,
                                              LastCalledAt        DATETIME2 NULL,
                                              AppointmentId       INT NULL,
                                              CreatedAt           DATETIME2 NOT NULL CONSTRAINT DF_CampaignContacts_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        CREATE INDEX IX_CampaignContacts_CampaignId ON dbo.CampaignContacts(CampaignId);
        CREATE INDEX IX_CampaignContacts_AgentUserId ON dbo.CampaignContacts(AgentUserId);
        PRINT 'Créée : dbo.CampaignContacts';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.CampaignContacts';
GO
-- Rattrapage : ExtraDataJson peut manquer sur une table CampaignContacts créée
-- avant son introduction (voir échange du 17/09/2026 — ce cas précis a cassé
-- myNotifications/myContacts en production).
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.CampaignContacts') AND name = 'ExtraDataJson')
ALTER TABLE dbo.CampaignContacts ADD ExtraDataJson NVARCHAR(4000) NULL;
GO
/* ============================================================================
   10. MASQUES DE MAIL
   ============================================================================ */

IF OBJECT_ID('dbo.MailTemplates', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.MailTemplates (
                                           TemplateId INT IDENTITY PRIMARY KEY,
                                           CategoryId INT NOT NULL REFERENCES dbo.MailTemplateCategories(CategoryId),
                                           Subject NVARCHAR(300) NOT NULL,
                                           Body NVARCHAR(MAX) NOT NULL,
                                           RecipientType NVARCHAR(20) NOT NULL,
                                           RecipientGroupId INT NULL REFERENCES dbo.MailRecipientGroups(GroupId),
                                           CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                           IsSystemTemplate BIT NOT NULL,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_MailTemplates_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_MailTemplates_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.MailTemplates';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.MailTemplates';
GO

/* ============================================================================
   11. KNOWLEDGE BASE
   ============================================================================ */

IF OBJECT_ID('dbo.KnowledgeArticles', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.KnowledgeArticles (
                                               ArticleId INT IDENTITY PRIMARY KEY,
                                               CategoryId INT NOT NULL REFERENCES dbo.KnowledgeCategories(CategoryId),
                                               CountryCode NVARCHAR(2) NULL REFERENCES dbo.KnowledgeCountries(CountryCode),
                                               ServiceId INT NULL REFERENCES dbo.SERVICES(ID),
                                               Title NVARCHAR(200) NOT NULL,
                                               ContentHtml NVARCHAR(MAX) NOT NULL,
                                               Tags NVARCHAR(300) NULL,
                                               SortOrder INT NOT NULL,
                                               CreatedByUserId INT NULL,
                                               CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_KnowledgeArticles_CreatedAt DEFAULT SYSUTCDATETIME(),
                                               UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_KnowledgeArticles_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.KnowledgeArticles';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.KnowledgeArticles';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.KnowledgeArticles') AND name = 'ServiceId')
ALTER TABLE dbo.KnowledgeArticles ADD ServiceId INT NULL REFERENCES dbo.SERVICES(ID);
GO

/* ============================================================================
   12. CENTRE DE JEUX
   ============================================================================ */

IF OBJECT_ID('dbo.GameDefinitions', 'U') IS NULL
    BEGIN
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
                                             SortOrder INT NOT NULL CONSTRAINT DF_GameDefinitions_Sort DEFAULT 0,
                                             Active BIT NOT NULL CONSTRAINT DF_GameDefinitions_Active DEFAULT 1,
                                             EvaluationRound INT NOT NULL CONSTRAINT DF_GameDefinitions_EvalRound DEFAULT 1,
                                             TargetTeam NVARCHAR(30) NULL,
                                             CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameDefinitions_CreatedAt DEFAULT SYSUTCDATETIME(),
                                             UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameDefinitions_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.GameDefinitions';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.GameDefinitions';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.GameDefinitions') AND name = 'EvaluationRound')
ALTER TABLE dbo.GameDefinitions ADD EvaluationRound INT NOT NULL CONSTRAINT DF_GameDefinitions_EvalRound2 DEFAULT 1;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.GameDefinitions') AND name = 'TargetTeam')
ALTER TABLE dbo.GameDefinitions ADD TargetTeam NVARCHAR(30) NULL;
GO

IF OBJECT_ID('dbo.GameScores', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.GameScores (
                                        ScoreId INT IDENTITY(1,1) PRIMARY KEY,
                                        GameKey VARCHAR(50) NOT NULL,
                                        UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                        Score INT NOT NULL,
                                        CorrectCount INT NULL,
                                        TotalCount INT NULL,
                                        PlayedAt DATETIME2 NOT NULL CONSTRAINT DF_GameScores_PlayedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.GameScores';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.GameScores';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_GameScores_GameKey')
CREATE INDEX IX_GameScores_GameKey ON dbo.GameScores(GameKey, Score DESC);
GO

IF OBJECT_ID('dbo.QuizQuestions', 'U') IS NULL
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
                                           VideoUrl NVARCHAR(500) NULL,
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
        PRINT 'Créée : dbo.QuizQuestions';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.QuizQuestions';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.QuizQuestions') AND name = 'VideoUrl')
ALTER TABLE dbo.QuizQuestions ADD VideoUrl NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_QuizQuestions_Category')
CREATE INDEX IX_QuizQuestions_Category ON dbo.QuizQuestions(Category);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_QuizQuestions_Difficulty')
CREATE INDEX IX_QuizQuestions_Difficulty ON dbo.QuizQuestions(Difficulty);
GO

IF OBJECT_ID('dbo.GameCompetitions', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.GameCompetitions (
                                              CompetitionId INT IDENTITY PRIMARY KEY,
                                              GameKey NVARCHAR(50) NOT NULL,
                                              Title NVARCHAR(200) NOT NULL,
                                              ScheduledAt DATETIME2 NOT NULL,
                                              Status NVARCHAR(30) NOT NULL CONSTRAINT DF_GameCompetitions_Status DEFAULT 'DRAFT',
                                              IsTraineeOnly BIT NOT NULL CONSTRAINT DF_GameCompetitions_TraineeOnly DEFAULT 0,
                                              CreatedByUsername NVARCHAR(100) NULL,
                                              CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameCompetitions_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.GameCompetitions';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.GameCompetitions';
GO

IF OBJECT_ID('dbo.GameCompetitionTeams', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.GameCompetitionTeams (
                                                  CompetitionTeamId INT IDENTITY PRIMARY KEY,
                                                  CompetitionId INT NOT NULL REFERENCES dbo.GameCompetitions(CompetitionId),
                                                  Team NVARCHAR(30) NOT NULL,
                                                  Validated BIT NOT NULL CONSTRAINT DF_GameCompetitionTeams_Validated DEFAULT 0,
                                                  ValidatedAt DATETIME2 NULL,
                                                  ValidatedByUsername NVARCHAR(100) NULL
        );
        PRINT 'Créée : dbo.GameCompetitionTeams';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.GameCompetitionTeams';
GO

IF OBJECT_ID('dbo.GameCompetitionParticipants', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.GameCompetitionParticipants (
                                                         ParticipantId INT IDENTITY PRIMARY KEY,
                                                         CompetitionId INT NOT NULL REFERENCES dbo.GameCompetitions(CompetitionId),
                                                         UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                         Team NVARCHAR(30) NOT NULL,
                                                         Score INT NULL,
                                                         CompletedAt DATETIME2 NULL,
                                                         CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameCompetitionParticipants_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.GameCompetitionParticipants';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.GameCompetitionParticipants';
GO

IF OBJECT_ID('dbo.GameEvaluationAttempts', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.GameEvaluationAttempts (
                                                    AttemptId INT IDENTITY PRIMARY KEY,
                                                    GameKey NVARCHAR(50) NOT NULL,
                                                    UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                    AttemptNumber INT NOT NULL,
                                                    EvaluationRound INT NOT NULL CONSTRAINT DF_GameEvaluationAttempts_Round DEFAULT 1,
                                                    Score INT NOT NULL,
                                                    CorrectCount INT NULL,
                                                    TotalCount INT NULL,
                                                    AnswersJson NVARCHAR(MAX) NULL,
                                                    CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameEvaluationAttempts_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.GameEvaluationAttempts';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.GameEvaluationAttempts';
GO

IF OBJECT_ID('dbo.GameEvaluationUnlocks', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.GameEvaluationUnlocks (
                                                   UnlockId INT IDENTITY PRIMARY KEY,
                                                   GameKey NVARCHAR(50) NOT NULL,
                                                   UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                   UnlockedByUsername NVARCHAR(100) NULL,
                                                   CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameEvaluationUnlocks_CreatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.GameEvaluationUnlocks';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.GameEvaluationUnlocks';
GO

/* ============================================================================
   13. MESSAGERIE INTERNE
   ============================================================================ */

IF OBJECT_ID('dbo.Conversations', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.Conversations (
                                           ConversationId INT IDENTITY(1,1) PRIMARY KEY,
                                           Type VARCHAR(10) NOT NULL CONSTRAINT CK_Conversations_Type CHECK (Type IN ('DM','GROUP')),
                                           Name NVARCHAR(150) NULL,
                                           CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Conversations_CreatedAt DEFAULT SYSUTCDATETIME(),
                                           UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_Conversations_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.Conversations';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.Conversations';
GO

IF OBJECT_ID('dbo.ConversationParticipants', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ConversationParticipants (
                                                      ParticipantId INT IDENTITY(1,1) PRIMARY KEY,
                                                      ConversationId INT NOT NULL CONSTRAINT FK_ConvPart_Conversation REFERENCES dbo.Conversations(ConversationId) ON DELETE CASCADE,
                                                      UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                      LastReadAt DATETIME2 NULL,
                                                      CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ConvPart_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                      UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ConvPart_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                                      CONSTRAINT UQ_ConvPart_Conv_User UNIQUE (ConversationId, UserId)
        );
        PRINT 'Créée : dbo.ConversationParticipants';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ConversationParticipants';
GO

IF OBJECT_ID('dbo.ChatMessages', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.ChatMessages (
                                          MessageId INT IDENTITY(1,1) PRIMARY KEY,
                                          ConversationId INT NOT NULL CONSTRAINT FK_ChatMsg_Conversation REFERENCES dbo.Conversations(ConversationId) ON DELETE CASCADE,
                                          SenderUserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                          Content NVARCHAR(MAX) NOT NULL,
                                          MediaUrl NVARCHAR(500) NULL,
                                          ExpiresAt DATETIME2 NULL,
                                          SentAt DATETIME2 NOT NULL CONSTRAINT DF_ChatMsg_SentAt DEFAULT SYSUTCDATETIME(),
                                          CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ChatMsg_CreatedAt DEFAULT SYSUTCDATETIME(),
                                          UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ChatMsg_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.ChatMessages';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.ChatMessages';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.ChatMessages') AND name = 'MediaUrl')
ALTER TABLE dbo.ChatMessages ADD MediaUrl NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.ChatMessages') AND name = 'ExpiresAt')
ALTER TABLE dbo.ChatMessages ADD ExpiresAt DATETIME2 NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_ChatMessages_Conversation_SentAt')
CREATE INDEX IX_ChatMessages_Conversation_SentAt ON dbo.ChatMessages(ConversationId, SentAt);
GO

/* ============================================================================
   14. MON RCC (réseau social interne)
   ============================================================================ */

IF OBJECT_ID('dbo.RccCommunities', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccCommunities (
                                            CommunityId INT IDENTITY PRIMARY KEY,
                                            CommunityKey NVARCHAR(50) NOT NULL UNIQUE,
                                            Label NVARCHAR(100) NOT NULL,
                                            SortOrder INT NOT NULL CONSTRAINT DF_RccCommunities_Sort DEFAULT 0,
                                            CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccCommunities_CreatedAt DEFAULT SYSUTCDATETIME(),
                                            UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccCommunities_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.RccCommunities';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccCommunities';
GO

IF OBJECT_ID('dbo.RccPosts', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccPosts (
                                      PostId INT IDENTITY PRIMARY KEY,
                                      AuthorUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                      AuthorLabel NVARCHAR(150) NOT NULL,
                                      Content NVARCHAR(MAX) NOT NULL,
                                      ImageUrl NVARCHAR(500) NULL,
                                      ViewCount INT NOT NULL DEFAULT 0,
                                      PublishedAt DATETIME2 NOT NULL,
                                      ExpiresAt DATETIME2 NULL,
                                      CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPosts_CreatedAt DEFAULT SYSUTCDATETIME(),
                                      UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPosts_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.RccPosts';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccPosts';
GO

IF OBJECT_ID('dbo.RccPostComments', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccPostComments (
                                             CommentId INT IDENTITY PRIMARY KEY,
                                             PostId INT NOT NULL REFERENCES dbo.RccPosts(PostId),
                                             AuthorUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                             AuthorLabel NVARCHAR(150) NOT NULL,
                                             Content NVARCHAR(1000) NOT NULL,
                                             CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPostComments_CreatedAt DEFAULT SYSUTCDATETIME(),
                                             UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPostComments_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.RccPostComments';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccPostComments';
GO

IF OBJECT_ID('dbo.RccPostLikes', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccPostLikes (
                                          RccPostLikeId INT IDENTITY PRIMARY KEY,
                                          PostId INT NOT NULL REFERENCES dbo.RccPosts(PostId),
                                          UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                          CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPostLikes_CreatedAt DEFAULT SYSUTCDATETIME(),
                                          UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccPostLikes_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                          CONSTRAINT UQ_RccPostLikes_Post_User UNIQUE (PostId, UserId)
        );
        PRINT 'Créée : dbo.RccPostLikes';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccPostLikes';
GO

IF OBJECT_ID('dbo.RccStories', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccStories (
                                        StoryId INT IDENTITY PRIMARY KEY,
                                        AuthorLabel NVARCHAR(150) NOT NULL,
                                        Content NVARCHAR(500) NULL,
                                        ImageUrl NVARCHAR(500) NULL,
                                        PublishedAt DATETIME2 NOT NULL,
                                        ExpiresAt DATETIME2 NULL,
                                        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccStories_CreatedAt DEFAULT SYSUTCDATETIME(),
                                        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccStories_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.RccStories';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccStories';
GO

IF OBJECT_ID('dbo.RccCommunityFollows', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccCommunityFollows (
                                                 RccCommunityFollowId INT IDENTITY PRIMARY KEY,
                                                 UserId BIGINT NOT NULL REFERENCES dbo.USERS(ID),
                                                 CommunityKey NVARCHAR(50) NOT NULL,
                                                 CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccCommunityFollows_CreatedAt DEFAULT SYSUTCDATETIME(),
                                                 UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccCommunityFollows_UpdatedAt DEFAULT SYSUTCDATETIME(),
                                                 CONSTRAINT UQ_RccCommunityFollows_User_Community UNIQUE (UserId, CommunityKey)
        );
        PRINT 'Créée : dbo.RccCommunityFollows';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccCommunityFollows';
GO

IF OBJECT_ID('dbo.RccNotifications', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.RccNotifications (
                                              NotificationId INT IDENTITY PRIMARY KEY,
                                              TargetUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                              Content NVARCHAR(500) NOT NULL,
                                              IsRead BIT NOT NULL,
                                              ActionType NVARCHAR(50) NULL,
                                              ActionTarget NVARCHAR(100) NULL,
                                              CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccNotifications_CreatedAt DEFAULT SYSUTCDATETIME(),
                                              UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_RccNotifications_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.RccNotifications';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.RccNotifications';
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.RccNotifications') AND name = 'ActionType')
ALTER TABLE dbo.RccNotifications ADD ActionType NVARCHAR(50) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.RccNotifications') AND name = 'ActionTarget')
ALTER TABLE dbo.RccNotifications ADD ActionTarget NVARCHAR(100) NULL;
GO

IF OBJECT_ID('dbo.NewsArticles', 'U') IS NULL
    BEGIN
        CREATE TABLE dbo.NewsArticles (
                                          NewsId INT IDENTITY PRIMARY KEY,
                                          Title NVARCHAR(200) NOT NULL,
                                          ContentHtml NVARCHAR(MAX) NOT NULL,
                                          ImageUrl NVARCHAR(500) NULL,
                                          CreatedByUserId BIGINT NULL REFERENCES dbo.USERS(ID),
                                          SortOrder INT NOT NULL CONSTRAINT DF_NewsArticles_Sort DEFAULT 0,
                                          CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_NewsArticles_CreatedAt DEFAULT SYSUTCDATETIME(),
                                          UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_NewsArticles_UpdatedAt DEFAULT SYSUTCDATETIME()
        );
        PRINT 'Créée : dbo.NewsArticles';
    END ELSE PRINT 'Déjà présente, ignorée : dbo.NewsArticles';
GO

PRINT '============================================================';
PRINT 'Script maître terminé — base de données à jour (régénéré depuis les entités JPA au 17/09/2026).';
PRINT '============================================================';
GO