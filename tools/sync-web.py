"""Kopiert die Web-App (Artifact-Quelle) als vollständiges HTML-Dokument in die Handy-App.

Aufruf: python3 tools/sync-web.py pfad/zu/trainingskompass.html
"""
import pathlib, sys

src = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
if not src.lstrip().lower().startswith("<!doctype"):
    src = ('<!doctype html><html lang="de"><head><meta charset="utf-8">'
           '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
           "</head><body>\n" + src + "\n</body></html>\n")
out = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/index.html"
out.write_text(src, encoding="utf-8")
print("geschrieben:", out)
