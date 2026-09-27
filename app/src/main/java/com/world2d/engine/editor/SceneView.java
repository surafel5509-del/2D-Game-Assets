package com.world2d.engine.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.runtime.GameRuntime;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native Canvas editor and game renderer. Pinch zoom, two-finger pan and touch transforms share scene coordinates. */
public final class SceneView extends View {
    public interface Events {
        void selected(String id);
        void beginGesture();
        void changed(boolean gestureFinished);
        void place(String assetId, float x, float y);
        void hint(String message);
    }
    public enum Tool { SELECT, MOVE, ROTATE, SCALE, PAN, BRUSH, ERASE }
    private static final int INK=Color.rgb(26,33,46), GRID=Color.argb(22,211,234,235), ACCENT=Color.rgb(185,236,137);
    private final AssetLibrary library;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Events listener;
    private GameProject project;
    private JSONObject scene;
    private GameRuntime runtime;
    private String selectedId, placementId, brushAssetId="builtin:tile-1";
    private Tool tool=Tool.SELECT;
    private boolean grid=true, snap=false, thumb=false, alive=true;
    private int gridSize=24;
    private float zoom=1,panX,panY,startX,startY,startPanX,startPanY,pinchDistance,pinchZoom,pinchX,pinchY;
    private float originalX,originalY,originalAngle,originalScaleX,originalScaleY;
    private boolean moving,historyMade;
    private long previousNanos;
    private float cursorX,cursorY,previewTime=-1;
    public SceneView(Context context,AssetLibrary library) {
        super(context);this.library=library;density=getResources().getDisplayMetrics().density;
    }
    public void setEvents(Events listener){this.listener=listener;}
    public void setProject(GameProject project,JSONObject scene){this.project=project;this.scene=scene;resetView();}
    public void setScene(JSONObject scene){this.scene=scene;selectedId=null;resetView();}
    public void setRuntime(GameRuntime runtime){this.runtime=runtime;previousNanos=0;invalidate();}
    public GameRuntime runtime(){return runtime;}
    public void setSelected(String id){selectedId=id;invalidate();}
    public String selected(){return selectedId;}
    public void setTool(Tool value){tool=value;invalidate();}
    public Tool tool(){return tool;}
    public void setSnap(boolean value){snap=value;invalidate();}
    public void setGrid(boolean value){grid=value;invalidate();}
    public boolean grid(){return grid;}
    public void setGridSize(int value){gridSize=Math.max(4,Math.min(256,value));invalidate();}
    public void setPlacement(String id){placementId=id;invalidate();}
    public void setBrushAsset(String id){brushAssetId=id;}
    public void setThumbnail(boolean value){thumb=value;invalidate();}
    public void setPreviewTime(float seconds){previewTime=seconds;invalidate();}
    public void setAlive(boolean value){alive=value;if(value)invalidate();}
    public float zoom(){return zoom;}
    public void resetView(){if(scene!=null){panX=scene.optInt("width")/2f;panY=scene.optInt("height")/2f;
        if(getWidth()>0&&getHeight()>0)zoom=Math.max(0.15f,Math.min(2f,Math.min((getWidth()-48*density)/scene.optInt("width",960),
            (getHeight()-48*density)/scene.optInt("height",540))));invalidate();}}
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){super.onSizeChanged(width,height,oldWidth,oldHeight);resetView();}
    private JSONObject activeScene(){return runtime!=null?runtime.scene:scene;}
    private float drawZoom(JSONObject world){
        if(runtime==null)return zoom;
        return Math.min(getWidth()/(float)world.optInt("width",960),getHeight()/(float)world.optInt("height",540))*runtime.cameraZoom;
    }
    private float viewX(JSONObject world,float factor){
        if(runtime==null)return panX;
        float half=getWidth()/2f/factor;
        return half>=world.optInt("width")/2f?world.optInt("width")/2f:
            Math.max(half,Math.min(world.optInt("width")-half,runtime.cameraX()));
    }
    private float viewY(JSONObject world,float factor){
        if(runtime==null)return panY;
        float half=getHeight()/2f/factor;
        return half>=world.optInt("height")/2f?world.optInt("height")/2f:
            Math.max(half,Math.min(world.optInt("height")-half,runtime.cameraY()));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.drawColor(INK);JSONObject world=activeScene();if(world==null||project==null)return;
        if(runtime!=null){
            long now=System.nanoTime();if(previousNanos>0)runtime.tick((now-previousNanos)/1_000_000_000f);
            previousNanos=now;
        }
        float scale=drawZoom(world),cx=viewX(world,scale),cy=viewY(world,scale);
        canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.scale(scale,scale);canvas.translate(-cx,-cy);
        int width=world.optInt("width",960),height=world.optInt("height",540);
        if(runtime==null&&!thumb){paint.reset();paint.setAntiAlias(true);paint.setColor(Color.argb(75,0,0,0));
            paint.setShadowLayer(16/scale,0,6/scale,Color.BLACK);canvas.drawRect(-3,-3,width+3,height+3,paint);paint.clearShadowLayer();}
        int background=color(world.optString("background","#263d50"),Color.rgb(38,61,80));
        paint.reset();paint.setAntiAlias(true);paint.setShader(new LinearGradient(0,0,0,height,
            background,blend(background,Color.rgb(15,33,51),0.28f), Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,width,height,paint);paint.setShader(null);
        if(runtime==null&&grid&&!thumb)drawGrid(canvas,width,height,scale);
        int calls=0;
        List<JSONObject> sorted=new ArrayList<>();JSONArray nodes=GameProject.nodes(world);
        for(int i=0;i<nodes.length();i++) {JSONObject n=J.at(nodes,i);if(n!=null)sorted.add(n);}
        sorted.sort(Comparator.comparingInt(n->n.optInt("layer")));
        for(JSONObject node:sorted){
            if(!node.optBoolean("visible",true)||hiddenParent(world,node,new HashSet<>()))continue;
            calls+=drawNode(canvas,world,node,scale);
        }
        if(runtime!=null){
            paint.reset();paint.setAntiAlias(true);
            for(GameRuntime.Particle particle:runtime.particles){
                paint.setColor(particle.color);
                paint.setAlpha(Math.min(255,Math.max(0,(int)(255*particle.life/particle.duration))));
                canvas.drawCircle(particle.x,particle.y,particle.size,paint);calls++;
            }
            if(runtime.collisionDebug)for(JSONObject n:sorted)drawCollider(canvas,world,n,scale);
            runtime.drawCalls=calls;
        }else{
            if(selectedId!=null&&!thumb){JSONObject node=GameProject.node(world,selectedId);
                if(node!=null)drawSelection(canvas,world,node,scale);}
        }
        canvas.restore();
        if(!thumb)drawOverlay(canvas,world,scale);
        if(alive&&(runtime!=null||(!thumb&&previewTime<0&&hasAnimation(world))))postInvalidateDelayed(16);
    }
    private void drawGrid(Canvas canvas,int w,int h,float scale){
        int spacing=gridSize*(scale<0.4?4:1);
        paint.reset();paint.setColor(GRID);paint.setStrokeWidth(1/scale);
        for(int x=0;x<=w;x+=spacing)canvas.drawLine(x,0,x,h,paint);
        for(int y=0;y<=h;y+=spacing)canvas.drawLine(0,y,w,y,paint);
        paint.setColor(Color.argb(44,185,236,137));
        canvas.drawLine(w/2f,0,w/2f,h,paint);canvas.drawLine(0,h/2f,w,h/2f,paint);
    }
    private boolean hasAnimation(JSONObject world){
        JSONArray nodes=GameProject.nodes(world);
        for(int i=0;i<nodes.length();i++)if(GameProject.component(J.at(nodes,i),"animation")!=null)return true;
        return false;
    }
    private boolean hiddenParent(JSONObject world,JSONObject node,Set<String>seen){
        String parent=node.optString("parentId");if(parent.isEmpty()||parent.equals("null")||!seen.add(parent))return false;
        JSONObject n=GameProject.node(world,parent);
        return n!=null&&(!n.optBoolean("visible",true)||hiddenParent(world,n,seen));
    }
    private Matrix nodeMatrix(JSONObject world,JSONObject node,Set<String>seen){
        Matrix matrix=new Matrix();
        if(!seen.add(node.optString("id")))return matrix;
        String parent=node.optString("parentId");
        if(!parent.isEmpty()&&!parent.equals("null")){
            JSONObject ancestor=GameProject.node(world,parent);
            if(ancestor!=null)matrix=nodeMatrix(world,ancestor,seen);
        }
        JSONObject t=J.obj(node,"transform");
        matrix.preTranslate((float)t.optDouble("x"),(float)t.optDouble("y"));
        matrix.preRotate((float)t.optDouble("rotation"));
        matrix.preScale((float)t.optDouble("scaleX",1),(float)t.optDouble("scaleY",1));
        return matrix;
    }
    private JSONObject animation(JSONObject node){
        JSONObject c=GameProject.component(node,"animation");if(c==null||!c.optBoolean("autoplay",true))return null;
        JSONArray clips=project.animations();for(int i=0;i<clips.length();i++){
            JSONObject a=J.at(clips,i);if(a.optString("id").equals(c.optString("animationId")))return a;
        }return null;
    }
    private double sample(JSONObject animation,String property,float time,double fallback){
        if(animation==null)return fallback;
        float duration=(float)Math.max(0.01,animation.optDouble("duration",0.8));
        String loop=animation.optString("loop","loop");
        float t=loop.equals("once")?Math.min(time,duration):time%duration;
        if(loop.equals("pingpong")){float phase=time%(duration*2);t=phase>duration?duration*2-phase:phase;}
        JSONArray tracks=animation.optJSONArray("tracks");if(tracks==null)return fallback;
        for(int i=0;i<tracks.length();i++){
            JSONObject track=J.at(tracks,i);if(!property.equals(track.optString("property")))continue;
            JSONArray keys=track.optJSONArray("keys");if(keys==null||keys.length()==0)return fallback;
            JSONObject prev=J.at(keys,0);if(t<=prev.optDouble("time"))return prev.optDouble("value",fallback);
            for(int j=1;j<keys.length();j++){
                JSONObject next=J.at(keys,j);
                if(t<=next.optDouble("time")){
                    double f=(t-prev.optDouble("time"))/Math.max(0.0001,next.optDouble("time")-prev.optDouble("time"));
                    return prev.optDouble("value")+(next.optDouble("value")-prev.optDouble("value"))*f;
                }prev=next;
            }return prev.optDouble("value",fallback);
        }return fallback;
    }
    private String frame(JSONObject animation,float time){
        if(animation==null)return null;
        JSONArray frames=animation.optJSONArray("frames");if(frames==null||frames.length()==0)return null;
        float duration=(float)Math.max(0.01,animation.optDouble("duration",1));
        float t=animation.optString("loop").equals("once")?Math.min(time,duration):time%duration;
        if(animation.optString("loop").equals("pingpong")){
            float phase=time%(duration*2);t=phase>duration?duration*2-phase:phase;
        }
        String chosen=J.at(frames,0).optString("assetId");
        for(int i=0;i<frames.length();i++)if(t>=J.at(frames,i).optDouble("time"))chosen=J.at(frames,i).optString("assetId");
        return chosen;
    }
    private int drawNode(Canvas canvas,JSONObject world,JSONObject node,float scale){
        Matrix matrix=nodeMatrix(world,node,new HashSet<>());
        canvas.save();canvas.concat(matrix);
        JSONObject t=J.obj(node,"transform"),anim=animation(node);
        float seconds=runtime!=null?runtime.elapsed:
            (previewTime>=0?previewTime:System.currentTimeMillis()/1000f);
        JSONObject assignment=GameProject.component(node,"animation");
        seconds*=assignment==null?1:assignment.optDouble("speed",1);
        if(anim!=null){canvas.translate((float)sample(anim,"x",seconds,0),(float)sample(anim,"y",seconds,0));
            canvas.rotate((float)sample(anim,"rotation",seconds,0));
            canvas.scale((float)sample(anim,"scaleX",seconds,1),(float)sample(anim,"scaleY",seconds,1));}
        int alpha=(int)(255*Math.max(0,Math.min(1,t.optDouble("opacity",1)*sample(anim,"opacity",seconds,1))));
        float w=(float)t.optDouble("width",48),h=(float)t.optDouble("height",48);
        int calls=0;
        JSONObject map=GameProject.component(node,"tilemap");
        if(map!=null){JSONObject cells=map.optJSONObject("cells");int size=map.optInt("tileSize",48);
            int columns=map.optInt("columns",20),rows=map.optInt("rows",12);
            if(cells!=null)for(java.util.Iterator<String> keys=cells.keys();keys.hasNext();){
                String key=keys.next();String[] parts=key.split(",");if(parts.length!=2)continue;
                try{int col=Integer.parseInt(parts[0]),row=Integer.parseInt(parts[1]);
                    if(col<0||row<0||col>=columns||row>=rows)continue;
                    Bitmap tile=library.bitmap(project,cells.optString(key),size*2);
                    if(tile==null)continue;
                    float x=(col-columns/2f)*size,y=(row-rows/2f)*size;
                    paint.reset();paint.setAlpha(alpha);paint.setFilterBitmap(false);
                    canvas.drawBitmap(tile,null,new RectF(x,y,x+size,y+size),paint);calls++;
                }catch(NumberFormatException ignored){}
            }
        }
        JSONObject sprite=GameProject.component(node,"sprite");
        if(sprite!=null){String id=frame(anim,seconds);if(id==null)id=sprite.optString("assetId");
            Bitmap bitmap=library.bitmap(project,id,Math.max(32,(int)(w*scale*2)));
            if(bitmap!=null){paint.reset();paint.setAntiAlias(true);paint.setAlpha(alpha);
                paint.setFilterBitmap(!id.contains("tile-")&&!id.contains("kenney-"));
                String tint=sprite.optString("tint","#ffffff");
                if(!tint.equalsIgnoreCase("#ffffff"))paint.setColorFilter(new PorterDuffColorFilter(color(tint,Color.WHITE),PorterDuff.Mode.MULTIPLY));
                canvas.drawBitmap(bitmap,null,new RectF(-w/2,-h/2,w/2,h/2),paint);paint.setColorFilter(null);calls++;
            }else{
                paint.reset();paint.setColor(Color.rgb(151,65,81));canvas.drawRect(-w/2,-h/2,w/2,h/2,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2/scale);paint.setColor(Color.rgb(255,169,161));
                canvas.drawLine(-w/2,-h/2,w/2,h/2,paint);canvas.drawLine(w/2,-h/2,-w/2,h/2,paint);calls++;
            }
        }
        JSONObject label=GameProject.component(node,"label");
        if(label!=null){paint.reset();paint.setAntiAlias(true);paint.setColor(color(label.optString("color","#ffffff"),Color.WHITE));
            paint.setAlpha(alpha);paint.setTextSize(label.optInt("fontSize",24));paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            String alignment=label.optString("align","center");paint.setTextAlign(alignment.equals("left")?Paint.Align.LEFT:alignment.equals("right")?Paint.Align.RIGHT:Paint.Align.CENTER);
            canvas.drawText(label.optString("text",""),alignment.equals("left")?-w/2:alignment.equals("right")?w/2:0,paint.getTextSize()/3,paint);calls++;
        }
        JSONObject light=GameProject.component(node,"light");
        if(light!=null){float r=(float)light.optDouble("radius",160);int c=color(light.optString("color","#ffe7ac"),Color.YELLOW);
            paint.reset();paint.setAntiAlias(true);paint.setShader(new RadialGradient(0,0,r,
                new int[]{withAlpha(c,(int)(80*light.optDouble("intensity",0.6))),Color.TRANSPARENT},
                null,Shader.TileMode.CLAMP));canvas.drawCircle(0,0,r,paint);paint.setShader(null);calls++;
        }
        canvas.restore();return calls;
    }
    private void drawCollider(Canvas canvas,JSONObject world,JSONObject node,float scale){
        JSONObject collider=GameProject.component(node,"collider");if(collider==null)return;
        canvas.save();canvas.concat(nodeMatrix(world,node,new HashSet<>()));
        paint.reset();paint.setAntiAlias(true);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f/scale);
        paint.setColor(collider.optBoolean("sensor")?Color.rgb(255,200,112):Color.rgb(111,231,188));
        float offsetX=(float)collider.optDouble("offsetX"),offsetY=(float)collider.optDouble("offsetY");
        if(collider.optString("shape").equals("circle"))canvas.drawCircle(offsetX,offsetY,(float)collider.optDouble("radius",20),paint);
        else {float w=(float)collider.optDouble("width",40),h=(float)collider.optDouble("height",40);
            canvas.drawRect(offsetX-w/2,offsetY-h/2,offsetX+w/2,offsetY+h/2,paint);}
        canvas.restore();
    }
    private void drawSelection(Canvas canvas,JSONObject world,JSONObject node,float scale){
        canvas.save();canvas.concat(nodeMatrix(world,node,new HashSet<>()));
        JSONObject t=J.obj(node,"transform");float w=(float)t.optDouble("width",48),h=(float)t.optDouble("height",48);
        paint.reset();paint.setAntiAlias(true);paint.setColor(ACCENT);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2/scale);paint.setPathEffect(new DashPathEffect(new float[]{7/scale,4/scale},0));
        canvas.drawRect(-w/2,-h/2,w/2,h/2,paint);paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(213,255,176));
        float r=4/scale;
        for(float x:new float[]{-w/2,w/2})for(float y:new float[]{-h/2,h/2})canvas.drawRoundRect(x-r,y-r,x+r,y+r,1/scale,1/scale,paint);
        canvas.restore();
    }
    private void drawOverlay(Canvas canvas,JSONObject world,float scale){
        paint.reset();paint.setAntiAlias(true);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        paint.setTextSize(11*density);paint.setColor(Color.rgb(217,231,231));
        float box=runtime!=null?210*density:190*density;
        paint.setColor(Color.argb(195,14,23,34));canvas.drawRoundRect(12*density,10*density,box,41*density,9*density,9*density,paint);
        paint.setColor(ACCENT);canvas.drawCircle(25*density,25*density,3*density,paint);
        paint.setColor(Color.WHITE);
        String title=runtime!=null?"● PLAY  ·  "+world.optString("name"):world.optString("name")+"  ·  "+world.optInt("width")+"×"+world.optInt("height");
        canvas.drawText(title,34*density,30*density,paint);
        if(runtime==null){
            paint.setColor(Color.argb(205,15,25,37));canvas.drawRoundRect(12*density,getHeight()-37*density,
                187*density,getHeight()-10*density,7*density,7*density,paint);
            paint.setColor(Color.rgb(177,197,204));paint.setTextSize(10*density);
            canvas.drawText("X "+(int)cursorX+"   Y "+(int)cursorY+"   "+Math.round(scale*100)+"%",21*density,getHeight()-18*density,paint);
            if(placementId!=null){paint.setColor(ACCENT);paint.setTextSize(12*density);
                canvas.drawText("Tap to place · two fingers to pan",15*density,63*density,paint);}
        }else{
            paint.setColor(Color.argb(210,15,25,37));canvas.drawRoundRect(12*density,50*density,
                200*density,86*density,9*density,9*density,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(12*density);
            canvas.drawText("♥ "+(int)runtime.stat("health")+"     ✦ "+(int)runtime.stat("score")+
                (runtime.project.data.optString("genre").equals("racing")?"     LAP "+(int)runtime.stat("lap"):""),23*density,73*density,paint);
            if(runtime.collisionDebug){paint.setTextSize(10*density);paint.setColor(ACCENT);
                canvas.drawText("FPS "+runtime.fps+"  ·  "+runtime.frameMs+"ms  ·  "+GameProject.nodes(world).length()+" objects  ·  "+runtime.particles.size()+" particles",16*density,getHeight()-17*density,paint);}
            if(!runtime.message.isEmpty()&&(runtime.won||runtime.over||runtime.elapsed-runtime.messageTime<3.5f)){
                paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize((runtime.won||runtime.over?21:15)*density);
                paint.setColor(Color.WHITE);paint.setShadowLayer(5*density,0,2*density,Color.BLACK);
                canvas.drawText(runtime.message,getWidth()/2f,getHeight()*0.29f,paint);paint.clearShadowLayer();paint.setTextAlign(Paint.Align.LEFT);
            }
        }
    }
    private static int withAlpha(int color,int alpha){return (color&0x00ffffff)|(Math.min(255,Math.max(0,alpha))<<24);}
    private static int color(String value,int fallback){try{return Color.parseColor(value);}catch(Exception ignored){return fallback;}}
    private static int blend(int a,int b,float t){return Color.rgb((int)(Color.red(a)*(1-t)+Color.red(b)*t),
        (int)(Color.green(a)*(1-t)+Color.green(b)*t),(int)(Color.blue(a)*(1-t)+Color.blue(b)*t));}
    public float[] screenToWorld(float x,float y){JSONObject world=activeScene();if(world==null)return new float[]{0,0};
        float factor=drawZoom(world);return new float[]{(x-getWidth()/2f)/factor+viewX(world,factor),
            (y-getHeight()/2f)/factor+viewY(world,factor)};}
    private JSONObject hit(float wx,float wy){if(scene==null)return null;
        JSONArray nodes=GameProject.nodes(scene);JSONObject result=null;int layer=Integer.MIN_VALUE;
        for(int i=0;i<nodes.length();i++){
            JSONObject n=J.at(nodes,i);
            if(n.optBoolean("locked")||!n.optBoolean("visible",true)||GameProject.component(n,"tilemap")!=null)continue;
            Matrix transform=nodeMatrix(scene,n,new HashSet<>()),inverse=new Matrix();
            if(!transform.invert(inverse))continue;float[] local={wx,wy};inverse.mapPoints(local);
            JSONObject t=J.obj(n,"transform");
            if(Math.abs(local[0])<=t.optDouble("width")/2 && Math.abs(local[1])<=t.optDouble("height")/2&&n.optInt("layer")>=layer){
                result=n;layer=n.optInt("layer");
            }
        }return result;
    }
    private float snapped(float value){return snap?Math.round(value/gridSize)*gridSize:value;}
    private void paintTile(float wx,float wy){
        JSONObject n=GameProject.node(scene,selectedId);
        JSONObject map=n==null?null:GameProject.component(n,"tilemap");
        if(map==null){if(listener!=null)listener.hint("Select a TileMap in the hierarchy first.");return;}
        Matrix inverse=new Matrix();if(!nodeMatrix(scene,n,new HashSet<>()).invert(inverse))return;
        float[] pos={wx,wy};inverse.mapPoints(pos);
        int size=map.optInt("tileSize",48),cols=map.optInt("columns",20),rows=map.optInt("rows",12);
        int col=(int)Math.floor((pos[0]+cols*size/2f)/size),row=(int)Math.floor((pos[1]+rows*size/2f)/size);
        if(col<0||row<0||col>=cols||row>=rows)return;
        JSONObject cells=J.obj(map,"cells");String key=col+","+row;
        if(tool==Tool.ERASE){if(cells.has(key)){cells.remove(key);if(listener!=null)listener.changed(false);}}
        else if(brushAssetId!=null&&!cells.optString(key).equals(brushAssetId)){
            J.put(cells,key,brushAssetId);if(listener!=null)listener.changed(false);
        }invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(thumb)return false;
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_POINTER_DOWN&&e.getPointerCount()>=2){
            pinchDistance=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
            pinchZoom=zoom;startPanX=panX;startPanY=panY;
            pinchX=(e.getX(0)+e.getX(1))/2;pinchY=(e.getY(0)+e.getY(1))/2;
            moving=false;return true;
        }
        if(action==MotionEvent.ACTION_MOVE&&e.getPointerCount()>=2){
            if(runtime==null){
                float distance=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
                float midpointX=(e.getX(0)+e.getX(1))/2,midpointY=(e.getY(0)+e.getY(1))/2;
                zoom=Math.max(0.16f,Math.min(5f,pinchZoom*distance/Math.max(1,pinchDistance)));
                panX=startPanX+(pinchX-midpointX)/zoom;panY=startPanY+(pinchY-midpointY)/zoom;invalidate();
            }return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){startX=e.getX(0);startY=e.getY(0);return true;}
        float[] point=screenToWorld(e.getX(),e.getY());cursorX=point[0];cursorY=point[1];
        if(runtime!=null){
            runtime.aimX=point[0];runtime.aimY=point[1];
            if(action==MotionEvent.ACTION_DOWN){startX=e.getX();startY=e.getY();return true;}
            if(action==MotionEvent.ACTION_UP&&Math.hypot(e.getX()-startX,e.getY()-startY)<12*density){
                runtime.tap(point[0],point[1]);invalidate();return true;
            }invalidate();return true;
        }
        switch(action){
            case MotionEvent.ACTION_DOWN:{
                startX=e.getX();startY=e.getY();startPanX=panX;startPanY=panY;
                moving=false;historyMade=false;
                if(placementId!=null)return true;
                if(tool==Tool.BRUSH||tool==Tool.ERASE){if(listener!=null)listener.beginGesture();historyMade=true;paintTile(point[0],point[1]);return true;}
                if(tool!=Tool.PAN){JSONObject n=hit(point[0],point[1]);
                    selectedId=n==null?null:n.optString("id");if(listener!=null)listener.selected(selectedId);
                    if(n!=null){JSONObject t=J.obj(n,"transform");originalX=(float)t.optDouble("x");originalY=(float)t.optDouble("y");
                        originalAngle=(float)t.optDouble("rotation");originalScaleX=(float)t.optDouble("scaleX",1);
                        originalScaleY=(float)t.optDouble("scaleY",1);}
                }invalidate();return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(Math.hypot(e.getX()-startX,e.getY()-startY)>5*density)moving=true;
                if(!moving)return true;
                if(tool==Tool.BRUSH||tool==Tool.ERASE){paintTile(point[0],point[1]);return true;}
                if(tool==Tool.PAN||selectedId==null){panX=startPanX-(e.getX()-startX)/zoom;
                    panY=startPanY-(e.getY()-startY)/zoom;invalidate();return true;}
                if(!historyMade&&listener!=null){listener.beginGesture();historyMade=true;}
                JSONObject n=GameProject.node(scene,selectedId);if(n==null||n.optBoolean("locked"))return true;
                JSONObject t=J.obj(n,"transform");
                float dx=(e.getX()-startX)/zoom,dy=(e.getY()-startY)/zoom;
                if(tool==Tool.ROTATE)J.put(t,"rotation",Math.round(originalAngle+(e.getX()-startX)*0.6f));
                else if(tool==Tool.SCALE){float factor=Math.max(0.1f,1+(e.getX()-startX)/(110*density));
                    J.put(t,"scaleX",Math.round(originalScaleX*factor*100)/100f);
                    J.put(t,"scaleY",Math.round(originalScaleY*factor*100)/100f);
                }else{J.put(t,"x",snapped(originalX+dx));J.put(t,"y",snapped(originalY+dy));}
                if(listener!=null)listener.changed(false);invalidate();return true;
            }
            case MotionEvent.ACTION_UP:{
                if(placementId!=null){if(listener!=null)listener.place(placementId,snapped(point[0]),snapped(point[1]));
                    placementId=null;invalidate();return true;}
                if(historyMade&&listener!=null)listener.changed(true);
                invalidate();return true;
            }
            case MotionEvent.ACTION_CANCEL:{if(historyMade&&listener!=null)listener.changed(true);return true;}
        }return true;
    }
}
