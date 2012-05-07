package cz.majncraft.maxtps;
import java.lang.reflect.*;
import java.util.logging.Logger;
import java.util.concurrent.*;
import java.util.*;
import net.minecraft.server.*;
import org.bukkit.craftbukkit.CraftWorld;

public class MyPlayerManager extends PlayerManager {
    private MinecraftServer server;
    private int idx;
    private int vd = 0;
    private int vdp = 0;
    private int vdmax = 0;
    private int vdmaxp = 0;
    private HashMap<Long, HashSet<EntityPlayer>> playermap = new HashMap<Long, HashSet<EntityPlayer>>();
    private HashMap<EntityPlayer, LinkedHashSet<Long>> chunkmap = new HashMap<EntityPlayer, LinkedHashSet<Long>>();
    private HashMap<EntityPlayer, LinkedHashSet<Long>> chunkmap2 = new HashMap<EntityPlayer, LinkedHashSet<Long>>();
    private HashSet<Long> alreadyqueued = new HashSet<Long>();
    private HashMap<Long, short[]> dirtymap = new HashMap<Long, short[]>();
    private List<EntityPlayer> managedPlayers = new ArrayList();
    private LinkedHashSet<Long> newchunks = null;
    private LinkedHashSet<Long> newchunks2 = null;
    private int newx, newz;
    public PriorityBlockingQueue<ChunkRequest> queue;
    public LinkedBlockingQueue<ChunkRequest> queue2;
    private int qseq = 0;
    private Field qfield;
    private Field gfield;

    public MaxTPS plugin;
    public MyPlayerManager(MinecraftServer minecraftserver, int i, int j, int j2, MaxTPS pl) {
        super(minecraftserver, i, 5);
        this.plugin = pl;
        this.server = minecraftserver;
        this.idx = i;
        this.setViewDist(j);
        this.setFakeDist(j2);
        this.queue = new PriorityBlockingQueue<ChunkRequest>(9999999, new Comparator<ChunkRequest>() {
            public int compare(ChunkRequest a, ChunkRequest b) { return a.prio - b.prio; }
        });
        this.queue2 = new LinkedBlockingQueue<ChunkRequest>(99999999);
    }
    public void setViewDist(int vd) {
        this.vd = vd;
        this.vdp = vd*vd;
    }
    public void setFakeDist(int vdmax) {
        this.vdmax = vdmax;
        this.vdmaxp = vdmax*vdmax;
    }

    private long encode(long x, long z)
    {
        return ((x + (1L<<30)) | ((z + (1L<<30)) << 32));
    }
    private int decodeX(long l)
    {
        return (int)((l & 0xffffffff) - (1L<<30));
    }
    private int decodeZ(long l)
    {
        return (int)((l >> 32) - (1L<<30));
    }


    private HashSet<EntityPlayer> lookup(long k, boolean alloc) {
        HashSet<EntityPlayer> plist = this.playermap.get(k);

        if (plist == null && alloc) {
            plist = new HashSet<EntityPlayer>();
            this.playermap.put(k, plist);
        }

        return plist;
    }
    public boolean withinradius(int x, int z, int r) {
        return (x*x + z*z) < r;
    }
    public boolean withinradius(int x, int z, EntityPlayer p, int r) {
        int px = (int) p.locX >> 4;
        int pz = (int) p.locZ >> 4;
//        log("px="+px+", pz="+pz+", x="+x+", z="+z);
        return withinradius(x-px, z-pz, r);
    }

    private void addchunk(int x, int z) {
        if (withinradius(x,z,vdp)) {
            this.newchunks.add(this.encode(this.newx+x, this.newz+z));
        } else if (withinradius(x,z,vdmaxp)) {
            this.newchunks2.add(this.encode(this.newx+x, this.newz+z));
        }
    }

