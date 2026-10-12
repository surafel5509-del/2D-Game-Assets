package com.world2d.engine.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.data.ProjectStore;
import com.world2d.engine.editor.SceneView;
import com.world2d.engine.runtime.GameRuntime;
import com.world2d.engine.runtime.HillClimbMode;
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
    private boolean runOverDialogShown;
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
            canvasArea.bottomMargin=dp(game.hillClimb!=null?86:69);layout.addView(canvas,canvasArea);
            LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(12),dp(8),dp(12),dp(12));
            if(game.hillClimb!=null){
                controls.setGravity(Gravity.CENTER_VERTICAL);
                hillPedal(controls,"BRAKE","left",false);
                View spacer=new View(this);controls.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
                hillPedal(controls,"⚡ BOOST","up",true);
                View secondSpacer=new View(this);controls.addView(secondSpacer,new LinearLayout.LayoutParams(0,1,1));
                hillPedal(controls,"GAS","right",true);
            }else{
                for(String[] pair:new String[][]{{"◀","left"},{"▼","down"},{"▲","up"},{"▶","right"}})
                    control(controls,pair[0],pair[1]);
                View spacer=new View(this);controls.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
                for(String[] pair:new String[][]{{"E","interact"},{"◎","fire"},{"↟","jump"}})
                    control(controls,pair[0],pair[1]);
            }
            layout.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            TextView pause=new TextView(this);pause.setText("Ⅱ");pause.setTextSize(21);pause.setTextColor(Color.WHITE);
            pause.setGravity(Gravity.CENTER);pause.setBackgroundColor(Color.argb(155,16,26,38));
            pause.setContentDescription("Pause game");
            pause.setOnClickListener(v->{
                if(game.hillClimb!=null)showHillMenu();
                else{
                    game.setPaused(true);
                    new AlertDialog.Builder(this).setTitle("Game paused")
                    .setItems(new String[]{"Resume","Restart","Exit"},(d,which)->{
                        if(which==0)game.setPaused(false);
                        else if(which==1)game.restart(null);else finish();
                    }).setOnCancelListener(d->game.setPaused(false)).show();
                }
            });
            game.onChange=()->{
                canvas.postInvalidate();
                if(game.hillClimb!=null&&game.over&&!runOverDialogShown){
                    runOverDialogShown=true;canvas.post(this::showRunOver);
                }
            };
            FrameLayout.LayoutParams pauseParams=new FrameLayout.LayoutParams(dp(46),dp(46),Gravity.RIGHT|Gravity.TOP);
            pauseParams.setMargins(0,dp(game.hillClimb!=null?112:12),dp(12),0);layout.addView(pause,pauseParams);
        }catch(Exception ex){new AlertDialog.Builder(this).setTitle("Cannot open game")
            .setMessage(ex.getMessage()+"\n\nRe-export the project and check its missing resources.")
            .setPositiveButton("Close",(d,w)->finish()).show();}
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void hillPedal(LinearLayout row,String label,String action,boolean accent){
        TextView key=new TextView(this);key.setText(label);key.setTextSize(action.equals("up")?10:13);
        key.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD));
        key.setGravity(Gravity.CENTER);key.setTextColor(Color.WHITE);
        int top=accent?Color.rgb(198,91,47):Color.rgb(59,82,82);
        int bottom=accent?Color.rgb(128,52,40):Color.rgb(35,54,61);
        GradientDrawable shape=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});
        shape.setCornerRadius(dp(18));shape.setStroke(dp(1),Color.argb(125,255,255,255));key.setBackground(shape);
        int buttonWidth=action.equals("up")?88:116;
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(buttonWidth),dp(60));
        params.rightMargin=dp(5);row.addView(key,params);
        key.setContentDescription(action.equals("right")?"Gas pedal":action.equals("left")?"Brake pedal":"Boost");
        key.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){game.setAction(action,true);return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                game.setAction(action,false);return true;}return true;
        });
    }
    private void showHillMenu(){
        if(game==null||game.hillClimb==null)return;
        game.setPaused(true);
        new AlertDialog.Builder(this).setTitle("TRAILBLAZER  ·  PAUSED")
            .setItems(new String[]{"Resume run","Garage & upgrades","Restart run","Exit game"},(dialog,which)->{
                if(which==0)game.setPaused(false);
                else if(which==1)showGarage();
                else if(which==2){runOverDialogShown=false;game.restart(null);}
                else finish();
            }).setOnCancelListener(dialog->{if(!game.over)game.setPaused(false);}).show();
    }
    private void returnFromGarage(){
        if(game==null)return;
        if(game.over){runOverDialogShown=false;if(canvas!=null)canvas.post(this::showRunOver);}
        else game.setPaused(false);
    }
    private void showGarage(){
        if(game==null||game.hillClimb==null)return;
        game.setPaused(true);
        HillClimbMode hill=game.hillClimb;int vehicles=HillClimbMode.vehicleCount();
        String[] choices=new String[vehicles+7];
        for(int i=0;i<vehicles;i++){
            String status=hill.isVehicleUnlocked(i)?(hill.vehicleIndex()==i?"  ·  EQUIPPED":"  ·  SELECT"):
                "  ·  LOCKED  /  "+hill.vehiclePrice(i)+" coins";
            choices[i]=HillClimbMode.vehicleName(i)+status;
        }
        choices[vehicles]=hill.upgradeTitle("engine");
        choices[vehicles+1]=hill.upgradeTitle("suspension");
        choices[vehicles+2]=hill.upgradeTitle("tyres");
        choices[vehicles+3]=hill.upgradeTitle("fuel");
        choices[vehicles+4]=hill.upgradeTitle("fourWheelDrive");
        choices[vehicles+5]="Trade 1 diamond for 80 coins  ·  "+hill.totalDiamonds()+" available";
        choices[vehicles+6]="Done  ·  back to the run";
        new AlertDialog.Builder(this).setTitle("GARAGE  ·  "+hill.totalCoins()+" coins  ·  "+
            hill.totalDiamonds()+" diamonds")
            .setItems(choices,(dialog,index)->{
                if(index<vehicles){
                    if(!hill.selectOrBuyVehicle(index))Toast.makeText(this,"Not enough coins to unlock that ride.",Toast.LENGTH_SHORT).show();
                    else Toast.makeText(this,hill.vehicleName()+" ready to roll!",Toast.LENGTH_SHORT).show();
                    showGarage();return;
                }
                String[] upgrades={"engine","suspension","tyres","fuel","fourWheelDrive"};
                if(index>=vehicles&&index<vehicles+5){
                    String upgrade=upgrades[index-vehicles];
                    if(!hill.buyUpgrade(upgrade))Toast.makeText(this,"Not enough coins, or this upgrade is already maxed.",Toast.LENGTH_SHORT).show();
                    else Toast.makeText(this,"Upgrade installed!",Toast.LENGTH_SHORT).show();
                    showGarage();return;
                }
                if(index==vehicles+5){
                    if(!hill.exchangeDiamondForCoins())Toast.makeText(this,"You need at least one diamond to trade.",Toast.LENGTH_SHORT).show();
                    else Toast.makeText(this,"Diamond traded for 80 coins.",Toast.LENGTH_SHORT).show();
                    showGarage();return;
                }
                returnFromGarage();
            }).setOnCancelListener(dialog->returnFromGarage()).show();
    }
    private void showRunOver(){
        if(isFinishing()||game==null||game.hillClimb==null||!game.over)return;
        HillClimbMode hill=game.hillClimb;
        new AlertDialog.Builder(this).setTitle(hill.distance()>hill.bestDistance()-0.5f?"NEW DISTANCE!":"RUN OVER")
            .setMessage(game.message+"\n\nDistance: "+Math.round(hill.distance())+" m"+
                "\nBest: "+Math.round(hill.bestDistance())+" m"+
                "\nCoins this run: "+hill.runCoins()+"   ·   Career coins: "+hill.totalCoins())
            .setPositiveButton("Run again",(dialog,which)->{runOverDialogShown=false;game.restart(null);})
            .setNeutralButton("Garage",(dialog,which)->showGarage())
            .setNegativeButton("Exit",(dialog,which)->finish()).setCancelable(false).show();
    }
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
    @Override public void onBackPressed(){
        if(game==null){super.onBackPressed();return;}
        if(game.hillClimb!=null)showHillMenu();else game.setPaused(!game.paused);
    }
    @Override protected void onDestroy(){if(assets!=null)assets.stopAudio();super.onDestroy();}
}
