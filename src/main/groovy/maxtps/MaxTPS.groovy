package cz.majncraft.maxtps
import java.nio.channels.*
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import net.minecraft.server.*;
import org.bukkit.event.*
import java.util.logging.Logger
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.*
import org.bukkit.event.*
import org.bukkit.entity.*
import org.bukkit.event.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.player.*
import org.bukkit.event.world.*
import org.bukkit.command.*
import org.bukkit.*
import org.bukkit.configuration.*
import java.lang.reflect.*
import java.lang.Math
import org.bukkit.craftbukkit.util.LongHashset
import java.util.concurrent.*

// summing average ticker
class Ticker implements Runnable {
        double lastlag = 20
        double lag = 20
        double abslag = 20 // lag = summing average, abslag = absolute lag for interval
        double lastnow
        long seconds
        long polls
        def plugin
        def lambda

        Ticker(MaxTPS p, double s, hook) {
            plugin = p
            seconds = s
            lambda = hook
            lastnow = System.currentTimeMillis()-(seconds*1000)
            plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this, 0, seconds*20);
        }

        @Override
        void run() {
            try {
            lambda()
            double now = System.currentTimeMillis()
            abslag = ((20000.0*seconds)/(now-lastnow))
            lag = (lastlag + abslag) / 2
            if (lag>20) lag=20
//            if (abslag>20) abslag=20
            lastlag = lag;
            lastnow = now;
            polls++;
            } catch (Exception e) {
                e.printStackTrace();
            };
        }
}

  
public class MaxTPS extends JavaPlugin {
    def desc, ver, ipbls, nickbls, bls, rbls
    def info;
    def ticker1, ticker60
    def chunkticker
    def spawners = [:]
    def ent2hash = [:]
    long adjustin = 0
    int loaded, entities, maxloaded, mindist, maxdist, nplayers, upint, downint, spawnerint, spawnerlimit, tpmonster,tpanimal, fakedist
    public int cb1 = 10, cb2 = 1;
    public int cs1 = 1, cs2 = 1;
    public int viewdist = 0
    int gcint = 120
    double growth = -1
    long memfree, memtotal, memused
    double stackradius, tpsthresh
    int nspawnerslimited = 0
    boolean faststack
    public int oreditor = 2
    def c = [
        b:ChatColor.BLACK,
        db:ChatColor.DARK_BLUE,
        dg:ChatColor.DARK_GREEN,
        dc:ChatColor.DARK_AQUA,
        dr:ChatColor.DARK_RED,
        dp:ChatColor.DARK_PURPLE,
        dy:ChatColor.GOLD,
        gr:ChatColor.GRAY,
        dg:ChatColor.DARK_GRAY,
        b:ChatColor.BLUE,
        g:ChatColor.GREEN,
        c:ChatColor.AQUA,
        r:ChatColor.RED,
        p:ChatColor.LIGHT_PURPLE,
        y:ChatColor.YELLOW,
        w:ChatColor.WHITE
    ]

    def hooks = []
    public void debug(s)
    {
//        info(s);
    }

    void onEnable() {
        desc = getDescription()
        def logger = Logger.getLogger("Minecraft")
        ver = desc.getVersion()
        info = { str -> logger.info("[MaxTPS] $str") }
        getServer().getPluginManager().registerEvents(new MaxTPSListener(this), this)
        getConfig().options().copyDefaults(true)
        saveConfig()
        countstuff()
        ticker1 = new Ticker(this, 1, {
                String msg = readjust(false)
                if (msg) info(msg)
        })
        ticker60 = new Ticker(this, 60, { writestats() });
        chunkticker = new ChunkTicker(this, 10);
        /*
        for (org.bukkit.World w in getServer().getWorlds()) {
            if (w.getName() != "world") continue;
            World ww = w.getHandle()
            Field ctl = ww.getClass().getSuperclass().getDeclaredField("chunkTickList")
            ctl.setAccessible(true)
            ctl.set(ww, new LongHashset())
//            ctl.set(ww, new LongHashset())
//                info("hook ok!")
//                info(ctl.get(ww))
//            } catch(Exception ex) { info("nofield :(") }
        }*/
        for (org.bukkit.World w in getServer().getWorlds()) {
            net.minecraft.server.WorldServer ww = w.getHandle()
            hooks.add(new WorldHook(this, ww, 8, 8))
        }
//        network = new NetworkIO(this, getServer().getHandle().server)
        info("$ver loaded, ${hooks.size()} worlds hooked.")
    }
    def network

