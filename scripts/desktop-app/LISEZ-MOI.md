# R360° — le portail RCC comme une application sur le bureau

Ce kit pose une icône **R360° RCC Portal** (logo R360°) sur le Bureau et dans *Démarrer → Ecobank*.
Un double-clic ouvre directement le portail (`http://10.16.1.16:8081/login`) dans sa propre fenêtre,
sans barre d'adresse ni onglets, comme une application.

## Installer sur un poste

1. Copier le dossier `desktop-app` sur le poste (clé USB, partage réseau, e-mail zippé…).
2. Double-cliquer sur **`Installer-R360.bat`**.
3. L'icône R360° apparaît sur le Bureau. Aucun droit administrateur n'est nécessaire.

Le raccourci utilise Microsoft Edge en mode application (présent sur tous les postes Windows 10/11),
Google Chrome à défaut, sinon le navigateur par défaut. Les identifiants enregistrés et la session
du navigateur sont conservés.

## Pour tous les utilisateurs d'un poste / déploiement par l'IT

En administrateur :

```powershell
powershell -ExecutionPolicy Bypass -File Installer-R360.ps1 -AllUsers
```

L'icône est alors posée sur le Bureau public et dans le menu Démarrer de tous les utilisateurs
(utilisable dans un script de démarrage GPO / SCCM / Intune).

## Changer l'adresse du portail

```powershell
powershell -ExecutionPolicy Bypass -File Installer-R360.ps1 -Url http://NOUVELLE-ADRESSE:8081/login
```

Relancer l'installation remplace l'ancien raccourci.

## Désinstaller

Double-cliquer sur **`Desinstaller-R360.bat`** (ou `-Uninstall -AllUsers` pour une installation tous utilisateurs).

## Logo

`r360.ico` (16 à 256 px). Le même logo est servi par le portail : `/images/app/r360.ico`,
`/images/app/r360-512.png`, et `/manifest.webmanifest` (nom « R360° », pour l'installation
d'application depuis le navigateur quand le portail sera servi en HTTPS).
