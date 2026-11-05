# SDK release follow-up

These items require release authority, external infrastructure, or a transport
decision. They are not substitutes for unfinished local implementation.

## Publish the 0.2 SDK artifacts

Publishing requires npm, PyPI, and JSR release credentials plus an explicit
release decision.

- Run the `Publish SDKs` workflow with the final version.
- Verify package provenance, signatures, installability, and release notes.
- Confirm the same Effect v4, platform runtime, protobuf, ConnectRPC, and
  CPython 3.14 ranges that passed the release candidate checks.

Completion means fresh projects can install the exact release from npm, PyPI,
and JSR without workspace links.

## Finish SoulFireClient high-level SDK adoption

SoulFireClient now consumes the published `@soulfiremc/sdk` protocol exports
instead of maintaining copied protobuf sources. Its current dependency is
0.1.1 because the high-level 0.2 package is not published yet.

After 0.2 is available:

- Upgrade SoulFireClient to the released package.
- Move its first live bot view and event subscription to `BotSession`.
- Use high-level administration and instance APIs where they cover the UI
  operation, while retaining generated services for wire-level screens.
- Run Biome, TypeScript, web, Electron, and packaging checks against the
  published artifact.

Completion means SoulFireClient exercises the same installed package that
third-party applications receive.

## Run the real Minecraft compatibility matrix

The repository has unit, protocol, SDK, package, and service tests, but a
release gate still needs provisioned Minecraft endpoints and test accounts.

- Test the oldest and newest supported Minecraft versions plus every protocol
  boundary where packet or registry behavior changes.
- Exercise reconnects, dimension changes, inventory revisions, pathfinding,
  combat, production tasks, plugin RPCs, and raw protocol permissions from
  both official SDKs.
- Include authenticated and offline-mode fixtures without storing account
  secrets in the repository.

Completion means the same conformance scenarios pass against real Soulfire
processes and Minecraft servers for the supported version matrix.

## Provision the beat-game fixture and scheduled completion gate

The repository includes a packed-package live worker and a manual smoke
workflow. Running the deterministic process smoke and full fixed-seed survival
gate still requires infrastructure and two maintainer-owned choices: the first
Minecraft version and fixture world, plus the fixed seed and server
implementation.

- Provision a disposable offline fixture server, a real Soulfire release
  candidate, test bot accounts, and a scoped `SOULFIRE_SMOKE_TOKEN`.
- Stage resources for collect, craft, portal, item-entity, dimension, and
  combat assertions before promoting the workflow to a required release gate.
- Run the worker with `SOULFIRE_SMOKE_CRASH_AFTER_CHECKPOINTS` so CI kills and
  restarts the separate Node process against the same JSON checkpoint.
- Add scheduled clean-world single-bot and multi-bot completion runs after the
  fixed seed and server implementation are approved.
- Retain Soulfire logs, Minecraft logs, checkpoints, event traces, timings,
  and the failed world as artifacts.
- Establish a repeated-success threshold instead of accepting one successful
  completion.

Completion means the process-level smoke passes from packed npm artifacts and
the scheduled single-bot and multi-bot runs meet the approved reliability and
duration budgets.

## Run fleet scale, soak, and fault-injection tests

This requires dedicated infrastructure with agreed traffic and resource
budgets.

- Measure event fan-out, task scheduling, world queries, backpressure, and
  reconnect storms at representative fleet sizes.
- Run multi-hour soak tests with forced transport loss, server restarts,
  partial bot failures, and slow SDK consumers.
- Publish latency, memory, CPU, dropped-event, and recovery thresholds as
  release gates.

Completion means the release candidate stays within the agreed budgets and
recovers without leaked tasks, subscriptions, or control leases.

## Complete the independent security and plugin-isolation review

The implemented permission, audit, rate-limit, and dangerous raw-protocol
boundaries protect callers. Server plugins still run as trusted code in the
Soulfire process.

- Review authentication, scoped plugin grants, descriptor downloads,
  reflective invocation, MCP exposure, raw packet access, archive handling,
  and local-server installation.
- Decide whether untrusted plugins require a separate process or another
  isolation boundary.
- Threat-model and test the selected isolation design before advertising
  untrusted plugin execution.

Completion means review findings are resolved or explicitly accepted by the
maintainers, and the documented trust model matches deployment behavior.

## Add client-streaming and bidirectional plugin RPC transport

The stable browser-compatible baseline supports unary and server-streaming
plugin calls. Client-streaming and bidirectional calls need a cross-language
transport that works for TypeScript, Python, proxies, permissions, audit, and
reflection.

- Select native gRPC, Connect over WebSocket, or another specified transport.
- Add protocol negotiation and capability flags.
- Extend generated and reflective clients in both SDKs.
- Add cancellation, backpressure, resume, permission, and interoperability
  tests.

Completion means all supported call shapes behave consistently in Node.js,
  browsers, Bun, Deno, async Python, and sync Python.

## Register task payloads with the HTTP/JSON protobuf marshaller

The binary SDK transport can list tasks, but the HTTP/JSON `ListBotTasks`
endpoint currently fails when a task contains a concrete protobuf message in
its `input` or `result` `Any` field. The marshaller reports that it cannot find
the corresponding `type.googleapis.com/soulfire.v1.*` type.

- Register every core task input, progress detail, and result type with the
  HTTP/JSON protobuf type registry.
- Include dynamically registered plugin task types where the transport can
  expose their descriptors safely.
- Add an HTTP/JSON integration test that lists both terminal and nonterminal
  tasks and verifies decoded `Any` payloads.
- Define the fallback representation for an authorized plugin type whose
  descriptor is unavailable.

Completion means HTTP/JSON callers can list and inspect core and plugin tasks
without losing typed payloads or receiving `INVALID_ARGUMENT`.

## Deploy the documentation and parity dashboard

The complete SDK documentation and Mineflayer parity dashboard build in
`soulfiremc.com`. Production deployment requires website release authority.

- Deploy the reviewed website source.
- Verify SDK navigation, OpenAPI pages, search indexing, external links, and
  the parity matrix link in production.
- Add the deployed URL to the SDK release notes.

Completion means the release documentation is publicly reachable from the SDK
package metadata and Soulfire website navigation.
