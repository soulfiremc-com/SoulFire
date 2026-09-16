FROM eclipse-temurin:25-jre AS soulfire-runner

ARG VERSION

# Setup groups and install dumb init
RUN groupadd --gid 1001 soulfire && \
    useradd --home-dir /soulfire --uid 1001 --gid soulfire --create-home soulfire && \
    apt-get update && apt-get install -y --no-install-recommends dumb-init curl && \
    rm -rf /var/lib/apt/lists/* && \
    chmod 755 /soulfire

# Download a release, or pass a local JAR path when validating an unpublished build.
ARG JAR_SOURCE=https://github.com/soulfiremc-com/SoulFire/releases/download/${VERSION}/SoulFireDedicated-${VERSION}.jar
ADD --chown=soulfire:soulfire ${JAR_SOURCE} /soulfire/soulfire.jar
RUN chmod 644 /soulfire/soulfire.jar

# Use the soulfire's home directory as our work directory
WORKDIR /soulfire/data

# Copy over the start script
COPY --chmod=755 start.sh /soulfire/start.sh

# Ensure runtime data directory is writable when mounted or initialized
RUN mkdir -p /soulfire/data && chown -R soulfire:soulfire /soulfire

# Switch from root to soulfire
USER soulfire

EXPOSE 38765/tcp

LABEL org.opencontainers.image.source="https://github.com/soulfiremc-com/SoulFire" \
      org.opencontainers.image.description="SoulFire Dedicated Server" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.title="SoulFire" \
      org.opencontainers.image.vendor="AlexProgrammerDE"

HEALTHCHECK --interval=30s --timeout=10s --retries=5 \
  CMD curl -f http://localhost:38765/health || exit 1

# Start the process using dumb-init
ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["/bin/bash", "/soulfire/start.sh"]
