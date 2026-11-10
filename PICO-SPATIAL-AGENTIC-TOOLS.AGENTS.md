# PICO Spatial Agentic Tools Plugin Guidance

This installed plugin provides skills and references for AI coding agents: Claude Code, Cursor, Codex, GitHub Copilot, Trae CLI, OpenCode V2, CodeBuddy Code, Qoder CLI, Antigravity CLI, and Grok CLI.

## Conditional Environment Pre-Flight

Run `pico-env-doctor` before environment-dependent execution tasks: running `pico-cli`, querying `pico-dev-knowledge` MCP, installing/updating plugin host integration, or starting emulator/device workflows. It is not a universal gate for purely local code reading, architecture discussion, static code edits, or project analysis that does not depend on live local tooling.

Record the result once per host session and reuse it when the workspace, host, and tooling have not changed. Re-run only when setup changed, a new failure signal appears, or the user asks to re-verify.

## Available Skills

Installed Skill names: `porting-android-app`, `spatial-app-onboarding`, `spatial-design-to-app`, `pico-spatial-app-designer`, `spatial-sdk-guideline`, `spatial-app-dev-workflow`, `spatial-sdk-update`, `spatial-editor`, `spatial-sdk-scene-builder`, `pico-env-doctor`, `pico-cli`, `pico-workflow-integrator`, `spatial-emulator-usage`, `spatial-app-performance-analysis`, `spatial-app-performance-analysis-ext`, `spatial-ui-ability`, `spatial-ui-design-style`, `spatialml`, `spatialml-debugging`.
Use the Task Routing rules below to select and load the matching Skill instructions.

| Workflow Skill             | When to use                                               |
| -------------------------- | --------------------------------------------------------- |
| `pico-workflow-integrator` | Integrate or migrate an existing Workflow implementation. |

## Task Routing

- **Existing Editor handoff takes precedence over 3D authoring routes.** If an existing Spatial SDK app has a co-located `<name>.bundle` and `<name>.scenes.json` and the request is to integrate, load, or validate that authored scene in the app, enter `spatial-app-dev-workflow` as the enclosing workflow. Inspect the files before selecting any 3D subskill. Do not activate `spatial-editor` to recreate or repackage existing content. Use `spatial-sdk-scene-builder` only as a subordinate step when the integration also needs Kotlin Entity hierarchy, placement, or loading code; return to the app workflow for the build and APK check. If the request explicitly changes authored content, route that new authoring step to `spatial-editor` and then resume app integration.
- For Kotlin SpatialML service logs, `debug.pico.spatialml.debug`, operator runtime failures,
  zero/stale tensors, or explicit global tensor inspection, use `spatialml-debugging`
  (`skills/spatialml-debugging/SKILL.md`). For Unity, use the Unity plugin's corresponding skill.

- **Sole, top-priority routing criterion for app generation — has the user already provided an executable design?** For any request to create or generate a usable app that carries any feature, page, or business flow, decide the route by one question only: did the user supply an executable design **with the request** — a visual asset (Figma URL, screenshot, mockup) **or** an explicit design package/structured design spec (at least information architecture + page structure + state model)?
  - **No** → route to the unified `spatial-design-to-app` workflow. For `intent_only`, start the registered Workflow; its first `feature_definition` caller-agent Handoff writes a short non-empty `.scratch/intent-brief.md`. Complete requirements are preserved without added scope, while only missing goal, capability, or flow details receive minimal explicit assumptions. Its mandatory `design` Handoff then invokes `pico-spatial-app-designer` to produce and accept a JSON-first design package before code generation. This holds even when the target directory is empty or the app is new, and regardless of whether the prompt says "create", "implement directly", or contains no "design" keyword.
  - **Yes** → route to `spatial-design-to-app`, which consumes the provided visual asset or design package/spec directly without rerunning the designer gate.
  - Do not decide this by application complexity, keyword presence, or design depth — only by whether an executable design is already provided. `spatial-app-onboarding` is only for "just an empty scaffold / first runnable demo with no product feature described", or as a scaffold-only substep called by `spatial-design-to-app`.
