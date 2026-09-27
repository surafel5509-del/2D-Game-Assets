package com.world2d.engine.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.Examples;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.data.ProjectStore;
import com.world2d.engine.export.AndroidGameExporter;
import com.world2d.engine.runtime.GameRuntime;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Native Android entry point. Editor screens, panels and gestures are Android Views; there is no WebView. */
public final class MainActivity extends Activity implements SceneView.Events {
    private static final int OPEN_PROJECT=101,OPEN_ASSET=102,SAVE_PROJECT=103,EXPORT_ANDROID=104,OPEN_SHEET=105;
    private static final int PANEL_BODY_ID=0x302d0101;
    ProjectStore store;
    AssetLibrary library;
    GameProject project;
    JSONObject scene;
    SceneView viewport;
    FrameLayout root;
    private FrameLayout viewLayer;
    private LinearLayout editorShell;
    private Dialog panelDialog;
    private String panelName="",selectedId,assetSearch="",assetCategory="All",pendingExport="";
    private boolean editor=false,fullscreen=false,proMode=true,dirty=false,snap=false;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<GameProject> past=new ArrayDeque<>(),future=new ArrayDeque<>();
    private final List<String> editorLogs=new ArrayList<>();
    private final Runnable autosave=new Runnable(){@Override public void run(){
        if(editor&&project!=null&&dirty)save(false);
        handler.postDelayed(this,30000);
    }};
    private final Runnable recovery=new Runnable(){@Override public void run(){
        if(editor&&project!=null&&dirty)try{store.draft(project);}catch(Exception ex){log("Recovery write failed: "+ex.getMessage());}
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        store=new ProjectStore(this);
        try{library=new AssetLibrary(this,store);}catch(Exception ex){
            new AlertDialog.Builder(this).setTitle("Asset library unavailable")
                .setMessage(ex.getMessage()).setPositiveButton("Close",(d,w)->finish()).show();return;
        }
        proMode=getPreferences(0).getBoolean("pro-mode",true);
        handler.postDelayed(autosave,30000);
        String last=state!=null?state.getString("project"):getPreferences(0).getString("active-project",null);
        if(last!=null&&state!=null){try{openEditor(store.load(last),state.getString("scene"));}catch(Exception ex){showHome();}}
        else showHome();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        if(editor&&project!=null){out.putString("project",project.id());out.putString("scene",scene.optString("id"));}
        super.onSaveInstanceState(out);
    }
    @Override protected void onPause(){super.onPause();if(editor&&project!=null&&dirty)save(false);
        if(viewport!=null)viewport.setAlive(false);}
    @Override protected void onResume(){super.onResume();if(viewport!=null)viewport.setAlive(true);}
    @Override protected void onDestroy(){handler.removeCallbacks(autosave);handler.removeCallbacks(recovery);
        if(library!=null)library.stopAudio();super.onDestroy();}
    private void setRoot(){
        root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);setContentView(root);
        if(android.os.Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets system=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            if(fullscreen)view.setPadding(0,0,0,0);
            else view.setPadding(system.left,system.top,system.right,system.bottom);
            return insets;
        });
    }
    private void log(String line){editorLogs.add(0,line);while(editorLogs.size()>100)editorLogs.remove(editorLogs.size()-1);}
    public void notify(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();log(message);}
    public JSONObject selectedNode(){return scene==null?null:GameProject.node(scene,selectedId);}
    public String selectedId(){return selectedId;}
    public boolean isPro(){return proMode;}
    public List<String> editorLogs(){return editorLogs;}
    public String assetCategory(){return assetCategory;}
    public String assetSearch(){return assetSearch;}
    public void assetFilter(String category,String search){assetCategory=category;assetSearch=search;refreshPanel();}
    public void remember(){if(project==null)return;past.push(project.snapshot());while(past.size()>40)past.removeLast();future.clear();}
    public void edit(Runnable change){remember();change.run();changed(true);}
    private void changed(boolean refresh){
        if(project==null)return;project.touch();dirty=true;
        handler.removeCallbacks(recovery);handler.postDelayed(recovery,1400);
        if(viewport!=null)viewport.invalidate();
        if(refresh)refreshPanel();
    }
    private void save(boolean backup){
        if(project==null)return;
        try{store.save(project,backup);dirty=false;handler.removeCallbacks(recovery);
            if(backup)notify("Saved · snapshot created");}
        catch(Exception ex){notify("Save failed: "+ex.getMessage());}
    }
    private void undo(){if(past.isEmpty())return;future.push(project.snapshot());
        String id=scene.optString("id");project=past.pop();scene=project.scene(id);
        if(scene==null)scene=project.firstScene();viewport.setProject(project,scene);viewport.setSelected(selectedId);
        changed(true);}
    private void redo(){if(future.isEmpty())return;past.push(project.snapshot());
        String id=scene.optString("id");project=future.pop();scene=project.scene(id);
        if(scene==null)scene=project.firstScene();viewport.setProject(project,scene);viewport.setSelected(selectedId);
        changed(true);}
    private void showHome(){
        if(panelDialog!=null){panelDialog.dismiss();panelDialog=null;}
        if(viewport!=null){viewport.setRuntime(null);viewport.setAlive(false);}
        editor=false;fullscreen=false;project=null;scene=null;viewport=null;selectedId=null;
        getPreferences(0).edit().remove("active-project").apply();
        setRoot();
        LinearLayout page=Ui.vertical(this);root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=Ui.horizontal(this);Ui.pad(header,this,20,13,18,12);header.setBackgroundColor(Ui.BG);
        TextView mark=Ui.text(this,"◇",29,Ui.GREEN,true);Ui.add(header,mark,36,48);
        LinearLayout branding=Ui.vertical(this);
        Ui.add(branding,Ui.text(this,"2D WORLD",17,Ui.TEXT,true),-1,-2);
        Ui.add(branding,Ui.text(this,"E N G I N E",9,Ui.GREEN,true),-1,-2);
        header.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        TextView offline=Ui.text(this,"●  OFFLINE",10,Ui.GREEN,true);Ui.add(header,offline,-2,48);
        Ui.add(page,header,-1,-2);Ui.add(page,Ui.divider(this),-1,1);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.vertical(this);Ui.pad(body,this,20,28,20,30);scroll.addView(body);
        Ui.add(body,Ui.text(this,"YOUR WORKSPACE  /  OVERVIEW",10,Ui.GREEN,true),-1,24);
        TextView headline=Ui.text(this,"Build worlds.\nAnywhere.",37,Ui.TEXT,true);headline.setLineSpacing(0,1.06f);
        Ui.add(body,headline,-1,-2);
        Ui.add(body,Ui.space(this,8),1,8);
        Ui.add(body,Ui.text(this,"A real 2D editor, in your pocket. Create scenes, bring assets to life and press play.",14,Ui.MUTED,false),-1,-2);
        Ui.add(body,Ui.space(this,22),1,22);
        LinearLayout actions=Ui.horizontal(this);
        TextView create=Ui.button(this,"＋  New project",true);create.setOnClickListener(v->projectWizard());
        actions.addView(create,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
        TextView importProject=Ui.button(this,"↥  Import",false);importProject.setOnClickListener(v->pickProject());
        LinearLayout.LayoutParams importParams=new LinearLayout.LayoutParams(-2,Ui.dp(this,48));importParams.leftMargin=Ui.dp(this,10);
        actions.addView(importProject,importParams);Ui.add(body,actions,-1,-2);
        Ui.add(body,Ui.space(this,30),1,30);
        sectionTitle(body,"RECENT PROJECTS","Pick up right where you left off");
        List<GameProject> recent=store.list();
        if(recent.isEmpty()){
            LinearLayout empty=Ui.card(this);Ui.add(empty,Ui.text(this,"Your next world starts here",17,Ui.TEXT,true),-1,35);
            Ui.add(empty,Ui.text(this,"Create a project or open an example to explore a complete game.",13,Ui.MUTED,false),-1,-2);
            Ui.add(body,empty,-1,-2);
        }else for(int i=0;i<Math.min(4,recent.size());i++){
            GameProject p=recent.get(i);Ui.add(body,projectCard(p),-1,-2);Ui.add(body,Ui.space(this,9),1,9);
        }
        Ui.add(body,Ui.space(this,25),1,25);
        sectionTitle(body,"LEARN BY PLAYING","Five complete, editable example games");
        for(int i=0;i<Examples.GENRES.length;i++){
            final String genre=Examples.GENRES[i];
            GameProject sample=Examples.create(genre,library);
            LinearLayout card=Ui.card(this);card.setPadding(0,0,0,0);
            SceneView cover=new SceneView(this,library);cover.setProject(sample,
                genre.equals("adventure")?J.at(sample.scenes(),1):sample.firstScene());cover.setThumbnail(true);
            Ui.add(card,cover,-1,120);
            LinearLayout details=Ui.vertical(this);Ui.pad(details,this,14,12,14,13);
            Ui.add(details,Ui.text(this,String.format("0%d / 05    •    %s",i+1,genre.toUpperCase()),10,Ui.GREEN,true),-1,22);
            Ui.add(details,Ui.text(this,Examples.NAMES[i],18,Ui.TEXT,true),-1,32);
            Ui.add(details,Ui.text(this,Examples.DESCRIPTIONS[i],12,Ui.MUTED,false),-1,-2);
            Ui.add(details,Ui.space(this,9),1,9);
            LinearLayout buttons=Ui.horizontal(this);
            TextView explore=Ui.button(this,"Explore project  →",false);
            explore.setOnClickListener(v->openExample(genre,false));
            buttons.addView(explore,new LinearLayout.LayoutParams(0,Ui.dp(this,46),1));
            TextView play=Ui.button(this,"▶  Play",true);play.setOnClickListener(v->openExample(genre,true));
            LinearLayout.LayoutParams playParams=new LinearLayout.LayoutParams(-2,Ui.dp(this,46));playParams.leftMargin=Ui.dp(this,8);
            buttons.addView(play,playParams);Ui.add(details,buttons,-1,-2);Ui.add(card,details,-1,-2);
            Ui.add(body,card,-1,-2);Ui.add(body,Ui.space(this,10),1,10);
        }
        Ui.add(body,Ui.space(this,22),1,22);
        LinearLayout libraryCard=Ui.card(this);
        Ui.add(libraryCard,Ui.text(this,"623 ready-to-use resources",18,Ui.TEXT,true),-1,36);
        Ui.add(libraryCard,Ui.text(this,"Original CC0 art and audio, plus Kenney Pixel Platformer. Every preview is the real resource.",13,Ui.MUTED,false),-1,-2);
        Ui.add(libraryCard,Ui.space(this,12),1,12);
        TextView browse=Ui.button(this,"Browse library  →",false);browse.setOnClickListener(v->showStandaloneLibrary());
        Ui.add(libraryCard,browse,-1,46);Ui.add(body,libraryCard,-1,-2);
        Ui.add(body,Ui.space(this,25),1,25);
        Ui.add(body,Ui.text(this,"2D WORLD Engine  ·  Native Android  ·  Works offline",11,Ui.MUTED,false),-1,32);
        LinearLayout nav=Ui.horizontal(this);nav.setBackgroundColor(Ui.SURFACE);Ui.pad(nav,this,12,7,12,8);
        String[] names={"⌂  Home","◈  Assets","☰  Guides","⚙  Settings"};
        for(int i=0;i<names.length;i++){
            final int tab=i;TextView item=Ui.subtleButton(this,names[i]);item.setTextSize(11);
            item.setOnClickListener(v->{if(tab==1)showStandaloneLibrary();else if(tab==2)Panels.docs(this);
                else if(tab==3)globalSettings();else showHome();});
            nav.addView(item,new LinearLayout.LayoutParams(0,Ui.dp(this,49),1));
        }Ui.add(page,nav,-1,-2);
    }
    private void sectionTitle(LinearLayout body,String eyebrow,String subtitle){
        Ui.add(body,Ui.text(this,eyebrow,12,Ui.GREEN,true),-1,27);
        Ui.add(body,Ui.text(this,subtitle,13,Ui.MUTED,false),-1,30);
    }
    private LinearLayout projectCard(GameProject p){
        LinearLayout card=Ui.card(this);
        LinearLayout row=Ui.horizontal(this);
        LinearLayout words=Ui.vertical(this);
        Ui.add(words,Ui.text(this,p.name(),17,Ui.TEXT,true),-1,29);
        Ui.add(words,Ui.text(this,Ui.titlecase(p.data.optString("genre"))+"  •  "+p.scenes().length()+" scene(s)  •  v"+p.data.optString("version"),11,Ui.MUTED,false),-1,24);
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView more=Ui.subtleButton(this,"⋮");more.setTextSize(22);more.setOnClickListener(v->projectActions(p));
        Ui.add(row,more,42,45);Ui.add(card,row,-1,-2);
        SceneView thumbnail=new SceneView(this,library);thumbnail.setProject(p,p.firstScene());thumbnail.setThumbnail(true);
        Ui.add(card,thumbnail,-1,116);Ui.add(card,Ui.space(this,11),1,11);
        LinearLayout buttons=Ui.horizontal(this);
        TextView open=Ui.button(this,"Open editor  →",true);open.setOnClickListener(v->loadProject(p.id(),false));
        buttons.addView(open,new LinearLayout.LayoutParams(0,Ui.dp(this,46),1));
        TextView play=Ui.button(this,"▶  Play",false);play.setOnClickListener(v->loadProject(p.id(),true));
        LinearLayout.LayoutParams playParams=new LinearLayout.LayoutParams(-2,Ui.dp(this,46));playParams.leftMargin=Ui.dp(this,8);
        buttons.addView(play,playParams);Ui.add(card,buttons,-1,-2);return card;
    }
    private void openExample(String genre,boolean play){
        GameProject p=Examples.create(genre,library);
        try{store.save(p,false);openEditor(p,null);if(play)startPreview(true);}catch(Exception ex){notify("Could not open example: "+ex.getMessage());}
    }
    private void loadProject(String id,boolean play){
        try{
            if(store.hasRecovery(id)){
                new AlertDialog.Builder(this).setTitle("Recovered project found")
                    .setMessage("Unsaved changes were found after the last save. Restore the recovery copy, discard it, or compare the dates?")
                    .setPositiveButton("Restore",(d,w)->{try{openEditor(store.recover(id),null);if(play)startPreview(true);}catch(Exception ex){notify(ex.getMessage());}})
                    .setNegativeButton("Discard",(d,w)->{store.discardRecovery(id);loadProject(id,play);})
                    .setNeutralButton("Compare",(d,w)->{
                        File saved=new File(store.folder(id),"project.json"),draft=new File(store.folder(id),"recovery.json");
                        new AlertDialog.Builder(this).setTitle("Compare versions")
                            .setMessage("Saved: "+new java.util.Date(saved.lastModified())+"\nRecovery: "+new java.util.Date(draft.lastModified())+"\nThe recovered draft is newer. Your saved copy is preserved until you choose Restore.")
                            .setPositiveButton("Choose",(dialog,which)->loadProject(id,play)).show();
                    }).show();return;
            }
            openEditor(store.load(id),null);if(play)startPreview(true);
        }catch(Exception ex){notify("Cannot open project: "+ex.getMessage());}
    }
    private void openEditor(GameProject value,String sceneId){
        project=value;scene=sceneId==null?project.firstScene():project.scene(sceneId);
        if(scene==null)scene=project.firstScene();
        selectedId=null;editor=true;fullscreen=false;dirty=false;past.clear();future.clear();
        getPreferences(0).edit().putString("active-project",project.id()).apply();
        renderEditor();
    }
    private void renderEditor(){
        setRoot();editorShell=Ui.vertical(this);root.addView(editorShell,new FrameLayout.LayoutParams(-1,-1));
        HorizontalScrollView topScroll=new HorizontalScrollView(this);topScroll.setHorizontalScrollBarEnabled(false);
        topScroll.setBackgroundColor(Ui.SURFACE);LinearLayout toolbar=Ui.horizontal(this);Ui.pad(toolbar,this,9,5,9,5);
        addTool(toolbar,"⌂", "Projects", v->{if(project!=null&&dirty)save(false);showHome();});
        TextView title=Ui.text(this,project.name(),15,Ui.TEXT,true);title.setMaxWidth(Ui.dp(this,170));
        Ui.pad(title,this,10,0,10,0);Ui.add(toolbar,title,-2,48);
        addTool(toolbar,"↧ Save","Save project",v->save(true));
        addTool(toolbar,"↶","Undo",v->undo());addTool(toolbar,"↷","Redo",v->redo());
        addTool(toolbar,"▶ Play","Play scene",v->startPreview(false));
        addTool(toolbar,"▣ Build","Export game",v->openPanel("Build"));
        addTool(toolbar,"⌕","Search",v->globalSearch());
        addTool(toolbar,"⋮","Project menu",v->editorMenu());
        topScroll.addView(toolbar);Ui.add(editorShell,topScroll,-1,58);
        HorizontalScrollView tabsScroll=new HorizontalScrollView(this);tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=Ui.horizontal(this);tabs.setBackgroundColor(Ui.BG);
        for(int i=0;i<project.scenes().length();i++){
            JSONObject target=J.at(project.scenes(),i);
            TextView tab=Ui.subtleButton(this,(scene.optString("id").equals(target.optString("id"))?"●  ":"◇  ")+target.optString("name"));
            tab.setTextColor(scene.optString("id").equals(target.optString("id"))?Ui.GREEN:Ui.MUTED);
            tab.setOnClickListener(v->switchScene(target.optString("id")));
            tab.setOnLongClickListener(v->{sceneActions(target);return true;});Ui.add(tabs,tab,-2,46);
        }
        TextView add=Ui.subtleButton(this,"＋ Scene");add.setOnClickListener(v->addScene());Ui.add(tabs,add,-2,46);
        tabsScroll.addView(tabs);Ui.add(editorShell,tabsScroll,-1,48);
        View line=Ui.divider(this);Ui.add(editorShell,line,-1,1);
        viewLayer=new FrameLayout(this);editorShell.addView(viewLayer,new LinearLayout.LayoutParams(-1,0,1));
        viewport=new SceneView(this,library);viewport.setProject(project,scene);viewport.setSelected(selectedId);
        viewport.setEvents(this);viewport.setSnap(snap);
        viewLayer.addView(viewport,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout tools=Ui.vertical(this);Ui.pad(tools,this,8,46,0,0);
        String[] names={"V","G","R","S","H","B","E"};
        SceneView.Tool[] types=SceneView.Tool.values();
        for(int i=0;i<names.length;i++){
            final SceneView.Tool type=types[i];
            TextView item=Ui.subtleButton(this,names[i]);item.setTextSize(14);
            item.setContentDescription(type.name()+" tool");
            item.setOnClickListener(v->{viewport.setTool(type);notify(type.name().toLowerCase()+" tool");});
            Ui.add(tools,item,43,42);
        }
        FrameLayout.LayoutParams toolParams=new FrameLayout.LayoutParams(-2,-2,Gravity.START|Gravity.TOP);
        viewLayer.addView(tools,toolParams);
        LinearLayout toggles=Ui.horizontal(this);
        TextView grid=Ui.subtleButton(this,"▦ Grid");grid.setOnClickListener(v->{viewport.setGrid(!viewport.grid());});
        TextView snapButton=Ui.subtleButton(this,snap?"◆ Snap":"◇ Snap");snapButton.setOnClickListener(v->{snap=!snap;viewport.setSnap(snap);snapButton.setText(snap?"◆ Snap":"◇ Snap");});
        Ui.add(toggles,grid,-2,43);Ui.add(toggles,snapButton,-2,43);
        FrameLayout.LayoutParams toggleParams=new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.TOP);
        toggleParams.setMargins(0,Ui.dp(this,46),Ui.dp(this,7),0);viewLayer.addView(toggles,toggleParams);
        HorizontalScrollView bottomScroll=new HorizontalScrollView(this);bottomScroll.setHorizontalScrollBarEnabled(false);
        bottomScroll.setBackgroundColor(Ui.SURFACE);LinearLayout bottom=Ui.horizontal(this);Ui.pad(bottom,this,7,5,7,5);
        String[] panels=proMode?new String[]{"Hierarchy","Assets","Inspector","Animation","Scripts","TileMap","Input","Console","Build"}:
            new String[]{"Hierarchy","Assets","Inspector","Console"};
        for(String p:panels){TextView tab=Ui.subtleButton(this,p);tab.setTextSize(12);tab.setOnClickListener(v->openPanel(p));Ui.add(bottom,tab,-2,48);}
        bottomScroll.addView(bottom);Ui.add(editorShell,bottomScroll,-1,58);
    }
    private void addTool(LinearLayout bar,String label,String description,View.OnClickListener click){
        TextView button=Ui.subtleButton(this,label);button.setContentDescription(description);
        button.setOnClickListener(click);Ui.add(bar,button,-2,47);
    }
    private void switchScene(String id){if(viewport.runtime()!=null)stopPreview();
        if(dirty)save(false);scene=project.scene(id);if(scene==null)return;
        selectedId=null;renderEditor();}
    private void addScene(){
        EditText name=Ui.field(this,"Scene name","New Scene",false);
        new AlertDialog.Builder(this).setTitle("Create scene").setView(name)
            .setPositiveButton("Create",(d,w)->{String value=name.getText().toString().trim();if(value.isEmpty())value="New Scene";
                String finalName=value;edit(()->{JSONObject item=GameProject.newScene(finalName,project.data.optInt("width",960),
                    project.data.optInt("height",540),"#243b50");project.scenes().put(item);scene=item;});renderEditor();})
            .setNegativeButton("Cancel",null).show();
    }
    private void sceneActions(JSONObject target){String[] items={"Rename scene","Duplicate scene","Set as starting scene","Delete scene"};
        new AlertDialog.Builder(this).setTitle(target.optString("name"))
            .setItems(items,(d,index)->{
                if(index==0){EditText input=Ui.field(this,"Name",target.optString("name"),false);
                    new AlertDialog.Builder(this).setTitle("Rename scene").setView(input)
                        .setPositiveButton("Save",(x,y)->edit(()->J.put(target,"name",input.getText().toString().trim()))).show();}
                else if(index==1){edit(()->{JSONObject copy=J.copy(target);J.put(copy,"id",J.id("scene"));
                    J.put(copy,"name",target.optString("name")+" copy");project.scenes().put(copy);});renderEditor();}
                else if(index==2){edit(()->J.put(project.data,"startSceneId",target.optString("id")));notify("Starting scene set");}
                else if(project.scenes().length()>1)new AlertDialog.Builder(this).setTitle("Delete scene?")
                    .setMessage("This removes all objects in "+target.optString("name")+". A snapshot is saved first.")
                    .setPositiveButton("Delete",(x,y)->{save(true);edit(()->{
                        for(int i=0;i<project.scenes().length();i++)if(J.at(project.scenes(),i).optString("id").equals(target.optString("id"))){project.scenes().remove(i);break;}
                        if(project.data.optString("startSceneId").equals(target.optString("id")))
                            J.put(project.data,"startSceneId",J.at(project.scenes(),0).optString("id"));
                        scene=project.firstScene();});renderEditor();})
                    .setNegativeButton("Cancel",null).show();
                else notify("Keep at least one scene.");
            }).show();}
    private void projectActions(GameProject p){
        String[] actions={"Open","Duplicate","Rename","Export .2dw","Backups","Delete"};
        new AlertDialog.Builder(this).setTitle(p.name()).setItems(actions,(d,index)->{
            switch(index){
                case 0:loadProject(p.id(),false);break;
                case 1:try{store.duplicate(p);showHome();}catch(Exception ex){notify(ex.getMessage());}break;
                case 2:{EditText field=Ui.field(this,"Project name",p.name(),false);
                    new AlertDialog.Builder(this).setTitle("Rename project").setView(field)
                        .setPositiveButton("Rename",(x,y)->{J.put(p.data,"name",field.getText().toString().trim());
                            try{store.save(p,true);showHome();}catch(Exception ex){notify(ex.getMessage());}}).show();break;}
                case 3:project=p;requestExport("project");break;
                case 4:showBackups(p);break;
                case 5:new AlertDialog.Builder(this).setTitle("Delete "+p.name()+"?")
                    .setMessage("This permanently deletes the project and all its local files. Export a backup first.")
                    .setPositiveButton("Delete",(x,y)->{try{store.delete(p.id());showHome();}catch(Exception ex){notify(ex.getMessage());}})
                    .setNegativeButton("Cancel",null).show();break;
            }
        }).show();
    }
    private void showBackups(GameProject p){List<File> snapshots=store.snapshots(p.id());
        if(snapshots.isEmpty()){notify("No snapshots yet. Use Save in the editor to create one.");return;}
        String[] names=new String[snapshots.size()];for(int i=0;i<names.length;i++)names[i]=new java.util.Date(snapshots.get(i).lastModified()).toString();
        new AlertDialog.Builder(this).setTitle("Version snapshots").setItems(names,(d,index)->
            new AlertDialog.Builder(this).setTitle("Restore version?")
                .setMessage(names[index]+"\nYour current project is backed up before restoring.")
                .setPositiveButton("Restore",(x,y)->{try{GameProject value=store.restoreSnapshot(p.id(),snapshots.get(index));
                    openEditor(value,null);}catch(Exception ex){notify(ex.getMessage());}})
                .setNegativeButton("Cancel",null).show()).show();
    }
    private void editorMenu(){String[] items={"Create object","Project settings","Example projects","Backups","Documentation","Beginner / Pro mode","Return to projects"};
        new AlertDialog.Builder(this).setTitle("Project tools").setItems(items,(d,index)->{
            switch(index){case 0:addObjectMenu();break;case 1:projectSettings();break;case 2:save(false);showHome();break;
                case 3:showBackups(project);break;case 4:Panels.docs(this);break;
                case 5:proMode=!proMode;getPreferences(0).edit().putBoolean("pro-mode",proMode).apply();renderEditor();break;
                case 6:if(dirty)save(false);showHome();break;}
        }).show();
    }
    private void projectWizard(){
        LinearLayout form=Ui.vertical(this);Ui.pad(form,this,22,18,22,10);
        TextView info=Ui.text(this,"Choose a template and make it yours. Every project stays on this device until you export it.",13,Ui.MUTED,false);
        Ui.add(form,info,-1,-2);Ui.add(form,Ui.space(this,16),1,16);
        EditText name=Ui.field(this,"Project name","My New World",false);
        formLabel(form,"PROJECT NAME");Ui.add(form,name,-1,52);
        formLabel(form,"TEMPLATE");
        android.widget.Spinner templates=new android.widget.Spinner(this);
        String[] options={"Empty 2D","Platformer","Top-down RPG","Shooter","Racing","Adventure","Puzzle","Arcade"};
        templates.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));
        Ui.add(form,templates,-1,50);
        formLabel(form,"APPLICATION ID");
        EditText packageId=Ui.field(this,"Package ID","com.world2d.mynewworld",false);Ui.add(form,packageId,-1,52);
        formLabel(form,"RESOLUTION");
        LinearLayout resolution=Ui.horizontal(this);
        EditText width=Ui.field(this,"Width","960",true),height=Ui.field(this,"Height","540",true);
        resolution.addView(width,new LinearLayout.LayoutParams(0,Ui.dp(this,52),1));
        TextView cross=Ui.text(this,"  ×  ",15,Ui.MUTED,false);Ui.add(resolution,cross,-2,52);
        resolution.addView(height,new LinearLayout.LayoutParams(0,Ui.dp(this,52),1));Ui.add(form,resolution,-1,52);
        formLabel(form,"ORIENTATION");
        android.widget.Spinner orientation=new android.widget.Spinner(this);
        orientation.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Landscape","Portrait","Auto"}));Ui.add(form,orientation,-1,50);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create a project").setView(scroll)
            .setPositiveButton("Create",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
            String projectName=name.getText().toString().trim();String pkg=packageId.getText().toString().trim();
            if(projectName.isEmpty()){name.setError("Enter a project name");return;}
            if(!pkg.matches("[a-zA-Z_][\\w]*(\\.[a-zA-Z_][\\w]*){2,}")){packageId.setError("Example: com.example.mygame");return;}
            int w,h;try{w=Integer.parseInt(width.getText().toString());h=Integer.parseInt(height.getText().toString());}
            catch(Exception ex){notify("Enter a valid resolution.");return;}
            if(w<160||h<160||w>4096||h>4096){notify("Resolution must be between 160 and 4096.");return;}
            String[] genres={"empty","platformer","rpg","shooter","racing","adventure","puzzle","arcade"};
            String genre=genres[templates.getSelectedItemPosition()];
            GameProject created=genre.equals("empty")?GameProject.create(projectName,genre,w,h):Examples.create(genre,library);
            J.put(created.data,"name",projectName);J.put(created.data,"packageId",pkg);
            J.put(created.data,"orientation",new String[]{"landscape","portrait","auto"}[orientation.getSelectedItemPosition()]);
            if(genre.equals("empty")){J.put(created.data,"width",w);J.put(created.data,"height",h);}
            try{store.save(created,false);dialog.dismiss();openEditor(created,null);}
            catch(Exception ex){notify("Project creation failed: "+ex.getMessage());}
        }));dialog.show();
    }
    private void formLabel(LinearLayout parent,String label){Ui.add(parent,Ui.space(this,12),1,12);
        Ui.add(parent,Ui.text(this,label,10,Ui.MUTED,true),-1,24);}
    public void addObjectMenu(){String[] names={"Empty node","Sprite","Character","Animated sprite","TileMap","Particles",
        "Camera","Light","Audio source","RigidBody","StaticBody","Collision area","UI label","Timer","Prefab instance"};
        new AlertDialog.Builder(this).setTitle("Add object").setItems(names,(d,index)->{
            String name=names[index];if(index==14){Panels.prefabs(this);return;}
            edit(()->{
                JSONObject n=GameProject.newNode(name,scene.optInt("width")/2d,scene.optInt("height")/2d,64,64);
                switch(index){
                    case 1:GameProject.addComponent(n,GameProject.sprite("builtin:player-0"));break;
                    case 2:GameProject.addComponent(n,GameProject.sprite("builtin:player-0"));
                        GameProject.addComponent(n,GameProject.body("character",1));
                        GameProject.addComponent(n,GameProject.collider(38,58,false));
                        GameProject.addComponent(n,GameProject.controller("platformer"));break;
                    case 3:GameProject.addComponent(n,GameProject.sprite("builtin:player-0"));
                        GameProject.addComponent(n,J.o("type","animation","animationId","","autoplay",true,"speed",1));break;
                    case 4:{J.put(J.obj(n,"transform"),"width",scene.optInt("width"));
                        J.put(J.obj(n,"transform"),"height",scene.optInt("height"));
                        GameProject.addComponent(n,J.o("type","tilemap","tileSize",48,"columns",20,"rows",12,
                            "cells",new JSONObject(),"collision",false));break;}
                    case 5:GameProject.addComponent(n,defaultParticle());break;
                    case 6:GameProject.addComponent(n,J.o("type","camera","zoom",1,"smoothing",0.12,"follow",false));break;
                    case 7:GameProject.addComponent(n,J.o("type","light","radius",160,"color","#ffe7ac","intensity",0.6));break;
                    case 8:GameProject.addComponent(n,J.o("type","audio","assetId","builtin:sfx-coin","volume",0.8,"loop",false,"autoplay",false));break;
                    case 9:GameProject.addComponent(n,GameProject.body("dynamic",1));break;
                    case 10:GameProject.addComponent(n,GameProject.body("static",0));GameProject.addComponent(n,GameProject.collider(64,64,false));break;
                    case 11:GameProject.addComponent(n,GameProject.collider(64,64,true));break;
                    case 12:GameProject.addComponent(n,J.o("type","label","text","Your text","fontSize",24,"color","#ffffff","align","center"));break;
                    case 13:GameProject.addComponent(n,J.o("type","timer","interval",2,"repeat",true,"enabled",true));break;
                }
                GameProject.nodes(scene).put(n);selectedId=n.optString("id");
            });viewport.setSelected(selectedId);notify("Added "+name);
        }).show();}
    public static JSONObject defaultParticle(){return J.o("type","particles","preset","Sparkles","rate",12,"speed",65,
        "lifetime",0.8,"spread",360,"gravity",-20,"size",5,"color","#afea84","emitting",true);}
    public void chooseObjectAction(String id){JSONObject node=GameProject.node(scene,id);if(node==null)return;
        String[] actions={"Inspect","Rename","Duplicate","Reparent","Split into parts","Save as prefab","Toggle visibility","Toggle lock","Delete"};
        new AlertDialog.Builder(this).setTitle(node.optString("name")).setItems(actions,(d,index)->{
            switch(index){case 0:selectNode(id);openPanel("Inspector");break;
                case 1:{EditText name=Ui.field(this,"Name",node.optString("name"),false);
                    new AlertDialog.Builder(this).setTitle("Rename object").setView(name)
                        .setPositiveButton("Save",(x,y)->edit(()->J.put(node,"name",name.getText().toString().trim())))
                        .setNegativeButton("Cancel",null).show();break;}
                case 2:edit(()->{JSONObject copy=GameProject.duplicate(scene,id);if(copy!=null)selectedId=copy.optString("id");});
                    viewport.setSelected(selectedId);break;
                case 3:reparent(node);break;
                case 4:splitSelected();break;
                case 5:savePrefab(node);break;
                case 6:edit(()->J.put(node,"visible",!node.optBoolean("visible",true)));break;
                case 7:edit(()->J.put(node,"locked",!node.optBoolean("locked")));break;
                case 8:new AlertDialog.Builder(this).setTitle("Delete "+node.optString("name")+"?")
                    .setMessage("Child objects will also be removed. Undo is available.")
                    .setPositiveButton("Delete",(x,y)->edit(()->{GameProject.removeNode(scene,id);
                        selectedId=null;viewport.setSelected(null);})).setNegativeButton("Cancel",null).show();break;}
        }).show();}
    public void selectNode(String id){selectedId=id;if(viewport!=null)viewport.setSelected(id);refreshPanel();}
    private void reparent(JSONObject node){List<JSONObject> options=new ArrayList<>();
        JSONArray nodes=GameProject.nodes(scene);
        List<JSONObject> descendants=GameProject.subtree(scene,node.optString("id"));
        for(int i=0;i<nodes.length();i++){JSONObject candidate=J.at(nodes,i);
            if(!descendants.contains(candidate))options.add(candidate);}
        String[] labels=new String[options.size()+1];labels[0]="Scene root";
        for(int i=0;i<options.size();i++)labels[i+1]=options.get(i).optString("name");
        new AlertDialog.Builder(this).setTitle("Parent for "+node.optString("name"))
            .setItems(labels,(d,index)->{double wx=worldCoordinate(node,true),wy=worldCoordinate(node,false);
                edit(()->{JSONObject parent=index==0?null:options.get(index-1);
                    J.put(node,"parentId",parent==null?JSONObject.NULL:parent.optString("id"));
                    JSONObject t=J.obj(node,"transform");
                    J.put(t,"x",wx-(parent==null?0:worldCoordinate(parent,true)));
                    J.put(t,"y",wy-(parent==null?0:worldCoordinate(parent,false)));
                });}).show();
    }
    private double worldCoordinate(JSONObject node,boolean x){
        if(node==null)return 0;
        double amount=J.obj(node,"transform").optDouble(x?"x":"y");
        JSONObject parent=GameProject.node(scene,node.optString("parentId"));
        return amount+(parent==null?0:worldCoordinate(parent,x));
    }
    public void savePrefab(JSONObject node){
        List<JSONObject> members=GameProject.subtree(scene,node.optString("id"));
        JSONArray copies=new JSONArray();for(JSONObject member:members)copies.put(J.copy(member));
        edit(()->{JSONObject prefab=J.o("id",J.id("prefab"),"name",node.optString("name"),"nodes",copies);
            project.prefabs().put(prefab);J.put(node,"prefabId",prefab.optString("id"));});notify("Saved reusable prefab");}
    public void placeAsset(String id,float x,float y,boolean split){
        AssetLibrary.Entry asset=library.get(project,id);
        if(asset==null){notify("Missing asset. Locate or replace its reference.");return;}
        edit(()->{
            JSONObject root=GameProject.newNode(asset.name,x,y,Math.max(24,asset.width),Math.max(24,asset.height));
            GameProject.nodes(scene).put(root);
            if(asset.kind.equals("audio"))GameProject.addComponent(root,J.o("type","audio","assetId",id,
                "volume",0.8,"loop",false,"autoplay",false));
            else if(split&&asset.parts!=null&&asset.parts.length()>0)placeParts(root,asset);
            else GameProject.addComponent(root,GameProject.sprite(id));
            selectedId=root.optString("id");
        });viewport.setSelected(selectedId);notify(asset.name+" placed");
    }
    private void placeParts(JSONObject parent,AssetLibrary.Entry asset){
        for(int i=0;i<asset.parts.length();i++){
            JSONObject part=asset.parts.optJSONObject(i);if(part==null)continue;
            JSONObject n=GameProject.newNode(part.optString("name"),part.optDouble("x"),part.optDouble("y"),
                part.optDouble("width",24),part.optDouble("height",24));
            J.put(n,"parentId",parent.optString("id"));
            GameProject.addComponent(n,GameProject.sprite("part:"+asset.id+":"+i));
            GameProject.nodes(scene).put(n);
        }
    }
    public void splitSelected(){JSONObject root=selectedNode();if(root==null){notify("Select an object first.");return;}
        JSONObject sprite=GameProject.component(root,"sprite");
        AssetLibrary.Entry asset=sprite==null?null:library.get(project,sprite.optString("assetId"));
        if(asset==null||asset.parts==null||asset.parts.length()==0){
            notify("This image has no semantic parts. Use Sprite Sheet to slice raster art.");return;
        }
        edit(()->{
            JSONArray components=J.arr(root,"components");
            for(int i=components.length()-1;i>=0;i--)if("sprite".equals(J.at(components,i).optString("type")))components.remove(i);
            placeParts(root,asset);
        });notify("Split into "+asset.parts.length()+" independent parts");
    }
    public void selectBrush(String id){viewport.setBrushAsset(id);viewport.setTool(SceneView.Tool.BRUSH);
        notify("Brush ready. Select a TileMap, then paint in the viewport.");}
    private void openPanel(String name){if(!editor||project==null)return;
        if(panelDialog!=null){panelDialog.dismiss();panelDialog=null;}
        panelName=name;
        panelDialog=new Dialog(this);panelDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        panelDialog.getWindow();
        LinearLayout layout=Ui.vertical(this);layout.setBackground(Ui.background(this,Ui.SURFACE,18,Ui.BORDER));
        LinearLayout header=Ui.horizontal(this);Ui.pad(header,this,18,9,12,9);
        TextView heading=Ui.text(this,name.toUpperCase(),13,Ui.GREEN,true);
        header.addView(heading,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));
        TextView close=Ui.subtleButton(this,"✕");close.setOnClickListener(v->panelDialog.dismiss());Ui.add(header,close,45,43);
        Ui.add(layout,header,-1,-2);Ui.add(layout,Ui.divider(this),-1,1);
        ScrollView scroll=new ScrollView(this);scroll.setId(android.R.id.list);
        LinearLayout content=Ui.vertical(this);content.setId(PANEL_BODY_ID);
        Ui.pad(content,this,15,14,15,24);scroll.addView(content);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        panelDialog.setContentView(layout);panelDialog.show();
        Window window=panelDialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*0.61f));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs=window.getAttributes();attrs.dimAmount=0.25f;window.setAttributes(attrs);}
        refreshPanel();
    }
    public void refreshPanel(){if(panelDialog==null||!panelDialog.isShowing()||project==null)return;
        LinearLayout container=panelDialog.findViewById(PANEL_BODY_ID);
        if(container==null)return;container.removeAllViews();
        View panel=Panels.make(this,panelName);
        if(panel!=null)Ui.add(container,panel,-1,-2);
    }
    public void switchPanel(String name){openPanel(name);}
    public void closePanel(){if(panelDialog!=null)panelDialog.dismiss();}
    private void showStandaloneLibrary(){Panels.standaloneLibrary(this,library);}
    private void globalSettings(){Panels.globalSettings(this);}
    public void projectSettings(){Panels.projectSettings(this);}
    public void onAssetChosen(AssetLibrary.Entry entry){Panels.assetDetail(this,entry);}
    public void pickProject(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream","application/json"});
        startActivityForResult(intent,OPEN_PROJECT);}
    public void pickAsset(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/png","image/jpeg","image/webp","image/gif",
            "audio/wav","audio/mpeg","audio/ogg"});startActivityForResult(intent,OPEN_ASSET);}
    public void pickSheet(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");startActivityForResult(intent,OPEN_SHEET);}
    public void requestExport(String what){if(project==null){notify("Open a project first.");return;}
        if(editor&&dirty)save(false);
        pendingExport=what;
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/zip");String suffix=what.equals("android")?"-Android-source.zip":".2dw";
        save.putExtra(Intent.EXTRA_TITLE,project.name().replaceAll("[^a-zA-Z0-9 -]","")+suffix);
        startActivityForResult(save,what.equals("android")?EXPORT_ANDROID:SAVE_PROJECT);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            if(code==OPEN_PROJECT){GameProject imported;
                String name=uri.getLastPathSegment();
                if(name!=null&&name.endsWith(".json")){
                    byte[] buffer=new byte[12*1024*1024];int n;
                    try(java.io.InputStream in=getContentResolver().openInputStream(uri)){n=in.read(buffer);}
                    JSONObject json=new JSONObject(new String(buffer,0,Math.max(0,n),java.nio.charset.StandardCharsets.UTF_8));
                    GameProject.validate(json);imported=new GameProject(json);
                    J.put(imported.data,"id",J.id("project"));J.put(imported.data,"name",imported.name()+" (imported)");
                    store.save(imported,false);
                }else try(java.io.InputStream in=getContentResolver().openInputStream(uri)){imported=store.importZip(in);}
                openEditor(imported,null);notify("Project imported");
            }else if(code==OPEN_ASSET&&project!=null){
                JSONObject asset=store.importAsset(project,uri);changed(true);
                notify("Imported "+asset.optString("name"));
            }else if(code==OPEN_SHEET&&project!=null)Panels.sliceSheet(this,uri);
            else if(code==SAVE_PROJECT&&project!=null){
                try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){store.exportZip(project,out);}
                notify("Project exported");
            }else if(code==EXPORT_ANDROID&&project!=null){
                try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){
                    new AndroidGameExporter(this,store,library).write(project,out);
                }notify("Android Studio project exported. Open it on a machine with the Android SDK to compile an APK.");
            }
        }catch(Exception ex){notify("Operation failed: "+ex.getMessage());}
    }
    public void globalSearch(){if(project==null)return;
        EditText query=Ui.field(this,"Search scenes, objects, scripts and assets","",false);
        new AlertDialog.Builder(this).setTitle("Search everywhere").setView(query)
            .setPositiveButton("Search",(d,w)->{
                String term=query.getText().toString().trim().toLowerCase();if(term.isEmpty())return;
                List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
                for(int s=0;s<project.scenes().length();s++){
                    JSONObject sc=J.at(project.scenes(),s);
                    if(sc.optString("name").toLowerCase().contains(term)){
                        labels.add("Scene  /  "+sc.optString("name"));actions.add(()->switchScene(sc.optString("id")));}
                    JSONArray nodes=GameProject.nodes(sc);
                    for(int n=0;n<nodes.length();n++){JSONObject node=J.at(nodes,n);
                        if(node.optString("name").toLowerCase().contains(term)){
                            labels.add("Object  /  "+sc.optString("name")+" / "+node.optString("name"));
                            actions.add(()->{switchScene(sc.optString("id"));selectNode(node.optString("id"));openPanel("Inspector");});}
                    }
                }
                for(AssetLibrary.Entry asset:library.all(project))if(asset.name.toLowerCase().contains(term)){
                    labels.add("Asset  /  "+asset.name);actions.add(()->onAssetChosen(asset));
                    if(labels.size()>70)break;}
                for(int i=0;i<project.scripts().length();i++){
                    JSONObject script=J.at(project.scripts(),i);
                    if(script.optString("name").toLowerCase().contains(term)){
                        labels.add("Script  /  "+script.optString("name"));actions.add(()->Panels.editScript(this,script));}
                }
                if(labels.isEmpty()){notify("No matches for "+term);return;}
                new AlertDialog.Builder(this).setTitle("Results for “"+term+"”")
                    .setItems(labels.toArray(new String[0]),(dialog,index)->actions.get(index).run()).show();
            }).setNegativeButton("Cancel",null).show();
    }
    private LinearLayout previewControls;
    private void startPreview(boolean startScene){if(project==null||viewport==null)return;
        if(dirty)save(false);
        if(viewport.runtime()!=null)stopPreview();
        GameRuntime runtime=new GameRuntime(this,project,startScene?project.data.optString("startSceneId"):scene.optString("id"),library);
        runtime.onChange=()->handler.post(()->{if(viewport!=null)viewport.invalidate();});
        viewport.setRuntime(runtime);previewControls=Ui.horizontal(this);
        previewControls.setBackground(Ui.background(this,Ui.SURFACE,11,Ui.BORDER));
        addTool(previewControls,"Ⅱ","Pause or resume",v->{runtime.paused=!runtime.paused;viewport.invalidate();});
        addTool(previewControls,"↻","Restart",v->runtime.restart(startScene?null:scene.optString("id")));
        addTool(previewControls,"⚿","Collision debug",v->{runtime.collisionDebug=!runtime.collisionDebug;viewport.invalidate();});
        addTool(previewControls,"⛶","Full screen",v->toggleFullscreen());
        addTool(previewControls,"■","Stop",v->stopPreview());
        FrameLayout.LayoutParams bar=new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.TOP);
        bar.setMargins(0,Ui.dp(this,95),Ui.dp(this,12),0);viewLayer.addView(previewControls,bar);
        attachTouchControls(runtime);
        notify("Live preview running. Touch controls or physical keyboard are ready.");
    }
    private LinearLayout virtualControls;
    private void attachTouchControls(GameRuntime runtime){
        virtualControls=Ui.horizontal(this);virtualControls.setGravity(Gravity.BOTTOM|Gravity.CENTER_VERTICAL);
        Ui.pad(virtualControls,this,12,8,12,10);
        String[] names={"◀","▼","▲","▶"};String[] actions={"left","down","up","right"};
        for(int i=0;i<4;i++)touchButton(virtualControls,names[i],actions[i],runtime,false);
        View spacer=new View(this);virtualControls.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        touchButton(virtualControls,"E","interact",runtime,false);
        touchButton(virtualControls,"◎","fire",runtime,false);
        touchButton(virtualControls,"↟","jump",runtime,true);
        FrameLayout.LayoutParams controls=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        viewLayer.addView(virtualControls,controls);
    }
    private void touchButton(LinearLayout row,String label,String action,GameRuntime runtime,boolean accent){
        TextView button=Ui.button(this,label,accent);button.setTextSize(17);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(Ui.dp(this,43),Ui.dp(this,46));
        params.rightMargin=Ui.dp(this,3);row.addView(button,params);
        button.setContentDescription(action);
        button.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){runtime.setAction(action,true);return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                runtime.setAction(action,false);return true;}return true;
        });
    }
    private void stopPreview(){if(viewport==null||viewport.runtime()==null)return;
        viewport.runtime().stopped=true;viewport.setRuntime(null);
        if(previewControls!=null)viewLayer.removeView(previewControls);
        if(virtualControls!=null)viewLayer.removeView(virtualControls);
        previewControls=null;virtualControls=null;
        if(fullscreen)toggleFullscreen();viewport.invalidate();}
    private void toggleFullscreen(){fullscreen=!fullscreen;
        int flags=fullscreen?(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN):0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if(editorShell!=null)for(int i=0;i<editorShell.getChildCount();i++){
            View child=editorShell.getChildAt(i);if(child!=viewLayer)child.setVisibility(fullscreen?View.GONE:View.VISIBLE);
        }
        if(root!=null)root.requestApplyInsets();
        if(viewport!=null)viewport.invalidate();
    }
    @Override public void selected(String id){selectedId=id;refreshPanel();}
    @Override public void beginGesture(){remember();}
    @Override public void changed(boolean gestureFinished){changed(gestureFinished);}
    @Override public void place(String id,float x,float y){placeAsset(id,x,y,true);}
    @Override public void hint(String text){notify(text);}
    @Override public void onBackPressed(){
        if(fullscreen){toggleFullscreen();return;}
        if(panelDialog!=null&&panelDialog.isShowing()){panelDialog.dismiss();return;}
        if(viewport!=null&&viewport.runtime()!=null){stopPreview();return;}
        if(editor){if(dirty)save(false);showHome();return;}
        super.onBackPressed();
    }
    private String keyAction(int keyCode){
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_A)return "left";
        if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT||keyCode==KeyEvent.KEYCODE_D)return "right";
        if(keyCode==KeyEvent.KEYCODE_DPAD_UP||keyCode==KeyEvent.KEYCODE_W)return "up";
        if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN||keyCode==KeyEvent.KEYCODE_S)return "down";
        if(keyCode==KeyEvent.KEYCODE_SPACE)return "jump";
        if(keyCode==KeyEvent.KEYCODE_J||keyCode==KeyEvent.KEYCODE_ENTER)return "fire";
        if(keyCode==KeyEvent.KEYCODE_E)return "interact";
        return "";
    }
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(editor&&event.isCtrlPressed()){
            if(keyCode==KeyEvent.KEYCODE_S){save(true);return true;}
            if(keyCode==KeyEvent.KEYCODE_Z){if(event.isShiftPressed())redo();else undo();return true;}
            if(keyCode==KeyEvent.KEYCODE_Y){redo();return true;}
            if(keyCode==KeyEvent.KEYCODE_K){globalSearch();return true;}
        }
        if(viewport!=null&&viewport.runtime()!=null){String action=keyAction(keyCode);
            if(!action.isEmpty()){viewport.runtime().setAction(action,true);return true;}
            if(keyCode==KeyEvent.KEYCODE_ESCAPE){viewport.runtime().paused=!viewport.runtime().paused;return true;}
        }
        if(editor&&keyCode==KeyEvent.KEYCODE_DEL&&selectedNode()!=null){chooseObjectAction(selectedId);return true;}
        return super.onKeyDown(keyCode,event);
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent event){
        if(viewport!=null&&viewport.runtime()!=null){String action=keyAction(keyCode);
            if(!action.isEmpty()){viewport.runtime().setAction(action,false);return true;}}
        return super.onKeyUp(keyCode,event);
    }
}
