#!/usr/bin/env bash
#
# install-release.sh — download a Page Property Links release and install it into a running
# XWiki Docker container as a core extension, then restart the container (Tomcat).
#
# Usage:
#   bash dev/install-release.sh [VERSION] [CONTAINER]
#   bash dev/install-release.sh                 # latest release, container "xwiki"
#   bash dev/install-release.sh v1.0.0          # a specific version
#   bash dev/install-release.sh v1.0.0 my-xwiki # specific version + container name
#
# WHERE THE JAR GOES — and why there is no lib folder in your data mount:
#   A core-extension JAR lives with the WEBAPP, inside the container at:
#       /usr/local/tomcat/webapps/ROOT/WEB-INF/lib/
#   Your bind mount (/usr/local/xwiki — e.g. /docker/xwiki/data/data on the host) is XWiki's
#   PERMANENT directory (data, extensions, Solr index) — NOT the webapp — so it has no WEB-INF/lib.
#   This script docker-cp's the JAR into the webapp dir and restarts the container.
#
#   The JAR survives `docker restart`, but is LOST if the container is recreated (compose down/up,
#   image upgrade). Re-run this script after recreating the container, or install through the
#   Extension Manager for a data-volume-persistent install.
#
set -euo pipefail

# Stop Git Bash (MSYS) from rewriting the container-side paths in docker cp / exec.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

REPO="${PPL_REPO:-nickamante/xwiki-page-property-links}"
VERSION="${1:-latest}"
CONTAINER="${2:-xwiki}"
LIB_DIR="/usr/local/tomcat/webapps/ROOT/WEB-INF/lib"

# Resolve "latest" to a concrete tag via the GitHub redirect.
if [ "$VERSION" = "latest" ]; then
  VERSION="$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
              "https://github.com/$REPO/releases/latest" | sed 's#.*/tag/##')"
  [ -n "$VERSION" ] || { echo "ERROR: could not resolve the latest release tag." >&2; exit 1; }
fi
JAR="page-property-links-${VERSION#v}.jar"
URL="https://github.com/$REPO/releases/download/${VERSION}/${JAR}"

echo "Repo      : $REPO"
echo "Version   : $VERSION"
echo "Artifact  : $JAR"
echo "Container : $CONTAINER"

# 1. The container must be running.
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "ERROR: no running Docker container named '$CONTAINER'. Running containers:" >&2
  docker ps --format '  {{.Names}}' >&2
  exit 1
fi

# 2. Download the release JAR to a temp dir.
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo "Downloading $URL ..."
curl -fSL -o "$TMP/$JAR" "$URL"

# 3. Remove any previous version, copy the new JAR in, restart the container (Tomcat).
echo "Removing any existing page-property-links-*.jar ..."
docker exec "$CONTAINER" sh -c "rm -f $LIB_DIR/page-property-links-*.jar"
echo "Installing $JAR into $CONTAINER:$LIB_DIR/ ..."
docker cp "$TMP/$JAR" "$CONTAINER:$LIB_DIR/$JAR"
echo "Restarting $CONTAINER (Tomcat) ..."
docker restart "$CONTAINER" >/dev/null

echo
echo "Done. $JAR installed; XWiki is restarting (~30-60s)."
echo "First install only: run a full Solr reindex (Admin > Search > Solr) so existing pages"
echo "gain their Page-property backlinks."
