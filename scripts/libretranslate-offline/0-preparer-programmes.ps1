<#
.SYNOPSIS
    OPTIONNEL - regenere le dossier .\wheels (programmes LibreTranslate) sur un PC Windows AVEC
    Internet. Le paquet fourni contient deja les programmes pour Windows 64 bits + Python 3.11 :
    ce script ne sert que pour une autre version de Python ou une mise a jour de LibreTranslate.
    Le PC doit avoir la MEME version de Python que le serveur.
#>
param([string]$Python = "py -3.11", [string]$Version = "1.9.6")
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$wheels = Join-Path $here "wheels"
New-Item -ItemType Directory -Force -Path $wheels | Out-Null
$parts = @($Python.Split(" ") | Where-Object { $_ })
$exe = $parts[0]
$fixed = @(); if ($parts.Length -gt 1) { $fixed = $parts[1..($parts.Length - 1)] }
& $exe @($fixed + @("-m", "pip", "wheel", "libretranslate==$Version", "pip", "setuptools", "wheel", "-w", $wheels))
if ($LASTEXITCODE -ne 0) { throw "Preparation echouee." }
Write-Host "Programmes prets dans $wheels" -ForegroundColor Green
