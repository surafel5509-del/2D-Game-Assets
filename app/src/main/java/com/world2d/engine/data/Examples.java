package com.world2d.engine.data;

import com.world2d.engine.assets.AssetLibrary;
import org.json.JSONArray;
import org.json.JSONObject;

/** Five editable project documents created with the same scene, component and script format as user projects. */
public final class Examples {
    public static final String[] GENRES = {"platformer", "rpg", "shooter", "racing", "adventure"};
    public static final String[] NAMES = {"Forest Runner", "Lantern Valley", "Neon Outpost", "Circuit Run", "The Verdant Key"};
    public static final String[] DESCRIPTIONS = {
        "Run, jump, collect coins, and reach the summit.",
        "Talk to villagers and unlock the moonlit glade.",
        "Aim, fire and survive three enemy waves.",
        "Drive a modular car through a checkpoint course.",
        "Explore two worlds, find the key, restore the grove."
    };
    private Examples() {}
    public static GameProject create(String genre, AssetLibrary library) {
        switch (genre) {
            case "platformer": return platformer();
            case "rpg": return rpg();
            case "shooter": return shooter();
            case "racing": return racing(library);
            case "adventure": return adventure();
            case "puzzle": { GameProject p = rpg(); J.put(p.data, "name", "Puzzle Starter"); J.put(p.data, "genre", "puzzle"); return p; }
            case "arcade": { GameProject p = shooter(); J.put(p.data, "name", "Arcade Starter"); J.put(p.data, "genre", "arcade"); return p; }
            default: return GameProject.create("Untitled World", "empty", 960, 540);
        }
    }
    private static JSONObject art(JSONObject scene, String name, String id, double x, double y, double w, double h, int layer) {
        JSONObject node = GameProject.newNode(name, x, y, w, h);
        GameProject.addComponent(node, GameProject.sprite(id)); J.put(node, "layer", layer);
        GameProject.nodes(scene).put(node); return node;
    }
    private static JSONObject label(JSONObject scene, String text, int x, int y, int size, String color) {
        JSONObject n = GameProject.newNode(text, x, y, Math.max(330, text.length() * size * 0.65), size + 16);
        GameProject.addComponent(n, J.o("type", "label", "text", text, "fontSize", size, "color", color, "align", "center"));
        J.put(n, "layer", 10); GameProject.nodes(scene).put(n); return n;
    }
    private static JSONObject scripted(GameProject project, JSONObject node, String name, String source) {
        JSONObject script = J.o("id", J.id("script"), "name", name, "source", source);
        project.scripts().put(script);
        GameProject.addComponent(node, J.o("type", "script", "scriptId", script.optString("id")));
        return node;
    }
    private static JSONObject player(GameProject project, JSONObject scene, double x, double y, String style, String image) {
        JSONObject node = art(scene, style.equals("car") ? "Player car" : "Player", image, x, y,
            style.equals("car") ? 110 : 54, style.equals("car") ? 63 : 68, 6);
        GameProject.addComponent(node, GameProject.body("character", style.equals("platformer") ? 1 : 0));
        GameProject.addComponent(node, GameProject.collider(style.equals("car") ? 85 : 36, style.equals("car") ? 42 : 60, false));
        GameProject.addComponent(node, GameProject.controller(style));
        GameProject.addComponent(node, J.o("type", "camera", "zoom", 1, "smoothing", 0.12, "follow", true));
        GameProject.tag(node, "player");
        if (!style.equals("car")) {
            JSONObject animation = J.o("id", J.id("animation"), "name", "Idle breathe", "duration", 0.85,
                "fps", 12, "loop", "loop", "tracks", new JSONArray().put(J.o("property", "y", "keys",
                    new JSONArray().put(J.o("time", 0, "value", 0)).put(J.o("time", 0.42, "value", -4)).put(J.o("time", 0.85, "value", 0)))), "events", new JSONArray());
            project.animations().put(animation);
            GameProject.addComponent(node, J.o("type", "animation", "animationId", animation.optString("id"), "autoplay", true, "speed", 1));
        }
        return node;
    }
    private static JSONObject enemy(GameProject p, JSONObject scene, int x, int y, int index, boolean platformer) {
        JSONObject n = art(scene, "Wandering sentinel", "builtin:enemy-" + index, x, y, 49, 63, 5);
        GameProject.addComponent(n, GameProject.body("character", platformer ? 1 : 0));
        GameProject.addComponent(n, GameProject.collider(37, 54, true));
        GameProject.addComponent(n, J.o("type", "ai", "behavior", platformer ? "patrol" : "chase",
            "speed", platformer ? 40 : 59, "range", platformer ? 85 : 280));
        scripted(p, n, "Enemy contact", "on_collision(player):\n  add health -1\n  sound damage\n  emit Impact\n  message \"Careful! An enemy hit you.\"");
        GameProject.tag(n, "enemy"); return n;
    }
    private static void forest(JSONObject scene) {
        for (int i = 0; i < 9; i++) {
            art(scene, "Forest tree " + (i + 1), "builtin:nature-" + (i * 7), 40 + i * 126, 403 + i % 3 * 17, 106, 145, -5);
        }
    }
    private static void platformGround(JSONObject scene) {
        JSONObject cells = new JSONObject();
        for (int row = 10; row < 12; row++) for (int col = 0; col < 20; col++)
            J.put(cells, col + "," + row, "builtin:tile-" + (row == 10 ? 1 : 40));
        JSONObject map = GameProject.newNode("Ground tilemap", 480, 270, 960, 540);
        GameProject.addComponent(map, J.o("type", "tilemap", "tileSize", 48, "columns", 20,
            "rows", 12, "cells", cells, "collision", false));
        J.put(map, "layer", 1); J.put(map, "locked", true); GameProject.nodes(scene).put(map);
        JSONObject floor = GameProject.newNode("Ground collision", 480, 514, 960, 60);
        GameProject.addComponent(floor, GameProject.body("static", 0));
        GameProject.addComponent(floor, GameProject.collider(960, 60, false));
        J.put(floor, "locked", true); GameProject.nodes(scene).put(floor);
    }
    private static void floor(JSONObject scene, String texture) {
        JSONObject cells = new JSONObject();
        for (int row = 0; row < 9; row++) for (int col = 0; col < 15; col++)
            J.put(cells, col + "," + row, texture);
        JSONObject map = GameProject.newNode("World tilemap", 480, 270, 960, 540);
        GameProject.addComponent(map, J.o("type", "tilemap", "tileSize", 64, "columns", 15,
            "rows", 9, "cells", cells, "collision", false));
        J.put(map, "layer", -9); J.put(map, "locked", true); GameProject.nodes(scene).put(map);
    }
    private static JSONObject platform(JSONObject scene, int x, int y, int width) {
        JSONObject node = art(scene, "Solid platform", "builtin:texture-60", x, y, width, 24, 2);
        GameProject.addComponent(node, GameProject.body("static", 0));
        GameProject.addComponent(node, GameProject.collider(width, 24, false)); return node;
    }
    private static JSONObject coin(GameProject p, JSONObject scene, int x, int y) {
        JSONObject node = art(scene, "Sun coin", "builtin:prop-8", x, y, 32, 32, 7);
        GameProject.addComponent(node, GameProject.collider(30, 30, true));
        scripted(p, node, "Collect coin", "on_collision(player):\n  add score 1\n  sound coin\n  emit Sparkles\n  destroy self");
        GameProject.tag(node, "coin"); return node;
    }
    private static GameProject platformer() {
        GameProject p = GameProject.create("Forest Runner", "platformer", 960, 540);
        JSONObject scene = p.firstScene(); J.put(scene, "name", "Meadow Path"); J.put(scene, "background", "#477c8b");
        forest(scene); platformGround(scene); label(scene, "MEADOW PATH  /  01", 480, 50, 19, "#e6f7e0");
        platform(scene, 305, 387, 186); platform(scene, 572, 328, 185); platform(scene, 779, 408, 155);
        player(p, scene, 88, 422, "platformer", "builtin:player-0");
        coin(p, scene, 310, 340); coin(p, scene, 571, 279); coin(p, scene, 779, 358);
        JSONObject foe = enemy(p, scene, 650, 438, 4, true);
        JSONArray prefabNodes = new JSONArray().put(J.copy(foe));
        p.prefabs().put(J.o("id", J.id("prefab"), "name", "Wandering sentinel", "nodes", prefabNodes));
        JSONObject exit = art(scene, "Exit flag", "builtin:prop-26", 896, 443, 55, 80, 5);
        GameProject.addComponent(exit, GameProject.collider(46, 76, true));
        scripted(p, exit, "Summit gate", "on_collision(player):\n  if score >= 3: scene Summit\n  if score < 3: message \"Collect all three coins first!\"");
        GameProject.tag(exit, "exit");
        JSONObject summit = GameProject.newScene("Summit", 960, 540, "#5b778a"); p.scenes().put(summit);
        forest(summit); platformGround(summit);
        label(summit, "THE SUMMIT  /  02", 480, 56, 23, "#e8f7eb");
        label(summit, "You made it. Claim the treasure.", 480, 116, 18, "#d7e9f3");
        player(p, summit, 120, 424, "platformer", "builtin:player-0");
        JSONObject crown = art(summit, "Summit treasure", "builtin:prop-2", 775, 446, 60, 60, 5);
        GameProject.addComponent(crown, GameProject.collider(53, 55, true));
        scripted(p, crown, "Complete journey", "on_collision(player):\n  sound level\n  win");
        return p;
    }
    private static JSONObject pickup(GameProject p, JSONObject scene, String name, String asset, int x, int y, String stat) {
        JSONObject node = art(scene, name, asset, x, y, 42, 42, 6);
        GameProject.addComponent(node, GameProject.collider(35, 35, true));
        scripted(p, node, "Collect " + name, "on_collision(player):\n  add " + stat + " 1\n  sound coin\n  emit Sparkles\n  message \"Collected " + name + "!\"\n  destroy self");
        GameProject.tag(node, "pickup"); return node;
    }
    private static GameProject rpg() {
        GameProject p = GameProject.create("Lantern Valley", "rpg", 960, 540); J.put(p.data, "gravity", 0);
        JSONObject village = p.firstScene(); J.put(village, "name", "Lantern Village"); J.put(village, "background", "#426957");
        floor(village, "builtin:texture-0");
        for (int i = 0; i < 7; i++) art(village, "Willow tree", "builtin:nature-" + i * 7, 55 + i * 155, i % 2 == 0 ? 76 : 464, 80, 92, -2);
        art(village, "The old inn", "builtin:building-2", 730, 140, 150, 145, 0);
        art(village, "Village cottage", "builtin:building-0", 255, 133, 125, 130, 0);
        label(village, "LANTERN VILLAGE", 480, 47, 21, "#edf9e6");
        player(p, village, 440, 300, "topdown", "builtin:player-12");
        JSONObject elder = art(village, "Village elder", "builtin:npc-4", 590, 318, 52, 66, 5);
        scripted(p, elder, "Elder dialogue", "on_interact:\n  message \"Find two moon crystals. The eastern gate will open.\"\n  sound notification");
        GameProject.tag(elder, "npc");
        JSONObject merchant = art(village, "Traveling merchant", "builtin:npc-9", 295, 348, 51, 66, 5);
        scripted(p, merchant, "Merchant dialogue", "on_interact:\n  message \"A wise explorer saves their progress.\"\n  save inventory\n  sound success");
        GameProject.tag(merchant, "npc");
        pickup(p, village, "Moon crystal", "builtin:prop-9", 135, 250, "inventory");
        pickup(p, village, "Moon crystal", "builtin:prop-9", 783, 363, "inventory");
        enemy(p, village, 800, 450, 10, false);
        JSONObject gate = art(village, "Glade passage", "builtin:prop-27", 903, 267, 72, 110, 4);
        GameProject.addComponent(gate, GameProject.collider(74, 100, true));
        scripted(p, gate, "Unlock glade", "on_collision(player):\n  if inventory >= 2: scene \"Moonlit Glade\"\n  if inventory < 2: message \"Find two crystals first.\"");
        GameProject.tag(gate, "exit");
        JSONObject glade = GameProject.newScene("Moonlit Glade", 960, 540, "#31465e"); p.scenes().put(glade);
        floor(glade, "builtin:texture-5");
        for (int i = 0; i < 8; i++) art(glade, "Moonlit tree", "builtin:nature-" + (i * 7 + 14) % 42, 55 + i * 132, i % 2 == 0 ? 80 : 449, 92, 100, -2);
        label(glade, "MOONLIT GLADE", 480, 52, 23, "#e3edff");
        player(p, glade, 123, 280, "topdown", "builtin:player-12");
        JSONObject treasure = art(glade, "Heart of the glade", "builtin:prop-23", 737, 280, 74, 74, 5);
        GameProject.addComponent(treasure, GameProject.collider(62, 62, true));
        scripted(p, treasure, "Restore the glade", "on_collision(player):\n  sound level\n  win");
        return p;
    }
    private static GameProject shooter() {
        GameProject p = GameProject.create("Neon Outpost", "shooter", 960, 540); J.put(p.data, "gravity", 0);
        JSONObject arena = p.firstScene(); J.put(arena, "name", "Outpost Arena"); J.put(arena, "background", "#343b58");
        floor(arena, "builtin:texture-75");
        for (int i = 0; i < 8; i++) art(arena, "Safety barrier", "builtin:prop-16", 115 + i * 105, i % 2 == 0 ? 75 : 470, 56, 56, -1);
        label(arena, "NEON OUTPOST", 480, 49, 24, "#f0e2fb");
        label(arena, "Move · aim · fire · survive", 480, 88, 16, "#d5c8ea");
        GameProject.tag(player(p, arena, 480, 270, "topdown", "builtin:player-20"), "can-shoot");
        for (int i = 0; i < 4; i++) {
            JSONObject foe = enemy(p, arena, 160 + i * 206, i % 2 == 0 ? 145 : 405, i * 4 + 2, false);
            J.put(J.obj(foe, "metadata"), "hp", 2);
        }
        return p;
    }
    private static JSONObject modularCar(GameProject p, AssetLibrary library, JSONObject scene, String id, String name, int x, int y) {
        AssetLibrary.Entry entry = library.get(p, id);
        JSONObject root = GameProject.newNode(name, x, y, entry.width, entry.height);
        GameProject.nodes(scene).put(root);
        if (entry.parts != null) for (int i = 0; i < entry.parts.length(); i++) {
            JSONObject part = entry.parts.optJSONObject(i);
            JSONObject node = GameProject.newNode(part.optString("name"), part.optDouble("x"), part.optDouble("y"),
                part.optDouble("width"), part.optDouble("height"));
            J.put(node, "parentId", root.optString("id"));
            GameProject.addComponent(node, GameProject.sprite("part:" + id + ":" + i));
            GameProject.nodes(scene).put(node);
        }
        return root;
    }
    private static GameProject racing(AssetLibrary library) {
        GameProject p = GameProject.create("Circuit Run", "racing", 1100, 650); J.put(p.data, "gravity", 0);
        JSONObject scene = p.firstScene(); J.put(scene, "name", "Greenfield Circuit"); J.put(scene, "background", "#58735f");
        for (int i = 0; i < 4; i++) {
            int[] x = {550, 550, 227, 873}; int[] y = {175, 475, 325, 325};
            int[] w = {850, 850, 200, 200}; int[] h = {180, 180, 380, 380};
            art(scene, "Asphalt track segment", "builtin:texture-90", x[i], y[i], w[i], h[i], -3);
        }
        for (int i = 0; i < 4; i++) art(scene, "Trackside tree", "builtin:nature-0",
            (i % 2 == 0 ? 83 : 1015), (i < 2 ? 80 : 570), 88, 97, 0);
        label(scene, "GREENFIELD CIRCUIT", 550, 53, 24, "#f9f1df");
        JSONObject car = modularCar(p, library, scene, "builtin:vehicle-2", "Player rally car", 350, 168);
        GameProject.tag(car, "player");
        GameProject.addComponent(car, GameProject.body("character", 0));
        GameProject.addComponent(car, GameProject.collider(88, 45, false));
        GameProject.addComponent(car, GameProject.controller("car"));
        GameProject.addComponent(car, J.o("type", "camera", "zoom", 0.9, "follow", true, "smoothing", 0.08));
        int[] cx = {855, 865, 236, 242}, cy = {166, 475, 475, 167};
        for (int i = 0; i < 4; i++) {
            JSONObject checkpoint = GameProject.newNode("Checkpoint " + (i + 1), cx[i], cy[i], 80, 110);
            GameProject.addComponent(checkpoint, GameProject.collider(115, 115, true));
            GameProject.tag(checkpoint, "checkpoint"); GameProject.nodes(scene).put(checkpoint);
            art(scene, "Checkpoint marker", "builtin:prop-26", cx[i] + 42, cy[i] - 45, 30, 41, 4);
        }
        JSONObject rival = modularCar(p, library, scene, "builtin:vehicle-10", "Fireline racer", 710, 475);
        J.put(J.obj(rival, "transform"), "rotation", 180);
        return p;
    }
    private static GameProject adventure() {
        GameProject p = GameProject.create("The Verdant Key", "adventure", 960, 540); J.put(p.data, "gravity", 0);
        JSONObject menu = p.firstScene(); J.put(menu, "name", "Main Menu"); J.put(menu, "background", "#304d5d");
        for (int i = 0; i < 7; i++) art(menu, "Forest silhouette", "builtin:nature-" + i * 7, i * 160, 430, 165, 190, -4);
        art(menu, "The Verdant Key", "builtin:prop-29", 480, 153, 84, 84, 1);
        label(menu, "THE VERDANT KEY", 480, 250, 42, "#effcde");
        label(menu, "An adventure in two worlds", 480, 307, 19, "#c6e4df");
        JSONObject start = label(menu, "TAP TO BEGIN", 480, 397, 25, "#c6f09e");
        GameProject.tag(start, "button");
        scripted(p, start, "Start adventure", "on_interact:\n  scene \"Whispering Woods\"");
        JSONObject woods = GameProject.newScene("Whispering Woods", 960, 540, "#4e7468"); p.scenes().put(woods);
        floor(woods, "builtin:texture-0"); forest(woods);
        label(woods, "WHISPERING WOODS", 480, 47, 20, "#f0f5e2");
        player(p, woods, 117, 285, "topdown", "builtin:player-8");
        JSONObject npc = art(woods, "Forest guide", "builtin:npc-12", 303, 298, 55, 67, 5);
        scripted(p, npc, "Guide dialogue", "on_interact:\n  message \"Seek the Verdant Key in the old chest. The eastern gate leads to the ruins.\"");
        GameProject.tag(npc, "npc");
        JSONObject key = pickup(p, woods, "Verdant Key", "builtin:prop-29", 653, 369, "inventory");
        J.put(key, "name", "Verdant Key");
        enemy(p, woods, 698, 209, 8, false);
        JSONObject gate = art(woods, "Ancient gate", "builtin:prop-27", 898, 273, 76, 110, 4);
        GameProject.addComponent(gate, GameProject.collider(78, 105, true));
        scripted(p, gate, "Ancient gate", "on_collision(player):\n  if inventory >= 1: scene \"Echoing Ruins\"\n  if inventory < 1: message \"Find the Verdant Key first.\"");
        JSONObject ruins = GameProject.newScene("Echoing Ruins", 960, 540, "#525b6a"); p.scenes().put(ruins);
        floor(ruins, "builtin:texture-80");
        for (int i = 0; i < 7; i++) art(ruins, "Ancient pillar", "builtin:prop-3", 58 + i * 145, i % 2 == 0 ? 116 : 460, 60, 75, 0);
        label(ruins, "ECHOING RUINS", 480, 50, 23, "#ede6f8");
        player(p, ruins, 110, 300, "topdown", "builtin:player-8");
        JSONObject guardian = enemy(p, ruins, 612, 280, 17, false);
        J.put(guardian, "name", "Ruins guardian"); J.put(J.obj(guardian, "metadata"), "hp", 3);
        GameProject.addComponent(guardian, J.o("type", "particles", "preset", "Magic", "rate", 12,
            "speed", 45, "lifetime", 1.2, "spread", 360, "gravity", -20,
            "size", 5, "color", "#c4a4ec", "emitting", true));
        JSONObject altar = art(ruins, "Restoration altar", "builtin:prop-23", 827, 289, 85, 85, 5);
        GameProject.addComponent(altar, GameProject.collider(80, 80, true));
        scripted(p, altar, "Restore grove", "on_collision(player):\n  if inventory >= 1: scene \"Grove Restored\"");
        JSONObject ending = GameProject.newScene("Grove Restored", 960, 540, "#5c9288"); p.scenes().put(ending);
        floor(ending, "builtin:texture-1"); forest(ending);
        label(ending, "THE GROVE IS RESTORED", 480, 173, 35, "#e7ffdc");
        label(ending, "A new chapter begins with you.", 480, 240, 20, "#dcefeb");
        JSONObject victory = GameProject.newNode("Victory event", 480, 330, 30, 30);
        scripted(p, victory, "Victory", "on_start:\n  sound level\n  win"); GameProject.nodes(ending).put(victory);
        return p;
    }
}
