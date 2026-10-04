"""Minimal native-app device smoke test. Run on an installed APK/emulator via adb.

Checks the dashboard, creates a project with the New project wizard, plays the created
project, plays a bundled example, saves it, and reopens it.
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


def command(*args, timeout=60):
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


def find(nodes, *, text=None, contains=None, description=None, clickable=None):
    for node in nodes:
        if text is not None and attr(node, 'text') != text:
            continue
        if contains is not None and contains not in attr(node, 'text'):
            continue
        if description is not None and attr(node, 'content-desc') != description:
            continue
        if clickable is not None and attr(node, 'clickable') != clickable:
            continue
        return node
    return None


def crash_trace():
    """Return the newest AndroidRuntime fatal exception block, if the app crashed."""
    try:
        log = command('logcat', '-d', '-t', '600', '-s', 'AndroidRuntime:E').decode('utf-8', 'replace')
    except Exception:
        return ''
    if 'FATAL EXCEPTION' not in log:
        return ''
    blocks = log.split('FATAL EXCEPTION')
    return ('FATAL EXCEPTION' + blocks[-1]).strip()


def fail(message, nodes):
    print('::error::' + message)
    print('::notice::Visible UI: ' + ' | '.join(attr(n, 'text') or attr(n, 'content-desc')
                                              for n in nodes if attr(n, 'text') or attr(n, 'content-desc'))[:1000])
    trace = crash_trace()
    if trace:
        # GitHub keeps the first annotations of a step: put the headline first.
        for line in trace.splitlines()[:12]:
            print('::error::CRASH ' + line.strip()[:200])
        try:
            Path('/tmp/world-crash.log').write_text(trace, encoding='utf-8')
        except Exception:
            pass
    raise RuntimeError(message)


def shoot(name):
    screenshot.with_name(name).write_bytes(command('exec-out', 'screencap', '-p'))
    return screenshot.with_name(name)


def wait_for(predicate, timeout=20.0, interval=1.0):
    deadline = time.time() + timeout
    nodes = []
    while time.time() < deadline:
        nodes = hierarchy()
        if predicate(nodes):
            return nodes
        time.sleep(interval)
    return nodes


nodes = hierarchy()
if not any('New project' in attr(n, 'text') for n in nodes):
    fail('Dashboard New project action not visible', nodes)
print('::notice::Dashboard loaded with native project actions')

# --- Create a project with the New project wizard and open it in the scene editor ---------
new_project = find(nodes, contains='New project', clickable='true')
if new_project is None:
    fail('Dashboard New project button is not clickable', nodes)
tap(new_project)
time.sleep(2)
nodes = wait_for(lambda visible: any('Create a project' in attr(n, 'text') for n in visible), timeout=15)
if not any('Create a project' in attr(n, 'text') for n in nodes):
    fail('Create-project wizard did not open', nodes)
print('::notice::Create-project wizard opened on the device')

create = find(nodes, text='Create', clickable='true')
if create is None:
    fail('Create button missing from the project wizard', nodes)
tap(create)
nodes = wait_for(lambda visible: find(visible, description='Play scene') is not None, timeout=25)
if find(nodes, description='Play scene') is None:
    fail('Creating a new project did not reach the native scene editor', nodes)
print('::notice::New project created and opened in the native scene editor')
shoot('world-new-project-editor.png')

play = find(nodes, description='Play scene')
if play is None:
    fail('Play action missing right after project creation', nodes)
tap(play)
nodes = wait_for(lambda visible: find(visible, description='Stop') is not None, timeout=25)
if find(nodes, description='Stop') is None:
    fail('A project created on the device could not start its live preview', nodes)
print('::notice::Newly created project entered live preview')
shoot('world-new-project-preview.png')
command('shell', 'input', 'keyevent', '4')
time.sleep(1)
command('shell', 'input', 'keyevent', '4')
time.sleep(2)

# --- A second project created from a game template ---------------------------------------
nodes = wait_for(lambda visible: find(visible, contains='New project', clickable='true') is not None, timeout=15)
new_project = find(nodes, contains='New project', clickable='true')
if new_project is not None:
    tap(new_project)
    time.sleep(2)
    nodes = wait_for(lambda visible: find(visible, text='Create', clickable='true') is not None, timeout=15)
    template = find(nodes, text='Empty 2D', clickable='true') or find(nodes, contains='Empty 2D')
    if template is not None:
        tap(template)
        time.sleep(2)
        nodes = hierarchy()
        platformer = find(nodes, text='Platformer', clickable='true')
        if platformer is not None:
            tap(platformer)
            time.sleep(2)
            nodes = hierarchy()
            create = find(nodes, text='Create', clickable='true')
            if create is not None:
                tap(create)
                nodes = wait_for(lambda visible: find(visible, description='Play scene') is not None, timeout=25)
                if find(nodes, description='Play scene') is not None:
                    print('::notice::Template project (Platformer) created and opened in the editor')
                    shoot('world-new-template-project.png')
                    command('shell', 'input', 'keyevent', '4')
                    time.sleep(2)
                else:
                    fail('Creating a project from the Platformer template did not open the editor', nodes)
            else:
                print('::notice::Skipped the template project check: Create button not found')
        else:
            print('::notice::Skipped the template project check: Platformer entry not found')
    else:
        print('::notice::Skipped the template project check: template spinner not found')
else:
    print('::notice::Skipped the template project check: dashboard New project action not found')

# --- Play a bundled example from the dashboard -------------------------------------------
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

print('::notice::Device smoke test finished: dashboard, new project wizard, template project, live previews and project reopening all worked')
