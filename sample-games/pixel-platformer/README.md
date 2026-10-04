# Pixel Platformer

A complete platformer: a tilemap level with pits, one-way platforms and spikes, coins to
collect, a patrolling slime, a goal flag, a HUD, a title screen and sound effects.

```sh
lumen2d --game sample-games/pixel-platformer
```

Controls: **A/D** or **←/→** move, **Space/Z** jump, **Esc** pauses.
The same project shows touch buttons on phones — nothing in the scene is desktop-specific.

Try editing `scripts/game.lumen` while the game runs in Lumen Studio: the script reloads
and the level keeps its state.

| file | what it is |
|------|------------|
| `scenes/menu.scene.json` | title screen (the start scene) |
| `scenes/level_1.scene.json` | the level: tilemap, coins, slime, spikes, goal flag |
| `scripts/menu.lumen` | any accept/fire press starts the level |
| `scripts/game.lumen` | movement, coyote time, coin pickups, HUD, game over |
| `project.lumen` | resolution, gravity, audio buses, asset packs |
| `input_map.json` | keyboard and touch bindings |
