#!/usr/bin/env python3
"""
OCR local pour l'import KPI par capture d'écran — PaddleOCR (gratuit, sans cloud).
Usage : python ocr.py <chemin_image>
Sortie (stdout) : JSON structuré {"rawText": "...", "lines": [{"text": "...", "confidence": 0.98}, ...]}

⚠ Ce script ne reconstruit PAS la structure de tableau (lignes/colonnes) — PaddleOCR détecte
du texte positionné, pas une sémantique de tableau. La reconstruction agent/métrique/valeur
se fait côté Java (LocalOcrService.mapLinesToKpiEntries) à partir des coordonnées Y/X de
chaque ligne détectée, en s'appuyant sur l'alignement des cellules.

Prérequis (à installer manuellement, non fournis par ce dépôt) :
    pip install paddleocr paddlepaddle --break-system-packages
Le premier lancement télécharge les modèles de reconnaissance (~100 Mo, une seule fois).
"""
import sys
import json

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python ocr.py <chemin_image>"}))
        sys.exit(1)

    image_path = sys.argv[1]

    try:
        from paddleocr import PaddleOCR
    except ImportError:
        print(json.dumps({
            "error": "PaddleOCR non installé. Exécutez : pip install paddleocr paddlepaddle --break-system-packages"
        }))
        sys.exit(2)

    try:
        # lang='fr' pour les libellés français (ex. "Score qualité"), désactive l'angle
        # classifier (plus rapide, les captures d'écran ne sont jamais pivotées).
        ocr = PaddleOCR(use_angle_cls=False, lang='fr', show_log=False)
        result = ocr.ocr(image_path, cls=False)

        lines = []
        all_text = []
        if result and result[0]:
            for box in result[0]:
                coords, (text, confidence) = box
                # coords = 4 points (x,y) du quadrilatère — on garde juste le coin haut-gauche
                # pour la reconstruction de tableau côté Java (alignement par colonne/ligne).
                x = coords[0][0]
                y = coords[0][1]
                lines.append({"text": text, "confidence": round(float(confidence), 3), "x": round(x, 1), "y": round(y, 1)})
                all_text.append(text)

        print(json.dumps({"rawText": " ".join(all_text), "lines": lines}, ensure_ascii=False))

    except Exception as e:
        print(json.dumps({"error": "Erreur OCR : " + str(e)}))
        sys.exit(3)

if __name__ == "__main__":
    main()