    int chunk_gc() {
        int plists = 0
        int chunks = 0
        def provider

        for (h in hooks) {
            def mgr = h.mgr
            def pmap = mgr.playermap
            pmap.clone().each { k,v ->
                if (v.isEmpty()) {
                     h.mgr.playermap.remove(k)
                     plists++
                }
            }
            for (ch in h.provider.chunkList.clone()) {
                if (!pmap.containsKey(mgr.encode(ch.x, ch.z))) {
                    h.provider.queueUnload(ch.x, ch.z)
                    chunks++
                }
            }
        }

        info("Garbage collected ${plists} playerlists, ${chunks} chunks")
        return chunks
    }

/*
    def update_tiles() {
        int c = 0;
        for (h in hooks) {
            for (t in h.world.tileEntityList) {
                long key = h.mgr.encode(t.x>>4,t.z>>4)
                if (!(key in h.mgr.playermap)) continue;
                def pkt = t.d()
                if (!pkt) continue;
                c++;
                // find players for this tile
                for (p in h.mgr.playermap[key])
                        p.netServerHandler.sendPacket(pkt);
            }
        }
        return c;
    }
    int tileupdate = 4*/

    String readjust(force) {
        adjustin++

/*
        if (adjustin % tileupdate == 0)
            update_tiles();
*/
        // try to up viewdist
        if (force || ((adjustin % gcint==0))) {
            if (adjustin > 120)
                chunk_gc();
        }


        // recount spawner-limiting
        if (force || adjustin % spawnerint==0) {
            nspawnerslimited = 0
            def newhash = [:]
            for (org.bukkit.World w in getServer().getWorlds()) {
                w.setTicksPerMonsterSpawns(tpmonster)
                w.setTicksPerAnimalSpawns(tpanimal)
                def spw = spawners[w.getName()] = [:]
                for (org.bukkit.entity.Entity e in w.getEntities()) {
                    if (!(e instanceof LivingEntity)) continue
                    org.bukkit.entity.LivingEntity le = e
                    int entid = le.getEntityId()
                    if (!ent2hash.containsKey(entid)) continue
                    long hash = ent2hash[entid]
                    newhash[entid] = hash
                    spw[hash] = spw.get(hash, 0)+1
                    nspawnerslimited += spw.size()
                }
            }
            ent2hash = newhash
        }

        // try to up viewdist
        if (force || ((adjustin % upint==0) && (ticker60.lag > tpsthresh))) {
            countstuff();
            if ((loaded < maxloaded || viewdist < mindist || (ticker60.lag > tpsthresh)) && viewdist < maxdist) {
                for (h in hooks) h.mgr.setViewDist(viewdist+1);
                viewdist++;
                return "Auto-Incremented viewdist ${viewdist-1} -> ${viewdist}"
            }
        }

        // try to down viewdist
        if (force || ((adjustin % downint==0) && (ticker60.lag < tpsthresh))) {
            writestats();
            if ((loaded > maxloaded || viewdist > maxdist || (ticker60.lag < tpsthresh)) && viewdist > mindist) {
                for (h in hooks) h.mgr.setViewDist(viewdist-1);
                viewdist--;
                return "Auto-Decremented viewdist ${viewdist+1} -> ${viewdist}"
            }
        }
        return ""
    }
         
    void onDisable() {
        def players = []
        for (h in hooks)
            players += h.unhook();
        for (p in players) {
            def mgr = getmgr(p)
            mgr.migrating = mgr.killed = true
            mgr.reader.interrupt()
            mgr.writer.interrupt()
        }
        Thread parent = Thread.currentThread();
        Thread.start {
//            parent.suspend();
            try {
//                Thread.sleep(100);
            for (p in players) {
                try {
                    def mgr = getmgr(p)
                    debug("${mgr.reader} ${mgr.writer}")
                    if (mgr.reader.isAlive() || mgr.writer.isAlive()) {
                        mgr.killed = mgr.migrating = false;
                        info("Failed to properly shutdown old handler for player ${p.name}, trying to continue anyway...")
                        continue
                    }
                    def old = mgr.oldmanager
                    set_field(old, "x", 0) // counter
                    set_field(old, "l", true) // alive
                    set_field(old, "input", mgr.dinput);
                    set_field(old, "output", mgr.doutput);
                    mgr.hiprio.clear()
                    mgr.loprio.clear()
    //                mgr.oldrecvq.addAll(mgr.recvq) # problems with pktflying?
                    setnm(p, mgr.oldmanager)
                    //p.netServerHandler.networkManager.a(mgr.oldhandler);
                    // it should be alive from now on
                    def nr = new NetworkReaderThread(old, " read thread")
                    def nw = new NetworkWriterThread(old, " write thread")
                    set_field(old,"s", nr);
                    set_field(old,"r", nw);
                    for (Packet pkt in mgr.sendq) old.queue(pkt);
                    for (Packet pkt in mgr.chunksendq) old.queue(pkt);
                    nr.start()
                    nw.start()
                } catch (Exception ex) {
                    ex.printStackTrace();
                    info("Failed to restore old handler for player ${p.name}, restart the server if this happens a lot!")
                }
            }
            } catch(Exception ex) {
                ex.printStackTrace();
                info("v$ver unloaded, but something went horribly wrong")
            }
//            parent.resume();
            info("v$ver unloaded")
        };
    }


