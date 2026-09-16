#!/usr/bin/env python3
"""Run opt-in Lavapipe comparisons and retain results even when individual scenes fail."""

import argparse
import csv
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "mod/src/lavapipeTest/resources/lavapipe-scenes.csv"
OUTPUT = ROOT / "mod/build/lavapipe-test/output"


def main():
    with CATALOG.open(newline="") as catalog:
        scenes = list(csv.DictReader(catalog))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scene", action="append", choices=[s["scene"] for s in scenes])
    parser.add_argument("--family", action="append", choices=sorted({s["family"] for s in scenes}))
    parser.add_argument("--list", action="store_true", help="List selected scenes without starting Minecraft")
    parser.add_argument("--server", default="127.0.0.1:25640", help="Local validation server supplying the client registries")
    parser.add_argument("--icd", help="Path to the Lavapipe Vulkan ICD")
    args = parser.parse_args()
    selected = [s for s in scenes if (not args.scene or s["scene"] in args.scene)
                and (not args.family or s["family"] in args.family)]
    if not selected:
        parser.error("No scenes match the selected families and names")
    if args.list:
        for scene in selected:
            print(f'{scene["scene"]:32} {scene["dimension"]:12} {scene["biome"]:18} '
                  f'tick={scene["animationTick"]:>3} partial={scene["partialTick"]}')
        return 0

    OUTPUT.mkdir(parents=True, exist_ok=True)
    results = []
    for index, scene in enumerate(selected, 1):
        name = scene["scene"]
        print(f"[{index}/{len(selected)}] {name}", flush=True)
        command = [str(ROOT / "gradlew"), ":mod:runLavapipeTest", f"-PlavapipeScene={name}",
                   f"-PlavapipeServer={args.server}", "--console=plain"]
        if args.icd:
            command.append(f"-PlavapipeIcd={args.icd}")
        log_path = OUTPUT / f"{name}.log"
        metrics_path = OUTPUT / name / "metrics.json"
        # A build failure before Gradle's task action must not reuse an earlier result.
        metrics_path.unlink(missing_ok=True)
        started = time.monotonic()
        with log_path.open("w") as log:
            process = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        result = {"scene": name, "exitCode": process.returncode,
                  "seconds": round(time.monotonic() - started, 2), "log": str(log_path)}
        if metrics_path.exists():
            result["metrics"] = json.loads(metrics_path.read_text())
            changed = result["metrics"]["changedPixels"]
            result["status"] = "exact" if changed == 0 and process.returncode == 0 else "different"
            print(f"  {result['status']}: {changed} changed pixels, "
                  f"max channel error {result['metrics']['maxChannelError']}", flush=True)
        else:
            result["status"] = "failed"
            print(f"  render failed; see {log_path}", flush=True)
        results.append(result)
        (OUTPUT / "suite.json").write_text(json.dumps(results, indent=2) + "\n")
    exact = sum(result["status"] == "exact" for result in results)
    print(f"{exact}/{len(results)} exact comparisons. Summary: {OUTPUT / 'suite.json'}")
    return 0 if exact == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
