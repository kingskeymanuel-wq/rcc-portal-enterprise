# RAF — assistant du RCC Portal (sans IA externe)

RAF répond **uniquement à partir des données du portail**. Il n'appelle ni Anthropic, ni
OpenAI, ni Copilot, ni le web pour construire ses réponses. Chaque réponse est déterministe,
cite ses sources et explique son raisonnement (« Pourquoi cette réponse ? »). Quand RAF ne
trouve rien de fiable, il le dit, et la question est journalisée pour la QA
(`GET /api/ralph/gaps`).

## Architecture : un orchestrateur et des agents spécialisés

```
/api/ralph/ask ─► RafOrchestrator
   1. assainissement (DataProtectionService), langue de réponse, entités (pays, ville, date, étape, N1/N2, nom/montant/réf.)
   2. relances : bouton du widget, « suivant », « 2 », « détails » → commande ; « et pour le Sénégal ? » → question précédente + nouveau critère
   3. IntentRouter : scoring explicable (mots-clés fr/en/pt/es + entités + correspondances réelles dans les données + contexte)
   4. consultation des agents pertinents (3 max + documentation en filet de sécurité)
   5. fusion : meilleure réponse, « à voir aussi », clarification si deux pistes sont aussi probables, confiance
```

| Agent | Source | Ce qu'il fait |
|---|---|---|
| Fiche appel | Procédures + SLA + modèles + glossaire | Ce que je fais / ce que je dis au client (délai officiel + date d'échéance) / où transmettre / modèle à envoyer / termes utiles |
| Procédures | `Procedures`, `ProcedureSteps` | Fiche la plus pertinente (pays pris en compte), **mode guidé** étape par étape (« suivant », « précédent », « étape 4 », voix ou boutons) |
| Référentiel SLA | `SlaRules` | Libellé officiel **uniquement**, date d'échéance calculée (jours ouvrés hors week-end) ; motif absent → le dit, n'estime jamais |
| Glossaire | `WordTerms` | Définition des sigles et termes bancaires |
| Agences | `BankBranches`, `KnowledgeCountries` | Agences par pays, ville ou nom, avec adresse, horaires et lien carte (jamais le nom du responsable) |
| Mon planning & shift | `AgentSchedules`, `ShiftEvents` | Planning, retard et statut **de l'agent connecté uniquement** |
| Modèles de mail | `MailTemplates` | Brouillon officiel pré-rempli depuis la phrase (« pour Mme Koné, montant 50 000 FCFA, réf TRX123 »), champs manquants surlignés |
| Q/R vérifiées | Banque d'évaluation (`QuizQuestions`) | Réponses validées par la QA |
| Documentation | Base de connaissances, formations | Filet de sécurité, toujours consulté |

Le code se trouve dans `src/main/java/com/ecobank/rccportal/raf/`. Les données sont lues via
`RafCatalog`, un instantané rafraîchi toutes les 5 minutes : une modification faite dans
l'Administration est prise en compte sans redémarrage.

## Ajouter un agent

1. Créer une classe `@Component` qui implémente `raf.agent.RafAgent`, avec son intention
   (`RafModels.RafIntent`) et `answer()` qui renvoie `AgentAnswer.notFound(...)` quand il n'y a
   rien de fiable.
2. Ajouter les mots-clés de cette intention dans `IntentRouter.LEXICON`.
3. Ajouter un cas dans `RafOrchestratorTest`.

## API

| Méthode | Rôle |
|---|---|
| `GET /api/ralph/ask?keyword=…&lang=fr\|en\|pt\|es` | Question |
| `GET /api/ralph/ask?cmd=raf:proc:12:step:2` | Commande d'un bouton (mode guidé, choix, modèle…) |
| `POST /api/ralph/ask` `{keyword, lang, cmd}` | Idem, pour les longues saisies |
| `GET /api/ralph/welcome` | Accueil et exemples cliquables |
| `GET /api/ralph/capabilities` | Liste des agents |
| `GET /api/ralph/gaps` | Questions restées sans réponse (QA / IT) |

La réponse garde les champs historiques (`explanation`, `results`, `source`, `webResults`,
`confidencePercent`, `sourcesConsulted`) et ajoute `intent`, `agentsConsulted`, `suggestions`,
`action` (`GUIDED_STEP`, `SLA_DUE`, `DRAFT`, `CALL_CARD`, `CLARIFY`), `clarification` et
`reasoning`.

Seule l'analyse de fichier (`/api/ralph/analyze-file`, réservée à l'IT) utilise encore
Anthropic. Elle est hors du dialogue RAF.
