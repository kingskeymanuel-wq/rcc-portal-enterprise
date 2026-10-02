# Kit « serveur sans Internet » du RCC Portal (Windows)

Installe sur le serveur, **sans Internet**, les services qui remplacent le cloud.
Tout écoute uniquement sur `127.0.0.1` : rien ne sort du serveur.

| Service | Remplace | Port | Tâche Windows |
|---|---|---|---|
| Ollama + modèle `qwen2.5:7b` (IA locale) | Anthropic, Azure OpenAI | 11434 | RCC-Ollama |
| Whisper `small` (transcription QA) | Azure AI Speech | 8090 | RCC-Whisper |
| PaddleOCR (import KPI par capture) | Claude vision | — | lancé par le portail |
| LibreTranslate (traduction) | MyMemory, DeepL, Azure | 5000 | RCC-LibreTranslate |

## Installation (3 étapes)
1. **PC avec Internet** (Windows 64 bits + Python 3.11) — PowerShell dans ce dossier :
   `powershell -ExecutionPolicy Bypass -File .\1-preparer-sur-pc-connecte.ps1`
   → crée `paquet\` (≈ 8 à 12 Go). Options : `-LlmModel "mistral:7b"`, `-VisionModel "qwen2.5vl:7b"`, `-WhisperModel medium`.
2. **Copier le dossier `scripts` entier** (`offline-kit` + `libretranslate-offline` + `ocr.py`) sur le serveur.
3. **Serveur**, PowerShell **en administrateur** :
   `powershell -ExecutionPolicy Bypass -File .\2-installer-serveur.ps1` (ajouter `-Gpu` avec une carte NVIDIA)
   → installe dans `C:\rcc-offline`, crée les tâches de démarrage, active `RCC_OFFLINE=true`, puis vérifie.

Redémarrer le RCC Portal, puis **Administration → Maintenance → Fonctionnement sans Internet → Lancer le diagnostic**.

## Vérifier / dépanner
- `.\3-verifier.ps1` : teste IA, transcription, traduction, OCR et le mode hors ligne.
- Relancer un service : `schtasks /Run /TN RCC-Ollama` (ou RCC-Whisper, RCC-LibreTranslate).
- Changer de modèle IA : relancer l'étape 1 avec `-LlmModel`, recopier, relancer l'étape 2.
- Revenir au mode connecté : `setx /M RCC_OFFLINE false`, puis redémarrer le portail.

## Matériel
IA texte : 16 Go de RAM minimum (réponse 20 à 60 s), carte NVIDIA 8 Go+ recommandée (2 à 5 s).
Disque : ≈ 15 Go dans `C:\rcc-offline`.
