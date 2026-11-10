import fs from 'node:fs/promises';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
const output = 'artifacts/2026-10-22-visual/workflow';
const run = 'run-cf36c1b8-d548-424c-8322-1b59cb684173';
const client = new Client({ name: 'wasteland-material-save', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/.nvm/versions/node/v22.23.2/bin/pico-cli',
  args: ['editor:bootstrap'], stderr: 'inherit',
  env: { ...process.env, SPATIAL_CRAFT_BOOTSTRAP_MODE: 'cloud',
    SPATIAL_CRAFT_TOOL_REGISTRY_URL: 'https://is.snssdk.com/service/settings/v3/?app=1&caller_name=x_bird_mcp&config_key=mcp_tools',
    SPATIAL_CRAFT_DEPLOY_REGION: 'cn' },
});
function unwrap(value) {
  if (value.structuredContent) return unwrap(value.structuredContent);
  if (typeof value.result === 'string') return value.result;
  if (!value.content) return value;
  const text = value.content.find(c => c.type === 'text')?.text ?? '';
  try { return unwrap(JSON.parse(text)); } catch { return text; }
}
try {
  await client.connect(transport, { timeout: 60000 });
  await client.callTool({ name: 'get_editor_workflow_status', arguments: { run_id: run } });
  for (const file of ['primitive-steel-opaque', 'primitive-material-cream', 'primitive-material-orange']) {
    const text = unwrap(JSON.parse(await fs.readFile(`${output}/${file}.json`, 'utf8')));
    const entries = JSON.parse(text.slice(text.indexOf('[')));
    for (const entry of entries) {
      const data = entry.result.data;
      if (data.resolved_properties.opacity !== 1 ||
          data.resolved_properties.blending_mode !== 'OPAQUE') throw new Error('Non-opaque preview');
      const workspace = unwrap(await client.callTool({ name: 'get_editor_workspace', arguments: {} }));
      const name = entry.entity_path.split('/').at(-1);
      const result = await client.callTool({ name: 'execute_editor_operation', arguments: {
        operation_id: `save_v7_material_${name}`,
        expected_scene_fingerprint: workspace.sceneFingerprint,
        tool: 'commit_material_preview',
        arguments: { preview_id: data.preview_id,
          output_usd_path: `Sources/Materials/V7_${name}.usda` },
      } }, undefined, { timeout: 60000 });
      await fs.writeFile(`${output}/commit-${name}.json`, JSON.stringify(result, null, 2));
      if (result.isError) throw new Error(`Commit failed: ${name}`);
      console.log(`MATERIAL_SAVED ${name}`, true);
    }
  }
} finally { await client.close(); }
