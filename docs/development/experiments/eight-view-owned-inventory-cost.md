# Owned inventory overhead after real-photo setup failure

Source examined: `22e1cb63537e149279281cf23a0f46e50c87668a`.
GitHub Actions run 40174922938 retained archive SHA-256:
`a095db3e8fa60b8ffb40e84e543523ed96b93f49d5e535287351103c74a06cb5`.

The actual command record identifies `header-provider-18-version` failure in
`loop_disk_guard`, not compiler output admission. It retained 122 output bytes
but never recorded complete command success. Its ten-second phase elapsed
10.000052 seconds before cleanup; loop disk guards consumed 4.704654 seconds,
selector waits 5.283608 seconds. Prior successful inventory-bearing commands
spent roughly 3.4 seconds in a disk guard. Overall failure was at 517.233 seconds;
restoration and owned cleanup passed, with zero pipeline attempts.

The legacy error joins time/count causes and does not record the inventory root
or entries visited. The timing strongly supports exhaustion of the ten-second
command deadline, but **neither exact root nor count-versus-time is conclusively
recoverable** from that artifact. Possible roots are the owned work tree and
source-declared build/Gradle/installed-owned-NDK roots. No live system/header
directory audit was performed to fill this evidence gap.

## Bounded correction

Each ordinary filesystem entry previously allocated an absolute Path, converted
it back relative to the root, then allocated its POSIX string. The entry name and
ancestor tuple already come exclusively from scandir beneath the retained root
FD, so their lexical join is the same relative key. Only this redundant lexical
work is removed. Symlinks still construct the original absolute path for exact
alias admission. Root/ancestor device/inode/type checks, no-follow opens/stats,
queued-directory reopening, disappearing-entry handling, all count/byte/time
limits and five-second scan scheduling remain unchanged.

On an owned synthetic tree of 32,000 empty files in 80 directories, five
alternating runs returned identical 32,080-entry inventories. Local median wall
time decreased from 0.929628s to 0.087591s (10.61×). This demonstrates the specific
Python bookkeeping cost, **not** an actual hosted-runner replay or guarantee that
the next ten-second compiler probe will complete.

Bounded refusal diagnostics now distinguish TIME from COUNT and include only
visited count, observed bytes, elapsed seconds, original numeric bounds, and a fixed owned-root
role (`work` or `extra-owned-N`). No child names, private paths or photo metadata
are emitted. The exact root role can therefore be established from a later
authorized source-bound run rather than guessed from this failure.
