# Onglet Formation — modèle EduFun, contrôlé par la QA

L'onglet Formation reprend la structure du projet EduFun (V9) : tableau de bord personnel,
programme par thématique, lecteur de leçon, quiz & défis, certificats vérifiables et un
espace d'administration — ici l'**Espace QA**.

## Espaces

| Espace | Agent | QA / Admin / Formateur |
|---|---|---|
| Tableau de bord | XP, niveau, série de jours, badges, classement de l'équipe, « À découvrir maintenant » | idem (la QA est aussi apprenante) |
| Mon parcours | cours **publiés** de son équipe, par thématique | tous les cours, y compris brouillons |
| Quiz & défis | évaluations notées : à faire / réussies / à repasser | idem |
| Formations programmées | agenda jour/semaine/mois, lecteur de leçon suivi (scroll + vidéo) | idem |
| Certificats | demander un certificat, voir/imprimer, vérifier un numéro | idem |
| Espace QA | — | publication, validation des certificats, activité en direct, classement, création de cours, programmation, suivi A→Z |

## Contrôle QA

- **Publication** : tout nouveau cours est créé en **brouillon** (invisible des agents).
  La QA le publie depuis « Publication des cours » ; une évaluation notée sans question ne
  peut pas être publiée. Les cours existants avant cette version restent publiés.
- **Certificats** : l'agent demande un certificat quand un parcours programmé est terminé à
  100 % ou qu'une évaluation est réussie (≥ 70 %). La QA **valide** (numéro `RCC-AAAA-XXXXXX`
  attribué), **refuse** (motif obligatoire, visible par l'agent) ou **révoque**.
- **Synchronisation** : XP, badges, classements et activité sont recalculés à partir des
  données réelles (leçons terminées, tentatives, certificats) à chaque appel ; l'Espace QA se
  rafraîchit toutes les 30 s tant qu'il est ouvert.

## Barème XP

Leçon terminée +20 · auto-diagnostic +30 · évaluation réussie +50 (+20 si 100 %) ·
évaluation tentée +10 · certificat validé +100.
Niveaux : Découverte (0), Apprenti (100), Confirmé (300), Expert (600), Maître (1000), Légende (1600).

## Technique

- API : `/api/training/journey` (`TrainingJourneyController`), `PATCH /api/courses/{id}/publication`.
- Tables : `TrainingCertificates` + colonnes `Courses.PublicationStatus/PublishedAt/PublishedBy`,
  créées automatiquement au démarrage par `WorkflowSchemaBootstrap`.
- Front : `templates/training.html`, `css/formation.css`, `js/formation.js` (+ `training.js`,
  `training-schedule.js`, `training-lesson.js` existants). Images : `static/images/formation/`.
