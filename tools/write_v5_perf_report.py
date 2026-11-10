#!/usr/bin/env python3
"""Assemble topic outputs from retained V5 SQL and the completed shadow experiment."""
from pathlib import Path
import json
import statistics
import yaml

session = Path("artifacts/2026-10-21-expansion/session-output-20261021-195709")
data = session / "performance-data"
topics = session / "topic-result"
topics.mkdir(exist_ok=True)
full = json.loads((data / "report-data-full.json").read_text())
budget = json.loads((data / "report-data-budget.json").read_text())


def mean_counter(report, name):
    for group in report["render_load_counter_groups"]:
        for counter in group["counters"]:
            if counter["name"] == name:
                return statistics.mean(point["value"] for point in counter["series"])
    raise ValueError("Missing measured counter: " + name)


comparison = {
    "full": full["spr_stats"],
    "budget": budget["spr_stats"],
    "counters": {name: {"full": mean_counter(full, name), "budget": mean_counter(budget, name)}
                 for name in ("Shadow Draw Call Count", "Shadow Triangle Count",
                              "3D Mesh Draw Call Count", "3D Mesh Triangle Count")},
    "decision": "Normal mode retains full authored shadows; budget is Debug opt-in.",
    "limits": [
        "Same APK, device, entry-combat routine and 30-second captures after navigation.",
        "Full used combat; budget used self-check then combat. Warm-up and enemy timing are not identical.",
        "Runtime counters aggregate submissions. Temperature and GPU frequency were not controlled.",
        "No stable previous-user-APK baseline. Frame duration is a pipeline span, not an FPS denominator.",
    ],
}
(data / "shadow-ab-comparison.json").write_text(json.dumps(comparison, indent=2) + "\n")
# Normal gameplay uses the full-shadow branch; do not present the experimental data as delivery.
(data / "report-data.json").write_text(json.dumps(full, indent=2) + "\n")

window = ("V5-active 36784532848623..36814510056059 ns; "
          "A/B budget 37544252372239..37574193210560 ns")
kb = {"status": "unavailable", "global_triage": "not_run",
      "selected_observations": [],
      "reason": "Tool discovery exposed no pico-spatial-knowledge or configured PerformanceKB backend."}
common_limits = [
    "Debug、脚本驱动真机检查；不是实际手柄输入或用户体感覆盖。",
    "没有稳定旧 APK 基线，不能宣称已修复用户报告的相对性能回退。",
    "PerformanceKB 后端不可用，机制结论保守标为 candidate。",
]


def evidence(ident, location, finding, kind="perfetto_sql"):
    path = session / location
    if not path.exists():
        raise FileNotFoundError(path)
    return {"id": ident, "type": kind, "location": "./" + location,
            "one_line_finding": finding}


