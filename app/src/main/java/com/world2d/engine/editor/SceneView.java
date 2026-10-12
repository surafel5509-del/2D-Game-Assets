package com.world2d.engine.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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
import com.world2d.engine.runtime.HillClimbMode;
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
        if(runtime.hillClimb!=null)return runtime.cameraX();
        float half=getWidth()/2f/factor;
        return half>=world.optInt("width")/2f?world.optInt("width")/2f:
            Math.max(half,Math.min(world.optInt("width")-half,runtime.cameraX()));
    }
    private float viewY(JSONObject world,float factor){
        if(runtime==null)return panY;
        if(runtime.hillClimb!=null)return runtime.cameraY();
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
        if(isHillScene(world))drawHillBackdrop(canvas,world,cx,cy,scale);
        else{
            int background=color(world.optString("background","#263d50"),Color.rgb(38,61,80));
            paint.reset();paint.setAntiAlias(true);paint.setShader(new LinearGradient(0,0,0,height,
                background,blend(background,Color.rgb(15,33,51),0.28f), Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,width,height,paint);paint.setShader(null);
        }
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
    private boolean isHillScene(JSONObject world){
        if(runtime!=null&&runtime.hillClimb!=null)return true;
        JSONArray nodes=GameProject.nodes(world);
        for(int i=0;i<nodes.length();i++)if(GameProject.component(J.at(nodes,i),"hillTerrain")!=null)return true;
        return false;
    }
    private int hillSeed(JSONObject world){
        JSONArray nodes=GameProject.nodes(world);
        for(int i=0;i<nodes.length();i++){
            JSONObject terrain=GameProject.component(J.at(nodes,i),"hillTerrain");
            if(terrain!=null)return terrain.optInt("seed",18473);
        }
        return 18473;
    }
    private void drawHillBackdrop(Canvas canvas,JSONObject world,float cx,float cy,float scale){
        int stage=runtime!=null&&runtime.hillClimb!=null?runtime.hillClimb.stage():HillClimbMode.stageAt(cx,104);
        int seed=hillSeed(world);
        int skyTop,skyBottom;
        if(stage==1){skyTop=Color.rgb(255,185,116);skyBottom=Color.rgb(255,232,179);}
        else if(stage==2){skyTop=Color.rgb(119,183,218);skyBottom=Color.rgb(222,244,250);}
        else if(stage==3){skyTop=Color.rgb(25,34,79);skyBottom=Color.rgb(105,106,174);}
        else{skyTop=Color.rgb(93,190,231);skyBottom=Color.rgb(211,243,247);}
        float viewHeight=runtime!=null?getHeight()/Math.max(0.1f,scale):world.optInt("height",540);
        float viewWidth=runtime!=null?getWidth()/Math.max(0.1f,scale):world.optInt("width",960);
        float top=runtime!=null?cy-viewHeight/2-130:-120;
        float bottom=runtime!=null?cy+viewHeight/2+330:world.optInt("height",540)+260;
        float left=runtime!=null?cx-viewWidth/2-140:-140;
        float right=runtime!=null?cx+viewWidth/2+140:world.optInt("width",960)+140;
        paint.reset();paint.setAntiAlias(true);paint.setShader(new LinearGradient(0,top,0,bottom,
            skyTop,skyBottom,Shader.TileMode.CLAMP));canvas.drawRect(left,top,right,bottom,paint);paint.setShader(null);
        if(stage==3){
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(190,240,245,255));
            for(int i=0;i<60;i++){
                float x=left+(float)((i*79.7+seed*13)%Math.max(1,right-left));
                float y=top+26+(i*47%175);
                canvas.drawCircle(x,y,(i%7==0?1.7f:0.9f),paint);
            }
            paint.setColor(Color.rgb(226,233,255));canvas.drawCircle(cx+viewWidth*0.22f,92,33,paint);
            paint.setColor(Color.rgb(105,106,174));canvas.drawCircle(cx+viewWidth*0.22f+12,82,31,paint);
        }else{
            paint.setColor(stage==1?Color.rgb(255,236,163):Color.rgb(255,242,183));
            canvas.drawCircle(cx+viewWidth*0.23f,97,stage==1?40:31,paint);
            paint.setColor(Color.argb(32,255,255,255));canvas.drawCircle(cx+viewWidth*0.23f,97,49,paint);
            drawHillClouds(canvas,left,right,top,cx,seed,stage);
        }
        int far=stage==1?Color.rgb(217,171,122):stage==2?Color.rgb(153,200,213):
            stage==3?Color.rgb(52,66,130):Color.rgb(113,178,178);
        int near=stage==1?Color.rgb(192,145,106):stage==2?Color.rgb(121,178,198):
            stage==3?Color.rgb(43,56,113):Color.rgb(78,151,146);
        float horizon=350;
        drawHillRidge(canvas,left,right,cx,0.16f,horizon,72,withAlpha(far,170),seed,510);
        drawHillRidge(canvas,left,right,cx,0.29f,horizon+36,49,withAlpha(near,220),seed+21,360);
    }
    private void drawHillClouds(Canvas canvas,float left,float right,float top,float cx,int seed,int stage){
        int color=stage==1?Color.argb(130,255,250,232):Color.argb(155,255,255,255);
        float offset=cx*0.12f;int first=(int)Math.floor((left-offset)/310f),last=(int)Math.ceil((right-offset)/310f);
        paint.reset();paint.setAntiAlias(true);paint.setColor(color);
        for(int i=first;i<=last;i++){
            float x=offset+i*310f+(float)(Math.sin(i*7.17+seed)*55);
            float y=top+105+(float)(Math.sin(i*2.47+seed)*22);
            canvas.drawCircle(x,y,17,paint);canvas.drawCircle(x+21,y-8,24,paint);
            canvas.drawCircle(x+48,y,17,paint);canvas.drawRoundRect(x-1,y-1,x+49,y+15,8,8,paint);
        }
    }
    private void drawHillRidge(Canvas canvas,float left,float right,float cameraX,float parallax,
                               float baseline,float amplitude,int color,int seed,float wavelength){
        float offset=cameraX*parallax;
        float start=left-offset,end=right-offset;
        Path path=new Path();path.moveTo(left,baseline);
        int count=(int)Math.ceil((end-start)/36f);
        for(int i=0;i<=count;i++){
            float x=start+i*36f;
            double a=Math.sin((x+seed*7)*Math.PI*2/wavelength);
            double b=Math.sin((x+seed*19)*Math.PI*2/(wavelength*0.47));
            path.lineTo(x+offset,(float)(baseline-amplitude*(0.58*a+0.28*b)));
        }
        path.lineTo(right,baseline+200);path.lineTo(left,baseline+200);path.close();
        paint.reset();paint.setAntiAlias(true);paint.setColor(color);canvas.drawPath(path,paint);
    }
    private void drawHillTerrain(Canvas canvas,JSONObject world,JSONObject terrain,float scale){
        int seed=terrain.optInt("seed",hillSeed(world));
        int stage=runtime!=null&&runtime.hillClimb!=null?runtime.hillClimb.stage():HillClimbMode.stageAt(260,104);
        float viewWidth=runtime!=null?getWidth()/Math.max(0.1f,scale):world.optInt("width",960);
        float left=runtime!=null?runtime.cameraX()-viewWidth/2-80:-60;
        float right=runtime!=null?runtime.cameraX()+viewWidth/2+80:world.optInt("width",960)+60;
        float bottom=runtime!=null?runtime.cameraY()+getHeight()/Math.max(0.1f,scale)/2+500:world.optInt("height",540)+650;
        drawHillTrees(canvas,left,right,seed,stage);
        Path soil=new Path(),topLine=new Path(),strata=new Path();
        int steps=Math.max(1,(int)Math.ceil((right-left)/12f));
        for(int i=0;i<=steps;i++){
            float x=left+(right-left)*i/steps;
            float y=(float)HillClimbMode.terrainHeight(x,seed);
            if(i==0){soil.moveTo(x,y);topLine.moveTo(x,y);strata.moveTo(x,y+68);}
            else{soil.lineTo(x,y);topLine.lineTo(x,y);strata.lineTo(x,y+68+(float)Math.sin(x*0.013)*4);}
        }
        soil.lineTo(right,bottom);soil.lineTo(left,bottom);soil.close();
        int dirtTop=stage==1?Color.rgb(204,151,84):stage==2?Color.rgb(153,183,181):
            stage==3?Color.rgb(86,83,136):Color.rgb(146,91,57);
        int dirtDeep=stage==1?Color.rgb(130,86,48):stage==2?Color.rgb(81,113,129):
            stage==3?Color.rgb(43,39,83):Color.rgb(89,54,43);
        int grass=stage==1?Color.rgb(189,151,74):stage==2?Color.rgb(225,244,234):
            stage==3?Color.rgb(132,143,229):Color.rgb(76,164,72);
        int highlight=stage==1?Color.rgb(234,193,105):stage==2?Color.rgb(249,255,245):
            stage==3?Color.rgb(180,181,255):Color.rgb(129,207,87);
        paint.reset();paint.setAntiAlias(true);paint.setShader(new LinearGradient(0,330,0,bottom,
            dirtTop,dirtDeep,Shader.TileMode.CLAMP));canvas.drawPath(soil,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(7/Math.max(0.55f,scale));
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(grass);canvas.drawPath(topLine,paint);
        paint.setStrokeWidth(2/Math.max(0.55f,scale));paint.setColor(highlight);canvas.drawPath(topLine,paint);
        paint.setStrokeWidth(1.5f/Math.max(0.55f,scale));paint.setColor(Color.argb(75,52,40,34));
        canvas.drawPath(strata,paint);paint.setStyle(Paint.Style.FILL);paint.setStrokeCap(Paint.Cap.BUTT);
        float first=(float)Math.floor(left/33f)*33f;
        for(float x=first;x<right;x+=33){
            float y=(float)HillClimbMode.terrainHeight(x,seed)+29+(float)(Math.sin(x*0.09+seed)*13);
            paint.setColor(stage==2?Color.argb(110,232,250,242):Color.argb(90,54,39,35));
            canvas.drawOval(x,y,x+5+(Math.abs((int)x)%6),y+2.5f,paint);
        }
        if(stage==0){
            paint.setColor(Color.argb(180,167,226,113));paint.setStrokeWidth(1.8f/Math.max(0.55f,scale));
            for(float x=(float)Math.floor(left/58f)*58f;x<right;x+=58){
                float y=(float)HillClimbMode.terrainHeight(x,seed)-3;
                canvas.drawLine(x,y,x-2,y-5,paint);canvas.drawLine(x+1,y,x+4,y-4,paint);
            }
        }
    }
    private void drawHillTrees(Canvas canvas,float left,float right,int seed,int stage){
        if(stage==3)return;
        float spacing=stage==1?390:310;
        int first=(int)Math.floor(left/spacing),last=(int)Math.ceil(right/spacing);
        for(int i=first;i<=last;i++){
            float x=i*spacing+(float)(Math.sin(i*13.11+seed)*64);
            if(x<left-90||x>right+90)continue;
            float base=(float)HillClimbMode.terrainHeight(x,seed)+5;
            if(stage==1){
                paint.setColor(Color.rgb(89,127,79));paint.setStrokeWidth(8);paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(x,base,x,base-32,paint);canvas.drawLine(x,base-20,x-14,base-29,paint);
                canvas.drawLine(x,base-13,x+15,base-24,paint);canvas.drawCircle(x-2,base-33,12,paint);
            }else{
                paint.setColor(stage==2?Color.rgb(73,128,118):Color.rgb(48,117,89));
                canvas.drawRect(x-3,base-30,x+4,base,paint);
                for(int tier=0;tier<3;tier++){
                    float y=base-19-tier*18;
                    Path pine=new Path();pine.moveTo(x,y-25);pine.lineTo(x-20+tier*4,y+7);pine.lineTo(x+20-tier*4,y+7);pine.close();
                    paint.setColor(stage==2?Color.rgb(79+tier*8,143+tier*8,133+tier*6):Color.rgb(54+tier*7,137+tier*7,91+tier*5));
                    canvas.drawPath(pine,paint);
                }
                if(stage==2){paint.setColor(Color.argb(220,237,250,244));canvas.drawCircle(x-5,base-46,4,paint);}
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }
    private void drawHillVehicleBody(Canvas canvas,JSONObject node,JSONObject component){
        int type=runtime!=null&&runtime.hillClimb!=null?runtime.hillClimb.vehicleIndex():component.optInt("vehicleIndex",0);
        if(runtime==null){
            float bob=(float)Math.sin(System.currentTimeMillis()/270.0)*1.2f;canvas.translate(0,bob);
        }
        if(type==1){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(7);paint.setColor(Color.rgb(220,72,55));
            Path frame=new Path();frame.moveTo(-25,8);frame.lineTo(-4,-20);frame.lineTo(25,7);frame.lineTo(-25,8);
            canvas.drawPath(frame,paint);paint.setStrokeWidth(4);paint.setColor(Color.rgb(70,79,88));
            canvas.drawLine(-22,5,21,5,paint);canvas.drawLine(-6,-18,5,-32,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(41,48,57));
            canvas.drawRoundRect(-12,-27,13,-18,5,5,paint);canvas.drawCircle(4,-38,9,paint);
            paint.setColor(Color.rgb(240,183,105));canvas.drawCircle(6,-39,6,paint);
            paint.setStrokeCap(Paint.Cap.BUTT);return;
        }
        int body=type==3?Color.rgb(94,133,103):type==2?Color.rgb(227,103,44):Color.rgb(221,70,54);
        int trim=type==3?Color.rgb(43,70,58):type==2?Color.rgb(115,57,39):Color.rgb(125,48,43);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(48,19,33,34));
        canvas.drawOval(-49,3,52,18,paint);
        if(type==2){
            paint.setColor(trim);canvas.drawRoundRect(-54,-28,53,11,10,10,paint);
            paint.setColor(body);canvas.drawRoundRect(-50,-34,50,4,9,9,paint);
            paint.setColor(Color.rgb(29,43,50));canvas.drawRoundRect(-28,-52,25,-28,9,9,paint);
            paint.setColor(Color.rgb(153,218,226));canvas.drawRoundRect(-20,-47,-2,-31,4,4,paint);
            canvas.drawRoundRect(3,-47,17,-31,4,4,paint);
        }else{
            paint.setColor(trim);canvas.drawRoundRect(-51,-25,51,11,9,9,paint);
            paint.setColor(body);canvas.drawRoundRect(-49,-29,49,6,8,8,paint);
            canvas.drawRoundRect(-25,-50,27,-25,9,9,paint);
            paint.setColor(Color.rgb(118,194,210));canvas.drawRoundRect(-19,-45,0,-28,4,4,paint);
            canvas.drawRoundRect(4,-45,21,-28,4,4,paint);
            paint.setColor(Color.argb(115,255,255,255));canvas.drawLine(-15,-42,-6,-33,paint);
            canvas.drawLine(8,-42,17,-33,paint);
        }
        paint.setColor(type==3?Color.rgb(189,198,142):Color.rgb(248,172,91));
        canvas.drawRoundRect(21,-19,49,-11,4,4,paint);
        paint.setColor(Color.rgb(248,223,139));canvas.drawRoundRect(43,-21,50,-13,2,2,paint);
        paint.setColor(Color.rgb(53,62,67));canvas.drawRoundRect(47,-5,58,2,2,2,paint);
        paint.setColor(trim);canvas.drawRoundRect(-41,-4,-34,4,2,2,paint);canvas.drawRoundRect(20,-4,27,4,2,2,paint);
        paint.setColor(Color.rgb(255,226,160));canvas.drawCircle(-4,-36,8,paint);
        paint.setColor(Color.rgb(47,71,80));canvas.drawArc(-13,-45,5,-29,180,180,false,paint);
        paint.setColor(Color.rgb(40,50,58));canvas.drawCircle(-1,-37,1.3f,paint);
        paint.setColor(Color.rgb(255,255,255));canvas.drawCircle(-2,-39,1.3f,paint);
        paint.setColor(Color.argb(140,255,228,163));canvas.drawRoundRect(-38,-27,-28,-23,2,2,paint);
    }
    private void drawHillWheel(Canvas canvas,JSONObject wheel){
        float r=(float)wheel.optDouble("radius",19),spin=(float)wheel.optDouble("spin",0);
        canvas.save();canvas.rotate(spin);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(26,34,39));canvas.drawCircle(0,0,r,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(2,r*0.17f));
        paint.setColor(Color.rgb(49,60,63));canvas.drawCircle(0,0,r*0.78f,paint);
        for(int i=0;i<8;i++){
            canvas.save();canvas.rotate(i*45);
            paint.setStrokeWidth(Math.max(1.5f,r*0.12f));paint.setColor(Color.rgb(101,111,108));
            canvas.drawLine(0,-r*0.82f,0,-r*0.96f,paint);canvas.restore();
        }
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(188,195,183));canvas.drawCircle(0,0,r*0.49f,paint);
        paint.setColor(Color.rgb(106,125,131));canvas.drawCircle(0,0,r*0.31f,paint);
        paint.setColor(Color.rgb(238,206,125));canvas.drawCircle(0,0,r*0.12f,paint);
        canvas.restore();
    }
    private void drawFuelCan(Canvas canvas,float width,float height){
        float w=width,h=height;
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(226,63,47));
        canvas.drawRoundRect(-w/2+3,-h/2+7,w/2-3,h/2-2,6,6,paint);
        paint.setColor(Color.rgb(171,39,38));canvas.drawRoundRect(-w/2+7,-h/2,w/2-10,-h/2+11,3,3,paint);
        paint.setColor(Color.rgb(243,91,59));canvas.drawRoundRect(-w/2+10,-h/2+3,0,-h/2+11,3,3,paint);
        paint.setColor(Color.rgb(255,229,176));paint.setStrokeWidth(2.5f);
        canvas.drawRect(-w/2+9,-h/2+16,w/2-10,h/2-10,paint);
        paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(Math.min(w,h)*0.48f);canvas.drawText("F",0,h*0.23f,paint);paint.setTextAlign(Paint.Align.LEFT);
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
        JSONObject hillTerrain=GameProject.component(node,"hillTerrain");
        if(hillTerrain!=null){drawHillTerrain(canvas,world,hillTerrain,scale);calls++;}
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
        JSONObject hillWheel=GameProject.component(node,"hillWheel");
        if(hillWheel!=null){drawHillWheel(canvas,hillWheel);calls++;}
        JSONObject hillVehicle=GameProject.component(node,"hillVehicle");
        if(hillVehicle!=null){drawHillVehicleBody(canvas,node,hillVehicle);calls++;}
        JSONObject hillPickup=GameProject.component(node,"hillPickup");
        if(hillPickup!=null&&"fuel".equals(hillPickup.optString("kind"))){drawFuelCan(canvas,w,h);calls++;}
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
        if(runtime!=null&&runtime.hillClimb!=null){drawHillHud(canvas);return;}
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
    private void drawHillHud(Canvas canvas){
        HillClimbMode hill=runtime.hillClimb;float d=density;float width=getWidth(),height=getHeight();
        float leftW=Math.min(202*d,width*0.32f),rightW=Math.min(172*d,width*0.28f),centerW=Math.min(148*d,width*0.25f);
        float panelY=12*d,panelH=91*d;
        paint.reset();paint.setAntiAlias(true);paint.setColor(Color.argb(208,18,34,43));
        canvas.drawRoundRect(12*d,panelY,12*d+leftW,panelY+panelH,12*d,12*d,paint);
        float cx=width/2f,centerLeft=cx-centerW/2f;
        canvas.drawRoundRect(centerLeft,panelY,centerLeft+centerW,panelY+panelH,12*d,12*d,paint);
        float rightLeft=width-rightW-12*d;
        canvas.drawRoundRect(rightLeft,panelY,width-12*d,panelY+panelH,12*d,12*d,paint);

        paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD));
        paint.setTextAlign(Paint.Align.LEFT);paint.setTextSize(12*d);paint.setColor(Color.rgb(246,251,235));
        canvas.drawText("HILL CLIMB",24*d,31*d,paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        paint.setTextSize(8.5f*d);paint.setColor(Color.rgb(169,196,188));
        canvas.drawText(hill.vehicle().shortName+"  ·  BEST "+Math.round(hill.bestDistance())+" m",24*d,46*d,paint);
        paint.setColor(Color.rgb(255,202,73));paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(31*d,63*d,6*d,paint);paint.setColor(Color.rgb(255,245,190));
        canvas.drawCircle(29*d,61*d,2*d,paint);
        paint.setColor(Color.rgb(249,221,154));paint.setTextSize(10*d);
        canvas.drawText(String.valueOf(hill.totalCoins()),43*d,67*d,paint);
        paint.setColor(Color.rgb(92,209,242));Path gem=new Path();
        gem.moveTo(97*d,57*d);gem.lineTo(103*d,63*d);gem.lineTo(97*d,69*d);gem.lineTo(91*d,63*d);gem.close();
        canvas.drawPath(gem,paint);paint.setColor(Color.rgb(219,245,255));
        canvas.drawText(String.valueOf(hill.totalDiamonds()),109*d,67*d,paint);
        paint.setColor(Color.argb(150,5,19,25));canvas.drawRoundRect(24*d,76*d,leftW+2*d,86*d,5*d,5*d,paint);
        float fuelEnd=24*d+(leftW-40*d)*hill.fuelFraction();
        int fuelColor=hill.fuelFraction()<0.25f?Color.rgb(244,79,56):
            hill.fuelFraction()<0.52f?Color.rgb(255,183,64):Color.rgb(121,218,112);
        paint.setColor(fuelColor);canvas.drawRoundRect(24*d,76*d,Math.max(29*d,fuelEnd),86*d,5*d,5*d,paint);
        paint.setTextSize(7.5f*d);paint.setColor(Color.WHITE);
        canvas.drawText("FUEL  "+Math.round(hill.fuelFraction()*100)+"%",leftW-39*d,94*d,paint);

        paint.setTextAlign(Paint.Align.CENTER);paint.setColor(Color.rgb(174,205,205));paint.setTextSize(8*d);
        canvas.drawText("DISTANCE",cx,panelY+18*d,paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD));
        paint.setTextSize(24*d);paint.setColor(Color.WHITE);
        canvas.drawText(String.format(java.util.Locale.US,"%04d",Math.round(hill.distance())),cx,panelY+48*d,paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));paint.setTextSize(9*d);
        paint.setColor(Color.rgb(255,219,136));canvas.drawText("METERS",cx,panelY+65*d,paint);
        paint.setColor(Color.rgb(204,228,221));paint.setTextSize(8*d);
        canvas.drawText("RUN  +"+hill.runCoins()+" COINS",cx,panelY+81*d,paint);

        paint.setTextAlign(Paint.Align.RIGHT);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        paint.setTextSize(7.5f*d);paint.setColor(Color.rgb(169,196,188));
        canvas.drawText("STAGE  "+(hill.stage()+1)+" / 04",width-23*d,panelY+23*d,paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD));
        paint.setTextSize(10*d);paint.setColor(Color.rgb(243,249,229));
        canvas.drawText(HillClimbMode.stageName(hill.stage()),width-23*d,panelY+42*d,paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        paint.setTextSize(8*d);paint.setColor(Color.rgb(202,218,210));
        canvas.drawText("ENGINE  "+(hill.engineLevel()+1)+"    TYRES  "+(hill.tyreLevel()+1),width-23*d,panelY+61*d,paint);
        canvas.drawText("BOOST",width-23*d,panelY+78*d,paint);
        float boostLeft=rightLeft+11*d,boostRight=width-23*d;
        paint.setTextAlign(Paint.Align.LEFT);paint.setColor(Color.argb(145,5,19,25));
        canvas.drawRoundRect(boostLeft,panelY+72*d,boostRight,panelY+82*d,4*d,4*d,paint);
        paint.setColor(Color.rgb(81,204,226));
        canvas.drawRoundRect(boostLeft,panelY+72*d,boostLeft+(boostRight-boostLeft)*hill.boost()/100f,
            panelY+82*d,4*d,4*d,paint);

        float gaugeX=width/2f,gaugeY=height+9*d,radius=54*d;
        paint.setColor(Color.argb(195,15,29,37));paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(gaugeX-77*d,height-48*d,gaugeX+77*d,height+20*d,18*d,18*d,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(5*d);
        paint.setColor(Color.argb(100,229,238,220));
        canvas.drawArc(new RectF(gaugeX-radius,gaugeY-radius,gaugeX+radius,gaugeY+radius),180,180,false,paint);
        paint.setColor(hill.rpm()>5400?Color.rgb(250,105,64):Color.rgb(255,203,85));
        canvas.drawArc(new RectF(gaugeX-radius,gaugeY-radius,gaugeX+radius,gaugeY+radius),180,
            180*Math.max(0,Math.min(1,(hill.rpm()-850)/6050f)),false,paint);
        float needle=(float)Math.toRadians(180+180*Math.max(0,Math.min(1,(hill.rpm()-850)/6050f)));
        paint.setStrokeWidth(2*d);paint.setColor(Color.WHITE);
        canvas.drawLine(gaugeX,gaugeY,gaugeX+(float)Math.cos(needle)*radius*0.78f,
            gaugeY+(float)Math.sin(needle)*radius*0.78f,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(255,218,145));canvas.drawCircle(gaugeX,gaugeY,4*d,paint);
        paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD));
        paint.setTextSize(8*d);paint.setColor(Color.rgb(202,220,210));canvas.drawText("RPM",gaugeX,height-23*d,paint);
        paint.setTextSize(9*d);paint.setColor(Color.WHITE);canvas.drawText(String.valueOf(Math.round(hill.rpm())),gaugeX,height-10*d,paint);
        paint.setStrokeCap(Paint.Cap.BUTT);paint.setTextAlign(Paint.Align.LEFT);paint.setStyle(Paint.Style.FILL);

        if(runtime.over){
            paint.setColor(Color.argb(140,7,16,23));canvas.drawRect(0,0,width,height,paint);
            float cardW=Math.min(390*d,width-32*d),cardH=176*d,cardX=(width-cardW)/2,cardY=(height-cardH)/2;
            paint.setColor(Color.rgb(25,42,49));canvas.drawRoundRect(cardX,cardY,cardX+cardW,cardY+cardH,18*d,18*d,paint);
            paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD));
            paint.setTextSize(25*d);paint.setColor(Color.rgb(255,207,133));
            canvas.drawText(runtime.message.contains("FUEL")?"OUT OF FUEL":"RUN OVER",width/2f,cardY+43*d,paint);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));paint.setTextSize(11*d);
            paint.setColor(Color.WHITE);canvas.drawText("DISTANCE  "+Math.round(hill.distance())+" m",width/2f,cardY+77*d,paint);
            canvas.drawText("BEST  "+Math.round(hill.bestDistance())+" m     ·     +"+hill.runCoins()+" COINS",width/2f,cardY+102*d,paint);
            paint.setTextSize(9*d);paint.setColor(Color.rgb(175,202,191));
            canvas.drawText("Use the pause menu to retry or visit the garage",width/2f,cardY+137*d,paint);
            canvas.drawText("Tap to restart",width/2f,cardY+157*d,paint);paint.setTextAlign(Paint.Align.LEFT);
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
