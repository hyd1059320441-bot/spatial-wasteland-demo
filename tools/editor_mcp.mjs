import fs from 'node:fs/promises';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';

const launcher = '/Users/bytedance/Library/PICO/sdk/6.1/editor/Spatial Editor.app/Contents/Resources/plugins/mcp/launcher/run_mcp.sh';
const registryPath = '/Users/bytedance/Library/Application Support/Spatial Editor/mcp/instances/45323.json';
const registry = JSON.parse(await fs.readFile(registryPath, 'utf8'));
const client = new Client({ name: 'wasteland-v3-native-editor', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: launcher, args: [],
  env: { ...process.env, EDITOR_HOST: registry.host, EDITOR_PORT: String(registry.port),
    EDITOR_INSTANCE_PID: String(registry.pid) },
  stderr: 'inherit',
});
try {
  await client.connect(transport, { timeout: 45000, maxTotalTimeout: 45000 });
  const listed = await client.listTools();
  const action = process.argv[2] || 'list';
  if (action === 'list') {
    const selected = listed.tools.filter(t => /3d.*(model|generation)|generation.*(status|task)|scene_info/.test(t.name));
    console.log(JSON.stringify(selected, null, 2));
  } else {
    const request = JSON.parse(await fs.readFile(process.argv[3], 'utf8'));
    if (!listed.tools.some(t => t.name === request.name)) throw new Error(`Native MCP tool missing: ${request.name}`);
    const result = await client.callTool(request, undefined, { timeout: 30000, maxTotalTimeout: 30000 });
    if (process.argv[4]) await fs.writeFile(process.argv[4], JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result, null, 2));
  }
} finally {
  await client.close();
}
