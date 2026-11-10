#!/usr/bin/env python3
"""Run opt-in Debug checks, retain PID-scoped evidence, and optionally capture a bounded trace."""
import argparse
import pathlib
import re
import shutil
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--check", choices=("self", "combat", "both", "mission"), default="both")
    parser.add_argument("--trace", help="Optional .perfetto-trace output")
    parser.add_argument("--trace-delay", type=float, default=31.0)
    parser.add_argument("--trace-duration", type=int, default=30)
    parser.add_argument("--no-ink", action="store_true",
                        help="Debug-only A/B renderer probe; disables cloned ink shells.")
    shadows = parser.add_mutually_exclusive_group()
    shadows.add_argument("--full-shadows", action="store_true",
                         help="Explicitly use normal full static shadow casting (default).")
    shadows.add_argument("--shadow-budget", action="store_true",
                         help="Opt into the Debug distance-shadow experiment.")
    parser.add_argument("--combat-seconds", type=int, default=120,
                        help="Bounded combat check duration, 120..1200 seconds.")
    args = parser.parse_args()
    if args.trace and not (1 <= args.trace_duration <= 60 and 0 <= args.trace_delay <= 55):
        parser.error("Capture must fit in the bounded combat check.")
    if args.trace and args.check in ("self", "mission"):
        parser.error("A trace capture needs the combat check.")
    if not 120 <= args.combat_seconds <= 1200:
        parser.error("Combat duration must be between 120 and 1200 seconds.")
    adb = shutil.which("adb") or "/Users/bytedance/Library/Android/sdk/platform-tools/adb"
    cli = shutil.which("pico-cli")
    if args.trace and not cli:
        parser.error("pico-cli is required for trace capture")
    package = "com.pico.demo.wasteland"
    target = [adb, "-s", args.serial]
    flags = []
    if args.check in ("self", "both", "mission"):
        flags += ["--ez", "wasteland_self_check", "true"]
    if args.check in ("combat", "both"):
        flags += ["--ez", "wasteland_combat_stress", "true"]
        flags += ["--ei", "wasteland_combat_seconds", str(args.combat_seconds)]
    if args.check == "mission":
        flags += ["--ez", "wasteland_mission_stress", "true",
                  "--ei", "wasteland_combat_seconds", str(args.combat_seconds)]
    if args.no_ink:
        flags += ["--ez", "wasteland_profile_no_ink", "true"]
    if args.full_shadows:
        flags += ["--ez", "wasteland_profile_shadow_budget", "false"]
    if args.shadow_budget:
        flags += ["--ez", "wasteland_profile_shadow_budget", "true"]
    subprocess.run(target + ["shell", "am", "start", "-S", "-n",
                             package + "/.platform.LaunchActivity"] + flags, check=True)
    pid = ""
    pid_deadline = time.monotonic() + 15
    while time.monotonic() < pid_deadline:
        probe = subprocess.run(target + ["shell", "pidof", package],
                               text=True, stdout=subprocess.PIPE, check=False)
        pid = probe.stdout.strip()
        if pid.isdigit():
            break
        time.sleep(0.2)
    if not pid.isdigit():
        raise RuntimeError("No single app PID appeared within 15 seconds: " + pid)
    output = pathlib.Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    capture = None
    capture_log = None
    with output.open("w") as log:
        reader = subprocess.Popen(target + ["logcat", "--pid=" + pid, "-v", "threadtime"],
                                  stdout=log, stderr=subprocess.STDOUT)
        start = time.monotonic()
        ready_at = None
        last_progress = -10.0
        last_memory = -60.0
        self_passed = False
        combat_passed = False
        recent = ""
        last_status = "initializing"
        evidence_reader = output.open("r", errors="replace")
        memory_path = output.with_suffix(".memory.log")
        memory_path.write_text("")
        try:
            timeout_budget = args.combat_seconds + (240 if args.check == "mission" else 120)
            while time.monotonic() - start < timeout_budget:
                elapsed = time.monotonic() - start
                # Read appended bytes only; SDK diagnostics can make long soak logs tens of MB.
                text = recent[-1024:] + evidence_reader.read()
                recent = text[-64_000:]
                self_passed = self_passed or "V3_SELFCHECK_PASS" in text
                combat_passed = combat_passed or "COMBAT_STRESS_PASS" in text
                combat_passed = combat_passed or "MISSION_STRESS_PASS" in text
                lines = [line for line in text.splitlines()
                         if any(marker in line for marker in
                                ("COMBAT_STRESS ", "V3_SELFCHECK", "MISSION_STRESS ",
                                 "MISSION_PHASE ", "MISSION_CYCLE_PASS"))]
                if lines:
                    last_status = lines[-1]
                if any(marker in text for marker in
                       ("V3_SELFCHECK_FAIL", "COMBAT_STRESS_FAIL", "MISSION_STRESS_FAIL",
                        "SCENE_LOAD_FAILED", "AUDIO_LOOP_ERROR", "AUDIO_LOOP_INIT_ERROR", "FATAL EXCEPTION")):
                    raise RuntimeError("Device check failed; evidence: " + str(output))
                ready_marker = ("V3_SELFCHECK_PASS" if args.check in ("both", "mission") else "SCENE_READY")
                if ready_at is None and ready_marker in text:
                    ready_at = time.monotonic()
                if args.trace and capture is None and ready_at is not None and \
                        time.monotonic() - ready_at >= args.trace_delay:
                    trace = pathlib.Path(args.trace).resolve()
                    trace.parent.mkdir(parents=True, exist_ok=True)
                    capture_log = trace.with_suffix(".capture.log").open("w")
                    capture = subprocess.Popen([cli, "perf", "trace", "record",
                        "-d", args.serial, "-a", package, "-t", str(args.trace_duration),
                        "-o", str(trace)], stdout=capture_log, stderr=subprocess.STDOUT)
                    print("TRACE_STARTED " + str(trace), flush=True)
                if elapsed - last_progress >= 10:
                    current_pid = subprocess.check_output(
                        target + ["shell", "pidof", package], text=True).strip()
                    if current_pid != pid:
                        raise RuntimeError("App exited/restarted during check: " + current_pid)
                    print("DEVICE_CHECK pid={} elapsed={:.1f}s {}".format(
                        pid, elapsed, last_status), flush=True)
                    last_progress = elapsed
                if elapsed - last_memory >= 60:
                    memory = subprocess.check_output(
                        target + ["shell", "dumpsys", "meminfo", package], text=True)
                    match = re.search(r"TOTAL PSS:\s*(\d+)", memory)
                    with memory_path.open("a") as memory_log:
                        memory_log.write("elapsed={:.1f}s pid={}\n{}\n".format(elapsed, pid, memory))
                    print("MEMORY_SAMPLE elapsed={:.1f}s pssKb={}".format(
                        elapsed, match.group(1) if match else "unparsed"), flush=True)
                    last_memory = elapsed
                self_done = args.check == "combat" or self_passed
                combat_done = args.check == "self" or combat_passed
                if self_done and combat_done:
                    if args.trace and capture is None:
                        raise RuntimeError("Trace capture was never started")
                    if capture is not None and capture.wait(timeout=10) != 0:
                        raise RuntimeError("Trace capture failed")
                    print("DEVICE_CHECK_PASS pid={} evidence={}".format(pid, output), flush=True)
                    return
                time.sleep(0.3)
            raise TimeoutError("Device check exceeded its bounded runtime/startup budget")
        finally:
            evidence_reader.close()
            reader.terminate()
            reader.wait(timeout=5)
            if capture is not None and capture.poll() is None:
                capture.terminate()
                capture.wait(timeout=10)
            if capture_log:
                capture_log.close()


if __name__ == "__main__":
    main()