    // write stats file
    void writestats() {
        String fn = getConfig().getString("stats-file","")
        if (fn == "")
             return
        File f = new File(getDataFolder(),fn)
        f.setText("${ticker60.lag} ${viewdist} ${nplayers} ${entities} ${loaded} ${maxloaded} ${memfree} ${memused} ${ticker60.abslag} ${ticker1.lag} ${ticker1.abslag} ${fakedist}\n")
    }

    long monsterhash(LivingEntity ent) {
        org.bukkit.Chunk ch = ent.getLocation().getChunk()
        int x = ch.getX()
        int z = ch.getZ()
        long k = ((long) x + 2147483647L) | (((long) z + 2147483647L) << 32)
        return k
    }

    void loadconf() {
        def cfg = getConfig()
        //tileupdate = cfg.getInt("tile-update-interval", 4)
        upint = cfg.getInt("up-interval", 60)
        downint = cfg.getInt("down-interval", 30)
        spawnerint = cfg.getInt("spawner-check-interval", 30)
        if (viewdist == 0)
            viewdist = cfg.getInt("view-dist",6)
        mindist = cfg.getInt("min-dist",4)
        gcint = cfg.getInt("chunk-gc-interval",120)
        maxdist = cfg.getInt("max-dist",8)
        fakedist = cfg.getInt("virtual-dist",16)
        spawnerlimit = cfg.getInt("mobs-per-spawner", 30)
        stackradius = cfg.getDouble("stack-radius",3.5)
        maxloaded = getConfig().getInt("chunk-limit")
        faststack = getConfig().getBoolean("faster-stacking", false)
        tpmonster = getConfig().getInt("ticks-per-monster", 1)
        tpanimal = getConfig().getInt("ticks-per-animal", 400)
        tpsthresh = getConfig().getDouble("tps-threshold", 18.0)
        oreditor = getConfig().getInt("oreditor", 2)

        def convert = { x ->
            double a = x/20, b = 1;
            double frac
            

            for (int attempts = 0; attempts < 10; attempts++) {
                frac = (Math.ceil(a)-a);
                if (frac>0.01) {
//                    debug(frac);
                    b *= (a+frac)/a;
                    a += frac;
                    frac = Math.ceil(b)-b;
                    if (frac>0.01) {
                        a *= (b+frac)/b;
                        b += frac;
                    }
                    continue;
                }
                break;
            }
            debug("res $a $b")
            return [(int)Math.floor(a), (int)Math.floor(b)]
        }

        (cb1, cb2) = convert(getConfig().getDouble("chunk-rate", 20));
        (cs1, cs2) = convert(getConfig().getDouble("chunk-rate-burst", 200));
    }

    // count stats
    void countstuff() {
        loadconf()
        memfree = Runtime.getRuntime().freeMemory() >> 20;
        memtotal = Runtime.getRuntime().totalMemory() >> 20;
        memused = memtotal - memfree;
        loaded = 0
        entities = 0
        nplayers = 0
        growth = -1
        for (w in getServer().getWorlds()) {
            loaded += w.getLoadedChunks().size()
            entities += w.getEntities().size()
            int wplayers = w.getPlayers().size()
            nplayers += wplayers
            try {
                int optimalChunks = w.getOptimalGrowthChunks()
                if (optimalChunks<=0) continue;
                int chunksPerPlayer = Math.min(200, Math.max(1, (int)(((optimalChunks - wplayers) / (double)wplayers) + 0.5)));
                float modifiedOdds = Math.min(100, ((chunksPerPlayer + 1) * 100F) / 255F);
                if (growth < 0) {
                    growth = modifiedOdds
                } else {
                    growth = (growth + modifiedOdds)/2
                }
            } catch (Exception ex) {}
        }
    }

    int vdmax = 32

    void dumpdebug(spam) {

    }

    // plugin commands
    boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        def cl = cmd.getName().toLowerCase()
        def spam = { msg -> sender.sendMessage("$msg"); return true }
        def cfg = getConfig()
        String msg="??"

