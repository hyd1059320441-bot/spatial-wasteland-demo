import fs from 'node:fs/promises';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
const output = 'artifacts/2026-10-22-visual/workflow';
const client = new Client({ name: 'wasteland-color-probe', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/.nvm/versions/node/v22.23.2/bin/pico-cli',
  args: ['editor:bootstrap'], stderr: 'inherit',
  env: { ...process.env, SPATIAL_CRAFT_BOOTSTRAP_MODE: 'cloud',
    SPATIAL_CRAFT_TOOL_REGISTRY_URL: 'https://is.snssdk.com/service/settings/v3/?app=1&caller_name=x_bird_mcp&config_key=mcp_tools',
    SPATIAL_CRAFT_DEPLOY_REGION: 'cn' },
});
function unwrap(value) {
  if (typeof value === 'string') { try { return unwrap(JSON.parse(value)); } catch { return value; } }
  if (value.structuredContent) return unwrap(value.structuredContent);
  if (typeof value.result === 'string') return unwrap(value.result);
  if (!value.content) return value;
  return unwrap(value.content.find(c => c.type === 'text')?.text ?? '');
}
const target = '/Root/Batch_000/CeramicFront';
const usda = `#usda 1.0
(defaultPrim = "Root"
metersPerUnit = 1)
def Xform "Root" {
 def Material "Cream" (customData = { string materialType = "SHADER_GRAPH"
 string blendingMode = "OPAQUE"
 string cullingMode = "BACK"
 bool depthWrite = 1
 bool depthTest = 1 }) {
 token outputs:mtlx:surface.connect = </Root/Cream/Surface.outputs:out>
 def Shader "Color" {
 uniform token info:id = "ND_constant_color3"
 color3f inputs:value = (0.89,0.84,0.72)
 color3f outputs:out
 }
 def Shader "Surface" {
 uniform token info:id = "ND_pbr_surfaceshader"
 color3f inputs:baseColor.connect = </Root/Cream/Color.outputs:out>
 float inputs:roughness = 0.881
 float inputs:metallic = 0
 float inputs:opacity = 1
 token outputs:out
 }
 }
}
`;
async function operation(id, tool, args, materialCapture) {
  const workspace = unwrap(await client.callTool({ name: 'get_editor_workspace', arguments: {} }));
  const result = await client.callTool({ name: 'execute_editor_operation', arguments: {
    operation_id: id, expected_scene_fingerprint: workspace.sceneFingerprint,
    tool, arguments: args, ...(materialCapture ? { material_capture: materialCapture } : {}),
  } }, undefined, { timeout: 60000 });
  await fs.writeFile(`${output}/${id}.json`, JSON.stringify(result, null, 2));
  if (result.isError) throw new Error(`Failed ${id}`);
  return unwrap(result);
}
try {
  await client.connect(transport, { timeout: 60000 });
  await client.callTool({ name: 'get_editor_workflow_status', arguments: {
    run_id: 'run-cf36c1b8-d548-424c-8322-1b59cb684173' } });
  let validation = await operation('probe_v7_compiler_fixed_validate', 'validate_material_usda',
    { entity_path: target, usda_text: usda });
  if (typeof validation === 'string') validation = JSON.parse(validation.slice(validation.indexOf('{')));
  if (!validation.can_preview || validation.error_count) throw new Error('Validation rejected');
  await operation('probe_v7_compiler_fixed_preview', 'preview_material_usda',
    { entity_path: target, usda_text: usda });
  const capture = await operation('probe_v7_compiler_fixed_capture', 'capture_3d_scene',
    { capture_mode: 'offscreen', width: 640, height: 480, frame: 'none',
      camera_position: [2, 1.5, 4.5], camera_target: [0, 0.595, 1.02],
      container_fit_mode: 'none' }, { material_id: 'cream', target_entity_path: target });
  console.log(capture.screenshot.localPath);
} finally { await client.close(); }
