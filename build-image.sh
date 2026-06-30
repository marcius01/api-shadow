  #!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Usage:
#   ./build-image.sh                              # build only, tag as api-shadow:latest
#   ./build-image.sh harbor.company.com/team      # build + tag for Harbor (no push)
#   ./build-image.sh harbor.company.com/team push # build + tag + push
#
# Examples:
#   ./build-image.sh harbor.mycompany.com/backend
#   ./build-image.sh harbor.mycompany.com/backend push
# ---------------------------------------------------------------------------

REGISTRY="${1:-}"
PUSH="${2:-}"
VERSION=$(grep '^version' build.gradle.kts | head -1 | sed "s/.*= \"\(.*\)\"/\1/")
LOCAL_TAG="api-shadow:latest"

echo "▶ Building Quarkus app..."
./gradlew build -x test

echo "▶ Building Docker image (JVM mode)..."
docker build -f src/main/docker/Dockerfile.jvm -t "$LOCAL_TAG" .

if [[ -n "$REGISTRY" ]]; then
  REMOTE_TAG="${REGISTRY}/api-shadow:${VERSION}"
  REMOTE_LATEST="${REGISTRY}/api-shadow:latest"

  echo "▶ Tagging as $REMOTE_TAG and $REMOTE_LATEST"
  docker tag "$LOCAL_TAG" "$REMOTE_TAG"
  docker tag "$LOCAL_TAG" "$REMOTE_LATEST"

  if [[ "$PUSH" == "push" ]]; then
    echo "▶ Pushing to Harbor..."
    docker push "$REMOTE_TAG"
    docker push "$REMOTE_LATEST"
    echo "✓ Pushed $REMOTE_TAG"
    echo "✓ Pushed $REMOTE_LATEST"
  else
    echo "✓ Tagged (not pushed). Run with 'push' to push:"
    echo "  ./build-image.sh $REGISTRY push"
  fi
else
  echo "✓ Built $LOCAL_TAG (local only)"
fi
