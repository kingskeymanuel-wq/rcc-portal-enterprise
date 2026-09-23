#!/usr/bin/env python3
"""
Traduction locale hors-ligne pour le Traducteur RAF (/translator) — Argos Translate
(gratuit, open source, tourne entièrement sur cette machine, aucun appel réseau au moment
de la traduction). Pensé pour le même besoin que scripts/ocr.py : un réseau Ecobank qui
bloque les domaines externes (api.mymemory.translated.net) ne doit
jamais empêcher une fonctionnalité de marcher.

Usage : python translate.py <sourceLang|auto> <targetLang>
Texte à traduire : lu sur STDIN (UTF-8) — jamais en argument, pour ne pas dépendre de
l'échappement shell (apostrophes, guillemets, accents).
Sortie (stdout) : JSON {"translatedText": "...", "detectedSourceLang": "fr"}
                  ou {"error": "..."} avec exit code != 0.

Prérequis (à installer manuellement, non fournis par ce dépôt) :
    pip install argostranslate langdetect --break-system-packages
Puis télécharger UNE FOIS (nécessite internet ce jour-là seulement — jamais ensuite) les
paquets de langues nécessaires, ex. français<->anglais :
    python -c "import argostranslate.package as p; p.update_package_index(); \
      pkgs = p.get_available_packages(); \
      [p.install_from_path(x.download()) for x in pkgs if x.from_code in ('fr','en') and x.to_code in ('fr','en')]"
Voir docs/TRANSLATION_OFFLINE_SETUP.md pour la procédure complète, y compris comment ajouter
d'autres langues (swahili, haoussa, etc. — sous réserve de disponibilité d'un paquet Argos
pour cette paire précise).
"""
import sys
import json
import io

# UTF-8 forcé sur STDIN/STDOUT : sous Windows, Python utilise cp1252 par défaut — les accents
# arrivaient corrompus et print(ensure_ascii=False) plantait sur l'arabe, le chinois, etc.
sys.stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8", errors="replace")
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

# Codes du portail (ISO 639-1, parfois avec région) -> codes des paquets Argos.
ARGOS_CODES = {"zh-cn": "zh", "zh-hans": "zh", "zh": "zh", "zh-tw": "zt", "zh-hant": "zt", "iw": "he",
               "pt-br": "pt", "pt-pt": "pt", "en-gb": "en", "en-us": "en"}


def to_argos(code):
    code = (code or "").strip().lower().replace("_", "-")
    return ARGOS_CODES.get(code, code.split("-")[0])


def fail(message, code=1):
    print(json.dumps({"error": message}, ensure_ascii=False))
    sys.stdout.flush()
    sys.exit(code)


def main():
    if len(sys.argv) < 3:
        fail("Usage: python translate.py <sourceLang|auto> <targetLang>")

    source_lang = sys.argv[1].strip().lower()
    target_lang = to_argos(sys.argv[2])

    text = sys.stdin.read()
    if not text or not text.strip():
        fail("Aucun texte reçu sur STDIN.")

    try:
        import argostranslate.translate
    except ImportError:
        fail("argostranslate non installé. Exécutez : pip install argostranslate --break-system-packages")

    if source_lang == "auto":
        try:
            from langdetect import detect, DetectorFactory
            DetectorFactory.seed = 0  # résultat stable d'un appel à l'autre (langdetect est aléatoire sinon)
            detected = to_argos(detect(text))
        except ImportError:
            # Plus de repli silencieux sur le français (qui produisait des « traductions » absurdes
            # d'un texte anglais traité comme du français) : on demande une langue explicite.
            fail("Détection automatique indisponible (langdetect non installé) — précisez la langue source.", 2)
        except Exception:
            fail("Impossible de détecter la langue de ce texte — précisez la langue source.", 2)
    else:
        detected = to_argos(source_lang)

    if detected == target_lang:
        print(json.dumps({"translatedText": text, "detectedSourceLang": detected}, ensure_ascii=False))
        return

    try:
        installed_languages = argostranslate.translate.get_installed_languages()
    except Exception as e:
        fail("Impossible de lister les langues installées : " + str(e))

    from_candidates = [lang for lang in installed_languages if lang.code == detected]
    to_candidates = [lang for lang in installed_languages if lang.code == target_lang]

    if not from_candidates:
        fail("Langue source « " + detected + " » non installée localement. "
             "Voir docs/TRANSLATION_OFFLINE_SETUP.md pour installer le paquet de langue.", 2)
    if not to_candidates:
        fail("Langue cible « " + target_lang + " » non installée localement. "
             "Voir docs/TRANSLATION_OFFLINE_SETUP.md pour installer le paquet de langue.", 2)

    try:
        translation = from_candidates[0].get_translation(to_candidates[0])
        if translation is None:
            fail("Aucun paquet de traduction installé pour la paire " + detected + " → " + target_lang + ".", 2)
        # Ligne par ligne : conserve les sauts de ligne et paragraphes du texte d'origine.
        lines = text.split("\n")
        result = "\n".join(translation.translate(line) if line.strip() else line for line in lines)
    except Exception as e:
        fail("Erreur de traduction : " + str(e))

    print(json.dumps({"translatedText": result, "detectedSourceLang": detected}, ensure_ascii=False))


if __name__ == "__main__":
    main()
