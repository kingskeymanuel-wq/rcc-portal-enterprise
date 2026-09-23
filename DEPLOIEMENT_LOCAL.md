# Déploiement local — RCC Portal (Ecobank)

Guide complet + toutes les commandes pour builder et lancer le portail sur une machine Windows, à partir de zéro ou en reprenant une base existante.

Un script d'automatisation (`deploy-local.ps1`) couvre les étapes 4 et 5 ci-dessous — voir la fin de ce document.

---

## 0. ⚠️ Limite connue — schéma de base de données

Ce dépôt contient uniquement trois **migrations incrémentales** (`db/migrations/001_add_user_status.sql`, `002_add_user_totp.sql`, `003_add_chat.sql`) qui ajoutent des colonnes/tables à un schéma déjà existant (`Users.Status`, `Users.TotpSecret`/`TotpConfirmedAt`, tables de messagerie `Conversations`/`ConversationParticipants`/`ChatMessages`).

**Il n'y a PAS de script de création du schéma complet** (tables `Users`, `Roles`, `Teams`, et toutes les autres tables métier) dans ce dépôt — seulement les migrations incrémentales listées ci-dessus. Le schéma complet vient d'un dossier `db/rcc-full-schema/` (un projet Node séparé) qui n'est pas présent ici.

Concrètement :
- Si tu redéploies sur la **même base SQL Server** qui existe déjà (ton instance locale actuelle), tout fonctionne directement — passe à l'étape 1.
- Si tu déploies sur une **base neuve/vide**, il faudra d'abord recréer le schéma complet (récupérer le projet `rcc-full-schema`, ou exporter le schéma de la base actuelle via SQL Server Management Studio : clic droit sur la base → Tasks → Generate Scripts).

---

## 1. Prérequis

| Outil | Version | Notes |
|---|---|---|
| JDK | 21+ | Testé avec OpenJDK 26. `java -version` pour vérifier. |
| Maven | 3.9+ | Ou utiliser celui fourni par IntelliJ (voir §4). |
| SQL Server | toute édition | Local ou accessible sur le réseau. Base `bd-rcc` avec son schéma déjà en place (voir §0). |

---

## 2. Base de données — login applicatif

Si le login `rcc_app` n'existe pas encore sur ton instance SQL Server (via `sqlcmd`, SSMS, ou Azure Data Studio en admin) :

```sql
CREATE LOGIN rcc_app WITH PASSWORD = 'VotreMotDePasseSecurise!', CHECK_POLICY = OFF;
USE [bd-rcc];
CREATE USER rcc_app FOR LOGIN rcc_app;
ALTER ROLE db_owner ADD MEMBER rcc_app;
```

