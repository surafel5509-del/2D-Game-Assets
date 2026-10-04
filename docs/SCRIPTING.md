# LumenScript

LumenScript is Lumen2D's built-in scripting language: a small, dynamically typed, indentation-free
language with JavaScript-like braces and GDScript-like keywords. It is written from scratch in
`engine-core/.../script/` (lexer, parser, interpreter, and the engine bridge in `ScriptRuntime.kt`),
it is interpreted — never compiled to bytecode — and it runs on Android without any extra runtime.

Scripts live in the project's `scripts/` folder and are attached to nodes with a **ScriptNode**:

```json
{
  "type": "ScriptNode",
  "name": "Behaviour",
  "properties": { "scriptPath": "scripts/game.lumen" }
}
```

The editor's Script panel edits them in place and re-registers the source on every keystroke, so a
running game picks up edits on the next frame (live reload). On desktop, `installLumenScripts(game)`
resolves `scripts/*.lumen` from the project folder.

---

## 1. The language in one screen

```lumen
# Comments start with '#'.

export var speed = 105.0        # exported: shown in the inspector, saved in the scene
var total_coins = 9             # script-local state
const GRAVITY = 900.0

func ready() {                  # called once when the node enters the tree
    print("ready, engine " + engine.version)
}

func physics_process(delta) {   # fixed step, 60 Hz by default
    var player = get_node("Player")
    var axis = input.axis("move")
    player.velocity.x = axis.x * speed
    if input.just_pressed("jump") and player.on_floor {
        player.velocity.y = -320.0
        play_sound("lib://packs/base/audio/jump.wav")
    }
    if not player.on_floor {
        player.velocity.y = min(player.velocity.y + GRAVITY * delta, 480.0)
    }
    player.move_and_slide(delta)
}

func process(delta) { }         # every rendered frame
func draw(r, alpha) { }         # custom drawing through the renderer bridge
func exit_tree() { }            # node (or scene) is leaving the tree
func destroy() { }              # instance freed
```

**Syntax**

| Element | Form |
| --- | --- |
| Variables | `var x = 1`, `const K = 2`, `export var speed = 10` |
| Functions | `func name(a, b) { ... }`, returns with `return value` |
| Conditionals | `if cond { } elif cond2 { } else { }` |
| Loops | `while cond { }`, `for item in list { }`, `break`, `continue` |
| Operators | `+ - * / %`, `== != < <= > >=`, `and or not` (`&& \|\| !` also work) |
| Literals | numbers, `"strings"`, `true`, `false`, `null`, `[1, 2, 3]`, `{"key": value}` |
| Grouping | `( )` for expressions, `{ }` for blocks, `[ ]` for indexing |

Numbers are doubles, strings concatenate with `+`, lists and dictionaries are mutable and
reference-typed. Blocks are **not** indentation sensitive. There is no `if` *expression* — use a
statement plus a variable, or `bool(...)`.

## 2. Lifecycle callbacks

The runtime calls these functions on the script's node when they exist:

| Callback | When |
| --- | --- |
| `ready()` | node entered the tree, after children are ready |
| `process(delta)` | every frame, with the frame's delta in seconds |
| `update(delta)` | alias of `process` |
| `physics_process(delta)` | every fixed physics step (60 Hz default) |
| `draw(renderer, alpha)` | inside the render pass, only if the function exists |
| `exit_tree()` / `on_tree_exit()` | node is leaving the tree |
| `destroy()` | instance freed |

Signals connect to any function of the script: `connect("timeout", "on_timeout")`,
`node.connect("died", "on_died")`, or `tween_callback(...)`/`set_timer(seconds, "method_name")`.

## 3. Builtins

**Engine & scene**

