package com.world2d.engine.export;

import android.content.Context;
import android.content.res.AssetManager;
import com.world2d.engine.assets.AssetLibrary;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import com.world2d.engine.data.ProjectStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports a compilable Android Gradle project with a separate native game Activity + author's actual data. */
public final class AndroidGameExporter {
    private final Context context;
    private final ProjectStore store;
    private final AssetLibrary library;
    private int count;
    public AndroidGameExporter(Context context,ProjectStore store,AssetLibrary library){
        this.context=context;this.store=store;this.library=library;
    }
    public void write(GameProject source,OutputStream output) throws IOException {
        String appId=source.data.optString("packageId");
        if(!appId.matches("[a-zA-Z_][\\w]*(\\.[a-zA-Z_][\\w]*){2,}"))
            throw new IOException("Set a valid package ID in Project Settings first.");
        GameProject project=source.snapshot();
        String name=project.name().replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");
        String orientation=project.data.optString("orientation","landscape");
        if(!orientation.equals("landscape")&&!orientation.equals("portrait"))orientation="unspecified";
        String version=project.data.optString("version","1.0.0");
        if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))version="1.0.0";
        try(ZipOutputStream zip=new ZipOutputStream(output)){
            text(zip,"settings.gradle","pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\n"+
                "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\n"+
                "rootProject.name = 'World2DGame'\ninclude ':app'\n");
            text(zip,"build.gradle","plugins { id 'com.android.application' version '8.7.3' apply false }\n");
            text(zip,"gradle.properties","org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nandroid.useAndroidX=false\n");
            text(zip,"gradle/wrapper/gradle-wrapper.properties","distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n");
            text(zip,"gradlew","#!/bin/sh\nAPP_HOME=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"+
                "exec java -classpath \"$APP_HOME/gradle/wrapper/gradle-wrapper.jar\" org.gradle.wrapper.GradleWrapperMain \"$@\"\n");
            asset(zip,"gradle/wrapper/gradle-wrapper.jar","export-runtime/gradle-wrapper.jar");
            String build="plugins { id 'com.android.application' }\nandroid {\n"+
                "    namespace 'com.world2d.engine'\n    compileSdk 35\n    defaultConfig {\n"+
                "        applicationId '"+appId+"'\n        minSdk 26\n        targetSdk 35\n        versionCode 1\n"+
                "        versionName '"+version+"'\n    }\n"+
                "    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }\n"+
                "}\n";
            text(zip,"app/build.gradle",build);
            text(zip,"app/src/main/AndroidManifest.xml","<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"+
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"+
                "<application android:allowBackup=\"false\" android:label=\""+name+"\" " +
                "android:icon=\"@drawable/world_icon\" android:theme=\"@style/WorldTheme\" " +
                "android:usesCleartextTraffic=\"false\"><activity android:name=\"com.world2d.engine.export.GameActivity\" " +
                "android:exported=\"true\" android:screenOrientation=\""+orientation+"\">"+
                "<intent-filter><action android:name=\"android.intent.action.MAIN\"/>"+
                "<category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter>"+
                "</activity></application></manifest>\n");
            text(zip,"app/src/main/res/values/styles.xml","<resources><style name=\"WorldTheme\" parent=\"android:style/Theme.Material.NoActionBar\">"+
                "<item name=\"android:colorAccent\">#B9EA88</item><item name=\"android:windowBackground\">#0E131D</item>"+
                "</style></resources>\n");
            text(zip,"app/src/main/res/drawable/world_icon.xml","<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "android:width=\"96dp\" android:height=\"96dp\" android:viewportWidth=\"96\" android:viewportHeight=\"96\">"+
                "<path android:fillColor=\"#172333\" android:pathData=\"M0,0h96v96h-96z\"/>"+
                "<path android:fillColor=\"#B9EA88\" android:pathData=\"M48,10L82,30v37L48,87 14,67V30z\"/>"+
                "</vector>\n");
            String[] sources={"data/J.java","data/GameProject.java","data/ProjectStore.java",
                "assets/AssetLibrary.java","runtime/ScriptVM.java","runtime/GameRuntime.java",
                "editor/SceneView.java","export/GameActivity.java"};
            for(String sourceFile:sources){String path="com/world2d/engine/"+sourceFile;
                asset(zip,"app/src/main/java/"+path,"export-runtime/"+path);}
            text(zip,"app/src/main/assets/game/project.json",J.pretty(project.data));
            JSONArray imported=project.assets();
            for(int i=0;i<imported.length();i++){
                JSONObject record=J.at(imported,i);String src=record.optString("src");
                File file=store.assetFile(project,src);
                if(file==null||!file.isFile())throw new IOException("Missing project asset: "+src);
                try(InputStream in=new FileInputStream(file)){entry(zip,"app/src/main/assets/game/"+src,in);}
            }
            addFolder(zip,context.getAssets(),"library","app/src/main/assets/library");
            text(zip,"README.md","# "+source.name()+" — Android game\n\n"+
                "This is a native Android Gradle project containing the exported 2D WORLD game data and runtime. " +
                "It is not an APK.\n\nInstall JDK 17 and Android SDK Platform 35 + Build Tools 35. " +
                "Open the folder in Android Studio and choose Build → Build APK(s). " +
                "Or run `./gradlew :app:assembleDebug`. " +
                "The debug APK is at app/build/outputs/apk/debug/app-debug.apk.\n\n"+
                "Do not distribute a debug-signed APK as a release. Configure your own signing key for release builds.\n"+
                "All game scenes, imported assets and bundled CC0 resources are in app/src/main/assets.\n"+
                "Package ID: "+appId+"; Version: "+version+". The Gradle wrapper needs SDK and plugin downloads on first use.\n");
        }
    }
    private void addFolder(ZipOutputStream zip,AssetManager assets,String path,String target) throws IOException {
        String[] children=assets.list(path);
        if(children==null)return;
        for(String child:children){String from=path+"/"+child,to=target+"/"+child;
            String[] nested=assets.list(from);
            if(nested!=null&&nested.length>0)addFolder(zip,assets,from,to);
            else asset(zip,to,from);
        }
    }
    private void asset(ZipOutputStream zip,String target,String source) throws IOException {
        try(InputStream in=context.getAssets().open(source)){entry(zip,target,in);}
    }
    private void text(ZipOutputStream zip,String path,String value) throws IOException {
        zip.putNextEntry(new ZipEntry(path));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    private void entry(ZipOutputStream zip,String path,InputStream in) throws IOException {
        if(++count>4500)throw new IOException("Too many resources in game export.");
        zip.putNextEntry(new ZipEntry(path));byte[] bytes=new byte[16384];int n;
        while((n=in.read(bytes))!=-1)zip.write(bytes,0,n);
        zip.closeEntry();
    }
}
