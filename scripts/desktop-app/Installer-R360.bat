@echo off
rem R360 - installe le portail RCC comme une application (icone sur le Bureau).
rem Double-cliquez sur ce fichier. Aucun droit administrateur n'est necessaire.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Installer-R360.ps1" %*
echo.
pause
