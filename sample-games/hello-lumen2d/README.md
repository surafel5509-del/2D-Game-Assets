# Hello Lumen2D

The smallest complete Lumen2D game: a tilemap floor, a character with an animated sprite, a
camera that follows it and a script that reads the input map.

```sh
lumen2d --game sample-games/hello-lumen2d      # play it
lumen2d --check sample-games/hello-lumen2d     # headless smoke test
lumen2d --new "My Game"                        # start your own
```

| file | what it is |
|------|------------|
| `scripts/player.lumen` | movement, gravity and jumping |
| `scenes/main.scene.json` | the level — open it in Lumen Studio |
| `project.lumen` | resolution, gravity, audio buses, asset packs |
| `input_map.json` | keyboard and touch bindings |