        try {
        switch (cl) {
            case "lag":
            case "tps":
                if (args.length==1 && args[0] == "debug") {
                    dumpdebug(spam);
                    return true;
                }

                countstuff()
                def bar = { n -> ("#".multiply(n>20?20:n)+c.dg).padRight(22, "_") }
                spam("${c.w}There are ${c.r}${entities}${c.w} entities, ${c.g}${nplayers}${c.w} players, ${c.r}${nspawnerslimited}${c.w} spawnermobs")
                if (growth>0)
                    spam("${c.w}[${c.g}${bar(growth*20/100)}${c.w}]${c.gr}/growth odds ${c.g}${(int)(growth)}${c.w}%")
                spam("${c.w}[${c.r}${bar(memused*20/memtotal)}${c.w}]${c.gr}/Mem ${c.w}u=${c.r}${memused}M ${c.w}f=${c.g}${memfree}M ${c.w}t=${c.y}${memtotal}M")
                spam("${c.w}[${c.r}${bar(loaded*20/maxloaded)}${c.w}]${c.gr}/Chunks ${c.g}${loaded}${c.w}/${c.r}${maxloaded}${c.w} (${c.y}${(int)(loaded*100/maxloaded)}%${c.w})")
                spam(sprintf("${c.w}[${c.g}%s${c.w}]${c.gr}/1min TPS${c.w} ${c.g}%.2f ${c.w}avg, ${c.y}%.2f${c.w} abs", bar(ticker60.lag), ticker60.lag, ticker60.abslag))
                spam(sprintf("${c.w}[${c.g}%s${c.w}]${c.gr}/1sec TPS${c.w} ${c.g}%.2f ${c.w}avg, ${c.y}%.2f${c.w} abs", bar(ticker1.lag), ticker1.lag, ticker1.abslag))
                spam("${c.w}[${c.g}${bar((viewdist+1)*20/(maxdist+20))}${c.w}]${c.gr}/VDist ${c.g}${viewdist} ${c.w}(${c.r}${(int)(viewdist*viewdist*3.14)}${c.w} chunks) ${c.r}s:${mindist}${c.w}/${c.g}${maxdist}${c.w}/${c.g}c:${fakedist}")
                return true
            case "viewdist":
                countstuff()
                int vd=args[0].toInteger()
                if (vd < 1 || vd > vdmax)
                    return spam("view-distance must be 1-${vdmax} interval")
                viewdist = vd
                cfg.set("view-dist",vd)
                for (h in hooks) h.mgr.setViewDist(vd);
                return spam("view-distance forced to ${vd}")
            case "vdist":
                int vd=args[0].toInteger()
                if (vd > vdmax || vd < viewdist)
                        return spam("virtual view-distance must be ${viewdist}-${vdmax} interval")
                cfg.set("virtual-dist",vd)
                for (h in hooks) h.mgr.setFakeDist(vd);
                msg="Virtual view-distance set to ${vd}";
                break;
            case "maxdist":
                if (args.length==0)
                    return spam("Maximum view-distance is is ${maxdist}")
                int vd=args[0].toInteger()
                if (vd > vdmax || vd < mindist)
                        return spam("view-distance must be ${mindist}-${vdmax} interval")
                cfg.set("max-dist",vd)
                msg="Distance range is now ${mindist}-${vd}"
                break;
            case "upinterval":
                if (args.length==0)
                    return spam("View-distance increase interval is ${downint}")
                int ui=args[0].toInteger()
                assert ui>0
                cfg.set("up-interval",ui)
                msg="View-distance upscaling interval set to ${ui} seconds"
                break;
            case "downinterval":
                if (args.length==0)
                    return spam("View-distance decrease interval is ${downint}")
                int ui=args[0].toInteger()
                assert ui>0
                cfg.set("down-interval",ui)
                msg="View-distance downscaling interval set to ${ui} seconds"
                break;
            case "spawnerinterval":
                if (args.length==0)
                    return spam("Spawner check interval ${spawnerint}")
                int ui=args[0].toInteger()
                assert ui>0
                cfg.set("spawner-check-interval",ui)
                msg="Spawners will be checked for limits every ${ui} seconds"
                break;
            case "mindist":
                if (args.length==0)
                    return spam("Minimum view-distance is ${mindist}")
                int vd=args[0].toInteger()
                if (vd < 1 || vd > maxdist)
                        return spam("view-distance must be 1-${maxdist} interval")
                cfg.set("min-dist",vd)
                msg="Distance range is now ${vd}-${maxdist}"
                break
            case "chunklimit":
                if (args.length==0)
                    return spam("Chunklimit is ${maxloaded}")
                int chl=args[0].toInteger()
                if (chl < 100)
                    return spam("at least 100 chunks is necessary")
                cfg.set("chunk-limit", chl)
                msg="Chunk limit is now ${chl}"
                break
            case "chunkgc":
                if (args.length==0)
                    return spam("Garbage collected ${chunk_gc()} chunks")
                int chl=args[0].toInteger()
                assert chl>0
                cfg.set("chunk-gc-interval", chl)
                msg="Chunk gc interval is now ${chl}"
                break
            case "mtreload":
                msg="MaxTPS config reloaded"
                reloadConfig()
                break
            case "stacking":
                if (args.length==0)
                    return spam("Stacking radius is ${spawnerlimit}")
                double sr=args[0].toDouble()
                msg=sprintf("Stacking radius is now %.2f",sr)
                cfg.set("stacking-radius",sr)
                break
            case "spawnerlimit":
                if (args.length==0)
                    return spam("Spawner limit is ${spawnerlimit}")
                int sl=args[0].toInteger()
                assert sl>=0
                msg="Spawner limit is now ${sl}"
                cfg.set("mobs-per-spawner",sl)
                break;
            case "tpmonster":
                if (args.length==0)
                    return spam("Ticks per monster spawn ${tpmonster}")
                int sl=args[0].toInteger()
                assert sl>0
                msg="Monsters now spawn every ${sl} tick"
                cfg.set("ticks-per-monster",sl)
                break;
            case "tpanimal":
                if (args.length==0)
                    return spam("Ticks per animal spawn ${tpanimal}")
                int sl=args[0].toInteger()
                assert sl>0
                msg="Monsters now spawn every ${sl} tick"
                cfg.set("ticks-per-animal",sl)
                break;
            case "threshold":
                if (args.length==0)
                    return spam(sprintf("TPS threshold is now %.2f",tpsthresh))
                double sl=args[0].toInteger()
                assert sl>=0
                msg=sprintf("view-dist will increase if TPS is above %.2f, decrease if below",sl)
                cfg.set("tps-threshold",sl)
                break;
                /*
            case "tileupdate":
yy                if (args.length==0)
                    return spam(sprintf("Updated %d tiles",update_tiles()))
                int sl=args[0].toInteger()
                assert sl>0
                msg="Tile updates now every ${sl} seconds"
                cfg.set("tile-update-interval",sl)
                break;*/
            case "oreditor":
                if (args.length==1) {
                    oreditor=args[0].toInteger();
                }
                msg="Oreditor is ${oreditor>0?("enabled with radius "+oreditor):"disabled"}";
                break;
            case "chunkrate":
                if (args.length!=0) {
                assert args.length == 2
                double d0, d1
                d0 = args[0].toDouble()
                d1 = args[1].toDouble()
                assert d0 > 0 && d1 > 0
                cfg.set("chunk-rate", d0)
                cfg.set("chunk-rate-burst", d1)
                saveConfig()
                reloadConfig()
                countstuff()
                }
                msg=sprintf("Current chunk rates : normal=%.2f/s (%d packets/%d ticks), burst=%.2f/s (%d packets/%d ticks)",(double)cb1*20/(double)cb2,cb1,cb2,(double)cs1*20/(double)cs2,cs1,cs2);
                break;
        }
        saveConfig()
        reloadConfig()
        countstuff()
        if (cl=="mindist" || cl=="maxdist" || cl=="chunklimit") {
            String m2=readjust(true);
            if (m2) spam(m2);
        }
        return spam(msg)
        }
        catch (java.lang.ArrayIndexOutOfBoundsException ex) { return false; }
        catch (Error ex) { return false; }
    }

    def fields = [:]

    Field fetch_field(obj,name,typ) {
        Class kls
        if (typ != 2) {
            kls = obj.getClass();
            if (typ==1) // 1 = super
                kls = kls.getSuperclass()
        } else { // named
            kls = Class.forName("net.minecraft.server."+obj);
        }
        if (!fields.containsKey(kls)) {
            fields[kls] = [:]
        }
        if (!fields[kls].containsKey(name)) {
            fields[kls][name] = kls.getDeclaredField(name)
            fields[kls][name].setAccessible(true)
        }
        return fields[kls][name]
    }

    public void set_superfield(Object obj, String name, Object val)
    {
        fetch_field(obj, name, 1).set(obj,val);
    }
    public Object get_superfield(Object obj, String name)
    {
        return fetch_field(obj, name, 1).get(obj);
    }

    public void set_field(Object obj, String name, Object val) {
        fetch_field(obj, name, 0).set(obj,val);
    }
    public Object get_field(Object obj, String name) {
        return fetch_field(obj, name, 0).get(obj)
    }

    
    public void set_hard(Object obj, String kls, String name, Object val) {
        fetch_field(kls, name, 2).set(obj, val);
    }
    public Object get_hard(Object obj, String kls, String name) {
        return fetch_field(kls, name, 2).get(obj);
    }

    public void setnm(EntityPlayer p, Object mgr) {
        /*
        if (p.netServerHandler.hasProperty("nshInstance")) {
            info("setting: ${p.netServerHandler}")
            p.netServerHandler.networkManager = (NetworkManager)mgr;
        } else p.netServerHandler.networkManager = mgr;*/
        if (p.netServerHandler.hasProperty("nshInstance")) { // necessary for ofc
            p.netServerHandler.nshInstance.networkManager = mgr;
        }

        set_hard(p.netServerHandler, "NetServerHandler", "networkManager", mgr);
        p.netServerHandler.networkManager = mgr;
//        ((NetServerHandler)p.netServerHandler.networkManager) = (NetworkManager)mgr;
        /*
        try {
            set_field((Object)p.netServerHandler, "networkManager", (Object)mgr);
        } catch (Exception ex) {
            set_superfield((Object)p.netServerHandler, "networkManager", (Object)mgr);
            p.netServerHandler.nshInstance.networkManager = mgr;
        }
//        p.netServerHandler.networkManager = (NetworkManager)mgr;*/
    }

    public NetServerHandler getnsh(EntityPlayer p) {
/*        if (p.netServerHandler.hasProperty("nshInstance")) {
            return p.netServerHandler.nshInstance;
        }*/
        return p.netServerHandler;
    }


    public void hook_network(EntityPlayer p) {
/*        if (!(get_hard(p.netServerHandler, "NetServerHandler", "networkManager") instanceof MyNetworkManager)) {
            NetworkManager mgr = get_hard(p.netServerHandler, "NetServerHandler","networkManager")
            set_hard(p.netServerHandler, "NetServerHandler", "networkManager", MyNetworkManager.alloc(mgr, this, p))
        }*/
        if (!(getnsh(p).networkManager instanceof MyNetworkManager)) {
            NetworkManager mgr = getnsh(p).networkManager;
            setnm(p, MyNetworkManager.alloc(mgr, this, p));
        }

    }
    public MyNetworkManager getmgr(EntityPlayer p) {
        return (MyNetworkManager)p.netServerHandler.networkManager;
    }

}


