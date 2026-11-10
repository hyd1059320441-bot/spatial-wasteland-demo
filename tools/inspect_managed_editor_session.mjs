// Read-only inspection of the exact Editor already recorded by a managed Run.
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';

const descriptor = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
const output = path.resolve(process.argv[3]);
if (descriptor.state !== 'active') throw new Error('Managed Editor session is not active');
process.kill(descriptor.editorPid, 0);
const identity = execFileSync('ps', ['-o', 'lstart=', '-p', String(descriptor.editorPid)],
  { encoding: 'utf8' }).trim();
if (identity !== descriptor.processStartIdentity) throw new Error('Editor process identity changed');
const registry = JSON.parse(await fs.readFile(
  `/Users/bytedance/Library/Application Support/Spatial Editor/mcp/instances/${descriptor.editorPid}.json`,
  'utf8'));
if (registry.pid !== descriptor.editorPid || registry.port !== descriptor.editorPort ||
    registry.host !== descriptor.editorHost) throw new Error('Editor registry identity changed');
await fs.mkdir(output, { recursive: true });
const client = new Client({ name: 'wasteland-managed-readonly-inspection', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: path.resolve(descriptor.editorPath, '../..',
    'Resources/plugins/mcp/launcher/run_mcp.sh'),
  args: [],
  env: { ...process.env, EDITOR_HOST: descriptor.editorHost,
    EDITOR_PORT: String(descriptor.editorPort), EDITOR_INSTANCE_PID: String(descriptor.editorPid) },
  stderr: 'inherit',
});
try {
  await client.connect(transport, { timeout: 45000, maxTotalTimeout: 45000 });
  const tools = await client.listTools();
  await fs.writeFile(path.join(output, 'live-backend-tools.json'), JSON.stringify(tools, null, 2));
  const scene = await client.callTool({ name: 'get_scene_info', arguments: {
    detail: 'tree', max_depth: -1, include_transforms: true, include_bounds: true, limit: 0,
  } }, undefined, { timeout: 45000, maxTotalTimeout: 45000 });
  const file = path.join(output, 'live-backend-scene.json');
  await fs.writeFile(file, JSON.stringify(scene, null, 2));
  console.log(JSON.stringify({ sessionRunId: descriptor.runId, editorPid: descriptor.editorPid,
    file, isError: scene.isError }));
} finally {
  await client.close();
}
