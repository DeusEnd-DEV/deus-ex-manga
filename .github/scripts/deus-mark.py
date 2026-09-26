"""Añade el sufijo del repo (p. ej. " (Deus)") al nombre de las extensiones antes de compilarlas.

Solo cambia el nombre visible de la extensión: cada `source {}` sin nombre propio recibe
explícitamente el nombre original, porque el ID de la fuente se calcula a partir de él y
cambiarlo haría perder la biblioteca. Se ejecuta en CI sobre la copia de trabajo; no se commitea.
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / "deus-repo.json").read_text(encoding="utf-8"))
SUFFIX = CONFIG.get("nameSuffix", "")


def blocks(text: str, name: str) -> list[tuple[int, int]]:
    """(inicio del contenido, fin del contenido) de cada bloque `name { ... }`."""
    out = []
    for m in re.finditer(rf"\b{name}\s*\{{", text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"{": 1, "}": -1}.get(text[i], 0)
            i += 1
        out.append((m.end(), i - 1))
    return out


def mark(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    sources = blocks(text, "source")
    first_source = sources[0][0] if sources else len(text)
    top = re.search(r'\bname\s*=\s*"([^"]+)"', text[:first_source])
    if top is None:
        print(f"{path}: sin nombre de extensión, no se marca")
        return
    original = top.group(1)

    # De atrás hacia delante para no desplazar las posiciones pendientes.
    for start, end in reversed(sources):
        if re.search(r"\bname\s*=", text[start:end]):
            continue
        line_start = text.rfind("\n", 0, start - 1) + 1
        indent = re.match(r"\s*", text[line_start:]).group(0) + "    "
        text = text[:start] + f'\n{indent}name = "{original}"' + text[start:]

    text = text[: top.start(1)] + original + SUFFIX + text[top.end(1):]
    path.write_text(text, encoding="utf-8")
    print(f"{path.parent.name}: '{original}' -> '{original}{SUFFIX}'")


def main() -> None:
    if not SUFFIX:
        return
    for line in (ROOT / "deus-extensions.txt").read_text(encoding="utf-8").splitlines():
        key = line.split("#", 1)[0].strip()
        if key:
            mark(ROOT / "src" / key / "build.gradle.kts")


if __name__ == "__main__":
    main()
