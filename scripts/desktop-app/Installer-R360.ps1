<#
  R360° — installe le portail RCC comme une application sur le bureau Windows.

  Crée un raccourci « R360° RCC Portal » (logo R360°) sur le Bureau et dans le menu Démarrer.
  Un double-clic ouvre le portail dans sa propre fenêtre, sans barre d'adresse, comme une application
  (Microsoft Edge en mode application ; Google Chrome à défaut ; sinon le navigateur par défaut).

  Utilisation :
    Double-cliquer sur Installer-R360.bat               (poste courant, utilisateur courant)
    powershell -ExecutionPolicy Bypass -File Installer-R360.ps1 -AllUsers   (tous les utilisateurs, en administrateur)
    powershell -ExecutionPolicy Bypass -File Installer-R360.ps1 -Url http://10.16.1.16:8081/login
    powershell -ExecutionPolicy Bypass -File Installer-R360.ps1 -Uninstall
#>
param(
    [string]$Url = "http://10.16.1.16:8081/login",
    [switch]$AllUsers,
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
$deg = [char]0x00B0
$appName = "R360$deg RCC Portal"

if ($AllUsers) {
    $iconDir   = Join-Path $env:ProgramData "RCC360"
    $desktop   = [Environment]::GetFolderPath("CommonDesktopDirectory")
    $startMenu = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "Ecobank"
} else {
    $iconDir   = Join-Path $env:LOCALAPPDATA "RCC360"
    $desktop   = [Environment]::GetFolderPath("Desktop")      # suit la redirection OneDrive
    $startMenu = Join-Path ([Environment]::GetFolderPath("Programs")) "Ecobank"
}
$targets = @((Join-Path $desktop "$appName.lnk"), (Join-Path $startMenu "$appName.lnk"),
             (Join-Path $desktop "$appName.url"), (Join-Path $startMenu "$appName.url"))

if ($Uninstall) {
    foreach ($t in $targets) { if (Test-Path -LiteralPath $t) { Remove-Item -LiteralPath $t -Force } }
    if (Test-Path $iconDir) { Remove-Item $iconDir -Recurse -Force }
    Write-Host "$appName a ete retire de ce poste." -ForegroundColor Green
    return
}

# 1. Logo R360° : celui du kit, sinon téléchargé depuis le portail.
New-Item -ItemType Directory -Force -Path $iconDir, $startMenu | Out-Null
$icon = Join-Path $iconDir "r360.ico"
$kitIcon = Join-Path $PSScriptRoot "r360.ico"
if (Test-Path $kitIcon) {
    Copy-Item $kitIcon $icon -Force
} else {
    $origin = ([Uri]$Url).GetLeftPart([UriPartial]::Authority)
    Invoke-WebRequest -UseBasicParsing -Uri "$origin/images/app/r360.ico" -OutFile $icon
}

# 2. Navigateur en mode application : Edge (présent sur tous les postes Windows 10/11), sinon Chrome.
$candidates = @(
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "$env:LOCALAPPDATA\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
)
$browser = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

# Anciennes versions du raccourci (autre navigateur, autre adresse) remplacées.
foreach ($t in $targets) { if (Test-Path -LiteralPath $t) { Remove-Item -LiteralPath $t -Force } }

$shell = New-Object -ComObject WScript.Shell
foreach ($dir in @($desktop, $startMenu)) {
    if ($browser) {
        $lnk = $shell.CreateShortcut((Join-Path $dir "$appName.lnk"))
        $lnk.TargetPath = $browser
        $lnk.Arguments = "--app=`"$Url`""
        $lnk.WorkingDirectory = Split-Path $browser
        $lnk.IconLocation = "$icon,0"
        $lnk.Description = "RCC Portal Enterprise | Ecobank"
        $lnk.Save()
    } else {
        # Aucun Edge/Chrome : raccourci Internet classique, ouvert dans le navigateur par défaut.
        $content = "[InternetShortcut]`r`nURL=$Url`r`nIconFile=$icon`r`nIconIndex=0`r`n"
        [IO.File]::WriteAllText((Join-Path $dir "$appName.url"), $content, [Text.Encoding]::ASCII)
    }
}

Write-Host ""
Write-Host "  $appName est installe." -ForegroundColor Green
Write-Host "  Icone sur le Bureau et dans Demarrer > Ecobank. Double-cliquez pour ouvrir le portail."
Write-Host "  Adresse : $Url"
if ($browser) { Write-Host "  Ouvert avec : $browser (mode application)" }
Write-Host ""