    public void update(EntityPlayer p) {
        alreadyqueued.clear();
        log("doing update, queue="+queue.size()+", queue2="+queue2.size());
        int x = (int) p.locX >> 4;
        int z = (int) p.locZ >> 4;
        int vd = this.vd;
        LinkedHashSet<Long> oldchunks = this.chunkmap.get(p);
        LinkedHashSet<Long> oldchunks2 = this.chunkmap2.get(p);
        this.newchunks = new LinkedHashSet<Long>();
        this.newchunks2 = new LinkedHashSet<Long>();
        this.newx = x;
        this.newz = z;

        this.addchunk(0, 0);
//        for (int f = this.vdmax; f > 0; f--) {
        for (int f = 1; f <= vdmax; f++) {
            for (int i = -f; i < f; i++) {
                this.addchunk(i, -f);
                this.addchunk(f, i);
                this.addchunk(i + 1, f);
                this.addchunk(-f, i + 1);
            }
        }
        if (needburst) {
            plugin.getmgr(p).aburst.set(this.newchunks.size() / 2);
        }

        needburst = false;
        //log("newchunks="+newchunks.size()+" newchunks2="+newchunks2.size());

/*
        Long[] o = new Long[oldchunks.size()];
        o = oldchunks.toArray(o);
        for (int i = o.length-1; i >= 0; i--) {
            long k = (long)o[i];*/

        // first, kill old chunks not contained in new set. always unmap, newchunks2 could map it back
        for (long k: oldchunks) {
            if (!newchunks.contains(k)) {
                this.remove_player_from_chunk(k, p, !newchunks2.contains(k));
            }
        }

        // also remove chunks not in far distance
        for (long k: oldchunks2) {
            if (!newchunks.contains(k) && !newchunks2.contains(k)) {
                p.netServerHandler.networkManager.queue(new Packet50PreChunk(decodeX(k),decodeZ(k), false));
            }
        }

        // install new chunks
        for (long k: newchunks) {
            if (!oldchunks.contains(k)) {
//                if (!needburst)
//                if (oldchunks2.contains(k))
//                    p.netServerHandler.sendPacket(new Packet50PreChunk(decodeX(k),decodeZ(k), true));
                this.add_player_to_chunk(k, p);
            }
        }

        // install far chunks
//        Object[] a = newchunks2.toArray();
        for (long k: newchunks2) {
//        for (int i = a.length-1; i >= 0; i--)  long k = (Long)a[i];
            if (!oldchunks.contains(k) && !oldchunks2.contains(k)) {
//                p.netServerHandler.networkManager.queue(new Packet50PreChunk(decodeX(k),decodeZ(k), true));
                this.add_player_to_chunk2(k, p);
            }
        }


        this.chunkmap.put(p, newchunks);
        this.chunkmap2.put(p, newchunks2);
        log("finished update, queue="+queue.size()+", queue2="+queue2.size());
    }

    boolean needburst = false;
    public void addPlayer(EntityPlayer p)
    {
        plugin.hook_network(p);
        needburst = true;
        //if (!(p.netServerHandler.networkManager instanceof MyNetworkManager)) plugin.hook_network(p);
        addPlayer(p, false);
    }

    public void addPlayer(EntityPlayer p, boolean haschunk) {
        p.d = p.locX; p.e = p.locZ;
        chunkmap.put(p, new LinkedHashSet<Long>());
        chunkmap2.put(p, new LinkedHashSet<Long>());
        if (haschunk) {
            long hischunk = encode((int) p.locX >> 4, (int) p.locZ >> 4);
            chunkmap.get(p).add(hischunk);
            this.lookup(hischunk, true).remove(p);
        }
        this.managedPlayers.add(p);
        this.update(p);
        uncover(p);
    }
    public void removePlayer(EntityPlayer p)
    {
        removePlayer(p, false);
    }
    public void removePlayer(EntityPlayer p, boolean haschunk)
    {
        long hischunk = encode((int) p.locX >> 4, (int) p.locZ >> 4);

        if (!managedPlayers.remove(p)) return;
        for (long k: this.chunkmap.remove(p))
            if (!(haschunk && hischunk == k))
                this.remove_player_from_chunk(k, p, true);
        for (long k: this.chunkmap2.remove(p))
            p.netServerHandler.networkManager.queue(new Packet50PreChunk(decodeX(k),decodeZ(k), false));
    }

    private boolean isVisible(CraftWorld w, int x, int y, int z)
    {
        if (
            w.getBlockTypeIdAt(x-1,y,z) == 0 ||
            w.getBlockTypeIdAt(x+1,y,z) == 0 ||
            w.getBlockTypeIdAt(x,y-1,z) == 0 ||
            w.getBlockTypeIdAt(x,y+1,z) == 0 ||
            w.getBlockTypeIdAt(x,y,z-1) == 0 ||
            w.getBlockTypeIdAt(x,y,z+1) == 0
            ) return true;
        return false;
    }