- Product-specific visual/codegen requests outrank onboarding. If the prompt includes any Figma URL, screenshot, mockup, visual reference image(s), multi-page product UI, PRD, or visual-fidelity requirement, route to `spatial-design-to-app` even when the target directory is empty or the app is new. `spatial-app-onboarding` may only be called later as a scaffold-only substep after `spatial-design-to-app` has resolved the evidence, container, and window model.
- **Existing-app UI/function tie-breaker:** route changes that add or remove visible controls, or alter product layout, visual design/fidelity, panel hierarchy, or the container/window model, to `spatial-design-to-app`. Route behavior-only changes that preserve the visible UI and spatial structure, such as search/filter/data/business logic, interaction behavior, SDK/ECS integration, and crash or functional fixes, to `spatial-app-dev-workflow`. Select one primary workflow; do not chain the two by default.
- Use `porting-android-app` when a traditional 2D Android app must be redesigned into a PICO Spatial app.
- Use `spatial-app-onboarding` only for create/bootstrap/scaffold/quickstart requests that need just an empty scaffold or a first runnable demo with **no** described product feature, page, or business flow. The moment a request names any application feature/page/business flow, it is an app-generation request that must go to `spatial-design-to-app`. `spatial-app-onboarding` is also a scaffold substep called by `spatial-design-to-app` after its executable-design gate, or as an explicitly featureless parent scaffold substep.
- Use `spatial-sdk-guideline` for focused SDK API guidance, implementation patterns, and runtime debugging inside a PICO Spatial SDK project. Use its scene-surface-placement flow for attaching content to detected real-world surfaces. Its `Investigation Order` and `SDK Development Safety Rules` sections define knowledge-gathering and safety constraints for all SDK work.
- Use `spatial-app-dev-workflow` for iterative post-onboarding feature work: inspect project-local `AGENTS.md`, implement one requirement, build, install/launch, capture evidence, inspect crash logs, and repair before moving on. When a step needs authored spatial content, activate `spatial-editor`; when editor handoff outputs are present, consume them directly.
- Use `spatial-sdk-scene-builder` for new code-owned 3D behavior inside an existing app, or for code-owned Entity work after an accepted design and scaffold exist. It operates the public root Workflow through `start_3d_generation_workflow` with `backendPreference="codegen"`. Especially for `sense` or tracking-driven features, require an ECS-first plan.
- Use `spatial-design-to-app` as the single app-generation workflow when the user wants to create or substantially update a PICO Spatial app from Figma, screenshot/mockup, PRD, intent, hybrid inputs, or a bounded panel patch. Start the registered `spatial-design-to-app` pico-cli Workflow for `intent_only`; other input modes continue through the skill's source-specific routing. Its `workflow.json` conditionally invokes `pico-spatial-app-designer`, consumes accepted `design-spec.json` facts directly, resolves needed APIs through `spatial-ui-ability`, and enforces design-style plus JSON-to-App fidelity during verification.
- Use `pico-spatial-app-designer` directly when the requested deliverable is design-only: design, review, repair, or produce a PICO Spatial app design package. It writes `design-spec.json` first and renders `preview.html` from that IR.
- **No-visual, no-design-package app requests are an entry-level routing rule.** For an `intent_only` request, start the registered `spatial-design-to-app` Workflow. Its first `feature_definition` caller-agent Handoff writes the lightweight `.scratch/intent-brief.md`; its mandatory `design` Handoff then invokes `pico-spatial-app-designer` and consumes the accepted JSON through the design-package bridge before code generation. Substantial `product_doc` inputs enter the Designer directly without the intent-brief step. An executable design counts as "already provided" only when the **user** supplied it with the request.
- Use `spatial-sdk-update` when the project is already Spatial/MR and needs SDK/toolchain/version alignment or deprecated-API migration.
- Use `spatial-editor` when the task must change or inspect real Spatial Editor state, or when a workflow step needs editor-authored content. Read its `contracts.md` before submissions and `recovery.md` only after rejection, interruption, blocker, or user-decision results. If both `spatial-editor` and `spatial-sdk-scene-builder` apply, use Spatial Editor for authored content and Scene Builder for Kotlin Entity integration.
- Use `pico-env-doctor` before live environment-dependent work and whenever the environment is suspect: `pico-cli` missing/outdated, plugin/skills not loading, `pico-dev-knowledge` absent or disconnected, or commands failing with missing dependencies.
- Use `pico-cli` when the task is about generic CLI usage: choosing a command family, discovering help/version/setup commands, understanding output formats, targeting devices, or first-pass troubleshooting.
- Use `spatial-emulator-usage` when the task becomes an emulator/device workflow: preparing the machine, creating/starting a PICO emulator, checking devices, installing/launching APKs, diagnosing app startup crashes or flash exits, moving files, collecting screenshots/recordings, reading logcat, or cleaning up resources. A request to install, launch, and diagnose a crashing APK routes here even when it mentions a physical device rather than an emulator.
- Use `spatial-app-performance-analysis` to diagnose Spatial App performance on a real PICO device — stutter, frame drops, high CPU/GPU load, or Perfetto Trace analysis via `pico-cli perf`.
- Use `spatial-app-performance-analysis-ext` as an extension when the task concerns app power/energy/battery drain, heat/thermal pressure, battery life, Client vs SpatialRuntime attribution, or RenderDoc/Vulkan XML joint analysis with Perfetto/FastPerf traces. Use the base skill first; this supplements it.
- Use `spatial-ui-ability` for specific SpatialUI spatial capability snippets: gestures, Vibrant, hover, `windowConstraints`, `backgroundMaterial`, depth layout, `zOffset`, `rotate3D`, `scale3D`, or Augment-style windows.
- Use `spatial-ui-design-style` for SpatialUI application-side design consistency: PicoTheme wrapping, color/typography roles, preferring built-in components, or custom Compose UI behaving like native SpatialUI.
- Use `spatialml` for Kotlin Spatial SDK requests that explicitly mention SpatialML, SecureMR, OpenMR, a custom pipeline/package, Pipeline Zoo, or LiteRT/TFLite. Do not trigger it for generic AI features without model inference.
- Unity SpatialML belongs to the external **PICO Unity Agentic Tools** plugin. Hand the complete request to that plugin's `spatialml` skill; do not apply this plugin's Kotlin workflow. If the Unity plugin is unavailable, report `BLOCKED` with plugin installation and host-restart guidance.
- `knowledge-query` for cross-platform Q&A via subagent. `pico-dev-knowledge` MCP supplements the current platform's agent context.

