<#
.SYNOPSIS
    ETAPE 2 - sur le SERVEUR sans Internet, PowerShell EN ADMINISTRATEUR : installe l'IA locale (Ollama),
    la transcription (Whisper), l'OCR (PaddleOCR), la traduction (LibreTranslate) depuis .\paquet, cree
    les taches de demarrage automatique et configure le RCC Portal en mode hors ligne.

.DESCRIPTION
    Tout s'installe dans C:\rcc-offline (modifiable) et n'ecoute que sur 127.0.0.1 :
        Ollama      127.0.0.1:11434   tache RCC-Ollama
        Whisper     127.0.0.1:8090    tache RCC-Whisper
        LibreTranslate 127.0.0.1:5000 tache RCC-LibreTranslate (kit libretranslate-offline)
    Variables d'environnement (machine) posees pour le portail : RCC_OFFLINE=true, RCC_LOCAL_AI_URL,
    RCC_LOCAL_AI_MODEL, RCC_LOCAL_WHISPER_URL, RCC_OCR_*, RCC_TRANSLATION_*, WEBSEARCH_ENABLED=false.
    Redemarrer ensuite le RCC Portal pour qu'il les prenne en compte.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\2-installer-serveur.ps1
    .\2-installer-serveur.ps1 -InstallDir "D:\rcc-offline" -Gpu
#>
param(
    [string]$InstallDir = "C:\rcc-offline",
    [switch]$Gpu,
    [switch]$SkipTranslation
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pkg = Join-Path $here "paquet"
if (-not (Test-Path "$pkg\paquet.json")) { throw "Paquet introuvable ($pkg). Lancez d'abord 1-preparer-sur-pc-connecte.ps1 sur un PC avec Internet." }
$cfg = Get-Content "$pkg\paquet.json" -Raw | ConvertFrom-Json
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Lancez ce script dans PowerShell EN ADMINISTRATEUR."
}
function Step($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Task($name, $exe, $arguments, $workDir) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    $action = New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory $workDir
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit 0 -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries
    Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -User "SYSTEM" -RunLevel Highest -Force | Out-Null
    Start-ScheduledTask -TaskName $name
    Write-Host "  tache $name creee et demarree"
}
function MachineEnv($name, $value) { [Environment]::SetEnvironmentVariable($name, $value, "Machine"); Set-Item -Path "Env:$name" -Value $value }
New-Item -ItemType Directory -Force -Path $InstallDir, "$InstallDir\logs" | Out-Null

Step "1/6 Python 3.11"
$python = (Get-Command py -ErrorAction SilentlyContinue) | ForEach-Object { & py -3.11 -c "import sys; print(sys.executable)" 2>$null }
if (-not $python) {
    Write-Host "  installation de Python 3.11 (silencieuse)..."
    Start-Process -Wait -FilePath "$pkg\installers\python-3.11.9-amd64.exe" -ArgumentList "/quiet InstallAllUsers=1 PrependPath=1 Include_launcher=1 Include_test=0"
    $python = "C:\Program Files\Python311\python.exe"
}
Write-Host "  Python : $python"
$venv = "$InstallDir\venv"
if (-not (Test-Path "$venv\Scripts\python.exe")) { & $python -m venv $venv }
& "$venv\Scripts\python.exe" -m pip install --no-index --find-links "$pkg\wheels" faster-whisper paddlepaddle paddleocr
if ($LASTEXITCODE -ne 0) { throw "Echec de l'installation des programmes Python (dossier wheels)." }

Step "2/6 IA locale (Ollama, modele $($cfg.llmModel))"
Expand-Archive -Force "$pkg\installers\ollama-windows-amd64.zip" "$InstallDir\ollama"
Write-Host "  copie des modeles (plusieurs Go)..."
robocopy "$pkg\ollama-models" "$InstallDir\ollama-models" /E /NFL /NDL /NJH /NJS | Out-Null
MachineEnv "OLLAMA_MODELS" "$InstallDir\ollama-models"
MachineEnv "OLLAMA_HOST" "127.0.0.1:11434"
Task "RCC-Ollama" "$InstallDir\ollama\ollama.exe" "serve" "$InstallDir\ollama"

Step "3/6 Transcription (Whisper $($cfg.whisperModel))"
robocopy "$pkg\whisper" "$InstallDir\whisper" /E /NFL /NDL /NJH /NJS | Out-Null
Copy-Item -Force "$here\whisper_server.py" "$InstallDir\whisper_server.py"
$device = if ($Gpu) { "--device cuda --compute-type float16" } else { "--device cpu --compute-type int8" }
Task "RCC-Whisper" "$venv\Scripts\python.exe" "`"$InstallDir\whisper_server.py`" --model `"$InstallDir\whisper\$($cfg.whisperModel)`" --port 8090 $device" $InstallDir

Step "4/6 OCR (PaddleOCR)"
robocopy "$pkg\paddleocr" "$InstallDir\paddleocr" /E /NFL /NDL /NJH /NJS | Out-Null
Copy-Item -Force (Join-Path (Split-Path -Parent $here) "ocr.py") "$InstallDir\ocr.py"
MachineEnv "PADDLE_OCR_BASE_DIR" "$InstallDir\paddleocr"

Step "5/6 Traduction (LibreTranslate)"
$lt = Join-Path (Split-Path -Parent $here) "libretranslate-offline"
if ($SkipTranslation) { Write-Host "  ignore (-SkipTranslation)" }
elseif (Test-Path "$lt\2-installer-serveur.ps1") { & powershell -ExecutionPolicy Bypass -File "$lt\2-installer-serveur.ps1" -Python $python }
else { Write-Warning "Kit LibreTranslate introuvable : traduction non installee." }

Step "6/6 Configuration du RCC Portal (mode hors ligne)"
MachineEnv "RCC_OFFLINE" "true"
MachineEnv "RCC_LOCAL_AI_URL" "http://127.0.0.1:11434"
MachineEnv "RCC_LOCAL_AI_MODEL" $cfg.llmModel
if ($cfg.visionModel) { MachineEnv "RCC_LOCAL_AI_VISION_MODEL" $cfg.visionModel }
MachineEnv "RCC_LOCAL_WHISPER_URL" "http://127.0.0.1:8090"
MachineEnv "RCC_LOCAL_WHISPER_MODEL" $cfg.whisperModel
MachineEnv "RCC_OCR_PYTHON_EXECUTABLE" "$venv\Scripts\python.exe"
MachineEnv "RCC_OCR_SCRIPT_PATH" "$InstallDir\ocr.py"
MachineEnv "RCC_TRANSLATION_LIBRETRANSLATE_URL" "http://127.0.0.1:5000"
MachineEnv "RCC_TRANSLATION_PROVIDER_ORDER" "libretranslate,local,custom"
MachineEnv "RCC_TRANSLATION_MYMEMORY_ENABLED" "false"
MachineEnv "WEBSEARCH_ENABLED" "false"
MachineEnv "COPILOT_ENABLED" "false"

Write-Host "`nInstallation terminee. Verification dans 20 secondes..." -ForegroundColor Green
Start-Sleep -Seconds 20
& powershell -ExecutionPolicy Bypass -File "$here\3-verifier.ps1" -InstallDir $InstallDir
Write-Host "`nRedemarrez le RCC Portal, puis : Administration > Maintenance > Fonctionnement sans Internet > Lancer le diagnostic." -ForegroundColor Yellow
