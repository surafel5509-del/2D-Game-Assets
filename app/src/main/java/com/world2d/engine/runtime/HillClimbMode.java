package com.world2d.engine.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import com.world2d.engine.data.GameProject;
import com.world2d.engine.data.J;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Built-in endless hill-climb game mode. It is intentionally part of the normal project/runtime format:
 * the terrain, vehicle, wheels, driver hitbox and pickups are editable scene nodes with typed components.
 */
public final class HillClimbMode {
    public static final String PREFS = "world2d-hill-climb-career-v1";
    public static final class VehicleSpec {
        public final String name, shortName;
        public final float wheelRadius, wheelBase, wheelOffset, mass, acceleration, maxSpeed;
        VehicleSpec(String name, String shortName, float wheelRadius, float wheelBase, float wheelOffset,
                    float mass, float acceleration, float maxSpeed) {
            this.name=name; this.shortName=shortName; this.wheelRadius=wheelRadius;
            this.wheelBase=wheelBase; this.wheelOffset=wheelOffset; this.mass=mass;
            this.acceleration=acceleration; this.maxSpeed=maxSpeed;
        }
    }
    private static final VehicleSpec[] VEHICLES = {
        new VehicleSpec("Classic 4×4", "JEEP", 20, 76, 18, 1.00f, 1.00f, 515),
        new VehicleSpec("Trail Bike", "BIKE", 15, 52, 17, 0.73f, 1.22f, 585),
        new VehicleSpec("Monster Truck", "MONSTER", 27, 88, 20, 1.42f, 0.92f, 470),
        new VehicleSpec("Moon Rover", "ROVER", 23, 84, 19, 1.12f, 1.04f, 520)
    };
    private static final int[] VEHICLE_PRICES = {0, 260, 720, 1550};
    private static final int MAX_UPGRADE = 5;
    private static final double PI2 = Math.PI * 2;

    private static final class Pickup {
        final String id, kind;
        final double x, y;
        Pickup(String id, String kind, double x, double y) {
            this.id=id; this.kind=kind; this.x=x; this.y=y;
        }
    }

    private final GameRuntime game;
    private final SharedPreferences prefs;
    private final List<Pickup> pickups = new ArrayList<>();
    private JSONObject car, head;
    private int seed=18473, vehicleIndex, engineLevel, suspensionLevel, tyreLevel, fuelLevel, fourWheelDrive;
    private int runCoins, runDiamonds, lastSavedBest;
    private float carX, carY, velocityX, velocityY, angle, angularVelocity, wheelRotation;
    private float fuel, boost=100, rpm=900, distance, bestDistance, nextPatternX;
    private boolean grounded, finished;

    HillClimbMode(Context context, GameRuntime game) {
        this.game=game;
        String packageId=game.project.data.optString("packageId","trailblazer").replaceAll("[^a-zA-Z0-9_.]","_");
        prefs=context.getApplicationContext().getSharedPreferences(PREFS+"-"+packageId,Context.MODE_PRIVATE);
        loadCareer();
        resetRun();
    }

    private void loadCareer() {
        vehicleIndex=clamp(prefs.getInt("vehicle",0),0,VEHICLES.length-1);
        engineLevel=clamp(prefs.getInt("engine",0),0,MAX_UPGRADE);
        suspensionLevel=clamp(prefs.getInt("suspension",0),0,MAX_UPGRADE);
        tyreLevel=clamp(prefs.getInt("tyres",0),0,MAX_UPGRADE);
        fuelLevel=clamp(prefs.getInt("fuelTank",0),0,MAX_UPGRADE);
        fourWheelDrive=clamp(prefs.getInt("fourWheelDrive",0),0,1);
        bestDistance=Math.max(0,prefs.getFloat("bestDistance",0));
        lastSavedBest=(int)bestDistance;
        for(int i=0;i<VEHICLES.length;i++)
            if(i==0&&!prefs.contains("unlocked_0"))prefs.edit().putBoolean("unlocked_0",true).apply();
    }

