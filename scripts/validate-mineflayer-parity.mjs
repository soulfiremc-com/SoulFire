import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

const matrixUrl = new URL("../docs/mineflayer-parity.json", import.meta.url);
const matrix = JSON.parse(await readFile(matrixUrl, "utf8"));
const sourceResponse = await fetch(
  "https://raw.githubusercontent.com/PrismarineJS/mineflayer/master/docs/api.md",
);

if (!sourceResponse.ok) {
  throw new Error(`Failed to fetch Mineflayer API reference: ${sourceResponse.status}`);
}

const headings = (await sourceResponse.text())
  .split(/\r?\n/u)
  .filter((line) => line.startsWith("#### "))
  .map((line) => line.slice(5));
const canonicalHeadings = `${headings.join("\n")}\n`;
const digest = createHash("sha256").update(canonicalHeadings).digest("hex");

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
  if (matches.length === 0) {
    throw new Error(`No parity rule covers ${JSON.stringify(heading)}`);
  }

  const firstMatch = matches[0];
  if (firstMatch.status === "native" && firstMatch.regex === ".*") {
    continue;
  }
  if (matches.findIndex((rule) => rule.status === firstMatch.status) < 0) {
    throw new Error(`Invalid rule resolution for ${JSON.stringify(heading)}`);
  }
}

console.log(
  `Validated ${headings.length} Mineflayer API headings against the Soulfire parity matrix.`,
);
