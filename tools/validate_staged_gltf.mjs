import fs from 'node:fs/promises';
import path from 'node:path';
import validator from '../.tools/v8-gltf-validation/node_modules/gltf-validator/index.js';

for (const input of process.argv.slice(2)) {
  const bytes = await fs.readFile(input);
  const result = await validator.validateBytes(new Uint8Array(bytes), {
    uri: path.basename(input), maxIssues: 100,
  });
  await fs.writeFile(path.join(path.dirname(input), 'gltf-validation.json'),
    JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ file: input, errors: result.issues.numErrors,
    warnings: result.issues.numWarnings }));
  if (result.issues.numErrors || result.issues.numWarnings) process.exitCode = 1;
}
