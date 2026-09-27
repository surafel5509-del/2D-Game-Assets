package com.world2d.engine.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mutable project document. References use IDs rather than paths, so renames do not break scenes. */
public final class GameProject {
    public JSONObject data;
    public GameProject(JSONObject value) { data = value; }

    public static GameProject create(String name, String genre, int width, int height) {
        long now = System.currentTimeMillis();
        String safe = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (safe.isEmpty()) safe = "mygame";
        JSONObject scene = newScene("Main Scene", width, height, "#273d50");
        GameProject p = new GameProject(J.o("format", 1, "id", J.id("project"), "name", name,
            "packageId", "com.world2d." + safe, "version", "1.0.0", "genre", genre,
            "orientation", "landscape", "width", width, "height", height, "gravity", 800,
            "fps", 60, "scenes", new JSONArray().put(scene), "startSceneId", scene.optString("id"),
            "assets", new JSONArray(), "scripts", new JSONArray(), "animations", new JSONArray(),
            "prefabs", new JSONArray(), "favorites", new JSONArray(), "input", defaultInput(),
            "createdAt", now, "updatedAt", now));
        return p;
    }
    public String id() { return data.optString("id"); }
    public String name() { return data.optString("name", "Untitled World"); }
    public JSONArray scenes() { return J.arr(data, "scenes"); }
    public JSONArray assets() { return J.arr(data, "assets"); }
    public JSONArray scripts() { return J.arr(data, "scripts"); }
    public JSONArray animations() { return J.arr(data, "animations"); }
    public JSONArray prefabs() { return J.arr(data, "prefabs"); }
    public JSONObject scene(String id) {
        JSONArray scenes = scenes();
        for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = J.at(scenes, i);
            if (scene != null && (scene.optString("id").equals(id) || scene.optString("name").equals(id))) return scene;
        }
        return null;
    }
    public JSONObject firstScene() {
        JSONObject scene = scene(data.optString("startSceneId"));
        return scene == null ? J.at(scenes(), 0) : scene;
    }
    public static JSONArray nodes(JSONObject scene) { return J.arr(scene, "nodes"); }
    public static JSONObject node(JSONObject scene, String id) {
        if (scene == null) return null;
        JSONArray nodes = nodes(scene);
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = J.at(nodes, i);
            if (node != null && node.optString("id").equals(id)) return node;
        }
        return null;
    }
    public static JSONObject newScene(String name, int width, int height, String background) {
        return J.o("id", J.id("scene"), "name", name, "width", width, "height", height,
            "background", background, "nodes", new JSONArray());
    }
    public static JSONObject newNode(String name, double x, double y, double width, double height) {
        return J.o("id", J.id("node"), "name", name, "parentId", JSONObject.NULL,
            "transform", J.o("x", x, "y", y, "width", width, "height", height,
                "rotation", 0, "scaleX", 1, "scaleY", 1, "opacity", 1),
            "components", new JSONArray(), "visible", true, "locked", false,
            "layer", 0, "tags", new JSONArray(), "metadata", new JSONObject());
    }
    public static JSONObject sprite(String assetId) { return J.o("type", "sprite", "assetId", assetId, "tint", "#ffffff"); }
    public static JSONObject body(String mode, double gravity) {
        return J.o("type", "body", "mode", mode, "gravityScale", gravity, "mass", 1,
            "friction", 0.2, "bounce", 0, "vx", 0, "vy", 0);
    }
    public static JSONObject collider(double width, double height, boolean sensor) {
        return J.o("type", "collider", "shape", "box", "width", width, "height", height,
            "radius", Math.min(width, height) / 2, "sensor", sensor, "offsetX", 0, "offsetY", 0);
    }
    public static JSONObject controller(String style) {
        return J.o("type", "controller", "style", style, "speed", style.equals("car") ? 310 : 210,
            "jump", 400);
    }
    public static JSONObject component(JSONObject node, String type) {
        JSONArray list = J.arr(node, "components");
        for (int i = 0; i < list.length(); i++) {
            JSONObject component = J.at(list, i);
            if (component != null && type.equals(component.optString("type"))) return component;
        }
        return null;
    }
    public static void addComponent(JSONObject node, JSONObject component) { J.arr(node, "components").put(component); }
    public static void tag(JSONObject node, String tag) { J.arr(node, "tags").put(tag); }
    public static boolean tagged(JSONObject node, String tag) {
        JSONArray tags = J.arr(node, "tags");
        for (int i = 0; i < tags.length(); i++) if (tag.equals(tags.optString(i))) return true;
        return false;
    }
    public static double x(JSONObject node) { return J.number(node.optJSONObject("transform"), "x", 0); }
    public static double y(JSONObject node) { return J.number(node.optJSONObject("transform"), "y", 0); }
    public static JSONObject at(JSONObject scene, int index) { return J.at(nodes(scene), index); }
    public static List<JSONObject> subtree(JSONObject scene, String rootId) {
        List<JSONObject> result = new ArrayList<>();
        JSONObject root = node(scene, rootId);
        if (root == null) return result;
        result.add(root);
        Set<String> seen = new HashSet<>(); seen.add(rootId);
        boolean added;
        do {
            added = false;
            JSONArray nodes = nodes(scene);
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject child = J.at(nodes, i);
                if (child == null || seen.contains(child.optString("id"))) continue;
                if (seen.contains(child.optString("parentId"))) {
                    result.add(child); seen.add(child.optString("id")); added = true;
                }
            }
        } while (added);
        return result;
    }
    public static void removeNode(JSONObject scene, String id) {
        Set<String> ids = new HashSet<>();
        for (JSONObject node : subtree(scene, id)) ids.add(node.optString("id"));
        JSONArray nodes = nodes(scene);
        for (int i = nodes.length() - 1; i >= 0; i--)
            if (ids.contains(J.at(nodes, i).optString("id"))) nodes.remove(i);
    }
    public static JSONObject duplicate(JSONObject scene, String id) {
        List<JSONObject> originals = subtree(scene, id);
        if (originals.isEmpty()) return null;
        java.util.Map<String, String> mapping = new java.util.HashMap<>();
        for (JSONObject n : originals) mapping.put(n.optString("id"), J.id("node"));
        JSONObject first = null;
        for (JSONObject original : originals) {
            JSONObject clone = J.copy(original);
            J.put(clone, "id", mapping.get(original.optString("id")));
            String parent = original.optString("parentId");
            if (mapping.containsKey(parent)) J.put(clone, "parentId", mapping.get(parent));
            if (first == null) {
                first = clone; J.put(clone, "name", original.optString("name") + " copy");
                JSONObject t = J.obj(clone, "transform");
                J.put(t, "x", t.optDouble("x") + 24); J.put(t, "y", t.optDouble("y") + 24);
            }
            nodes(scene).put(clone);
        }
        return first;
    }
    public void touch() { J.put(data, "updatedAt", System.currentTimeMillis()); }
    public GameProject snapshot() { return new GameProject(J.copy(data)); }
    public static JSONObject defaultInput() {
        return J.o("left", new JSONArray().put("ArrowLeft").put("KeyA"),
            "right", new JSONArray().put("ArrowRight").put("KeyD"),
            "up", new JSONArray().put("ArrowUp").put("KeyW"),
            "down", new JSONArray().put("ArrowDown").put("KeyS"),
            "jump", new JSONArray().put("Space"), "fire", new JSONArray().put("KeyJ").put("Enter"),
            "interact", new JSONArray().put("KeyE"), "pause", new JSONArray().put("Escape"));
    }
    public static void validate(JSONObject data) throws JSONException {
        if (data.optInt("format") != 1 || data.optString("id").isEmpty() || data.optString("name").isEmpty())
            throw new JSONException("Not a 2D WORLD project.");
        JSONArray scenes = data.optJSONArray("scenes"), assets = data.optJSONArray("assets");
        if (scenes == null || scenes.length() < 1 || scenes.length() > 40 || assets == null || assets.length() > 3000)
            throw new JSONException("Scenes or assets missing / exceed safety limits.");
        Set<String> sceneIds = new HashSet<>();
        for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i);
            if (scene == null || scene.optInt("width") < 100 || scene.optInt("width") > 8192 ||
                scene.optInt("height") < 100 || scene.optInt("height") > 8192 ||
                scene.optJSONArray("nodes") == null || scene.optJSONArray("nodes").length() > 5000 ||
                scene.optString("id").isEmpty() || !sceneIds.add(scene.optString("id")))
                throw new JSONException("Invalid scene dimensions, ID or node count.");
            Set<String> nodeIds = new HashSet<>();
            JSONArray nodes = scene.optJSONArray("nodes");
            for(int n=0;n<nodes.length();n++){
                JSONObject node=nodes.optJSONObject(n);
                if(node==null||node.optString("id").isEmpty()||!nodeIds.add(node.optString("id")))
                    throw new JSONException("A scene contains an invalid or repeated object ID.");
            }
        }
        if(!sceneIds.contains(data.optString("startSceneId")))
            throw new JSONException("The starting scene is missing.");
        JSONArray scripts=data.optJSONArray("scripts"),clips=data.optJSONArray("animations"),prefabs=data.optJSONArray("prefabs");
        if(scripts!=null){
            if(scripts.length()>1000)throw new JSONException("Too many scripts.");
            for(int i=0;i<scripts.length();i++)if(scripts.optJSONObject(i)==null||
                    scripts.optJSONObject(i).optString("source").length()>50000)
                throw new JSONException("Invalid or oversized script.");
        }
        if(clips!=null){
            if(clips.length()>1000)throw new JSONException("Too many animations.");
            for(int i=0;i<clips.length();i++){
                JSONObject clip=clips.optJSONObject(i);
                if(clip==null||(clip.optJSONArray("frames")!=null&&clip.optJSONArray("frames").length()>2000)||
                    (clip.optJSONArray("tracks")!=null&&clip.optJSONArray("tracks").length()>64)||
                    (clip.optJSONArray("events")!=null&&clip.optJSONArray("events").length()>2000))
                    throw new JSONException("Invalid or oversized animation.");
            }
        }
        if(prefabs!=null){
            if(prefabs.length()>500)throw new JSONException("Too many prefabs.");
            for(int i=0;i<prefabs.length();i++)if(prefabs.optJSONObject(i)==null||
                    (prefabs.optJSONObject(i).optJSONArray("nodes")!=null&&
                     prefabs.optJSONObject(i).optJSONArray("nodes").length()>5000))
                throw new JSONException("Invalid or oversized prefab.");
        }
    }
}
