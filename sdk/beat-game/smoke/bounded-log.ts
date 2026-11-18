import { appendFile, mkdir, rename, rm, stat } from "node:fs/promises";
import path from "node:path";

export interface BoundedLogOptions {
  readonly maximumBytes: number;
  readonly files: number;
}

export class BoundedLog {
  readonly #filename: string;
  readonly #maximumBytes: number;
  readonly #files: number;
  #bytes: number | undefined;
  #pending: Promise<void> = Promise.resolve();

  public constructor(filename: string, options: BoundedLogOptions) {
    if (!Number.isSafeInteger(options.maximumBytes) || options.maximumBytes < 1) {
      throw new RangeError("maximumBytes must be a positive safe integer");
    }
    if (!Number.isSafeInteger(options.files) || options.files < 1) {
      throw new RangeError("files must be a positive safe integer");
    }
    this.#filename = filename;
    this.#maximumBytes = options.maximumBytes;
    this.#files = options.files;
  }

  public append(value: string): Promise<void> {
    const buffer = Buffer.from(value);
    const write = this.#pending.then(() => this.#write(buffer));
    this.#pending = write.catch(() => undefined);
    return write;
  }

  async #write(buffer: Buffer): Promise<void> {
    await mkdir(path.dirname(this.#filename), { recursive: true });
    this.#bytes ??= await fileSize(this.#filename);
    for (let offset = 0; offset < buffer.byteLength; offset += this.#maximumBytes) {
      const chunk = buffer.subarray(
        offset,
        Math.min(offset + this.#maximumBytes, buffer.byteLength),
      );
      if (this.#bytes > 0 && this.#bytes + chunk.byteLength > this.#maximumBytes) {
        await this.#rotate();
      }
      await appendFile(this.#filename, chunk);
      this.#bytes += chunk.byteLength;
    }
  }

  public flush(): Promise<void> {
    return this.#pending;
  }

  async #rotate(): Promise<void> {
    if (this.#files === 1) {
      await rm(this.#filename, { force: true });
      this.#bytes = 0;
      return;
    }
    for (let index = this.#files - 1; index >= 1; index -= 1) {
      const destination = `${this.#filename}.${index}`;
      const source = index === 1 ? this.#filename : `${this.#filename}.${index - 1}`;
      await rm(destination, { force: true });
      await renameIfPresent(source, destination);
    }
    this.#bytes = 0;
  }
}

async function fileSize(filename: string): Promise<number> {
  try {
    return (await stat(filename)).size;
  } catch (cause) {
    if (isNodeError(cause, "ENOENT")) {
      return 0;
    }
    throw cause;
  }
}

async function renameIfPresent(source: string, destination: string): Promise<void> {
  try {
    await rename(source, destination);
  } catch (cause) {
    if (!isNodeError(cause, "ENOENT")) {
      throw cause;
    }
  }
}

function isNodeError(cause: unknown, code: string): cause is NodeJS.ErrnoException {
  return cause instanceof Error && "code" in cause && cause.code === code;
}
