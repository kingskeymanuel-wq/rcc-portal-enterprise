# RCC Portal Enterprise — Documentation de la base de données

Ce document accompagne `01_schema.sql`. Il explique à quoi sert chaque groupe
de tables, comment les modules du site s'articulent entre eux, et comment
interroger la base en pratique.

## Comment appliquer le schéma

```sql
-- Sur une base vide (bd-rcc ou équivalent) :
sqlcmd -S <serveur> -d <base> -i 01_schema.sql
```

Le script est idempotent au niveau de la structure (il échouera s'il est
rejoué sur une base qui a déjà les tables — c'est volontaire, pour ne jamais
écraser des données réelles par erreur). Pour un environnement de
développement qui a besoin de recréer une table isolée manquante,
`WorkflowSchemaBootstrap` (Java, démarrage de l'appli) fait ça automatiquement
pour un sous-ensemble de tables — mais ce script SQL reste la référence
complète pour tout déploiement réel.

## Vue d'ensemble par module

### 1–2. Utilisateurs et accès (`ROLES`, `Teams`, `SERVICES`, `USERS`, `USER_ROLES`, `USER_SERVICES`, `UserProfiles`, `RefreshTokens`, `LoginAudit`, `TabPermissions`)

`USERS` est la table centrale — presque toutes les autres tables la
référencent (`UserId`/`AuthorUserId`/`AgentUserId`...). Un utilisateur a :
- Un ou plusieurs rôles via `USER_ROLES` → `ROLES` (ADMIN, AGENT...)
- Un ou plusieurs services via `USER_SERVICES` → `SERVICES` (le "pôle
  d'activité" — INBOUND, OUTBOUND, QUALITY_ASSURANCE...). C'est ce champ qui
  détermine si quelqu'un est traité comme QA dans toute l'application.
- Un profil optionnel (`UserProfiles` — photo, téléphone, bio)
- Une filiale (`AFFILIATE_ID`/`AFFILIATE_BRANCH`, directement sur `USERS`)

`RefreshTokens` et `LoginAudit` sont gérés par le flux d'authentification
(AD + serveur MFA externe — voir `AuthService.java`) ; jamais modifiés
manuellement. `TabPermissions` restreint l'accès à un onglet du site par
équipe et/ou rôle.

**Piège connu à ne pas reproduire** : le champ `SERVICES.CODE` est stocké en
majuscules avec underscore (`QUALITY_ASSURANCE`), jamais comme le libellé
d'affichage (`Quality Assurance`). Toute comparaison côté code doit
normaliser (`.toLowerCase().replace('_', ' ')`) avant de comparer — plusieurs
bugs réels sont venus de l'oubli de cette normalisation.

### 3. Shift, présence, workflow de demandes (`ShiftEvents`, `AttendanceRecords`, `WorkflowRequests`)

`ShiftEvents` est un journal **append-only** (jamais de UPDATE/DELETE) des
événements de connexion/pause d'un agent — sert à calculer le taux de
présence (`ShiftService.computePresenceRate`). `WorkflowRequests` est le
moteur générique de demandes à valider (congé, changement de procédure,
matériel) : une demande est assignée à une équipe (QA ou ADMIN) qui seule
peut la voir et la décider.

### 4. Procédures (`Procedures`, `ProcedureSteps`, `ProcedureWorkflowNodes`, `ProcedureWorkflowOptions`, `FavoriteProcedures`, `Attachments`)

Deux façons de représenter une procédure coexistent : `ProcedureSteps` (liste
texte simple, l'ancienne approche) et `ProcedureWorkflowNodes` +
`ProcedureWorkflowOptions` (parcours interactif en arbre de décision — un
nœud = une question, chaque option mène soit à un autre nœud, soit à une fin
de parcours). `Attachments` est une table générique réutilisée par plusieurs
modules (`EntityType`/`EntityId` identifient la cible — pas de FK SQL
possible puisque la cible varie).

### 5. Formation (`Courses`, `CourseQuestions`, `CourseAttempts`)

Un cours est `STANDARD` (questionnaire à bonne/mauvaise réponse) ou
`SELF_ASSESSMENT` (auto-diagnostic 1-5, pas de bonne réponse).
`CourseAttempts` est la progression d'un agent sur un cours — une seule
tentative par (cours, utilisateur), les réponses sont stockées en JSON brut
dans `AnswersJson` plutôt qu'une table séparée (volume raisonnable).

### 6. Qualité — Clairaudio (`QualityCriteria`, `QualityCriterionAttributes`, `QualityMotifs`, `QualityEvaluations`, `QualityEvaluationScores`, `CoachingPlans`, `AgentDossiers`, `KpiEvents`, `ManualKpiEntries`)

`QualityCriteria` est le référentiel des critères C1-C12 (pondération,
caractère éliminatoire). Une évaluation (`QualityEvaluations`) note un agent
sur un appel, avec un score par critère (`QualityEvaluationScores`) — le
calcul du score final (moyenne pondérée + vérification des critères
éliminatoires) est fait en Java (`QualityScoreCalculator`), jamais en SQL.
`ManualKpiEntries` porte les métriques saisies à la main (CAS_CREES,
TAUX_ATTEINTE_OBJECTIF...) utilisées par la page Ma Performance —
`PerformanceService.computeGlobalScore` calcule le score global à partir de
3 métriques précises + le score qualité moyen.

### 7. Masques de mail (`MailTemplates`, `MailTemplateCategories`, `MailRecipientGroups`)

Un masque appartient à une catégorie et cible soit une personne
(`RecipientType = 'person'`), soit un groupe préconfiguré
(`RecipientType = 'service'` → `MailRecipientGroups`).

### 8. Knowledge Base (`KnowledgeArticles`, `KnowledgeCategories`, `KnowledgeCountries`)

Un article appartient à une catégorie ; `CountryCode` est optionnel — vide
signifie "valable pour toutes les filiales", renseigné signifie "spécifique à
ce pays" (tarifs, offres locales).

### 9. Messagerie interne (`Conversations`, `ConversationParticipants`, `ChatMessages`)

Une conversation est `DM` (exactement 2 participants) ou `GROUP`.
`ConversationParticipants.LastReadAt` sert uniquement à calculer le compteur
de messages non lus côté frontend — pas de table de statut par message.

### 10. MON RCC — réseau social interne (`RccPosts`, `RccPostComments`, `RccPostLikes`, `RccStories`, `RccCommunityFollows`, `RccNotifications`)

Publication réservée à QA/ADMIN (`MonRccService.assertCanPost`), mais visible
et commentable/likable par tout le monde. `RccNotifications.TargetUserId`
vide = notification globale visible par tous ; renseigné = ciblée. Table
générique, pas de lien direct avec le module "Masques de mail" (deux canaux
de communication distincts, volontairement séparés).

## Comment interroger la base en pratique

**Trouver le taux de présence d'un agent sur une période :**
```sql
SELECT COUNT(DISTINCT CAST(OccurredAt AS DATE)) AS jours_connectes
FROM dbo.ShiftEvents
WHERE UserId = @userId AND EventType = 'LOGIN'
  AND OccurredAt BETWEEN @from AND @to;
```

**Trouver le score qualité moyen d'un agent sur un mois :**
```sql
SELECT AVG(CAST(qes.ScoreValue AS FLOAT))
FROM dbo.QualityEvaluationScores qes
JOIN dbo.QualityEvaluations qe ON qe.EvaluationId = qes.EvaluationId
WHERE qe.AgentUserId = @userId
  AND qes.IsNotApplicable = 0 AND qes.ScoreValue IS NOT NULL
  AND qe.EvaluationDate BETWEEN @from AND @to;
```
(Le calcul réel dans l'application applique aussi la pondération par critère
et la logique de critère éliminatoire — voir `QualityScoreCalculator.java` —
cette requête donne une moyenne brute, pas le score officiel.)

**Vérifier qu'un utilisateur est QA (avec la normalisation correcte) :**
```sql
SELECT s.CODE
FROM dbo.USER_SERVICES us
JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID
WHERE us.USER_ID = @userId
  AND REPLACE(LOWER(s.CODE), '_', ' ') = 'quality assurance';
```

## Tables volontairement non couvertes ici

`dbo.KvEntries` est un magasin clé/valeur générique prévu pour absorber des
données pas encore modélisées proprement (`eco_codestudio`,
`eco_site_images`, `eco_cisco*`...). Si un besoin récurrent se dégage
d'utiliser cette table pour un même `Scope`, c'est le signal qu'il mérite sa
propre table dédiée plutôt que de rester dans ce fourre-tout.
