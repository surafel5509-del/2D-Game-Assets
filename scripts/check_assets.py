"""Validate that every advertised asset in the native catalog has a real bundled file."""
import json
from pathlib import Path
from collections import Counter
import re
root = Path('app/src/main/assets')
catalog = json.loads((root / 'library/catalog.json').read_text())
assert len(catalog) >= 623, f'Expected 623 bundled resources, got {len(catalog)}'
assert len({item['id'] for item in catalog}) == len(catalog), 'Duplicate resource IDs'
parts = 0
for item in catalog:
    path = root / item['path']
    assert path.is_file() and path.stat().st_size > 30, f'Missing/empty resource: {path}'
    if item['kind'] == 'audio':
        assert path.read_bytes()[:4] == b'RIFF', f'Invalid WAV: {path}'
    for part in item['parts']:
        path = root / part['path']
        assert path.is_file() and path.stat().st_size > 30, f'Missing part: {path}'
        parts += 1
counts = Counter(item['category'] for item in catalog)
assert counts['Textures'] >= 100 and counts['Vehicles'] >= 30 and counts['Audio'] >= 30
assert parts >= 1000
assert len(json.loads((root / 'library/motion-presets.json').read_text())) >= 50
assert len(json.loads((root / 'library/particle-presets.json').read_text())) >= 100
# Catch literal references that would otherwise show a missing-asset rectangle in examples.
example_source = Path('app/src/main/java/com/world2d/engine/data/Examples.java').read_text()
resource_ids = {item['id'] for item in catalog}
for reference in re.findall(r'builtin:[a-z0-9-]+', example_source):
    if not reference.endswith('-'):
        assert reference in resource_ids, f'Example refers to missing resource: {reference}'
print(f'Validated {len(catalog)} resources, {parts} modular parts, {counts["Audio"]} WAVs, 59 animations and 100 particle presets')