    /** Reset run-state after the runtime has restored a clean copy of the starting scene. */
    public void resetRun() {
        pickups.clear();
        car=null; head=null;
        seed=18473;
        JSONArray nodes=GameProject.nodes(game.scene);
        double furthest=0;
        for(int i=0;i<nodes.length();i++) {
            JSONObject node=J.at(nodes,i);
            JSONObject terrain=GameProject.component(node,"hillTerrain");
            if(terrain!=null)seed=terrain.optInt("seed",seed);
            if(GameProject.component(node,"hillVehicle")!=null)car=node;
            if(GameProject.component(node,"hillHead")!=null)head=node;
            JSONObject pickup=GameProject.component(node,"hillPickup");
            if(pickup!=null) {
                JSONObject t=J.obj(node,"transform");
                double px=t.optDouble("x"),py=t.optDouble("y");
                pickups.add(new Pickup(node.optString("id"),pickup.optString("kind","coin"),px,py));
                furthest=Math.max(furthest,px);
            }
        }
        if(car==null) return;
        JSONObject setup=GameProject.component(car,"hillVehicle");
        VehicleSpec spec=vehicle();
        carX=(float)setup.optDouble("startX",104);
        carY=(float)terrainHeight(carX,seed)-spec.wheelRadius-spec.wheelOffset;
        velocityX=velocityY=angle=angularVelocity=wheelRotation=0;
        grounded=false;finished=false;
        fuel=maxFuel();boost=100;rpm=900;distance=0;runCoins=runDiamonds=0;
        nextPatternX=(float)Math.max(1850,furthest+520);
        game.over=false;game.won=false;game.message="";game.messageTime=0;
        game.variables.put("health",3d);game.variables.put("score",0d);
        game.variables.put("inventory",0d);game.variables.put("distance",0d);
        game.camera( carX+115, carY+20, 0.98f );
        applyVehicleGeometry();syncNodes();syncStats();
        spawnAhead(carX+1700);
    }

    public void restart() { loadCareer(); resetRun(); }
    public int vehicleIndex() { return vehicleIndex; }
    public static int vehicleCount() { return VEHICLES.length; }
    public static String vehicleName(int index) { return VEHICLES[clamp(index,0,VEHICLES.length-1)].name; }
    public VehicleSpec vehicle() { return VEHICLES[clamp(vehicleIndex,0,VEHICLES.length-1)]; }
    public float fuel() { return fuel; }
    public float fuelFraction() { return Math.max(0,Math.min(1,fuel/Math.max(1,maxFuel()))); }
    public float boost() { return boost; }
    public float rpm() { return rpm; }
    public float distance() { return distance; }
    public float bestDistance() { return bestDistance; }
    public int totalCoins() { return Math.max(0,prefs.getInt("coins",0)); }
    public int totalDiamonds() { return Math.max(0,prefs.getInt("diamonds",0)); }
    public int runCoins() { return runCoins; }
    public int runDiamonds() { return runDiamonds; }
    public int engineLevel() { return engineLevel; }
    public int suspensionLevel() { return suspensionLevel; }
    public int tyreLevel() { return tyreLevel; }
    public int fuelLevel() { return fuelLevel; }
    public int fourWheelDriveLevel() { return fourWheelDrive; }
    public String vehicleName() { return vehicle().name; }
    public int stage() { return stageAt(carX,104); }
    public static String stageName(int stage) {
        switch(stage) {
            case 1:return "SUNSCORCH DESERT";
            case 2:return "FROSTLINE ARCTIC";
            case 3:return "LUNAR FRONTIER";
            default:return "GREEN HILLS";
        }
    }
    public static int stageAt(double worldX,double originX) {
        double meters=Math.max(0,(worldX-originX)/10d);
        if(meters>=2600)return 3;
        if(meters>=1450)return 2;
        if(meters>=650)return 1;
        return 0;
    }
    public static double terrainHeight(double x,int seed) {
        double progress=Math.max(0,Math.min(1,(x-50)/24000d));
        double broad=valueNoise(x,seed,460)*46;
        double rolling=valueNoise(x,seed+17,205)*25;
        double small=valueNoise(x,seed+41,91)*10;
        double ripple=Math.sin(x*0.018+seed*0.31)*5.2+Math.sin(x*0.0061+seed*0.13)*7.5;
        double rise=Math.min(38,Math.max(0,x-1400)*0.0012);
        return 373 + (broad+rolling+small+ripple)*(0.34+progress*0.95) + rise;
    }
    private static double valueNoise(double x,int seed,double wavelength) {
        double position=x/wavelength;
        long cell=(long)Math.floor(position);
        double fraction=position-cell;
        fraction=fraction*fraction*(3-2*fraction);
        double a=hashNoise(cell,seed),b=hashNoise(cell+1,seed);
        return a+(b-a)*fraction;
    }
    private static double hashNoise(long x,int seed) {
        long n=x*0x9E3779B97F4A7C15L+seed*0x632BE59BD9B4E019L;
        n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;
        n=(n^(n>>>27))*0x94D049BB133111EBL;
        n^=n>>>31;
        return ((n>>>11)*0x1.0p-52)-1.0;
    }

