#!/usr/bin/env bash
# Re-pushes the locally built Angular bundle into the running webserver pod.
#
# Why this exists: the frontend fixes (welcome modal, agent cursor, result-panel
# drag) live only in frontend/dist, copied into the pod. A Kubernetes container
# restart starts a fresh container from the image and discards that copy, so
# every cluster restart silently reverts the UI to the image's build.
#
# Usage: bin/demo/redeploy-frontend.sh    (run from the repo root)
set -euo pipefail

[ -f frontend/dist/index.html ] || { echo "no frontend/dist — run 'cd frontend && npm run build' first"; exit 1; }

POD=$(kubectl get pods --no-headers -o custom-columns=:metadata.name | grep '^texera-webserver')
echo "pod: $POD"

kubectl exec "$POD" -- sh -c 'rm -rf /frontend/dist.new && mkdir -p /frontend/dist.new'
tar -C frontend/dist -cf - . | kubectl exec -i "$POD" -- tar -C /frontend/dist.new -xf -
kubectl exec "$POD" -- sh -c '
  rm -rf /frontend/dist.old
  mv /frontend/dist /frontend/dist.old
  mv /frontend/dist.new /frontend/dist'

# Verify what is actually served, not just what landed on disk. The bundle is
# gzipped by the gateway (hence --compressed) and is downloaded to a file before
# grepping: piping into `grep -q` makes grep exit early, which SIGPIPEs curl and
# trips `set -o pipefail` even on a successful match.
BUNDLE=$(mktemp)
trap 'rm -f "$BUNDLE"' EXIT
MAIN=$(curl -s --max-time 30 http://192.168.58.2:31675/ | grep -o "main\.[a-f0-9]*\.js" | sort -u | head -1)
curl -s --compressed --max-time 120 -o "$BUNDLE" "http://192.168.58.2:31675/$MAIN"

missing=""
grep -q "Built-in assistant" "$BUNDLE" || missing="$missing welcome-modal"
grep -q "ai-edit-mode-exit" "$BUNDLE" || missing="$missing ai-edit-mode"

if [ -z "$missing" ]; then
  echo "OK — $MAIN has the welcome modal and AI edit mode"
else
  echo "WARNING — served bundle $MAIN is missing:$missing"; exit 1
fi