| Builtin | Notes |
| --- | --- |
| `print(a, b, …)` | writes to the engine log (visible in the Studio console) |
| `get_node(path)` / `find_node(path)` | relative, `./`, `../`, absolute `/` and `*` globs; `""`/`"."` is self |
| `find_children(pattern = "*", type = "", recursive = true)` | returns a list |
| `queue_free()` / `node.queue_free()` | frees at the end of the frame |
| `instantiate(scene_path, parent = self)` | deep-copies a scene (prefab) into the tree |
| `load_scene(path)` | loads a scene without adding it |
| `change_scene(path)` | switches the current scene |
| `set_timer(seconds, method_name)` | one-shot timer on this node's script |
| `call_deferred(method_name, …)` | runs after the current frame |
| `tween_property(target, "property", value, seconds, easing)` | returns a tween handle |
| `tween_callback(seconds, method_name)` | delayed call, chainable with `.property(...)/.callback(...)` |
| `emit_signal(name, …)`, `connect_signal(node, name, method)` | script-level signals |
| `play_sound(path, volume = 1)` | fire-and-forget SFX through the mixer |
| `randf()`, `randi(from, to)`, `randomize()`, `now()` | deterministic-friendly helpers |
| `add_to_group(name)`, `remove_from_group(name)`, `is_in_group(name)`, `nodes_in_group(name)` | groups |
| `assert(condition, message)` | stops the script with a clear error |
| `pause_tree()`, `resume_tree()` | pause the whole tree |

**Math** — `sin cos tan asin acos atan2 sqrt abs floor ceil round sign pow min max clamp lerp damp
smoothstep wrap pingpong deg_to_rad rad_to_deg lerp_angle`

**Values** — `len(x)` (list/string/dict size), `str(x)`, `num(x)`, `int(x)`, `bool(x)`,
`range(n)`, `range(from, to)`, `range(from, to, step)`

**Constructors** — `vec2(x)` / `vec2(x, y)` (alias `vec`), `rect(x, y, w, h)`,
`color("name")` / `color(r, g, b)` / `color(r, g, b, a)`

## 4. Engine globals

| Global | Contents |
| --- | --- |
| `input` | `pressed(action)`, `just_pressed(action)`, `just_released(action)`, `strength(action)`, `axis(action)` → vec2, `tap(action)`, `consume()`, plus `mouse_position`, `screen_width`, `screen_height`, `pointer_count` |
| `scene` | the current scene's root node |
| `engine` | `name`, `version`, `api_version`, `platform` |

```lumen
var axis = input.axis("move")            # -1..1 per axis (keys, stick or on-screen buttons)
var jump = input.just_pressed("jump")
if input.tap("fire") { shoot() }
```

## 5. Node objects

Every node exposes these through `get_node(...)`, `scene`, `find_children(...)` or a signal
argument. Reading an unknown property or calling an unknown method raises a `ScriptError` with the
line number — typos fail loudly.

**All nodes**

* properties: `name`, `type`, `children`, `child_count`, `parent`, `node_path`, `is_ready`,
  `visible`, `groups`, `active`
* methods: `get_node(path)`, `get_parent()`, `get_child(i)`, `find_children(pattern, type, recursive)`,
  `add_child(node)`, `remove_child(node)`, `add_to_group(name)`, `remove_from_group(name)`,
  `is_in_group(name)`, `queue_free()`, `set_timer(seconds, method)`, `emit(name, …)`,
  `connect(name, method)`, `disconnect(name)`, `get(property)`, `set(property, value)`,
  `has(property)`, `call_deferred(method, …)`, `call_method(name, …)`, `has_method(name)`,
  `spawn(...)`
* signals: `ready`, `tree_exited`, `freed`, plus node-specific ones

**Node2D** — `position`, `global_position`, `rotation`, `global_rotation`, `scale`, `global_scale`,
`z_index`, `modulate`, `opacity`; `velocity`/`mass`/`gravity_scale` for physics bodies;
`pointing_right` reflects `scale.x >= 0`.

**Physics bodies** — `velocity`, `on_floor`, `on_wall`, `on_ceiling`,
`move_and_slide(delta)` (returns the collision result), `move_and_collide(delta)`,
`apply_impulse(vector)`, `jump(force)`, `is_on_floor()`

**Sprites** — `flipX`/`flipY`, `frame`, `play_animation(name)`, `pause_animation()`,
`resume_animation()`, `set_frame(index)`

**Gameplay helpers** — `damage(amount)`, `heal(amount)`, `add_score(points)`, `set_count(value, max)`,
`set_value(value)`, `press()`, `release()`, `is_pressed()`, `flash(node, seconds)`,
`shake_camera(strength, seconds)`

