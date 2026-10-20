// Khronos glTF Validator 2.0.0-dev.3.10 (Apache-2.0), external QA only.
const fs = require('fs');
const path = require('path');
const root = process.env.RF_GLTF_VALIDATOR;
if (!root) throw new Error('RF_GLTF_VALIDATOR must select pinned gltf-validator package');
const pkg = require(path.join(root, 'package.json'));
if (pkg.version !== '2.0.0-dev.3.10') throw new Error('Unexpected validator version');
const validator = require(root);
validator.validateBytes(new Uint8Array(fs.readFileSync(process.argv[2])), {
  uri: path.basename(process.argv[2]), maxIssues: 1000,
  externalResourceFunction: async () => { throw new Error('External URI forbidden'); }
}).then(report => process.stdout.write(JSON.stringify(report))).catch(error => {
  process.stderr.write(String(error)); process.exitCode = 1;
});
