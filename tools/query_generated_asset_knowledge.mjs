import fs from 'node:fs/promises';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
const output = 'artifacts/2026-10-22-v8/knowledge';
await fs.mkdir(output, { recursive: true });
const client = new Client({ name: 'wasteland-generated-assets', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/.nvm/versions/node/v22.23.2/bin/pico-cli',
  args: ['knowledge:server'], stderr: 'inherit',
});
try {
  await client.connect(transport, { timeout: 60000, maxTotalTimeout: 60000 });
  const tools = await client.listTools();
  await fs.writeFile(`${output}/tools.json`, JSON.stringify(tools, null, 2));
  const result = await client.callTool({ name: 'query_graph', arguments: {
    question: 'Exact public Spatial SDK 6.2.6 Kotlin: load generated glTF2 GLB with a skinned surface node separate from Rig/Root joint hierarchy, use findSkeletonRootEntity and SkeletonPoseComponent getJointNames/getJointTransforms for an animated muzzle socket. Which entity is the public animation playback owner? Are joint transforms parent-local and in meters? What are joint-parent metadata APIs? Main-thread playback, resource global ownership and clone sharing semantics. AssetBundle.load/loadModelSuspend and selective named subentity loading. Need exact public documentation paths; distinguish version6.1 references from6.2.6, do not assume an older version proves6.2.6 semantics.',
    mode: 'bfs', depth: 2, token_budget: 4500,
  } }, undefined, { timeout: 60000, maxTotalTimeout: 60000 });
  await fs.writeFile(`${output}/generated-animation-api.json`, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} finally { await client.close(); }