specs = [
    ("spatial-engine", "spatial_frame_abnormal", "candidate", "render_stage",
     "持续超预算集中在 GPU/运行时消费路径；静态阴影工作削减没有改善帧数。",
     [
         evidence("e1", "performance-data/v5-analysis/spatial-frame-stats.json",
                  "1348 Late；GPU均值20.637ms，CPU均值13.564ms。"),
         evidence("e2", "performance-data/shadow-ab-comparison.json",
                  "完整/预算分支GPU27.080/26.895ms，各898帧；投影三角数2232601/975291。"),
         evidence("e3", "performance-data/budget-analysis/final-gate-sweep.json",
                  "App消费等待均值14.138ms/max23.439ms；Wasteland更新均值4.766ms。"),
     ],
     "按真实 app/SPR 身份及 CAST(ns AS TEXT) 锚点定位窗口。对照保持模型、材质、灯光与碰撞，"
     "只切换静态投影距离预算。投影三角数下降56.3%，GPU均值变化0.7%，帧数均898。"
     "主路径中的 Wait_consumed 是运行时消费同步；不能把它计为纯 Kotlin 执行成本。",
     ["正常模式恢复完整投影；距离预算仅保留为显式 Debug 探针。",
      "下一轮区分3D材质/蒙皮、像素填充、2D合成和GPU频率；需要pass级GPU证据。"],
     ["未取得pass级GPU时间，几何、片元与2D合成不能细分归因。", *comparison["limits"]],
     {"spatial": {"abnormal_frame_types": ["late"], "stage": "GPU / SPR Consume",
                  "bottleneck_side": "gpu", "pressure_source": "render_workload_candidate"}}),
    ("app_jank", "app_main_thread_jank", "contributor", "critical_path",
     "App帧包括运行时等待和独立的本地更新峰值，不能仅按 doFrame 均值归因应用逻辑。",
     [
         evidence("e1", "performance-data/v5-analysis/app-frame-stats.json",
                  "只计depth0数字doFrame：1345帧，均值18.830ms，max37.885ms。"),
         evidence("e2", "performance-data/v5-analysis/ordinary-jank-states.json",
                  "25.409ms帧有25.013ms Running，0.332ms Runnable。"),
         evidence("e3", "performance-data/v5-analysis/ordinary-jank-work.json",
                  "同一帧WastelandSystem更新17.986ms，超过11.11ms预算。"),
         evidence("e4", "performance-data/budget-analysis/worst-states.json",
                  "预算分支最慢帧：14.283ms Running、19.780ms S、0.443ms R。"),
     ],
     "帧驱动线程由process.pid=thread.tid解析，旧窗口utid11，新窗口utid10。"
     "排除嵌套resynced doFrame，避免重复计数。分别下钻Running与Sleeping："
     "旧25.409ms帧确有本地更新峰值；新窗口的长帧更多包含消费等待。"
     "单个物理射线、动画写回叶节点很短，不能直接称为物理或动画过载。",
     ["保留资源池、变更缓存与后台导航；下一轮定位本地更新峰值中的分配/同步耗时。"],
     ["缺少Kotlin函数采样，尚不能把17.986ms细分到某一个业务函数。"],
     {"app_jank": {"jank_type": "running_slow", "doframe_budget_ms": 11.11,
                   "key_slices": ["System_Update: WastelandSystem", "Wait_consumed"]}}),
    ("binder", "binder_latency", "background_noise", "server_cause",
     "帧内同步调用对应手柄震动服务，当前样本的服务执行较短，不支持服务争用为主因。",
     [
         evidence("e1", "performance-data/v5-analysis/main-binder-parents.json",
                  "帧内IPxrControllerService::setVCMotorEvent客户端最大0.537ms。"),
         evidence("e2", "performance-data/v5-analysis/binder-flows.json",
                  "flow明确指向pxrcontrollerservice binder:2576_9。"),
         evidence("e3", "performance-data/v5-analysis/binder-server-work.json",
                  "两个最高延迟样本服务方法0.047/0.002ms。"),
         evidence("e4", "performance-data/v5-analysis/binder-server-states.json",
                  "服务样本Running0.138ms/R+0.133ms；无长阻塞证据。"),
     ],
     "从客户端ancestor、flow到callee线程逐项核对，再检查服务叶节点与线程状态。"
     "最大客户端0.537ms对应server reply0.014ms；另一0.369ms调用的reply有0.133ms抢占。"
     "这些调用确在帧内，但远小于持续GPU超预算。",
     ["维持按真实射击事件触发震动，避免把震动调用移到每帧轮询。"],
     ["结论限定已下钻的最高延迟样本，不是整个系统服务的性能担保。"],
     {"binder": {"caller": "com.pico.demo.wasteland/.demo.wasteland",
                 "callee": "/system_ext/bin/pxrcontrollerservice/binder:2576_9",
                 "transaction_name_or_code": "IPxrControllerService::setVCMotorEvent",
                 "wait_time_ms": 0.536927, "service_confidence": "exact",
                 "downstream_dependency": "No slow downstream dependency observed in these samples"}}),
    ("memory", "memory_gc_jank", "candidate", "signal_overlap",
     "存在高频并发GC及一次长等待重叠；没有证据确认泄漏或OOM，也未证明GC解释持续GPU卡顿。",
     [
         evidence("e1", "performance-data/v5-analysis/gc-slices.json",
                  "33次后台并发GC；1次显式GC；FullSuspendCheck最大21.275ms。"),
         evidence("e2", "performance-data/v5-analysis/worst-gc.json",
                  "显式GC和导航线程FullSuspendCheck重叠37.885ms慢帧。"),
         evidence("e3", "performance-data/v5-analysis/heap-app-scoped.json",
                  "目标App Java Heap计数范围10873..51461KB；仅范围不能推断泄漏。"),
         evidence("e4", "performance-data/budget-analysis/gc-slices.json",
                  "后续预算窗口30次并发GC，FullSuspendCheck最大2.827ms。"),
     ],
     "限定目标upid后检查Java heap与GC，而不是混用系统所有Heap计数。"
     "旧最慢帧S段20.863ms与另线程FullSuspendCheck21.275ms重叠，"
     "但主线程缺少阻塞栈，具体锁/GC暂停链仍未闭合。采集期间meminfo请求可能影响显式GC。"
     "同PID交火检查没有进程重启、Java OOM或持续单调增长证据。",
     ["进一步定位热循环分配来源；补主线程阻塞栈后判断GC/导航同步是否放大偶发峰值。"],
     ["短窗口与少数PSS样本不能排除长期泄漏；meminfo可能扰动GC。"],
     {"memory": {"memory_metrics": "App Java heap counter 10873..51461 KB",
                 "gc_events": "33 background + 1 explicit in original window",
                 "lmk_oom": "No target-process OOM/LMK/restart in completed V5 checks"}}),
    ("cpu_sched", "cpu_sched_latency", "background_noise", "thread_state",
     "可执行调度证据存在，但帧内Runnable占比和最长段不足以解释当前持续超预算。",
     [
         evidence("e1", "performance-data/v5-analysis/jank-states-summary.json",
                  "整个30s窗口R+R+共446.362ms，最长单段1.272ms。"),
         evidence("e2", "performance-data/v5-analysis/worst-cpu-contenders.json",
                  "同慢帧GC、导航、运行时线程有执行，未见长Runnable饥饿。"),
         evidence("e3", "performance-data/v5-analysis/cpu-freq.json",
                  "CPU0..5采样1.90..2.61GHz；CPU6..7为0.768..1.498GHz。"),
         evidence("e4", "performance-data/budget-analysis/cpu-distribution.json",
                  "后续App执行仍以CPU4/5为主。"),
     ],
     "从Running/R/R+/S区分预算损失，核对同窗口竞争线程、执行核与频率。"
     "旧普通慢帧Running25.013ms/R0.332ms，新最慢帧R0.443ms；主要损失不是Runnable。"
     "没有GPU频率/温度闭环，不能归因降频或ROM。",
     ["保留线程归属和后台工作边界；有新饥饿证据时再检查优先级/队列。"],
     ["没有完整runqueue长度、CPU affinity或温度证据；不能声称所有调度因素已排除。"],
     {"cpu_sched": {"sched_latency_ms": 1.272396,
                    "thread_state_summary": "Running and sleeping dominate; short Runnable segments",
                    "runqueue": "No complete runqueue depth evidence"}}),
    ("spatial_audio", "spatial_audio_perf", "background_noise", "api_service_path",
     "当前原生音频选择与用途相符，帧内SoundPool较短，较长服务操作位于音频工作线程。",
     [
         evidence("e1", "performance-data/v5-analysis/main-audio.json",
                  "帧驱动线程SoundPool.play179次，max0.234ms。"),
         evidence("e2", "performance-data/v5-critical-slice-inventory.json",
                  "createTrack/start较长调用在SoundPool线程，循环操作在WastelandMusic。"),
     ],
     "结合GameAudio/LoopAudioDriver源码核对：一个20流SoundPool、17个预加载短音，"
     "6个专用MediaPlayer循环音轨；MediaPlayer准备/控制/验证/释放在专用线程。"
     "资源为本地生成WAV，未引入ObjectAudioComponent/HRTF或40控制器上限问题。"
     "SoundPool.play在帧内但最大0.234ms，音频服务热点没有阻塞主帧的依赖证据。"
     "完成的V5交火/任务日志audioFailures与loopFailures均0。",
     ["保持短音预加载、专用循环播放器和有界音量提交。"],
     ["未测耳端播放延迟；后续A/B窗口没有相同SoundPool细粒度标签，不能把缺失当成零调用。"],
     {"spatial_audio": {"api_type": "SoundPool + MediaPlayer",
                        "usage": "17 short cues; 6 dedicated looping tracks",
                        "instance_count": "1 SoundPool(max20 streams), 6 MediaPlayer",
                        "lifecycle_issues": "No observed playback failure in bounded checks",
                        "need_benchmark": False}}),
]
outputs = []
registry = []
for topic, issue, role, closure, root, refs, investigation, actions, limits, ext in specs:
    header = {
        "issue_type_key": issue, "protocol": topic, "activated_subprotocols": [],
        "activation_context": {"temporal_overlap": True, "dependency_relation": "direct",
                               "evidence_refs": [f"{topic}:{item['id']}" for item in refs]},
        "topic_state": {"activation": "activated", "execution": "completed_with_evidence_gap",
                        "closure_level": closure, "causal_role": role},
        "controller_handoff": [],
        "time_window": window, "symptoms": "用户反馈性能变差；真机脚本交火出现持续Late Frame。",
        "target": {"package": "com.pico.demo.wasteland", "threads": [".demo.wasteland", "Spatial_Main", "Eng-Render"]},
        "evidence": refs, "conclusion_level": "candidate", "root_cause": root,
        "next_actions": actions, "limitations": limits + common_limits,
        "extensions": {**ext, "knowledge_base": kb},
    }
    sections = [
        ("① Symptom", header["symptoms"]),
        ("② Existing Evidence", "\n\n".join(f"{item['id']}: {item['one_line_finding']} (`{item['location']}`)" for item in refs)),
        ("③ Investigation Process", investigation),
        ("④ Root Cause Judgment", "candidate — " + root),
        ("⑤ Actions (Optimization/Troubleshooting)", "\n".join("- " + action for action in actions)),
        ("⑥ Limitations and Next Validation", "\n".join("- " + limit for limit in header["limitations"])),
    ]
    path = topics / f"output-{topic}.md"
    path.write_text("---\n" + yaml.safe_dump(header, allow_unicode=True, sort_keys=False) +
                    "---\n\n" + "\n\n".join(f"## {name}\n\n{text}" for name, text in sections) + "\n")
    outputs.append({"topic": topic, "file": f"./topic-result/output-{topic}.md", "conclusion_level": "candidate"})
    registry.append({"topic": topic, "activation_decision": "activated", "execution_status": "executed",
                     "status": "executed", "dependency_relation": "direct",
                     "evidence_refs": [f"{topic}:{item['id']}" for item in refs],
                     "reason": "完成可执行证据下钻；原因优先级在完整六项复核后计算。"})

