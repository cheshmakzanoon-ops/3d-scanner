"""Independent real-photograph geometry validation (no fixture photos redistributed)."""
from pathlib import Path
import sys
import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"tools"))
from benchmark_middlebury import benchmark, enforce_reference_gates, evaluate

@pytest.fixture(scope="module")
def result():
    pytest.importorskip("skimage")
    return benchmark()

def test_middlebury_real_images_reconstruct_without_ground_truth_in_matching(result):
    enforce_reference_gates(result)
    assert result["captureType"].startswith("rectified calibrated stereo")
    assert result["inputSha256"]["left"] != result["inputSha256"]["right"]
    assert len(result["inputSha256"]["groundTruthDisparity"])==64
    assert result["matchedDisparityAbsErrorPxP90"] >= result["matchedDisparityAbsErrorPxP50"]

def test_real_benchmark_refuses_invalid_or_missing_ground_truth():
    dummy={"points":[[0,0,5,120,120,120,0,1,0]]}
    with pytest.raises(ValueError,match="ground-truth"):
        evaluate(np.full((6,6),np.inf), np.array([[2.,2.,1.,2.]]), dummy)
