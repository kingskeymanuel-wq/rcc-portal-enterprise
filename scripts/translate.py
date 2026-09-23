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


def fail(message, code=1):
    print(json.dumps({"error": message}, ensure_ascii=False))
    sys.exit(code)


def main():
    if len(sys.argv) < 3:
        fail("Usage: python translate.py <sourceLang|auto> <targetLang>")

    source_lang = sys.argv[1].strip().lower()
    target_lang = sys.argv[2].strip().lower()

    text = sys.stdin.read()
    if not text or not text.strip():
        fail("Aucun texte reçu sur STDIN.")

    try:
        import argostranslate.translate
    except ImportError:
        fail("argostranslate non installé. Exécutez : pip install argostranslate --break-system-packages")

    detected = source_lang
    if source_lang == "auto":
        try:
            from langdetect import detect
            detected = detect(text)
        except ImportError:
            # langdetect absent — repli documenté sur le français plutôt qu'un blocage total,
            # cohérent avec le reste du projet (contenu du portail à la base en français).
            detected = "fr"
        except Exception:
            detected = "fr"

    try:
        installed_languages = argostranslate.translate.get_installed_languages()
    except Exception as e:
        fail("Impossible de lister les langues installées : " + str(e))

    from_candidates = [lang for lang in installed_languages if lang.code == detected]
    to_candidates = [lang for lang in installed_languages if lang.code == target_lang]

    if not from_candidates:
        fail("Langue source « " + detected + " » non installée localement. "
             "Voir l'en-tête de ce script pour la procédure d'installation d'un paquet de langue.", 2)
    if not to_candidates:
        fail("Langue cible « " + target_lang + " » non installée localement. "
             "Voir l'en-tête de ce script pour la procédure d'installation d'un paquet de langue.", 2)

    try:
        translation = from_candidates[0].get_translation(to_candidates[0])
        if translation is None:
            fail("Aucun paquet de traduction installé pour la paire " + detected + " → " + target_lang + ".", 2)
        result = translation.translate(text)
    except Exception as e:
        fail("Erreur de traduction : " + str(e))

    print(json.dumps({"translatedText": result, "detectedSourceLang": detected}, ensure_ascii=False))


if __name__ == "__main__":
    main()
