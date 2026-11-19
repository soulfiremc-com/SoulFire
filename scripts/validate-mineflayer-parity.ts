import * as NodeFileSystem from "@effect/platform-node/NodeFileSystem";
import * as NodePath from "@effect/platform-node/NodePath";
import { Effect, FileSystem, Path, Schema } from "effect";
import { Digest } from "effect/crypto";
import * as FetchHttpClient from "effect/http/FetchHttpClient";
import * as HttpClient from "effect/http/HttpClient";

const ParityMatrix = Schema.fromJsonString(
  Schema.Struct({
    source: Schema.Struct({
      headingCount: Schema.Int,
      headingSha256: Schema.String,
    }),
    rules: Schema.Array(
      Schema.Struct({
        status: Schema.String,
        exact: Schema.optional(Schema.Array(Schema.String)),
        regex: Schema.optional(Schema.String),
      }),
    ),
  }),
);

const matrixUrl = new URL("../docs/mineflayer-parity.json", import.meta.url);
const { matrix, source } = await Effect.runPromise(
  Effect.gen(function* () {
    const fs = yield* FileSystem.FileSystem;
    const path = yield* Path.Path;
    const matrixPath = yield* path.fromFileUrl(matrixUrl);
    const matrixText = yield* fs.readFileString(matrixPath);
    const decodedMatrix = yield* Schema.decodeEffect(ParityMatrix)(matrixText);
    const response = yield* HttpClient.get(
      "https://raw.githubusercontent.com/PrismarineJS/mineflayer/master/docs/api.md",
    );

    if (response.status < 200 || response.status >= 300) {
      throw new Error(`Failed to fetch Mineflayer API reference: ${response.status}`);
    }

    const sourceText = yield* response.text;
    return { matrix: decodedMatrix, source: sourceText };
  }).pipe(Effect.provide([NodeFileSystem.layer, NodePath.layer, FetchHttpClient.layer])),
);

const quote = Schema.encodeSync(Schema.fromJsonString(Schema.String));
const headings = source
  .split(/\r?\n/u)
  .filter((line) => line.startsWith("#### "))
  .map((line) => line.slice(5));
const canonicalHeadings = `${headings.join("\n")}\n`;
const digest = await Effect.runPromise(
  Effect.gen(function* () {
    const service = yield* Digest.Digest;
    return yield* service.digestHex(Digest.sha256, new TextEncoder().encode(canonicalHeadings));
  }).pipe(Effect.provide(Digest.layerWebCrypto)),
);

if (headings.length !== matrix.source.headingCount) {
  throw new Error(
    `Mineflayer heading count changed from ${matrix.source.headingCount} to ${headings.length}`,
  );
}
if (digest !== matrix.source.headingSha256) {
  throw new Error(
    `Mineflayer API headings changed: expected ${matrix.source.headingSha256}, received ${digest}`,
  );
}

for (const heading of headings) {
  const matches = matrix.rules.filter((rule) => {
    if (rule.exact?.includes(heading)) {
      return true;
    }
    return rule.regex !== undefined && new RegExp(rule.regex, "u").test(heading);
  });
  const [firstMatch] = matches;
  if (firstMatch === undefined) {
    throw new Error(`No parity rule covers ${quote(heading)}`);
  }
  if (firstMatch.status === "native" && firstMatch.regex === ".*") {
    continue;
  }
  if (!matches.some((rule) => rule.status === firstMatch.status)) {
    throw new Error(`Invalid rule resolution for ${quote(heading)}`);
  }
}

process.stdout.write(
  `Validated ${headings.length} Mineflayer API headings against the SoulFire parity matrix.\n`,
);
