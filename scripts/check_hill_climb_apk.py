"""Verify the standalone debug APK really packages the exported Hill Climb game/runtime."""
import json
import sys
import zipfile

apk_path = sys.argv[1]
with zipfile.ZipFile(apk_path) as apk:
    names = set(apk.namelist())
    for required in ('AndroidManifest.xml', 'classes.dex', 'assets/game/project.json', 'assets/library/catalog.json'):
        if required not in names:
            raise SystemExit(f'APK is missing {required}')
    project = json.loads(apk.read('assets/game/project.json'))

if project.get('genre') != 'hill-climb':
    raise SystemExit(f"Expected a hill-climb project, got {project.get('genre')!r}")
components = set()
for scene in project.get('scenes', []):
    for node in scene.get('nodes', []):
        components.update(component.get('type') for component in node.get('components', []))
required_components = {'hillTerrain', 'hillVehicle', 'hillWheel', 'hillHead', 'hillPickup'}
missing = sorted(required_components - components)
if missing:
    raise SystemExit(f'Exported scene is missing game components: {missing}')
if not any(name.startswith('assets/library/images/') for name in names):
    raise SystemExit('APK is missing the bundled sprite library')
print(f"Verified standalone Hill Climb APK: {project['name']} / {project['packageId']} ({len(names)} entries)")
