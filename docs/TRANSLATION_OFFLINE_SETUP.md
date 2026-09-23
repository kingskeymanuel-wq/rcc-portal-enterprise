# Traducteur, correcteur et recherche web — configuration des API

## Sources de traduction (ordre d'essai)

Le Traducteur (`/translator` et bouton « Traduire » de RAF) essaie les sources configurées
dans l'ordre de `rcc.translation.provider-order` et s'arrête à la première qui répond
correctement. Une source sans clé/URL est ignorée. L'interface affiche la source utilisée
(« via DeepL »…) et le bouton **Diagnostiquer la connexion** teste chacune d'elles.

| Id | Source | Configuration | Remarques |
|---|---|---|---|
| `local` | Argos Translate (hors-ligne) | `RCC_TRANSLATION_OFFLINE_PYTHON_EXECUTABLE`, `RCC_TRANSLATION_OFFLINE_SCRIPT_PATH` | Aucun accès internet, voir ci-dessous |
| `custom` | Service interne Ecobank | `RCC_TRANSLATION_CUSTOM_URL` | Contrat `POST {text,sourceLang,targetLang}` → `{translatedText}` |
| `libretranslate` | LibreTranslate | `RCC_TRANSLATION_LIBRETRANSLATE_URL` (+ `_API_KEY`) | **Recommandé** : `docker run -p 5000:5000 libretranslate/libretranslate` sur un serveur interne (même moteur qu'Argos, mais chargé une seule fois → bien plus rapide) |
| `deepl` | DeepL API | `RCC_TRANSLATION_DEEPL_API_KEY` | Meilleure qualité fr/en/es/pt ; clé gratuite en `:fx` (500 000 car./mois) |
| `azure` | Azure AI Translator | `RCC_TRANSLATION_AZURE_KEY`, `RCC_TRANSLATION_AZURE_REGION` | Couvre haoussa, yoruba, igbo, swahili, lingala, somali… ; offre gratuite 2 M car./mois |
| `mymemory` | MyMemory | `RCC_TRANSLATION_MYMEMORY_EMAIL` (optionnel) | Gratuit sans clé, dernier recours ; l'e-mail porte le quota de 5 000 à 50 000 car./jour |

« Détecter la langue » fonctionne avec toutes les sources : LibreTranslate, DeepL et Azure
détectent eux-mêmes ; pour les autres, le portail détecte la langue localement.

## Correcteur (onglet « Correcteur »)

- Français, anglais US et UK : LanguageTool embarqué, aucun appel réseau.
- Autres langues : serveur LanguageTool interne optionnel
  (`docker run -p 8010:8010 erikvl87/languagetool`) puis `RCC_SPELLCHECK_REMOTE_URL=http://srv:8010`.
  Ne jamais pointer vers le service public languagetool.org avec des données clients.
- Vocabulaire métier jamais signalé : `RCC_SPELLCHECK_IGNORE_WORDS` + table `WordTerms`.
- Règles stylistiques désactivées : `RCC_SPELLCHECK_DISABLED_RULES`.

## Recherche web (barre de recherche globale et RAF)

Bing Web Search API a été retirée par Microsoft en août 2025 : l'ancienne configuration ne
renvoyait plus aucun résultat. Activer `WEBSEARCH_ENABLED=true` puis configurer au moins un
moteur (essayés dans l'ordre de `WEBSEARCH_PROVIDER_ORDER`) :

| Id | Moteur | Configuration |
|---|---|---|
| `searxng` | SearXNG auto-hébergé (**recommandé**, aucune donnée chez un tiers) | `WEBSEARCH_SEARXNG_URL` (activer le format JSON dans `settings.yml`) |
| `brave` | Brave Search API | `WEBSEARCH_BRAVE_KEY` |
| `tavily` | Tavily | `WEBSEARCH_TAVILY_KEY` |
| `google` | Google Programmable Search | `WEBSEARCH_GOOGLE_KEY`, `WEBSEARCH_GOOGLE_CX` |
| `wikipedia` | Wikipédia (gratuit, sans clé) | `WEBSEARCH_WIKIPEDIA_ENABLED` (true par défaut) |

Diagnostic (compte IT/admin) : `GET /api/ralph/search/diagnose`.

---

# Traduction locale hors-ligne (Argos Translate) — installation

La source `local` tourne entièrement sur ce serveur sans aucun appel réseau au moment de
traduire.

## 1. Installer les dépendances Python

```bash
pip install argostranslate langdetect --break-system-packages
```

- `argostranslate` : le moteur de traduction hors-ligne lui-même (réseaux de neurones légers,
  open source, gratuit).
- `langdetect` : optionnel. Le portail détecte déjà la langue lui-même dans la plupart des
  cas ; sans langdetect, le script renvoie une erreur claire (au lieu de supposer, à tort, que
  le texte est en français) quand la détection du portail n'a pas pu conclure.

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
ligne "Argos Translate (local, hors-ligne)" doit passer à **OK**. Testez ensuite
une vraie traduction français → anglais dans l'écran principal.

## Ajouter une langue plus tard

Relancez l'étape 2 en ajoutant la paire voulue à la liste `paires` — aucune autre
configuration à toucher, aucun redémarrage requis (le script relit les paquets installés à
chaque appel).
