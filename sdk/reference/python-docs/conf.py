"""Build the Python SDK reference from source and protocol stubs."""

import ast
import builtins
import json
from functools import cache
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
autoapi_template_dir = "templates"
autoapi_python_class_content = "class"
napoleon_numpy_docstring = False

html_theme = "furo"
html_title = f"{project} {version}"
html_baseurl = "https://py.soulfiremc.com/"
html_show_sourcelink = False
html_theme_options = {"navigation_with_keys": True}


@cache
def effect_functions():
    """Find Effect-returning decorators without importing SDK modules."""
    names = set()
    for path in (reference.parent / "python/src/soulfire").glob("*.py"):
        if path.name.endswith(("_pb2.py", "_connect.py")):
            continue
        module = "soulfire" if path.stem == "__init__" else f"soulfire.{path.stem}"

        def visit(node, prefix):
            for child in getattr(node, "body", []):
                if isinstance(
                    child, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)
                ):
                    name = f"{prefix}.{child.name}"
                    if isinstance(
                        child, (ast.FunctionDef, ast.AsyncFunctionDef)
                    ) and any(
                        isinstance(decorator, ast.Call)
                        and isinstance(decorator.func, ast.Name)
                        and decorator.func.id == "fn"
                        for decorator in child.decorator_list
                    ):
                        names.add(name)
                    visit(child, name)

        visit(ast.parse(path.read_text()), module)
    return names


def original_name(app, name, obj=None):
    """Resolve reexported classes and members to their source definitions."""
    original = getattr(obj, "obj", {}).get("original_path")
    if original:
        return original
    parent_name = name
    while "." in parent_name:
        parent_name = parent_name.rpartition(".")[0]
        parent = app.env.autoapi_objects.get(parent_name)
        if parent is None:
            continue
        original = parent.obj.get("original_path")
        if original:
            return original + name.removeprefix(parent_name)
        for child in parent.children:
            if child.id == name:
                return child.obj.get("original_path", name)
    return name


def public_return_annotation(annotation, obj):
    """Show the caller's Effect type for @fn generator implementations."""
    original = original_name(obj.app, obj.id, obj)
    if original in effect_functions() and annotation:
        name, bracket, parameters = annotation.partition("[")
        if bracket and name.rsplit(".", 1)[-1] == "EffectGen":
            return name.removesuffix("Gen") + bracket + parameters
    return annotation


def prepare_jinja_env(jinja_env):
    jinja_env.filters["public_return_annotation"] = public_return_annotation


autoapi_prepare_jinja_env = prepare_jinja_env


@cache
def type_parameters():
    """Record lexical type parameters so they do not link to unrelated globals."""
    parameters = {}
    for path in (reference.parent / "python/src/soulfire").glob("*.py"):
        if path.name.endswith(("_pb2.py", "_connect.py")):
            continue
        module = "soulfire" if path.stem == "__init__" else f"soulfire.{path.stem}"

        def visit(node, prefix, inherited):
            for child in getattr(node, "body", []):
                if isinstance(
                    child, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)
                ):
                    name = f"{prefix}.{child.name}"
                    local = inherited | {
                        parameter.name for parameter in child.type_params
                    }
                    parameters[name] = local
                    visit(child, name, local)

        visit(ast.parse(path.read_text()), module, set())
    return parameters


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
    for description in doctree.findall(addnodes.desc):
        for signature in description.children:
            if not isinstance(signature, addnodes.desc_signature):
                continue
            full_name = f"{signature.get('module', '')}.{signature.get('fullname', '')}"
            original = original_name(app, full_name)
            parameters = type_parameters().get(original, set())
            for node in description.findall(addnodes.pending_xref):
                if (
                    node.get("refdomain") == "py"
                    and node.get("reftarget") in parameters
                ):
                    node.replace_self(
                        nodes.inline("", node.astext(), classes=["type-parameter"])
                    )
    for node in doctree.findall(addnodes.pending_xref):
        if node.get("refdomain") == "py":
            if obj is not None and obj.type in {"class", "exception"}:
                original = obj.obj.get("original_path", obj.id)
                module = original.removesuffix(f".{obj.qual_name}")
                node["py:module"] = module
            target = node.get("reftarget", "")
            local = f"{node.get('py:module', '')}.{target}"
            definition = app.env.autoapi_all_objects.get(local)
            if "." not in target and definition is not None:
                node["reftarget"] = definition.obj.get("original_path", local)
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