    /** Run one frame of spring-suspension, wheel traction, hill gravity and airborne pitch control. */
    public void tick(float delta) {
        if(finished||car==null)return;
        float dt=Math.min(0.04f,Math.max(0,delta));
        if(dt<=0)return;
        VehicleSpec spec=vehicle();
        JSONObject vehicleSetup=GameProject.component(car,"hillVehicle");
        double engineTorque=vehicleSetup==null?590:vehicleSetup.optDouble("engineTorque",590);
        double baseSuspension=vehicleSetup==null?9.5:vehicleSetup.optDouble("suspensionStiffness",9.5);
        double baseDamping=vehicleSetup==null?5:vehicleSetup.optDouble("suspensionDamping",5);
        boolean gas=game.actions.contains("right");
        boolean brake=game.actions.contains("left");
        boolean useBoost=game.actions.contains("up")&&boost>0.5f;
        int substeps=Math.max(1,Math.min(3,(int)Math.ceil(dt*60f)));
        float step=dt/substeps;
        for(int i=0;i<substeps;i++) {
            float gravity=stage()==3?390:930;
            double localSlope=Math.atan2(terrainHeight(carX+spec.wheelBase*0.42,seed)-
                terrainHeight(carX-spec.wheelBase*0.42,seed),spec.wheelBase*0.84);
            double traction=grounded?1.0+tyreLevel*0.09:0.22;
            double engine=spec.acceleration*(1+engineLevel*0.17)*(fourWheelDrive>0?1.12:1);
            if(gas)velocityX+=(float)(engine*engineTorque*traction*step);
            else if(brake)velocityX-=(float)(engine*engineTorque*(grounded&&velocityX>25?1.17:0.64)*traction*step);
            if(useBoost) {
                velocityX+=spec.acceleration*(grounded?730:360)*step;
                boost=Math.max(0,boost-33*step);
            } else boost=Math.min(100,boost+7.5f*step);
            velocityX+=(float)(Math.sin(localSlope)*gravity*(grounded?0.34:0.10)*step);
            float drag=grounded?0.58f+tyreLevel*0.045f:0.075f;
            velocityX*=Math.max(0,1-drag*step);
            velocityX=Math.max(-230,Math.min(spec.maxSpeed+engineLevel*24+(useBoost?115:0),velocityX));

            double pitchInput=(gas?-1:0)+(brake?1:0);
            if(grounded) {
                angularVelocity+=(float)(pitchInput*(0.35+spec.mass*0.08)*step);
                double suspension=baseSuspension+suspensionLevel*2.35;
                double diff=normalizeAngle(localSlope-angle);
                angularVelocity+=(float)(diff*suspension*step);
                angularVelocity*=Math.max(0,1-(baseDamping+suspensionLevel*0.4)*step);
            } else {
                angularVelocity+=(float)(pitchInput*4.1*step);
                angularVelocity*=Math.max(0,1-0.22f*step);
            }
            angularVelocity=Math.max(-3.3f,Math.min(3.3f,angularVelocity));
            angle=normalizeAngle((float)(angle+angularVelocity*step));

            velocityY+=gravity*step;
            carX+=velocityX*step;
            carY+=velocityY*step;
            grounded=false;
            int contacts=0;
            float lowestPenetration=0;
            for(int side=-1;side<=1;side+=2) {
                double offsetX=side*spec.wheelBase/2d;
                double wheelX=carX+Math.cos(angle)*offsetX-Math.sin(angle)*spec.wheelOffset;
                double wheelY=carY+Math.sin(angle)*offsetX+Math.cos(angle)*spec.wheelOffset;
                double penetration=wheelY+spec.wheelRadius-terrainHeight(wheelX,seed);
                if(penetration>0) {
                    contacts++;
                    lowestPenetration=Math.max(lowestPenetration,(float)penetration);
                    carY-=Math.min(penetration*0.67,25);
                }
            }
            if(contacts>0) {
                grounded=true;
                if(velocityY>0)velocityY=velocityY<190?0:-velocityY*0.11f;
                else velocityY*=0.45f;
                // Spring travel is converted into a small pitch response, rather than snapping to the hill.
                if(contacts==1)angularVelocity+=(float)(Math.signum(localSlope-angle)*lowestPenetration*0.0015);
            }
            float fuelLoad=gas?3.0f:brake?1.3f:0.95f;
            fuel=Math.max(0,fuel-step*fuelLoad*(1+Math.max(0,velocityX)/1100f));
        }
        wheelRotation=(wheelRotation+(float)(velocityX*dt/Math.max(10,spec.wheelRadius)*57.29578))%360f;
        distance=(float)Math.max(0,(carX-104)/10d);
        rpm=Math.max(850,Math.min(6900,930+Math.abs(velocityX)*7.1f+(gas?1180:0)+(useBoost?850:0)));
        spawnAhead(carX+1750);
        collectPickups();
        cleanupPickups();
        syncNodes();
        syncStats();
        persistBestIfNeeded();
        game.camera(carX+142,carY+14,Math.max(0.78f,0.99f-Math.min(0.14f,Math.abs(velocityX)/2800f)));

        double headX=carX+Math.sin(angle)*45;
        double headY=carY-Math.cos(angle)*45;
        if(headY+9>=terrainHeight(headX,seed)) {
            finish("CRASH!  Driver hit the ground.","gameover");
            return;
        }
        if(fuel<=0.01f) {
            finish("OUT OF FUEL", "failure");
            return;
        }
        if(distance>0 && Math.abs(carX)>5_000_000) {
            // Rebase very long runs before floating point precision can affect the contact solver.
            carX-=4_000_000;
            nextPatternX-=4_000_000;
            distance=(float)Math.max(0,(carX-104)/10d);
        }
    }

