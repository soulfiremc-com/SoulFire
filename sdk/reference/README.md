# SDK API references

[TypeScript reference](https://ts.soulfiremc.com/) and
[Python reference](https://py.soulfiremc.com/) document the SDK source in this repository.
Keep tutorials and recipes in the [website guides](https://soulfiremc.com/docs/sdk).

TypeDoc reads every TypeScript package entry point, including generated protocol modules.
MkDocs with mkdocstrings reads the Python package, public modules, and protocol stubs.
Both sites include search and source code.

## Build

Install Bun and CPython 3.14. From this directory, run:

```bash
bun install --frozen-lockfile
bun run build:typescript
bun run build:python
```

The HTML output is in `dist/typescript` and `dist/python`.
The Python build creates a local `.venv` and installs the documentation tools.
The TypeScript build installs the repository dependencies before it runs TypeDoc.
TypeDoc uses its supported TypeScript version from this directory.
The SDK compiler configuration stays separate.

The most recent build also creates Cloudflare Build Output in `.cloudflare/output`.
Cloudflare serves the HTML, scripts, and styles as static assets.
Neither reference needs Worker application code.
TypeScript rewrites `/` to `/index.html` and preserves its `.html` links.
Python uses native index handling for `/` and module directories.

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
