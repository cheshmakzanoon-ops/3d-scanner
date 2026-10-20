"""Independent OpenCV calibration numeric checks; purely synthetic, not a handset result."""
import numpy as np
import cv2


def test_synthetic_checkerboard_calibration_recovers_intrinsics():
    rng = np.random.default_rng(20261010)
    cols, rows = 9, 6
    board = np.zeros((cols * rows, 3), dtype=np.float32)
    board[:, :2] = np.stack(np.meshgrid(np.arange(cols), np.arange(rows)), -1).reshape(-1, 2) * 0.022
    K = np.array([[1210.0, 0, 804.0], [0, 1198.0, 595.0], [0, 0, 1]], dtype=np.float64)
    D = np.array([-0.18, 0.065, 0.0012, -0.001, -0.016], dtype=np.float64)
    obj, img = [], []
    for _ in range(38):
        rvec = rng.uniform(-0.35, 0.35, 3).astype(np.float64)
        tvec = np.array([rng.uniform(-0.12, 0.08), rng.uniform(-0.08, 0.06), rng.uniform(0.33, 0.60)])
        points = cv2.projectPoints(board, rvec, tvec, K, D)[0].reshape(-1, 2)
        if not ((points[:, 0] > 10).all() and (points[:, 0] < 1590).all()
                and (points[:, 1] > 10).all() and (points[:, 1] < 1190).all()):
            continue
        img.append((points + rng.normal(0, 0.12, points.shape)).astype(np.float32))
        obj.append(board.copy())
    assert len(img) >= 10
    rms, fitK, fitD, *_ = cv2.calibrateCamera(obj, img, (1600, 1200), None, None)
    assert rms < 0.25
    assert abs(fitK[0, 0]/K[0, 0] - 1.0) < 0.025
    assert abs(fitK[1, 1]/K[1, 1] - 1.0) < 0.025
    assert abs(fitD.ravel()[0] - D[0]) < .07


def test_opencv_brown_conrady_undistortion_roundtrip():
    K = np.array([[1240., 0, 795.], [0, 1225., 605.], [0, 0, 1.]])
    D = np.array([-0.24, 0.11, 0.0015, -.001, -.02])
    points = np.array([[x, y, 1.] for x in np.linspace(-.45,.45,11)
                       for y in np.linspace(-.32,.32,9)], dtype=np.float64)
    pixels = cv2.projectPoints(points, np.zeros(3), np.zeros(3), K, D)[0].reshape(-1,2)
    undistorted = cv2.undistortPoints(pixels.reshape(-1,1,2), K, D, None, None).reshape(-1,2)
    assert np.max(np.abs(undistorted-points[:,:2])) < 1e-6