    private void finish(String reason,String sound) {
        if(finished)return;
        finished=true;game.over=true;game.won=false;game.message=reason;game.messageTime=game.elapsed;
        persistBest(true);game.sound(sound);game.changed();
    }
    private void persistBestIfNeeded() {
        if(distance>bestDistance) {
            bestDistance=distance;
            int bucket=(int)bestDistance/5;
            if(bucket>lastSavedBest/5)persistBest(false);
        }
    }
    private void persistBest(boolean force) {
        if(force || (int)bestDistance>lastSavedBest) {
            lastSavedBest=(int)bestDistance;
            prefs.edit().putFloat("bestDistance",bestDistance).apply();
        }
    }
    private void syncStats() {
        game.variables.put("health",3d);
        game.variables.put("score",(double)Math.round(distance));
        game.variables.put("distance",(double)distance);
        game.variables.put("fuel",(double)fuel);
        game.variables.put("coins",(double)totalCoins());
        game.variables.put("diamonds",(double)totalDiamonds());
        game.variables.put("runCoins",(double)runCoins);
        game.variables.put("runDiamonds",(double)runDiamonds);
        game.variables.put("bestDistance",(double)bestDistance);
        game.variables.put("engineLevel",(double)engineLevel);
        game.variables.put("suspensionLevel",(double)suspensionLevel);
        game.variables.put("tyreLevel",(double)tyreLevel);
    }
    private void syncNodes() {
        if(car==null)return;
        JSONObject t=J.obj(car,"transform");
        J.put(t,"x",carX);J.put(t,"y",carY);J.put(t,"rotation",Math.toDegrees(angle));
        JSONObject body=GameProject.component(car,"body");
        if(body!=null){J.put(body,"vx",velocityX);J.put(body,"vy",velocityY);}
        VehicleSpec spec=vehicle();
        JSONArray nodes=GameProject.nodes(game.scene);
        for(int i=0;i<nodes.length();i++) {
            JSONObject node=J.at(nodes,i);
            JSONObject wheel=GameProject.component(node,"hillWheel");
            if(wheel==null)continue;
            JSONObject wt=J.obj(node,"transform"),wc=GameProject.component(node,"hillWheel");
            int side=wc.optInt("side",-1);
            J.put(wt,"x",side*spec.wheelBase/2f);J.put(wt,"y",spec.wheelOffset);
            J.put(wt,"width",spec.wheelRadius*2);J.put(wt,"height",spec.wheelRadius*2);
            J.put(wc,"radius",spec.wheelRadius);J.put(wc,"spin",wheelRotation);
        }
        if(head!=null) {
            J.put(J.obj(head,"transform"),"x",carX+Math.sin(angle)*45);
            J.put(J.obj(head,"transform"),"y",carY-Math.cos(angle)*45);
        }
    }

