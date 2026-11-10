import fs from 'node:fs/promises';
import path from 'node:path';
import readline from 'node:readline';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';

const project = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const evidence = path.join(project, 'artifacts/2026-10-15-v3/mcp');
await fs.mkdir(evidence, { recursive: true });
const directory = '/Users/bytedance/Library/Application Support/Spatial Editor/mcp/instances';
const live = [];
for (const file of await fs.readdir(directory)) {
  if (!file.endsWith('.json')) continue;
  try {
    const value = JSON.parse(await fs.readFile(path.join(directory, file), 'utf8'));
    process.kill(value.pid, 0);
    live.push(value);
  } catch {}
}
if (live.length !== 1) throw new Error(`Expected exactly one live Editor, found ${live.length}`);
const editor = live[0];
const client = new Client({ name: 'wasteland-v3-meshy', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/Library/PICO/sdk/6.1/editor/Spatial Editor.app/Contents/Resources/plugins/mcp/launcher/run_mcp.sh',
  args: [], env: { ...process.env, EDITOR_HOST: editor.host, EDITOR_PORT: String(editor.port),
    EDITOR_INSTANCE_PID: String(editor.pid) }, stderr: 'inherit',
});
function unwrap(result) {
  let value = result.structuredContent?.result ?? result.content?.filter(c => c.type === 'text').map(c => c.text).join('\n') ?? result;
  if (typeof value === 'string') {
    try { return JSON.parse(value); } catch {}
    const start = value.indexOf('{');
    if (start >= 0) { try { return JSON.parse(value.slice(start)); } catch {} }
  }
  return value;
}
try {
  await client.connect(transport, { timeout: 45000, maxTotalTimeout: 45000 });
  const listed = process.env.EDITOR_MCP_SKIP_TOOL_LIST === '1'
    ? { tools: JSON.parse(await fs.readFile(path.join(evidence, 'tools.json'), 'utf8')) }
    : await client.listTools();
  if (process.env.EDITOR_MCP_SKIP_TOOL_LIST !== '1')
    await fs.writeFile(path.join(evidence, 'tools.json'), JSON.stringify(listed.tools, null, 2));
  console.log(JSON.stringify({ ready: true, editorPid: editor.pid, port: editor.port,
    tools: listed.tools.map(t => t.name) }));
  for await (const line of readline.createInterface({ input: process.stdin })) {
    if (!line.trim()) continue;
    const request = JSON.parse(line);
    if (request.quit) break;
    try {
      const result = await client.callTool({ name: request.name, arguments: request.arguments ?? {} },
        undefined, { timeout: 45000, maxTotalTimeout: 45000 });
      const file = path.join(evidence, `${request.id}.json`);
      await fs.writeFile(file, JSON.stringify(result, null, 2) + '\n');
      console.log(JSON.stringify({ id: request.id, isError: result.isError, result: unwrap(result) }));
    } catch (error) {
      console.log(JSON.stringify({ id: request.id, error: error.message }));
    }
  }
} finally { await client.close(); }
