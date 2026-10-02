"""Serveur de transcription local du RCC Portal (faster-whisper), sans Internet.

API compatible OpenAI, utilisée par le portail (RCC_LOCAL_WHISPER_URL) :
  GET  /health                     -> {"status": "ok", "model": "..."}
  POST /v1/audio/transcriptions    multipart : file, language (fr), model (ignoré) -> {"text": "..."}

Lancement : python whisper_server.py --model C:\\rcc-offline\\whisper\\small --port 8090
Le modèle est un dossier faster-whisper (CTranslate2) copié par le kit hors ligne : aucun téléchargement.
"""
import argparse
import json
import os
import tempfile
import threading
from email.parser import BytesParser
from email.policy import default as default_policy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from faster_whisper import WhisperModel

MODEL = None
MODEL_NAME = ""
LOCK = threading.Lock()  # un seul calcul à la fois : le processeur reste disponible pour le portail


def parse_multipart(content_type, body):
    """Champs d'un formulaire multipart/form-data -> {nom: (nom_de_fichier, octets)}."""
    msg = BytesParser(policy=default_policy).parsebytes(
        b"Content-Type: " + content_type.encode("latin-1") + b"\r\nMIME-Version: 1.0\r\n\r\n" + body)
    fields = {}
    for part in msg.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if name:
            fields[name] = (part.get_filename(), part.get_payload(decode=True) or b"")
    return fields


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.rstrip("/") == "/health":
            self._send(200, {"status": "ok", "model": MODEL_NAME})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path.rstrip("/") != "/v1/audio/transcriptions":
            self._send(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        fields = parse_multipart(self.headers.get("Content-Type", ""), self.rfile.read(length))
        if "file" not in fields:
            self._send(400, {"error": "champ 'file' manquant"})
            return
        filename, audio = fields["file"]
        language = (fields.get("language", (None, b"fr"))[1] or b"fr").decode() or "fr"
        suffix = os.path.splitext(filename or "audio.wav")[1] or ".wav"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp.write(audio)
            path = tmp.name
        try:
            with LOCK:
                segments, _ = MODEL.transcribe(path, language=language, vad_filter=True, beam_size=5)
                text = " ".join(s.text.strip() for s in segments).strip()
            self._send(200, {"text": text})
        except Exception as e:  # noqa: BLE001 — l'erreur est renvoyée au portail
            self._send(500, {"error": str(e)})
        finally:
            os.remove(path)

    def log_message(self, fmt, *args):
        print("[whisper] " + fmt % args, flush=True)


def main():
    global MODEL, MODEL_NAME
    p = argparse.ArgumentParser()
    p.add_argument("--model", required=True, help="dossier du modèle faster-whisper (ex. C:\\rcc-offline\\whisper\\small)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8090)
    p.add_argument("--device", default="auto", help="cpu, cuda ou auto")
    p.add_argument("--compute-type", default="int8", help="int8 (processeur), float16 (carte graphique)")
    a = p.parse_args()
    MODEL_NAME = os.path.basename(a.model.rstrip("\\/"))
    MODEL = WhisperModel(a.model, device=a.device, compute_type=a.compute_type, local_files_only=True)
    print(f"[whisper] modèle {MODEL_NAME} chargé — http://{a.host}:{a.port}", flush=True)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
