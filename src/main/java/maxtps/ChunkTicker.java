package cz.majncraft.maxtps;
import org.bukkit.plugin.*;
import net.minecraft.server.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.event.world.ChunkLoadEvent;


class ChunkTicker implements Runnable {
    MaxTPS plugin;
    public LinkedBlockingQueue queue;
    ChunkTicker(MaxTPS p, int ticks) {
        queue = new LinkedBlockingQueue<ChunkRequest>(99999999);
        plugin = p;
        plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this, 0, ticks);
    }
    @Override
    public void run() {
        try {
            while (true) {
                if (queue.isEmpty()) break;

                ChunkRequest req = (ChunkRequest)queue.take();
                if (req.p.world != req.world) continue; // not from this world ...
//                this.plugin.debug("prio "+req.prio+" x,z="+req.x+","+req.z);
                ChunkProviderServer provider = (ChunkProviderServer) req.world.chunkProvider;
                Chunk ch = null;
                boolean newChunk = false;
                if (provider.isChunkLoaded(req.x, req.z)) {
                    ch = provider.getChunkAt(req.x, req.z);
                } else {
                    if (req.nbt!=null) {
                        ch = req.loader.load_nbt(req.world, req.x, req.z, req.nbt); //fast
                    }
                    if (ch == null) {
                        ch = provider.chunkProvider.getOrCreateChunk(req.x, req.z); //slow
                    }
                    newChunk = true;
                    ch.n = req.world.getTime();
                    provider.chunks.put(req.x, req.z, ch);
                    provider.chunkList.add(ch);

//                    if (ch != null) {
//                    }
                }
                if (!ch.done) {
                    /*
                    if (req.level > 4) {
                        this.plugin.debug("wtf?! tarrain still not done? x,z="+req.x+","+req.z);
                    }
                    req.resubmit();
                    */
                    ch.a(provider, provider, req.x, req.z); 
                    if (!ch.done) {
                        MyPlayerManager mgr = (MyPlayerManager)req.p.server.getWorldServer(req.p.dimension).manager;
//                        plugin.debug("killing chunk "+req.x*16 +" "+req.z*16);
                        mgr.kill_chunk(req.p, req.x, req.z);
                        continue;
                    }
                } else if (newChunk) {
                    ch.addEntities();
                }


                if ((ch != null) && (req.p.world == req.world)) {
                    org.bukkit.Server server = req.world.getServer();
                    if (server != null) {
                        /*
                         * If it's a new world, the first few chunks are generated inside
                         * the World constructor. We can't reliably alter that, so we have
                         * no way of creating a CraftWorld/CraftServer at that point.
                         */
                        server.getPluginManager().callEvent(new ChunkLoadEvent(ch.bukkitChunk, newChunk));
                    }

                        /*
                    if (!ch.done) {
                        req.resubmit();

                            this.queue.add(new ChunkRequest(nx+d[i], nz+d[i+1], null,(diffx*diffx+diffz*diffz)-1)); // dependencies should load first
                        int d[] = { -1, -1,   -1, 0,   -1, 1,   0,-1,  0,1,   1,-1,   1, 0,   1,1 };
                        int nz = req.z;
                        int nx = req.x;
                        for (int i = 0; i < 16; i += 2) {
                            if (!provider.isChunkLoaded(nx+d[i], nz+d[i+1]))
                                ch.a(provider, provider, nx+d[i], nz+d[i+1]);
                        }
                        ch.a(provider, provider, req.x, req.z);
                        if (!ch.done)
                            this.plugin.debug("wtf?! tarrain still not done? x,z="+req.x+","+req.z);
                        */
//                    } else {
//                        Thread.sleep(100);
                        // get tile entities
                        // send
                        req.send(ch);
//                    }
                }
            }
        } catch (Exception ex) {
                ex.printStackTrace();
        }
    }
}
; 
