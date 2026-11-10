#!/usr/bin/env python3
"""Bounded, reproducible same-session queries; retain SQL and raw CLI envelopes."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--session", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)


def query(name, sql):
    (output / (name + ".sql")).write_text(sql + "\n")
    result = subprocess.run(
        ["pico-cli", "perf", "trace", "query", "--session", args.session, "--sql", sql],
        capture_output=True, text=True, timeout=50)
    (output / (name + ".json")).write_text(result.stdout)
    envelope = json.loads(result.stdout)
    if not envelope.get("success"):
        raise RuntimeError(name + ": " + str(envelope.get("error")))
    rows = envelope["data"]["rows"]
    print(name, json.dumps(rows)[:2200], flush=True)
    return rows


identity = query("identities", """SELECT p.upid,p.pid,p.name process,t.utid,t.tid,t.name thread
FROM process p JOIN thread t ON p.upid=t.upid
WHERE (p.name='com.pico.demo.wasteland' AND p.pid=t.tid)
OR (p.name='com.pico.spatial.runtime' AND
(t.name GLOB '*Spatial_Main*' OR t.name GLOB '*Eng*Render*'))""")
app = next(row for row in identity if row["process"] == "com.pico.demo.wasteland")
utid = app["utid"]
upid = app["upid"]
track = query("app-tracks", f"SELECT id FROM thread_track WHERE utid={utid}")
track_ids = ",".join(str(row["id"]) for row in track)
app_filter = f"track_id IN ({track_ids})"
frames = (f"SELECT * FROM slice WHERE {app_filter} AND depth=0 "
          "AND name GLOB 'Choreographer#doFrame [0-9]*' AND dur>0")
query("app-frame-stats", f"""WITH f AS ({frames}) SELECT COUNT(*) n,AVG(dur)/1e6 avgMs,
MAX(dur)/1e6 maxMs,SUM(dur>11111111) over90,SUM(dur>22222222) over45,
CAST(MIN(ts) AS TEXT) startNs,CAST(MAX(ts+dur) AS TEXT) endNs FROM f""")
worst = query("app-worst", f"""WITH f AS ({frames}) SELECT id,CAST(ts AS TEXT) tsNs,
dur,dur/1e6 ms FROM f ORDER BY dur DESC LIMIT 5""")
frame = worst[0]
start = int(frame["tsNs"])
end = start + frame["dur"]
query("worst-states", f"""SELECT state,blocked_function,
SUM(MIN(ts+dur,{end})-MAX(ts,{start}))/1e6 overlapMs
FROM thread_state WHERE utid={utid} AND ts<{end} AND ts+dur>{start} AND dur>0 GROUP BY 1,2""")
query("worst-slices", f"""SELECT id,parent_id,name,dur/1e6 ms,CAST(ts AS TEXT) tsNs
FROM slice WHERE {app_filter} AND ts>={start} AND ts+dur<={end} AND dur>100000
ORDER BY dur DESC LIMIT 35""")
query("main-dependencies", f"""SELECT name,COUNT(*) n,MAX(dur)/1e6 maxMs,
SUM(dur)/1e6 totalMs FROM slice WHERE {app_filter} AND dur>0
AND (name GLOB '*binder*' OR name GLOB '*Audio*' OR name GLOB '*audio*'
OR name GLOB '*Sound*' OR name GLOB '*Wait*' OR name GLOB '*Suspend*'
OR name GLOB '*Flip*' OR name GLOB '*contention*')
GROUP BY name ORDER BY maxMs DESC LIMIT 35""")
query("spatial-frame-stats", """SELECT name,COUNT(*) n,AVG(dur)/1e6 avgMs,
MAX(dur)/1e6 maxMs,CAST(MIN(ts) AS TEXT) startNs,CAST(MAX(ts+dur) AS TEXT) endNs
FROM OpenXRClientSpatialFrames WHERE name IN ('CPUTime','GPUTime') OR depth=0 GROUP BY depth,
CASE WHEN depth=0 THEN 'frame' ELSE name END""")
query("counter-tracks", """SELECT id,name FROM counter_track
WHERE name GLOB '*Shadow*' OR name GLOB '*Triangle*' OR name GLOB '*Draw*'
OR name GLOB '*freq*' OR name GLOB '*rss*' OR name GLOB '*mem*' LIMIT 100""")
query("gc-slices", f"""SELECT s.name,COUNT(*) n,AVG(s.dur)/1e6 avgMs,MAX(s.dur)/1e6 maxMs
FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
WHERE t.upid={upid} AND s.dur>0 AND (s.name GLOB '*GC*' OR s.name GLOB '*Suspend*'
OR s.name GLOB '*Flip*') GROUP BY s.name ORDER BY maxMs DESC LIMIT 40""")
query("worst-gc", f"""SELECT t.name thread,s.name,s.dur/1e6 ms,CAST(s.ts AS TEXT) tsNs
FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
WHERE t.upid={upid} AND s.ts<{end} AND s.ts+s.dur>{start} AND s.dur>0
AND (s.name GLOB '*GC*' OR s.name GLOB '*Suspend*' OR s.name GLOB '*Flip*') ORDER BY s.dur DESC""")
query("cpu-distribution", f"""SELECT cpu,COUNT(*) n,SUM(dur)/1e6 runMs
FROM sched WHERE utid={utid} AND dur>0 GROUP BY cpu ORDER BY runMs DESC""")
query("worst-cpu-contenders", f"""SELECT p.name process,t.name thread,s.cpu,
SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1e6 runMs
FROM sched s JOIN thread t ON s.utid=t.utid LEFT JOIN process p ON t.upid=p.upid
WHERE s.ts<{end} AND s.ts+s.dur>{start} AND s.dur>0 AND s.utid!={utid}
GROUP BY 1,2,3 ORDER BY runMs DESC LIMIT 18""")
