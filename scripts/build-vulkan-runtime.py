#!/usr/bin/env python3
"""Build and package the headless Vulkan loader and lavapipe for the current host."""

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
from pathlib import Path

try:
    from backports import tarfile
except ImportError:
    import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "glslang": (
        "https://codeload.github.com/KhronosGroup/glslang/tar.gz/refs/tags/14.3.0",
        "be6339048e20280938d9cb399fcdd06e04f8654d43e170e8cce5a56c9a754284",
    ),
    "mesa": (
        "https://archive.mesa3d.org/mesa-26.1.8.tar.xz",
        "b320f65874fd9653ac6c0bd1616605387344e1247411a50c797b5f3fb9dc0b55",
    ),
    "loader": (
        "https://codeload.github.com/KhronosGroup/Vulkan-Loader/tar.gz/refs/tags/v1.4.341",
        "9a8c6f1aace4a03641429a09bc823073f2375abf2d62ac5a4d38c46e732b2d85",
    ),
    "headers": (
        "https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/v1.4.341",
        "8876aad926b2e72bdefddc34885472d5332e2d20aa3ed57313466f0bc982cf3f",
    ),
}


def run(*args, **kwargs):
    print("+", *map(str, args), flush=True)
    subprocess.run(list(map(str, args)), check=True, **kwargs)


def output(*args):
    return subprocess.check_output(list(map(str, args)), text=True)


