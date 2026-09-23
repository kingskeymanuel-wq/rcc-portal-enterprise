# Intégration Microsoft Graph (Teams / Outlook réels)

Ce document décrit ce qu'**il faut faire dans le portail Azure** — je ne peux pas le faire à
votre place, ça nécessite un accès administrateur sur votre tenant Entra ID (Azure AD) Ecobank.

## Ce qui est déjà fait côté code

- `MicrosoftGraphClient.java` : authentification par identifiants d'application (client
  credentials, pas de connexion utilisateur individuelle), envoi d'e-mail réel Outlook,
  tentative d'envoi de message Teams réel.
- `GraphController.java` : endpoints `/api/graph/send-outlook` et `/api/graph/send-teams`.
- Le bouton "Outlook"/"Teams" de MON RCC tente l'envoi réel si Graph est configuré,
  et retombe automatiquement sur le lien classique (mailto:/Teams web) sinon ou en cas
  d'échec — **rien ne casse si vous ne configurez pas Graph**, ça continue de marcher comme
  avant.

## ⚠️ Limite importante à connaître avant de commencer (Teams)

**L'envoi Outlook (e-mail) fonctionne de façon fiable** avec des permissions "Application"
seules. **L'envoi Teams, lui, n'est pas garanti** : Microsoft restreint fortement la création
de discussions et l'envoi de messages Teams par une application seule (sans utilisateur
connecté) — la permission `ChatMessage.Send` en mode Application est soumise à des règles
spécifiques et n'est pas toujours accordée, même avec le consentement admin. Si Teams échoue
après configuration, ce n'est pas un bug côté code — c'est une restriction Microsoft sur votre
tenant. Le repli automatique vers le lien Teams classique reste actif dans ce cas.

## Étapes dans le portail Azure (portal.azure.com)

### 1. Créer l'inscription d'application
1. Allez dans **Azure Active Directory** (ou **Entra ID**) → **Inscriptions d'applications** → **Nouvelle inscription**
2. Nom : `RCC Portal Graph Integration` (ou ce que vous voulez)
3. Types de comptes pris en charge : **Comptes dans cet annuaire organisationnel uniquement**
4. Pas besoin d'URI de redirection (on utilise le flux "client credentials", pas de connexion utilisateur)
5. Cliquez **Inscrire**

### 2. Récupérer les identifiants
Sur la page de l'application créée :
- **ID d'application (client)** → c'est votre `AZURE_CLIENT_ID`
- **ID d'annuaire (locataire)** → c'est votre `AZURE_TENANT_ID`

### 3. Créer un secret client
1. Menu **Certificats et secrets** → **Nouveau secret client**
2. Description libre, expiration (12 ou 24 mois recommandé)
3. **Copiez immédiatement la "Valeur"** (pas l'ID du secret) — elle ne sera plus jamais affichée après avoir quitté la page
4. C'est votre `AZURE_CLIENT_SECRET`

### 4. Ajouter les permissions API (Application, pas Déléguée)
Menu **Autorisations API** → **Ajouter une autorisation** → **Microsoft Graph** → **Autorisations d'application** (pas "Déléguées") :
- `Mail.Send` — obligatoire pour l'envoi Outlook réel
- `Chat.Create` — pour Teams (peut être refusé, voir avertissement ci-dessus)
- `ChatMessage.Send` — pour Teams (peut être refusé, voir avertissement ci-dessus)

### 5. Accorder le consentement administrateur
**Obligatoire** — sans ça, aucune des permissions ci-dessus ne fonctionne, même une fois ajoutées.
Bouton **"Accorder un consentement administrateur pour Ecobank"** en haut de la page Autorisations API.
Vous devez être administrateur global ou administrateur d'application du tenant pour faire ça.

## Configuration côté portail RCC

Une fois les 3 valeurs récupérées, ajoutez-les dans `application-secrets.yml` (même fichier
que la clé Anthropic, à la racine du projet — voir ce fichier s'il existe déjà) :

```yaml
graph:
  tenant-id: "votre-tenant-id"
  client-id: "votre-client-id"
  client-secret: "votre-client-secret"
```

Redémarrez l'application. Vérifiez que ça fonctionne via `GET /api/graph/status` (connecté) —
doit répondre `{"configured": true}`.
