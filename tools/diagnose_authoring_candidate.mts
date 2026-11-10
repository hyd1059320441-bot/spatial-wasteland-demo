import fs from 'node:fs/promises';
import { z } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/node_modules/zod/index.js';
import { sceneCandidateSubmissionSchema } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/src/foundation/editor/workflow/authoringContracts.ts';
import { assertManifestCoverage } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/src/foundation/editor/workflow/assetManifest.ts';
import { assertMaterialCaptureEvidence } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/src/foundation/editor/workflow/materialEvidence.ts';
import { interactionContractSchema } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/src/foundation/editor/interactionContracts.ts';
import { assertActiveEditorSession } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/workflow/public/spatial-editor-workflow/src/drivers/candidate-validation.ts';
import { inspectAuthoringScene } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/workflow/public/spatial-editor-workflow/src/drivers/authoring-workspace.ts';
import { sceneCandidateRefSchema } from '/Users/bytedance/Documents/ai/spatial-craft-dev-readonly/src/foundation/editor/workflow/authoringContracts.ts';
import { createHash } from 'node:crypto';

// Read-only diagnostics. Never calls the driver, seal, content store writes or grants.
const runDirectory = '/Users/bytedance/.pico/cli/workflow/runs/run-a7b2f39e-1d84-4575-8aab-7319a967ceed';
const read = async (p: string) => JSON.parse(await fs.readFile(p, 'utf8'));
const request = await read(process.argv[2]);
const { candidate, interactionContract } = request.arguments.input;
const { grant } = await read(`${runDirectory}/native-runtime/editor-v6/workspace-access.json`);
const state = await read(`${runDirectory}/native-runtime/editor-v6/author-scene-driver.json`);
const operation: any = Object.values(state.operations)[0];
const results: any[] = [];
async function check(name: string, action: () => any) {
  try { await action(); results.push({ name, pass: true }); }
  catch (e: any) { results.push({ name, pass: false, error: e.message }); }
}
await check('submission', () => sceneCandidateSubmissionSchema.parse(candidate));
await check('interactionContract', () => interactionContractSchema.parse(interactionContract));
await check('activeSession', () => assertActiveEditorSession(runDirectory, grant.runId, operation.input.session, 'EDITOR_AUTHORING'));
await check('grant', () => {
  if (grant.operationId !== Object.keys(state.operations)[0] || !['editing', 'capture'].includes(grant.mode)) throw Error('window closed');
});
const observation = await read(process.argv[3]);
const workspace = observation.structuredContent ?? JSON.parse(observation.content.filter((x: any) => x.type === 'text').map((x: any) => x.text).join('\n'));
const hierarchy: any[] = [];
function walk(value: any) {
  if (!value || typeof value !== 'object') return;
  if (typeof value.path === 'string') hierarchy.push({ path: value.path });
  for (const child of Object.values(value)) if (typeof child === 'object') walk(child);
}
walk(workspace);
await check('manifestCoverage', () => assertManifestCoverage(operation.input.authoring.assetManifest, candidate.inventory, hierarchy));
for (const target of operation.input.authoring.materialTargets ?? []) {
  for (const assetId of target.assetIds) {
    const binding = candidate.inventory.assetBindings.find((x: any) => x.assetId === assetId);
    for (const entityPath of binding?.entityPaths ?? []) {
      await check(`material:${entityPath}`, () => assertMaterialCaptureEvidence({
        grant, materialId: target.materialId, entityPath, sceneFingerprint: candidate.sceneFingerprint,
        operationIds: candidate.operationIds, evidence: candidate.evidence,
      }));
    }
  }
}
await check('lineage', () => {
  const ids = grant.operations.filter((x: any) => x.status === 'succeeded').map((x: any) => x.id);
  if (candidate.operationIds.some((x: string) => !ids.includes(x)) || ids.some((x: string) => !candidate.operationIds.includes(x))) throw Error('lineage mismatch');
});
const ref = (value: any) => {
  const content = JSON.stringify(value);
  const digest = createHash('sha256').update(content).digest('hex');
  return { path: `sidecars/v6/${digest}.json`, digest, bytes: Buffer.byteLength(content), mediaType: 'application/json' };
};
await check('candidateReference', () => sceneCandidateRefSchema.parse({
  schemaVersion: 'spatialcraft.editor-candidate/v6', runId: grant.runId, moduleId: 'module:author-scene',
  revision: 1, windowId: operation.windowId,
  intentDigest: createHash('sha256').update(JSON.stringify(operation.input.authoring)).digest('hex'),
  sceneFingerprint: candidate.sceneFingerprint, inventory: ref(candidate.inventory),
  operationLineage: ref({ operationIds: candidate.operationIds }), evidence: candidate.evidence.map(ref),
}));
await check('observerInspection', async () => {
  const scene = await inspectAuthoringScene(runDirectory, grant.runId);
  if (scene.sceneFingerprint !== candidate.sceneFingerprint) throw Error(`stale: ${scene.sceneFingerprint}`);
});
console.log(JSON.stringify({ results, grantMode: grant.mode, fingerprint: grant.sceneFingerprint }, null, 2));
