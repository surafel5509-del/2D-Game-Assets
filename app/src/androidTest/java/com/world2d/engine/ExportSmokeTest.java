package com.world2d.engine;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.Examples;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.data.ProjectStore;
import com.world2d.engine.export.AndroidGameExporter;
import com.world2d.engine.runtime.GameRuntime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.FileInputStream;

/** The actual Android editor exports the Hill Climb template, runtime and bundled resources. */
@RunWith(AndroidJUnit4.class)
public final class ExportSmokeTest {
    @Test public void exportedGameContainsProjectResourcesAndNativeBuild() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProjectStore store=new ProjectStore(context);
        AssetLibrary assets=new AssetLibrary(context,store);
        GameProject project=Examples.create("hill-climb",assets);
        assertEquals("hill-climb",project.data.optString("genre"));
        boolean hasTerrain=false,hasVehicle=false,hasHeadCollider=false;
        for(int i=0;i<GameProject.nodes(project.firstScene()).length();i++){
            org.json.JSONObject node=GameProject.at(project.firstScene(),i);
            hasTerrain|=GameProject.component(node,"hillTerrain")!=null;
            hasVehicle|=GameProject.component(node,"hillVehicle")!=null;
            hasHeadCollider|=GameProject.component(node,"hillHead")!=null;
        }
        assertTrue("Export fixture needs procedural ground",hasTerrain);
        assertTrue("Export fixture needs a physics-driven vehicle",hasVehicle);
        assertTrue("Export fixture needs a distinct driver head collider",hasHeadCollider);
        GameRuntime simulation=new GameRuntime(context,project,null,assets);
        try{
            assertNotNull("Hill Climb template should activate its dedicated runtime",simulation.hillClimb);
            simulation.setAction("right",true);
            for(int frame=0;frame<180;frame++)simulation.tick(1f/60f);
            simulation.setAction("right",false);
            assertTrue("Gas input should move the vehicle through the endless terrain",
                simulation.hillClimb.distance()>10);
            assertTrue("Using the throttle should consume some fuel",simulation.hillClimb.fuelFraction()<1f);
        }finally{simulation.stopAudio();}
        String id=J.id("asset"),path="assets/"+id+".png";
        File imported=store.assetFile(project,path);
        assertNotNull(imported);
        assertTrue(imported.getParentFile().mkdirs() || imported.getParentFile().isDirectory());
        try(InputStream input=context.getAssets().open("library/images/builtin_player-0.png");
                FileOutputStream output=new FileOutputStream(imported)){
            byte[] bytes=new byte[8192];int count;
            while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
        }
        project.assets().put(J.o("id",id,"name","QA custom sprite","kind","image",
            "src",path,"category","Imported","width",64,"height",80,
            "tags",new JSONArray().put("imported"),"license","test only"));
        JSONObject node=GameProject.newNode("Imported character",80,100,64,80);
        GameProject.addComponent(node,GameProject.sprite(id));
        GameProject.nodes(project.firstScene()).put(node);
        File archive=new File(context.getFilesDir(),"game-export-smoke.zip");
        try(FileOutputStream output=new FileOutputStream(archive)){
            new AndroidGameExporter(context,store,assets).write(project,output);
        }
        assertTrue("Game ZIP should include the resource library",archive.length()>500_000);
        Set<String> names=new HashSet<>();
        JSONObject projectJson=null;
        try(ZipInputStream input=new ZipInputStream(new FileInputStream(archive))){
            ZipEntry entry;
            while((entry=input.getNextEntry())!=null){
                names.add(entry.getName());
                if(entry.getName().equals("app/src/main/assets/game/project.json")){
                    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                    byte[] buffer=new byte[8192];int count;
                    while((count=input.read(buffer))!=-1)out.write(buffer,0,count);
                    projectJson=new JSONObject(out.toString("UTF-8"));
                }
                input.closeEntry();
            }
        }
        assertTrue(names.contains("app/src/main/assets/game/"+path));
        assertTrue(names.contains("app/src/main/assets/library/catalog.json"));
        assertTrue(names.contains("app/src/main/java/com/world2d/engine/export/GameActivity.java"));
        assertTrue(names.contains("app/src/main/java/com/world2d/engine/runtime/GameRuntime.java"));
        assertTrue(names.contains("app/src/main/java/com/world2d/engine/runtime/HillClimbMode.java"));
        assertTrue(names.contains("gradle/wrapper/gradle-wrapper.jar"));
        assertNotNull(projectJson);
        assertEquals(project.id(),projectJson.optString("id"));
        assertEquals(1,projectJson.optJSONArray("assets").length());
        assertTrue("Actual bundled artwork should be exported",names.size()>1500);

        // The QA export above deliberately includes an imported test sprite. Build the APK
        // from a fresh template so device screenshots show only the authored Hill Climb game.
        GameProject gameplayProject=Examples.create("hill-climb",assets);
        File gameplayArchive=new File(context.getFilesDir(),"hill-climb-gameplay-export.zip");
        try(FileOutputStream output=new FileOutputStream(gameplayArchive)){
            new AndroidGameExporter(context,store,assets).write(gameplayProject,output);
        }
        assertTrue("Clean Hill Climb export should be available for the emulator APK build",
            gameplayArchive.length()>500_000);
    }
}