class MaxTPSListener implements Listener {
    def plugin

    MaxTPSListener(p) {
        plugin = p
    }

    @EventHandler
    void onCreatureSpawn(CreatureSpawnEvent e) {
        SpawnReason wat = e.getSpawnReason()
        if (wat!=SpawnReason.SPAWNER) return; // nothing for us to meddle in ... YET?!
        org.bukkit.entity.LivingEntity ent = e.getEntity()
        long hash = plugin.monsterhash(ent)
        String wname = ent.getWorld().getName()
        def spawners = plugin.spawners.get(wname, [:])
        if (spawners.get(hash, 0) > plugin.spawnerlimit) {
            e.setCancelled(true); // cancel the spawn!
            return
        }
        plugin.ent2hash[ent.getEntityId()] = hash
        spawners[hash] = spawners.get(hash, 0)+1
        plugin.spawners[wname] = spawners
    }

    @EventHandler
    void onItemSpawn(ItemSpawnEvent e) {
        // dont bother if radius is 0
        if (plugin.stackradius <= 0) return;
        double r = plugin.stackradius;
        EntityItem ne = e.getEntity().getHandle() // new entity, ce = current entity
        // iterate entities only in given height slice
        
        int max = ne.itemStack.getMaxStackSize();
        boolean fast = plugin.faststack
        List elist = e.getEntity().getWorld().getHandle().getChunkAtWorldCoords((int)Math.floor(ne.locX),(int)Math.floor(ne.locZ)).entitySlices[(int)Math.floor(ne.locY/16.0)];
        if (!fast) elist = elist.clone();
        for (Entity le in elist) {
            if (!(le instanceof EntityItem)) continue;
            EntityItem ce = le
            if (!ce.dead // must be alive
                && ce.itemStack.id == ne.itemStack.id && ce.itemStack.getData() == ne.itemStack.getData() && // of the same kin
                Math.abs(ce.locX-ne.locX)<=r && Math.abs(ce.locY-ne.locY)<=r && Math.abs(ce.locZ-ne.locZ)<=r // within radius
                ) {
                if (fast) {
                    int toadd=Math.min(ne.itemStack.count, max-ce.itemStack.count)
                    ce.itemStack.count += toadd;
                    ne.itemStack.count -= toadd;
                    if (ne.itemStack.count <= 0) {
                        e.setCancelled(true); // nothing left, all merged - cancel
                    }
                } else {
                    int toadd=Math.min(ce.itemStack.count, max-ne.itemStack.count)
                    ne.itemStack.count += toadd;
                    ce.itemStack.count -= toadd;
                    if (ce.itemStack.count <= 0) {
                        ce.die() // slower, but makes stacking more obvious
                    }
                }
                return;
            }
        }
    }

