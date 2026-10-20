Exact frozen code: 2f18a4f5a87139c38ee388f6c70af33e0fb247f7.

Two independent archive creations and metadata records are byte-identical.
Each archive is1,735,414bytes with SHA256
25e660fe1024ec3da67962cb0751c5cc8b519bdbc82f8fa487b8427c71599b08.
Both canonical archive verifications pass; the exact591entry source manifest
passes, covering592tracked files including the manifest. HEAD and tracked state
were unchanged and clean before/after. `proof.json` records source tree and hashes.

Commands were executed read-only in /workspace/campaign/final-candidate:

```
python tools/source_manifest.py verify --source 2f18a4f5a87139c38ee388f6c70af33e0fb247f7
python tools/ci/source_snapshot/archive.py create --revision 2f18a4f5a87139c38ee388f6c70af33e0fb247f7 --output /workspace/campaign/evidence/094/final-2f18a4f5a871/source1.tar.gz --record /workspace/campaign/evidence/094/final-2f18a4f5a871/source1.json
python tools/ci/source_snapshot/archive.py create --revision 2f18a4f5a87139c38ee388f6c70af33e0fb247f7 --output /workspace/campaign/evidence/094/final-2f18a4f5a871/source2.tar.gz --record /workspace/campaign/evidence/094/final-2f18a4f5a871/source2.json
cmp /workspace/campaign/evidence/094/final-2f18a4f5a871/source1.tar.gz /workspace/campaign/evidence/094/final-2f18a4f5a871/source2.tar.gz
cmp /workspace/campaign/evidence/094/final-2f18a4f5a871/source1.json /workspace/campaign/evidence/094/final-2f18a4f5a871/source2.json
python tools/ci/source_snapshot/archive.py verify --revision 2f18a4f5a87139c38ee388f6c70af33e0fb247f7 --archive /workspace/campaign/evidence/094/final-2f18a4f5a871/source1.tar.gz --record /workspace/campaign/evidence/094/final-2f18a4f5a871/source1.json
python tools/ci/source_snapshot/archive.py verify --revision 2f18a4f5a87139c38ee388f6c70af33e0fb247f7 --archive /workspace/campaign/evidence/094/final-2f18a4f5a871/source2.tar.gz --record /workspace/campaign/evidence/094/final-2f18a4f5a871/source2.json
```

This is reproducible-source evidence, not release/runtime qualification. The
unchanged >50 fusion-count gate conflicts with the complete eligible maximum50;
final native/sanitizer/Android/full-suite results remain separate. The prior
ccdef4f checkpoint and its source archives were preserved.
