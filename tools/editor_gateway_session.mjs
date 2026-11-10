import fs from 'node:fs/promises';
import path from 'node:path';
import readline from 'node:readline';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';

const output = path.resolve(process.argv[2]);
await fs.mkdir(output, { recursive: true });
const client = new Client({ name: 'wasteland-art-workflow', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/.nvm/versions/node/v22.23.2/bin/pico-cli',
  args: ['editor:bootstrap'],
  env: { ...process.env, SPATIAL_CRAFT_BOOTSTRAP_MODE: 'cloud',
    SPATIAL_CRAFT_TOOL_REGISTRY_URL: 'https://is.snssdk.com/service/settings/v3/?app=1&caller_name=x_bird_mcp&config_key=mcp_tools',
    SPATIAL_CRAFT_DEPLOY_REGION: 'cn' },
  stderr: 'inherit',
});
function unwrap(result) {
  if (result.structuredContent) return result.structuredContent;
  const text = result.content?.filter(c => c.type === 'text').map(c => c.text).join('\n');
  if (text) { try { return JSON.parse(text); } catch { return text; } }
  return result;
}
const authTokens = new Map();
function findAuth(value) {
  if (typeof value === 'string') {
    try { return findAuth(JSON.parse(value)); } catch {}
    return value.match(/auth_token parameter:\s*([A-Za-z0-9._-]{16,})/)?.[1] ??
      value.match(/auth_token["'\s]*[:=]\s*["']?([A-Za-z0-9._-]{16,})/)?.[1];
  }
  if (!value || typeof value !== 'object') return undefined;
  if (typeof value.auth_token === 'string') return value.auth_token;
  for (const child of Object.values(value)) {
    const token = findAuth(child);
    if (token) return token;
  }
}
function redact(value) {
  let text = JSON.stringify(value);
  for (const token of authTokens.values()) text = text.replaceAll(token, '[AUTH_TOKEN_IN_MEMORY]');
  return JSON.parse(text);
}
try {
  await client.connect(transport, { timeout: 60000, maxTotalTimeout: 60000 });
  const listed = await client.listTools();
  await fs.writeFile(path.join(output, 'gateway-tools.json'), JSON.stringify(listed.tools, null, 2));
  console.log(JSON.stringify({ ready: true, tools: listed.tools.map(t => t.name) }));
  for await (const line of readline.createInterface({ input: process.stdin })) {
    if (!line.trim()) continue;
    let request = JSON.parse(line);
    if (request.file) request = JSON.parse(await fs.readFile(request.file, 'utf8'));
    if (request.quit) break;
    try {
      if (request.useAuth) {
        const token = authTokens.get(request.useAuth);
        if (!token) throw new Error(`No in-memory authorization for ${request.useAuth}`);
        request.arguments.arguments.auth_token = token;
      }
      const result = await client.callTool({ name: request.name, arguments: request.arguments ?? {} },
        undefined, { timeout: 60000, maxTotalTimeout: 60000 });
      const token = findAuth(unwrap(result));
      if (token) authTokens.set(request.id, token);
      const file = path.join(output, `${request.id}.json`);
      await fs.writeFile(file, JSON.stringify(redact(result), null, 2));
      const observation = unwrap(result);
      console.log(JSON.stringify(redact({ id: request.id, file, isError: result.isError,
        authInMemory: !!token, runId: observation?.runId, current: observation?.current,
        next: observation?.next?.arguments, error: observation?.error,
        outcome: observation?.result?.outcome })));
    } catch (error) { console.log(JSON.stringify({ id: request.id, error: error.message })); }
  }
} finally { await client.close(); }
