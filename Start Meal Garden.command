#!/bin/zsh
set -e
cd "${0:A:h}"
export PATH="$HOME/.local/bin:$HOME/.local/share/mise/installs/node/lts/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
export MEAL_HOST=0.0.0.0
if [[ ! -d companion/node_modules/qrcode ]]; then
 npm ci --prefix companion --ignore-scripts
fi
if curl -fsS http://127.0.0.1:4783/health >/dev/null 2>&1; then
 open http://localhost:4783/setup
 echo 'Meal Garden is already running. Open the setup page to pair your phone.'
else
 node companion/server.mjs &
 GARDEN_PID=$!
 trap 'kill "$GARDEN_PID" 2>/dev/null || true' EXIT INT TERM
 for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:4783/health >/dev/null 2>&1; then break; fi
  sleep 0.2
 done
 open http://localhost:4783/setup
 echo 'Keep this window open while using the phone app. Press Control-C to stop.'
 wait "$GARDEN_PID"
fi
