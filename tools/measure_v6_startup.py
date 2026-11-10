#!/usr/bin/env python3
"""Measure fresh processes with PID-scoped ready/scale evidence; never drives spatial UI."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--output", required=True)
p.add_argument("--runs", type=int, default=3)
args = p.parse_args()
cli = shutil.which("pico-cli")
adb = shutil.which("adb") or "/Users/bytedance/Library/Android/sdk/platform-tools/adb"
target = [adb, "-s", args.serial]
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
measurements = []
for run in range(1, args.runs + 1):
    for command in ("stop", "launch"):
        completed = subprocess.run(
            [cli, "app", command, "com.pico.demo.wasteland", "--device", args.serial] +
            (["--activity", ".platform.LaunchActivity"] if command == "launch" else []),
            text=True, capture_output=True, timeout=30, check=True)
        (output / f"run-{run}-{command}.log").write_text(completed.stdout + completed.stderr)
    pid = subprocess.check_output(target + ["shell", "pidof", "com.pico.demo.wasteland"],
                                  text=True).strip()
    assert pid.isdigit(), pid
    log_file = (output / f"run-{run}.log").open("w")
    reader = subprocess.Popen(target + ["logcat", "--pid=" + pid, "-v", "threadtime"],
                              stdout=log_file, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 60
    last_progress = 0
    while time.monotonic() < deadline:
        logs = (output / f"run-{run}.log").read_text(errors="replace")
        if "FATAL EXCEPTION" in logs or "SCENE_LOAD_FAILED" in logs:
            reader.terminate(); reader.wait(timeout=5); log_file.close()
            raise RuntimeError(f"Startup failed; see {output}/run-{run}.log")
        ready = re.search(r"SCENE_READY version=\d+.*?initMs=(\d+) launchMs=(\d+)", logs)
        if ready:
            nav = re.search(r"NAV_GRID_READY .*?cache=(\S+) durationMs=(\d+)", logs)
            phases = re.findall(r"V6_LOAD_PHASE phase=(\S+) durationMs=(\d+)", logs)
            measurement = dict(run=run, pid=int(pid), initMs=int(ready[1]), launchMs=int(ready[2]),
                               navigation=nav[1] if nav else None,
                               navigationMs=int(nav[2]) if nav else None,
                               phases=dict((k, int(v)) for k, v in phases))
            measurements.append(measurement)
            (output / "measurements.json").write_text(json.dumps(measurements, indent=2))
            print(json.dumps(measurement), flush=True)
            reader.terminate(); reader.wait(timeout=5); log_file.close()
            break
        if time.monotonic() - last_progress > 10:
            print(f"STARTUP_WAIT run={run} pid={pid}", flush=True)
            last_progress = time.monotonic()
        time.sleep(0.25)
    else:
        reader.terminate(); reader.wait(timeout=5); log_file.close()
        raise TimeoutError(f"Startup exceeded 60s; see {output}/run-{run}.log")
