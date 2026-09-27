package com.world2d.engine.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.data.ProjectStore;
import com.world2d.engine.editor.SceneView;
import com.world2d.engine.runtime.GameRuntime;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Launch activity shipped in game exports. Renders the author's actual scenes without the editor. */
public final class GameActivity extends Activity {
    private SceneView canvas;
    private GameRuntime game;
    private AssetLibrary assets;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=getAssets().open("game/project.json")){
                byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            }
            JSONObject data=new JSONObject(out.toString("UTF-8"));GameProject.validate(data);
            GameProject project=new GameProject(data);
            ProjectStore store=new ProjectStore(this);
            File dir=store.folder(project.id());dir.mkdirs();
            // Copy only imported assets. Built-in artwork is loaded directly from the APK assets.
            for(int i=0;i<project.assets().length();i++){
                JSONObject asset=J.at(project.assets(),i);
                File target=store.assetFile(project,asset.optString("src"));
                if(target==null)continue;target.getParentFile().mkdirs();
                if(target.isFile())continue;
                try(InputStream in=getAssets().open("game/"+asset.optString("src"));FileOutputStream file=new FileOutputStream(target)){
                    byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)file.write(bytes,0,n);
                }
            }
            assets=new AssetLibrary(this,store);
            game=new GameRuntime(this,project,project.data.optString("startSceneId"),assets);
            FrameLayout layout=new FrameLayout(this);layout.setBackgroundColor(Color.rgb(14,19,29));setContentView(layout);
            canvas=new SceneView(this,assets);canvas.setProject(project,project.firstScene());canvas.setRuntime(game);
            FrameLayout.LayoutParams canvasArea=new FrameLayout.LayoutParams(-1,-1);
            canvasArea.bottomMargin=dp(69);layout.addView(canvas,canvasArea);
            LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(8,8,8,12);
            for(String[] pair:new String[][]{{"◀","left"},{"▼","down"},{"▲","up"},{"▶","right"}})
                control(controls,pair[0],pair[1]);
            View spacer=new View(this);controls.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
            for(String[] pair:new String[][]{{"E","interact"},{"◎","fire"},{"↟","jump"}})
                control(controls,pair[0],pair[1]);
            layout.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            TextView pause=new TextView(this);pause.setText("Ⅱ");pause.setTextSize(21);pause.setTextColor(Color.WHITE);
            pause.setGravity(Gravity.CENTER);pause.setBackgroundColor(Color.argb(140,16,26,38));
            pause.setOnClickListener(v->{game.setPaused(true);
                new AlertDialog.Builder(this).setTitle("Game paused")
                .setItems(new String[]{"Resume","Restart","Exit"},(d,which)->{
                    if(which==0)game.setPaused(false);
                    else if(which==1)game.restart(null);else finish();
                }).setOnCancelListener(d->game.setPaused(false)).show();});
            game.onChange=()->canvas.postInvalidate();
            FrameLayout.LayoutParams pauseParams=new FrameLayout.LayoutParams(dp(46),dp(46),Gravity.RIGHT|Gravity.TOP);
            pauseParams.setMargins(0,dp(12),dp(12),0);layout.addView(pause,pauseParams);
        }catch(Exception ex){new AlertDialog.Builder(this).setTitle("Cannot open game")
            .setMessage(ex.getMessage()+"\n\nRe-export the project and check its missing resources.")
            .setPositiveButton("Close",(d,w)->finish()).show();}
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void control(LinearLayout row,String glyph,String action){
        TextView key=new TextView(this);key.setText(glyph);key.setTextSize(20);
        key.setGravity(Gravity.CENTER);key.setTextColor(Color.WHITE);
        key.setBackgroundColor(Color.argb(165,23,39,52));
        int available=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
        int buttonWidth=Math.max(31,Math.min(43,(available-24)/7-3));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(buttonWidth),dp(48));params.rightMargin=dp(3);
        row.addView(key,params);
        key.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){game.setAction(action,true);return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                game.setAction(action,false);return true;
            }return true;
        });
    }
    @Override protected void onPause(){super.onPause();if(game!=null)game.setPaused(true);if(canvas!=null)canvas.setAlive(false);}
    @Override protected void onResume(){super.onResume();if(canvas!=null)canvas.setAlive(true);}
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(game!=null){String action=game.mapKey(keyCode);
            if(action.equals("pause")){if(event.getRepeatCount()==0)game.setPaused(!game.paused);return true;}
            if(!action.isEmpty()){game.setAction(action,true);return true;}
        }
        return super.onKeyDown(keyCode,event);
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent event){
        if(game!=null){String action=game.mapKey(keyCode);
            if(!action.isEmpty()){game.setAction(action,false);return true;}
        }
        return super.onKeyUp(keyCode,event);
    }
    @Override public void onBackPressed(){if(game!=null)game.setPaused(!game.paused);else super.onBackPressed();}
    @Override protected void onDestroy(){if(assets!=null)assets.stopAudio();super.onDestroy();}
}