    private void spawnAhead(double targetX) {
        int safety=0;
        while(nextPatternX<targetX&&safety++<8) {
            double base=nextPatternX;
            int pattern=(int)Math.round(base/480d);
            for(int i=0;i<5;i++) {
                double px=base+i*42;
                double py=terrainHeight(px,seed)-72-Math.sin(i*Math.PI/4)*16;
                addPickup("coin",px,py);
            }
            if(pattern%17==2)addPickup("fuel",base+260,terrainHeight(base+260,seed)-90);
            if(pattern%19==1)addPickup("diamond",base+185,terrainHeight(base+185,seed)-135);
            nextPatternX+=480;
        }
    }
    private void addPickup(String kind,double x,double y) {
        JSONObject node=GameProject.newNode(kind.equals("fuel")?"Fuel can":kind.equals("diamond")?"Diamond":"Hill coin",
            x,y,kind.equals("fuel")?42:34,kind.equals("fuel")?48:34);
        GameProject.addComponent(node,J.o("type","hillPickup","kind",kind));
        if(kind.equals("coin"))GameProject.addComponent(node,GameProject.sprite("builtin:prop-8"));
        else if(kind.equals("diamond"))GameProject.addComponent(node,GameProject.sprite("builtin:prop-9"));
        JSONObject collider=GameProject.collider(kind.equals("fuel")?34:30,kind.equals("fuel")?42:30,true);
        GameProject.addComponent(node,collider);
        J.put(node,"layer",7);GameProject.tag(node,kind);GameProject.nodes(game.scene).put(node);
        pickups.add(new Pickup(node.optString("id"),kind,x,y));
    }
    private void collectPickups() {
        for(int i=pickups.size()-1;i>=0;i--) {
            Pickup p=pickups.get(i);
            if(Math.hypot(p.x-carX,p.y-(carY-12))>49)continue;
            if(p.kind.equals("coin")) {
                runCoins++;prefs.edit().putInt("coins",totalCoins()+1).apply();
                game.sound("coin");game.burst((float)p.x,(float)p.y,"Sparkles");
            } else if(p.kind.equals("diamond")) {
                runDiamonds++;prefs.edit().putInt("diamonds",totalDiamonds()+1).apply();
                game.sound("level");game.burst((float)p.x,(float)p.y,"Healing");
            } else {
                fuel=maxFuel();game.sound("powerup");game.burst((float)p.x,(float)p.y,"Healing");
            }
            GameProject.removeNode(game.scene,p.id);
            pickups.remove(i);
            game.changed();
        }
    }
    private void cleanupPickups() {
        for(int i=pickups.size()-1;i>=0;i--) {
            Pickup p=pickups.get(i);
            if(p.x<carX-900) {
                GameProject.removeNode(game.scene,p.id);pickups.remove(i);
            }
        }
    }

