<#
.SYNOPSIS
    Verifie sur le serveur que les services locaux du RCC Portal repondent (IA, transcription, traduction, OCR).
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\3-verifier.ps1
#>
param([string]$InstallDir = "C:\rcc-offline")
$ok = 0; $total = 0
function Check($name, [scriptblock]$test, $fix) {
    $script:total++
    try { $detail = & $test; Write-Host ("[OK] {0} - {1}" -f $name, $detail) -ForegroundColor Green; $script:ok++ }
    catch { Write-Host ("[KO] {0} - {1}`n     -> {2}" -f $name, $_.Exception.Message, $fix) -ForegroundColor Red }
}
$model = [Environment]::GetEnvironmentVariable("RCC_LOCAL_AI_MODEL", "Machine")
Check "IA locale (Ollama)" {
    $m = (Invoke-RestMethod -TimeoutSec 5 "http://127.0.0.1:11434/v1/models").data.id
    if ($model -and -not ($m -contains $model)) { throw "modele $model absent (installes : $($m -join ', '))" }
    $r = Invoke-RestMethod -TimeoutSec 300 -Method Post -ContentType "application/json" -Uri "http://127.0.0.1:11434/v1/chat/completions" `
        -Body (@{ model = $model; messages = @(@{ role = "user"; content = "Reponds seulement : OK" }); max_tokens = 5 } | ConvertTo-Json -Depth 4)
    "repond (" + $r.choices[0].message.content.Trim() + ")"
} "schtasks /Run /TN RCC-Ollama ; journal : $InstallDir\logs"
Check "Transcription (Whisper)" { (Invoke-RestMethod -TimeoutSec 5 "http://127.0.0.1:8090/health").model } "schtasks /Run /TN RCC-Whisper"
Check "Traduction (LibreTranslate)" { "{0} langues" -f (Invoke-RestMethod -TimeoutSec 5 "http://127.0.0.1:5000/languages").Count } "schtasks /Run /TN RCC-LibreTranslate"
Check "OCR (PaddleOCR)" {
    $py = [Environment]::GetEnvironmentVariable("RCC_OCR_PYTHON_EXECUTABLE", "Machine")
    if (-not $py -or -not (Test-Path $py)) { throw "RCC_OCR_PYTHON_EXECUTABLE non defini ou introuvable" }
    & $py -c "import paddleocr; print('paddleocr ' + paddleocr.__version__)"
    if ($LASTEXITCODE -ne 0) { throw "PaddleOCR non importable" }
} "Relancer 2-installer-serveur.ps1"
Check "Mode hors ligne" { if ([Environment]::GetEnvironmentVariable("RCC_OFFLINE", "Machine") -ne "true") { throw "RCC_OFFLINE n'est pas a true" }; "RCC_OFFLINE=true" } "Relancer 2-installer-serveur.ps1"
$color = if ($ok -eq $total) { "Green" } else { "Yellow" }
Write-Host ("`n{0} / {1} services prets." -f $ok, $total) -ForegroundColor $color
