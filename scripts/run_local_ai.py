"""Run one local development component with explicit loopback-only AI settings."""
import argparse
import os
from pathlib import Path
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("component", choices=("ollama", "api", "backend"))
    component = parser.parse_args().component
    for line in (ROOT / ".env").read_text().splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key, value)
    if component == "ollama":
        os.environ.update(OLLAMA_NO_CLOUD="1", OLLAMA_HOST="127.0.0.1:11434", OLLAMA_NUM_PARALLEL="1", OLLAMA_MAX_LOADED_MODELS="1", OLLAMA_DEBUG_LOG_REQUESTS="false")
        os.execvp("ollama", ["ollama", "serve"])
    if not os.environ.get("WECALL_AI_SERVICE_TOKEN"):
        raise SystemExit(".env에 WECALL_AI_SERVICE_TOKEN을 설정하세요. 토큰을 출력하거나 Git에 저장하지 마세요.")
    os.environ.update(WECALL_AI_PROVIDER="ollama-local", WECALL_AI_BASE_URL="http://127.0.0.1:8000", WECALL_AI_ALLOW_MOCK="false", WECALL_AI_READ_TIMEOUT_MS="100000")
    if component == "api":
        os.chdir(ROOT / "ai")
        os.execvp("uv", ["uv", "run", "--locked", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000", "--workers", "1", "--no-access-log"])
    jar = ROOT / "backend/build/libs/wecall-backend-0.0.1-SNAPSHOT.jar"
    if not jar.exists():
        raise SystemExit("먼저 ./backend/gradlew -p backend bootJar를 실행하세요.")
    # Keep a running JAR isolated from later builds.
    fd, copy = tempfile.mkstemp(prefix="wecall-local-ai-", suffix=".jar")
    os.close(fd)
    shutil.copyfile(jar, copy)
    os.chdir(ROOT)
    os.execvp("java", ["java", "-jar", copy])


if __name__ == "__main__": main()
