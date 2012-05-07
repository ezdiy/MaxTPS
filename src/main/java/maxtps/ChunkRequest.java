package cz.majncraft.maxtps;
import net.minecraft.server.*;
import java.util.logging.Logger;
import java.util.*;

public class ChunkRequest implements Cloneable {
    public int x, z;
    public EntityPlayer p = null;
    public WorldServer world = null;
    public NBTTagCompound nbt = null;
    public MyChunkRegionLoader loader = null;
    public int prio = 0;
    public int level = 0;
    ChunkRequest(int _x, int _z, EntityPlayer _p, int _prio) {
        this.x = _x;
        this.z = _z;
        this.p = _p;
        this.prio = _prio;
    }
    void send_lowprio(Chunk ch) {
//        pkt.lowPriority = true;
        send(ch, false);
    }
    void send(Chunk ch) {
        send(ch, true);
    }
    void send(Chunk ch, boolean tiles) {
        int px = (int) p.locX >> 4;
        int pz = (int) p.locZ >> 4;
        if (tiles)
            p.netServerHandler.networkManager.queue(new Packet50PreChunk(x, z, true));
//        MyPlayerManager mgr = (MyPlayerManager)this.p.server.getWorldServer(p.dimension).manager;
        Packet pkt = new Packet51MapChunk(ch, true, 0);
//        mgr.burstpacket(p, ch.x, ch.z);
//        pkt.lowPriority = 
        send(pkt); // this sucks
        if (tiles)
            sendTiles(p,ch);
    }
    public static void prechunk(EntityPlayer pl, int x, int z) {
        pl.netServerHandler.networkManager.queue(new Packet50PreChunk(x, z, true));
    }
    public static void sendTiles(EntityPlayer pl, Chunk ch)
    {
        for (TileEntity ent : (Collection<TileEntity>)(ch.tileEntities.values())) {
            Packet tpkt = ent.d();
            if (tpkt != null) {
//                Logger.getLogger("Minecraft").info("queue tile"+tpkt);
                pl.netServerHandler.networkManager.queue(tpkt);
            }
        }
    }
    void send(Packet pkt) {
        this.p.netServerHandler.networkManager.queue(pkt);
    }

/*
    void resubmit(int ox, int oz, int pr) {
        MyPlayerManager mgr = (MyPlayerManager)this.p.server.getWorldServer(p.dimension).manager;
        ChunkRequest cl = null;
        try {
        cl = (ChunkRequest)this.clone();
        } catch (Exception ex) {
                    ex.printStackTrace();
        }
        if (ox != 0 && oz != 0)
            cl.level = this.level+1;
        cl.x += ox;
        cl.z += oz;
        cl.prio += pr;
        mgr.queue.add(cl);
    }
    void resubmit() {
        boolean gotself = false;
        MyPlayerManager mgr = (MyPlayerManager)this.p.server.getWorldServer(p.dimension).manager;
//        if (!mgr.is_done(x,z) && mgr.is_loaded(x + 1, z + 1) && mgr.is_loaded(x, z + 1) && mgr.is_loaded(x + 1, z)) {
        resubmit(0, 0, 0);
//        }

        if (mgr.is_loaded(x - 1, z) && !mgr.is_done(x - 1, z) && mgr.is_loaded(x - 1, z + 1) && mgr.is_loaded(x, z + 1) && mgr.is_loaded(x - 1, z + 1)) {
            resubmit(-1, 0, -1);
        }

        if (mgr.is_loaded(x, z - 1) && !mgr.is_done(x, z - 1) && mgr.is_loaded(x + 1, z - 1) && mgr.is_loaded(x + 1, z - 1) && mgr.is_loaded(x + 1, z)) {
            resubmit(0, 1, -1);
        }

        if (mgr.is_loaded(x - 1, z - 1) && !mgr.is_done(x - 1, z - 1) && mgr.is_loaded(x, z - 1) && mgr.is_loaded(x - 1, z)) {
            resubmit(-1, -1, -1);
        }
    }*/
}


