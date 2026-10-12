"""Launch the exported Hill Climb APK, hold the gas pedal, and save a real emulator screenshot."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

adb, package, output = sys.argv[1:4]


def run(*args, check=True):
    return subprocess.run([adb, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          check=check, timeout=90).stdout


def button_center():
    saw_system_anr = False
    try:
        for _ in range(3):
            run('shell', 'uiautomator', 'dump', '/sdcard/hill-climb-ui.xml')
            root = ET.fromstring(run('shell', 'cat', '/sdcard/hill-climb-ui.xml'))
            nodes = list(root.iter('node'))
            if any("isn't responding" in n.attrib.get('text', '').lower() for n in nodes):
                saw_system_anr = True
                wait = next((n for n in nodes if n.attrib.get('text') == 'Wait'), None)
                match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',
                                     wait.attrib.get('bounds', '') if wait is not None else '')
                if not match:
                    break
                left, top, right, bottom = map(int, match.groups())
                run('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
                time.sleep(5)
                continue
            for node in nodes:
                description = node.attrib.get('content-desc', '').lower()
                if 'gas pedal' not in description:
                    continue
                match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds', ''))
                if match:
                    left, top, right, bottom = map(int, match.groups())
                    return (left + right) // 2, (top + bottom) // 2
    except (subprocess.SubprocessError, ET.ParseError):
        pass
    if saw_system_anr:
        raise RuntimeError('Android system remained unresponsive while locating the accelerator')
    # The exported game is landscape, so the accelerator sits at the lower-right edge.
    dimensions = run('shell', 'wm', 'size').decode('utf-8', 'replace')
    matches = re.findall(r'(\d+)x(\d+)', dimensions)
    width, height = map(int, matches[-1]) if matches else (1920, 1080)
    return int(width * 0.84), int(height * 0.93)


run('shell', 'am', 'force-stop', package, check=False)
run('shell', 'monkey', '-p', package, '-c', 'android.intent.category.LAUNCHER', '1', check=False)
time.sleep(3)
x, y = button_center()
run('shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '2300', check=False)
time.sleep(1)
Path(output).parent.mkdir(parents=True, exist_ok=True)
Path(output).write_bytes(run('exec-out', 'screencap', '-p'))
print(f'Captured exported Hill Climb APK gameplay at {output} (accelerator {x},{y})')
