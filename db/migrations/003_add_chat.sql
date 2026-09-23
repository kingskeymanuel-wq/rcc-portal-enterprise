-- Messagerie interne (Conversations DM/GROUP + ChatMessages) — remplace la messagerie
-- "MON RCC" qui était entièrement fictive côté frontend (localStorage 'eco_rcc_msg',
-- fausses conversations pré-remplies). Voir ChatService / ChatController.
--
-- À exécuter une seule fois contre la base bd-rcc existante, après 002_add_user_totp.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'Conversations' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.Conversations (
        ConversationId INT IDENTITY(1,1) PRIMARY KEY,
        Type VARCHAR(10) NOT NULL CONSTRAINT CK_Conversations_Type CHECK (Type IN ('DM','GROUP')),
        Name NVARCHAR(150) NULL,
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_Conversations_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_Conversations_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'ConversationParticipants' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.ConversationParticipants (
        ParticipantId INT IDENTITY(1,1) PRIMARY KEY,
        ConversationId INT NOT NULL CONSTRAINT FK_ConvPart_Conversation REFERENCES dbo.Conversations(ConversationId) ON DELETE CASCADE,
        UserId INT NOT NULL CONSTRAINT FK_ConvPart_User REFERENCES dbo.Users(UserId),
        LastReadAt DATETIME2 NULL,
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ConvPart_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ConvPart_UpdatedAt DEFAULT SYSUTCDATETIME(),
        CONSTRAINT UQ_ConvPart_Conv_User UNIQUE (ConversationId, UserId)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'ChatMessages' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.ChatMessages (
        MessageId INT IDENTITY(1,1) PRIMARY KEY,
        ConversationId INT NOT NULL CONSTRAINT FK_ChatMsg_Conversation REFERENCES dbo.Conversations(ConversationId) ON DELETE CASCADE,
        SenderUserId INT NOT NULL CONSTRAINT FK_ChatMsg_Sender REFERENCES dbo.Users(UserId),
        Content NVARCHAR(MAX) NOT NULL,
        SentAt DATETIME2 NOT NULL CONSTRAINT DF_ChatMsg_SentAt DEFAULT SYSUTCDATETIME(),
        CreatedAt DATETIME2 NOT NULL CONSTRAINT DF_ChatMsg_CreatedAt DEFAULT SYSUTCDATETIME(),
        UpdatedAt DATETIME2 NOT NULL CONSTRAINT DF_ChatMsg_UpdatedAt DEFAULT SYSUTCDATETIME()
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_ChatMessages_Conversation_SentAt')
BEGIN
    CREATE INDEX IX_ChatMessages_Conversation_SentAt ON dbo.ChatMessages(ConversationId, SentAt);
END
GO