root = "当前持续超预算位于GPU/运行时消费路径；App本地更新峰值与GC重叠影响部分帧，静态投影削减未改善帧数。"
summary = {
    "header": {"app_package": "com.pico.demo.wasteland", "app_name": "废土矿区 V5",
               "analysis_time": "2026-10-21", "trace_file": "v5-shadow-full.perfetto-trace",
               "device_model": "B3110 / PB331XPGL6040019G",
               "os_version": "6.3.0-202610200409-NIGHTLY-user-swan-b17166"},
    "issue_type_key": [spec[1] for spec in specs], "activated_subprotocols": [spec[0] for spec in specs],
    "conclusion_level": "candidate", "root_cause": root,
    "topic_outputs": outputs, "routing_registry": registry,
    "pending_leads": [], "execution_queue": [],
    "directions_to_extend": [
        {"signal": "GPU持续超预算", "missing": "pass级GPU时间、频率、温度与2D/3D分离对照"},
        {"signal": "偶发更新/GC长等待", "missing": "Kotlin采样与主线程阻塞栈"},
    ],
    "next_actions": [
        {"priority": "P0", "action": "正常模式保留完整投影；不把56%投影三角数下降宣称为FPS优化。",
         "sources": ["./topic-result/output-spatial-engine.md"]},
        {"priority": "P1", "action": "下一轮针对GPU pass、3D材质/蒙皮与2D合成做单变量实验。",
         "sources": ["./topic-result/output-spatial-engine.md"]},
        {"priority": "P1", "action": "用采样和阻塞栈定位App更新峰值与分配来源。",
         "sources": ["./topic-result/output-app_jank.md", "./topic-result/output-memory.md"]},
    ],
    "limitations": [*common_limits, *comparison["limits"],
                    "本轮没有达到稳定VR帧预算，不具备性能修复完成或上架质量的证据。"],
    "extensions": {"knowledge_base": kb},
}
table = "\n".join(f"| {item['topic']} | [{item['topic']}]({item['file']}) | candidate | {specs[i][4]} |"
                  for i, item in enumerate(outputs))
