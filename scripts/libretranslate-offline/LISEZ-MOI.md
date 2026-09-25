# LibreTranslate hors ligne pour le RCC Portal (Windows)

Traduction **100 % locale** : RCC Portal → LibreTranslate (127.0.0.1:5000) → Argos Translate.
Aucune donnée ne sort du serveur, aucun accès Internet nécessaire une fois installé.

## Contenu du paquet
| Élément | Rôle |
|---|---|
| `wheels\` | Programmes LibreTranslate 1.9.6 pour **Windows 64 bits + Python 3.11** (déjà inclus, ~96 Mo) |
| `models\` | Modèles de langue — **à remplir à l'étape 1** (trop volumineux pour être inclus : ~100 Mo par modèle) |
| `1-telecharger-modeles.ps1` | Étape 1, sur un PC **avec** Internet |
| `2-installer-serveur.ps1` | Étape 2, sur le **serveur** (sans Internet), en administrateur |
| `0-preparer-programmes.ps1` | Optionnel : régénérer `wheels\` pour une autre version de Python |

## Installation
1. **Sur un PC avec Internet** : ouvrir PowerShell dans ce dossier, puis
   `powershell -ExecutionPolicy Bypass -File .\1-telecharger-modeles.ps1`
   (par défaut : français, anglais, portugais, espagnol, arabe, swahili — ajoutez-en avec `-Languages "fr,en,pt,es,ar,sw,zh"`).
2. **Copier tout le dossier** sur le serveur (clé USB, partage réseau…).
3. **Sur le serveur** : installer **Python 3.11 64 bits** (fichier `python-3.11.x-amd64.exe` récupéré sur python.org depuis n'importe quel PC), puis dans PowerShell **en administrateur** :
   `powershell -ExecutionPolicy Bypass -File .\2-installer-serveur.ps1`
4. Dans le portail : **Traducteur → Diagnostiquer la connexion** → « LibreTranslate (local) : OK ».

Le script crée la tâche planifiée **RCC-LibreTranslate** : LibreTranslate redémarre tout seul à chaque démarrage du serveur.
Journal : `C:\rcc-libretranslate\libretranslate.log`.

## Si les modèles ne peuvent pas être téléchargés par script
Téléchargez-les dans un navigateur et placez les fichiers `.argosmodel` dans `models\` :
- https://argos-net.com/v1/translate-en_fr-1_9.argosmodel
- https://argos-net.com/v1/translate-fr_en-1_9.argosmodel
- (idem `en_pt`/`pt_en`, `en_es`/`es_en`, `en_ar`/`ar_en`, `en_sw`/`sw_en` — noms exacts dans
  https://raw.githubusercontent.com/argosopentech/argospm-index/main/index.json)

## Commandes utiles
- Arrêter : `schtasks /End /TN RCC-LibreTranslate` · Démarrer : `schtasks /Run /TN RCC-LibreTranslate`
- Ajouter une langue : placer ses 2 modèles dans `models\` puis relancer `2-installer-serveur.ps1`.
- Désinstaller : `schtasks /Delete /TN RCC-LibreTranslate /F` puis supprimer `C:\rcc-libretranslate`.
