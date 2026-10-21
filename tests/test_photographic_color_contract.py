"""Supplemental source/math guards; not Android or GPU qualification."""
from pathlib import Path
import math
import unittest

ROOT = Path(__file__).resolve().parents[1]
RENDERER = ROOT / 'app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt'


def decode_srgb(value):
    return value / 12.92 if value <= .04045 else ((value + .055) / 1.055) ** 2.4


def encode_srgb(value):
    return 12.92 * value if value <= .0031308 else 1.055 * value ** (1 / 2.4) - .055


class PhotographicColorContractTest(unittest.TestCase):
    def test_linear_light_midpoint_oracle(self):
        self.assertAlmostEqual(187.51603067837462, 255 * encode_srgb(.5), places=9)
        self.assertAlmostEqual(146.40556166549544,
                               255 * encode_srgb((decode_srgb(64 / 255) + decode_srgb(192 / 255)) / 2), places=7)
        self.assertNotAlmostEqual(.5, encode_srgb(.5), places=2)

    def test_gray_round_trip_and_transfer_breakpoints(self):
        for value in [0, .0031308, .04045, 128 / 255, 1]:
            self.assertTrue(math.isclose(value, encode_srgb(decode_srgb(value)), abs_tol=3e-8))
        self.assertAlmostEqual(.040449936, encode_srgb(.0031308), places=9)
        self.assertAlmostEqual(.0031308049535603713, decode_srgb(.04045), places=12)

    def test_background_round_trip(self):
        for value in [.055, .065, .075]:
            self.assertAlmostEqual(value, encode_srgb(decode_srgb(value)), places=12)

    def test_srgb_storage_precedes_straight_rgba_subimage_upload(self):
        source = RENDERER.read_text()
        self.assertIn('inPremultiplied = false', source)
        self.assertIn('inScaled = false', source)
        self.assertIn('GLES30.GL_SRGB8_ALPHA8', source)
        self.assertLess(source.index('GLES30.glTexStorage2D'), source.index('GLUtils.texSubImage2D'))
        self.assertIn('bitmap, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE', source)
        self.assertNotIn('GLUtils.texImage2D(', source)

    def test_output_encoding_is_queried_and_fails_closed(self):
        source = RENDERER.read_text()
        self.assertIn('GLES30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING', source)
        self.assertIn('GLES30.GL_FRAMEBUFFER, GLES30.GL_BACK', source)
        self.assertIn('GLES30.GL_LINEAR -> true', source)
        self.assertIn('GLES30.GL_SRGB -> false', source)
        self.assertIn('else -> error("Unsupported framebuffer color encoding.")', source)
        self.assertIn('Cannot query framebuffer color encoding.', source)
        for callback, next_callback in [('onSurfaceCreated', 'onSurfaceChanged'), ('onSurfaceChanged', 'onDrawFrame')]:
            self.assertIn('configureOutputEncoding()', source.split('override fun ' + callback)[1].split('override fun ' + next_callback)[0])

    def test_fragment_encodes_only_rgb_after_linear_sampling(self):
        source = RENDERER.read_text().split('private val FRAGMENT_SHADER')[1]
        self.assertIn('uniform bool uEncodeOutput', source)
        self.assertIn('vec3 encodeSrgb(vec3 c)', source)
        self.assertIn('vec4 color = texture(uAtlas, vUv)', source)
        self.assertIn('if (color.a < uCutoff) discard', source)
        self.assertIn('uEncodeOutput ? encodeSrgb(color.rgb) : color.rgb', source)
