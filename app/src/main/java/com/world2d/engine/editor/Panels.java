package com.world2d.engine.editor;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.data.ProjectStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Functional native hierarchy, resource browser, inspector, timeline, scripts and build panels. */
public final class Panels {
    private Panels() {}
    public static View make(MainActivity a,String name){
        switch(name){
            case "Hierarchy":return hierarchy(a);
            case "Assets":return assets(a,false);
            case "Inspector":return inspector(a);
            case "Animation":return animations(a);
            case "Scripts":return scripts(a);
            case "TileMap":return tilemap(a);
            case "Input":return input(a);
            case "Console":return console(a);
            case "Build":return build(a);
            default:return Ui.text(a,"Panel not found",14,Ui.MUTED,false);
        }
    }
    private static TextView label(Context c,String text){return Ui.text(c,text,11,Ui.MUTED,true);}
    private static void gap(LinearLayout parent,int dp){Ui.add(parent,Ui.space(parent.getContext(),dp),1,dp);}
    private static void title(LinearLayout parent,String heading,String subtitle){
        Ui.add(parent,Ui.text(parent.getContext(),heading,18,Ui.TEXT,true),-1,30);
        if(subtitle!=null)Ui.add(parent,Ui.text(parent.getContext(),subtitle,12,Ui.MUTED,false),-1,-2);
        gap(parent,13);
    }
    private static TextView button(LinearLayout parent,String text,boolean primary,Runnable action){
        TextView view=Ui.button(parent.getContext(),text,primary);view.setOnClickListener(v->action.run());
        Ui.add(parent,view,-1,46);return view;
    }
    private static void hint(LinearLayout parent,String text){
        TextView view=Ui.text(parent.getContext(),text,12,Ui.MUTED,false);
        Ui.pad(view,parent.getContext(),5,10,5,10);Ui.add(parent,view,-1,-2);
    }
    private static View hierarchy(MainActivity a){
        LinearLayout body=Ui.vertical(a);
        title(body,"Scene hierarchy",a.scene.optString("name")+" · "+GameProject.nodes(a.scene).length()+" objects");
        LinearLayout controls=Ui.horizontal(a);
        TextView create=Ui.button(a,"＋ Add object",true);create.setOnClickListener(v->a.addObjectMenu());
        controls.addView(create,new LinearLayout.LayoutParams(0,Ui.dp(a,46),1));
        TextView prefs=Ui.button(a,"◇ Prefabs",false);prefs.setOnClickListener(v->prefabs(a));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,Ui.dp(a,46));params.leftMargin=Ui.dp(a,8);controls.addView(prefs,params);
        Ui.add(body,controls,-1,-2);gap(body,10);
        EditText search=Ui.field(a,"Search objects","",false);Ui.add(body,search,-1,48);gap(body,11);
        LinearLayout tree=Ui.vertical(a);Ui.add(body,tree,-1,-2);
        Runnable populate=()->{
            tree.removeAllViews();String query=search.getText().toString().trim().toLowerCase();
            JSONArray nodes=GameProject.nodes(a.scene);
            if(nodes.length()==0){hint(tree,"This scene is empty. Add an object or place an asset.");return;}
            Set<String> seen=new HashSet<>();
            for(int i=0;i<nodes.length();i++){
                JSONObject n=J.at(nodes,i);
                String parent=n.optString("parentId");
                if(parent.isEmpty()||parent.equals("null")||GameProject.node(a.scene,parent)==null)
                    appendNode(tree,a,n,0,query,seen);
            }
            for(int i=0;i<nodes.length();i++){
                JSONObject n=J.at(nodes,i);if(!seen.contains(n.optString("id")))appendNode(tree,a,n,0,query,seen);
            }
        };populate.run();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){populate.run();}
            public void afterTextChanged(Editable e){}
        });
        gap(body,8);hint(body,"Long-press an object to rename, reparent, duplicate, split or save it as a prefab.");
        return body;
    }
    private static void appendNode(LinearLayout tree,MainActivity a,JSONObject n,int depth,String query,Set<String> seen){
        String id=n.optString("id");if(depth>30||!seen.add(id))return;
        boolean matches=query.isEmpty()||n.optString("name").toLowerCase().contains(query);
        if(matches){
            LinearLayout row=Ui.horizontal(a);Ui.pad(row,a,Math.min(65,8+depth*16),3,3,3);
            if(id.equals(a.selectedId()))row.setBackground(Ui.background(a,Ui.DARK_GREEN,9,Ui.GREEN));
            String icon=GameProject.component(n,"tilemap")!=null?"▦":GameProject.component(n,"particles")!=null?"✳":
                GameProject.component(n,"camera")!=null?"◎":GameProject.component(n,"label")!=null?"T":
                GameProject.component(n,"sprite")!=null?"◈":"◇";
            TextView symbol=Ui.text(a,icon,17,Ui.GREEN,true);Ui.add(row,symbol,25,44);
            TextView name=Ui.text(a,n.optString("name"),13,n.optBoolean("visible",true)?Ui.TEXT:Ui.MUTED,false);
            name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name,new LinearLayout.LayoutParams(0,Ui.dp(a,44),1));
            TextView visible=Ui.subtleButton(a,n.optBoolean("visible",true)?"◉":"◌");
            visible.setOnClickListener(v->a.edit(()->J.put(n,"visible",!n.optBoolean("visible",true))));
            Ui.add(row,visible,39,43);
            TextView menu=Ui.subtleButton(a,"⋮");menu.setOnClickListener(v->a.chooseObjectAction(id));Ui.add(row,menu,38,43);
            row.setOnClickListener(v->a.selectNode(id));
            row.setOnLongClickListener(v->{a.chooseObjectAction(id);return true;});
            Ui.add(tree,row,-1,51);
        }
        JSONArray nodes=GameProject.nodes(a.scene);
        for(int i=0;i<nodes.length();i++){
            JSONObject child=J.at(nodes,i);
            if(id.equals(child.optString("parentId")))appendNode(tree,a,child,depth+1,query,seen);
        }
    }
    private static final String[] CATEGORIES={"All","Favorites","Imported","Characters","Enemies","NPCs","Animals",
        "Vehicles","Buildings","Nature","Props","Textures","Tiles","Terrain","Audio"};
    public static View assets(MainActivity a,boolean standalone){
        LinearLayout body=Ui.vertical(a);
        title(body,"Asset library",a.library.count()+" bundled resources · CC0 / original / Kenney");
        if(!standalone){LinearLayout actions=Ui.horizontal(a);
            TextView importButton=Ui.button(a,"↥ Import image / audio",true);importButton.setOnClickListener(v->a.pickAsset());
            actions.addView(importButton,new LinearLayout.LayoutParams(0,Ui.dp(a,46),1));
            TextView slice=Ui.button(a,"▦ Sheet",false);slice.setOnClickListener(v->a.pickSheet());
            LinearLayout.LayoutParams sheetParams=new LinearLayout.LayoutParams(-2,Ui.dp(a,46));sheetParams.leftMargin=Ui.dp(a,8);
            actions.addView(slice,sheetParams);Ui.add(body,actions,-1,-2);gap(body,9);
        }
        EditText query=Ui.field(a,"Search assets by name or tag",a.assetSearch(),false);Ui.add(body,query,-1,48);
        gap(body,9);
        HorizontalScrollView filters=new HorizontalScrollView(a);filters.setHorizontalScrollBarEnabled(false);
        LinearLayout strip=Ui.horizontal(a);
        final String[] chosen={a.assetCategory()};
        for(String category:CATEGORIES){TextView chip=Ui.subtleButton(a,category);
            chip.setTextColor(category.equals(chosen[0])?Ui.GREEN:Ui.MUTED);chip.setTextSize(11);
            chip.setOnClickListener(v->{chosen[0]=category;for(int j=0;j<strip.getChildCount();j++)
                ((TextView)strip.getChildAt(j)).setTextColor(strip.getChildAt(j)==chip?Ui.GREEN:Ui.MUTED);
                populateAssets(a,body.findViewWithTag("asset-grid"),category,query.getText().toString(),standalone,0);});
            Ui.add(strip,chip,-2,41);
        }filters.addView(strip);Ui.add(body,filters,-1,43);gap(body,10);
        LinearLayout grid=Ui.vertical(a);grid.setTag("asset-grid");Ui.add(body,grid,-1,-2);
        populateAssets(a,grid,chosen[0],query.getText().toString(),standalone,0);
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){populateAssets(a,grid,chosen[0],s.toString(),standalone,0);}
            public void afterTextChanged(Editable e){}
        });
        return body;
    }
    private static void populateAssets(MainActivity a,LinearLayout container,String category,String query,boolean standalone,int offset){
        if(container==null)return;container.removeAllViews();
        String needle=query.toLowerCase().trim();List<AssetLibrary.Entry> results=new ArrayList<>();
        for(AssetLibrary.Entry entry:a.library.all(a.project)){
            if(category.equals("Favorites")&&(a.project==null||!a.library.favorite(a.project,entry.id)))continue;
            if(!category.equals("All")&&!category.equals("Favorites")&&!category.equals(entry.category))continue;
            if(!needle.isEmpty()&&!entry.name.toLowerCase().contains(needle)&&!entry.id.toLowerCase().contains(needle))continue;
            results.add(entry);
        }
        Ui.add(container,Ui.text(a,results.size()+" resources",11,Ui.MUTED,false),-1,26);
        if(results.isEmpty()){hint(container,"No resources match this filter.");return;}
        GridLayout grid=new GridLayout(a);grid.setColumnCount(3);Ui.add(container,grid,-1,-2);
        int shown=Math.min(results.size(),offset+45);
        for(int i=0;i<shown;i++){
            AssetLibrary.Entry asset=results.get(i);
            LinearLayout tile=Ui.vertical(a);tile.setGravity(Gravity.CENTER);
            tile.setBackground(Ui.background(a,Ui.RAISED,10,Ui.BORDER));Ui.pad(tile,a,5,6,5,6);
            if(asset.kind.equals("image")){
                ImageView preview=new ImageView(a);Bitmap bitmap=a.library.bitmap(a.project,asset.id,104);
                if(bitmap!=null)preview.setImageBitmap(bitmap);
                preview.setScaleType(ImageView.ScaleType.FIT_CENTER);Ui.add(tile,preview,-1,73);
            }else{
                TextView preview=Ui.text(a,"♫",32,Ui.GREEN,true);preview.setGravity(Gravity.CENTER);Ui.add(tile,preview,-1,73);
            }
            TextView text=Ui.text(a,asset.name,10,Ui.TEXT,false);text.setGravity(Gravity.CENTER);
            text.setMaxLines(2);text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.add(tile,text,-1,32);
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams();
            lp.width=(a.getResources().getDisplayMetrics().widthPixels-Ui.dp(a,64))/3;
            lp.height=Ui.dp(a,118);lp.setMargins(Ui.dp(a,3),Ui.dp(a,3),Ui.dp(a,3),Ui.dp(a,3));
            grid.addView(tile,lp);tile.setOnClickListener(v->assetDetail(a,asset));
            if(!standalone)tile.setOnLongClickListener(v->{a.placeAsset(asset.id,a.scene.optInt("width")/2f,a.scene.optInt("height")/2f,
                asset.parts!=null&&asset.parts.length()>0);a.closePanel();return true;});
        }
        if(shown<results.size()){
            gap(container,10);button(container,"Load more · "+(results.size()-shown)+" remaining",false,
                ()->populateAssets(a,container,category,query,standalone,shown));
        }
        gap(container,12);
        hint(container,"Bundled resources: generated CC0 art/audio and Kenney Pixel Platformer CC0. User imports retain their own licenses.");
    }
    public static void standaloneLibrary(MainActivity a,AssetLibrary library){
        Dialog dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll=new ScrollView(a);LinearLayout page=Ui.vertical(a);Ui.pad(page,a,16,18,16,18);
        TextView close=Ui.button(a,"←  Back to projects",false);close.setOnClickListener(v->dialog.dismiss());Ui.add(page,close,-1,46);
        gap(page,10);Ui.add(page,assets(a,true),-1,-2);scroll.addView(page);dialog.setContentView(scroll);dialog.show();
        Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(-1,-1);}
    }
    public static void assetDetail(MainActivity a,AssetLibrary.Entry entry){
        LinearLayout details=Ui.vertical(a);Ui.pad(details,a,17,16,17,8);
        if(entry.kind.equals("image")){
            ImageView preview=new ImageView(a);Bitmap bitmap=a.library.bitmap(a.project,entry.id,280);
            if(bitmap!=null)preview.setImageBitmap(bitmap);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setBackground(Ui.background(a,Ui.RAISED,12,Ui.BORDER));Ui.add(details,preview,-1,150);gap(details,10);
        }
        Ui.add(details,Ui.text(a,entry.name,19,Ui.TEXT,true),-1,34);
        String meta=entry.category+"  ·  "+entry.kind.toUpperCase();
        if(entry.kind.equals("image"))meta+="  ·  "+entry.width+"×"+entry.height;
        Ui.add(details,Ui.text(a,meta,11,Ui.MUTED,false),-1,23);
        if(entry.parts!=null&&entry.parts.length()>0)
            Ui.add(details,Ui.text(a,"◈  "+entry.parts.length()+" editable parts · split-ready",11,Ui.GREEN,true),-1,25);
        if(a.project!=null){List<String> uses=a.store.assetUses(a.project,entry.id);
            Ui.add(details,Ui.text(a,"Used in "+uses.size()+" scene location(s)",11,Ui.MUTED,false),-1,24);
            if(!uses.isEmpty())Ui.add(details,Ui.text(a,uses.get(0),10,Ui.GREEN,false),-1,22);
        }
        Ui.add(details,Ui.text(a,"LICENSE  /  "+entry.license,11,Ui.MUTED,false),-1,-2);
        Ui.add(details,Ui.text(a,"SOURCE  /  "+entry.origin,11,Ui.MUTED,false),-1,-2);
        gap(details,15);
        ScrollView scroll=new ScrollView(a);scroll.addView(details);
        AlertDialog dialog=new AlertDialog.Builder(a).setView(scroll).setNegativeButton("Close",null).create();
        if(entry.kind.equals("audio")){
            button(details,"▶  Preview sound",true,()->{try{a.library.play(a.project,entry.id);}catch(Exception ex){a.notify(ex.getMessage());}});
            gap(details,7);
        }
        if(a.project!=null){
            if(entry.kind.equals("image")){
                button(details,"＋  Use in scene",true,()->{dialog.dismiss();a.closePanel();
                    a.viewport.setPlacement(entry.id);a.notify("Tap the viewport to place "+entry.name);});gap(details,7);
                if(entry.parts!=null&&entry.parts.length()>0){button(details,"◈  Split into editable parts",false,
                    ()->{dialog.dismiss();a.closePanel();a.placeAsset(entry.id,a.scene.optInt("width")/2f,a.scene.optInt("height")/2f,true);});gap(details,7);}
                if(entry.category.equals("Tiles")||entry.category.equals("Textures")){
                    button(details,"▦  Paint with this tile",false,()->{dialog.dismiss();a.closePanel();a.selectBrush(entry.id);});gap(details,7);
                }
            }else {button(details,"＋  Add audio source",true,()->{dialog.dismiss();a.placeAsset(entry.id,
                a.scene.optInt("width")/2f,a.scene.optInt("height")/2f,false);});gap(details,7);}
            button(details,a.library.favorite(a.project,entry.id)?"★  Remove from favorites":"☆  Add to favorites",false,
                ()->{a.edit(()->a.library.toggleFavorite(a.project,entry.id));dialog.dismiss();});gap(details,7);
            button(details,"⧉  Duplicate to project",false,()->{duplicateAsset(a,entry);dialog.dismiss();});gap(details,7);
            if(!entry.builtin){button(details,"✎  Rename asset",false,()->{dialog.dismiss();renameAsset(a,entry);});gap(details,7);
                button(details,"×  Delete asset",false,()->{dialog.dismiss();deleteAsset(a,entry);});gap(details,7);}
        }
        dialog.show();
        int max=(int)(a.getResources().getDisplayMetrics().heightPixels*0.78f);
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,Math.min(max,Ui.dp(a,620)));
    }
    private static void duplicateAsset(MainActivity a,AssetLibrary.Entry entry){
        if(a.project==null)return;
        try{
            String id=J.id("asset");
            String ext=entry.path.substring(entry.path.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
            if(!ext.matches("png|jpg|webp|gif|wav|mp3|ogg|m4a|aac"))throw new java.io.IOException("Unsupported source type.");
            String src="assets/"+id+"."+ext;
            File dest=a.store.assetFile(a.project,src);
            if(dest==null)throw new java.io.IOException("Invalid destination.");
            dest.getParentFile().mkdirs();
            // Copy the original bytes: no quality loss, GIF flattening, or huge bitmap decoding.
            if(entry.builtin){
                try(java.io.InputStream in=a.getAssets().open(entry.path);FileOutputStream out=new FileOutputStream(dest)){
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
            }else{
                File from=a.store.assetFile(a.project,entry.path);
                if(from==null||!from.isFile())throw new java.io.IOException("Missing source asset.");
                try(FileInputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(dest)){
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
            }
            a.edit(()->a.project.assets().put(J.o("id",id,"name",entry.name+" copy","category","Imported",
                "kind",entry.kind,"src",src,"width",entry.width,"height",entry.height,
                "tags",new JSONArray().put("project"),"license",entry.license,"origin",entry.origin)));
            a.notify("Asset duplicated into your project");
        }catch(Exception ex){a.notify("Duplicate failed: "+ex.getMessage());}
    }
    private static void renameAsset(MainActivity a,AssetLibrary.Entry entry){
        EditText name=Ui.field(a,"Asset name",entry.name,false);
        new AlertDialog.Builder(a).setTitle("Rename asset").setView(name)
            .setPositiveButton("Rename",(dialog,index)->{
                JSONArray array=a.project.assets();for(int i=0;i<array.length();i++){
                    JSONObject asset=J.at(array,i);
                    if(entry.id.equals(asset.optString("id"))){a.edit(()->J.put(asset,"name",name.getText().toString().trim()));break;}}
            }).setNegativeButton("Cancel",null).show();
    }
    private static void deleteAsset(MainActivity a,AssetLibrary.Entry entry){
        List<String> uses=a.store.assetUses(a.project,entry.id);
        for(int i=0;i<a.project.animations().length();i++){
            JSONArray frames=J.at(a.project.animations(),i).optJSONArray("frames");
            if(frames!=null)for(int f=0;f<frames.length();f++)if(entry.id.equals(J.at(frames,f).optString("assetId")))uses.add("Animation frame");
        }
        if(!uses.isEmpty()){a.notify("Cannot delete: referenced by "+uses.get(0));return;}
        new AlertDialog.Builder(a).setTitle("Delete "+entry.name+"?")
            .setMessage("The resource has no active references. This removes its project file.")
            .setPositiveButton("Delete",(d,w)->{a.edit(()->{
                JSONArray items=a.project.assets();for(int i=items.length()-1;i>=0;i--)
                    if(entry.id.equals(J.at(items,i).optString("id")))items.remove(i);
            });File file=a.store.assetFile(a.project,entry.path);if(file!=null)file.delete();a.notify("Asset deleted");})
            .setNegativeButton("Cancel",null).show();
    }
    private static View inspector(MainActivity a){
        LinearLayout body=Ui.vertical(a);JSONObject node=a.selectedNode();
        if(node==null){title(body,"Inspector","Select an object or tap a sprite in the viewport.");
            button(body,"＋ Add object",true,a::addObjectMenu);return body;}
        title(body,node.optString("name"),"Object ID  ·  "+node.optString("id").substring(0,Math.min(24,node.optString("id").length())));
        LinearLayout controls=Ui.horizontal(a);
        TextView more=Ui.button(a,"Object actions  ⋮",false);more.setOnClickListener(v->a.chooseObjectAction(node.optString("id")));
        controls.addView(more,new LinearLayout.LayoutParams(0,Ui.dp(a,46),1));
        TextView prefab=Ui.button(a,"◇  Prefab",false);prefab.setOnClickListener(v->a.savePrefab(node));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,Ui.dp(a,46));lp.leftMargin=Ui.dp(a,8);controls.addView(prefab,lp);
        Ui.add(body,controls,-1,-2);gap(body,14);
        LinearLayout transform=Ui.card(a);
        Ui.add(transform,Ui.text(a,"TRANSFORM",12,Ui.GREEN,true),-1,29);
        Map<String,EditText> values=new HashMap<>();JSONObject t=J.obj(node,"transform");
        for(String key:new String[]{"x","y","width","height","rotation","scaleX","scaleY","opacity"}){
            LinearLayout row=Ui.horizontal(a);Ui.add(row,label(a,Ui.titlecase(key)),95,47);
            EditText field=Ui.field(a,key,String.valueOf(t.optDouble(key,key.startsWith("scale")||key.equals("opacity")?1:0)),true);
            row.addView(field,new LinearLayout.LayoutParams(0,Ui.dp(a,43),1));values.put(key,field);Ui.add(transform,row,-1,48);
        }
        gap(transform,7);button(transform,"Apply transform",true,()->{
            Map<String,Double> parsed=new HashMap<>();
            try{for(String key:values.keySet())parsed.put(key,Double.parseDouble(values.get(key).getText().toString()));}
            catch(Exception ex){a.notify("Enter valid numeric transform values.");return;}
            a.edit(()->{for(String key:parsed.keySet())J.put(t,key,parsed.get(key));});
        });Ui.add(body,transform,-1,-2);gap(body,12);
        JSONArray components=J.arr(node,"components");
        for(int i=0;i<components.length();i++){
            JSONObject component=J.at(components,i);Ui.add(body,componentCard(a,node,component),-1,-2);gap(body,10);
        }
        button(body,"＋ Add component",false,()->componentMenu(a,node));
        gap(body,12);hint(body,"All changes are undoable. References are stored by stable ID; renaming assets will not break the scene.");
        return body;
    }
    private static View componentCard(MainActivity a,JSONObject node,JSONObject component){
        LinearLayout card=Ui.card(a);String type=component.optString("type");
        LinearLayout header=Ui.horizontal(a);
        TextView heading=Ui.text(a,type.toUpperCase(),11,Ui.GREEN,true);
        header.addView(heading,new LinearLayout.LayoutParams(0,Ui.dp(a,36),1));
        TextView remove=Ui.subtleButton(a,"Remove ×");remove.setTextSize(11);
        remove.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("Remove "+type+" component?")
            .setPositiveButton("Remove",(dialog,index)->a.edit(()->{
                JSONArray list=J.arr(node,"components");for(int n=list.length()-1;n>=0;n--)
                    if(list.optJSONObject(n)==component)list.remove(n);
            })).setNegativeButton("Cancel",null).show());Ui.add(header,remove,-2,38);
        Ui.add(card,header,-1,39);
        if(type.equals("sprite")||type.equals("audio")){
            String id=component.optString("assetId");AssetLibrary.Entry entry=a.library.get(a.project,id);
            if(entry!=null){LinearLayout assetRow=Ui.horizontal(a);
                if(entry.kind.equals("image")){ImageView image=new ImageView(a);Bitmap preview=a.library.bitmap(a.project,id,74);
                    if(preview!=null)image.setImageBitmap(preview);Ui.add(assetRow,image,68,64);}
                TextView name=Ui.text(a,entry.name,13,Ui.TEXT,true);
                assetRow.addView(name,new LinearLayout.LayoutParams(0,Ui.dp(a,55),1));
                Ui.add(card,assetRow,-1,-2);
            }else hint(card,"Missing asset: "+id+". Tap Replace to locate a valid resource.");
            button(card,"Choose "+(type.equals("audio")?"sound":"sprite"),false,
                ()->chooseAsset(a,component,type.equals("audio")?"audio":"image"));gap(card,6);
            if(type.equals("audio"))button(card,"▶  Preview audio",false,()->{
                try{a.library.play(a.project,component.optString("assetId"));}catch(Exception ex){a.notify(ex.getMessage());}
            });
        }
        if(type.equals("collider")){
            button(card,"Auto-fit collider to object",false,()->a.edit(()->{
                J.put(component,"width",J.obj(node,"transform").optDouble("width"));
                J.put(component,"height",J.obj(node,"transform").optDouble("height"));
            }));gap(card,6);
        }
        if(type.equals("script")){
            JSONObject script=findById(a.project.scripts(),component.optString("scriptId"));
            Ui.add(card,Ui.text(a,script==null?"No script assigned":script.optString("name"),12,Ui.MUTED,false),-1,28);
            button(card,"Choose or create script",false,()->chooseScript(a,component));gap(card,6);
            if(script!=null)button(card,"Edit script",false,()->editScript(a,script));
        }
        if(type.equals("animation")){
            JSONObject anim=findById(a.project.animations(),component.optString("animationId"));
            Ui.add(card,Ui.text(a,anim==null?"No clip assigned":anim.optString("name"),12,Ui.MUTED,false),-1,28);
            button(card,"Choose animation",false,()->chooseAnimation(a,component));gap(card,6);
            button(card,"Open timeline",false,()->a.switchPanel("Animation"));
        }
        if(type.equals("tilemap")){
            hint(card,"Tile cells: "+J.obj(component,"cells").length()+" · Select an asset in Tiles, tap Paint, then draw directly on the viewport.");
            button(card,"Open tile tools",false,()->a.switchPanel("TileMap"));gap(card,6);
        }
        if(type.equals("particles")){
            button(card,"Choose particle preset",false,()->chooseParticle(a,component));gap(card,6);
        }
        Map<String,EditText> textFields=new HashMap<>();Map<String,CheckBox> switches=new HashMap<>();
        for(java.util.Iterator<String> keys=component.keys();keys.hasNext();){
            String key=keys.next();if(key.equals("type")||key.equals("assetId")||key.equals("scriptId")||key.equals("animationId")||key.equals("cells")||key.equals("preset"))continue;
            Object value=component.opt(key);
            if(value instanceof JSONObject||value instanceof JSONArray)continue;
            LinearLayout row=Ui.horizontal(a);Ui.add(row,label(a,Ui.titlecase(key)),95,47);
            if(value instanceof Boolean){CheckBox check=new CheckBox(a);check.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
                check.setChecked((boolean)value);switches.put(key,check);Ui.add(row,check,48,43);
            }else{EditText field=Ui.field(a,key,String.valueOf(value),value instanceof Number);
                row.addView(field,new LinearLayout.LayoutParams(0,Ui.dp(a,43),1));textFields.put(key,field);}
            Ui.add(card,row,-1,48);
        }
        if(!textFields.isEmpty()||!switches.isEmpty()){
            gap(card,6);button(card,"Apply component",true,()->{
                Map<String,Object> changed=new HashMap<>();
                try{for(Map.Entry<String,EditText> e:textFields.entrySet()){
                    Object before=component.opt(e.getKey());String input=e.getValue().getText().toString();
                    changed.put(e.getKey(),before instanceof Number?Double.parseDouble(input):input);
                }}catch(Exception ex){a.notify("Enter valid numeric values.");return;}
                for(Map.Entry<String,CheckBox> e:switches.entrySet())changed.put(e.getKey(),e.getValue().isChecked());
                a.edit(()->{for(String key:changed.keySet())J.put(component,key,changed.get(key));});
            });
        }
        return card;
    }
    private static JSONObject findById(JSONArray items,String id){
        for(int i=0;i<items.length();i++){JSONObject item=J.at(items,i);if(id.equals(item.optString("id")))return item;}
        return null;
    }
    private static void chooseAsset(MainActivity a,JSONObject target,String kind){
        List<AssetLibrary.Entry> matches=new ArrayList<>();
        for(AssetLibrary.Entry asset:a.library.all(a.project))if(asset.kind.equals(kind))matches.add(asset);
        String[] labels=new String[Math.min(100,matches.size())];
        for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name;
        new AlertDialog.Builder(a).setTitle("Choose "+kind+" (first 100)")
            .setItems(labels,(dialog,index)->a.edit(()->J.put(target,"assetId",matches.get(index).id)))
            .setNeutralButton("Search library",(dialog,index)->a.switchPanel("Assets"))
            .setNegativeButton("Cancel",null).show();
    }
    private static void componentMenu(MainActivity a,JSONObject n){
        String[] types={"Sprite","Body","Collider","Controller","Camera","Particles","Audio","Script",
            "Animation","TileMap","Label","AI","Light","Timer"};
        new AlertDialog.Builder(a).setTitle("Add component").setItems(types,(d,index)->{
            String type=types[index].toLowerCase();if(GameProject.component(n,type)!=null){a.notify("Only one "+type+" component per object.");return;}
            JSONObject c;
            switch(type){
                case "sprite":c=GameProject.sprite("builtin:player-0");break;
                case "body":c=GameProject.body("dynamic",1);break;
                case "collider":c=GameProject.collider(J.obj(n,"transform").optDouble("width",48),
                    J.obj(n,"transform").optDouble("height",48),false);break;
                case "controller":c=GameProject.controller("platformer");break;
                case "camera":c=J.o("type",type,"zoom",1,"smoothing",0.12,"follow",true);break;
                case "particles":c=MainActivity.defaultParticle();break;
                case "audio":c=J.o("type",type,"assetId","builtin:sfx-coin","volume",0.8,"loop",false,"autoplay",false);break;
                case "script":c=J.o("type",type,"scriptId","");break;
                case "animation":c=J.o("type",type,"animationId","","autoplay",true,"speed",1);break;
                case "tilemap":c=J.o("type",type,"tileSize",48,"columns",20,"rows",12,"cells",new JSONObject(),"collision",false);break;
                case "label":c=J.o("type",type,"text","New label","fontSize",24,"color","#ffffff","align","center");break;
                case "ai":c=J.o("type",type,"behavior","patrol","speed",60,"range",120);break;
                case "light":c=J.o("type",type,"radius",160,"color","#ffe7ac","intensity",0.6);break;
                default:c=J.o("type",type,"interval",2,"repeat",true,"enabled",true);
            }
            a.edit(()->GameProject.addComponent(n,c));
        }).show();
    }
    private static void chooseScript(MainActivity a,JSONObject component){
        JSONArray list=a.project.scripts();String[] names=new String[list.length()+1];names[0]="＋ Create new script";
        for(int i=0;i<list.length();i++)names[i+1]=J.at(list,i).optString("name");
        new AlertDialog.Builder(a).setTitle("Attach script").setItems(names,(d,index)->{
            if(index==0){JSONObject fresh=J.o("id",J.id("script"),"name","New script",
                "source","on_start:\n  message \"Hello, world!\"");
                a.edit(()->{list.put(fresh);J.put(component,"scriptId",fresh.optString("id"));});editScript(a,fresh);
            }else{JSONObject chosen=J.at(list,index-1);a.edit(()->J.put(component,"scriptId",chosen.optString("id")));}
        }).show();
    }
    private static void chooseAnimation(MainActivity a,JSONObject component){
        JSONArray list=a.project.animations();String[] names=new String[list.length()+1];names[0]="＋ New animation";
        for(int i=0;i<list.length();i++)names[i+1]=J.at(list,i).optString("name");
        new AlertDialog.Builder(a).setTitle("Choose animation").setItems(names,(d,index)->{
            if(index==0){JSONObject clip=newAnimation("New animation");a.edit(()->{list.put(clip);J.put(component,"animationId",clip.optString("id"));});editAnimation(a,clip);}
            else a.edit(()->J.put(component,"animationId",J.at(list,index-1).optString("id")));
        }).show();
    }
    private static void chooseParticle(MainActivity a,JSONObject emitter){
        JSONArray presets=a.library.presets("particle-presets.json");String[] names=new String[presets.length()];
        for(int i=0;i<presets.length();i++)names[i]=J.at(presets,i).optString("name");
        new AlertDialog.Builder(a).setTitle("100 particle presets")
            .setItems(names,(d,index)->{JSONObject choice=J.at(presets,index);
                a.edit(()->{for(String key:new String[]{"name","rate","speed","lifetime","spread","gravity","size","color"})
                    if(choice.has(key))J.put(emitter,key.equals("name")?"preset":key,choice.opt(key));});
            }).show();
    }
    private static JSONObject newAnimation(String name){
        return J.o("id",J.id("animation"),"name",name,"duration",1,"fps",12,"loop","loop",
            "tracks",new JSONArray(),"frames",new JSONArray(),"events",new JSONArray());
    }
    private static View animations(MainActivity a){
        LinearLayout page=Ui.vertical(a);title(page,"Animation timeline",a.project.animations().length()+" project clips · 59 motion presets");
        button(page,"＋ Create keyframe animation",true,()->{
            JSONObject clip=newAnimation("New animation");
            a.edit(()->{a.project.animations().put(clip);JSONObject node=a.selectedNode();
                if(node!=null){JSONObject component=GameProject.component(node,"animation");
                    if(component==null){component=J.o("type","animation","animationId",clip.optString("id"),"autoplay",true,"speed",1);
                        GameProject.addComponent(node,component);
                    }else J.put(component,"animationId",clip.optString("id"));}
            });editAnimation(a,clip);
        });gap(page,8);
        button(page,"✧ Apply motion preset",false,()->{
            JSONArray presets=a.library.presets("motion-presets.json");
            String[] names=new String[presets.length()];for(int i=0;i<presets.length();i++)names[i]=J.at(presets,i).optString("name");
            new AlertDialog.Builder(a).setTitle("Motion presets · editable keyframes")
                .setItems(names,(d,index)->{
                    JSONObject clip=J.copy(J.at(presets,index));J.put(clip,"id",J.id("animation"));
                    a.edit(()->{a.project.animations().put(clip);JSONObject node=a.selectedNode();if(node!=null){
                        JSONObject component=GameProject.component(node,"animation");
                        if(component==null)GameProject.addComponent(node,J.o("type","animation","animationId",clip.optString("id"),"autoplay",true,"speed",1));
                        else J.put(component,"animationId",clip.optString("id"));
                    }});a.notify("Motion clip applied. Tap the clip to edit its tracks.");
                }).show();
        });gap(page,8);
        button(page,"▦ Import and slice sprite sheet",false,a::pickSheet);gap(page,13);
        JSONArray clips=a.project.animations();
        if(clips.length()==0)hint(page,"No animations yet. Create a clip or apply a motion preset. Every keyframe can be edited.");
        for(int i=0;i<clips.length();i++){
            JSONObject clip=J.at(clips,i);LinearLayout card=Ui.card(a);
            Ui.add(card,Ui.text(a,clip.optString("name"),15,Ui.TEXT,true),-1,31);
            JSONArray tracks=clip.optJSONArray("tracks"),frames=clip.optJSONArray("frames");
            Ui.add(card,Ui.text(a,clip.optDouble("duration")+"s · "+clip.optInt("fps",12)+" FPS · "+clip.optString("loop")+
                " · "+(tracks==null?0:tracks.length())+" tracks · "+(frames==null?0:frames.length())+" frames",11,Ui.MUTED,false),-1,30);
            TextView edit=Ui.button(a,"Edit timeline  →",false);edit.setOnClickListener(v->editAnimation(a,clip));Ui.add(card,edit,-1,44);
            Ui.add(page,card,-1,-2);gap(page,8);
        }
        hint(page,"Preview runs on the scene canvas. Tracks animate relative to the object's saved transform; they never overwrite your layout.");
        return page;
    }
    public static void editAnimation(MainActivity a,JSONObject clip){
        LinearLayout form=Ui.vertical(a);Ui.pad(form,a,15,10,15,12);
        EditText name=Ui.field(a,"Animation name",clip.optString("name"),false);
        Ui.add(form,label(a,"NAME"),-1,23);Ui.add(form,name,-1,47);gap(form,7);
        LinearLayout settings=Ui.horizontal(a);
        EditText duration=Ui.field(a,"Seconds",String.valueOf(clip.optDouble("duration",1)),true);
        EditText fps=Ui.field(a,"FPS",String.valueOf(clip.optInt("fps",12)),true);
        settings.addView(duration,new LinearLayout.LayoutParams(0,Ui.dp(a,47),1));
        LinearLayout.LayoutParams fpsParams=new LinearLayout.LayoutParams(0,Ui.dp(a,47),1);fpsParams.leftMargin=Ui.dp(a,8);
        settings.addView(fps,fpsParams);Ui.add(form,settings,-1,-2);gap(form,7);
        Spinner loop=new Spinner(a);String[] modes={"loop","once","pingpong"};
        loop.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,modes));
        loop.setSelection(Arrays.asList(modes).indexOf(clip.optString("loop","loop"))<0?0:
            Arrays.asList(modes).indexOf(clip.optString("loop","loop")));Ui.add(form,loop,-1,47);
        gap(form,10);
        button(form,"Apply clip settings",true,()->{
            try{double seconds=Double.parseDouble(duration.getText().toString());int frameRate=Integer.parseInt(fps.getText().toString());
                if(seconds<=0||seconds>120||frameRate<1||frameRate>120)throw new NumberFormatException();
                a.edit(()->{J.put(clip,"name",name.getText().toString());J.put(clip,"duration",seconds);
                    J.put(clip,"fps",frameRate);J.put(clip,"loop",modes[loop.getSelectedItemPosition()]);});
            }catch(Exception ex){a.notify("Duration 0–120 seconds; FPS 1–120.");}
        });gap(form,12);
        JSONArray tracks=J.arr(clip,"tracks");
        Ui.add(form,Ui.text(a,"KEYFRAME TRACKS",12,Ui.GREEN,true),-1,33);
        for(int i=0;i<tracks.length();i++){
            JSONObject track=J.at(tracks,i);LinearLayout trackCard=Ui.card(a);
            Ui.add(trackCard,Ui.text(a,track.optString("property").toUpperCase(),13,Ui.TEXT,true),-1,30);
            JSONArray keys=J.arr(track,"keys");
            for(int k=0;k<keys.length();k++){
                JSONObject key=J.at(keys,k);final int keyIndex=k;
                LinearLayout line=Ui.horizontal(a);
                TextView label=Ui.text(a,String.format("%.2fs  →  %.2f",key.optDouble("time"),key.optDouble("value")),12,Ui.MUTED,false);
                line.addView(label,new LinearLayout.LayoutParams(0,Ui.dp(a,38),1));
                TextView edit=Ui.subtleButton(a,"Edit");edit.setOnClickListener(v->keyframeDialog(a,clip,track,key));Ui.add(line,edit,56,38);
                TextView delete=Ui.subtleButton(a,"×");delete.setOnClickListener(v->a.edit(()->keys.remove(keyIndex)));Ui.add(line,delete,42,38);
                Ui.add(trackCard,line,-1,40);
            }
            button(trackCard,"＋ Add keyframe",false,()->keyframeDialog(a,clip,track,null));gap(trackCard,5);
            button(trackCard,"Remove track",false,()->a.edit(()->{
                JSONArray all=J.arr(clip,"tracks");for(int t=all.length()-1;t>=0;t--)if(J.at(all,t)==track)all.remove(t);
            }));Ui.add(form,trackCard,-1,-2);gap(form,8);
        }
        button(form,"＋ Add property track",false,()->{
            String[] properties={"x","y","rotation","scaleX","scaleY","opacity"};
            new AlertDialog.Builder(a).setTitle("Animate a property")
                .setItems(properties,(d,index)->{String p=properties[index];
                    a.edit(()->tracks.put(J.o("property",p,"keys",new JSONArray()
                        .put(J.o("time",0,"value",p.startsWith("scale")||p.equals("opacity")?1:0))
                        .put(J.o("time",clip.optDouble("duration",1),"value",p.startsWith("scale")||p.equals("opacity")?1:0)))));
                    a.notify("Track added. Reopen timeline to edit its keys.");
                }).show();
        });gap(form,12);
        Ui.add(form,Ui.text(a,"SPRITE FRAMES",12,Ui.GREEN,true),-1,29);
        JSONArray frames=J.arr(clip,"frames");
        for(int i=0;i<frames.length();i++){
            JSONObject frame=J.at(frames,i);AssetLibrary.Entry asset=a.library.get(a.project,frame.optString("assetId"));
            Ui.add(form,Ui.text(a,String.format("%.2fs  ·  %s",frame.optDouble("time"),asset==null?"missing frame":asset.name),11,Ui.MUTED,false),-1,29);
        }
        button(form,"＋ Add sprite frame",false,()->{
            List<AssetLibrary.Entry> images=new ArrayList<>();for(AssetLibrary.Entry item:a.library.all(a.project))
                if(item.kind.equals("image")&&(item.category.equals("Imported")||item.category.equals("Characters")))images.add(item);
            String[] labels=new String[Math.min(80,images.size())];for(int i=0;i<labels.length;i++)labels[i]=images.get(i).name;
            new AlertDialog.Builder(a).setTitle("Choose sprite frame")
                .setItems(labels,(d,index)->{
                    EditText at=Ui.field(a,"Time in seconds",String.valueOf(frames.length()/Math.max(1,clip.optInt("fps",12))),true);
                    new AlertDialog.Builder(a).setTitle("Frame time").setView(at)
                        .setPositiveButton("Add frame",(dialog,which)->{
                            try{double time=Double.parseDouble(at.getText().toString());
                                a.edit(()->frames.put(J.o("time",time,"assetId",images.get(index).id)));
                            }catch(Exception ex){a.notify("Invalid frame time.");}
                        }).show();
                }).show();
        });gap(form,12);
        JSONArray events=J.arr(clip,"events");
        Ui.add(form,Ui.text(a,"TIMELINE EVENTS",12,Ui.GREEN,true),-1,29);
        for(int i=0;i<events.length();i++){
            JSONObject event=J.at(events,i);
            Ui.add(form,Ui.text(a,event.optDouble("time")+"s  ·  "+event.optString("action"),11,Ui.MUTED,false),-1,28);
        }
        button(form,"＋ Add audio / particle event",false,()->{
            LinearLayout values=Ui.vertical(a);Ui.pad(values,a,14,8,14,8);
            EditText time=Ui.field(a,"Time (s)","0.5",true),action=Ui.field(a,"sound coin or emit Sparkles","sound coin",false);
            Ui.add(values,time,-1,50);gap(values,6);Ui.add(values,action,-1,50);
            new AlertDialog.Builder(a).setTitle("Timeline event").setView(values)
                .setPositiveButton("Add",(d,index)->{try{double t=Double.parseDouble(time.getText().toString());
                    a.edit(()->events.put(J.o("time",t,"action",action.getText().toString())));
                }catch(Exception ex){a.notify("Invalid time.");}}).show();
        });
        ScrollView scroll=new ScrollView(a);scroll.addView(form);
        Dialog dialog=new Dialog(a);dialog.setContentView(scroll);dialog.setTitle("Animation · "+clip.optString("name"));dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*0.85f));
    }
    private static void keyframeDialog(MainActivity a,JSONObject clip,JSONObject track,JSONObject key){
        LinearLayout values=Ui.vertical(a);Ui.pad(values,a,18,10,18,10);
        EditText time=Ui.field(a,"Time (s)",key==null?"0.5":String.valueOf(key.optDouble("time")),true);
        EditText value=Ui.field(a,"Value",key==null?"0":String.valueOf(key.optDouble("value")),true);
        Ui.add(values,label(a,"TIME IN SECONDS"),-1,27);Ui.add(values,time,-1,50);gap(values,7);
        Ui.add(values,label(a,"PROPERTY VALUE"),-1,27);Ui.add(values,value,-1,50);
        new AlertDialog.Builder(a).setTitle((key==null?"Add":"Edit")+" keyframe · "+track.optString("property"))
            .setView(values).setPositiveButton("Save",(d,index)->{
                try{double t=Double.parseDouble(time.getText().toString()),v=Double.parseDouble(value.getText().toString());
                    if(t<0||t>clip.optDouble("duration"))throw new NumberFormatException();
                    a.edit(()->{JSONObject target=key==null?J.o("time",t,"value",v):key;
                        J.put(target,"time",t);J.put(target,"value",v);
                        if(key==null)J.arr(track,"keys").put(target);});
                }catch(Exception ex){a.notify("Time must fit the animation duration.");}
            }).setNegativeButton("Cancel",null).show();
    }
    private static View scripts(MainActivity a){
        LinearLayout page=Ui.vertical(a);
        title(page,"Game scripts",a.project.scripts().length()+" sandboxed scripts · no arbitrary JavaScript");
        button(page,"＋ New text script",true,()->{
            JSONObject script=J.o("id",J.id("script"),"name","New script","source","on_start:\n  message \"Hello, world!\"");
            a.edit(()->a.project.scripts().put(script));editScript(a,script);
        });gap(page,8);
        button(page,"▧ Logic blocks → safe script",false,()->logicBlocks(a));gap(page,11);
        JSONArray scripts=a.project.scripts();
        for(int i=0;i<scripts.length();i++){
            JSONObject script=J.at(scripts,i);LinearLayout card=Ui.card(a);
            Ui.add(card,Ui.text(a,script.optString("name"),15,Ui.TEXT,true),-1,31);
            List<String> issues=com.world2d.engine.runtime.ScriptVM.validate(script.optString("source"));
            Ui.add(card,Ui.text(a,issues.isEmpty()?"✓ Syntax valid":issues.size()+" issue(s): "+issues.get(0),
                11,issues.isEmpty()?Ui.GREEN:Ui.RED,false),-1,-2);gap(card,7);
            button(card,"Open script  →",false,()->editScript(a,script));Ui.add(page,card,-1,-2);gap(page,8);
        }
        hint(page,"Events: on_start, on_update, on_collision(player), on_interact, on_input(fire). Actions: set, add, move, destroy, sound, scene, message, emit, save, load, spawn, win. Conditions: if score >= 3: scene Next.");
        return page;
    }
    public static void editScript(MainActivity a,JSONObject script){
        LinearLayout body=Ui.vertical(a);Ui.pad(body,a,12,10,12,10);
        EditText name=Ui.field(a,"Script name",script.optString("name"),false);Ui.add(body,name,-1,49);gap(body,8);
        LinearLayout editor=Ui.horizontal(a);editor.setGravity(Gravity.TOP);
        TextView numbers=Ui.text(a,"1\n2\n3\n4\n5",12,Ui.MUTED,false);
        numbers.setTypeface(Typeface.MONOSPACE);numbers.setGravity(Gravity.TOP);
        Ui.pad(numbers,a,4,10,5,0);Ui.add(editor,numbers,28,-1);
        EditText source=new EditText(a);source.setText(script.optString("source"));source.setTextSize(13);
        source.setTextColor(Ui.TEXT);source.setTypeface(Typeface.MONOSPACE);source.setGravity(Gravity.TOP);
        source.setBackground(Ui.background(a,Ui.RAISED,8,Ui.BORDER));
        source.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        source.setHorizontallyScrolling(false);source.setMinLines(12);
        editor.addView(source,new LinearLayout.LayoutParams(0,Ui.dp(a,300),1));Ui.add(body,editor,-1,-2);
        TextView status=Ui.text(a,"",11,Ui.MUTED,false);Ui.add(body,status,-1,41);
        final boolean[] coloring={false};
        Runnable validate=()->{
            String raw=source.getText().toString();int lines=raw.split("\\n",-1).length;
            StringBuilder list=new StringBuilder();for(int i=1;i<=lines;i++)list.append(i).append('\n');numbers.setText(list.toString());
            List<String> issues=com.world2d.engine.runtime.ScriptVM.validate(raw);
            status.setText(issues.isEmpty()?"✓ Valid · "+lines+" lines":issues.get(0));
            status.setTextColor(issues.isEmpty()?Ui.GREEN:Ui.RED);
        };validate.run();
        source.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){validate.run();}
            public void afterTextChanged(Editable edit){
                if(coloring[0])return; coloring[0]=true;
                try{
                    String text=edit.toString();
                    ForegroundColorSpan[] spans=edit.getSpans(0,edit.length(),ForegroundColorSpan.class);
                    for(ForegroundColorSpan span:spans)edit.removeSpan(span);
                    java.util.regex.Matcher events=java.util.regex.Pattern.compile("on_(start|update|collision|input|interact|timer)(?:\\([^)]*\\))?:").matcher(text);
                    while(events.find())edit.setSpan(new ForegroundColorSpan(Ui.GREEN),events.start(),events.end(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    java.util.regex.Matcher comments=java.util.regex.Pattern.compile("(?m)^\\s*#.*$").matcher(text);
                    while(comments.find())edit.setSpan(new ForegroundColorSpan(Ui.MUTED),comments.start(),comments.end(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }catch(Exception ignored){}finally{coloring[0]=false;}
            }
        });
        LinearLayout buttons=Ui.horizontal(a);
        TextView format=Ui.button(a,"Format",false);format.setOnClickListener(v->{StringBuilder formatted=new StringBuilder();
            for(String line:source.getText().toString().split("\\n")){
                String trim=line.trim();if(trim.startsWith("on_")||trim.startsWith("#")||trim.isEmpty())formatted.append(trim);
                else formatted.append("  ").append(trim);formatted.append('\n');}
            source.setText(formatted.toString().trim());});
        buttons.addView(format,new LinearLayout.LayoutParams(0,Ui.dp(a,45),1));
        TextView find=Ui.button(a,"Find / replace",false);
        find.setOnClickListener(v->replaceInSource(a,source));
        LinearLayout.LayoutParams findParams=new LinearLayout.LayoutParams(0,Ui.dp(a,45),1);findParams.leftMargin=Ui.dp(a,7);
        buttons.addView(find,findParams);Ui.add(body,buttons,-1,-2);gap(body,8);
        button(body,"View script API reference",false,()->docs(a));gap(body,8);
        ScrollView scroll=new ScrollView(a);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Script editor").setView(scroll)
            .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
            List<String> errors=com.world2d.engine.runtime.ScriptVM.validate(source.getText().toString());
            if(!errors.isEmpty()){a.notify(errors.get(0));return;}
            a.edit(()->{J.put(script,"name",name.getText().toString().trim());
                J.put(script,"source",source.getText().toString());});dialog.dismiss();
        }));dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*0.85f));
    }
    private static void replaceInSource(MainActivity a,EditText source){
        LinearLayout form=Ui.vertical(a);Ui.pad(form,a,16,10,16,10);
        EditText find=Ui.field(a,"Find","",false),replace=Ui.field(a,"Replace with","",false);
        Ui.add(form,find,-1,50);gap(form,7);Ui.add(form,replace,-1,50);
        new AlertDialog.Builder(a).setTitle("Find and replace").setView(form)
            .setPositiveButton("Replace all",(d,w)->{String needle=find.getText().toString();if(!needle.isEmpty())
                source.setText(source.getText().toString().replace(needle,replace.getText().toString()));})
            .setNegativeButton("Cancel",null).show();
    }
    private static void logicBlocks(MainActivity a){
        LinearLayout form=Ui.vertical(a);Ui.pad(form,a,18,10,18,12);
        String[] events={"on_start","on_update","on_collision(player)","on_interact","on_input(fire)"};
        String[] actions={"add score 1","add health -1","sound coin","emit Sparkles","destroy self",
            "message \"Hello!\"","scene \"Next Scene\"","win"};
        Spinner event=new Spinner(a),action=new Spinner(a);
        event.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,events));
        action.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,actions));
        Ui.add(form,label(a,"WHEN THIS EVENT HAPPENS"),-1,27);Ui.add(form,event,-1,50);
        Ui.add(form,label(a,"DO THIS ACTION"),-1,27);Ui.add(form,action,-1,50);
        EditText name=Ui.field(a,"Name","My logic",false);
        Ui.add(form,label(a,"LOGIC NAME"),-1,27);Ui.add(form,name,-1,50);
        new AlertDialog.Builder(a).setTitle("Logic blocks → script").setView(form)
            .setPositiveButton("Create",(d,w)->{
                String scriptText=events[event.getSelectedItemPosition()]+":\n  "+actions[action.getSelectedItemPosition()];
                JSONObject script=J.o("id",J.id("script"),"name",name.getText().toString(),"source",scriptText);
                a.edit(()->{a.project.scripts().put(script);JSONObject node=a.selectedNode();if(node!=null){
                    JSONObject component=GameProject.component(node,"script");
                    if(component==null)GameProject.addComponent(node,J.o("type","script","scriptId",script.optString("id")));
                    else J.put(component,"scriptId",script.optString("id"));
                }});a.notify("Logic attached. Open the script to add more actions.");
            }).setNegativeButton("Cancel",null).show();
    }
    private static View tilemap(MainActivity a){
        LinearLayout page=Ui.vertical(a);
        title(page,"TileMap tools","Paint individual cells using actual assets; enable collision in the inspector.");
        JSONObject node=a.selectedNode(),map=node==null?null:GameProject.component(node,"tilemap");
        if(map==null){hint(page,"Select a TileMap node in the hierarchy. Or create one now.");
            button(page,"＋ Create TileMap",true,()->{
                a.edit(()->{JSONObject item=GameProject.newNode("New TileMap",a.scene.optInt("width")/2,
                    a.scene.optInt("height")/2,a.scene.optInt("width"),a.scene.optInt("height"));
                    GameProject.addComponent(item,J.o("type","tilemap","tileSize",48,"columns",20,"rows",12,
                        "cells",new JSONObject(),"collision",false));
                    GameProject.nodes(a.scene).put(item);a.selectNode(item.optString("id"));
                });a.viewport.setTool(SceneView.Tool.BRUSH);
            });return page;}
        Ui.add(page,Ui.text(a,node.optString("name")+"  ·  "+J.obj(map,"cells").length()+" painted cells",13,Ui.TEXT,true),-1,33);
        LinearLayout buttons=Ui.horizontal(a);
        TextView brush=Ui.button(a,"✎ Brush",true);brush.setOnClickListener(v->{a.viewport.setTool(SceneView.Tool.BRUSH);a.closePanel();});
        TextView erase=Ui.button(a,"⌫ Eraser",false);erase.setOnClickListener(v->{a.viewport.setTool(SceneView.Tool.ERASE);a.closePanel();});
        buttons.addView(brush,new LinearLayout.LayoutParams(0,Ui.dp(a,46),1));
        LinearLayout.LayoutParams eraserParams=new LinearLayout.LayoutParams(0,Ui.dp(a,46),1);eraserParams.leftMargin=Ui.dp(a,8);
        buttons.addView(erase,eraserParams);Ui.add(page,buttons,-1,-2);gap(page,12);
        Ui.add(page,Ui.text(a,"QUICK TILE PALETTE",11,Ui.GREEN,true),-1,31);
        GridLayout palette=new GridLayout(a);palette.setColumnCount(7);
        for(int i=0;i<35;i++){
            String id="builtin:tile-"+i;ImageView tile=new ImageView(a);Bitmap image=a.library.bitmap(a.project,id,52);
            if(image!=null)tile.setImageBitmap(image);
            tile.setBackground(Ui.background(a,Ui.RAISED,5,Ui.BORDER));
            tile.setPadding(Ui.dp(a,4),Ui.dp(a,4),Ui.dp(a,4),Ui.dp(a,4));
            GridLayout.LayoutParams layout=new GridLayout.LayoutParams();layout.width=Ui.dp(a,44);layout.height=Ui.dp(a,44);
            layout.setMargins(Ui.dp(a,2),Ui.dp(a,2),Ui.dp(a,2),Ui.dp(a,2));palette.addView(tile,layout);
            tile.setOnClickListener(v->{a.selectBrush(id);a.closePanel();});
        }Ui.add(page,palette,-1,-2);gap(page,12);
        button(page,"Browse all tiles and textures",false,()->a.switchPanel("Assets"));gap(page,8);
        CheckBox solid=new CheckBox(a);solid.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        solid.setText("Generate solid collision for painted cells");solid.setTextColor(Ui.TEXT);
        solid.setChecked(map.optBoolean("collision"));solid.setOnCheckedChangeListener((v,enabled)->a.edit(()->J.put(map,"collision",enabled)));
        Ui.add(page,solid,-1,48);gap(page,8);
        button(page,"Fill entire map with selected tile",false,()->{
            new AlertDialog.Builder(a).setTitle("Fill all cells?")
                .setMessage("The current paint brush replaces all cells. Undo remains available.")
                .setPositiveButton("Fill",(d,w)->a.edit(()->{JSONObject cells=J.obj(map,"cells");
                    for(int y=0;y<Math.min(map.optInt("rows",12),100);y++)
                        for(int x=0;x<Math.min(map.optInt("columns",20),100);x++)
                            J.put(cells,x+","+y,"builtin:tile-1");}))
                .setNegativeButton("Cancel",null).show();
        });gap(page,7);
        button(page,"Clear painted cells",false,()->a.edit(()->J.put(map,"cells",new JSONObject())));
        hint(page,"Grid dimensions, spacing and collision are editable on this TileMap's Inspector card. Two fingers pan and pinch the viewport while painting.");
        return page;
    }
    private static View input(MainActivity a){
        LinearLayout page=Ui.vertical(a);title(page,"Input map","Physical keyboard mappings and in-game touch controls.");
        JSONObject mapping=J.obj(a.project.data,"input");
        for(java.util.Iterator<String> iterator=mapping.keys();iterator.hasNext();){String action=iterator.next();
            JSONArray keys=mapping.optJSONArray(action);StringBuilder initial=new StringBuilder();
            if(keys!=null)for(int i=0;i<keys.length();i++){if(i>0)initial.append(", ");initial.append(keys.optString(i));}
            Ui.add(page,label(a,action.toUpperCase()),-1,26);
            EditText values=Ui.field(a,"Key codes",initial.toString(),false);Ui.add(page,values,-1,48);
            TextView apply=Ui.subtleButton(a,"Apply "+action);apply.setOnClickListener(v->{String text=values.getText().toString().trim();
                JSONArray updated=new JSONArray();for(String key:text.split(","))if(!key.trim().isEmpty())updated.put(key.trim());
                a.edit(()->J.put(mapping,action,updated));});Ui.add(page,apply,-1,42);gap(page,6);
        }
        button(page,"＋ Add custom action",false,()->{
            EditText name=Ui.field(a,"Action name","dash",false);
            new AlertDialog.Builder(a).setTitle("New input action").setView(name)
                .setPositiveButton("Add",(d,w)->{String key=name.getText().toString().trim().toLowerCase().replaceAll("[^a-z0-9_]","");
                    if(!key.isEmpty())a.edit(()->J.put(mapping,key,new JSONArray()));}).show();
        });hint(page,"Use Android-style codes: KeyA–KeyZ, ArrowLeft/Right/Up/Down, Space, Enter, Escape. The preview's touch pad sends left, right, up, down, jump, fire and interact actions. Scripts can receive on_input(action): events.");
        return page;
    }
    private static View console(MainActivity a){
        LinearLayout page=Ui.vertical(a);title(page,"Console & profiler","Live output from the actual game runtime.");
        com.world2d.engine.runtime.GameRuntime runtime=a.viewport==null?null:a.viewport.runtime();
        if(runtime==null)hint(page,"Start Play to see FPS, physics contacts, object and particle counts.");
        else{
            LinearLayout stats=Ui.card(a);
            String[] metrics={"FPS  "+runtime.fps,"FRAME  "+String.format("%.1f",runtime.frameMs)+" ms",
                "OBJECTS  "+GameProject.nodes(runtime.scene).length(),"CONTACTS  "+runtime.collisions,
                "PARTICLES  "+runtime.particles.size(),"DRAW CALLS  "+runtime.drawCalls};
            for(String metric:metrics)Ui.add(stats,Ui.text(a,metric,12,Ui.GREEN,true),-1,27);
            Ui.add(page,stats,-1,-2);gap(page,9);
            if(runtime.fps<30)hint(page,"Suggestion: reduce particle emission or tilemap size to improve frame rate.");
            button(page,"Toggle collision visualization",false,()->{runtime.collisionDebug=!runtime.collisionDebug;a.viewport.invalidate();});gap(page,10);
        }
        Ui.add(page,Ui.text(a,"OUTPUT",11,Ui.GREEN,true),-1,30);
        List<String> lines=new ArrayList<>();if(runtime!=null)lines.addAll(runtime.logs);lines.addAll(a.editorLogs());
        if(lines.isEmpty())hint(page,"No warnings or errors. Editor output will appear here.");
        for(int i=0;i<Math.min(80,lines.size());i++){
            String line=lines.get(i);TextView item=Ui.text(a,line,11,
                line.startsWith("[ERROR]")||line.contains("failed")?Ui.RED:line.startsWith("[WARNING]")?Ui.AMBER:Ui.MUTED,false);
            Ui.pad(item,a,6,7,5,7);Ui.add(page,item,-1,-2);Ui.add(page,Ui.divider(a),-1,1);
        }
        gap(page,12);button(page,"Clear console",false,()->{a.editorLogs().clear();if(runtime!=null)runtime.logs.clear();a.refreshPanel();});
        return page;
    }
    private static View build(MainActivity a){
        LinearLayout page=Ui.vertical(a);title(page,"Build & export","Your project data and game source, ready to take further.");
        LinearLayout info=Ui.card(a);
        Ui.add(info,Ui.text(a,"ANDROID CONFIGURATION",11,Ui.GREEN,true),-1,30);
        String[] details={"App  "+a.project.name(),"Package  "+a.project.data.optString("packageId"),
            "Version  "+a.project.data.optString("version"),"Orientation  "+a.project.data.optString("orientation"),
            "Resolution  "+a.project.data.optInt("width")+" × "+a.project.data.optInt("height"),
            "Min Android  8.0 (API 26)"};
        for(String detail:details)Ui.add(info,Ui.text(a,detail,12,Ui.TEXT,false),-1,27);
        Ui.add(page,info,-1,-2);gap(page,12);
        button(page,"⚙  Edit build settings",false,a::projectSettings);gap(page,9);
        button(page,"↥  Export editable project  (.2dw)",false,()->a.requestExport("project"));gap(page,9);
        button(page,"◈  Export Android Studio game project  (.zip)",true,()->a.requestExport("android"));gap(page,14);
        LinearLayout limitation=Ui.card(a);
        Ui.add(limitation,Ui.text(a,"ABOUT APK COMPILATION",12,Ui.AMBER,true),-1,31);
        Ui.add(limitation,Ui.text(a,"This Android editor does not contain the Android SDK, AAPT2, D8 or release signing keys. It cannot honestly claim to compile an APK on-device. Export the real Android Gradle project above, open it in Android Studio and build a debug APK. The 2D WORLD editor itself is a native Android APK.",12,Ui.TEXT,false),-1,-2);
        gap(limitation,8);
        Ui.add(limitation,Ui.text(a,"A debug APK is for testing. Configure your own release key before distribution. No build progress or installation is simulated.",11,Ui.MUTED,false),-1,-2);
        Ui.add(page,limitation,-1,-2);gap(page,10);
        button(page,"How to build the exported game",false,()->docs(a));
        return page;
    }
    public static void projectSettings(MainActivity a){
        if(a.project==null){globalSettings(a);return;}
        LinearLayout form=Ui.vertical(a);Ui.pad(form,a,18,12,18,12);
        Map<String,EditText> fields=new HashMap<>();
        String[] keys={"name","packageId","version","width","height","gravity","fps"};
        for(String key:keys){Ui.add(form,label(a,Ui.titlecase(key)), -1,27);
            EditText input=Ui.field(a,key,a.project.data.optString(key),
                key.equals("width")||key.equals("height")||key.equals("gravity")||key.equals("fps"));
            Ui.add(form,input,-1,49);fields.put(key,input);gap(form,7);
        }
        Spinner orientation=new Spinner(a);String[] modes={"landscape","portrait","auto"};
        orientation.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,modes));
        orientation.setSelection(Math.max(0,Arrays.asList(modes).indexOf(a.project.data.optString("orientation"))));
        Ui.add(form,label(a,"ORIENTATION"),-1,27);Ui.add(form,orientation,-1,48);
        hint(form,"Changing the project resolution affects new scenes. Existing scenes keep their authored dimensions until edited separately.");
        ScrollView scroll=new ScrollView(a);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Project settings").setView(scroll)
            .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
            String pkg=fields.get("packageId").getText().toString().trim();
            if(!pkg.matches("[a-zA-Z_][\\w]*(\\.[a-zA-Z_][\\w]*){2,}")){a.notify("Invalid package ID. Example: com.example.mygame");return;}
            int width,height,fps;double gravity;
            try{width=Integer.parseInt(fields.get("width").getText().toString());
                height=Integer.parseInt(fields.get("height").getText().toString());
                fps=Integer.parseInt(fields.get("fps").getText().toString());
                gravity=Double.parseDouble(fields.get("gravity").getText().toString());}
            catch(Exception ex){a.notify("Enter valid numbers.");return;}
            if(width<160||height<160||width>8192||height>8192||fps<15||fps>120){a.notify("Resolution 160–8192, FPS 15–120.");return;}
            a.edit(()->{for(String key:new String[]{"name","packageId","version"})
                J.put(a.project.data,key,fields.get(key).getText().toString().trim());
                J.put(a.project.data,"width",width);J.put(a.project.data,"height",height);
                J.put(a.project.data,"gravity",gravity);J.put(a.project.data,"fps",fps);
                J.put(a.project.data,"orientation",modes[orientation.getSelectedItemPosition()]);});
            dialog.dismiss();
        }));dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*0.80f));
    }
    public static void globalSettings(MainActivity a){
        String[] items={"Professional mode: "+(a.isPro()?"On":"Off"),"Asset licenses","About & limitations"};
        new AlertDialog.Builder(a).setTitle("Editor settings").setItems(items,(d,index)->{
            if(index==0){a.getPreferences(0).edit().putBoolean("pro-mode",!a.isPro()).apply();a.notify("Switches on next editor open.");}
            else if(index==1)new AlertDialog.Builder(a).setTitle("Bundled resource licenses")
                .setMessage("2D WORLD procedurally generated vector sprites, textures and sound effects: original work released as CC0.\n\nKenney Pixel Platformer artwork by Kenney (kenney.nl): CC0 1.0.\n\nImported resources keep their original licenses; verify rights before publishing a game.")
                .setPositiveButton("Close",null).show();
            else docs(a);
        }).show();
    }
    public static void docs(MainActivity a){
        String[] titles={"Getting started","Scene editor & touch gestures","Assets & modular split",
            "Physics & collision","TileMaps","Animation & sprite sheets","Scripting API","Input & touch",
            "Saving & recovery","Exporting an Android game","Limitations"};
        String[] docs={
            "Tap New project, choose Empty or a playable template, then open the scene editor. Add a sprite or tap an asset and choose Use. Tap the viewport to place it. Press ▶ Play to run the current scene. Save using ↧ or Ctrl+S.",
            "Tap a scene object to select. Drag to move it. Use R and S in the left toolbar to rotate and scale. Pinch with two fingers to zoom and pan. Enable Snap for grid alignment. Long-press an object in the hierarchy for reparent, prefab, rename and duplicate. Undo / redo record commands.",
            "Browse 623 bundled resources: generated CC0 artwork and Kenney Pixel Platformer CC0. Import PNG, JPG, WEBP, GIF, WAV, MP3 or OGG using Android's file picker. Use places an asset, Split decomposes authored vehicles, characters and buildings into independent sprites. Arbitrary flat raster images cannot be semantically separated; use the grid sprite-sheet slicer instead. References are IDs, not file names.",
            "Add a Body and Collider via the Inspector. Body types: static, dynamic and character. Add a Controller for platformer, top-down or car movement. Colliders support box and circle triggers. Auto-fit matches the object's width and height. Toggle collision outlines while previewing. Joints and arbitrary polygons are not implemented in this first native release.",
            "Add a TileMap node, then open TileMap tools. Select a tile to set the brush, draw on the viewport, or erase cells. Toggle solid collision for painted cells. Tile size, grid dimensions and cells are stored in the scene JSON. Multiple maps form independent layers.",
            "Animation clips contain keyframes for x, y, rotation, scale and opacity. Select from 59 transform-motion presets or create your own tracks. Import a sprite sheet to slice actual image frames into an animation. FPS, duration, loop and ping-pong modes are editable. Frame events can play sound or spawn particles.",
            "Scripts use a restricted, offline DSL (not JavaScript). Events: on_start:, on_update:, on_collision(player):, on_interact:, on_input(fire):. Actions: set health 3; add score 1; move self 100 0; destroy self; sound coin; scene \"Level 2\"; message \"Hello!\"; emit Sparkles; save health; load health; spawn Enemy; win. Use if score >= 3: scene Next. Errors are shown before save. The Logic blocks button generates a safe script.",
            "Touch controls appear while playing. A connected keyboard supports arrows/WASD, Space, J/Enter and E. Edit mapping strings in the Input panel. Use on_input(action): in scripts for custom actions. Camera follows the player when a Camera component is attached.",
            "Projects use human-readable JSON with stable IDs. The editor writes an autosave every 30 seconds and recovery drafts while editing. Manual Save creates up to five version snapshots. Close and reopen from Recent Projects. Export / import .2dw ZIP files to back up or share project source.",
            "Build → Export Android Studio game project writes your scene JSON, imported assets, built-in assets and a native Android runtime into a Gradle project ZIP. Open the extracted folder in Android Studio with JDK 17 + Android SDK 35, then Build → Build APK(s). Debug APK: app/build/outputs/apk/debug/app-debug.apk. Configure your own release signing key for distribution. On-device APK compilation requires SDK build tools not bundled in this app.",
            "This is a working native Android editor/runtime foundation, not all features of a desktop engine. Current limitations: box/circle collision only; no polygon joints, shader graph, high-end lighting, editor dock rearrangement or on-device Gradle toolchain. Flat images cannot be semantically split automatically. Never interpret imported scripts as unrestricted code. Inspect the five example projects to learn supported behavior."
        };
        new AlertDialog.Builder(a).setTitle("2D WORLD · Help & API").setItems(titles,(d,index)->
            new AlertDialog.Builder(a).setTitle(titles[index]).setMessage(docs[index])
                .setPositiveButton("Got it",null).setNeutralButton("All topics",(x,y)->docs(a)).show()).show();
    }
    public static void prefabs(MainActivity a){if(a.project==null)return;
        JSONArray items=a.project.prefabs();String[] names=new String[items.length()];
        for(int i=0;i<items.length();i++)names[i]=J.at(items,i).optString("name");
        if(names.length==0){a.notify("No prefabs yet. Long-press an object and choose Save as prefab.");return;}
        new AlertDialog.Builder(a).setTitle("Reusable prefabs").setItems(names,(d,index)->{
            JSONObject prefab=J.at(items,index),template=J.at(prefab.optJSONArray("nodes"),0);
            if(template==null)return;
            a.edit(()->{
                JSONArray nodes=prefab.optJSONArray("nodes");Map<String,String> remap=new HashMap<>();
                for(int i=0;i<nodes.length();i++)remap.put(J.at(nodes,i).optString("id"),J.id("node"));
                for(int i=0;i<nodes.length();i++){
                    JSONObject n=J.copy(J.at(nodes,i));String old=n.optString("id");J.put(n,"id",remap.get(old));
                    String parent=n.optString("parentId");if(remap.containsKey(parent))J.put(n,"parentId",remap.get(parent));
                    else{J.put(n,"parentId",JSONObject.NULL);
                        J.put(J.obj(n,"transform"),"x",a.scene.optInt("width")/2);
                        J.put(J.obj(n,"transform"),"y",a.scene.optInt("height")/2);}
                    J.put(n,"prefabId",prefab.optString("id"));GameProject.nodes(a.scene).put(n);
                }
            });a.notify("Prefab instance placed in scene");
        }).show();
    }
    public static void sliceSheet(MainActivity a,Uri uri){
        if(a.project==null)return;
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(java.io.InputStream in=a.getContentResolver().openInputStream(uri)){
            android.graphics.BitmapFactory.decodeStream(in,null,bounds);
        }catch(Exception ex){a.notify("Cannot read image: "+ex.getMessage());return;}
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>4096||bounds.outHeight>4096){
            a.notify("Sprite sheet must be a raster image under 4096×4096.");return;
        }
        LinearLayout form=Ui.vertical(a);Ui.pad(form,a,16,10,16,12);
        Ui.add(form,Ui.text(a,"Sheet size: "+bounds.outWidth+" × "+bounds.outHeight,13,Ui.GREEN,true),-1,34);
        Map<String,EditText> fields=new HashMap<>();
        for(String key:new String[]{"Cell width","Cell height","Margin","Spacing","FPS"}){
            Ui.add(form,label(a,key.toUpperCase()),-1,25);
            String initial=key.equals("Cell width")||key.equals("Cell height")?"32":key.equals("FPS")?"12":"0";
            EditText value=Ui.field(a,key,initial,true);fields.put(key,value);Ui.add(form,value,-1,48);gap(form,6);
        }
        TextView preview=Ui.text(a,"Each non-empty cell becomes a real PNG project asset and a timed animation frame.",12,Ui.MUTED,false);
        Ui.add(form,preview,-1,-2);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Slice sprite sheet").setView(form)
            .setPositiveButton("Slice & import",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
            try{
                int w=Integer.parseInt(fields.get("Cell width").getText().toString()),h=Integer.parseInt(fields.get("Cell height").getText().toString());
                int margin=Integer.parseInt(fields.get("Margin").getText().toString()),space=Integer.parseInt(fields.get("Spacing").getText().toString());
                int fps=Integer.parseInt(fields.get("FPS").getText().toString());
                if(w<1||h<1||margin<0||space<0||fps<1||fps>60)throw new IllegalArgumentException("Check cell size, margin, spacing and FPS.");
                int cols=(bounds.outWidth-2*margin+space)/(w+space),rows=(bounds.outHeight-2*margin+space)/(h+space);
                if(cols<1||rows<1||cols*rows>400)throw new IllegalArgumentException("Sheet must contain 1–400 cells.");
                Bitmap bitmap;try(java.io.InputStream in=a.getContentResolver().openInputStream(uri)){
                    bitmap=android.graphics.BitmapFactory.decodeStream(in);}
                if(bitmap==null)throw new IllegalArgumentException("Image decode failed.");
                JSONArray frames=new JSONArray();List<JSONObject> created=new ArrayList<>();
                try{
                    for(int row=0;row<rows;row++)for(int col=0;col<cols;col++){
                        int x=margin+col*(w+space),y=margin+row*(h+space);
                        if(x+w>bitmap.getWidth()||y+h>bitmap.getHeight())continue;
                        String id=J.id("asset"),path="assets/"+id+".png";
                        File file=a.store.assetFile(a.project,path);file.getParentFile().mkdirs();
                        Bitmap cell=Bitmap.createBitmap(bitmap,x,y,w,h);
                        try(FileOutputStream out=new FileOutputStream(file)){cell.compress(Bitmap.CompressFormat.PNG,100,out);}
                        cell.recycle();
                        JSONObject asset=J.o("id",id,"name","Frame "+(frames.length()+1),"kind","image",
                            "category","Imported","src",path,"width",w,"height",h,
                            "license","User supplied — verify rights before distribution","origin","Sprite sheet",
                            "tags",new JSONArray().put("frame"));
                        created.add(asset);frames.put(J.o("time",frames.length()/(double)fps,"assetId",id));
                    }
                }finally{bitmap.recycle();}
                JSONObject animation=newAnimation("Sheet animation");
                J.put(animation,"duration",Math.max(1d/fps,frames.length()/(double)fps));
                J.put(animation,"fps",fps);J.put(animation,"frames",frames);
                a.edit(()->{for(JSONObject asset:created)a.project.assets().put(asset);
                    a.project.animations().put(animation);
                    JSONObject node=a.selectedNode();if(node!=null){
                        JSONObject sprite=GameProject.component(node,"sprite");if(sprite!=null&&frames.length()>0)
                            J.put(sprite,"assetId",J.at(frames,0).optString("assetId"));
                        JSONObject anim=GameProject.component(node,"animation");
                        if(anim==null)GameProject.addComponent(node,J.o("type","animation","animationId",animation.optString("id"),"autoplay",true,"speed",1));
                        else J.put(anim,"animationId",animation.optString("id"));
                    }
                });dialog.dismiss();a.notify("Imported "+frames.length()+" frames and a playable animation.");
            }catch(Exception ex){a.notify("Sheet slicing failed: "+ex.getMessage());}
        }));dialog.show();
    }
}
