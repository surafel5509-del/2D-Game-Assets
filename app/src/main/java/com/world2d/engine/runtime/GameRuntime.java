package com.world2d.engine.runtime;

import android.content.Context;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed-step-friendly runtime: input, gravity, AABB/sensor collision, AI, scripts, particles and sound. */
public final class GameRuntime {
    public static final class Particle {
        public float x, y, vx, vy, size, life, duration, gravity;
        public int color;
        Particle(float x, float y, float vx, float vy, float size, float lifetime, float gravity, int color) {
            this.x=x; this.y=y; this.vx=vx; this.vy=vy; this.size=size; this.life=lifetime;
            this.duration=lifetime; this.gravity=gravity; this.color=color;
        }
    }
    public final GameProject project;
    public JSONObject scene;
    public final Map<String, Double> variables = new HashMap<>();
    public final List<Particle> particles = new ArrayList<>();
    public final List<String> logs = new ArrayList<>();
    public final Set<String> actions = new HashSet<>();
    private final Set<String> previousActions = new HashSet<>();
    private Set<String> grounded = new HashSet<>();
    private Set<String> contacts = new HashSet<>();
    private final Map<String, Float> emission = new HashMap<>();
    private final AssetLibrary library;
    private final Context context;
    private String pendingScene;
    private float waveTimer, shotTimer, cameraX, cameraY;
    private int checkpoint = -1;
    public float elapsed, cameraZoom = 1, aimX, aimY, messageTime;
    public boolean paused, stopped, over, won, collisionDebug;
    public String message = "";
    public int fps = 60, collisions, drawCalls;
    public float frameMs = 16;
    public Runnable onChange;
    public GameRuntime(Context context, GameProject source, String sceneId, AssetLibrary library) {
        this.context = context.getApplicationContext(); this.library = library;
        project = source.snapshot();
        variables.put("health", 3d); variables.put("score", 0d); variables.put("inventory", 0d);
        variables.put("wave", 1d); variables.put("lap", 0d);
        open(sceneId == null ? project.data.optString("startSceneId") : sceneId);
    }
    private void changed() { if (onChange != null) onChange.run(); }
    public void log(String level, String text) {
        logs.add(0, "[" + level.toUpperCase() + "] " + text);
        while (logs.size() > 100) logs.remove(logs.size() - 1);
        changed();
    }
    public void open(String sceneName) {
        JSONObject target = project.scene(sceneName);
        if (target == null) { log("error", "Scene not found: " + sceneName); return; }
        scene = J.copy(target);
        cameraX = scene.optInt("width") / 2f; cameraY = scene.optInt("height") / 2f;
        particles.clear(); contacts.clear(); grounded.clear(); emission.clear();
        checkpoint = -1; message = "";
        log("info", "Scene ready: " + scene.optString("name"));
        JSONArray nodes = GameProject.nodes(scene);
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = J.at(nodes, i); ScriptVM.dispatch(this, n, "start", null, null, 0);
            JSONObject audio = GameProject.component(n, "audio");
            if (audio != null && audio.optBoolean("autoplay")) sound(audio.optString("assetId"));
        }
    }
    public void restart(String sceneId) {
        actions.clear(); previousActions.clear();
        variables.put("health", 3d); variables.put("score", 0d); variables.put("inventory", 0d);
        variables.put("wave", 1d); variables.put("lap", 0d);
        elapsed = 0; paused = stopped = over = won = false; waveTimer = 0;
        open(sceneId == null ? project.data.optString("startSceneId") : sceneId);
    }
    public double stat(String name) { Double value = variables.get(name); return value == null ? 0 : value; }
    public JSONObject script(String id) {
        JSONArray scripts = project.scripts();
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject script = J.at(scripts, i);
            if (id.equals(script.optString("id"))) return script;
        }
        return null;
    }
    public void setAction(String action, boolean active) {
        if (active) actions.add(action); else actions.remove(action);
    }
    public float cameraX() { return cameraX; }
    public float cameraY() { return cameraY; }
    private JSONObject player() {
        JSONArray nodes = GameProject.nodes(scene);
        for (int i = 0; i < nodes.length(); i++) if (GameProject.tagged(J.at(nodes, i), "player")) return J.at(nodes, i);
        return null;
    }
    private JSONObject entity(String token, JSONObject self, JSONObject other) {
        if ("self".equals(token)) return self;
        if ("other".equals(token)) return other;
        if ("player".equals(token)) return player();
        JSONArray nodes = GameProject.nodes(scene);
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = J.at(nodes, i);
            if (token.equals(node.optString("id")) || token.equals(node.optString("name"))) return node;
        }
        return null;
    }
    public double value(String key, JSONObject self, JSONObject other) {
        if (!key.contains(".")) return stat(key);
        String[] parts = key.split("\\.", 2); JSONObject node = entity(parts[0], self, other);
        if (node == null) return 0;
        JSONObject t = J.obj(node, "transform");
        if (t.has(parts[1])) return t.optDouble(parts[1]);
        return J.obj(node, "metadata").optDouble(parts[1]);
    }
    private void write(String key, double amount, JSONObject self, JSONObject other) {
        if (!Double.isFinite(amount)) return;
        if (!key.contains(".")) { variables.put(key, amount); changed(); return; }
        String[] parts = key.split("\\.", 2); JSONObject node = entity(parts[0], self, other);
        if (node == null) return;
        JSONObject t = J.obj(node, "transform");
        if (t.has(parts[1])) J.put(t, parts[1], amount);
        else J.put(J.obj(node, "metadata"), parts[1], amount);
    }
    public void execute(String op, List<String> args, JSONObject self, JSONObject other, float dt) {
        String a = args.size() > 0 ? args.get(0) : "";
        String b = args.size() > 1 ? args.get(1) : "";
        String c = args.size() > 2 ? args.get(2) : "";
        switch (op) {
            case "set": write(a, Double.parseDouble(b), self, other); break;
            case "add": write(a, value(a,self,other) + Double.parseDouble(b), self, other); break;
            case "move": {
                JSONObject n = entity(a,self,other);
                if (n != null) {
                    JSONObject t = J.obj(n,"transform");
                    J.put(t,"x", t.optDouble("x") + Double.parseDouble(b) * dt);
                    J.put(t,"y", t.optDouble("y") + Double.parseDouble(c) * dt);
                }
                break;
            }
            case "destroy": {
                JSONObject n = entity(a,self,other);
                if (n != null) GameProject.removeNode(scene,n.optString("id"));
                break;
            }
            case "sound": sound(a); break;
            case "scene": pendingScene = a; break;
            case "message": message = a; messageTime = elapsed; log("info", a); break;
            case "emit": burst((float)GameProject.x(self),(float)GameProject.y(self),a); break;
            case "save": context.getSharedPreferences("world-saves-" + project.id(), Context.MODE_PRIVATE)
                .edit().putFloat(a, (float)stat(a)).apply(); log("info","Saved " + a); break;
            case "load": variables.put(a,(double)context.getSharedPreferences("world-saves-" + project.id(),Context.MODE_PRIVATE)
                .getFloat(a,(float)stat(a))); log("info","Loaded " + a); break;
            case "win": won = true; message = "Victory!"; messageTime = elapsed; sound("level"); changed(); break;
            case "spawn": {
                JSONArray prefabs = project.prefabs();
                if (GameProject.nodes(scene).length() > 1200) break;
                for (int i = 0; i < prefabs.length(); i++) {
                    JSONObject prefab = J.at(prefabs, i);
                    if (!a.equals(prefab.optString("id")) && !a.equals(prefab.optString("name"))) continue;
                    JSONArray template = prefab.optJSONArray("nodes");
                    if (template == null) break;
                    Map<String,String> remap = new HashMap<>();
                    for (int t = 0; t < template.length(); t++) remap.put(J.at(template,t).optString("id"),J.id("node"));
                    for (int t = 0; t < template.length(); t++) {
                        JSONObject n = J.copy(J.at(template,t));
                        J.put(n,"id",remap.get(n.optString("id")));
                        if (remap.containsKey(n.optString("parentId"))) J.put(n,"parentId",remap.get(n.optString("parentId")));
                        else { J.put(n,"parentId",JSONObject.NULL);
                            JSONObject pos = J.obj(n,"transform");
                            J.put(pos,"x",pos.optDouble("x")+GameProject.x(self));
                            J.put(pos,"y",pos.optDouble("y")+GameProject.y(self));
                        }
                        GameProject.nodes(scene).put(n);
                    }
                    break;
                }
                break;
            }
        }
    }
    private void sound(String key) {
        String id = key.startsWith("builtin:") || key.startsWith("asset_") ? key : "builtin:sfx-" + key;
        try { library.play(project,id); } catch (Exception ex) { log("warning", "Sound unavailable: " + key); }
    }
    public void tap(float x, float y) {
        aimX = x; aimY = y;
        JSONObject best = null; int bestLayer = Integer.MIN_VALUE;
        JSONArray nodes = GameProject.nodes(scene);
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = J.at(nodes,i);
            if (!node.optBoolean("visible",true)) continue;
            JSONObject transform = J.obj(node,"transform");
            float px=(float)worldX(node),py=(float)worldY(node);
            if (Math.abs(px-x) < transform.optDouble("width")/2 && Math.abs(py-y) < transform.optDouble("height")/2 &&
                node.optInt("layer") >= bestLayer && GameProject.component(node,"tilemap")==null) {
                best = node; bestLayer = node.optInt("layer");
            }
        }
        if (best != null) ScriptVM.dispatch(this,best,"interact",player(),null,0);
        if ("shooter".equals(project.data.optString("genre"))) shoot();
    }
    public double worldX(JSONObject n) { return world(n,true,new HashSet<>()); }
    public double worldY(JSONObject n) { return world(n,false,new HashSet<>()); }
    private double world(JSONObject n, boolean x, Set<String> seen) {
        if (n == null || !seen.add(n.optString("id"))) return 0;
        JSONObject t=J.obj(n,"transform"); double v=t.optDouble(x?"x":"y");
        String parent=n.optString("parentId");
        if (!parent.isEmpty() && !parent.equals("null")) {
            JSONObject ancestor=GameProject.node(scene,parent);
            if (ancestor!=null) v+=world(ancestor,x,seen);
        }
        return v;
    }
    public void tick(float delta) {
        if (stopped || paused || over || won || scene == null) return;
        long start = System.nanoTime();
        float dt = Math.min(0.04f, Math.max(0,delta));
        if (dt <= 0) return;
        elapsed += dt; shotTimer -= dt;
        JSONArray nodes = GameProject.nodes(scene);
        for (String action : new HashSet<>(actions)) if (!previousActions.contains(action)) {
            for (int i=0;i<nodes.length();i++) ScriptVM.dispatch(this,J.at(nodes,i),"input",null,action,0);
            if (action.equals("interact")) {
                JSONObject hero=player();
                if (hero!=null) for (int i=0;i<nodes.length();i++) {
                    JSONObject n=J.at(nodes,i);
                    if (n!=hero && Math.hypot(worldX(n)-worldX(hero),worldY(n)-worldY(hero))<100)
                        ScriptVM.dispatch(this,n,"interact",hero,null,0);
                }
            }
            if (action.equals("pause")) paused=true;
        }
        previousActions.clear(); previousActions.addAll(actions);
        if (actions.contains("fire")) shoot();
        for (int i=0;i<nodes.length();i++) ScriptVM.dispatch(this,J.at(nodes,i),"update",null,null,dt);
        updateAI(dt);
        animationEvents(dt);
        Set<String> nextGround = new HashSet<>();
        for (int i=0;i<nodes.length();i++) {
            JSONObject node=J.at(nodes,i), body=GameProject.component(node,"body");
            if (body==null || body.optString("mode").equals("static")) continue;
            JSONObject t=J.obj(node,"transform"), control=GameProject.component(node,"controller");
            double vx=body.optDouble("vx"), vy=body.optDouble("vy");
            if (control!=null) {
                int h=(actions.contains("right")?1:0)-(actions.contains("left")?1:0);
                int v=(actions.contains("down")?1:0)-(actions.contains("up")?1:0);
                double speed=control.optDouble("speed",210);
                String type=control.optString("style");
                if (type.equals("platformer")) {
                    vx=h*speed;
                    if (actions.contains("jump") && grounded.contains(node.optString("id"))) vy=-control.optDouble("jump",400);
                } else if (type.equals("topdown")) {
                    double length=Math.max(1,Math.hypot(h,v)); vx=h/length*speed; vy=v/length*speed;
                } else if (type.equals("car")) {
                    double heading=t.optDouble("rotation")+h*140*dt;
                    J.put(t,"rotation",heading);
                    double angle=Math.toRadians(heading);
                    vx=(vx+Math.cos(angle)*-v*speed*dt*3)*Math.max(0,1-dt*1.8);
                    vy=(vy+Math.sin(angle)*-v*speed*dt*3)*Math.max(0,1-dt*1.8);
                }
            }
            String style=control==null?"":control.optString("style");
            if (!style.equals("topdown") && !style.equals("car")) vy+=project.data.optDouble("gravity",800)*body.optDouble("gravityScale",1)*dt;
            vx=Math.max(-1800,Math.min(1800,vx));vy=Math.max(-1800,Math.min(1800,vy));
            J.put(body,"vx",vx);J.put(body,"vy",vy);
            J.put(t,"x",t.optDouble("x")+vx*dt);
            resolveStatic(node,true,nextGround);
            J.put(t,"y",t.optDouble("y")+body.optDouble("vy")*dt);
            resolveStatic(node,false,nextGround);
        }
        grounded=nextGround;
        Set<String> nextContacts=new HashSet<>();
        nodes=GameProject.nodes(scene);
        for (int i=0;i<nodes.length();i++) {
            JSONObject a=J.at(nodes,i); if (GameProject.component(a,"collider")==null) continue;
            for (int j=i+1;j<nodes.length();j++) {
                JSONObject b=J.at(nodes,j);
                if (GameProject.component(b,"collider")==null) continue;
                if ("static".equals(J.text(GameProject.component(a,"body"),"mode","")) &&
                    "static".equals(J.text(GameProject.component(b,"body"),"mode",""))) continue;
                if (!overlaps(a,b)) continue;
                String pair=a.optString("id").compareTo(b.optString("id"))<0 ? a.optString("id")+"|"+b.optString("id") : b.optString("id")+"|"+a.optString("id");
                nextContacts.add(pair);
                if (!contacts.contains(pair)) onContact(a,b);
            }
        }
        collisions=nextContacts.size();contacts=nextContacts;
        nodes=GameProject.nodes(scene);
        for (int i=nodes.length()-1;i>=0;i--) {
            JSONObject node=J.at(nodes,i);
            if (GameProject.tagged(node,"projectile") && elapsed-J.obj(node,"metadata").optDouble("born",0)>2) nodes.remove(i);
        }
        stepParticles(dt);
        JSONObject cameraTarget=player();
        if (cameraTarget!=null) {
            JSONObject camera=GameProject.component(cameraTarget,"camera");
            float smooth=camera==null?0.12f:(float)camera.optDouble("smoothing",0.12);
            cameraX+=(worldX(cameraTarget)-cameraX)*Math.min(1,smooth*60*dt);
            cameraY+=(worldY(cameraTarget)-cameraY)*Math.min(1,smooth*60*dt);
            cameraZoom=camera==null?1:(float)camera.optDouble("zoom",1);
        }
        if ("shooter".equals(project.data.optString("genre"))) waves(dt);
        if ("racing".equals(project.data.optString("genre")) && stat("lap")>=3) {
            won=true;message="Three laps complete. Victory!";sound("level");changed();
        }
        if (stat("health")<=0 && !over) {over=true;message="Game over — tap restart";sound("gameover");changed();}
        if (pendingScene!=null) {String next=pendingScene;pendingScene=null;open(next);}
        frameMs=(System.nanoTime()-start)/1_000_000f;
        fps=Math.round(1/Math.max(dt,0.0001f));
        if ((int)(elapsed*5)!=(int)((elapsed-dt)*5)) changed();
    }
    private void updateAI(float dt) {
        JSONObject hero=player(); if (hero==null) return;
        JSONArray nodes=GameProject.nodes(scene);
        for (int i=0;i<nodes.length();i++) {
            JSONObject node=J.at(nodes,i), ai=GameProject.component(node,"ai"),body=GameProject.component(node,"body");
            if (ai==null || body==null) continue;
            String behavior=ai.optString("behavior");
            double dx=worldX(hero)-worldX(node),dy=worldY(hero)-worldY(node), distance=Math.hypot(dx,dy);
            if ((behavior.equals("chase") || behavior.equals("follow")) && distance<ai.optDouble("range",250) && distance>30) {
                J.put(body,"vx",dx/distance*ai.optDouble("speed",60));
                if (body.optDouble("gravityScale")==0) J.put(body,"vy",dy/distance*ai.optDouble("speed",60));
            } else if (behavior.equals("patrol") || behavior.equals("wander")) {
                JSONObject meta=J.obj(node,"metadata");
                if (!meta.has("originX")) J.put(meta,"originX",GameProject.x(node));
                double origin=meta.optDouble("originX"),range=ai.optDouble("range",100);
                int direction=meta.optInt("direction",1);
                if (GameProject.x(node)>origin+range) direction=-1;
                if (GameProject.x(node)<origin-range) direction=1;
                J.put(meta,"direction",direction);J.put(body,"vx",direction*ai.optDouble("speed",60));
            }
        }
    }
    private boolean overlaps(JSONObject a,JSONObject b) {
        JSONObject ca=GameProject.component(a,"collider"),cb=GameProject.component(b,"collider");
        if (ca==null || cb==null) return false;
        double ax=worldX(a)+ca.optDouble("offsetX"),ay=worldY(a)+ca.optDouble("offsetY");
        double bx=worldX(b)+cb.optDouble("offsetX"),by=worldY(b)+cb.optDouble("offsetY");
        if (ca.optString("shape").equals("circle") && cb.optString("shape").equals("circle"))
            return Math.hypot(ax-bx,ay-by)<ca.optDouble("radius",20)+cb.optDouble("radius",20);
        JSONObject ta=J.obj(a,"transform"),tb=J.obj(b,"transform");
        return Math.abs(ax-bx)<(ca.optDouble("width")*Math.abs(ta.optDouble("scaleX",1))+cb.optDouble("width")*Math.abs(tb.optDouble("scaleX",1)))/2 &&
            Math.abs(ay-by)<(ca.optDouble("height")*Math.abs(ta.optDouble("scaleY",1))+cb.optDouble("height")*Math.abs(tb.optDouble("scaleY",1)))/2;
    }
    private void resolveStatic(JSONObject moving,boolean horizontal,Set<String> nextGround) {
        JSONObject collider=GameProject.component(moving,"collider"),body=GameProject.component(moving,"body");
        if (collider==null || collider.optBoolean("sensor")) return;
        JSONArray nodes=GameProject.nodes(scene);
        for (int i=0;i<nodes.length();i++) {
            JSONObject solid=J.at(nodes,i);
            if (solid==moving || !"static".equals(J.text(GameProject.component(solid,"body"),"mode",""))) continue;
            JSONObject shape=GameProject.component(solid,"collider");
            if (shape==null || shape.optBoolean("sensor") || !overlaps(moving,solid)) continue;
            JSONObject t=J.obj(moving,"transform");
            double a=horizontal?worldX(moving):worldY(moving),b=horizontal?worldX(solid):worldY(solid);
            double half=(horizontal?collider.optDouble("width")*Math.abs(J.obj(moving,"transform").optDouble("scaleX",1))+
                shape.optDouble("width")*Math.abs(J.obj(solid,"transform").optDouble("scaleX",1)):
                collider.optDouble("height")*Math.abs(J.obj(moving,"transform").optDouble("scaleY",1))+
                shape.optDouble("height")*Math.abs(J.obj(solid,"transform").optDouble("scaleY",1)))/2;
            double overlap=half-Math.abs(a-b);
            if (overlap<=0) continue;
            String key=horizontal?"x":"y",speed=horizontal?"vx":"vy";
            if (!horizontal && a<b && body.optDouble("vy")>=0) nextGround.add(moving.optString("id"));
            J.put(t,key,t.optDouble(key)+(a<b?-1:1)*(overlap+0.02));
            J.put(body,speed,-body.optDouble(speed)*body.optDouble("bounce",0));
        }
        // TileMap collision: painted cells act as individual static boxes when enabled.
        for(int i=0;i<nodes.length();i++){
            JSONObject mapNode=J.at(nodes,i),map=GameProject.component(mapNode,"tilemap");
            if(map==null||!map.optBoolean("collision")||!mapNode.optBoolean("visible",true))continue;
            JSONObject cells=map.optJSONObject("cells");if(cells==null||cells.length()==0)continue;
            int size=Math.max(4,map.optInt("tileSize",48)),cols=map.optInt("columns",20),rows=map.optInt("rows",12);
            float startX=(float)(worldX(mapNode)-cols*size/2f),startY=(float)(worldY(mapNode)-rows*size/2f);
            double ax=worldX(moving)+collider.optDouble("offsetX"),ay=worldY(moving)+collider.optDouble("offsetY");
            double halfW=collider.optDouble("width")*Math.abs(J.obj(moving,"transform").optDouble("scaleX",1))/2;
            double halfH=collider.optDouble("height")*Math.abs(J.obj(moving,"transform").optDouble("scaleY",1))/2;
            int left=Math.max(0,(int)Math.floor((ax-halfW-startX)/size));
            int right=Math.min(cols-1,(int)Math.floor((ax+halfW-startX)/size));
            int top=Math.max(0,(int)Math.floor((ay-halfH-startY)/size));
            int bottom=Math.min(rows-1,(int)Math.floor((ay+halfH-startY)/size));
            for(int row=top;row<=bottom;row++)for(int col=left;col<=right;col++){
                if(!cells.has(col+","+row))continue;
                double bx=startX+(col+0.5)*size,by=startY+(row+0.5)*size;
                if(Math.abs(ax-bx)>=halfW+size/2d||Math.abs(ay-by)>=halfH+size/2d)continue;
                JSONObject t=J.obj(moving,"transform");
                double delta=horizontal?ax-bx:ay-by;
                double overlap=(horizontal?halfW:halfH)+size/2d-Math.abs(delta);
                if(overlap<=0)continue;
                if(!horizontal&&delta<0&&body.optDouble("vy")>=0)nextGround.add(moving.optString("id"));
                String axis=horizontal?"x":"y",velocity=horizontal?"vx":"vy";
                J.put(t,axis,t.optDouble(axis)+(delta<0?-1:1)*(overlap+0.02));
                J.put(body,velocity,-body.optDouble(velocity)*body.optDouble("bounce",0));
                ax=worldX(moving)+collider.optDouble("offsetX");
                ay=worldY(moving)+collider.optDouble("offsetY");
            }
        }
    }
    private void animationEvents(float dt){
        JSONArray nodes=GameProject.nodes(scene),clips=project.animations();
        for(int i=0;i<nodes.length();i++){
            JSONObject node=J.at(nodes,i),assignment=GameProject.component(node,"animation");
            if(assignment==null||!assignment.optBoolean("autoplay",true))continue;
            String id=assignment.optString("animationId");JSONObject clip=null;
            for(int j=0;j<clips.length();j++)if(id.equals(J.at(clips,j).optString("id"))){clip=J.at(clips,j);break;}
            if(clip==null)continue;
            JSONArray events=clip.optJSONArray("events");if(events==null||events.length()==0)continue;
            double duration=Math.max(0.01,clip.optDouble("duration",1));
            double speed=Math.max(0,assignment.optDouble("speed",1));
            double now=elapsed*speed,previous=(elapsed-dt)*speed;
            if(clip.optString("loop").equals("once")){now=Math.min(duration,now);previous=Math.min(duration,previous);}
            else{now%=duration;previous%=duration;}
            for(int e=0;e<events.length();e++){
                JSONObject event=J.at(events,e);double at=event.optDouble("time",0);
                boolean crossed=now>=previous?at>previous&&at<=now:at>previous||at<=now;
                if(!crossed)continue;
                String action=event.optString("action","").trim();
                if(action.startsWith("sound "))sound(action.substring(6).trim());
                else if(action.startsWith("emit "))burst((float)worldX(node),(float)worldY(node),action.substring(5).trim());
                else if(action.startsWith("message ")){message=action.substring(8).trim();messageTime=elapsed;changed();}
            }
        }
    }
    private void onContact(JSONObject a,JSONObject b) {
        JSONObject hero=GameProject.tagged(a,"player")?a:GameProject.tagged(b,"player")?b:null;
        JSONObject other=hero==a?b:a;
        if (GameProject.tagged(a,"projectile") || GameProject.tagged(b,"projectile")) {
            JSONObject bullet=GameProject.tagged(a,"projectile")?a:b;
            JSONObject target=bullet==a?b:a;
            if (GameProject.tagged(target,"enemy")) {
                JSONObject meta=J.obj(target,"metadata");double hp=meta.optDouble("hp",1)-1;
                J.put(meta,"hp",hp);burst((float)worldX(target),(float)worldY(target),"Impact");sound("hit");
                if (hp<=0) {GameProject.removeNode(scene,target.optString("id"));variables.put("score",stat("score")+1);changed();}
                GameProject.removeNode(scene,bullet.optString("id"));
            } else if ("static".equals(J.text(GameProject.component(target,"body"),"mode","")))
                GameProject.removeNode(scene,bullet.optString("id"));
            return;
        }
        if (hero!=null && GameProject.tagged(other,"checkpoint")) {
            List<String> checkpoints=new ArrayList<>();JSONArray nodes=GameProject.nodes(scene);
            for (int i=0;i<nodes.length();i++) if (GameProject.tagged(J.at(nodes,i),"checkpoint")) checkpoints.add(J.at(nodes,i).optString("id"));
            int index=checkpoints.indexOf(other.optString("id"));
            if (!checkpoints.isEmpty() && index==(checkpoint+1)%checkpoints.size()) {
                if (index==0 && checkpoint>=0) {variables.put("lap",stat("lap")+1);sound("coin");
                    message="Lap " + (int)stat("lap") + " complete!";messageTime=elapsed;}
                checkpoint=index;changed();
            }
        }
        ScriptVM.dispatch(this,a,"collision",b,null,0);
        ScriptVM.dispatch(this,b,"collision",a,null,0);
    }
    private void shoot() {
        JSONObject hero=player();
        if (hero==null || !GameProject.tagged(hero,"can-shoot") || shotTimer>0) return;
        double dx=aimX-worldX(hero),dy=aimY-worldY(hero);
        double angle=Math.hypot(dx,dy)>35?Math.atan2(dy,dx):Math.toRadians(J.obj(hero,"transform").optDouble("rotation"));
        JSONObject bullet=GameProject.newNode("Projectile",worldX(hero)+Math.cos(angle)*27,
            worldY(hero)+Math.sin(angle)*27,13,13);
        GameProject.addComponent(bullet,GameProject.sprite("builtin:prop-9"));
        JSONObject physics=GameProject.body("dynamic",0);
        J.put(physics,"vx",Math.cos(angle)*500);J.put(physics,"vy",Math.sin(angle)*500);
        GameProject.addComponent(bullet,physics);
        JSONObject collision=GameProject.collider(13,13,true);J.put(collision,"shape","circle");
        GameProject.addComponent(bullet,collision);GameProject.tag(bullet,"projectile");
        J.put(J.obj(bullet,"metadata"),"born",elapsed);
        GameProject.nodes(scene).put(bullet);shotTimer=0.18f;sound("laser");
    }
    private void waves(float dt) {
        JSONArray nodes=GameProject.nodes(scene);
        for (int i=0;i<nodes.length();i++) if (GameProject.tagged(J.at(nodes,i),"enemy")) {waveTimer=0;return;}
        waveTimer+=dt;if (waveTimer<2) return;waveTimer=0;
        if (stat("wave")>=3) {won=true;message="All waves cleared. Victory!";sound("level");changed();return;}
        variables.put("wave",stat("wave")+1);
        for (int i=0;i<(int)stat("wave")+2;i++) {
            JSONObject n=GameProject.newNode("Wave enemy",120+i*145,90+(i%2)*350,47,58);
            GameProject.addComponent(n,GameProject.sprite("builtin:enemy-"+((i+(int)stat("wave"))%36)));
            GameProject.addComponent(n,GameProject.body("character",0));
            GameProject.addComponent(n,GameProject.collider(35,48,true));
            GameProject.addComponent(n,J.o("type","ai","behavior","chase","speed",70+stat("wave")*12,"range",900));
            GameProject.tag(n,"enemy");J.put(J.obj(n,"metadata"),"hp",1+(int)stat("wave")/2);
            nodes.put(n);
        }
        message="Wave "+(int)stat("wave")+" incoming";messageTime=elapsed;log("info",message);
    }
    private void stepParticles(float dt) {
        JSONArray nodes=GameProject.nodes(scene);
        for (int i=0;i<nodes.length();i++) {
            JSONObject n=J.at(nodes,i),emitter=GameProject.component(n,"particles");
            if (emitter==null || !emitter.optBoolean("emitting",true)) continue;
            float accumulator=emission.containsKey(n.optString("id"))?emission.get(n.optString("id")):0;
            accumulator+=(float)emitter.optDouble("rate",12)*dt;
            int count=Math.min(20,(int)accumulator);accumulator-=count;emission.put(n.optString("id"),accumulator);
            int color=parseColor(emitter.optString("color","#aee589"));
            for (int j=0;j<count && particles.size()<600;j++) {
                double angle=Math.toRadians(-90+(Math.random()-0.5)*emitter.optDouble("spread",360));
                double speed=emitter.optDouble("speed",60)*(0.5+Math.random());
                particles.add(new Particle((float)worldX(n),(float)worldY(n),
                    (float)(Math.cos(angle)*speed),(float)(Math.sin(angle)*speed),
                    (float)emitter.optDouble("size",5),
                    (float)emitter.optDouble("lifetime",0.8),
                    (float)emitter.optDouble("gravity",0),color));
            }
        }
        for (int i=particles.size()-1;i>=0;i--) {
            Particle p=particles.get(i);p.x+=p.vx*dt;p.y+=p.vy*dt;p.vy+=p.gravity*dt;p.life-=dt;
            if (p.life<=0) particles.remove(i);
        }
    }
    public void burst(float x,float y,String kind) {
        int color=parseColor(kind.equals("Impact")?"#ff987c":kind.equals("Healing")?"#a0edb0":"#f6d988");
        for (int i=0;i<16;i++) {
            double angle=i*Math.PI/8,speed=50+Math.random()*100;
            particles.add(new Particle(x,y,(float)(Math.cos(angle)*speed),(float)(Math.sin(angle)*speed),
                3+(float)Math.random()*3,0.7f,75,color));
        }
    }
    private int parseColor(String text) {
        try { return android.graphics.Color.parseColor(text); }
        catch (Exception ex) { return android.graphics.Color.WHITE; }
    }
}
