# Neon Shooter

A vertical shooter demonstrating prefab instancing (`spawn`), wave spawning with
`set_timer`, particle feedback, a score HUD and a restart flow.

```sh
lumen2d --game sample-games/neon-shooter
```

Controls: **←/→** or **A/D** steer, **Space/X** fires, **Space** restarts after a game over.

| file | what it is |
|------|------------|
| `scenes/space.scene.json` | the playable scene (the start scene) |
| `scenes/prefabs/bullet.scene.json` | bullet prefab instanced by `spawn` |
| `scenes/prefabs/enemy.scene.json` | enemy prefab, picked per wave |
| `scripts/game.lumen` | firing, wave spawning, collisions, score, restart |
| `project.lumen` | resolution, gravity, audio buses, asset packs |
| `input_map.json` | keyboard and touch bindings |
