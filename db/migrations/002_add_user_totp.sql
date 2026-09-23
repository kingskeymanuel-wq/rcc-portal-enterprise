-- Authentification 2FA réelle par TOTP (Microsoft/Google Authenticator) — remplace les
-- codes envoyés par e-mail (voir AuthService, TotpService). TotpSecret est généré au
-- premier login réussi (mot de passe/AD validé) et n'est considéré "actif" qu'une fois
-- confirmé par un premier code correct (TotpConfirmedAt non nul) — voir
-- AuthService.completeLogin.
--
-- À exécuter une seule fois contre la base bd-rcc existante, après 001_add_user_status.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.Users') AND name = 'TotpSecret'
)
BEGIN
    ALTER TABLE dbo.Users ADD TotpSecret VARCHAR(64) NULL;
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.Users') AND name = 'TotpConfirmedAt'
)
BEGIN
    ALTER TABLE dbo.Users ADD TotpConfirmedAt DATETIME2 NULL;
END
GO
