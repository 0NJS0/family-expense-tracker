#!/usr/bin/env bash
# End-to-end check on a throwaway database:
#   real backend  +  the dashboard's production build in a headless browser
#   +  the phone app's real-backend test on the emulator (if it is running).
#
#   scripts/e2e.sh                 # skips the phone part when no emulator
#   scripts/e2e.sh --require-phone # fails when no emulator
#
# Only ever uses the emulator (emulator-5554), never a phone on USB. Everything
# it creates (database, processes, files) is removed at the end, also on failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API_PORT=8011
WEB_PORT=4180
require_phone=0
[ "${1:-}" = "--require-phone" ] && require_phone=1

work="$(mktemp -d)"
db_url=""
pids=()
cleanup() {
	for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
	if [ -n "$db_url" ]; then
		(cd "$ROOT/backend" && uv run python -c "import sys; from sqlalchemy import make_url; from tests.pgutil import drop_database; drop_database(make_url(sys.argv[1]))" "$db_url") \
			&& echo "== throwaway database dropped"
	fi
	rm -rf "$work"
}
trap cleanup EXIT

wait_for() { # url
	for _ in $(seq 1 60); do curl -sf "$1" >/dev/null && return 0; sleep 1; done
	echo "timed out waiting for $1" >&2
	return 1
}

echo "== throwaway database and backend"
cd "$ROOT/backend"
db_url="$(uv run python -c "from tests.pgutil import create_database; print(create_database().render_as_string(hide_password=False))")"
password="$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')"
export FET_DATABASE_URL="$db_url" FET_ENVIRONMENT=development FET_RECEIPTS_DIR="$work/receipts" \
	FET_JWT_SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')" FET_LLAMA_BASE_URL=
uv run alembic upgrade head >/dev/null
FET_BOOTSTRAP_PASSWORD="$password" uv run python -m app.cli bootstrap \
	--username e2eadmin --display-name "E2E Admin" --household "E2E family" >/dev/null
# 0.0.0.0 so the emulator can reach it as 10.0.2.2.
setsid uv run uvicorn app.main:create_app --factory --host 0.0.0.0 --port "$API_PORT" --no-access-log \
	>"$work/api.log" 2>&1 </dev/null &
pids+=("$!")
wait_for "http://127.0.0.1:$API_PORT/api/v1/ready"
python3 "$ROOT/scripts/e2e_seed.py" seed "http://127.0.0.1:$API_PORT" e2eadmin "$password"

echo "== dashboard (production build) in a headless browser"
cd "$ROOT/dashboard"
npm run build >/dev/null
FET_API_URL="http://127.0.0.1:$API_PORT" setsid npx vite preview --port "$WEB_PORT" --strictPort \
	>"$work/web.log" 2>&1 </dev/null &
pids+=("$!")
wait_for "http://127.0.0.1:$WEB_PORT/"
E2E_URL="http://127.0.0.1:$WEB_PORT" E2E_USER=e2eadmin E2E_PASSWORD="$password" node e2e/browser-check.mjs

echo "== phone app against the real backend (emulator only)"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
if "$adb" devices 2>/dev/null | grep -q "^emulator-5554[[:space:]]*device"; then
	cd "$ROOT/android"
	export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" ANDROID_SERIAL=emulator-5554
	rm -rf app/build/outputs/androidTest-results/connected
	./gradlew --console=plain connectedDebugAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.class=bd.family.expenses.ui.RealBackendTest \
		-Pandroid.testInstrumentationRunnerArguments.fetServer="http://10.0.2.2:$API_PORT" \
		-Pandroid.testInstrumentationRunnerArguments.fetUser=e2eadmin \
		-Pandroid.testInstrumentationRunnerArguments.fetPassword="$password" >"$work/phone.log" 2>&1 \
		|| { tail -30 "$work/phone.log"; exit 1; }
	# Gradle's exit code is not enough: the test must have run, not been skipped.
	python3 - <<'PY'
import glob, sys, xml.etree.ElementTree as ET
cases = [tc for f in glob.glob("app/build/outputs/androidTest-results/connected/debug/*.xml") for tc in ET.parse(f).getroot().iter("testcase")]
bad = [tc for tc in cases if any(c.tag in ("failure", "error", "skipped") for c in tc)]
if not cases or bad:
    sys.exit(f"phone test did not pass: {len(cases)} run, {len(bad)} failed or skipped")
print("  ok  phone test passed")
PY
	python3 "$ROOT/scripts/e2e_seed.py" verify "http://127.0.0.1:$API_PORT" e2eadmin "$password"
elif [ "$require_phone" = 1 ]; then
	echo "no emulator (emulator-5554) running" >&2
	exit 1
else
	echo "  SKIPPED: start the emulator to include the phone (android emulator start --headless <avd-name>)"
fi
echo "== end-to-end check passed"
