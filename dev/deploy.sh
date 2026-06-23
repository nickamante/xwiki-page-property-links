#!/usr/bin/env bash
# deploy.sh — copy the built JAR into the running XWiki container and restart it.
#
# USAGE:
#   bash dev/deploy.sh                    # uses the compose xwiki service (dev stack)
#   bash dev/deploy.sh <container-name>   # deploy to any named/id'd running container
#
# PRIVILEGED-EXTENSION CAVEAT:
#   Both components (SolrEntityMetadataExtractor + EventListener) are registered via
#   META-INF/components.txt and loaded from WEB-INF/lib at Tomcat startup. Dropping the JAR
#   into WEB-INF/lib while XWiki is running has NO effect until the container is restarted —
#   a hot-reload is NOT supported for XWiki component JARs. This script always restarts.
#
# TARGET PATH (confirmed from xwiki-docker README):
#   /usr/local/tomcat/webapps/ROOT/WEB-INF/lib/
#   The CONTEXT_PATH env var defaults to ROOT; override if you customised it.
#
# PREREQUISITES:
#   - JAR built: bash dev/build.sh clean package
#   - Docker accessible in your shell (on Windows, run from WSL or Git Bash).

set -euo pipefail
cd "$(dirname "$0")/.."

# Stop Git Bash (MSYS) from rewriting the container-side path in `docker cp ... :/usr/local/...`.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# --- locate the JAR ---
JAR=$(ls target/page-property-links-*.jar 2>/dev/null | head -n1)
if [[ -z "$JAR" ]]; then
  echo "ERROR: No JAR found in target/. Run: bash dev/build.sh clean package" >&2
  exit 1
fi

# --- locate the container ---
CONTEXT_PATH="${CONTEXT_PATH:-ROOT}"
LIB_PATH="/usr/local/tomcat/webapps/${CONTEXT_PATH}/WEB-INF/lib/"

if [[ $# -ge 1 ]]; then
  # Explicit container name/id supplied — deploy to an already-running instance (e.g. your own
  # XWiki) rather than the compose dev stack.
  CONTAINER="$1"
  echo "Deploying to explicitly named container: $CONTAINER"
else
  # Default: resolve the compose xwiki service container id.
  CONTAINER=$(docker compose -f dev/docker-compose.yml ps -q xwiki 2>/dev/null | head -n1)
  if [[ -z "$CONTAINER" ]]; then
    echo "ERROR: Dev stack xwiki service is not running." >&2
    echo "  Start it: docker compose -f dev/docker-compose.yml up -d" >&2
    echo "  Or supply a container name: bash dev/deploy.sh <container-name>" >&2
    exit 1
  fi
  echo "Deploying to dev-stack container: $CONTAINER"
fi

echo "JAR:  $JAR"
echo "Dest: ${CONTAINER}:${LIB_PATH}"

docker cp "$JAR" "${CONTAINER}:${LIB_PATH}"
echo "Copied. Restarting container..."
docker restart "$CONTAINER"

echo ""
echo "Done. XWiki is restarting — wait ~60 s for startup, then verify in the browser."
echo "See dev/VERIFICATION.md for the full test runbook."