    // yeah, bukkit goes full-retard on this one
    @EventHandler
    void onEntityDeath(EntityDeathEvent e) {
        if (plugin.stackradius <= 0) return;
        double r = plugin.stackradius;
        int exp = e.getDroppedExp()
        if (exp) {
            e.setDroppedExp(0)
            World w = e.getEntity().getWorld().getHandle()
            EntityLiving ne = e.getEntity().getHandle()
            for (Entity le in w.getChunkAtWorldCoords((int)Math.floor(ne.locX),(int)Math.floor(ne.locZ)).entitySlices[(int)Math.floor(ne.locY/16.0)]) {
                if (!(le instanceof EntityExperienceOrb)) continue;
                EntityExperienceOrb ce = le
                if (!ce.dead && // alive orb
                    Math.abs(ce.locX-ne.locX)<=r && Math.abs(ce.locY-ne.locY)<=r && Math.abs(ce.locZ-ne.locZ)<=r) { // within radius
                    ce.value += exp;
                    return;
                }
            }
            // uhoh, no exporbs found
            w.addEntity(new EntityExperienceOrb(w, ne.locX, ne.locY, ne.locZ, exp))
        }
    }

    @EventHandler
    void onPlayerTeleport(PlayerTeleportEvent e) {
        def p = e.getPlayer().getHandle()
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onPlayerJoinEvent(PlayerJoinEvent e) {
        def p = e.getPlayer().getHandle()
//        debug("${p.netServerHandler.nshInstance.networkManager} @@ ${p.netServerHandler.networkManager}")
    }
}



class WorldHook {
    def plugin
    def loader
    def provider
    def mgr
    WorldServer world
    PriorityBlockingQueue<ChunkRequest> queue
    LinkedBlockingQueue<ChunkRequest> queue2
    Field rle
    ChunkRegionLoader rl

