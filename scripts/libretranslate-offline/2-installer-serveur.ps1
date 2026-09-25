<#
.SYNOPSIS
    ETAPE 2 - sur le SERVEUR (sans Internet) : installe LibreTranslate + les modeles depuis ce
    paquet, puis le lance automatiquement au demarrage de Windows (tache planifiee).

.DESCRIPTION
    Chaine de traduction du portail :
        RCC Portal -> TranslationService -> LibreTranslate (127.0.0.1:5000) -> Argos Translate
    - Aucune connexion Internet : programmes (dossier wheels) et modeles (dossier models) sont
      installes depuis ce paquet.
    - Ecoute sur 127.0.0.1 uniquement : seul le portail (meme serveur) peut l'appeler.
    - Prerequis : Python 3.11 64 bits installe (python.org, "Add python.exe to PATH" ou lanceur py).
    - A lancer dans PowerShell EN ADMINISTRATEUR (creation de la tache planifiee).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\2-installer-serveur.ps1
    .\2-installer-serveur.ps1 -InstallDir "D:\rcc-libretranslate" -Port 5000
    .\2-installer-serveur.ps1 -NoService      # installe sans tache planifiee (lancement manuel)
#>
param(
    [string]$InstallDir = "C:\rcc-libretranslate",
    [int]$Port = 5000,
    [string]$Python = "",
    [switch]$NoService
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$wheels = Join-Path $here "wheels"
$models = Join-Path $here "models"
$venv = Join-Path $InstallDir "venv"
$venvPython = Join-Path $venv "Scripts\python.exe"
$ltExe = Join-Path $venv "Scripts\libretranslate.exe"
$pkgDir = Join-Path $InstallDir "argos-packages"
$taskName = "RCC-LibreTranslate"

Write-Host "=== Installation hors ligne de LibreTranslate pour le RCC Portal ===" -ForegroundColor Cyan
if (-not (Test-Path $wheels)) { throw "Dossier 'wheels' introuvable a cote du script ($wheels)." }
$modelFiles = @(Get-ChildItem -Path $models -Filter *.argosmodel -ErrorAction SilentlyContinue)
if ($modelFiles.Count -lt 2) { throw "Moins de 2 modeles dans '$models'. Lancez d'abord 1-telecharger-modeles.ps1 sur un PC avec Internet." }

# --- 1. Python 3.11 -------------------------------------------------------------------
# Chaque candidat = executable + arguments fixes (ex. lanceur "py -3.11").
function Invoke-Candidate([string]$exe, [string[]]$fixed, [string[]]$more) {
    $all = @()
    if ($fixed) { $all += $fixed }
    if ($more) { $all += $more }
    & $exe @all
}
function Find-Python {
    $candidates = @()
    if ($Python) { $candidates += ,@($Python) }
    $candidates += ,@("py", "-3.11")
    $candidates += ,@("python")
    foreach ($c in $candidates) {
        $exe = $c[0]
        $fixed = @(); if ($c.Length -gt 1) { $fixed = $c[1..($c.Length - 1)] }
        try {
            $v = Invoke-Candidate $exe $fixed @("-c", "import sys;print('%d.%d' % sys.version_info[:2])") 2>$null
            if ("$v".Trim() -eq "3.11") { return ,@($exe, $fixed) }
        } catch { }
    }
    throw "Python 3.11 introuvable. Installez python-3.11.x-amd64.exe (python.org) ou passez -Python 'C:\chemin\python.exe'."
}
$found = Find-Python
$pyExe = $found[0]; $pyFixed = $found[1]
Write-Host "Python : $pyExe $($pyFixed -join ' ')"

# --- 2. Environnement + programmes (aucun acces reseau : --no-index) -----------------
New-Item -ItemType Directory -Force -Path $InstallDir, $pkgDir | Out-Null
if (-not (Test-Path $venvPython)) {
    Invoke-Candidate $pyExe $pyFixed @("-m", "venv", $venv)
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $venvPython)) { throw "Creation de l'environnement Python impossible." }
}
& $venvPython -m pip install --no-index --find-links $wheels --upgrade pip
& $venvPython -m pip install --no-index --find-links $wheels libretranslate
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $ltExe)) { throw "Installation de LibreTranslate echouee (voir messages ci-dessus)." }
Write-Host "LibreTranslate installe." -ForegroundColor Green

