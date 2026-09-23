-- Centre de jeux : 10 jeux (GameDefinitions), classement (GameScores), vocabulaire
-- bancaire (WordTerms, alimente JoliGo/Mémoire/Pendu). Le jeu "Parcours Client"
-- ne stocke rien de nouveau — il réutilise ProcedureWorkflowNodes/Options déjà
-- existants (vrais parcours saisis par QA), voir ProcessPuzzleService.
--
-- A executer une seule fois contre la base existante, apres 006_add_templates_and_leave_balance.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'GameDefinitions' AND schema_id = SCHEMA_ID('dbo'))
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
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameDefinitions_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_GameDefinitions_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'GameScores' AND schema_id = SCHEMA_ID('dbo'))
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
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_GameScores_GameKey')
    CREATE INDEX IX_GameScores_GameKey ON dbo.GameScores(GameKey, Score DESC);
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'WordTerms' AND schema_id = SCHEMA_ID('dbo'))
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
END
GO

-- ===================== SEED : LES 10 JEUX =====================

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'quiz-eclair')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('quiz-eclair', 'MCQ_STANDARD', 'Quiz Éclair', 'Un quiz rapide et efficace pour réviser tous les thèmes en quelques minutes.', 'bi-lightning-charge', '#0057B8', '#00A651', '{"questionCount":8}', 1);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'millions-ecobank')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('millions-ecobank', 'MCQ_LADDER', 'Qui Veut Gagner des Millions Ecobank', 'Grimpez les paliers de difficulté avec vos deux jokers — sécurisez vos points ou tentez le tout pour le tout.', 'bi-cash-coin', '#F5A623', '#8B5E00', '{"questionCount":10}', 2);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'vrai-faux-chrono')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('vrai-faux-chrono', 'TRUE_FALSE_RAPID', 'Vrai ou Faux Chrono', 'Répondez vrai ou faux le plus vite possible et enchaînez les combos avant la fin du chrono.', 'bi-toggle2-on', '#7B2FF7', '#F72585', '{"timeLimitSeconds":30}', 3);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'roue-fortune')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('roue-fortune', 'WHEEL', 'La Roue de la Fortune Ecobank', 'Lancez la roue pour choisir votre thème, puis répondez pour multiplier vos gains.', 'bi-disc', '#00A651', '#0057B8', '{}', 4);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'joligo-ecobank')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('joligo-ecobank', 'WORD_GUESS', 'JoliGo Ecobank', 'Devinez le terme bancaire lettre par lettre à partir de sa définition.', 'bi-alphabet-uppercase', '#D93025', '#F5A623', '{"maxAttempts":6}', 5);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'duel-chrono')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('duel-chrono', 'MCQ_DUEL', 'Duel Chrono', 'Battez le score à battre avant la fin du temps imparti — face à vous-même ou vos collègues.', 'bi-people-fill', '#0057B8', '#7B2FF7', '{"timeLimitSeconds":60}', 6);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'memoire-ecobank')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('memoire-ecobank', 'MEMORY', 'Mémoire Ecobank', 'Retrouvez les paires terme / définition en un minimum de coups.', 'bi-grid-3x3-gap-fill', '#00A651', '#7B2FF7', '{"pairCount":6}', 7);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'pendu-bancaire')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('pendu-bancaire', 'HANGMAN', 'Le Pendu Bancaire', 'Devinez le mot avant d''épuiser vos essais.', 'bi-emoji-frown', '#6b7280', '#D93025', '{"maxWrong":6}', 8);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'chrono-challenge')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('chrono-challenge', 'MCQ_SURVIVAL', 'Chrono Challenge 60s', 'Enchaînez un maximum de bonnes réponses en 60 secondes — la difficulté augmente au fil du jeu.', 'bi-stopwatch', '#F72585', '#7B2FF7', '{"timeLimitSeconds":60}', 9);
GO

IF NOT EXISTS (SELECT 1 FROM dbo.GameDefinitions WHERE GameKey = 'parcours-client')
INSERT INTO dbo.GameDefinitions (GameKey, Mechanic, Title, Description, Icon, ColorFrom, ColorTo, ConfigJson, SortOrder)
VALUES ('parcours-client', 'PROCESS_ORDER', 'Puzzle du Parcours Client', 'Remettez dans le bon ordre les vraies étapes d''un parcours de traitement Ecobank.', 'bi-signpost-split', '#0057B8', '#00A651', '{}', 10);
GO

-- ===================== SEED : VOCABULAIRE BANCAIRE (définitions génériques, jamais de politique Ecobank non vérifiée) =====================

