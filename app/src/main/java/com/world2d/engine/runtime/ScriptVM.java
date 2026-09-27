package com.world2d.engine.runtime;

import com.world2d.engine.data.GameProject;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded, declarative script interpreter. It never evaluates Java, JS or shell commands. */
public final class ScriptVM {
    private static final Pattern EVENT = Pattern.compile("^on_(start|update|collision|interact|input|timer)(?:\\(([^)]+)\\))?:$");
    private static final Pattern CONDITION = Pattern.compile("^if\\s+([\\w.]+)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)\\s*:\\s*(.+)$");
    private static final Pattern WORDS = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\S+");
    private static final Set<String> OPS = new HashSet<>(Arrays.asList(
        "set", "add", "move", "destroy", "sound", "scene", "message", "emit", "save", "load", "spawn", "win"));
    private ScriptVM() {}
    public static List<String> validate(String source) {
        List<String> errors = new ArrayList<>();
        if (source == null || source.length() > 50000) { errors.add("Script exceeds 50 KB safety limit."); return errors; }
        boolean inEvent = false; String[] lines = source.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].trim(); if (text.isEmpty() || text.startsWith("#")) continue;
            if (EVENT.matcher(text).matches()) { inEvent = true; continue; }
            if (!inEvent) { errors.add("Line " + (i + 1) + ": add on_start: or another event first."); continue; }
            Matcher condition = CONDITION.matcher(text);
            if (text.startsWith("if ")) {
                if (!condition.matches()) { errors.add("Line " + (i + 1) + ": use if score >= 3: scene Next"); continue; }
                text = condition.group(4);
            }
            List<String> tokens = tokenize(text);
            if (tokens.isEmpty() || !OPS.contains(tokens.get(0))) {
                errors.add("Line " + (i + 1) + ": unknown action. Use set/add/move/destroy/sound/scene/message/emit/save/load/spawn/win.");
                continue;
            }
            String op = tokens.get(0); int count = tokens.size() - 1;
            int needed = op.equals("win") ? 0 : op.equals("set") || op.equals("add") ? 2 : op.equals("move") ? 3 : 1;
            if (count != needed) errors.add("Line " + (i + 1) + ": " + op + " expects " + needed + " argument(s). Quote text containing spaces.");
            else if (op.equals("set") || op.equals("add") || op.equals("move")) {
                for (int t = op.equals("move") ? 2 : 2; t < tokens.size(); t++) {
                    try { Double.parseDouble(tokens.get(t)); }
                    catch (NumberFormatException ex) { errors.add("Line " + (i + 1) + ": numeric value required."); break; }
                }
            }
        }
        return errors;
    }
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>(); Matcher matcher = WORDS.matcher(line);
        while (matcher.find()) {
            String value = matcher.group();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
            tokens.add(value);
        }
        return tokens;
    }
    public static void dispatch(GameRuntime runtime, JSONObject node, String event, JSONObject other, String filter, float dt) {
        JSONObject reference = GameProject.component(node, "script");
        if (reference == null) return;
        JSONObject script = runtime.script(reference.optString("scriptId"));
        if (script == null) { runtime.log("warning", "Missing script on " + node.optString("name")); return; }
        String source = script.optString("source");
        if (source.length() > 50000) return;
        boolean active = false; int budget = 0;
        for (String raw : source.split("\\r?\\n")) {
            String text = raw.trim(); if (text.isEmpty() || text.startsWith("#")) continue;
            Matcher header = EVENT.matcher(text);
            if (header.matches()) {
                active = header.group(1).equals(event);
                String eventFilter = header.group(2);
                if (active && eventFilter != null) {
                    active = eventFilter.equals(filter) || (other != null && (
                        eventFilter.equals(other.optString("name")) || GameProject.tagged(other, eventFilter)));
                }
                continue;
            }
            if (!active || ++budget > 64) continue;
            Matcher conditional = CONDITION.matcher(text);
            if (conditional.matches()) {
                double a = runtime.value(conditional.group(1), node, other);
                double b;
                try { b = Double.parseDouble(conditional.group(3)); } catch (Exception ex) { continue; }
                boolean yes;
                switch (conditional.group(2)) {
                    case ">": yes = a > b; break; case "<": yes = a < b; break;
                    case ">=": yes = a >= b; break; case "<=": yes = a <= b; break;
                    case "==": yes = a == b; break; default: yes = a != b;
                }
                if (!yes) continue;
                text = conditional.group(4);
            } else if (text.startsWith("if ")) continue;
            List<String> tokens = tokenize(text);
            if (tokens.isEmpty() || !OPS.contains(tokens.get(0))) continue;
            try { runtime.execute(tokens.get(0), tokens.subList(1, tokens.size()), node, other, dt); }
            catch (Exception ex) { runtime.log("error", "Script " + script.optString("name") + ": " + ex.getMessage()); }
        }
    }
}