# --- 3. Modeles de langue (dossier fixe, lisible par le compte SYSTEM de la tache) ----
$env:ARGOS_PACKAGES_DIR = $pkgDir
$installPy = Join-Path $InstallDir "install_models.py"
@'
import sys
import argostranslate.package as p
for f in sys.argv[1:]:
    p.install_from_path(f)
    print("  modele installe :", f)
'@ | Set-Content -Path $installPy -Encoding ASCII
$langsPy = Join-Path $InstallDir "installed_langs.py"
@'
import argostranslate.package as p
codes = set()
for x in p.get_installed_packages():
    codes.update([x.from_code, x.to_code])
print(",".join(sorted(codes)))
'@ | Set-Content -Path $langsPy -Encoding ASCII
& $venvPython $installPy @($modelFiles | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "Installation des modeles echouee." }
$langs = ("" + (& $venvPython $langsPy)).Trim()
if (-not $langs) { throw "Aucune langue installee." }
Write-Host "Langues installees : $langs" -ForegroundColor Green

# --- 4. Script de lancement --------------------------------------------------------------
$runCmd = Join-Path $InstallDir "run-libretranslate.cmd"
@"
@echo off
rem Lance LibreTranslate pour le RCC Portal (hors ligne, 127.0.0.1:$Port).
set ARGOS_PACKAGES_DIR=$pkgDir
set PYTHONIOENCODING=utf-8
cd /d "$InstallDir"
"$ltExe" --host 127.0.0.1 --port $Port --load-only $langs --disable-web-ui >> "$InstallDir\libretranslate.log" 2>&1
"@ | Set-Content -Path $runCmd -Encoding ASCII
Write-Host "Script de lancement : $runCmd"

# --- 5. Demarrage automatique (tache planifiee au demarrage, compte SYSTEM) ------------
if (-not $NoService) {
    schtasks /Query /TN $taskName 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { schtasks /End /TN $taskName 2>$null | Out-Null; schtasks /Delete /TN $taskName /F | Out-Null }
    schtasks /Create /TN $taskName /TR "`"$runCmd`"" /SC ONSTART /RU SYSTEM /RL HIGHEST /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Creation de la tache planifiee impossible : lancez PowerShell en administrateur (ou utilisez -NoService)." }
    schtasks /Run /TN $taskName | Out-Null
    Write-Host "Tache planifiee '$taskName' creee et demarree (relance automatique a chaque demarrage du serveur)." -ForegroundColor Green
} else {
    Start-Process -FilePath $runCmd -WindowStyle Minimized
    Write-Host "Lance manuellement (sans tache planifiee)."
}

# --- 6. Verification ---------------------------------------------------------------------
Write-Host "Verification (le premier chargement des modeles peut prendre 1 a 2 minutes)..."
$ok = $false
for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
    Start-Sleep -Seconds 5
    try {
        $r = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/translate" -ContentType "application/json" `
            -Body '{"q":"Bonjour, votre carte est prete.","source":"fr","target":"en","format":"text"}'
        Write-Host "Test fr -> en : $($r.translatedText)" -ForegroundColor Green
        $ok = $true
    } catch { Write-Host "  ... en attente ($((($i + 1) * 5)) s)" }
}
if (-not $ok) {
    Write-Warning "LibreTranslate ne repond pas encore. Consultez $InstallDir\libretranslate.log"
} else {
    Write-Host ""
    Write-Host "Termine. Dans le portail : Traducteur -> 'Diagnostiquer la connexion' doit afficher LibreTranslate : OK." -ForegroundColor Cyan
    Write-Host "(Aucun reglage cote portail : rcc.translation.libretranslate.url = http://localhost:$Port par defaut.)"
}
