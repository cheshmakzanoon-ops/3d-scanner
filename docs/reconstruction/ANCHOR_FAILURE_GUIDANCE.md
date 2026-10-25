# No-anchor failure guidance

`SparsePipeline` keeps its camera-initialization acceptance gates unchanged. If no
pair is accepted, it now distinguishes uniformly weak/ambiguous native geometry
rejections from insufficient or inconclusive evidence:

- Every rejected candidate must report one of the finite native codes
  `WEAK_BASELINE`, `PLANAR_AMBIGUITY`, or `ROTATION_ONLY` for the specific result.
- The existing typed `CAMERA_REGISTRATION_FAILED` outcome is persisted, with
  `RECAPTURE_SIDEWAYS` and `scan.failure.weak_or_ambiguous_geometry` in diagnostics.
  The diagnostic stage remains `MATCHING`; the reciprocal-match checkpoint is
  still retained before failure. The outcome does not prove too little physical
  camera motion, nor guarantee that a different capture will reconstruct.
- Any low-match, missing, unknown, or other rejection leaves the existing
  `INSUFFICIENT_OVERLAP` fallback unchanged. No candidate is not evidence of a
  geometric cause. The summary retains only two booleans, not native text, photo
  identifiers, paths, coordinates, descriptors or metadata.
- Successful anchors bypass this failure selection. Candidate ordering, native
  thresholds, scoring, caches, and cancellation/resource/native-exception handling
  are unchanged. This does not introduce recovery by accepting rejected geometry.

The installed processing screen previously displayed outcome codes with spaces.
It now resolves `CAMERA_REGISTRATION_FAILED` to an Android string resource:
geometry could not be established and may be weak or ambiguous; use distinct
sideways viewpoints while retaining overlap and keeping the object still. The
qualified wording also applies to the existing later camera-registration failure.
Only default English resources currently exist; no new translation is claimed.

Regression evidence on base `68ef9b70` includes failing-before/passing-after wiring
and resource guards, an executable Kotlin regression against the extracted prior
constant mapping, finite/mixed/unknown classifications, native cancellation and
resource propagation, and durable outcome-to-UI-adapter transport. Host JVM tests
are not Android compilation, rendered phone UI, or physical capture validation.
Gradle unit/lint execution was blocked before compilation by an unavailable
network route to the official Gradle distribution; installed/device tests remain
NOT_RUN. No native implementation changed in this patch.

The real-stereo HOST diagnostic adapter admits exactly the prior
`INSUFFICIENT_OVERLAP` and the existing `CAMERA_REGISTRATION_FAILED` outcomes,
only for an original `FAILED` job at `MATCHES`. It serializes the actual persisted
outcome, never relabels the original failure, and still fails the complete-journey
assertion. All aggregate replay bounds/privacy checks remain. Even an accepted
replay is diagnostic-only; it cannot publish or qualify the failed job. The
adapter manifest and source pin are updated together. No newly added outcome
means that this unrun full-controller candidate has been validated end to end.
