#!/usr/bin/env bash
# CI only: an empty disposable PostgreSQL database and test accounts are required.
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p validation-results
backend_pid=''
frontend_pid=''
cleanup() {
  if [[ -n "$frontend_pid" ]]; then kill "$frontend_pid" 2>/dev/null || true; fi
  if [[ -n "$backend_pid" ]]; then kill "$backend_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT
# Refuse to accidentally validate an unrelated server already using these ports.
python3 - <<'PY'
import socket
for port in (8080, 5173):
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1', port)) == 0:
            raise SystemExit(f'Port {port} is already in use; use a disposable CI environment')
PY
java -jar backend/build/libs/wecall-backend-0.0.1-SNAPSHOT.jar > validation-results/backend.log 2>&1 &
backend_pid=$!
node frontend/node_modules/vite/bin/vite.js --host 127.0.0.1 --config frontend/vite.config.ts frontend > validation-results/frontend.log 2>&1 &
frontend_pid=$!
python3 - <<'PY'
import time
import urllib.request
for url in ('http://127.0.0.1:8080/actuator/health', 'http://127.0.0.1:5173'):
    for attempt in range(90):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    break
        except OSError:
            pass
        time.sleep(1)
    else:
        raise SystemExit(f'Server readiness timed out: {url}')
PY
(cd frontend && PLAYWRIGHT_JUNIT_OUTPUT_FILE=test-results/results.xml npm run test:e2e -- --reporter=list,junit)
python3 scripts/demo_recall.py --with-evidence --with-tasks > validation-results/recall.json
python3 scripts/demo_closure.py > validation-results/closure.json
