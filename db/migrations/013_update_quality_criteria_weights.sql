-- Corrige les poids des 16 critères de la grille d'écoute qualité (dbo.QualityCriteria),
-- qui avaient tous été insérés avec Weight=1 (voir WorkflowSchemaBootstrap.seedRealQualityCriteriaIfMissing,
-- désormais corrigé pour les nouvelles installations — cette migration rattrape une base déjà seedée).
--
-- Poids repris du barème officiel Ecobank : Accueil 10, Compréhension 10, Efficacité 15,
-- Engagement 5, Excellence 5, Empathie 10, Résolution 10, Communication 5, Authentification 5
-- (même poids sur les variantes Voice/Chat d'un même critère — une seule s'applique par évaluation
-- selon le canal), Clôture 5 (idem Voice/Chat). Le bloc Documentation/Conformité (5 points dans la
-- grille d'origine) est réparti entre les 3 critères qui coexistent dans une même évaluation :
-- Documentation 2, Conformité du cas 1, Engagement Ecobank 2 (éliminatoire, IsKnockOut déjà à 1).
-- Total par évaluation (Voice ou Chat) : 85, identique à la grille d'origine.
--
-- Idempotent : peut être exécuté plusieurs fois sans risque (UPDATE ... WHERE Code = ...).
-- A exécuter une seule fois contre la base existante, après 012_add_bank_branches.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF EXISTS (SELECT 1 FROM sys.tables WHERE name = 'QualityCriteria' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    UPDATE dbo.QualityCriteria SET Weight = 10 WHERE Code = 'GREET';
    UPDATE dbo.QualityCriteria SET Weight = 10 WHERE Code = 'RECOGNIZE';
    UPDATE dbo.QualityCriteria SET Weight = 15 WHERE Code = 'ACCESS';
    UPDATE dbo.QualityCriteria SET Weight = 5  WHERE Code = 'DOWHATSAY';
    UPDATE dbo.QualityCriteria SET Weight = 5  WHERE Code = 'EXTRAMILE';
    UPDATE dbo.QualityCriteria SET Weight = 10 WHERE Code = 'EMPATHY';
    UPDATE dbo.QualityCriteria SET Weight = 10 WHERE Code = 'RESOLVE';
    UPDATE dbo.QualityCriteria SET Weight = 5  WHERE Code = 'KEEPINFO';
    UPDATE dbo.QualityCriteria SET Weight = 5  WHERE Code = 'AUTHCUST';
    UPDATE dbo.QualityCriteria SET Weight = 5  WHERE Code = 'CHAT_AUTH';
    UPDATE dbo.QualityCriteria SET Weight = 2  WHERE Code = 'CASEDOC';
    UPDATE dbo.QualityCriteria SET Weight = 2  WHERE Code = 'CHAT_DOC';
    UPDATE dbo.QualityCriteria SET Weight = 5  WHERE Code = 'CHATCLOSE';
    UPDATE dbo.QualityCriteria SET Weight = 5  WHERE Code = 'CHAT_CLOSE';
    UPDATE dbo.QualityCriteria SET Weight = 1  WHERE Code = 'CASECOMPLY';
    UPDATE dbo.QualityCriteria SET Weight = 2  WHERE Code = 'ENGKO';
END
GO