body = f"""# Final Summary

## A. Topic Output Index

| Topic | Output | Level | Finding |
| --- | --- | --- | --- |
{table}

## B. Cross-Topic Evidence-Chain Analysis

{root}

完整投影/预算分支的30秒采集均有898个SPR帧，GPU均值27.080/26.895ms。
投影三角数2232601/975291，减少56.3%；GPU变化约0.7%，不足以证明可感知收益。
正常模式因此保留完整投影；预算仅为显式Debug探针。
report-data.json已显式指定App包名，App帧桶不再误用XRFetch运行时包名。
所有帧统计排除嵌套resynced doFrame；绝对ns锚点以TEXT读取。

App旧窗口另有25.409ms的Running帧，本地更新17.986ms，说明独立的应用峰值仍需定位。
Binder的callee/服务方法/状态已下钻，音频调用归属和生命周期已检查，调度状态以短Runnable为主。
GC与慢帧重叠尚缺主线程阻塞栈，不能认定为全部卡顿原因。
六项registry终态均为executed；末轮sweep没有未执行的可执行新线索。

## C. Directions to Extend

- GPU：缺pass级时间、GPU频率/温度和2D/3D分离证据。
- App/GC：缺Kotlin采样与主线程阻塞栈。
- PerformanceKB：当前工具目录未提供后端，使用不可用后端的保守fallback。

## D. Final Action Plan

- P0：恢复正常完整投影；保留已验证的视点、敌人、场景与资源准备改动。
- P1：定位GPU具体pass及应用更新峰值；取得稳定旧包基线后才能评价相对回退。
- 性能仍未满足稳定VR预算；没有声称达到《无主之地3》质量或可上架。
"""
(session / "final-summary.md").write_text("---\n" + yaml.safe_dump(summary, allow_unicode=True, sort_keys=False) +
                                          "---\n\n" + body)
print("Wrote 6 topic outputs, complete registry, final-summary.md and normal-branch report-data.json")