**Node signals (examples)**

| Node | Signals |
| --- | --- |
| `Timer` | `timeout` |
| `HealthNode` | `damaged`, `healed`, `died` |
| `ScoreTracker` | `score_changed`, `combo_changed`, `new_best` |
| `AnimationPlayer` | `finished` |
| `AnimatedSprite2D` | `frame_changed`, `animation_finished` |
| `Button` / `TouchButton` | `clicked`, `pressed`, `released` |
| `Area2D` | `body_entered`, `body_exited` |

## 6. Values and methods

| Type | Members |
| --- | --- |
| `vec2` | fields `x`, `y`; methods `length()`, `length_squared()`, `normalized()`, `distance_to(v)`, `dot(v)`, `angle()`, `rotated(a)`, `lerp(v, t)`, `direction_to(v)`, `clamp_length(max)`; operators `+ - * /` with vectors and numbers |
| `rect` | fields `x`, `y`, `w`, `h`; `position`, `size`, `end`; methods `contains(point)`, `intersects(rect)`, `grow(n)`, `center()` |
| `color` | fields `r`, `g`, `b`, `a`; methods `lerp(other, t)`, `darkened(f)`, `lightened(f)`; named constructors via `color("red")` |
| `list` | properties `size`, `length`, `count`, `empty`, `first`, `last`; methods `append/push`, `pop`, `has/contains`, `find/index_of`, `join(sep)`, `slice(from, to)`, `sorted()`, `sort()`, `shuffled()`, `duplicate()` |
| `string` | properties `size`, `length`, `empty`, `upper`, `lower`; methods `split(sep)`, `strip()`, `replace(a, b)`, `to_int()`, `to_float()`, `begins_with(s)`, `ends_with(s)`, `contains(s)`, `substr(from, len)` |
| `dictionary` | `has(key)`, `contains(key)`, `keys()`, `values()`, `size`; index with `d["key"]` |

Reading `null`, a missing struct field or a missing dictionary key is an error (use `has(...)` to
test), and lists/dictionaries are compared by reference.

## 7. Recipes

**Camera shake and hit feedback**

```lumen
func hurt(amount) {
    health -= amount
    flash(get_node("Player"), 0.25)
    shake_camera(3.0, 0.4)
    if health <= 0.0 {
        emit_signal("died")
        change_scene("scenes/game_over.scene.json")
    }
}
```

**Collectibles and HUD**

```lumen
var found = 0
var total = 9

func collect(coin) {
    coin.visible = false
    found += 1
    get_node("UI/HUD/CoinBar").set_count(found, total)
    play_sound("lib://packs/base/audio/coin.wav")
}
```

**Spawning enemies on a timer**

```lumen
func ready() {
    set_timer(2.0, "wave")
}

func wave() {
    var enemy = instantiate("scenes/prefabs/enemy.scene.json", get_node("Enemies"))
    enemy.position = vec2(randi(40, 440), -8.0)
    set_timer(2.0, "wave")
}
```

**Tweens**

```lumen
func ready() {
    var sprite = get_node("Sprite")
    tween_property(sprite, "opacity", 0.0, 0.6, "ease_in")
        .callback("after_fade")
}

func after_fade() {
    get_node("Sprite").visible = false
}
```

## 8. Errors and debugging

* Errors carry the script path, line number and a description, and are reported to the engine log
  (visible in the Studio console and in `lumen2d --check` output, which fails on any `ERROR` line).
* `assert(condition, "message")` fails fast during development.
* `print()` output is tagged `Script` in the log.
* Unknown properties/methods/off-arity calls are errors rather than silent `null`s.
* The interpreter has a call-depth limit and reports runaway recursion instead of crashing.

## 9. Registering scripts from code

```kotlin
val game = Game(DesktopPlatform(), project)
installLumenScripts(game)                    // registers scripts/*.lumen from the project
game.registerScript("scripts/extra.lumen", source)   // or feed source directly (live reload)
```

`installLumenScripts` lives in `engine-core` (`dev.lumen2d.core.script`), so every host — desktop,
Android, an embedded tool — wires scripts the same way.