## Spatial Editor Activation

Spatial Editor is the default path for new or revised editor-authored 3D content. Activate `spatial-editor` when a request needs editor-created scene objects or assets, visual composition or tuning, materials, lighting, effects, or a new Editor package for app integration. Users do not need to mention Spatial Editor explicitly. An existing `.bundle` and `.scenes.json` pair ready for app consumption follows the first Task Routing rule instead.

Within an existing app, or after a new app has passed the executable-design gate, an explicit request for Kotlin/Spatial SDK Entity implementation routes to `spatial-sdk-scene-builder`, not as an unavailable-Editor fallback. Do not activate Spatial Editor solely for SDK/API explanation, Kotlin/Compose implementation, Gradle repair, emulator/device work, runtime debugging, or transform-only planning.

A 3D content-production step may skip Spatial Editor only when:

1. The user explicitly asks not to use Spatial Editor.
2. Runtime capability inspection shows Spatial Editor cannot satisfy any part of the requirement.
3. Spatial Editor is actually unavailable after reasonable recovery steps.

Do not infer exceptions 2 or 3 without runtime evidence. An idle, stopped, or non-recoverable backend status is not unavailable-editor evidence — activate `spatial-editor` and call `start_editor_workflow` first. Before a Handoff submission, read `spatial-editor/contracts.md`; after rejection or a user-decision result, read `spatial-editor/recovery.md`.

## Editor-As-Workflow-Step Responsibilities