`CHECK_POLICY = OFF` évite que le mot de passe expire silencieusement (vécu en pratique — un mot de passe expiré casse le login applicatif sans message d'erreur clair côté Spring Boot).

### Appliquer les migrations incrémentales

```powershell
sqlcmd -S localhost -U rcc_app -P "VotreMotDePasse" -d bd-rcc -i db\migrations\001_add_user_status.sql
sqlcmd -S localhost -U rcc_app -P "VotreMotDePasse" -d bd-rcc -i db\migrations\002_add_user_totp.sql
sqlcmd -S localhost -U rcc_app -P "VotreMotDePasse" -d bd-rcc -i db\migrations\003_add_chat.sql
```

(Si elles ont déjà été appliquées, `sqlcmd` renverra une erreur de type "colonne déjà existante" — sans conséquence, tu peux l'ignorer.)

---

## 3. Variables d'environnement

| Variable | Requis | Défaut | Rôle |
|---|:---:|---|---|
| `MSSQL_USER` | **Oui** | — | Login SQL Server |
| `MSSQL_PASSWORD` | **Oui** | — | Mot de passe du login SQL Server |
| `JWT_SECRET` | **Oui** | — | Signe les tokens JWT — chaîne aléatoire ≥ 32 caractères |
| `MSSQL_SERVER` | Non | `localhost` | Hôte SQL Server |
| `MSSQL_PORT` | Non | `1433` | Port SQL Server |
| `MSSQL_DATABASE` | Non | `bd-rcc` | Nom de la base |
| `PORT` | Non | `8080` | Port HTTP du portail |
| `AD_URL` | Non | vide (AD désactivé) | URL du service SOAP Active Directory Ecobank — voir §9 |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | Non | vide (envoi désactivé) | Requis pour "mot de passe oublié par e-mail" et les notifications d'approbation de compte |
| `CORS_ALLOWED_ORIGINS` | Non | vide (same-origin uniquement) | Domaines externes autorisés en CORS |

Aucun assistant IA n'est fourni par ce portail — les quelques points d'appel IA du
frontend (assistant RAF, traduction, analyse Clairaudio, transcription audio) renvoient
toujours une erreur claire côté client, il n'y a rien à configurer côté serveur pour ça.

---

## 4. Build

```powershell
# Si mvn n'est pas dans le PATH, utiliser celui fourni par IntelliJ :
# $mvn = "C:\Program Files\JetBrains\IntelliJ IDEA <version>\plugins\maven\lib\maven3\bin\mvn.cmd"

$env:JAVA_HOME = "C:\Chemin\vers\ton\JDK"
mvn clean package -DskipTests
```

Le jar est produit dans `target\rcc-portal-1.0.0.jar`.

---

## 5. Lancer l'application

```powershell
$env:MSSQL_USER = "rcc_app"
$env:MSSQL_PASSWORD = "VotreMotDePasse"
$env:JWT_SECRET = "une-chaine-secrete-aleatoire-d-au-moins-32-caracteres"
$env:PORT = "8080"

java -jar target\rcc-portal-1.0.0.jar
```

### Vérifier que ça tourne

```powershell
curl http://localhost:8080/
```

Puis ouvrir `http://localhost:8080` dans un navigateur.

---

## 6. Rendre le site accessible aux autres machines du réseau local

**PowerShell EN ADMINISTRATEUR** (voir §10 si "accès refusé" malgré une fenêtre qui semble élevée) :

```powershell
New-NetFirewallRule -DisplayName "RCC Portal 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Domain,Private
```

Trouver l'IP réseau local de la machine à partager :

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" }
```

Les collègues sur le même réseau ouvrent alors `http://<IP-de-la-machine>:8080`.

---

## 7. Comptes de test

| Matricule | Mot de passe | Rôle | 2FA |
|---|---|---|---|
| `edoudou` | `Milacam@2026` | admin | **Aucune** — accès direct (compte exempté, voir `AuthService.TWO_FACTOR_EXEMPT_MATRICULES`) |
| autres comptes | — | agent/admin | TOTP standard (Microsoft/Google Authenticator) au premier login |

---

## 8. Active Directory réel (Ecobank) — à activer uniquement sur le réseau interne

```powershell
$env:AD_URL = "http://epg-eci-apps01/RIB_DELIVERY/RIB_DELIVRY.asmx"
```

⚠️ Deux points à valider avant de compter dessus en production :
1. Ce host n'est joignable **que depuis le réseau interne Ecobank** (VPN ou sur site) — inutile de l'activer ailleurs, chaque connexion ajoutera un délai de timeout réseau avant de retomber sur l'authentification locale.
2. La valeur `successStatus` (par défaut `"OK"`, voir `AdAuthProperties`) qui détermine une authentification réussie est **une hypothèse non confirmée avec l'IT Ecobank**. À valider avec un identifiant AD réel avant tout déploiement sérieux.

---

## 9. Dépannage — problèmes réellement rencontrés

| Symptôme | Cause | Solution |
|---|---|---|
| `Port XXXX was already in use` | Un process java tourne déjà dessus | `Get-NetTCPConnection -LocalPort XXXX` pour trouver le PID, `Stop-Process -Id <PID> -Force`, ou changer `$env:PORT` |
| `JAVA_HOME environment variable is not defined correctly` | Variable non définie dans **cette** session PowerShell | Refaire `$env:JAVA_HOME = "..."` avant `mvn` — ne persiste pas d'une fenêtre à l'autre |
| `Failed to delete ...rcc-portal-1.0.0.jar` lors de `mvn clean` | Le jar est verrouillé par une instance déjà lancée | Arrêter le process java qui tourne dessus avant de rebuilder |
| Erreur SQL `18456` "Login failed" | Mot de passe du login SQL Server incorrect/expiré | `ALTER LOGIN rcc_app WITH PASSWORD = '...', CHECK_POLICY = OFF` (en admin sysadmin, connexion Windows intégrée) |
| Collègues ne peuvent pas se connecter en réseau local | Pare-feu Windows bloque le port | Voir §6 |
| PowerShell "administrateur" refuse quand même (`Accès refusé`) | La fenêtre n'est en fait pas élevée (arrive avec certains raccourcis épinglés qui réutilisent une fenêtre existante) | Vérifier avec `whoami /groups \| Select-String "Administrat"` — doit afficher *"Activé par défaut"*, pas *"refus uniquement"*. Sinon : Démarrer → taper `PowerShell` → **Ctrl+Maj+Entrée** pour forcer une vraie élévation |

---

## 10. Points de sécurité avant d'aller au-delà d'une démo interne

- Le compte `edoudou` n'a **aucune 2FA** (mot de passe seul) — exception délibérée, à restreindre/retirer si le portail est exposé plus largement.
- Pas de HTTPS actuellement — les cookies de session ne sont pas marqués `Secure` (profil ≠ `production`, voir `AuthController`). Ne pas exposer au-delà d'un réseau local fermé sans TLS.
- `SMTP_USERNAME`/`SMTP_PASSWORD` vides par défaut → réinitialisation de mot de passe par e-mail indisponible tant que non configuré.
- `AD_URL` vide par défaut → tous les comptes utilisent l'authentification locale (bcrypt) tant que non configuré et le réseau Ecobank accessible.

---

## 11. Déployer ailleurs / fonctionnement hors internet

**Portabilité** : `target\rcc-portal-1.0.0.jar` est un jar Spring Boot standard (Tomcat embarqué) — il tourne sur n'importe quelle machine avec un JRE 21+ (Windows, Linux, macOS), du moment qu'elle peut atteindre l'instance SQL Server. Il suffit de copier le jar + définir les mêmes variables d'environnement (§3) sur la nouvelle machine ; `deploy-local.ps1` est un confort Windows/PowerShell, pas une dépendance du jar lui-même. Sur Linux/macOS, remplacer par un simple `java -jar rcc-portal-1.0.0.jar` avec les variables exportées.

**Fonctionnement hors internet** : toutes les librairies front (polices, Leaflet, Chart.js, xlsx) et les images d'illustration sont embarquées dans `src/main/resources/static/vendor/` — plus aucun CDN externe n'est chargé au démarrage de la page. Seule exception assumée : les tuiles de la carte "Agence" (OpenStreetMap) nécessitent internet pour s'afficher visuellement ; sans connexion, cette carte reste grise mais le reste du portail fonctionne normalement (dégradation propre, pas d'erreur JS). Les liens vers la presse économique externe (Financial Afrik, Sika Finance, etc.) et les outils SSO tiers (Adobe Sign, Ariba…) sont des liens cliqués par l'utilisateur, pas des ressources chargées — ils nécessitent internet uniquement si on clique dessus.
