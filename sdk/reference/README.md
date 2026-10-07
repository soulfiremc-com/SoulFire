# SDK API references

[TypeScript reference](https://ts.soulfiremc.com/) and
[Python reference](https://py.soulfiremc.com/) document the SDK source in this repository.
Keep tutorials and recipes in the [website guides](https://soulfiremc.com/docs/sdk).

TypeDoc reads every TypeScript package entry point, including generated protocol modules.
Sphinx with AutoAPI reads the Python package, public modules, and protocol stubs.
The Python reference uses Furo and gives each class its own page.
Both sites include an API overview, lifecycle and task explanations, search, and source code.
The concept pages include examples from SDK source files instead of copied workflows.

## Build

Install Bun and CPython 3.14. From this directory, run:

```bash
bun install --frozen-lockfile
bun run build:typescript
bun run build:python
```

The HTML output is in `dist/typescript` and `dist/python`.
The Python build creates a local `.venv` and installs the documentation tools and SDK dependencies.
The TypeScript build installs the repository dependencies before it runs TypeDoc.
TypeDoc uses its supported TypeScript version from this directory.
The SDK compiler configuration stays separate.

The most recent build also creates Cloudflare Build Output in `.cloudflare/output`.
Cloudflare serves the HTML, scripts, and styles as static assets.
Neither reference needs Worker application code.
TypeScript rewrites `/` to `/index.html` and preserves its `.html` links.
Python uses native index handling for `/` and module directories.
AutoAPI prefers `.pyi` files so the reference includes generated protobuf fields and their types.
Module pages keep class anchors so existing links still reach the class summaries.

## Publish

Sign in with `bunx cf login`, then run:

```bash
bun run deploy:typescript
bun run deploy:python
```

`cloudflare.config.ts` defines both sites and their custom domains.
The default configuration publishes TypeScript. The `python` mode publishes Python.
Each deploy command builds its site before it uploads the output.

Cloudflare Workers Builds watches `main` for changes to SDK source and reference tooling.
The build triggers use `sdk/reference` as the root directory:

| Site       | Build command              | Deploy command                            |
| ---------- | -------------------------- | ----------------------------------------- |
| TypeScript | `bun run build:typescript` | `bunx cf deploy --prebuilt`               |
| Python     | `bun run build:python`     | `bunx cf deploy --prebuilt --mode python` |

Both triggers use `BUN_VERSION=1.4.2`. Python also uses `PYTHON_VERSION=3.14`.
These triggers publish documentation. They add no GitHub Actions validation jobs.

## Update

Change comments, docstrings, signatures, and defaults in the SDK source.
The generators show the package version from that checkout.
Before publishing a release, review its public classes, runtime entry points, and protocol modules.
The Python source uses `EffectGen` in decorated generator implementations.
Methods decorated with `@fn` return lazy `Effect` operations when called.

## Document a public operation

Keep the API contract in TSDoc comments or Python docstrings beside the implementation.
Each operation needs an outcome, parameter meanings and units, result semantics,
error conditions, and ownership rules. Explain when it runs, what requires a scope,
and what happens after cancellation. Link related operations and complete recipes.

For task APIs, distinguish acceptance, progress observation, and successful completion.
Document request timeouts separately from readiness waits and server task deadlines.
Keep language-specific contracts accurate: Python uses `SoulFireTaskError`, while
TypeScript reports `SoulFireTaskFailed` through its Effect error channel.

The concept pages include `sdk/typescript/examples/reference-lifecycle.ts` and
`sdk/python/examples/reference_lifecycle.py` directly. TypeScript's existing typecheck
covers its examples. For Python examples, run these local checks from `sdk/python`:

```bash
.venv/bin/ruff check examples/reference_lifecycle.py
.venv/bin/pyright --pythonpath .venv/bin/python examples/reference_lifecycle.py
```

Build both references and inspect the overview, a class page, its source links,
and rendered examples before publishing. Do not edit generated HTML or protocol bindings.
Put protocol documentation in `.proto` files and regenerate bindings with `bun run sdk:generate`.
The Python renderer converts `@fn` implementation return annotations from `EffectGen`
to the caller's `Effect` signature through AutoAPI's method and function templates.
Ordinary generator functions retain their declared return types.