def source(name, work):
    url, digest = SOURCES[name]
    archive = work / f"{name}.tar"
    if not archive.exists():
        urllib.request.urlretrieve(url, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        raise RuntimeError(f"Checksum mismatch: {archive}")
    directory = work / name
    if not directory.exists() or not any(directory.iterdir()):
        directory.mkdir(exist_ok=True)
        with tarfile.open(archive) as tar:
            tar.extractall(directory, filter="data")
    return next(directory.iterdir())


def linux_closure(roots, stage):
    # Only glibc and its ELF interpreter belong to the base OS. Bundle C++/LLVM dependencies.
    system = re.compile(
        r"^(lib(c|m|dl|pthread|rt|resolv|util)\.so\.|ld-linux|linux-vdso)"
    )
    pending = list(roots)
    seen = set()
    while pending:
        library = pending.pop()
        if library.name in seen:
            continue
        seen.add(library.name)
        target = stage / library.name
        shutil.copy2(library.resolve(), target)
        dependencies = output("ldd", library)
        if "not found" in dependencies:
            raise RuntimeError(dependencies)
        for line in dependencies.splitlines():
            match = re.match(r"\s*(\S+) => (/\S+)", line)
            if match and not system.match(match[1]):
                pending.append(Path(match[2]))
        run("patchelf", "--set-rpath", "$ORIGIN", target)
        run("strip", "--strip-unneeded", target)
    # Private SONAMEs keep our baseline C++/LLVM libraries out of the dependency
    # resolution of newer system GPU drivers loaded into the same JVM.
    root_names = {path.name for path in roots}
    names = {
        path.name: "libsf_" + path.name
        for path in stage.glob("*.so*")
        if path.name not in root_names
    }
    for library in list(stage.glob("*.so*")):
        for needed in output("patchelf", "--print-needed", library).splitlines():
            if needed in names:
                run("patchelf", "--replace-needed", needed, names[needed], library)
        if library.name in names:
            name = names[library.name]
            run("patchelf", "--set-soname", name, library)
            library.rename(stage / name)
    for library in stage.glob("*.so*"):
        for needed in output("patchelf", "--print-needed", library).splitlines():
            if not system.match(needed) and not (stage / needed).is_file():
                raise RuntimeError(f"Unbundled dependency: {library.name}: {needed}")
            if re.search(r"lib(X11|xcb|wayland|drm|vulkan)\b", needed):
                raise RuntimeError(f"Unexpected graphics dependency: {needed}")


def macos_closure(roots, stage):
    pending = list(roots)
    seen = set()
    while pending:
        library = pending.pop()
        if library.name in seen:
            continue
        seen.add(library.name)
        target = stage / library.name
        shutil.copy2(library.resolve(), target)
        target.chmod(0o755)
        rpaths = re.findall(
            r"cmd LC_RPATH\n\s*cmdsize \d+\n\s*path (.+?) \(offset",
            output("otool", "-l", library),
        )
        for line in output("otool", "-L", library).splitlines()[1:]:
            dependency = line.strip().split(" (", 1)[0]
            if dependency.startswith(
                ("/usr/lib/", "/System/Library/")
            ) or dependency == str(library):
                continue
            resolved = dependency.replace("@loader_path", str(library.parent))
            if resolved.startswith("@rpath/"):
                candidates = [
                    Path(r.replace("@loader_path", str(library.parent))) / resolved[7:]
                    for r in rpaths
                ]
                resolved = str(
                    next(
                        (p for p in candidates if p.is_file()),
                        Path("/missing") / resolved[7:],
                    )
                )
            dep = Path(resolved)
            if dep.name == library.name:
                continue
            if not dep.is_file():
                raise RuntimeError(f"Cannot resolve {dependency} required by {library}")
            pending.append(dep)
            run(
                "install_name_tool",
                "-change",
                dependency,
                f"@loader_path/{dep.name}",
                target,
            )
        run("install_name_tool", "-id", f"@loader_path/{target.name}", target)
    for library in stage.glob("*.dylib"):
        run("codesign", "--force", "--sign", "-", library)
        for line in output("otool", "-L", library).splitlines()[1:]:
            dependency = line.strip().split(" (", 1)[0]
            if not dependency.startswith(
                ("/usr/lib/", "/System/Library/", "@loader_path/")
            ):
                raise RuntimeError(f"Unbundled dependency: {dependency}")


def windows_closure(roots, stage):
    llvm_prefix = Path(output("llvm-config", "--prefix").strip())
    search = [
        llvm_prefix / "bin",
        *(Path(p) for p in os.environ["PATH"].split(os.pathsep)),
    ]
    pending = list(roots)
    seen = set()
    while pending:
        library = pending.pop()
        if library.name.lower() in seen:
            continue
        seen.add(library.name.lower())
        shutil.copy2(library, stage / library.name)
        for name in re.findall(
            r"DLL Name: (\S+)",
            output(shutil.which("llvm-objdump") or "objdump", "-p", library),
        ):
            dependency = next((p / name for p in search if (p / name).is_file()), None)
            # Windows API sets and OS DLLs are provided by Windows itself.
            if name.lower().startswith(("api-ms-", "ext-ms-")):
                continue
            windows = Path(os.environ["SystemRoot"])
            if dependency is not None and not dependency.is_relative_to(windows):
                pending.append(dependency)
            elif not (windows / "System32" / name).is_file():
                raise RuntimeError(f"Unresolved DLL: {library}: {name}")


def copy_licenses(stage, system, mesa, loader):
    licenses = stage / "licenses"
    licenses.mkdir()
    shutil.copy2(mesa / "docs/license.rst", licenses / "mesa.txt")
    shutil.copy2(loader / "LICENSE.txt", licenses / "vulkan-loader.txt")
    if system == "linux":
        # Include notices for LLVM and every packaged distro dependency.
        for package in Path("/usr/share/doc").iterdir():
            copyright_file = package / "copyright"
            if copyright_file.is_file():
                shutil.copy2(copyright_file, licenses / f"{package.name}.txt")
        (stage / "build-packages.txt").write_text(output("dpkg-query", "-W"))
    elif system == "windows":
        prefix = Path(output("llvm-config", "--prefix").strip())
        shutil.copytree(
            prefix / "share/licenses", licenses / "dependencies", dirs_exist_ok=True
        )
    else:
        cellar = Path(output("brew", "--cellar").strip())
        for formula in cellar.iterdir():
            for version in formula.iterdir():
                for file in version.iterdir():
                    if file.is_file() and file.name.upper().startswith(
                        ("LICENSE", "COPYING", "NOTICE")
                    ):
                        destination = licenses / formula.name / file.name
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(file, destination)
        (stage / "build-packages.txt").write_text(output("brew", "list", "--versions"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, default=min(os.cpu_count() or 2, 12))
    args = parser.parse_args()
    system = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}[
        platform.system()
    ]
    arch = {
        "x86_64": "x86_64",
        "AMD64": "x86_64",
        "aarch64": "arm64",
        "arm64": "arm64",
        "ARM64": "arm64",
    }[platform.machine()]
    target = f"{system}-{arch}"
    work = ROOT / "build" / "vulkan-work" / target
    stage = work / "runtime"
    work.mkdir(parents=True, exist_ok=True)
    glslang, mesa, loader, headers = [source(name, work) for name in SOURCES]
    prefix = work / "install"
    run(
        "cmake",
        "-S",
        headers,
        "-B",
        work / "headers-build",
        "-G",
        "Ninja",
        f"-DCMAKE_INSTALL_PREFIX={prefix}",
    )
    run("cmake", "--install", work / "headers-build")
    run(
        "cmake",
        "-S",
        loader,
        "-B",
        work / "loader-build",
        "-G",
        "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_INSTALL_PREFIX={prefix}",
        f"-DCMAKE_PREFIX_PATH={prefix}",
        "-DBUILD_TESTS=OFF",
        "-DBUILD_WERROR=OFF",
        "-DBUILD_WSI_XCB_SUPPORT=OFF",
        "-DBUILD_WSI_XLIB_SUPPORT=OFF",
        "-DBUILD_WSI_XLIB_XRANDR_SUPPORT=OFF",
        "-DBUILD_WSI_WAYLAND_SUPPORT=OFF",
    )
    run("cmake", "--build", work / "loader-build", "--parallel", args.jobs)
    run("cmake", "--install", work / "loader-build")
    run(
        "cmake",
        "-S",
        glslang,
        "-B",
        work / "glslang-build",
        "-G",
        "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_INSTALL_PREFIX={prefix}",
        "-DENABLE_OPT=OFF",
        "-DBUILD_TESTING=OFF",
    )
    run("cmake", "--build", work / "glslang-build", "--parallel", args.jobs)
    run("cmake", "--install", work / "glslang-build")
    os.environ["PATH"] = str(prefix / "bin") + os.pathsep + os.environ["PATH"]
    mesa_build = work / "mesa-build"
    run(
        "meson",
        "setup",
        *(["--reconfigure"] if mesa_build.exists() else []),
        mesa_build,
        mesa,
        f"--prefix={prefix}",
        "--libdir=lib",
        "--buildtype=release",
        # MSYS2's static regex wrapper omits its transitive TRE dependency.
        # Use its shared libraries on Windows and bundle the complete DLL closure.
        *(["--prefer-static"] if system != "windows" else []),
        "-Db_ndebug=true",
        "-Dgallium-drivers=llvmpipe",
        "-Dvulkan-drivers=swrast",
        "-Dllvm=enabled",
        "-Dshared-llvm=enabled",
        # Mesa's Win32 external handles and WSI dispatch share platform declarations.
        # Keep its supported Windows configuration; no window or surface is created.
        "-Dplatforms=windows" if system == "windows" else "-Dplatforms=",
        "-Dglx=disabled",
        "-Dgbm=disabled",
        "-Dgallium-va=disabled",
        "-Dopengl=false",
        "-Dgles1=disabled",
        "-Dgles2=disabled",
        "-Degl=disabled",
        "-Dglvnd=disabled",
        "-Dspirv-tools=disabled",
        "-Dzstd=disabled",
        "-Dzlib=enabled",
        "-Dexpat=disabled",
        "-Dxmlconfig=disabled",
        "-Dbuild-tests=false",
        "-Dtools=",
        "-Dvideo-codecs=",
    )
    run("meson", "compile", "-C", mesa_build, "-j", args.jobs)
    run("meson", "install", "-C", mesa_build)
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    if system == "linux":
        loader_file = next(prefix.rglob("libvulkan.so.1"))
        driver_file = next(prefix.rglob("libvulkan_lvp.so"))
        linux_closure([loader_file, driver_file], stage)
    elif system == "macos":
        loader_file = next(prefix.rglob("libvulkan.1.dylib"))
        driver_file = next(prefix.rglob("libvulkan_lvp.dylib"))
        macos_closure([loader_file, driver_file], stage)
    else:
        loader_file = next(prefix.rglob("vulkan-1.dll"))
        driver_file = next(prefix.rglob("*vulkan_lvp.dll"))
        windows_closure([loader_file, driver_file], stage)
    run(
        "cmake",
        "-S",
        ROOT / "build-data/vulkan",
        "-B",
        work / "smoke-build",
        "-G",
        "Ninja",
        f"-DCMAKE_PREFIX_PATH={prefix}",
        "-DCMAKE_BUILD_TYPE=Release",
    )
    run("cmake", "--build", work / "smoke-build")
    smoke = (
        work
        / "smoke-build"
        / ("vulkan-smoke.exe" if system == "windows" else "vulkan-smoke")
    )
    smoke_env = {
        key: value for key, value in os.environ.items() if not key.startswith("VK_")
    }
    print(
        "Packaged libraries:",
        ", ".join(sorted(file.name for file in stage.iterdir())),
        flush=True,
    )
    run(smoke, stage / loader_file.name, stage / driver_file.name, env=smoke_env)
    copy_licenses(stage, system, mesa, loader)
    (stage / "sources.json").write_text(json.dumps(SOURCES, indent=2) + "\n")
    entries = {
        file.relative_to(stage).as_posix(): hashlib.sha256(
            file.read_bytes()
        ).hexdigest()
        for file in sorted(stage.rglob("*"))
        if file.is_file()
    }
    manifest = {
        "loader": loader_file.name,
        "driver": driver_file.name,
        **{f"sha256.{name}": digest for name, digest in entries.items()},
    }
    (stage / "runtime.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in manifest.items())
    )
    destination = ROOT / "build/vulkan-runtime" / f"soulfire-vulkan-{target}.jar"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(
        destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9
    ) as jar:
        for file in sorted(stage.rglob("*")):
            if file.is_file():
                jar.write(
                    file,
                    f"soulfire-vulkan/{target}/{file.relative_to(stage).as_posix()}",
                )
    print(
        f"Built {destination}: {destination.stat().st_size / 1024 / 1024:.1f} MiB",
        flush=True,
    )


if __name__ == "__main__":
    main()