    private float maxFuel() { return 105+fuelLevel*17; }
    private void applyVehicleGeometry() {
        if(car==null)return;
        VehicleSpec spec=vehicle();
        JSONObject setup=GameProject.component(car,"hillVehicle");
        J.put(setup,"vehicleIndex",vehicleIndex);J.put(setup,"wheelBase",spec.wheelBase);
        J.put(setup,"wheelRadius",spec.wheelRadius);J.put(setup,"mass",spec.mass);
        J.put(J.obj(car,"transform"),"width",spec.wheelBase+46);
        J.put(J.obj(car,"transform"),"height",spec.wheelOffset+46);
    }

    public boolean selectOrBuyVehicle(int index) {
        if(index<0||index>=VEHICLES.length)return false;
        String key="unlocked_"+index;
        if(!prefs.getBoolean(key,index==0)) {
            int price=VEHICLE_PRICES[index],coins=totalCoins();
            if(coins<price)return false;
            prefs.edit().putInt("coins",coins-price).putBoolean(key,true).putInt("vehicle",index).apply();
        } else prefs.edit().putInt("vehicle",index).apply();
        vehicleIndex=index;applyVehicleGeometry();
        carY=(float)terrainHeight(carX,seed)-vehicle().wheelRadius-vehicle().wheelOffset;
        velocityY=0;angle=0;angularVelocity=0;syncNodes();syncStats();game.changed();
        return true;
    }
    public boolean isVehicleUnlocked(int index) { return prefs.getBoolean("unlocked_"+index,index==0); }
    public int vehiclePrice(int index) { return VEHICLE_PRICES[clamp(index,0,VEHICLE_PRICES.length-1)]; }
    public String upgradeTitle(String upgrade) {
        int level=upgradeLevel(upgrade);
        String name=upgrade.equals("engine")?"Engine":upgrade.equals("suspension")?"Suspension":
            upgrade.equals("tyres")?"All-terrain tyres":upgrade.equals("fuel")?"Fuel tank":"4WD conversion";
        if(level>=upgradeMaximum(upgrade))return name+"  ·  MAX LEVEL";
        return name+"  ·  Level "+(level+1)+"  ·  "+upgradePrice(upgrade)+" coins";
    }
    public boolean exchangeDiamondForCoins() {
        int diamonds=totalDiamonds();
        if(diamonds<1)return false;
        prefs.edit().putInt("diamonds",diamonds-1).putInt("coins",totalCoins()+80).apply();
        syncStats();game.changed();return true;
    }
    public boolean buyUpgrade(String upgrade) {
        int level=upgradeLevel(upgrade),maximum=upgradeMaximum(upgrade);
        if(level>=maximum||totalCoins()<upgradePrice(upgrade))return false;
        int amount=upgradePrice(upgrade),coins=totalCoins()-amount;
        SharedPreferences.Editor edit=prefs.edit().putInt("coins",coins);
        switch(upgrade) {
            case "engine":engineLevel++;edit.putInt("engine",engineLevel);break;
            case "suspension":suspensionLevel++;edit.putInt("suspension",suspensionLevel);break;
            case "tyres":tyreLevel++;edit.putInt("tyres",tyreLevel);break;
            case "fuel":fuelLevel++;edit.putInt("fuelTank",fuelLevel);break;
            default:fourWheelDrive++;edit.putInt("fourWheelDrive",fourWheelDrive);break;
        }
        edit.apply();
        if(upgrade.equals("fuel"))fuel=maxFuel();
        syncStats();game.changed();return true;
    }
    private int upgradeLevel(String upgrade) {
        switch(upgrade) {
            case "engine":return engineLevel;
            case "suspension":return suspensionLevel;
            case "tyres":return tyreLevel;
            case "fuel":return fuelLevel;
            default:return fourWheelDrive;
        }
    }
    private int upgradeMaximum(String upgrade) { return upgrade.equals("fourWheelDrive")?1:MAX_UPGRADE; }
    private int upgradePrice(String upgrade) {
        int base=upgrade.equals("engine")?180:upgrade.equals("suspension")?150:
            upgrade.equals("tyres")?130:upgrade.equals("fuel")?210:520;
        return base*(upgradeLevel(upgrade)+1);
    }
    private static int clamp(int value,int min,int max) { return Math.max(min,Math.min(max,value)); }
    private static float normalizeAngle(double value) {
        while(value>Math.PI)value-=PI2;
        while(value<-Math.PI)value+=PI2;
        return (float)value;
    }
}
