#!/bin/bash

projectVersion=$(grep '^maven_version=' gradle.properties | cut -d'=' -f2)
commitSha=$(git rev-parse --short HEAD)
echo "Building image for commit $commitSha and version $projectVersion"

docker buildx build \
  --push \
  --platform=linux/amd64,linux/arm64 \
  -t ghcr.io/soulfiremc-com/soulfire:$commitSha \
  -t ghcr.io/soulfiremc-com/soulfire:$projectVersion \
  -t ghcr.io/soulfiremc-com/soulfire:latest \
  .
