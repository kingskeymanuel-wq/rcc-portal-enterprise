# RCC Portal — Analyse de la dépendance à Internet

**Date :** 02/10/2026 · **Version analysée :** v228 · **Méthode :** inventaire de tous les appels sortants du code
(serveur Java, pages, scripts) et classement de chaque fonction du portail selon son comportement sans Internet.

## 1. Résultat

| Fonctionnement sans Internet | Part des fonctions | Détail |
|---|---|---|
| ✅ Complet | **≈ 85 %** | Aucun appel externe |
| 🟡 Dégradé (repli local automatique) | **≈ 10 %** | Fonctions d'IA, traduction, OCR |
| ❌ Indisponible | **≈ 5 %** | Services cloud sans équivalent local |

Hypothèse : le serveur reste sur le **réseau interne Ecobank** (SQL Server, passerelle d'authentification
SAGED/AD, MFA `10.16.1.16`, SMTP interne). Ces services ne passent pas par Internet.

Aucune ressource d'interface ne vient d'Internet : Bootstrap, icônes, Chart.js, Leaflet, polices, drapeaux et
fonds de carte vectoriels sont servis par le portail (`static/vendor`).

## 2. Fonctions concernées

### ✅ Complet sans Internet
Connexion · tous les portails (Agent, Team Leader, Superviseur, RH, QA, Head QA, Outbound, Télévente, Mail,
Réseaux sociaux, Rafiki, Agence) · shifts, plannings, débordement · reporting, KPI, import Excel · workflow,
procédures, SLA, modèles de mail · base de connaissance, Pas à pas · formation (contenus déposés sur le serveur),
jeux · campagnes et formulaires · RAF (agents locaux) · correcteur (LanguageTool embarqué) · administration,
audit, notifications.

### 🟡 Dégradé — repli local déjà en place
| Fonction | Service externe | Repli actuel sans Internet |
|---|---|---|
| Réécriture de texte | Anthropic | règles locales |
| Génération de campagne par IA | Anthropic | bibliothèque de modèles |
| Analyse de données | Anthropic | synthèse locale |
| Résumé IA des rapports | Azure OpenAI | rapport sans résumé |
| Import KPI par capture | Anthropic (vision) | OCR local PaddleOCR — **si installé** |
| Traducteur | MyMemory, DeepL, Azure | LibreTranslate local — **si installé**, sinon glossaire |

### ❌ Indisponible sans Internet
| Fonction | Service externe |
|---|---|
| Transcription des appels QA | Azure AI Speech |
| Analyse IA des appels QA | Azure OpenAI |
| Analyse de fichier par RAF (IT) | Anthropic |
| Envoi Teams / Outlook | Microsoft Graph |
| Recherche web de RAF, assistant Copilot | DuckDuckGo, Wikipédia, Brave…, Copilot Studio |
| Fond de carte détaillé, recherche d'adresse | OpenStreetMap (tuiles, Nominatim) |
| Vidéos YouTube / Vimeo, liens Google | sites publics |

## 3. Plan pour un fonctionnement 100 % hors ligne

| # | Action | Remplace | Type |
|---|---|---|---|
| 1 | **Mode hors ligne** `RCC_OFFLINE=true` : aucun appel externe, aucune attente, boutons inutiles masqués | tous | code |
| 2 | **IA locale** (Ollama, modèle Qwen 2.5 / Mistral) branchée sur toutes les fonctions d'IA | Anthropic, Azure OpenAI | code + installation |
| 3 | **Transcription locale** (faster-whisper) | Azure AI Speech | code + installation |
| 4 | **OCR local** (PaddleOCR) | Claude vision | installation (déjà prévu) |
| 5 | **Traduction locale** (LibreTranslate) | MyMemory, DeepL, Azure | installation (kit existant) |
| 6 | **Diagnostic hors ligne** dans l'administration : état de chaque service local | — | code |
| 7 | Fond de carte : serveur de tuiles interne (`RCC_MAP_TILE_URL`) ou fond vectoriel local | OpenStreetMap | configuration |
| 8 | Vidéos de formation déposées sur le serveur | YouTube / Vimeo | usage |
| 9 | Messagerie : SMTP interne (Exchange) + notifications du portail | Microsoft Graph | configuration |

**Reste hors ligne sans équivalent :** Teams (service cloud Microsoft) et la recherche web (par nature).

## 4. Matériel recommandé pour l'IA locale

| Usage | Minimum | Recommandé |
|---|---|---|
| IA texte (modèle 7 milliards de paramètres) | 16 Go RAM, 8 cœurs — réponse en 20 à 60 s | carte graphique NVIDIA 8 Go+ — réponse en 2 à 5 s |
| Transcription (Whisper « small ») | 8 Go RAM — ≈ durée de l'appel | GPU — quelques secondes |
| Disque | 15 Go (modèles IA, Whisper, OCR, traduction) | SSD |

La qualité d'un modèle local est inférieure à celle de Claude ou GPT-4o, mais suffisante pour la reformulation,
les synthèses et l'analyse d'écoutes guidée par la grille QA.

## 5. Réalisé (v229)

| Élément | Où |
|---|---|
| Mode hors ligne `RCC_OFFLINE=true` : Anthropic, Azure, Graph, Copilot, recherche web, traducteurs en ligne et tuiles OSM ne sont plus appelés ; liens Google et recherche d'adresse masqués | `OfflineMode`, services concernés |
| IA locale (Ollama, API compatible OpenAI) branchée sur réécriture, campagnes, analyses, rapports, QA, RAF, lecture d'images | `LocalAiClient` |
| Transcription locale des appels QA | `LocalAiClient.transcribe` + `scripts/offline-kit/whisper_server.py` |
| Diagnostic hors ligne (état de chaque service, % de préparation) | Administration → Maintenance |
| Kit d'installation sans Internet : préparation sur PC connecté, installation serveur, vérification | `scripts/offline-kit` |

**Après installation du kit : ≈ 98 % des fonctions marchent sans Internet.** Restent indisponibles : Teams
(cloud Microsoft) et la recherche web de RAF (par nature).

**Testé :** 326 tests automatiques ; IA, transcription et traduction locales testées avec des serveurs simulés
(les modèles réels ne peuvent pas être téléchargés depuis l'environnement de développement). **À faire sur site :**
exécuter le kit (étapes 1 et 2), puis `3-verifier.ps1` et le diagnostic de l'administration.
