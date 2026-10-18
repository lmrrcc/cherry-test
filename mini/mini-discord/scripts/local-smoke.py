import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.request

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="mini-discord-") as temporary:
    endpoint = Path(temporary) / "endpoints.json"
    environment = dict(os.environ, MINI_DISCORD_MODE="LOCAL_MINI_DISCORD",
                       MINI_DISCORD_TEST_API="true", MINI_DISCORD_TEST_SECRET=secrets.token_hex(32),
                       MINI_DISCORD_PORT="0", MINI_DISCORD_ENDPOINT_FILE=str(endpoint))
    started = time.monotonic()
    process = subprocess.Popen(["java", "-jar", "target/mini-discord.jar"], cwd=root, env=environment)
    try:
        deadline = time.monotonic() + 15
        while not endpoint.exists():
            if process.poll() is not None:
                raise RuntimeError("Mini-Discord exited before readiness")
            if time.monotonic() > deadline:
                raise TimeoutError("Mini-Discord did not become ready")
            time.sleep(0.02)
        endpoints = json.loads(endpoint.read_text())
        base = endpoints["CHERRY_TEST_MINI_DISCORD_DISCORD_URL"]
        with urllib.request.urlopen(base + "/health", timeout=2) as response:
            assert response.status == 200
        startup_ms = (time.monotonic() - started) * 1000
        environment["MINI_DISCORD_CONTAINER_URL"] = base
        subprocess.run(["./mvnw", "-o", "-B", "-ntp", "-Pcontainer", "verify"], cwd=root, env=environment, check=True)
        (root / "target" / "startup-performance.json").write_text(json.dumps({"startupMs": startup_ms}))
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
