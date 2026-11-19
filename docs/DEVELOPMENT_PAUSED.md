# Development pause and resumption record

## Resumed — 19 November 2026

The owner explicitly requested the next repository phase and a new 60-agent,
10-wave implementation campaign. That instruction supersedes the October pause.
Development resumes from `a373aff43a03bd736edb16707cbf5edabf5f2156` with M20
preparation: trustworthy reconstruction diagnostics, qualification receipts and
validation workflow repairs. See the
[campaign record](development/M20_PREPARATION_2026_11_19.md),
[current status](MILESTONE_STATUS.md) and
[physical M20 handoff](development/M20_PHYSICAL_HANDOFF.md).

This resumption does not qualify the earlier paused checkpoint or establish
completed M20, installed-device behavior or release readiness. The historical
record below remains unchanged in scope.

## Historical pause — 25 October 2026

Development was stopped at the owner's request. This final checkpoint preserves the latest work; it is not a release or a claim of completed software.

Included previously reviewed work:
- Topology aliases from `8d603d96076e9f265c7468720f30e547762b8182`: preserve unique observation provenance and observed faces while separating supported disconnected vertex fans. Intersections and missing surfaces remain. Automatic vertex welding can undo the alias representation; STL does not preserve its indexed topology.
- Read-only eight-photo cache diagnostics from `04997f5748f78cf01609d6f5b7061ac145521276`: bounded matching summaries, with no native replay or input/threshold changes. A new source-bound pipeline invocation has not qualified this diagnostic.
- Help-only test isolation from `5f2f1afda8df83ec4895b347800ce0f60a525dfa`: strict real-process help checks for exact pinned commands, not a native sanitizer waiver.

The combined checkpoint has not completed integrated CI, Android runtime or physical-device qualification. Individual component tests/reviews do not substitute for that combined validation. The topology candidate passed 53 native tests, 14 portable Kotlin tests, 21 interoperability tests and 27 security tests; its final full-suite rerun was cancelled when development stopped, not passed.

The last completed wider-orbit HOST run on `c445fefd54aa777fb19084348673bb1ad9be1815` retained an insufficient-overlap failure before an initial camera pair was accepted. It did not establish camera registration, bundle adjustment or a model. Both completed sanitizer suites on that revision retained 14 failures. Earlier status documents remain source-bound historical records.

No new one-shot HOST diagnostic was activated by that final checkpoint. At the
time, further development and validation required a new instruction from the
owner; the 19 November instruction above now provides it.