IF NOT EXISTS (SELECT 1 FROM dbo.WordTerms WHERE Term = 'RIB')
INSERT INTO dbo.WordTerms (Term, Definition, Category) VALUES
('RIB', 'Relevé d''Identité Bancaire — document qui identifie un compte bancaire (banque, guichet, numéro de compte, clé).', 'COMPTE'),
('OTP', 'One Time Password — code à usage unique envoyé pour valider une connexion ou une transaction sécurisée.', 'CONNEXION PRODUITS DIGITAUX'),
('DAB', 'Distributeur Automatique de Billets — permet de retirer de l''argent sans passer par un caissier.', 'CARTE ATM'),
('GAB', 'Guichet Automatique Bancaire — équivalent du DAB, parfois utilisé pour désigner des opérations plus larges (dépôt, consultation).', 'CARTE ATM'),
('TPE', 'Terminal de Paiement Électronique — appareil utilisé en magasin pour régler un achat par carte bancaire.', 'CARTE ATM'),
('KYC', 'Know Your Customer — processus de vérification de l''identité du client, obligatoire avant certaines opérations bancaires.', 'AUTRE'),
('VBV', 'Verified by Visa — sécurité supplémentaire (code ou OTP) demandée lors d''un paiement en ligne par carte Visa.', 'CARTE ATM'),
('SWIFT', 'Réseau international sécurisé de messagerie interbancaire ; chaque banque possède un code SWIFT/BIC qui l''identifie pour les virements internationaux.', 'TRANSFERT'),
('CASHXPRESS', 'Carte prépayée permettant de retirer et de payer sans nécessiter un compte bancaire classique.', 'CASHXPRESS'),
('MMH', 'Mobile Money Hub — passerelle qui relie un compte bancaire à un portefeuille mobile money.', 'MMH'),
('B2W', 'Bank to Wallet — transfert d''argent d''un compte bancaire vers un portefeuille mobile money.', 'MMH'),
('W2B', 'Wallet to Bank — transfert d''argent d''un portefeuille mobile money vers un compte bancaire.', 'MMH'),
('GIM UEMOA', 'Groupement Interbancaire Monétique de l''UEMOA — réseau régional permettant d''utiliser sa carte dans les distributeurs d''autres banques de la zone.', 'CARTE ATM'),
('DAT', 'Dépôt à Terme — placement d''argent bloqué pendant une durée fixée, généralement rémunéré par un taux d''intérêt.', 'COMPTE'),
('ECOBANK ONLINE', 'Service de banque à distance accessible depuis un navigateur web.', 'CONNEXION PRODUITS DIGITAUX'),
('ECOBANK MOBILE APP', 'Application mobile bancaire permettant de consulter ses comptes et d''effectuer des opérations depuis un smartphone.', 'CONNEXION PRODUITS DIGITAUX'),
('ECOBANK PAY', 'Solution de paiement mobile proposée par la banque.', 'CONNEXION PRODUITS DIGITAUX'),
('E-ALERT', 'Service de notification automatique (SMS ou email) des mouvements sur un compte.', 'CONNEXION PRODUITS DIGITAUX'),
('E-STATEMENT', 'Relevé de compte transmis électroniquement plutôt qu''en version papier.', 'CONNEXION PRODUITS DIGITAUX'),
('RAPIDTRANSFER', 'Service de transfert d''argent rapide entre particuliers.', 'TRANSFERT'),
('CHEQUE', 'Moyen de paiement écrit ordonnant à la banque de verser une somme déterminée à un bénéficiaire.', 'CHEQUE'),
('CHEQUIER', 'Carnet contenant plusieurs chèques remis au titulaire d''un compte.', 'CHEQUE'),
('OPPOSITION', 'Démarche permettant de bloquer l''utilisation d''un chèque ou d''une carte, généralement en cas de perte ou de vol.', 'CARTE ATM'),
('PROCURATION', 'Autorisation donnée par le titulaire d''un compte à une autre personne pour effectuer des opérations en son nom.', 'COMPTE'),
('COMPTE DORMANT', 'Compte resté inactif (sans opération) pendant une longue période, généralement soumis à des restrictions.', 'COMPTE'),
('SUCCESSION', 'Ensemble des démarches bancaires réalisées après le décès du titulaire d''un compte.', 'COMPTE'),
('VIREMENT', 'Opération consistant à transférer une somme d''argent d''un compte vers un autre.', 'TRANSFERT'),
('PRELEVEMENT', 'Paiement automatique, récurrent ou ponctuel, autorisé par le titulaire du compte au profit d''un tiers.', 'COMPTE');
GO
