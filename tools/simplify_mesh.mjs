import fs from 'node:fs';
import { MeshoptSimplifier } from '../.tools/meshopt/node_modules/meshoptimizer/meshopt_simplifier.js';

await MeshoptSimplifier.ready;
const request = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const positions = new Float32Array(request.positions.flat());
const attributes = new Float32Array(request.attributes.flat());
const indices = new Uint32Array(request.indices);
const [result, error] = MeshoptSimplifier.simplifyWithAttributes(
  indices, positions, 3, attributes, 5,
  request.attributeWeights ?? [0.1, 0.1, 0.1, 0.2, 0.2], null,
  Math.min(indices.length, request.triangles * 3), request.targetError ?? 0.012,
  request.options ?? ['Permissive', 'PreserveFolds']
);
fs.writeFileSync(process.argv[3], JSON.stringify({ indices: Array.from(result), error }));
