"""JNI source wiring only; executable CABI/Kotlin tests cover their actual paths.
These checks are deliberately not advertised as Android/JNI runtime execution.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]


def test_jni_retains_camera_cabi_response_without_failure_message_rewrite():
    text = (ROOT / 'reconstruction/src/jni.cpp').read_text()
    camera = text.split('Java_com_realityforge_scanner_engine_NativeCore_registerCamera(', 1)[1].split('// Gauge-fixed', 1)[0]
    assert 'rf_register_camera_json(data.data(),data.size()/5,threshold)' in camera
    assert 'rf::jni::response(env,json)' in camera
    assert 'take(' not in camera and 'substr(' not in camera
    cancellable = text.split('Java_com_realityforge_scanner_engine_NativeCore_registerCameraCancellable(', 1)[1].split('extern "C"', 1)[0]
    assert 'withOperation(env,token' in cancellable
    assert 'Java_com_realityforge_scanner_engine_NativeCore_registerCamera(env,receiver,rows,threshold)' in cancellable


def test_existing_registration_reason_bound_and_json_error_classification_remain():
    frontier = (ROOT / 'app/src/main/java/com/realityforge/scanner/engine/NativeFrontierGeometry.kt').read_text()
    registration = (ROOT / 'app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt').read_text()
    assert 'require(!result.has("error")){result.optString("error")}' in frontier
    assert 'failure.message.orEmpty().take(200)' in registration
    cpp = (ROOT / 'reconstruction/src/pnp.cpp').read_text()
    assert 'catch(const cancellation::Cancelled&){throw;}' in cpp
    assert 'iter<450' in cpp and 'std::ceil(.28*observations.size())' in cpp
    assert 'std::locale::classic()' in cpp and 'std::setprecision(6)' in cpp
