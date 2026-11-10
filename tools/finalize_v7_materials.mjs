import fs from 'node:fs/promises';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
const output = 'artifacts/2026-10-22-visual/workflow';
const run = 'run-cf36c1b8-d548-424c-8322-1b59cb684173';
const client = new Client({ name: 'wasteland-final-materials', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/.nvm/versions/node/v22.23.2/bin/pico-cli',
  args: ['editor:bootstrap'], stderr: 'inherit',
  env: { ...process.env, SPATIAL_CRAFT_BOOTSTRAP_MODE: 'cloud',
    SPATIAL_CRAFT_TOOL_REGISTRY_URL: 'https://is.snssdk.com/service/settings/v3/?app=1&caller_name=x_bird_mcp&config_key=mcp_tools',
    SPATIAL_CRAFT_DEPLOY_REGION: 'cn' },
});
let lastArchive;
function unwrap(value) {
  if (typeof value === 'string') { try { return unwrap(JSON.parse(value)); } catch { return value; } }
  if (value.structuredContent) return unwrap(value.structuredContent);
  if (typeof value.result === 'string') return unwrap(value.result);
  if (value.content) return unwrap(value.content.filter(c => c.type === 'text').map(c => c.text).join('\n'));
  return value;
}
function parseResult(value) {
  if (typeof value !== 'string') return value;
  const start = Math.min(...['{', '['].map(ch => value.indexOf(ch)).filter(i => i >= 0));
  if (!Number.isFinite(start)) throw new Error(value);
  return JSON.parse(value.slice(start));
}
async function workspace() {
  const value = unwrap(await client.callTool({ name: 'get_editor_workspace', arguments: {} }));
  if (value.scene.scenePath !== 'Sources/Scenes/WastelandV7Visuals.usda')
    throw new Error('Wrong active scene');
  return value;
}
async function operation(id, tool, args, materialCapture) {
  const current = await workspace();
  const result = await client.callTool({ name: 'execute_editor_operation', arguments: {
    operation_id: id, expected_scene_fingerprint: current.sceneFingerprint,
    tool, arguments: args, ...(materialCapture ? { material_capture: materialCapture } : {}),
  } }, undefined, { timeout: 60000 });
  await fs.writeFile(`${output}/${id}.json`, JSON.stringify(result, null, 2));
  lastArchive = result.content?.map(c => {
    try { return JSON.parse(c.text)?.capture; } catch { return undefined; }
  }).find(Boolean);
  const value = unwrap(result);
  if (result.isError || (typeof value === 'string' && /^(Failed|Error)/.test(value)))
    throw new Error(`Failed ${id}: ${String(value).slice(0, 400)}`);
  return value;
}
function material(name) {
  return name.startsWith('Ceramic') ? 'cream' : name.startsWith('Guard') ? 'orange' : 'steel';
}
try {
  await client.connect(transport, { timeout: 60000 });
  await client.callTool({ name: 'get_editor_workflow_status', arguments: { run_id: run } });
  const current = await workspace();
  const parts = current.scene.hierarchy.filter(e => e.type === 'Cube');
  if (parts.length !== 21) throw new Error(`Expected 21 parts, found ${parts.length}`);
  if (process.argv[2] === 'views') {
    const views = [];
    for (const [label, camera, up] of [
      ['view_from_+X', [5.5, 0.65, 0]], ['view_from_-X', [-5.5, 0.65, 0]],
      ['view_from_+Y', [0, 6.15, 0], [0, 0, -1]],
      ['view_from_-Y', [0, -4.85, 0], [0, 0, 1]],
      ['view_from_+Z', [0, 0.65, 5.5]], ['view_from_-Z', [0, 0.65, -5.5]],
      ['front_three_quarter', [3.8, 2.5, 4.8]],
    ]) {
      const id = label.replace('+', 'plus').replace('-', 'minus');
      const value = await operation(`v7_frozen2_${id}`, 'capture_3d_scene', {
        capture_mode: 'offscreen', frame: 'none',
        camera_target: [0, 0.65, 0], camera_position: camera,
        ...(up ? { camera_up: up } : {}), container_fit_mode: 'none',
      });
      if (!lastArchive?.imageHash) throw new Error(`No archived capture ${label}`);
      views.push({ viewLabel: label, capturedAt: lastArchive.capturedAt,
        imagePath: lastArchive.imagePath, imageHash: lastArchive.imageHash, camera: value.camera });
      console.log(`FROZEN_CAPTURE ${label} ${lastArchive.imagePath}`);
    }
    await fs.writeFile(`${output}/v7-frozen-views.json`, JSON.stringify({ views }, null, 2));
  } else if (process.argv[2] !== 'capture') {
    for (const [id, color, roughness, metallic] of [
      ['steel', [0.24, 0.37, 0.41], 0.82, 0.15],
      ['cream', [0.89, 0.84, 0.72], 0.88, 0],
      ['orange', [0.82, 0.31, 0.095], 0.84, 0.08],
    ]) {
      const selected = parts.filter(e => material(e.name) === id);
      const result = parseResult(await operation(`v7_final_preset_${id}`, 'apply_material_preset', {
        entity_paths: selected.map(e => '/' + e.path.replace(/^\/+/, '')),
        preset: 'preview_surface', color, roughness, metallic, opacity: 1,
        transparent: false, blending_mode: 'OPAQUE', face_culling: 'BACK',
        depth_write: true, depth_test: true,
      }));
      for (const entry of result) {
        const data = entry.result.data;
        if (!data?.preview_id || data.resolved_properties.opacity !== 1)
          throw new Error(`Invalid ${entry.entity_path}`);
        const name = entry.entity_path.split('/').at(-1);
        await operation(`v7_final_commit_${name}`, 'commit_material_preview', {
          preview_id: data.preview_id, output_usd_path: `Sources/Materials/V7_${name}.usda`,
        });
        console.log(`MATERIAL_COMMITTED ${name}`);
      }
    }
  } else {
    const captures = [];
    for (const part of parts) {
      const c = part.bounds.center;
      const s = part.bounds.extent;
      const d = Math.max(s.x, s.y, s.z, 0.25) * 2.2;
      const target = [c.x, c.y, c.z];
      const capture = await operation(`v7_final_capture_${part.name}`, 'capture_3d_scene', {
        capture_mode: 'offscreen', width: 480, height: 360, frame: 'none',
        camera_target: target, camera_position: [c.x + d * 0.5, c.y + d * 0.35, c.z + d * 0.8],
        container_fit_mode: 'none',
      }, { material_id: material(part.name), target_entity_path: '/' + part.path.replace(/^\/+/, '') });
      captures.push({ materialId: material(part.name), targetEntityPath: '/' + part.path.replace(/^\/+/, ''),
        capture });
      console.log(`MATERIAL_CAPTURED ${part.name} ${capture.screenshot.localPath}`);
    }
    for (const [id, camera] of [
      ['front', [0, 1.8, 5.5]], ['rear', [0, 1.8, -5.5]],
      ['above', [0.2, 5.5, 0.3]], ['angled', [3.8, 2.5, 4.8]],
    ]) {
      const capture = await operation(`v7_final_scene_${id}`, 'capture_3d_scene', {
        capture_mode: 'offscreen', width: 960, height: 720, frame: 'none',
        camera_target: [0, 0.65, 0], camera_position: camera, container_fit_mode: 'none',
      });
      captures.push({ viewId: id, projection: 'perspective', capture });
    }
    await fs.writeFile(`${output}/v7-final-captures.json`, JSON.stringify(captures, null, 2));
    await fs.writeFile(`${output}/v7-final-workspace.json`, JSON.stringify(await workspace(), null, 2));
  }
} finally { await client.close(); }