| Step                     | Owner                                                  | When to call                                                                                                                                             | Output / handoff                                                                                                               |
| ------------------------ | ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Classify task            | Agent routing in this file                             | At the start of every request or before a new workflow step                                                                                              | Decide whether the current step needs onboarding, app code, planning, authored spatial content, UI, device work, or validation |
| App setup                | `spatial-app-onboarding`                               | Featureless empty scaffold/first-runnable demo, or the scaffold-only Build step delegated by `spatial-design-to-app` after its executable-design gate    | Spatial SDK app project and project-local `AGENTS.md`                                                                          |
| Kotlin Entity scene      | `spatial-sdk-scene-builder`                            | Existing-app Entity work, or a new app whose executable design has been accepted and scaffolded                                                          | Implemented Kotlin scene or focused placement with bounds/runtime evidence                                                     |
| Authored spatial content | `spatial-editor`                                       | A workflow step needs authored spatial content such as scene objects, prototypes, spatial scene construction, composition, effects, or visual inspection | Authored-content result, integration handoff paths when needed, or a clear blocker                                             |
| Bundle handoff           | `spatial-editor` skill workflow                        | Editor-authored content must be consumed by a Spatial SDK app or another downstream workflow                                                             | Co-located `<bundleName>.bundle` and `<bundleName>.scenes.json`                                                                |
| App consumption          | `spatial-app-dev-workflow` or SDK implementation skill | After authored content is available and app integration needs packaged files                                                                             | Read scenes sidecar first for scene list, entry scene, warnings, and inline per-scene container data                           |
| Runtime validation       | `spatial-app-dev-workflow` + `spatial-emulator-usage`  | After app code/assets are updated                                                                                                                        | Build, install/launch, screenshot/log evidence, and repair loop                                                                |

### Bundle Scenes Consumption Contract

- Treat `<bundleName>.scenes.json` as the first app-side entry point. Validate `schemaVersion == "0.1"`.
- Use `<bundleName>.bundle` for SDK bundle loading after both files are under `app/src/main/assets/`.
- Use `activeScenePath` as the initial scene when present, otherwise the first `scenes[]` entry.
- Treat the chosen scene's inline `container` as authoring-container guidance when the app runtime container is unknown.
- Verify `.bundle` and `.scenes.json` exist before changing app loading code.
- After building an app that consumes the handoff, run `pico-cli app bundle verify <apk>` before installation; compressed or missing bundle entries block delivery.

## MCP Guidance

- **`pico-dev-knowledge`** — Knowledge graph MCP for PICO Spatial App development. Preferred retrieval source for non-trivial Spatial SDK/API facts; broader and more frequently updated than bundled skill references.

  | Tool               | What it does                                                                                                                                                                                                            |
  | ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
  | `query_graph`      | Search the knowledge graph using natural language questions or keywords. Returns relevant nodes and context via BFS/DFS traversal. Supports `mode` (bfs/dfs), `depth` (1-6), and `token_budget` to control output size. |
  | `switch_workspace` | Hot-reload to a different version's knowledge data without restarting the server.                                                                                                                                       |

- **`pico-spatial-editor`** — The public 3D Workflow and Editor gateway. `spatial-sdk-scene-builder` uses `start_3d_generation_workflow`, `resume_3d_generation_workflow`, `get_3d_generation_workflow_status`, `wait_3d_generation_workflow`, and `cancel_3d_generation_workflow` with the Codegen backend. `spatial-editor` uses the compatible `start_editor_workflow`, `resume_editor_workflow`, `get_editor_workflow_status`, `wait_editor_workflow`, and `cancel_editor_workflow` facade, which fixes the Editor backend. Callers control only the root Run; Child selection and execution remain managed.

### External MCP prerequisites (not provisioned by this plugin)

`.mcp.json` declares only the servers listed above. Some skill routes additionally depend on an MCP server the user must configure in their own host:

| Capability                                                                                                              | Server                    | Required by                                                                                                     | If absent                                                                                                                                                           |
| ----------------------------------------------------------------------------------------------------------------------- | ------------------------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Figma extraction + D2C verification (`d2c_get_figma_data`, `d2c_download_icons`, `d2c_verify_code`, `d2c_cleanup_temp`) | `codin-d2c-figma-to-code` | `spatial-design-to-app` on the `visual_design` route; `spatial-ui-design-style` `d2c_verify_code` `ruleContext` | Re-route to a screenshot/mockup (`visual_reference`) or report BLOCKED — see `skills/spatial-design-to-app/SKILL.md` stage 1b. Never skip the verify hook silently. |

Check the live tool list before promising a route that needs one of these. If MCP tools are unavailable, continue with bundled references and clearly state which lookup could not be performed.

## Core Rules

