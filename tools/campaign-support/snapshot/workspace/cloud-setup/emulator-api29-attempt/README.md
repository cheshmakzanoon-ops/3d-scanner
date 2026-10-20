Official API29 software emulator attempt

Package: system-images;android-29;default;x86_64, revision8, installed by official sdkmanager with the session proxy and Java CA trust. No verification was disabled.
AVD: /workspace/cloud-setup/emulator-api29-attempt/avd/campaign-api29-default.avd
Display:320x480 at160dpi. CPU:2 virtual cores;RAM:2048MiB;GPU:SwiftShader;no KVM,no Google applications.

Activate /workspace/cloud-setup/activate.sh. Use ANDROID_AVD_HOME=/workspace/cloud-setup/emulator-api29-attempt/avd for this AVD. The tested launch is:

/workspace/android-sdk/emulator/emulator -avd campaign-api29-default -accel off -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader -memory 2048 -cores 2 -skin 320x480 -dpi-device 160 -show-kernel -port 5556

Check adb -s emulator-5556 shell getprop sys.boot_completed and activity/package services before claiming readiness. Actual observations are append-only readiness.jsonl. Build/install/test outcomes are separate; no instrumentation completion is implied by a connected adb transport.

API35 qemu PID46011 was verified as campaign-api35/port5554 and SIGSTOPped solely to reduce CPU competition. Its disk/state was preserved. api35-pause-lease.json records the lease; send SIGCONT after the API29 attempt. Never apply this PID blindly in a later session; first verify its current executable/AVD/port.

SDK install, AVD creation, emulator kernel, and runtime probes are retained beside this file. No repository application source changed for this environment work.

Verified runtime result: official API29 completed boot and exposed activity/package services at2026-10-20T08:45:57Z. Frozen039 source f3a08c34027bfee439b117c21ca679d6684602fb app and instrumentation APK installs succeeded. Actual IncrementalNativeRegistrationTest completed with1 test,0failed,0ignored; recovered5 supported cameras and1 deliberate disconnected camera, maximum translation error1.31866e-6 arbitrary units. See frozen039/artifacts.json,result.json,instrumentation.log,runtime-logcat.log. This synthetic JNI proof does not qualify physical-handset/full-object geometry or resources.
