# Build tools stay in this image; the runtime JAR contains only the native closure.
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl build-essential cmake ninja-build pkg-config python3 python3-venv \
    bison flex glslang-tools llvm-15-dev libclang-cpp15-dev libzstd-dev libz-dev libedit-dev libxml2-dev libdrm-dev \
    patchelf && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/build-tools && /opt/build-tools/bin/pip install --no-cache-dir meson==1.9.2 mako==1.3.10 packaging==25.0 pyyaml==6.0.3 backports.tarfile==1.2.0
ENV PATH="/opt/build-tools/bin:/usr/lib/llvm-15/bin:${PATH}"
WORKDIR /work
ENTRYPOINT ["python3", "scripts/build-vulkan-runtime.py"]
