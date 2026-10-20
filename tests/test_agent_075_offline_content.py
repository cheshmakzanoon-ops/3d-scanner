"""Offline instructional asset geometry; this is content validation, not phone usability."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def test_bundled_board_is_the_approved_nine_by_six_inner_corner_asset():
    source = ROOT / 'docs/checkerboard-9x6.svg'
    bundled = ROOT / 'app/src/main/assets/onboarding/checkerboard-9x6.svg'
    assert bundled.read_bytes() == source.read_bytes()
    tree = ET.parse(bundled)
    squares = [node for node in tree.getroot().iter('{http://www.w3.org/2000/svg}rect')
               if node.get('fill') == 'black']
    assert len(squares) == 35
    cells = set()
    for node in squares:
        assert node.get('width') == '20' and node.get('height') == '20'
        column = (int(node.get('x')) - 5) // 20
        row = (int(node.get('y')) - 23) // 20
        assert 0 <= column < 10 and 0 <= row < 7
        assert (column + row) % 2 == 0
        cells.add((column, row))
    assert cells == {(column, row) for row in range(7) for column in range(10)
                     if (column + row) % 2 == 0}


def test_help_assets_do_not_require_external_uris():
    for path in (ROOT / 'app/src/main/assets/onboarding').iterdir():
        text = path.read_text()
        assert 'href=' not in text and 'url(' not in text
