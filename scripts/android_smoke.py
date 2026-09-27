"""Minimal native-app device smoke test. Run on an installed APK/emulator via adb.

Checks the dashboard, plays a bundled example, saves it, and reopens it.
No screenshots or credentials are committed; CI can upload screenshots separately.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

adb = sys.argv[1]
screenshot = Path(sys.argv[2])


def command(*args, timeout=30):
    return subprocess.run([adb, *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout, check=True).stdout


def hierarchy():
    command('shell', 'uiautomator', 'dump', '/sdcard/world-smoke.xml')
    return list(ET.fromstring(command('shell', 'cat', '/sdcard/world-smoke.xml')).iter('node'))


def attr(node, name):
    return node.attrib.get(name, '')


def center(node):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', attr(node, 'bounds'))
    if not match:
        raise RuntimeError('Missing clickable bounds for ' + str(node.attrib))
    left, top, right, bottom = map(int, match.groups())
    return (left + right) // 2, (top + bottom) // 2


def tap(node):
    x, y = center(node)
    command('shell', 'input', 'tap', str(x), str(y))


def scroll():
    command('shell', 'input', 'swipe', '165', '508', '163', '186', '390')
    time.sleep(1)


def fail(message, nodes):
    print('::error::' + message)
    print('::notice::Visible UI: ' + ' | '.join(attr(n, 'text') or attr(n, 'content-desc')
                                              for n in nodes if attr(n, 'text') or attr(n, 'content-desc'))[:1000])
    raise RuntimeError(message)


nodes = hierarchy()
if not any('New project' in attr(n, 'text') for n in nodes):
    fail('Dashboard New project action not visible', nodes)
print('::notice::Dashboard loaded with native project actions')

# The sample cards are below the fold on a compact portrait phone.
for _ in range(8):
    nodes = hierarchy()
    for index, node in enumerate(nodes):
        if attr(node, 'text') == 'Forest Runner':
            play = next((other for other in nodes[index + 1:index + 30]
                         if 'Play' in attr(other, 'text') and attr(other, 'clickable') == 'true'), None)
            if play is not None:
                tap(play)
                break
    else:
        scroll()
        continue
    break
else:
    fail('Could not find the Forest Runner Play button in the example card', nodes)

time.sleep(6)
nodes = hierarchy()
if not any(attr(n, 'content-desc') == 'Stop' for n in nodes):
    fail('Bundled playable example failed to launch live preview', nodes)
print('::notice::Bundled Forest Runner game entered live preview')
screenshot.parent.mkdir(parents=True, exist_ok=True)
screenshot.write_bytes(command('exec-out', 'screencap', '-p'))

# Press back once to stop preview and again to return to the saved-project dashboard.
command('shell', 'input', 'keyevent', '4')
time.sleep(1)
command('shell', 'input', 'keyevent', '4')
time.sleep(2)
for _ in range(5):
    nodes = hierarchy()
    if any('Open editor' in attr(n, 'text') for n in nodes):
        tap(next(n for n in nodes if 'Open editor' in attr(n, 'text')))
        break
    scroll()
else:
    fail('Saved example did not appear in Recent Projects', nodes)

time.sleep(3)
nodes = hierarchy()
if not any(attr(n, 'content-desc') == 'Play scene' for n in nodes):
    fail('Saved example could not be reopened in the scene editor', nodes)
print('::notice::Saved editable example reopened from the offline project dashboard')

crashes = command('logcat', '-d', '-t', '800', '-s', 'AndroidRuntime:E').decode('utf-8', 'replace')
if 'FATAL EXCEPTION' in crashes and 'com.world2d.engine' in crashes:
    print('::error::' + crashes[-1600:].replace('\n', ' '))
    raise RuntimeError('AndroidRuntime reported a fatal crash')
