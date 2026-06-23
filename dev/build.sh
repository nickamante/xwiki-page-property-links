#!/usr/bin/env bash
#
# Build/test the extension inside a throwaway Maven container — no host JDK or Maven
# required (Java 21, required by XWiki 18.x). The Maven cache lives in a named Docker
# volume so dependencies persist across runs and stay fast on any filesystem.
#
# Portable across Linux, macOS and Windows. On Windows, run from WSL or Git Bash.
#
#   bash dev/build.sh clean package    # -> target/page-property-links-<version>.jar
#   bash dev/build.sh test
#
# Override the cache volume name with PPL_M2_VOLUME if you like.
#
set -euo pipefail
cd "$(dirname "$0")/.."

# Stop Git Bash (MSYS) from rewriting container-side paths such as "/work" or "/root/.m2".
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

IMAGE="maven:3.9-eclipse-temurin-21"
M2_VOLUME="${PPL_M2_VOLUME:-ppl-m2}"

# Maven runs as root inside the container so it can write the cache volume; afterwards the
# build output is chowned back to the host user so target/ is never left root-owned on a
# native Linux filesystem. (`id` is absent on some Windows shells — default to 0:0 there,
# where Docker Desktop maps ownership anyway, so the chown is a harmless no-op.)
HOST_UID="$(id -u 2>/dev/null || echo 0)"
HOST_GID="$(id -g 2>/dev/null || echo 0)"

exec docker run --rm \
  -v "$PWD:/work" -w /work \
  -v "${M2_VOLUME}:/root/.m2" \
  -e HOST_UID="$HOST_UID" -e HOST_GID="$HOST_GID" \
  "$IMAGE" \
  sh -c 'mvn "$@"; rc=$?; chown -R "$HOST_UID:$HOST_GID" /work/target 2>/dev/null || true; exit $rc' sh "$@"
