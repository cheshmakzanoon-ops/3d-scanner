"""Real packaged shader execution on the SDK software Vulkan ICD, not phone evidence."""
from tools.native_process import native_compile_env
from pathlib import Path
import os
import subprocess
import pytest
import re
import struct

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
    words=[int(word,16) for word in re.findall(r'0x[0-9a-fA-F]+',(root/'reconstruction/src/vulkan/cost_reduce_spv.hpp').read_text())]
    assert struct.pack('<'+'I'*len(words),*words)==shader.read_bytes(), 'Packaged shader differs from reproducible source'
    exe=tmp_path/'vulkan-test'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wpedantic','-Werror','-I'+str(root/'reconstruction/include'),'-idirafter',str(headers),str(root/'reconstruction/tests/vulkan_depth_contract_test.cpp'),str(root/'reconstruction/src/vulkan/vulkan_depth.cpp'),str(root/'reconstruction/src/cancellation.cpp'),'-l:libvulkan.so.1','-o',str(exe)],check=True, env=native_compile_env())
    env=os.environ.copy()
    icd=sdk/'emulator/lib64/vulkan/lvp_icd.json'
    if icd.exists():
        env['VK_ICD_FILENAMES']=str(icd)
        layers=sdk/'emulator/lib64/vulkan/layers'
        if (layers/'VkLayer_khronos_validation.json').exists():
            env['VK_LAYER_PATH']=str(layers)
            env['VK_INSTANCE_LAYERS']='VK_LAYER_KHRONOS_validation'
        executed=subprocess.run([str(exe),'require-vulkan'],env=env,check=True,timeout=30,text=True,capture_output=True)
        assert 'Validation Error' not in executed.stderr+executed.stdout, executed.stderr+executed.stdout
    else:
        subprocess.run([str(exe)],env=env,check=True,timeout=30)
    # Explicit absent driver must retain exact CPU output, never fake GPU success.
    env['VK_ICD_FILENAMES']=str(tmp_path/'missing-icd.json')
    subprocess.run([str(exe)],env=env,check=True,timeout=30)
