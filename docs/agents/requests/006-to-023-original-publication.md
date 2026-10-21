# Integrated 006/023 handoff: original photograph publication

Agent 023 accepted the verified original-publication protocol during the 001–030
integration audit. `JournaledOriginalPublicationPort` is its concrete adapter for
the existing per-project `originals` layout. Agent 011's real bounded `ImportService`
receives this `ImportPublicationPort`; `BoundPhotoImportService` handles provider
ingestion, staging recovery and storage reservation only.

The adapter owns the actual synced publication intent, original-byte hash and
length verification, atomic file promotion, exact-revision Room transaction,
duplicate handling, abort journal and startup reconciliation. It retains the
existing original paths so independent capture archives and deletion ownership
remain compatible. The generic immutable multi-artifact publisher handles result
sets; it does not pretend to have published original rows.

All calls occur within the repository's C6 project write lease. A successful DAO
transaction with a lost reply is read back and returned as `Imported`. A confirmed
uncommitted original is explicitly aborted and its verified owned bytes removed.
An ambiguous readback becomes `PendingRecovery`, stops later provider opens and
retains the journal. Startup recovery cannot later commit an operation previously
reported as definitively rejected. Unsupported or corrupt journals stop admission
for the affected project instead of being silently skipped.

The actual provider/repository/Room fault fixtures in
`app/src/androidTest/java/com/realityforge/scanner/agents/a011/` exercise these
boundaries. Their existence is not an execution claim; the overall audit retains
the Android/device test results for the exact integrated revision.
