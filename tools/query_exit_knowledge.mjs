import fs from 'node:fs/promises';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
const output = 'artifacts/2026-10-21-exit-repair';
await fs.mkdir(output, { recursive: true });
const client = new Client({ name: 'wasteland-exit-repair', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/.nvm/versions/node/v22.23.2/bin/pico-cli',
  args: ['knowledge:server'], stderr: 'inherit',
});
try {
  await client.connect(transport, { timeout: 60000 });
  const listed = await client.listTools();
  await fs.writeFile(`${output}/knowledge-tools.json`, JSON.stringify(listed, null, 2));
  const result = await client.callTool({ name: 'query_graph', arguments: {
    question: 'Spatial SDK exact public 6.2.6: DefaultStage application close and exit to system launcher, LocalSpatialNavigator closeStage or closeApplication; left controller menu key event delivery, ControllerActionData menuButtonPressed, Compose BackHandler and KEYCODE_MENU. Need public API and lifecycle semantics.',
    mode: 'bfs', depth: 2, token_budget: 3500,
  } }, undefined, { timeout: 60000 });
  await fs.writeFile(`${output}/knowledge-query.json`, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} finally { await client.close(); }
