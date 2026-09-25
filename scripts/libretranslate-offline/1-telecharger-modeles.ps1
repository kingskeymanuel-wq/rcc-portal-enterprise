<#
.SYNOPSIS
    ETAPE 1 - sur un PC AVEC Internet : telecharge les modeles de langue Argos (.argosmodel)
    necessaires a LibreTranslate, dans le dossier .\models de ce paquet.

.DESCRIPTION
    Les modeles passent par l'anglais (fr->en->pt...). Pour chaque langue X demandee, on
    telecharge en->X et X->en. Compter ~100 Mo par modele (6 langues = 10 modeles ~ 1 Go).
    Copiez ensuite TOUT le dossier (wheels + models + scripts) sur le serveur, puis lancez
    2-installer-serveur.ps1.

.EXAMPLE
    .\1-telecharger-modeles.ps1
    .\1-telecharger-modeles.ps1 -Languages "fr,en,pt,es,ar,sw,zh"
#>
param(
    [string]$Languages = "fr,en,pt,es,ar,sw",
    [string]$IndexUrl = "https://raw.githubusercontent.com/argosopentech/argospm-index/main/index.json"
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest beaucoup plus rapide sans barre
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$modelsDir = Join-Path $here "models"
New-Item -ItemType Directory -Force -Path $modelsDir | Out-Null

$codes = $Languages.Split(",") | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ -and $_ -ne "en" }
Write-Host "Index des modeles : $IndexUrl" -ForegroundColor Cyan
$index = Invoke-RestMethod -Uri $IndexUrl

$wanted = @()
foreach ($c in $codes) {
    foreach ($pair in @(@("en", $c), @($c, "en"))) {
        $pkg = $index | Where-Object { $_.from_code -eq $pair[0] -and $_.to_code -eq $pair[1] } | Select-Object -First 1
        if ($null -eq $pkg) { Write-Warning "Aucun modele $($pair[0]) -> $($pair[1]) dans l'index (langue non prise en charge par Argos)."; continue }
        $wanted += $pkg
    }
}

foreach ($pkg in $wanted) {
    $url = $pkg.links[0]
    $file = Join-Path $modelsDir ([System.IO.Path]::GetFileName($url))
    if (Test-Path $file) { Write-Host "Deja present : $(Split-Path -Leaf $file)"; continue }
    Write-Host "Telechargement $($pkg.from_code) -> $($pkg.to_code) : $url"
    Invoke-WebRequest -Uri $url -OutFile "$file.part"
    Move-Item -Force "$file.part" $file
}
Write-Host ""
Write-Host "Termine : $((Get-ChildItem $modelsDir -Filter *.argosmodel).Count) modele(s) dans $modelsDir" -ForegroundColor Green
Write-Host "Copiez maintenant ce dossier complet sur le serveur et lancez 2-installer-serveur.ps1 (en administrateur)."
