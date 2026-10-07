"""Build the Python SDK reference from source and protocol stubs."""

import builtins
import json
from pathlib import Path

from docutils import nodes
from sphinx import addnodes

reference = Path(__file__).resolve().parent.parent
project = "SoulFire Python SDK"
version = json.loads((reference.parent / "typescript/package.json").read_text())[
    "version"
]
release = version

extensions = [
    "autoapi.extension",
    "sphinx.ext.napoleon",
    "sphinx.ext.viewcode",
    "sphinx.ext.intersphinx",
]
intersphinx_mapping = {"python": ("https://docs.python.org/3.14/", None)}
autoapi_dirs = [str(reference.parent / "python/src/soulfire")]
# Protobuf fields and signatures live in the generated .pyi files.
autoapi_file_patterns = ["*.pyi", "*.py"]
autoapi_root = "api"
autoapi_own_page_level = "class"
autoapi_options = [
    "members",
    "undoc-members",
    "show-inheritance",
    "show-module-summary",
    "imported-members",
]
autoapi_member_order = "bysource"
autoapi_python_class_content = "class"
napoleon_numpy_docstring = False

html_theme = "furo"
html_title = f"{project} {version}"
html_baseurl = "https://py.soulfiremc.com/"
html_show_sourcelink = False
html_theme_options = {"navigation_with_keys": True}


def set_class_module(app, doctree):
    """Set module context for class source links and short type names."""
    docname = app.env.docname
    prefix = f"{app.config.autoapi_root}/"
    if not docname.startswith(prefix):
        return
    name = docname.removeprefix(prefix).replace("/", ".")
    obj = app.env.autoapi_objects.get(name)
    if obj is not None and obj.type in {"class", "exception"}:
        module = obj.id.removesuffix(f".{obj.qual_name}")
        for signature in doctree.findall(addnodes.desc_signature):
            signature["module"] = module
            signature["fullname"] = signature["fullname"].removeprefix(f"{module}.")
            signature["class"] = signature["class"].removeprefix(f"{module}.")
    for node in doctree.findall(addnodes.pending_xref):
        if node.get("refdomain") == "py":
            if obj is not None and obj.type in {"class", "exception"}:
                original = obj.obj.get("original_path", obj.id)
                module = original.removesuffix(f".{obj.qual_name}")
                node["py:module"] = module
            target = node.get("reftarget", "")
            if node.get("reftype") == "class" and isinstance(
                getattr(builtins, target, None), type
            ):
                node["reftarget"] = f"builtins.{target}"


def keep_module_class_anchors(app, doctree, docname):
    """Keep module links to classes valid after splitting them into pages."""
    prefix = f"{app.config.autoapi_root}/"
    if not docname.startswith(prefix) or not docname.endswith("/index"):
        return
    module = docname.removeprefix(prefix).removesuffix("/index").replace("/", ".")
    for row in doctree.findall(nodes.row):
        for link in row.findall(nodes.reference):
            symbol = link.get("refuri", "").partition("#")[2]
            obj = app.env.autoapi_objects.get(symbol)
            if (
                obj is not None
                and obj.type in {"class", "exception"}
                and obj.id.removesuffix(f".{obj.qual_name}") == module
            ):
                row["ids"].append(symbol)


def setup(app):
    # Run before viewcode consumes signature metadata.
    app.connect("doctree-read", set_class_module, priority=450)
    app.connect("doctree-resolved", keep_module_class_anchors)
