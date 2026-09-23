# Traduction locale hors-ligne (Traducteur RAF) — installation

Le Traducteur (`/translator`) essaie d'abord une traduction locale, qui tourne entièrement
sur ce serveur sans aucun appel réseau au moment de traduire. C'est la solution recommandée
tant que l'IT n'a pas ouvert l'accès aux domaines externes utilisés en repli
(`api.mymemory.translated.net`) — Google Translate a été retiré des sources utilisées par
le Traducteur.

## 1. Installer les dépendances Python

```bash
pip install argostranslate langdetect --break-system-packages
```

- `argostranslate` : le moteur de traduction hors-ligne lui-même (réseaux de neurones légers,
  open source, gratuit).
- `langdetect` : optionnel, permet la détection automatique de la langue source ("Détecter la
  langue" dans le sélecteur). Sans lui, le script suppose que le texte est en français.

## 2. Télécharger les paquets de langue (une seule fois, nécessite internet ce jour-là)

Chaque paire de langues (ex. français → anglais) est un paquet séparé à télécharger une
fois — ensuite plus aucun accès internet n'est nécessaire pour l'utiliser.

```bash
python -c "
import argostranslate.package as p
p.update_package_index()
pkgs = p.get_available_packages()
paires = [('fr','en'), ('en','fr'), ('fr','ar'), ('ar','fr'), ('fr','es'), ('es','fr')]
for from_code, to_code in paires:
    match = next((x for x in pkgs if x.from_code == from_code and x.to_code == to_code), None)
    if match:
        p.install_from_path(match.download())
        print('Installé :', from_code, '->', to_code)
    else:
        print('Introuvable :', from_code, '->', to_code)
"
```

Adaptez la liste `paires` aux langues réellement utilisées par les équipes (voir la liste
complète des paires disponibles sur le dépôt officiel Argos Translate). Une paire non
installée renvoie une erreur claire côté Traducteur, indiquant exactement laquelle installer.

## 3. Configurer le portail

Dans `application-secrets.yml` (ou variables d'environnement) :

```yaml
rcc:
  translation:
    offline:
      python-executable: "C:/Python311/python.exe"   # ou "python3" sous Linux
      script-path: "C:/chemin/vers/rcc-portal-spring/scripts/translate.py"
```

Variables d'environnement équivalentes : `RCC_TRANSLATION_OFFLINE_PYTHON_EXECUTABLE`,
`RCC_TRANSLATION_OFFLINE_SCRIPT_PATH`.

## 4. Vérifier

Redémarrez le portail, ouvrez `/translator`, cliquez **"Diagnostiquer la connexion"** — la
ligne "Traduction locale (Argos Translate, hors-ligne)" doit passer à **OK**. Testez ensuite
une vraie traduction français → anglais dans l'écran principal.

## Ajouter une langue plus tard

Relancez l'étape 2 en ajoutant la paire voulue à la liste `paires` — aucune autre
configuration à toucher, aucun redémarrage requis (le script relit les paquets installés à
chaque appel).
