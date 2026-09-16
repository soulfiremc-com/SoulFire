#!/usr/bin/env python3
"""Opt-in POV and inventory validation in the production image without system Vulkan packages."""

import argparse
import base64
import hashlib
import json
import re
import shutil
import socket
import subprocess
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output(
        list(map(str, args)), text=True, stderr=subprocess.STDOUT
    )


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_for(check, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(1)
    raise TimeoutError(
        "Validation service did not become ready; inspect the saved logs"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument(
        "--server-jar",
        type=Path,
        required=True,
        help="Minecraft server JAR; this test accepts its EULA for an isolated local server",
    )
    parser.add_argument("--output", type=Path, default=ROOT / "build/vulkan-container")
    args = parser.parse_args()
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    context = destination / "context"
    context.mkdir(exist_ok=True)
    shutil.copy2(ROOT / "Dockerfile", context)
    shutil.copy2(ROOT / "start.sh", context)
    shutil.copy2(args.jar, context / "soulfire.jar")
    image = "soulfire-vulkan-validation"
    with (destination / "docker-build.log").open("w") as log:
        subprocess.run(
            [
                "docker",
                "build",
                "--build-arg",
                "JAR_SOURCE=soulfire.jar",
                "-t",
                image,
                str(context),
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
            check=True,
        )
    packages = run(
        "docker",
        "run",
        "--rm",
        "--entrypoint",
        "dpkg-query",
        image,
        "-W",
        "-f",
        "${Package} ${Status}\n",
    )
    graphics_packages = [
        line
        for line in packages.splitlines()
        if re.search(r"^(libvulkan|mesa-vulkan|libllvm)", line)
    ]
    if graphics_packages:
        raise RuntimeError(f"System Vulkan dependencies found: {graphics_packages}")
    (destination / "installed-packages.txt").write_text(packages)
    server_directory = destination / "server"
    server_directory.mkdir(exist_ok=True)
    mc_port, api_port = free_port(), free_port()
    (server_directory / "eula.txt").write_text("eula=true\n")
    (server_directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={mc_port}\nonline-mode=false\nenforce-secure-profile=false\n"
        "level-type=minecraft:flat\ngamemode=creative\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\n"
        "max-players=4\npause-when-empty-seconds=-1\n"
    )
    container = None
    attachment = None
    server_log = (destination / "minecraft.log").open("w")
    server = subprocess.Popen(
        ["java", "-Xmx768M", "-jar", str(args.server_jar.resolve()), "nogui"],
        cwd=server_directory,
        stdin=subprocess.PIPE,
        stdout=server_log,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        wait_for(lambda: "Done (" in (destination / "minecraft.log").read_text())
        container = run(
            "docker",
            "run",
            "-di",
            "--network",
            "host",
            "-e",
            f"SF_JVM_FLAGS=-Dsf.grpc.host=127.0.0.1 -Dsf.grpc.port={api_port}",
            image,
        ).strip()

        def token_ready():
            log = run("docker", "logs", container)
            tokens = re.findall(
                r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", log
            )
            return tokens[-1] if tokens else None

        wait_for(lambda: "Finished loading!" in run("docker", "logs", container), 360)
        attachment = subprocess.Popen(
            ["docker", "attach", "--sig-proxy=false", container],
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        attachment.stdin.write("generate-token api\n")
        attachment.stdin.flush()
        token = wait_for(token_ready, 30)

        def api(method, path, data=None):
            request = urllib.request.Request(
                f"http://127.0.0.1:{api_port}/v1{path}",
                data=json.dumps(data).encode() if data is not None else None,
                method=method,
                headers={
                    "Authorization": "Bearer " + token,
                    "Content-Type": "application/json",
                },
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)

        instance = api(
            "POST", "/instances", {"friendlyName": "Bundled Vulkan validation"}
        )["id"]
        path = "/instances/" + instance
        api(
            "PUT",
            path + "/config/namespaces/bot/entries/address",
            {"value": f"127.0.0.1:{mc_port}"},
        )
        bots = []
        for name in ["NativeProbeA", "NativeProbeB"]:
            bot = str(
                uuid.UUID(
                    bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(),
                    version=3,
                )
            )
            bots.append(bot)
            api(
                "POST",
                path + "/accounts",
                {
                    "account": {
                        "type": "OFFLINE",
                        "profileId": bot,
                        "lastKnownName": name,
                        "offlineJavaData": {},
                    }
                },
            )
        api(
            "POST",
            path + "/bots:setDesiredState",
            {"botIds": bots, "desiredState": "BOT_DESIRED_STATE_RUNNING"},
        )
        wait_for(
            lambda: all(
                name + " joined the game" in (destination / "minecraft.log").read_text()
                for name in ["NativeProbeA", "NativeProbeB"]
            )
        )
        for index, name in enumerate(["NativeProbeA", "NativeProbeB"]):
            for item in [
                "diamond_sword",
                "chest",
                "oak_log",
                "shield",
                "enchanted_book",
            ]:
                server.stdin.write(f"give {name} minecraft:{item}\n")
            server.stdin.write(f"tp {name} {index * 4} -60 0 0 10\n")
        for x, block in enumerate(["oak_log", "sea_lantern", "glass", "chest"]):
            server.stdin.write(f"setblock {x} -60 4 minecraft:{block}\n")
        server.stdin.flush()

        def capture(index):
            response = api(
                "GET",
                path
                + "/bots/"
                + bots[index % 2]
                + "/pov:render?width=320&height=180&include_hud=true&include_hands=true",
            )
            png = base64.b64decode(response["imageBase64"])
            frame = Image.open(BytesIO(png))
            if (
                response["imageMimeType"] != "image/png"
                or frame.size != (320, 180)
                or len(frame.getcolors(320 * 180) or []) <= 32
            ):
                raise AssertionError("Invalid or empty POV render")
            (destination / f"pov-{index}.png").write_bytes(png)

        # Allow the first simulation ticks and chunk uploads to finish before requesting captures.
        time.sleep(3)
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(capture, range(12)))
        icons = 0
        for bot in bots:
            inventory = api("GET", path + "/bots/" + bot + "/inventory")
            slots = [slot for slot in inventory["slots"] if slot.get("iconBase64")]
            if len(slots) < 5:
                raise AssertionError("Expected icons for all five supplied items")
            for slot in slots:
                png = base64.b64decode(slot["iconBase64"])
                icon = Image.open(BytesIO(png)).convert("RGBA")
                if (
                    slot["iconMimeType"] != "image/png"
                    or icon.size != (32, 32)
                    or icon.getextrema()[3] != (0, 255)
                ):
                    raise AssertionError(
                        "Invalid inventory icon or missing transparency"
                    )
                (destination / f"icon-{icons}.png").write_bytes(png)
                icons += 1
        log = run("docker", "logs", container)
        if "Loaded bundled headless Vulkan runtime" not in log or "llvmpipe" not in log:
            raise AssertionError("Bundled CPU Vulkan was not selected")
        report = {
            "systemVulkanPackages": graphics_packages,
            "display": None,
            "gpuPassthrough": False,
            "concurrentPovCaptures": 12,
            "inventoryIcons": icons,
            "bots": len(bots),
        }
        (destination / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
    finally:
        if container:
            (destination / "soulfire.log").write_text(run("docker", "logs", container))
            run("docker", "rm", "-f", container)
        if attachment:
            attachment.wait(timeout=15)
        if server.poll() is None:
            server.stdin.write("stop\n")
            server.stdin.flush()
            try:
                server.wait(timeout=45)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        server_log.close()


if __name__ == "__main__":
    main()