- **Project-local authority wins.** The user's project-local `AGENTS.md`, Gradle files, manifests, source tree, and explicit requirements override generic plugin guidance.
- **Ground advice in the project's actual version.** Identify the Spatial SDK version from project-local declarations before giving API advice. Do not assume latest-version behavior applies to an older project.
- **Use public APIs only.** Do not recommend internal, hidden, unstable, or decompiled-only SDK APIs unless the user explicitly asks and the risk is stated. Do not fabricate SDK classes, methods, Gradle coordinates, or lifecycle behavior.
- **Verify before claiming success.** Do not claim an implementation works unless verified by build result, test result, emulator/device run, log evidence, screenshot, or direct code-path inspection.
- **SpatialUI is mandatory for generated apps.** Build all 2D UI with SpatialUI (`com.pico.spatial.ui.*`) wrapped in `PicoTheme`. Material/Material3 is forbidden.

## Example Routing

These examples select a skill only. Their subjects, quantities, relationships, distances, and visual properties are not defaults for the routed task.

- "Create a new PICO Spatial app from scratch" -> `spatial-app-onboarding` only for an empty scaffold or first-runnable demo with no product feature; a feature-bearing request with no user-provided design routes through `spatial-design-to-app`'s `pico-spatial-app-designer` gate.
- "Build a spatial app from this one-line idea / PRD, with no Figma or screenshot" -> the unified `spatial-design-to-app` workflow; for a one-line intent, its first `feature_definition` Handoff produces `.scratch/intent-brief.md`, then its `design` Handoff runs `pico-spatial-app-designer` before code generation. A substantial PRD enters the Designer directly; onboarding is not the feature workflow.
- "Use this Figma to redesign my app into a SpatialUI-based PICO Spatial app" -> `spatial-design-to-app` without a redundant design pass.
- "Scaffold an empty first-runnable planar demo, nothing else" -> `spatial-app-onboarding`.
- "After onboarding, add grab interaction and verify it in the emulator" -> `spatial-app-dev-workflow`.
- "The Editor handoff already has `.bundle` and `.scenes.json`; integrate it and validate runtime behavior" -> `spatial-app-dev-workflow`.
- "Should I use `Stage` or `WindowContainer`?" -> `spatial-sdk-guideline`.
- "Upgrade this project to the newest PICO Spatial SDK" -> `spatial-sdk-update`.
- "Create and package the authored scene described by the request" -> `spatial-editor`.
- "Create this complete scene using Kotlin Entities" -> `spatial-sdk-scene-builder`.
- "Create a Kotlin Entity scene app in this empty directory" -> `spatial-design-to-app` (including its executable-design gate), then scaffold-only `spatial-app-onboarding`, then `spatial-sdk-scene-builder` for the accepted code-owned scene.
- "Generate the assets in Editor, then place them dynamically in Kotlin" -> `spatial-editor` + `spatial-sdk-scene-builder`.
- "pico-cli is broken, or the plugin/MCP will not load" -> `pico-env-doctor`.
- "Which pico-cli command should I run?" -> `pico-cli`; "start the emulator and install this APK" -> `spatial-emulator-usage`.
- "Install this APK on the device, launch it, and diagnose why it immediately crashes" -> `spatial-emulator-usage`.
- "Integrate or migrate this existing Workflow implementation" -> `pico-workflow-integrator`.
- "Diagnose real-device frame drops with pico-cli perf and Perfetto" -> `spatial-app-performance-analysis`.
- "Attribute battery drain, thermal pressure, or RenderDoc assets" -> `spatial-app-performance-analysis` + `spatial-app-performance-analysis-ext`.
- "How do I add `spatialHoverEffect`?" -> `spatial-ui-ability`; "which `PicoTheme` roles should this component use?" -> `spatial-ui-design-style`.
- "Add SpatialML to this Kotlin app" -> `spatialml`, with the Kotlin SDK workflow first.
- "Add SpatialML to this Unity app" -> the PICO Unity Agentic Tools plugin's `spatialml` skill.
- "Inspect this `.tflite` model or adapt the closest Pipeline Zoo package" -> `spatialml`.
- "How do I install or update this plugin in my Host?" -> `pico-cli` or plugin README/setup guidance.
