# Focused accelerated ATD diagnostic

Manual-only `accelerated-atd-diagnostic.yml` runs the existing four synthetic
JNI/cache methods on API35 AOSP ATD revision1, x86_64, with `-accel on`.
It does not launch Activities or test graphics. ATD has rendering disabled;
CPU acceleration does not make this full compatibility, stock-phone, physical
capture, ARM64, performance or release qualification. Those receipt fields stay
`NOT_RUN`. Actual execution of this new accelerated path is not yet established.

The controller reuses the software diagnostic's exact-source manifest checks,
APK/installed-APK identity checks, private ADB/AVD ownership, strict four-method
transcript parser, bounded commands, signal handling and owned-process cleanup.
The original software workflow keeps its original `-accel off` behavior.
The 75-minute job, 72-minute controller and existing phase/output bounds remain.
This diagnostic is not connected to candidate-evidence acceptance.

Before SDK setup/build, it requires an explicit manual main-branch dispatch of
this public repository on a standard Linux x64 GitHub-hosted runner, opens the
existing `/dev/kvm` character device read-write and queries API version12.
It never changes permissions, groups, ACLs or udev rules. Missing access blocks;
there is no software fallback or automatic retry. If runner access needs changing,
that is a separate authorized operation, not something this workflow performs.

SDK installation uses only the existing controller's preaccepted-license
snapshot and closed-stdin package installation; new terms or changed license
records block. No setup-android or emulator action accepting licenses is used.
No caches, artifact uploads, new dependencies, secrets, paid runners or persistent
keys are added. Existing bounded job-log output remains; APKs and temporary state
are not uploaded. The historical software-ATD pass is not this path's result.

Host tests mock KVM and Android phase boundaries. They verify admission, command
mode separation and inherited failure cleanup; they are not Android execution.
