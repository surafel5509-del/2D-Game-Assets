package com.world2d.engine.assets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.util.LruCache;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.data.ProjectStore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 623 offline, real image/audio resources. Bitmaps are decoded lazily with an LRU memory budget. */
public final class AssetLibrary {
    public static final class Entry {
        public final String id, name, category, kind, path, license, origin, modular;
        public final int width, height;
        public final JSONArray parts;
        public final boolean builtin;
        public final double offsetX, offsetY;
        Entry(JSONObject o, boolean builtin) {
            this.id = o.optString("id"); this.name = o.optString("name");
            this.category = o.optString("category", "Imported"); this.kind = o.optString("kind", "image");
            this.path = o.optString(builtin ? "path" : "src");
            this.license = o.optString("license"); this.origin = o.optString("origin");
            this.modular = o.optString("modular");
            this.width = o.optInt("width", 64); this.height = o.optInt("height", 64);
            this.parts = o.optJSONArray("parts"); this.builtin = builtin;
            this.offsetX = o.optDouble("x"); this.offsetY = o.optDouble("y");
        }
    }
    private final Context context;
    private final ProjectStore store;
    private final List<Entry> builtins = new ArrayList<>();
    private final Map<String, Entry> index = new HashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private MediaPlayer previewPlayer;
    public AssetLibrary(Context context, ProjectStore store) throws IOException, JSONException {
        this.context = context.getApplicationContext(); this.store = store;
        try (InputStream in = context.getAssets().open("library/catalog.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) output.write(buffer, 0, n);
            JSONArray catalog = new JSONArray(output.toString("UTF-8"));
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject record = catalog.optJSONObject(i); if (record == null) continue;
                Entry entry = new Entry(record, true);
                builtins.add(entry); index.put(entry.id, entry);
                JSONArray parts = entry.parts;
                if (parts != null) for (int part = 0; part < parts.length(); part++) {
                    JSONObject info = parts.optJSONObject(part); if (info == null) continue;
                    JSONObject definition = J.o("id", "part:" + entry.id + ":" + part,
                        "name", info.optString("name"), "category", entry.category,
                        "kind", "image", "path", info.optString("path"),
                        "width", info.optInt("width"), "height", info.optInt("height"),
                        "x", info.optDouble("x"), "y", info.optDouble("y"),
                        "license", entry.license, "origin", entry.name);
                    index.put(definition.optString("id"), new Entry(definition, true));
                }
            }
        }
    }
    public int count() { return builtins.size(); }
    public List<Entry> all(GameProject project) {
        List<Entry> all = new ArrayList<>(builtins);
        if (project != null) {
            JSONArray imported = project.assets();
            for (int i = 0; i < imported.length(); i++) {
                JSONObject item = imported.optJSONObject(i);
                if (item != null) all.add(0, new Entry(item, false));
            }
        }
        return all;
    }
    public Entry get(GameProject project, String id) {
        Entry bundled = index.get(id);
        if (bundled != null) return bundled;
        if (project != null) {
            JSONArray imported = project.assets();
            for (int i = 0; i < imported.length(); i++) {
                JSONObject record = imported.optJSONObject(i);
                if (record != null && id.equals(record.optString("id"))) return new Entry(record, false);
            }
        }
        return null;
    }
    public Bitmap bitmap(GameProject project, String id, int desiredWidth) {
        Entry entry = get(project, id);
        if (entry == null || !entry.kind.equals("image")) return null;
        int sample = desiredWidth > 0 ? Math.max(1, Integer.highestOneBit(Math.max(1, entry.width * (entry.builtin ? 2 : 1) / desiredWidth))) : 1;
        String key = id + "/" + sample;
        Bitmap cached = cache.get(key); if (cached != null && !cached.isRecycled()) return cached;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.min(16, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            Bitmap decoded;
            if (entry.builtin) {
                try (InputStream input = context.getAssets().open(entry.path)) {
                    decoded = BitmapFactory.decodeStream(input, null, options);
                }
            } else {
                File file = store.assetFile(project, entry.path);
                decoded = file == null ? null : BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
            if (decoded != null) cache.put(key, decoded);
            return decoded;
        } catch (Exception ex) { return null; }
    }
    public void play(GameProject project, String id) throws IOException {
        Entry entry = get(project, id);
        if (entry == null || !entry.kind.equals("audio")) throw new IOException("Audio resource not found.");
        stopAudio();
        File file;
        if (entry.builtin) {
            File cacheDir = new File(context.getCacheDir(), "sfx"); cacheDir.mkdirs();
            file = new File(cacheDir, id.replaceAll("[^a-zA-Z0-9_-]", "") + ".wav");
            if (!file.isFile()) {
                try (InputStream in = context.getAssets().open(entry.path);
                     FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
        } else file = store.assetFile(project, entry.path);
        if (file == null || !file.isFile()) throw new IOException("Audio file is missing. Replace it in the asset browser.");
        previewPlayer = new MediaPlayer();
        try {
            previewPlayer.setDataSource(file.getAbsolutePath());
            previewPlayer.prepare(); previewPlayer.start();
            previewPlayer.setOnCompletionListener(player -> stopAudio());
        } catch (Exception ex) { stopAudio(); throw new IOException("Cannot play this audio file.", ex); }
    }
    public void stopAudio() {
        if (previewPlayer != null) { try { previewPlayer.stop(); } catch (Exception ignored) { }
            previewPlayer.release(); previewPlayer = null; }
    }
    public JSONObject readPreset(String filename) throws IOException, JSONException {
        try (InputStream in = context.getAssets().open("library/" + filename)) {
            byte[] bytes = new byte[in.available()]; int count = in.read(bytes);
            return new JSONObject().put("presets", new JSONArray(new String(bytes, 0, Math.max(0, count), "UTF-8")));
        }
    }
    public JSONArray presets(String filename) {
        try (InputStream in = context.getAssets().open("library/" + filename)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new JSONArray(out.toString("UTF-8"));
        } catch (Exception ex) { return new JSONArray(); }
    }
    public boolean favorite(GameProject project, String id) {
        JSONArray favorites = J.arr(project.data, "favorites");
        for (int i = 0; i < favorites.length(); i++) if (id.equals(favorites.optString(i))) return true;
        return false;
    }
    public void toggleFavorite(GameProject project, String id) {
        JSONArray favorites = J.arr(project.data, "favorites");
        for (int i = 0; i < favorites.length(); i++) if (id.equals(favorites.optString(i))) {
            favorites.remove(i); project.touch(); return;
        }
        favorites.put(id); project.touch();
    }
}
