<#
.SYNOPSIS
    ETAPE 1 - sur un PC Windows AVEC Internet : rassemble dans .\paquet tout ce qu'il faut pour faire
    fonctionner le RCC Portal sur un serveur SANS Internet (IA locale, transcription, OCR, traduction).

.DESCRIPTION
    Telecharge :
      - Ollama (serveur d'IA locale, version portable) + le modele de texte (et de vision si demande) ;
      - Python 3.11 64 bits (installeur) + les programmes faster-whisper et PaddleOCR (fichiers .whl) ;
      - le modele de transcription Whisper ;
      - les modeles PaddleOCR (premier lancement fait ici, pas sur le serveur) ;
      - le kit LibreTranslate (scripts\libretranslate-offline) avec ses modeles de langue.
    Prerequis sur ce PC : Windows 64 bits, Python 3.11 installe (lanceur "py"), ~20 Go libres.
    Duree : 20 a 60 minutes selon la connexion. Copier ensuite le dossier offline-kit entier sur le serveur.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\1-preparer-sur-pc-connecte.ps1
    .\1-preparer-sur-pc-connecte.ps1 -LlmModel "mistral:7b" -VisionModel "qwen2.5vl:7b" -WhisperModel "medium"
#>
param(
    [string]$LlmModel = "qwen2.5:7b",
    [string]$VisionModel = "",
    [ValidateSet("tiny", "base", "small", "medium", "large-v3")] [string]$WhisperModel = "small",
    [switch]$SkipTranslation
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pkg = Join-Path $here "paquet"
New-Item -ItemType Directory -Force -Path $pkg, "$pkg\installers", "$pkg\wheels", "$pkg\ollama-models", "$pkg\whisper", "$pkg\paddleocr" | Out-Null

function Step($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Download($url, $dest) {
    if (Test-Path $dest) { Write-Host "  deja present : $(Split-Path -Leaf $dest)"; return }
    Write-Host "  telechargement : $url"
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
}

Step "1/6 Python 3.11 (installeur pour le serveur)"
Download "https://www.python.org/ftp/python/3.11.9/python-3.11.9-amd64.exe" "$pkg\installers\python-3.11.9-amd64.exe"
$py = "py"
& $py -3.11 --version
if ($LASTEXITCODE -ne 0) { throw "Python 3.11 introuvable sur ce PC (lanceur 'py -3.11'). Installez-le d'abord." }

Step "2/6 Programmes Python (faster-whisper, PaddleOCR) pour Windows 64 bits / Python 3.11"
& $py -3.11 -m pip download -d "$pkg\wheels" "faster-whisper==1.1.1" "paddlepaddle==2.6.2" "paddleocr==2.8.1" "huggingface_hub"
if ($LASTEXITCODE -ne 0) { throw "Echec du telechargement des programmes Python." }

Step "3/6 Ollama (IA locale) + modele(s)"
Download "https://github.com/ollama/ollama/releases/latest/download/ollama-windows-amd64.zip" "$pkg\installers\ollama-windows-amd64.zip"
$ollamaDir = Join-Path $env:TEMP "rcc-ollama"
if (-not (Test-Path "$ollamaDir\ollama.exe")) { Expand-Archive -Force "$pkg\installers\ollama-windows-amd64.zip" $ollamaDir }
$env:OLLAMA_MODELS = "$pkg\ollama-models"
$env:OLLAMA_HOST = "127.0.0.1:11555"   # port temporaire : n'interfere pas avec un Ollama deja installe
$serve = Start-Process -FilePath "$ollamaDir\ollama.exe" -ArgumentList "serve" -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 5
try {
    foreach ($m in @($LlmModel, $VisionModel) | Where-Object { $_ }) {
        Write-Host "  modele $m (plusieurs Go)..."
        & "$ollamaDir\ollama.exe" pull $m
        if ($LASTEXITCODE -ne 0) { throw "Echec du telechargement du modele $m." }
    }
} finally { Stop-Process -Id $serve.Id -Force -ErrorAction SilentlyContinue }

Step "4/6 Modele de transcription Whisper ($WhisperModel)"
$venv = Join-Path $env:TEMP "rcc-prep-venv"
if (-not (Test-Path "$venv\Scripts\python.exe")) { & $py -3.11 -m venv $venv }
& "$venv\Scripts\python.exe" -m pip install --no-index --find-links "$pkg\wheels" faster-whisper paddlepaddle paddleocr huggingface_hub | Out-Null
& "$venv\Scripts\python.exe" -c "from huggingface_hub import snapshot_download; snapshot_download('Systran/faster-whisper-$WhisperModel', local_dir=r'$pkg\whisper\$WhisperModel')"
if ($LASTEXITCODE -ne 0) { throw "Echec du telechargement du modele Whisper." }

Step "5/6 Modeles PaddleOCR (francais)"
$env:PADDLE_OCR_BASE_DIR = "$pkg\paddleocr"
& "$venv\Scripts\python.exe" -c "from paddleocr import PaddleOCR; PaddleOCR(use_angle_cls=False, lang='fr', show_log=False); print('modeles OCR prets')"
if ($LASTEXITCODE -ne 0) { throw "Echec de la preparation de PaddleOCR." }

Step "6/6 Traduction (kit LibreTranslate)"
$lt = Join-Path (Split-Path -Parent $here) "libretranslate-offline"
if ($SkipTranslation) { Write-Host "  ignore (-SkipTranslation)" }
elseif (Test-Path "$lt\1-telecharger-modeles.ps1") { & powershell -ExecutionPolicy Bypass -File "$lt\1-telecharger-modeles.ps1" }
else { Write-Warning "Kit LibreTranslate introuvable ($lt) : traduction non preparee." }

@{ llmModel = $LlmModel; visionModel = $VisionModel; whisperModel = $WhisperModel; prepared = (Get-Date).ToString("s") } |
    ConvertTo-Json | Set-Content -Encoding UTF8 "$pkg\paquet.json"
$size = "{0:N1}" -f ((Get-ChildItem $pkg -Recurse | Measure-Object Length -Sum).Sum / 1GB)
Write-Host "`nPaquet pret : $pkg ($size Go)." -ForegroundColor Green
Write-Host "Copiez le dossier 'scripts' entier (offline-kit + libretranslate-offline) sur le serveur, puis lancez 2-installer-serveur.ps1 en administrateur."
