-- Parcours de connectivité : IT/administrateur a un accès direct au portail ;
-- tout autre compte (agent) doit être explicitement autorisé par un
-- administrateur avant de pouvoir se connecter (voir AuthService.
-- assertAccountUsable, UserService.approve/reject, UserController).
--
-- DEFAULT 'APPROVED' : les comptes déjà existants (seedés avant cette
-- fonctionnalité, y compris les comptes admin) restent utilisables sans
-- action manuelle. Seuls les NOUVEAUX comptes créés via l'auto-inscription
-- (/api/auth/register) partent en 'PENDING' (voir AuthService.registerAccount).
--
-- À exécuter une seule fois contre la base bd-rcc existante.
-- QUOTED_IDENTIFIER doit être ON pour ajouter une colonne avec une contrainte
-- DEFAULT (sqlcmd l'a OFF par défaut selon le driver/la session -> erreur
-- "SET options have incorrect settings" sinon). Les deux ALTER TABLE sont
-- séparés par GO : SQL Server compile un batch entier avant de l'exécuter,
-- donc la contrainte CHECK ne "voit" la colonne Status que si l'ajout de
-- colonne a déjà été validé dans un batch précédent.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.Users') AND name = 'Status'
)
BEGIN
    ALTER TABLE dbo.Users ADD Status VARCHAR(20) NOT NULL
        CONSTRAINT DF_Users_Status DEFAULT 'APPROVED';
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints WHERE name = 'CK_Users_Status'
)
BEGIN
    ALTER TABLE dbo.Users ADD CONSTRAINT CK_Users_Status
        CHECK (Status IN ('PENDING', 'APPROVED', 'REJECTED'));
END
GO
