import { writeFileSync } from "node:fs";

const mode = process.argv[2];
process.stdout.write(`spawned:${process.pid}\n`);
if (mode === "fail") {
  process.exit(23);
}
if (mode === "ready") {
  writeFileSync("secret-key.bin", Buffer.alloc(32, 1));
  process.stdout.write("Finished loading!\n");
}
setInterval(() => {}, 1000);
