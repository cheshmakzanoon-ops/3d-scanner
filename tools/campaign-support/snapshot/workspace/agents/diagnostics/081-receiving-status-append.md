
## Minimal031 coordinate-contract receiving update

```json
{
  "scope": "Minimal031 pixel-centre receiving correction only; existing081 mission/results remain unchanged",
  "state": "READY_FOR_INTEGRATION",
  "source_sha": "4bf3dbb688e110f7282634241d160ed932ff15b0",
  "resumed_upstream_sha": "670ab781177d189d9bc40ea83cbbea9c3164b892",
  "supplying031_contract_sha": "a7dc014ecfab6aa5e196d094f7ba8cbcf5453e6a",
  "production_paths_changed": [
    "tools/benchmark_stereo_kernel.py"
  ],
  "tests_added": [
    "tests/test_agent_081_resize_centres.py"
  ],
  "policy_identity": "rf-pixel-centres-v2:opencv-area:half-pixel-resize-v1",
  "behavior": "Actual cv2.INTER_AREA camera producer now preserves source rays with cx=(cx+.5)*sx-.5 and cy=(cy+.5)*sy-.5, independently scaled axes. Published original calibration, baseline, numerical thresholds, profiles and timing rules are unchanged. Reports bind camera/image byte hashes and resize policy.",
  "acceptance_results": [
    {
      "criterion": "Asymmetric1001x777-to317x241 calibrated ray preservation and actual84x39-to21x13 OpenCV area-pixel correspondence",
      "result": "PASS",
      "command": "source /workspace/cloud-setup/activate.sh; python -m pytest -q tests/test_agent_081_resize_centres.py tests/test_native_regression_harness.py",
      "evidence": "/workspace/agents/081-resize-tests.log",
      "details": "10 passed in0.70s:3 focused producer checks plus7 existing harness checks. Synthetic arrays/analytic rays only; no native/photo or physical-performance claim."
    },
    {
      "criterion": "Independent regression detects old direct-principal scaling",
      "result": "PASS",
      "evidence": "/workspace/agents/081-resize-old-formula-negative.log",
      "details": "Negative control substituted only the old cx*sx/CY*sy formulas into the new camera producer in an outside-checkout copy. Both independent numeric fixtures fail (expected exit1,2failed1passed). This is an old-formula negative control, not a claimed full historical native benchmark execution."
    },
    {
      "criterion": "Benchmark CLI remains executable",
      "result": "PASS",
      "command": "python tools/benchmark_stereo_kernel.py --help",
      "evidence": "/workspace/agents/081-resize-help.log"
    },
    {
      "criterion": "Corrected-input real-photo baseline/current native timing/parity benchmark",
      "result": "NOT_RUN",
      "details": "Requires exact separately built pre-M13 and current Release binaries. No fabricated rerun or inherited numbers are claimed. Parent candidate reruns real-photo regression gates separately."
    }
  ],
  "prior081_acceptance": "The full081 historical sanitized Python process gate above remains FAIL; this narrow receiver correction does not waive or complete it.",
  "integration_request": "docs/agents/requests/081-to-031.md",
  "delivery": {
    "branch_compare_url": "https://github.com/cheshmakzanoon-ops/3d-scanner/compare/main...agents/081-host-numerical-sanitizers",
    "pull_request_api": "GitHub API Forbidden in this environment; native Git push works. No token requested."
  }
}
```