    public void movePlayer(EntityPlayer p) {
        int newx = (int) p.locX >> 4;
        int newz = (int) p.locZ >> 4;
        double diffx = p.d - p.locX;
        double diffz = p.e - p.locZ;
        double diff = diffx * diffx + diffz * diffz;


        int oldx = (int) p.d >> 4;
        int oldz = (int) p.e >> 4;

        int shiftx = oldx - newx;
        int shiftz = oldz - newz;

        // debug
//        this.log("shiftx="+shiftx+",shiftz="+shiftz+",locX="+p.locX+",locZ="+p.locZ+",oldX="+p.d+",oldZ="+p.e);
        // debug

        if ((diff >= 64) && (shiftx != 0 || shiftz != 0)) {
            this.update(p);
        }
        if (plugin.oreditor > 0 && diff > 16)
            uncover(p);
        if (diff < 64) return;
        p.d = p.locX;
        p.e = p.locZ;
    }

    private void uncover(EntityPlayer p) {
        if (p.locY <= 20) {
            int px = (int)p.locX, pz = (int)p.locZ;
            CraftWorld w = this.getServer().getWorld();
            int urad = 16;
            for (int x = -urad; x <= urad; x++)
            for (int y = 1; y < 20; y++)
            for (int z = -urad; z <= urad; z++) {
               if (w.getBlockTypeIdAt(px+x,y,pz+z)==56 && isVisible(w, px+x,y,pz+z))
                   do_flagDirty(px+x,y,pz+z);
            }
            this.flush_player(p);
        }
    }

    private void log(String s)
    {
//        Logger.getLogger("Minecraft").info(s);
    }
    public void kill_chunk(EntityPlayer p, int x, int z)
    {
        long k = encode(x, z);
        HashSet<EntityPlayer> pl = this.lookup(k, false);
        if (pl==null) return;
        for (EntityPlayer pla : pl)
            chunkmap.get(pla).remove(k);
        playermap.remove(k);
    }

    private void remove_player_from_chunk(long k, EntityPlayer p, boolean unmap)
    {
        HashSet<EntityPlayer> pl = this.lookup(k, false);
        if (pl != null && pl.remove(p)) {
            int nx = decodeX(k);
            int nz = decodeZ(k);
            if (unmap)
                p.netServerHandler.networkManager.queue(new Packet50PreChunk(nx,nz, false));
/*            if (pl.size() == 0) {
                playermap.remove(k); // avoid polution
                this.getServer().chunkProviderServer.queueUnload(nx,nz);
            } */
        }
    }
    private void add_player_to_chunk(long k, EntityPlayer p) {
        HashSet<EntityPlayer> pl = this.lookup(k, true);
        if (pl.add(p)) {
            int nx = decodeX(k);
            int nz = decodeZ(k);
            int px = (int) p.locX >> 4;
            int pz = (int) p.locZ >> 4;

            int diffx = px - nx;
            int diffz = pz - nz;

            if (!is_loaded(nx,nz) || !is_done(nx,nz)) {
                // not cached, will have to load
                if (alreadyqueued.add(k)) {
                    ChunkRequest.prechunk(p, nx, nz);
                    this.queue.add(new ChunkRequest(nx, nz, p, (diffx*diffx+diffz*diffz)));
                }
            } else {
                Chunk chunk = this.getServer().getChunkAt(nx, nz);
                log("chunk "+nx*16+","+nz*16+" already loaded"+is_done(nx,nz));
                ChunkRequest.prechunk(p, nx, nz);
                p.netServerHandler.networkManager.queue(new Packet51MapChunk(chunk, true, 0));
                ChunkRequest.sendTiles(p, chunk);
            }
        }
    }

    public boolean is_loaded(int x, int z) {
        return this.getServer().chunkProvider.isChunkLoaded(x, z);
    }
    public boolean is_done(int x, int z) {
        return this.getServer().getChunkAt(x, z).done;
    }

    private void add_player_to_chunk2(long k, EntityPlayer p) {
        int nx = decodeX(k);
        int nz = decodeZ(k);

//        if (!is_loaded(nx, nz) || !is_done(nx, nz)) {
            try {
            this.queue2.put(new ChunkRequest(nx, nz, p, 0));
            } catch (Exception ex) {
                    ex.printStackTrace();
                }
  //      } else {
  //          p.netServerHandler.sendPacket(new Packet51MapChunk(this.getServer().getChunkAt(nx, nz), true, 0));
  //      }
    }

