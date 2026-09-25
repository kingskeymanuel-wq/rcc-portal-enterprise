<#
.SYNOPSIS
    Installe (au premier lancement) puis demarre LibreTranslate en local pour le Traducteur
    du RCC Portal.

.DESCRIPTION
    Chaine de traduction du portail :
        Application -> TranslationService.java -> LibreTranslate local -> Argos Translate -> Traduction

    LibreTranslate est un serveur HTTP open source qui utilise le moteur Argos Translate. Les
    modeles de langue sont charges une seule fois en memoire (contrairement a
    scripts/translate.py, qui recharge tout a chaque traduction) : traduction rapide, 100 %
    hors-ligne une fois les modeles telecharges, aucune donnee ne sort du serveur.

    SERVEUR SANS INTERNET : utilisez plutot le kit scripts\libretranslate-offline
    (1-telecharger-modeles.ps1 sur un PC connecte, puis 2-installer-serveur.ps1 sur le serveur).

    - Premier lancement : cree un environnement Python dedie, installe LibreTranslate et
      telecharge les modeles des langues de LT_LOAD_ONLY (acces internet necessaire CE jour-la,
      vers pypi.org et github.com / argos-net).
    - Lancements suivants : aucun acces internet necessaire.
    - Ecoute sur 127.0.0.1 uniquement : seul le portail (meme machine) peut l'appeler.

    Cote portail, rien a configurer : rcc.translation.libretranslate.url vaut
    http://localhost:5000 par defaut. Verifier avec le bouton "Diagnostiquer la connexion"
    du Traducteur.

.PARAMETER Languages
    Langues a charger (codes ISO separes par des virgules). Chaque langue ajoutee augmente la
    memoire utilisee (~150-300 Mo par paire avec l'anglais).

.EXAMPLE
    .\scripts\start-libretranslate.ps1
    .\scripts\start-libretranslate.ps1 -Languages "fr,en,es,pt,ar,sw,ha"
    .\scripts\start-libretranslate.ps1 -Port 5001 -InstallDir "D:\libretranslate"
#>
param(
    [string]$Languages = $(if ($env:LT_LOAD_ONLY) { $env:LT_LOAD_ONLY } else { "fr,en,es,pt,ar" }),
    [int]$Port = 5000,
    [string]$InstallDir = "C:\rcc-libretranslate",
    [string]$Python = "py -3.11"
)

$ErrorActionPreference = "Stop"
Write-Host "=== LibreTranslate local pour le RCC Portal ===" -ForegroundColor Cyan

$venv = Join-Path $InstallDir "venv"
$venvPython = Join-Path $venv "Scripts\python.exe"
$ltExe = Join-Path $venv "Scripts\libretranslate.exe"

# --- Installation (une seule fois) -----------------------------------------------
if (-not (Test-Path $ltExe)) {
    Write-Host "Premiere installation dans $InstallDir (acces internet requis cette fois-ci)..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

    # LibreTranslate depend de PyTorch : Python 3.10 ou 3.11 recommande (3.12+ peut echouer).
    $pyParts = @($Python.Split(" ") | Where-Object { $_ })
    $pyFixed = @(); if ($pyParts.Length -gt 1) { $pyFixed = $pyParts[1..($pyParts.Length - 1)] }
    & $pyParts[0] @($pyFixed + @("-m", "venv", $venv))
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $venvPython)) {
        throw "Creation de l'environnement Python impossible. Installez Python 3.11 (python.org) ou passez -Python 'C:\chemin\python.exe'."
    }
    & $venvPython -m pip install --upgrade pip
    & $venvPython -m pip install libretranslate
    if ($LASTEXITCODE -ne 0) { throw "Installation de LibreTranslate echouee (voir messages ci-dessus)." }
    Write-Host "LibreTranslate installe." -ForegroundColor Green
}

# --- Demarrage -------------------------------------------------------------------
# --load-only : ne charge que les langues utiles (memoire, temps de demarrage). Au premier
# demarrage, les modeles Argos manquants sont telecharges automatiquement.
Write-Host "Langues chargees : $Languages"
Write-Host "Demarrage sur http://127.0.0.1:$Port (Ctrl+C pour arreter)..."
Write-Host "Le premier demarrage peut prendre plusieurs minutes (telechargement des modeles)."

& $ltExe --host 127.0.0.1 --port $Port --load-only $Languages --disable-web-ui
