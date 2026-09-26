"""Publica el repo de extensiones de Deus Ex Manga para Mihon.

Adaptado de publish-repo.py (keiyoushi/extensions-source, Apache 2.0): en vez de subir a
Releases de otro repo, deja los APK y el índice en una carpeta que el workflow publica como
rama `repo` de este mismo repositorio.

Uso: python deus-publish.py <carpeta-salida>
"""

import gzip
import html
import json
import os
import shutil
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import index_pb2  # noqa: E402
from google.protobuf import json_format  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]).resolve()
CONFIG = json.loads((ROOT / "deus-repo.json").read_text(encoding="utf-8"))

REPOSITORY = os.environ["GITHUB_REPOSITORY"]
SOURCE_BRANCH = os.environ.get("GITHUB_REF_NAME", "deus")
RAW = f"https://raw.githubusercontent.com/{REPOSITORY}"
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"


def icon_url(module: str, theme: str | None) -> str:
    candidates = [f"src/{module.replace('.', '/')}/{ICON_FILE}"]
    if theme:
        candidates.append(f"lib-multisrc/{theme}/{ICON_FILE}")
    candidates.append(f"core/src/main/{ICON_FILE}")
    path = next((c for c in candidates if (ROOT / c).exists()), candidates[-1])
    return f"{RAW}/{SOURCE_BRANCH}/{path}"


KEIYOUSHI_INDEX = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json"


def keiyoushi_ids() -> dict[str, set[int]]:
    with urllib.request.urlopen(KEIYOUSHI_INDEX, timeout=60) as resp:
        data = json.load(resp)
    return {
        e["packageName"]: {int(s["id"]) for s in e.get("sources", [])}
        for e in data["extensionList"]["extensions"]
    }


def check_ids(extensions: list) -> None:
    """Si el ID de una fuente cambia, la biblioteca de quien la use se pierde: mejor no publicar."""
    upstream = keiyoushi_ids()
    # Extensiones que Keiyoushi ya no publica (p. ej. NineManga): sus IDs se fijan en deus-repo.json.
    legacy = {pkg: {int(i) for i in ids} for pkg, ids in CONFIG.get("legacyIds", {}).items()}
    errors = []
    for ext in extensions:
        expected = upstream.get(ext.packageName) or legacy.get(ext.packageName)
        ids = {s.id for s in ext.sources}
        if expected is not None and ids != expected:
            errors.append(f"{ext.name}: IDs {sorted(ids)} != Keiyoushi {sorted(expected)}")
    if errors:
        sys.exit("El ID de fuente no coincide con el de Keiyoushi:\n" + "\n".join(errors))


def main() -> None:
    (OUT / "apk").mkdir(parents=True, exist_ok=True)
    extensions = []
    for info_file in sorted(ROOT.glob("src/*/*/build/keiyoushi-source-info.json")):
        info = json.loads(info_file.read_text(encoding="utf-8"))
        build = info_file.parent
        apk = next((build / "outputs/apk/release").glob("*.apk"))
        jar = next((build / "outputs/jar/release").glob("*.jar"), None)
        shutil.copy2(apk, OUT / "apk" / apk.name)
        resources = index_pb2.Resources(
            apkUrl=f"{RAW}/repo/apk/{apk.name}",
            iconUrl=icon_url(info["module"], info.get("theme")),
        )
        if jar is not None:
            shutil.copy2(jar, OUT / "apk" / jar.name)
            resources.jarUrl = f"{RAW}/repo/apk/{jar.name}"
        extensions.append(
            index_pb2.Extension(
                name=info["name"],
                packageName=info["packageName"],
                resources=resources,
                extensionLib=info["extensionLib"],
                versionCode=info["versionCode"],
                versionName=info["versionName"],
                contentWarning=info["contentWarning"],
                sources=[
                    index_pb2.Source(
                        id=int(s["id"]),
                        name=s["name"],
                        language=s["lang"],
                        homeUrl=s["baseUrl"],
                        mirrorUrls=s.get("mirrorUrls", []),
                    )
                    for s in info["sources"]
                ],
            )
        )

    if not extensions:
        sys.exit("No se ha compilado ninguna extensión: revisa deus-extensions.txt")

    extensions.sort(key=lambda e: e.packageName)
    check_ids(extensions)
    index = index_pb2.Index(
        name=CONFIG["name"],
        badgeLabel=CONFIG["badgeLabel"],
        signingKey=CONFIG["signingKey"],
        contact=index_pb2.Contact(website=f"https://github.com/{REPOSITORY}"),
        extensionList=index_pb2.ExtensionList(extensions=extensions),
    )

    (OUT / "index.json").write_text(
        json_format.MessageToJson(index, always_print_fields_with_no_presence=False, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    (OUT / "index.pb").write_bytes(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

    rows = "\n".join(
        f'<a href="apk/{html.escape(Path(e.resources.apkUrl).name)}">{html.escape(e.name)}</a> v{html.escape(e.versionName)}'
        for e in extensions
    )
    (OUT / "index.html").write_text(
        f"<!doctype html>\n<html lang=\"es\">\n<head><meta charset=\"utf-8\"><title>{html.escape(CONFIG['name'])}</title></head>\n"
        f"<body>\n<p>Añade este repositorio en Mihon: <code>https://github.com/{REPOSITORY}/raw/repo/index.pb</code></p>\n"
        f"<pre>\n{rows}\n</pre>\n</body>\n</html>\n",
        encoding="utf-8",
    )
    print(f"Publicadas {len(extensions)} extensiones: " + ", ".join(e.name for e in extensions))


if __name__ == "__main__":
    main()
