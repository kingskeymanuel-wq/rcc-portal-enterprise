@echo off
rem R360 - retire l'application R360 du Bureau et du menu Demarrer.
rem Double-cliquez sur ce fichier. Aucun droit administrateur n'est necessaire.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Installer-R360.ps1" -Uninstall %*
echo.
pause
