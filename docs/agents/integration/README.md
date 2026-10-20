# Campaign integration contract

The current request executes Agents 081–100. The common source base is recorded in campaign-base.json, the commit introducing the plan on origin/main. Agents 001–080 are indexed by their actual remote delivery; absence is NOT_RUN and does not certify that their product work exists. No main merge or release publication is implied by an agent branch push.

Run `python tools/agents/campaign.py plan` to validate the full roster and exclusive ownership. Run `python tools/agents/campaign.py ownership --agent NNN --base CAMPAIGN_BASE --revision DELIVERY_SHA` before accepting an immutable delivery. Renames are checked as deletion plus addition. The manifest is a mechanical shared exception; semantic shared-file changes require the named owner. Existing and reserved scope comes directly from the plan, so no copied ownership checklist can silently drift.

After fetching origin, run `python tools/agents/campaign.py index --base CAMPAIGN_BASE --output /tmp/delivery-index.json`. It resolves each exact remote branch head, reads its owned receipt from that commit, checks changed paths and indexes committed handoffs with source SHA/path. Missing and invalid receipts remain explicit. Add `--require-complete` for final acceptance: any absent/not-DONE/ownership-invalid delivery makes it fail closed. This index is discovery, not substitute test evidence.

Agent 100 alone integrates small immutable compatible slices on agents/100-integration-lead. Source conflicts are resolved by semantic owners; generated manifest conflicts are regenerated from the complete staged final tree after semantic resolution. Required source/build/runtime changes trigger affected reruns. Receipt-only evidence commits refer to the already tested candidate and never retrofit tests to a changed runtime tree. Agent 094 owns the exact-set generator; use its published CLI once available. Agent 099 independently qualifies the final candidate and all external gates.

## C16 evidence identity

Evidence must bind the full source SHA, actual artifact SHA256, tool/command and exit status, environment identity (host/emulator/physical), input/settings identities, finite metrics and PASS/FAIL/NOT_RUN/BLOCKED_EXTERNAL per gate. Do not classify emulator evidence as physical, unexecuted instrumentation as pass, or omitted cases as success. Required stale/missing evidence blocks release. Qualification schema and independent aggregator are owned by Agent 099; requests and candidate evidence must conform to its versioned schema.

## Open integration prerequisites

The001–080 remote branches are inspected through their immutable delivery index; some are present, others are absent or still WORKING. Their implementation is outside this request, and a branch name alone is not an accepted delivery. Requests to their foundation/runtime owners are concrete committed handoffs, not permission for Agent100 or a QA agent to edit their sources. Physical ARM64 phones, authorized calibrated whole-object/reference captures, production signing identity and external-beta operators are not present. These gates stay BLOCKED_EXTERNAL or NOT_RUN after executable tooling is implemented; campaign completion requires all one hundred deliveries and all required final evidence.
