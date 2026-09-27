package com.world2d.engine.data;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Internal project files + Android's Storage Access Framework. Never requests broad storage permission. */
public final class ProjectStore {
    private static final int MAX_IMPORT_BYTES = 60 * 1024 * 1024;
    private final Context context;
    private final File projects;
    public ProjectStore(Context context) {
        this.context = context.getApplicationContext();
        projects = new File(context.getFilesDir(), "projects");
        if (!projects.exists()) projects.mkdirs();
    }
    public File folder(String id) { return new File(projects, id.replaceAll("[^a-zA-Z0-9_-]", "")); }
    public File assetFile(GameProject project, String src) {
        if (src == null || !src.matches("assets/[a-zA-Z0-9_-]+\\.(png|jpg|webp|gif|wav|mp3|ogg|m4a|aac)")) return null;
        return new File(folder(project.id()), src);
    }
    public List<GameProject> list() {
        List<GameProject> result = new ArrayList<>();
        File[] dirs = projects.listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")) continue;
            try { result.add(load(dir.getName())); } catch (Exception ignored) { /* corrupt project cannot crash dashboard */ }
        }
        result.sort((a, b) -> Long.compare(b.data.optLong("updatedAt"), a.data.optLong("updatedAt")));
        return result;
    }
    public GameProject load(String id) throws IOException, JSONException {
        File file = new File(folder(id), "project.json");
        if (!file.isFile()) throw new IOException("Project file not found.");
        byte[] bytes = readLimited(new AtomicFile(file).openRead(), MAX_IMPORT_BYTES);
        JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        GameProject.validate(data);
        return new GameProject(data);
    }
    public void save(GameProject project, boolean backup) throws IOException {
        File dir = folder(project.id());
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create project directory.");
        File file = new File(dir, "project.json");
        if (backup && file.isFile()) {
            File versions = new File(dir, "versions");
            versions.mkdirs();
            File destination = new File(versions, "snapshot-" + System.currentTimeMillis() + ".json");
            copyFile(file, destination);
            File[] snapshots = versions.listFiles((f, name) -> name.endsWith(".json"));
            if (snapshots != null && snapshots.length > 5) {
                java.util.Arrays.sort(snapshots, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < snapshots.length - 5; i++) snapshots[i].delete();
            }
        }
        project.touch();
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(J.pretty(project.data).getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
            new File(dir, "recovery.json").delete();
        } catch (IOException ex) {
            if (stream != null) atomic.failWrite(stream);
            throw ex;
        }
    }
    public void draft(GameProject project) throws IOException {
        File dir = folder(project.id()); dir.mkdirs();
        File draft = new File(dir, "recovery.json");
        AtomicFile atomic = new AtomicFile(draft); FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(J.pretty(project.data).getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (IOException ex) { if (stream != null) atomic.failWrite(stream); throw ex; }
    }
    public boolean hasRecovery(String id) {
        File draft = new File(folder(id), "recovery.json"), saved = new File(folder(id), "project.json");
        return draft.isFile() && draft.lastModified() > saved.lastModified();
    }
    public GameProject recover(String id) throws IOException, JSONException {
        File draft = new File(folder(id), "recovery.json");
        JSONObject json = new JSONObject(new String(readLimited(new AtomicFile(draft).openRead(), MAX_IMPORT_BYTES), StandardCharsets.UTF_8));
        GameProject.validate(json); return new GameProject(json);
    }
    public void discardRecovery(String id) { new File(folder(id), "recovery.json").delete(); }
    public List<File> snapshots(String id) {
        File[] versions = new File(folder(id), "versions").listFiles((d, name) -> name.endsWith(".json"));
        List<File> files = new ArrayList<>();
        if (versions != null) Collections.addAll(files, versions);
        files.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }
    public GameProject restoreSnapshot(String id, File snapshot) throws IOException, JSONException {
        if (!snapshot.getParentFile().equals(new File(folder(id), "versions"))) throw new IOException("Invalid snapshot path.");
        JSONObject json = new JSONObject(new String(readLimited(new FileInputStream(snapshot), MAX_IMPORT_BYTES), StandardCharsets.UTF_8));
        GameProject.validate(json);
        GameProject project = new GameProject(json); save(project, true); return project;
    }
    public void delete(String id) throws IOException {
        File dir = folder(id);
        if (!dir.getParentFile().equals(projects) || !dir.isDirectory()) throw new IOException("Project not found.");
        deleteTree(dir);
    }
    private static void deleteTree(File target) throws IOException {
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!target.delete()) throw new IOException("Cannot delete " + target.getName());
    }
    public GameProject duplicate(GameProject source) throws IOException {
        GameProject clone = source.snapshot();
        String old = source.id(), fresh = J.id("project");
        J.put(clone.data, "id", fresh); J.put(clone.data, "name", source.name() + " copy");
        J.put(clone.data, "createdAt", System.currentTimeMillis());
        File dest = folder(fresh); dest.mkdirs();
        JSONArray assets = clone.assets();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject entry = J.at(assets, i);
            File from = assetFile(source, entry.optString("src")), to = assetFile(clone, entry.optString("src"));
            if (from != null && from.isFile() && to != null) { to.getParentFile().mkdirs(); copyFile(from, to); }
        }
        save(clone, false); return clone;
    }
    public JSONObject importAsset(GameProject project, Uri uri) throws IOException {
        String label=uri.getLastPathSegment();
        try(Cursor cursor=context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst())label=cursor.getString(0);
        }catch(Exception ignored){ /* URI name remains available if provider rejects queries. */ }
        if(label==null||label.length()>100)label="Imported asset";
        if(label.contains("/"))label=label.substring(label.lastIndexOf('/')+1);
        String mime=context.getContentResolver().getType(uri);
        String extension;
        if(mime==null||mime.equals("application/octet-stream")){
            int dot=label.lastIndexOf('.');
            extension=dot<0?"":label.substring(dot+1).toLowerCase(java.util.Locale.ROOT);
            if(extension.equals("jpeg"))extension="jpg";
            if(!extension.matches("png|jpg|webp|gif|wav|mp3|ogg|m4a|aac"))
                throw new IOException("File type not recognized. Use PNG, JPG, WEBP, GIF, WAV, MP3, OGG or M4A.");
            mime=extension.matches("png|jpg|webp|gif")?"image/"+extension:"audio/"+extension;
        }else switch(mime){
            case "image/png":extension="png";break;
            case "image/jpeg":extension="jpg";break;
            case "image/webp":extension="webp";break;
            case "image/gif":extension="gif";break;
            case "audio/wav":case "audio/x-wav":case "audio/wave":extension="wav";break;
            case "audio/mpeg":case "audio/mp3":extension="mp3";break;
            case "audio/ogg":extension="ogg";break;
            case "audio/mp4":case "audio/x-m4a":extension="m4a";break;
            case "audio/aac":extension="aac";break;
            default:throw new IOException("Unsupported file type: "+mime);
        }
        String id = J.id("asset"); String src = "assets/" + id + "." + extension;
        File target = assetFile(project, src);
        if (target == null) throw new IOException("Invalid asset destination.");
        target.getParentFile().mkdirs();
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Cannot open selected file.");
            byte[] buffer = new byte[8192]; int count, total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count; if (total > 15 * 1024 * 1024) throw new IOException("Asset exceeds 15 MB safety limit.");
                out.write(buffer, 0, count);
            }
        } catch (IOException ex) { target.delete(); throw ex; }
        boolean image = mime.startsWith("image/");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        if (image) BitmapFactory.decodeFile(target.getAbsolutePath(), bounds);
        if (image && (bounds.outWidth <= 0 || bounds.outWidth > 8192 || bounds.outHeight <= 0 || bounds.outHeight > 8192)) {
            target.delete(); throw new IOException("Image is corrupt or exceeds 8192 pixels per side.");
        }
        JSONObject asset = J.o("id", id, "name", label, "kind", image ? "image" : "audio",
            "category", "Imported", "src", src, "width", Math.max(0, bounds.outWidth),
            "height", Math.max(0, bounds.outHeight), "tags", new JSONArray().put("imported"),
            "license", "User supplied — verify rights before distribution", "origin", "Project import");
        project.assets().put(asset); project.touch(); return asset;
    }
    public void exportZip(GameProject project, OutputStream destination) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            entry(zip, "project.json", J.pretty(project.data).getBytes(StandardCharsets.UTF_8));
            JSONArray assets = project.assets();
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = J.at(assets, i);
                String path = asset.optString("src"); File file = assetFile(project, path);
                if (file == null || !file.isFile()) throw new IOException("Missing asset: " + path);
                zip.putNextEntry(new ZipEntry(path));
                try (InputStream in = new FileInputStream(file)) { transfer(in, zip, MAX_IMPORT_BYTES); }
                zip.closeEntry();
            }
            entry(zip, "LICENSES.txt", "Kenney Pixel Platformer: CC0 (kenney.nl).\nGenerated 2D WORLD art and audio: CC0.\nUser-supplied assets retain their own licenses.\n".getBytes(StandardCharsets.UTF_8));
        }
    }
    public GameProject importZip(InputStream source) throws IOException, JSONException {
        File staging = new File(projects, ".import-" + J.id("temp"));
        staging.mkdirs(); JSONObject json = null; int total = 0, entries = 0;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                if (++entries > 3000) throw new IOException("Too many archive entries.");
                String name = item.getName();
                if (item.isDirectory()) { zip.closeEntry(); continue; }
                if (!name.equals("project.json") && !name.matches("assets/[a-zA-Z0-9_-]+\\.(png|jpg|webp|gif|wav|mp3|ogg|m4a|aac)")) {
                    // Still count ignored members so a ZIP bomb cannot bypass the 60 MB limit.
                    total += transfer(zip, new OutputStream() {
                        @Override public void write(int value) { }
                        @Override public void write(byte[] bytes, int offset, int size) { }
                    }, MAX_IMPORT_BYTES - total);
                    zip.closeEntry(); continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int size = transfer(zip, buffer, MAX_IMPORT_BYTES - total); total += size;
                if (total > MAX_IMPORT_BYTES) throw new IOException("Expanded project exceeds 60 MB.");
                if (name.equals("project.json")) {
                    if (size > 12 * 1024 * 1024) throw new IOException("Project data too large.");
                    json = new JSONObject(buffer.toString("UTF-8"));
                } else {
                    File target = new File(staging, name);
                    target.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(target)) { buffer.writeTo(out); }
                }
                zip.closeEntry();
            }
        } catch (Exception ex) {
            deleteTree(staging);
            if (ex instanceof IOException) throw (IOException) ex;
            if (ex instanceof JSONException) throw (JSONException) ex;
            throw new IOException(ex.getMessage());
        }
        try {
            if (json == null) throw new IOException("No project.json in archive.");
            GameProject.validate(json);
            GameProject project = new GameProject(json);
            for (int i = 0; i < project.assets().length(); i++) {
                String assetPath = J.at(project.assets(), i).optString("src");
                if (assetFile(project, assetPath) == null || !new File(staging, assetPath).isFile())
                    throw new IOException("Imported project is missing " + assetPath);
            }
            String id = J.id("project"); J.put(project.data, "id", id);
            J.put(project.data, "name", project.name() + " (imported)");
            File dest = folder(id); dest.mkdirs();
            File incoming = new File(staging, "assets");
            File[] contents = incoming.listFiles();
            if (contents != null) {
                File assets = new File(dest, "assets"); assets.mkdirs();
                for (File file : contents) copyFile(file, new File(assets, file.getName()));
            }
            save(project, false); return project;
        } finally { deleteTree(staging); }
    }
    public List<String> assetUses(GameProject project, String id) {
        List<String> uses = new ArrayList<>();
        for (int s = 0; s < project.scenes().length(); s++) {
            JSONObject scene = J.at(project.scenes(), s); JSONArray nodes = GameProject.nodes(scene);
            for (int n = 0; n < nodes.length(); n++) {
                JSONObject node = J.at(nodes, n); JSONArray components = J.arr(node, "components");
                for (int c = 0; c < components.length(); c++) {
                    JSONObject component = J.at(components, c);
                    if (id.equals(component.optString("assetId"))) uses.add(scene.optString("name") + " / " + node.optString("name"));
                    JSONObject cells = component.optJSONObject("cells");
                    if (cells != null) for (java.util.Iterator<String> keys = cells.keys(); keys.hasNext(); )
                        if (id.equals(cells.optString(keys.next()))) { uses.add(scene.optString("name") + " / " + node.optString("name") + " (tilemap)"); break; }
                }
            }
        }
        for(int p=0;p<project.prefabs().length();p++){
            JSONObject prefab=J.at(project.prefabs(),p);
            JSONArray prefabNodes=prefab.optJSONArray("nodes");
            if(prefabNodes==null)continue;
            for(int n=0;n<prefabNodes.length();n++){
                JSONArray components=J.arr(J.at(prefabNodes,n),"components");
                for(int c=0;c<components.length();c++){
                    JSONObject part=J.at(components,c);
                    if(id.equals(part.optString("assetId")))uses.add("Prefab / "+prefab.optString("name"));
                    JSONObject cells=part.optJSONObject("cells");
                    if(cells!=null)for(java.util.Iterator<String> keys=cells.keys();keys.hasNext();)
                        if(id.equals(cells.optString(keys.next()))){
                            uses.add("Prefab / "+prefab.optString("name")+" (tilemap)");break;
                        }
                }
            }
        }
        for(int a=0;a<project.animations().length();a++){
            JSONObject animation=J.at(project.animations(),a);
            JSONArray frames=animation.optJSONArray("frames");
            if(frames==null)continue;
            for(int f=0;f<frames.length();f++)
                if(id.equals(J.at(frames,f).optString("assetId")))
                    uses.add("Animation / "+animation.optString("name"));
        }
        return uses;
    }
    private static void entry(ZipOutputStream zip, String path, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(path)); zip.write(content); zip.closeEntry();
    }
    private static int transfer(InputStream in, OutputStream out, int limit) throws IOException {
        byte[] buffer = new byte[8192]; int n, total = 0;
        while ((n = in.read(buffer)) != -1) {
            total += n; if (total > limit) throw new IOException("File exceeds safety limit.");
            out.write(buffer, 0, n);
        }
        return total;
    }
    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            transfer(in, out, limit); return out.toByteArray();
        }
    }
    private static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(destination)) {
            transfer(in, out, MAX_IMPORT_BYTES);
        }
    }
}
