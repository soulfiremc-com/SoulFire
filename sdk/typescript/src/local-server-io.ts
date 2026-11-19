import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, rename, rm, stat } from "node:fs/promises";
import path from "node:path";
import net from "node:net";

import AdmZip from "adm-zip";
import * as tar from "tar";

const RELEASES_API = "https://api.github.com/repos/soulfiremc-com/SoulFire/releases";

interface GitHubAsset {
  browser_download_url?: string;
  digest?: string;
  name?: string;
}

interface GitHubRelease {
  assets?: GitHubAsset[];
  tag_name?: string;
}

export async function requireLocalFile(value: string, description: string): Promise<string> {
  const resolved = path.resolve(value);
  let metadata;
  try {
    metadata = await stat(resolved);
  } catch (cause) {
    throw new Error(`${description} does not exist: ${resolved}`, { cause });
  }
  if (!metadata.isFile()) {
    throw new Error(`${description} is not a file: ${resolved}`);
  }
  return resolved;
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function parseGitHubRelease(value: unknown): GitHubRelease {
  if (!isRecord(value)) {
    return {};
  }
  const tagName = optionalString(value["tag_name"]);
  return {
    ...(tagName === undefined ? {} : { tag_name: tagName }),
    ...(Array.isArray(value["assets"])
      ? { assets: value["assets"].filter(isRecord).map((asset) => parseGitHubAsset(asset)) }
      : {}),
  };
}

function parseGitHubAsset(asset: Readonly<Record<string, unknown>>): GitHubAsset {
  const browserDownloadUrl = optionalString(asset["browser_download_url"]);
  const digest = optionalString(asset["digest"]);
  const name = optionalString(asset["name"]);
  return {
    ...(browserDownloadUrl === undefined ? {} : { browser_download_url: browserDownloadUrl }),
    ...(digest === undefined ? {} : { digest }),
    ...(name === undefined ? {} : { name }),
  };
}

export async function resolveRelease(
  version: string | undefined,
  fetchImplementation: typeof globalThis.fetch,
): Promise<Required<Pick<GitHubRelease, "assets" | "tag_name">>> {
  const requestedVersion = version?.trim();
  if (version !== undefined && !requestedVersion) {
    throw new TypeError("SoulFire version must not be empty");
  }

  const endpoint =
    requestedVersion === undefined
      ? `${RELEASES_API}/latest`
      : `${RELEASES_API}/tags/${encodeURIComponent(requestedVersion)}`;
  const response = await fetchImplementation(endpoint, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "@soulfiremc/sdk",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch SoulFire release metadata (${response.status})`);
  }

  const release = parseGitHubRelease(await response.json());
  if (!release.tag_name || !Array.isArray(release.assets)) {
    throw new Error("SoulFire release metadata was incomplete");
  }
  return {
    assets: release.assets,
    tag_name: release.tag_name,
  };
}

export function resolveDedicatedAsset(
  release: Required<Pick<GitHubRelease, "assets" | "tag_name">>,
  requestedVersion: string | undefined,
): Required<Pick<GitHubAsset, "browser_download_url" | "digest" | "name">> {
  const expectedName = `SoulFireDedicated-${requestedVersion?.trim() ?? release.tag_name}.jar`;
  const asset =
    release.assets.find((candidate) => candidate.name === expectedName) ??
    release.assets.find((candidate) => /^SoulFireDedicated-.+\.jar$/u.test(candidate.name ?? ""));
  if (!asset?.name || !asset.browser_download_url || !asset.digest) {
    throw new Error(`SoulFire release ${release.tag_name} has no verified dedicated server JAR`);
  }
  return {
    browser_download_url: asset.browser_download_url,
    digest: asset.digest,
    name: asset.name,
  };
}

export async function ensureJvm(
  jvmDirectory: string,
  fetchImplementation: typeof globalThis.fetch,
): Promise<string> {
  const javaPath = path.join(
    getJavaHome(jvmDirectory),
    "bin",
    process.platform === "win32" ? "java.exe" : "java",
  );
  if (await exists(javaPath)) {
    return javaPath;
  }

  const os = detectOs();
  const architecture = detectArchitecture();
  const metadataUrl =
    "https://api.adoptium.net/v3/assets/latest/25/hotspot" +
    `?architecture=${architecture}&image_type=jre&os=${os}&vendor=eclipse`;
  const response = await fetchImplementation(metadataUrl);
  if (!response.ok) {
    throw new Error(`Failed to fetch JVM metadata (${response.status})`);
  }

  const releases: unknown = await response.json();
  const latest = Array.isArray(releases) && isRecord(releases[0]) ? releases[0] : undefined;
  const binary = isRecord(latest?.["binary"]) ? latest["binary"] : undefined;
  const packageMetadata = isRecord(binary?.["package"]) ? binary["package"] : undefined;
  const checksum = optionalString(packageMetadata?.["checksum"]);
  const downloadUrl = optionalString(packageMetadata?.["link"]);
  const releaseName = optionalString(latest?.["release_name"]);
  if (!checksum || !downloadUrl || !releaseName) {
    throw new Error("JVM metadata was incomplete");
  }

  const temporaryRoot = path.join(path.dirname(jvmDirectory), `.jvm-25-${randomUUID()}`);
  const archivePath = `${temporaryRoot}.download`;
  await mkdir(temporaryRoot, { recursive: true });

  try {
    await downloadFile(downloadUrl, archivePath, checksum, fetchImplementation);
    if (downloadUrl.endsWith(".zip")) {
      new AdmZip(archivePath).extractAllTo(temporaryRoot, true);
    } else if (downloadUrl.endsWith(".tar.gz")) {
      await tar.x({ cwd: temporaryRoot, file: archivePath });
    } else {
      throw new Error("Unsupported JVM archive type");
    }

    const extractedJvm = path.join(temporaryRoot, `${releaseName}-jre`);
    const extractedJava = path.join(
      getJavaHome(extractedJvm),
      "bin",
      process.platform === "win32" ? "java.exe" : "java",
    );
    if (!(await exists(extractedJava))) {
      throw new Error("Extracted JVM is missing the Java executable");
    }

    await rm(jvmDirectory, { force: true, recursive: true });
    await rename(extractedJvm, jvmDirectory);
  } finally {
    await rm(archivePath, { force: true });
    await rm(temporaryRoot, { force: true, recursive: true });
  }

  return javaPath;
}

export async function ensureDownload(
  url: string,
  destination: string,
  checksum: string,
  fetchImplementation: typeof globalThis.fetch,
): Promise<void> {
  if ((await exists(destination)) && (await sha256File(destination)) === checksum) {
    return;
  }

  await mkdir(path.dirname(destination), { recursive: true });
  const temporaryPath = `${destination}.${randomUUID()}.download`;
  try {
    await downloadFile(url, temporaryPath, checksum, fetchImplementation);
    await rm(destination, { force: true });
    await rename(temporaryPath, destination);
  } finally {
    await rm(temporaryPath, { force: true });
  }
}

async function downloadFile(
  url: string,
  destination: string,
  checksum: string,
  fetchImplementation: typeof globalThis.fetch,
): Promise<void> {
  const response = await fetchImplementation(url);
  if (!response.ok || response.body === null) {
    throw new Error(`Download failed for ${url} (${response.status})`);
  }

  const file = await import("node:fs/promises").then(({ open }) => open(destination, "wx"));
  const hash = createHash("sha256");
  try {
    const reader = response.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      await file.write(value);
      hash.update(value);
    }
    await file.sync();
  } finally {
    await file.close();
  }

  const actualChecksum = hash.digest("hex");
  if (actualChecksum !== checksum.toLowerCase()) {
    throw new Error("Downloaded file checksum verification failed");
  }
}

export function requireSha256Digest(digest: string, label: string): string {
  const match = /^sha256:([a-fA-F0-9]{64})$/u.exec(digest);
  if (!match?.[1]) {
    throw new Error(`${label} did not include a SHA-256 digest`);
  }
  return match[1].toLowerCase();
}

async function sha256File(filePath: string): Promise<string> {
  const hash = createHash("sha256");
  const stream = createReadStream(filePath);
  for await (const chunk of stream) {
    const data: unknown = chunk;
    if (!Buffer.isBuffer(data)) {
      throw new TypeError(`Reading ${filePath} returned a non-binary chunk`);
    }
    hash.update(data);
  }
  return hash.digest("hex");
}

export async function findAvailablePort(): Promise<number> {
  const server = net.createServer();
  return new Promise<number>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("Failed to select an available port"));
        return;
      }
      server.close((error) => {
        if (error) {
          reject(error);
        } else {
          resolve(address.port);
        }
      });
    });
  });
}

async function exists(target: string): Promise<boolean> {
  try {
    await stat(target);
    return true;
  } catch {
    return false;
  }
}

function detectArchitecture(): string {
  switch (process.arch) {
    case "arm": {
      return "arm";
    }
    case "arm64": {
      return "aarch64";
    }
    case "ia32": {
      return "x32";
    }
    case "ppc64": {
      return "ppc64";
    }
    case "riscv64": {
      return "riscv64";
    }
    case "s390x": {
      return "s390x";
    }
    case "x64": {
      return "x64";
    }
    case "loong64":
    case "mips":
    case "mipsel":
    default: {
      throw new Error(`Unsupported architecture: ${process.arch}`);
    }
  }
}

function detectOs(): string {
  switch (process.platform) {
    case "darwin": {
      return "mac";
    }
    case "linux": {
      return "linux";
    }
    case "win32": {
      return "windows";
    }
    case "aix":
    case "android":
    case "cygwin":
    case "freebsd":
    case "haiku":
    case "netbsd":
    case "openbsd":
    case "sunos":
    default: {
      throw new Error(`Unsupported operating system: ${process.platform}`);
    }
  }
}

export function getJavaHome(jvmDirectory: string): string {
  return process.platform === "darwin" ? path.join(jvmDirectory, "Contents", "Home") : jvmDirectory;
}
