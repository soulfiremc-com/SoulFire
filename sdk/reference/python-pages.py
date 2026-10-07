"""Create one mkdocstrings page for each SDK module."""

from pathlib import Path

import mkdocs_gen_files

source = Path(__file__).resolve().parent.parent / "python" / "src"
nav = mkdocs_gen_files.Nav()

for path in sorted((source / "soulfire").rglob("*.py")):
    relative = path.relative_to(source)
    parts = relative.with_suffix("").parts
    if parts[-1] == "__init__":
        parts = parts[:-1]
        destination = relative.parent / "index.md"
    elif parts[-1].startswith("_"):
        continue
    else:
        destination = relative.with_suffix(".md")

    nav[parts] = destination.as_posix()
    with mkdocs_gen_files.open(Path("api") / destination, "w") as page:
        page.write(f"::: {'.'.join(parts)}\n")
    mkdocs_gen_files.set_edit_path(Path("api") / destination, relative)

with mkdocs_gen_files.open("api/SUMMARY.md", "w") as page:
    page.writelines(nav.build_literate_nav())
