#!/usr/bin/env python3
"""Launch inside a bounded trace window; retain capture and launch evidence."""
import argparse
import pathlib
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    cli = shutil.which("pico-cli")
    if not cli:
        raise RuntimeError("pico-cli missing")
    package = "com.pico.demo.wasteland"
    subprocess.run([cli, "app", "stop", package, "--device", args.serial], check=True)
    recorder = subprocess.Popen([
        cli, "perf", "trace", "record", "-d", args.serial, "-a", package,
        "-t", "30", "-o", str(output / "startup.perfetto-trace"),
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    launched = False
    try:
        with (output / "startup-capture.log").open("w") as log:
            for line in recorder.stdout:
                log.write(line)
                log.flush()
                print(line, end="", flush=True)
                if not launched and "Recording started" in line:
                    launched = True
                    result = subprocess.run([
                        cli, "app", "launch", package, "--activity",
                        ".platform.LaunchActivity", "--device", args.serial,
                    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                    (output / "startup-launch.log").write_text(result.stdout)
                    print(result.stdout, end="", flush=True)
                    if result.returncode:
                        raise RuntimeError("Launch failed inside capture")
        if recorder.wait(timeout=10):
            raise RuntimeError("Trace capture failed")
        if not launched:
            raise RuntimeError("Capture never reported readiness")
        print("STARTUP_CAPTURE_COMPLETE", flush=True)
    finally:
        if recorder.poll() is None:
            recorder.terminate()
            recorder.wait(timeout=10)


if __name__ == "__main__":
    main()
