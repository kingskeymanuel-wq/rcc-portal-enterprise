<#
.SYNOPSIS
    Build + lance le RCC Portal en local. Voir DEPLOIEMENT_LOCAL.md pour le detail
    de chaque etape (base de donnees, pare-feu) et le depannage.

.DESCRIPTION
    Assume que la base de donnees SQL Server (bd-rcc) existe deja avec son schema
    complet et le login applicatif deja cree (voir DEPLOIEMENT_LOCAL.md section 2).
    Ce script ne cree PAS la base ni son schema.

.EXAMPLE
    .\deploy-local.ps1
    .\deploy-local.ps1 -Port 8090
#>
param(
    [string]$Port = "8080"
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot

Write-Host "=== RCC Portal - deploiement local ===" -ForegroundColor Cyan

# --- JAVA_HOME ---------------------------------------------------------
if (-not $env:JAVA_HOME) {
    $jdkCandidate = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jdkCandidate) { $env:JAVA_HOME = $jdkCandidate.FullName }
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw "JAVA_HOME introuvable ou invalide. Definis-le manuellement : `$env:JAVA_HOME = 'C:\chemin\vers\ton\JDK' avant de relancer ce script."
}
Write-Host "JAVA_HOME : $env:JAVA_HOME"

# --- Secrets requis (repris de l'env courant si deja definis, sinon demandes) ---
if (-not $env:MSSQL_USER) { $env:MSSQL_USER = Read-Host "MSSQL_USER (ex: rcc_app)" }
if (-not $env:MSSQL_PASSWORD) {
    $securePwd = Read-Host "MSSQL_PASSWORD" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePwd)
    $env:MSSQL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
if (-not $env:JWT_SECRET) {
    $env:JWT_SECRET = Read-Host "JWT_SECRET (chaine aleatoire >= 32 caracteres)"
}
if ($env:JWT_SECRET.Length -lt 32) {
    Write-Warning "JWT_SECRET fait moins de 32 caracteres -- le demarrage de l'application echouera probablement."
}
$env:PORT = $Port

# --- Localiser Maven ----------------------------------------------------
$mvnCmd = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvnCmd) {
    $intellijMvn = Get-ChildItem "C:\Program Files\JetBrains" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "plugins\maven\lib\maven3\bin\mvn.cmd" } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
    if ($intellijMvn) { $mvnCmd = $intellijMvn }
}
if (-not $mvnCmd) {
    throw "Maven introuvable (ni dans le PATH, ni fourni par IntelliJ). Installe-le : winget install Apache.Maven"
}
Write-Host "Maven : $mvnCmd"

# --- Liberer le port cible si deja utilise -------------------------------
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($existing) {
    Write-Host "Port $Port deja utilise par le PID $($existing.OwningProcess) -- arret de ce process..." -ForegroundColor Yellow
    Stop-Process -Id $existing.OwningProcess -Force
    Start-Sleep -Milliseconds 500
}

# --- Arreter toute AUTRE instance de ce jar (sur un autre port) qui verrouillerait le fichier ---
# mvn clean echoue sinon avec "Failed to delete ...jar", meme si le port cible, lui, est libre.
$jarLockers = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match [regex]::Escape("rcc-portal") -and $_.CommandLine -match "\.jar" }
foreach ($p in $jarLockers) {
    Write-Host "Instance existante detectee (PID $($p.ProcessId)) -- arret pour liberer le .jar..." -ForegroundColor Yellow
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if ($jarLockers) { Start-Sleep -Milliseconds 500 }

# --- Build ---------------------------------------------------------------
Write-Host "`n=== Build (mvn clean package -DskipTests) ===" -ForegroundColor Cyan
Push-Location $projectRoot
try {
    & $mvnCmd -q -DskipTests clean package
    if ($LASTEXITCODE -ne 0) { throw "Le build a echoue (code $LASTEXITCODE)." }
} finally {
    Pop-Location
}
Write-Host "Build OK." -ForegroundColor Green

# --- Lancement -------------------------------------------------------------
$jar = Get-ChildItem "$projectRoot\target\*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw "Aucun .jar trouve dans target\ apres le build." }

$outLog = Join-Path $projectRoot "app-run.log"
$errLog = Join-Path $projectRoot "app-run-err.log"

Write-Host "`n=== Demarrage sur le port $Port ===" -ForegroundColor Cyan
$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList "-jar", "`"$($jar.FullName)`"" `
    -WorkingDirectory $projectRoot `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
    -PassThru -WindowStyle Hidden
Write-Host "PID : $($proc.Id)"

Write-Host "Attente du demarrage..."
$started = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 1
    $log = Get-Content $outLog -Raw -ErrorAction SilentlyContinue
    if ($log -match "Started RccPortalApplication") { $started = $true; break }
    if ($log -match "APPLICATION FAILED TO START") { break }
}

if ($started) {
    Write-Host "`nDemarrage reussi ! -> http://localhost:$Port`n" -ForegroundColor Green
} else {
    Write-Host "`nLe demarrage a echoue ou pris trop de temps. Dernieres lignes du log :`n" -ForegroundColor Red
    Get-Content $outLog -Tail 40 -ErrorAction SilentlyContinue
    Write-Host "`nVoir aussi $errLog et la section 10 (Depannage) de DEPLOIEMENT_LOCAL.md" -ForegroundColor Yellow
    exit 1
}
