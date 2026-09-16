#!/usr/bin/env python3
"""Benchmark identical headless world captures on explicitly selected Vulkan drivers."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "mod/build/headless-benchmark"
SCENES = ("stress-wide", "stress-transparency", "stress-entities")


def driver_argument(value):
    try:
        description, paths = value.split("=", 1)
        label, device_type = description.split(",", 1)
        if not re.fullmatch(r"[a-zA-Z0-9_-]+", label):
            raise ValueError("invalid label")
        if device_type not in ("DISCRETE", "INTEGRATED", "VIRTUAL", "CPU", "OTHER"):
            raise ValueError("invalid device type")
        resolved = [str(Path(path).resolve(strict=True)) for path in paths.split(os.pathsep)]
        if not all(Path(path).is_file() for path in resolved):
            raise ValueError("driver manifest is not a file")
        return label, device_type, os.pathsep.join(resolved)
    except (ValueError, OSError) as error:
        raise argparse.ArgumentTypeError(f"Use LABEL,TYPE=/path/to/icd.json: {error}") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", action="append", required=True, type=driver_argument,
                        help="LABEL,TYPE=ICD_PATH; join multiple ICD paths with the platform path separator")
    parser.add_argument("--scene", action="append", choices=SCENES)
    parser.add_argument("--server", default="127.0.0.1:25640")
    parser.add_argument("--historical", type=Path, help="Previous software-renderer benchmark-summary.json")
    args = parser.parse_args()
    if len({label for label, _, _ in args.driver}) != len(args.driver):
        parser.error("Driver labels must be unique")
    historical = {}
    if args.historical:
        historical = {scene["scene"]: scene for scene in json.loads(args.historical.read_text())["scenes"]}
    environment = os.environ.copy()
    for name in ("DISPLAY", "WAYLAND_DISPLAY", "VK_ICD_FILENAMES", "VK_DRIVER_FILES"):
        environment.pop(name, None)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    results = []
    for index, scene in enumerate(args.scene or SCENES):
        # Rotate device order between scenes to reduce a consistent warm-up/order advantage.
        offset = index % len(args.driver)
        drivers = args.driver[offset:] + args.driver[:offset]
        for label, expected_type, icd in drivers:
            output = OUTPUT / label / scene
            output.mkdir(parents=True, exist_ok=True)
            report_path = output / "headless-benchmark.json"
            report_path.unlink(missing_ok=True)
            command = [str(ROOT / "gradlew"), ":mod:runLavapipeTest", "--console=plain",
                       f"-PlavapipeScene={scene}", "-PlavapipeHeadless=true", "-PlavapipeBenchmark=true",
                       f"-PlavapipeExpectedDeviceType={expected_type}", f"-PlavapipeIcd={icd}",
                       f"-PlavapipeOutput=headless-benchmark/{label}/{scene}", f"-PlavapipeServer={args.server}"]
            print(f"{scene}: {label} ({expected_type})", flush=True)
            with (output / "run.log").open("w") as log:
                process = subprocess.run(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
            result = {"scene": scene, "driver": label, "icd": icd, "exitCode": process.returncode}
            if process.returncode == 0 and report_path.is_file():
                result["benchmark"] = json.loads(report_path.read_text())
                median = result["benchmark"]["capture"]["medianMs"]
                print(f"  median {median:.3f} ms", flush=True)
                if scene in historical:
                    baseline = historical[scene]
                    result["historicalSoftware"] = {
                        "source": str(args.historical.resolve()), "method": baseline["method"],
                        "width": baseline["width"], "height": baseline["height"],
                        "software": baseline["software"],
                    }
                    if (baseline["width"], baseline["height"]) != (result["benchmark"]["width"], result["benchmark"]["height"]):
                        raise ValueError("Historical resolution differs from current capture resolution")
                    result["speedupOverHistoricalSoftware"] = baseline["software"]["medianMs"] / median
            else:
                print(f"  FAILED: {output / 'run.log'}", flush=True)
            results.append(result)
            (OUTPUT / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
    return 0 if all("benchmark" in result for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
