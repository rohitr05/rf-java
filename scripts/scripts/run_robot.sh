#!/usr/bin/env bash
set -euo pipefail

# ---------- Config (override via env or Jenkins env) ----------
RF_HOST="${RF_HOST:-0.0.0.0}"
RF_PORT="${RF_PORT:-8270}"
BASE="${BASE:-https://httpbin.org}"
ALLURE_DIR="${ALLURE_DIR:-results/allure}"
ROBOT_DIR="${ROBOT_DIR:-results/robot}"
PROCESSES="${PROCESSES:-4}"

echo "[INFO] Building Java keyword server..."
mvn -B -DskipTests package

JAR_PATH="$(ls **/*-shaded.jar 2>/dev/null || true)"
if [ -z "${JAR_PATH}" ]; then
  JAR_PATH="$(ls target/*-shaded.jar)"
fi

echo "[INFO] Starting Keyword Server on ${RF_HOST}:${RF_PORT} from ${JAR_PATH} ..."
nohup java -Drf.port="${RF_PORT}" -Drf.host="${RF_HOST}" -jar "${JAR_PATH}" > keywordserver.log 2>&1 &
SERVER_PID=$!
echo "${SERVER_PID}" > keywordserver.pid

echo "[INFO] Waiting for /rest to become ready..."
for i in {1..40}; do
  if curl -sf "http://127.0.0.1:${RF_PORT}/rest" >/dev/null; then
    echo "[INFO] Keyword Server is ready."
    break
  fi
  sleep 1
done

python3 -m pip -q install -U pip
pip3 -q install -r requirements.txt

mkdir -p "${ALLURE_DIR}" "${ROBOT_DIR}"

echo "[INFO] Running tests in parallel with pabot (processes=${PROCESSES}) ..."
start=$(date +%s)

# NOTE: using --testlevelsplit to fully parallelize down to test level
pabot \
  --processes "${PROCESSES}" \
  --testlevelsplit \
  --outputdir "${ROBOT_DIR}" \
  --listener "allure_robotframework;${ALLURE_DIR}" \
  --variable "BASE:${BASE}" \
  api_smoke.robot \
  sql_demo.robot \
  fix_demo.robot

end=$(date +%s)
elapsed=$((end-start))
echo "ELAPSED_SECONDS=${elapsed}" | tee results/time.txt

echo "[INFO] Stopping Keyword Server..."
kill "${SERVER_PID}" 2>/dev/null || true
sleep 1

echo "[INFO] Done. Robot: ${ROBOT_DIR}, Allure: ${ALLURE_DIR}, Time: ${elapsed}s"
