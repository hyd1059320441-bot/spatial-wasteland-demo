import fs from 'node:fs/promises';
import { Client } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';
const output = 'artifacts/2026-10-22-visual/knowledge';
await fs.mkdir(output, { recursive: true });
const client = new Client({ name: 'wasteland-visual', version: '1.0.0' });
const transport = new StdioClientTransport({
  command: '/Users/bytedance/.nvm/versions/node/v22.23.2/bin/pico-cli',
  args: ['knowledge:server'], stderr: 'inherit',
});
try {
  await client.connect(transport, { timeout: 60000 });
  const result = await client.callTool({ name: 'query_graph', arguments: {
    question: 'Spatial SDK public 6.2.6 Kotlin: MeshResource createTorus/createCylinder or custom mesh descriptor with vertices normals indices for pooled low-poly rotating rings and wind banners. PointLightComponent/SpotLightComponent constructor, intensity units, attenuationRadius and shadow flag; native particle emitSpreadingAngle orientation and simulationSpace LOCAL. Need public API docs, ECS lifecycle, resource ownership.',
    mode: 'bfs', depth: 2, token_budget: 4500,
  } }, undefined, { timeout: 60000 });
  await fs.writeFile(`${output}/visual-api.json`, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} finally { await client.close(); }