    public Chunk nbt2chunk(World world, int x, int z, NBTTagCompound nbttagcompound) {

        int i = x;
        int j = z;
        nbttagcompound = nbttagcompound.getCompound("Level");
        Chunk chunk = new Chunk(world, i, j);

        chunk.heightMap = nbttagcompound.getIntArray("HeightMap");
        chunk.done = nbttagcompound.getBoolean("TerrainPopulated");
        NBTTagList nbttaglist = nbttagcompound.getList("Sections");
        byte b0 = 16;
        ChunkSection[] achunksection = new ChunkSection[b0];

        for (int k = 0; k < nbttaglist.size(); ++k) {
            NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist.get(k);
            byte b1 = nbttagcompound1.getByte("Y");
            ChunkSection chunksection = new ChunkSection(b1 << 4);

            chunksection.a(nbttagcompound1.getByteArray("Blocks"));
            if (nbttagcompound1.hasKey("Add")) {
                chunksection.a(new NibbleArray(nbttagcompound1.getByteArray("Add"), 4));
            }

            chunksection.b(new NibbleArray(nbttagcompound1.getByteArray("Data"), 4));
            chunksection.d(new NibbleArray(nbttagcompound1.getByteArray("SkyLight"), 4));
            chunksection.c(new NibbleArray(nbttagcompound1.getByteArray("BlockLight"), 4));
            chunksection.d();
            achunksection[b1] = chunksection;
        }

        chunk.a(achunksection);
        if (nbttagcompound.hasKey("Biomes")) {
            chunk.a(nbttagcompound.getByteArray("Biomes"));
        }
/*
        NBTTagList nbttaglist1 = nbttagcompound.getList("Entities");

        if (nbttaglist1 != null) {
            for (int l = 0; l < nbttaglist1.size(); ++l) {
                NBTTagCompound nbttagcompound2 = (NBTTagCompound) nbttaglist1.get(l);
                Entity entity = EntityTypes.a(nbttagcompound2, world);

                chunk.m = true;
                if (entity != null) {
                    chunk.a(entity);
                }
            }
        }

        NBTTagList nbttaglist2 = nbttagcompound.getList("TileEntities");

        if (nbttaglist2 != null) {
            for (int i1 = 0; i1 < nbttaglist2.size(); ++i1) {
                NBTTagCompound nbttagcompound3 = (NBTTagCompound) nbttaglist2.get(i1);
                TileEntity tileentity = TileEntity.c(nbttagcompound3);

                if (tileentity != null) {
                    chunk.a(tileentity);
                }
            }
        }*/
        chunk.i();
        return chunk;
    }

