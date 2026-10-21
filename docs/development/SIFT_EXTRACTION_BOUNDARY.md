# Borrowed-gray production extraction boundary

Production changes: FeatureExtractor.kt adapter and new GrayFeatureExtraction.kt.
Public signatures, JVM synchronization, settings identity text/version, baseline
SIFT configuration/order, pixel sampling, descriptor cloning and FeatureFrame
construction are unchanged. Settings/event types retain their qualified names.
No public host API, alternate algorithm, decoder mock, or platform branch added.

Android adapter still validates metadata before decode, checks cancellation before
and after decode, creates lens before color conversion, releases RGBA before SIFT,
and keeps bitmap alive until synchronous core sampler returns. Its finalizer owns
RGBA/gray/bitmap. Core owns keys/descriptor/mask/SIFT and retained clone until frame
constructor succeeds. Core checks cancellation exactly where moved code did:
after native kernel, every128 keypoints, and before FeatureFrame construction.
No additional cancellation callback invocation was added.

Allocation timing changes: the five-Mat ledger is split into a predecode two-Mat
adapter ledger and a three-Mat core ledger; keys/descriptor/mask are allocated
after color conversion instead of before projection/conversion. Thus allocation
failure ordering can differ. No additional image-sized buffer is introduced.

New internal seam rejects non-2D/non-CV_8UC1/nonpositive/over-analysisEdge Mats
before allocating working Mats. Android adapter already guarantees these
properties. The lower bound is1, not3, so tiny decoded images retain late
FeatureFrame validation rather than changing observer/cancellation ordering.
The core deliberately trusts prevalidated metadata supplied by adapter, while
FeatureFrame still validates on publication; host late-validation test documents
this distinction. No cache identity bump is warranted for movement alone.

Existing observer exception semantics intentionally remain: STARTED throw emits
no FINISHED; FINISHED throw in finally can replace a native exception. This draft
does not claim observers are best-effort or native memory bounds are measured.
Host synthetic Mat tests cover core only, not Android decode or physical quality.
