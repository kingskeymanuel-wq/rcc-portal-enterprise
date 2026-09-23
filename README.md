# RCC Portal

Portail RCC Ecobank construit avec Spring Boot, SQL Server et Thymeleaf. Il fournit un portail web ainsi qu'une API REST pour l'authentification, la gestion des équipes et utilisateurs, les procédures, la qualité, les KPI, la présence, les modèles d'e-mail et les échanges MON RCC.

## Démarrage rapide

### Prérequis

- JDK 21 ou version ultérieure
- Maven 3.9 ou version ultérieure
- Une instance SQL Server accessible
- Une base `bd-rcc` contenant le schéma applicatif

> Le dépôt ne contient que des migrations incrémentales dans `db/migrations/`. Pour une base vierge, créez ou importez d'abord le schéma complet avant de lancer l'application. Consultez [DEPLOIEMENT_LOCAL.md](DEPLOIEMENT_LOCAL.md) pour les détails.

### Configurer les variables d'environnement

Les trois variables suivantes sont requises :

```powershell
$env:MSSQL_USER = "rcc_app"
$env:MSSQL_PASSWORD = "votre-mot-de-passe"
$env:JWT_SECRET = "une-chaine-aleatoire-d-au-moins-32-caracteres"
```

Paramètres facultatifs courants :

| Variable | Valeur par défaut | Description |
|---|---|---|
| `MSSQL_SERVER` | `localhost` | Hôte SQL Server |
| `MSSQL_PORT` | `1433` | Port SQL Server |
| `MSSQL_DATABASE` | `bd-rcc` | Nom de la base |
| `PORT` | `8080` | Port HTTP de l'application |
| `MSSQL_ENCRYPT` | `true` | Active le chiffrement de la connexion SQL Server |
| `MSSQL_TRUST_SERVER_CERTIFICATE` | `true` | Accepte le certificat présenté par SQL Server |
| `SMTP_HOST` | `smtp.office365.com` | Serveur SMTP |
| `SMTP_PORT` | `587` | Port SMTP |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | vide | Identifiants SMTP |
| `SMTP_FROM_ADDRESS` | vide | Adresse d'expédition des e-mails |
| `AD_URL` | vide | URL du service Active Directory ; vide désactive l'intégration |
| `CORS_ALLOWED_ORIGINS` | vide | Origines CORS autorisées |
| `SPRING_PROFILES_ACTIVE` | `default` | Utiliser `production` pour les cookies sécurisés |

### Lancer l'application

```powershell
mvn clean package
java -jar target\rcc-portal-1.0.0.jar
```

Ouvrez ensuite [http://localhost:8080](http://localhost:8080). L'état de l'application et de sa connexion à SQL Server est disponible sur [http://localhost:8080/api/health](http://localhost:8080/api/health).

Pour Windows, le script interactif ci-dessous prépare les variables manquantes, construit l'application et la démarre :

```powershell
.\deploy-local.ps1
```

## Fonctionnalités

- Authentification locale ou Active Directory, JWT, cookies de session, limitation des tentatives et double authentification TOTP.
- Gestion des utilisateurs, profils, équipes, rôles et permissions d'onglets.
- Présence et suivi des événements KPI.
- Procédures, étapes et pièces jointes référencées par URL.
- Évaluations Clairaudio et plans de coaching.
- Modèles d'e-mail et groupes de destinataires.
- Espace MON RCC : publications, commentaires, réactions, abonnements, notifications et messagerie.

Les contrôleurs REST sont regroupés dans `src/main/java/com/ecobank/rccportal/controller`. Le portail web est servi à la racine (`/`) depuis `src/main/resources/static/index.html`; l'ancienne page de connexion reste disponible sur `/login`.

## Tests

```powershell
mvn test
```

Les tests utilisent notamment H2 et Mockito. Aucun service externe ne doit être requis pour exécuter la suite de tests.

## Configuration et sécurité

- Hibernate est configuré avec `ddl-auto: none` : l'application ne crée ni ne modifie le schéma SQL Server.
- En profil `production`, les cookies de session sont marqués `Secure`. Déployez derrière HTTPS avant toute exposition hors d'un réseau de confiance.
- Ne versionnez jamais `MSSQL_PASSWORD`, `JWT_SECRET` ou les identifiants SMTP.
- L'intégration Active Directory doit être activée uniquement lorsqu'elle est accessible depuis le réseau concerné.

## Déploiement local et dépannage

Le guide détaillé couvre la préparation de SQL Server, les migrations, le pare-feu Windows, le partage sur le réseau local et les problèmes fréquents : [DEPLOIEMENT_LOCAL.md](DEPLOIEMENT_LOCAL.md).