    def unhook() {
        world.manager = new PlayerManager(world.server.console, world.dimension, world.server.console.propertyManager.getInt("view-distance", 10))
        def myplayers = mgr.managedPlayers.clone()
        for (p in myplayers) {
            int x = ((int) p.d) >> 4;
            int z = ((int) p.e) >> 4;
            mgr.removePlayer(p, true)
            // this will prevent sending em unmapchunk for their own stance
            p.playerChunkCoordIntPairs.add(new ChunkCoordIntPair(x,z))
            world.manager.addPlayer(p)
//            plugin.fix_sendq(p, -plugin.sendq)
            // prevent receiving of first chunk
            for (coord in p.chunkCoordIntPairQueue) {
                if (coord.x==x && coord.z==z) {
                    p.chunkCoordIntPairQueue.remove(coord)
                    break;
                }
            }
//            plugin.set_field(p.netServerHandler, "checkMovement", true);
        }

        // restore region loader
        plugin.set_field(provider, "e", rl)
        //rle.set(provider, rl)
        return myplayers
    }

    WorldHook(pl, net.minecraft.server.WorldServer w, int nthreads, int nthreads2) {
        if (w.manager instanceof MyPlayerManager) return
        plugin = pl
        world = w
        provider = w.chunkProvider
        def gf = plugin.&get_field
        def sf = plugin.&set_field

        /*
        rle = provider.getClass().getDeclaredField("e")
        rle.setAccessible(true)
        rl = rle.get(provider)
        Class rlc = rl.getClass()
        Field a = rlc.getDeclaredField("a")
        Field b = rlc.getDeclaredField("b")
        Field c = rlc.getDeclaredField("c")
        Field d = rlc.getDeclaredField("d")
        a.setAccessible(true);
        b.setAccessible(true);
        c.setAccessible(true);
        d.setAccessible(true);
        def mcl = new MyChunkRegionLoader(a.get(rl), b.get(rl), c.get(rl), d.get(rl))
        rle.set(provider, mcl)*/
        rl = gf(provider, "e")
        def mcl = new MyChunkRegionLoader(gf(rl, "a"), gf(rl, "b"), gf(rl, "c"), gf(rl, "d"))
        sf(provider, "e", mcl)


        mgr = new MyPlayerManager(w.server.console, w.dimension, pl.viewdist, pl.fakedist, pl)

        // iterate players and move it to our mgr
        List players = w.manager.managedPlayers.clone()
        for (p in players) {
            pl.hook_network(p)
            int x = ((int) p.d) >> 4;
            int z = ((int) p.e) >> 4;
            // this will prevent sending em unmapchunk for their own stance
            p.playerChunkCoordIntPairs.remove(new ChunkCoordIntPair(x,z));
            w.manager.removePlayer(p)
            p.playerChunkCoordIntPairs.clear()
            p.chunkCoordIntPairQueue.clear()
            w.chunkProviderServer.getChunkAt(x, z) // this will cancel queued unload
//            plugin.fix_sendq(p, plugin.sendq)
            mgr.addPlayer(p, true)
        }


        w.manager = mgr

        queue = mgr.queue
        queue2 = mgr.queue2
        // near loading code
//        plugin.info("starting ${nthreads} waiters");
        for (int i = 0; i < nthreads; i++) Thread.start {
            try {
                while (1) {
//                    Thread.sleep(1000);
                    ChunkRequest req = queue.take();
//                    plugin.debug("took req ${req.prio} ${req.x*16} ${req.z*16}")
                    req.nbt = mcl.load_async(world, req.x, req.z)
                    req.world = world
                    req.loader = mcl
                    plugin.chunkticker.queue.add(req);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        // far loading code
        for (int i = 0; i < nthreads2; i++) Thread.start {
            try {
                while (1) {
//                    Thread.sleep(1000);
                    ChunkRequest req = queue2.take();
//                    plugin.info("took req ${req.prio}")
                    def nbt = mcl.load_async(world, req.x, req.z)
                    if (nbt) {
                        Chunk ch = nbt2chunk(world, req.x, req.z, nbt)
                        req.send_lowprio(ch);
                    }
//                    plugin.info("finished ${req.prio}")
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

}

/*
class NetworkIO {
    def plugin
    def serversk
    Selector select
    NetworkIO(pl, server) {
        def oldsk = pl.get_field(server.networkListenThread, "d")
        pl.info("Stopping ${oldsk}")
        oldsk.close()
        plugin = pl
        select = Selector.open()
        listen(20000)
        Thread.start(this.&processing)
    }
    def listen(port) {
        serversk = ServerSocketChannel.open();
        serversk.configureBlocking(false)
        serversk.socket().bind(new InetSocketAddress(port));
        serversk.register(select, SelectionKey.OP_ACCEPT)
    }
    def processing() {
        while (true) {
            select.select()
            for (item in select.selectedKeys()) {
                // new connection
                if (item.isAcceptable()) {
                    def newsk = item.channel().accept()
                    if (newsk) {
                        newsk.configureBlocking(false)
                        newsk.register(select, SelectionKey.OP_READ|SelectionKey.OP_WRITE)
                    }
                }
            }
        }
    }
}

*/