    public void do_flagDirty(int x, int y, int z) {
        long k = encode(x>>4,z>>4);
        x &= 15;
        z &= 15;
        if (!this.dirtymap.containsKey(k))
            this.dirtymap.put(k, new short[66]);
        short[] db = this.dirtymap.get(k);
        if (db[1] == 64)
            return;
        db[0] |= 1 << (y >> 4);
        short short1 = (short) (x << 12 | z << 8 | y);
        for (int l = 0; l < db[1]; ++l) {
            if (db[l+2] == short1) {
                return;
            }
        }
        db[2+db[1]++] = short1;
    }
    public void flagDirty(int x, int y, int z) {
        int rad = plugin.oreditor;
        if (rad == 0 || (y-rad>16)) {
            do_flagDirty(x,y,z);
            return;
        }
        do_flagDirty(x,y,z);
        CraftWorld w = this.getServer().getWorld();
        for (int j = Math.max(-rad,-y); j < rad; j++)
        for (int i = -rad; i < rad; i++)
        for (int k = -rad; k < rad; k++) {
           if (w.getBlockTypeIdAt(x+i,y+j,z+k)==56)
               do_flagDirty(x+i,y+j,z+k);
        }
    }
    public void flush() {
        for (EntityPlayer p : this.managedPlayers)
            this.flush_player(p);
    }
    private void flush_player(EntityPlayer p)
    {
        WorldServer worldserver = this.getServer();
        for (Long chunk: this.chunkmap.get(p)) {
            int chunkX = decodeX(chunk);
            int chunkZ = decodeZ(chunk);
            short[] db = this.dirtymap.remove(chunk);
            if (db == null) continue;
            short[] dirtyBlocks = Arrays.copyOfRange(db, 2, db.length);
            int thish = db[0];
            int dirtyCount = db[1];
            if (dirtyCount == 0) continue;
            int i;
            int j;
            int k;

            if (dirtyCount == 1) {
                i = chunkX * 16 + (dirtyBlocks[0] >> 12 & 15);
                j = dirtyBlocks[0] & 255;
                k = chunkZ * 16 + (dirtyBlocks[0] >> 8 & 15);
                this.sendAll(new Packet53BlockChange(i, j, k, worldserver), chunk);
                if (worldserver.isTileEntity(i, j, k)) {
                    this.sendTileEntity(worldserver.getTileEntity(i, j, k), chunk);
                }
            } else {
                int l;

                if (dirtyCount == 64) {
                    i = chunkX * 16;
                    j = chunkZ * 16;
                    this.sendAll(new Packet51MapChunk(worldserver.getChunkAt(chunkX, chunkZ), false, thish), chunk);

                    for (k = 0; k < 16; ++k) {
                        if ((thish & 1 << k) != 0) {
                            l = k << 4;
                            List list = worldserver.getTileEntities(i, l, j, i + 16, l + 16, j + 16);

                            for (int i1 = 0; i1 < list.size(); ++i1) {
                                this.sendTileEntity((TileEntity) list.get(i1), chunk);
                            }
                        }
                    }
                } else {
                    this.sendAll(new Packet52MultiBlockChange(chunkX, chunkZ, dirtyBlocks, dirtyCount, worldserver), chunk);

                    for (i = 0; i < dirtyCount; ++i) {
                        j = chunkX * 16 + (dirtyBlocks[i] >> 12 & 15);
                        k = dirtyBlocks[i] & 255;
                        l = chunkZ * 16 + (dirtyBlocks[i] >> 8 & 15);
                        if (worldserver.isTileEntity(j, k, l)) {
                            this.sendTileEntity(worldserver.getTileEntity(j, k, l), chunk);
                        }
                    }
                }
            }
        }
    }

    private void sendAll(Packet pkt, long chunk) {
        HashSet<EntityPlayer> players = this.playermap.get(chunk);
        if (players==null) return;
        for (EntityPlayer p : players) {
            p.netServerHandler.networkManager.queue(pkt);
        }
    }

    private void sendTileEntity(TileEntity tileentity, long chunk) {
        if (tileentity != null) {
            Packet packet = tileentity.d();

            if (packet != null) {
                this.sendAll(packet, chunk);
            }
        }
    }

    private WorldServer getServer() {
        return this.server.getWorldServer(this.idx);
    }


}

