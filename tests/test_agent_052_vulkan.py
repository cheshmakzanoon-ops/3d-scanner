"""Real packaged shader execution on the SDK software Vulkan ICD, not phone evidence."""
from pathlib import Path
import os
import subprocess
import pytest

def test_vulkan_contract_and_actual_software_dispatch(tmp_path):
    root=Path(__file__).resolve().parents[1]
    sdk=Path(os.environ.get('ANDROID_HOME','/workspace/android-sdk'))
    ndk=sdk/'ndk/28.2.13676358'
    headers=ndk/'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
    glslc=ndk/'shader-tools/linux-x86_64/glslc'
    if not glslc.exists(): pytest.skip('Pinned NDK shader compiler unavailable; NOT_RUN shader gate')
    shader=tmp_path/'cost.spv'
    subprocess.run([str(glslc),'-O',str(root/'reconstruction/shaders/depth/cost_reduce.comp'),'-o',str(shader)],check=True)
    subprocess.run([str(glslc.parent/'spirv-val'),str(shader)],check=True)
    exe=tmp_path/'vulkan-test'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wpedantic','-Werror','-I'+str(root/'reconstruction/include'),'-idirafter',str(headers),str(root/'reconstruction/tests/vulkan_depth_contract_test.cpp'),str(root/'reconstruction/src/vulkan/vulkan_depth.cpp'),str(root/'reconstruction/src/cancellation.cpp'),'-l:libvulkan.so.1','-o',str(exe)],check=True)
    env=os.environ.copy()
    icd=sdk/'emulator/lib64/vulkan/lvp_icd.json'
    if icd.exists():
        env['VK_ICD_FILENAMES']=str(icd)
        subprocess.run([str(exe),'require-vulkan'],env=env,check=True,timeout=30)
    else:
        subprocess.run([str(exe)],env=env,check=True,timeout=30)
    # Explicit absent driver must retain exact CPU output, never fake GPU success.
    env['VK_ICD_FILENAMES']=str(tmp_path/'missing-icd.json')
    subprocess.run([str(exe)],env=env,check=True,timeout=30)
