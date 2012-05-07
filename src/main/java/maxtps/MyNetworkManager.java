package cz.majncraft.maxtps;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.logging.Logger;
import java.util.concurrent.*;
import java.util.*;
import net.minecraft.server.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.*;
import java.util.zip.Deflater;


public class MyNetworkManager extends NetworkManager implements Comparator {

    private static Socket abort() {
        throw null;
    }
    int px, pz;
    // chunk distance from player
    private int chunkdist(Packet pk) {
        if (pk instanceof Packet51MapChunk) {
            Packet51MapChunk pkt = (Packet51MapChunk) pk;
            int diffx = px - pkt.a;
            int diffz = pz - pkt.b;
            return diffx * diffx + diffz * diffz;
        }
        if (pk instanceof Packet50PreChunk)
            return 0;
        if (ischunk(pk))
            return (Integer.MAX_VALUE/2);
        return 0;
    }

    @Override
    public int e() {
        return 0;
    }

    @Override
    public int compare(Object a, Object b) {
        return chunkdist((Packet)a)-chunkdist((Packet)b);
    }

    public MyNetworkManager() {
        super(abort(),"",null);
    }
    public static MyNetworkManager alloc(NetworkManager old, MaxTPS plugin, EntityPlayer player) throws Exception {
        Field u = Unsafe.class.getDeclaredField("theUnsafe"); u.setAccessible(true); MyNetworkManager me = (MyNetworkManager)((Unsafe)u.get(Unsafe.class)).allocateInstance(MyNetworkManager.class);
        me.init2(old, plugin, player);
        return me;
    }

    DataInputStream dinput;
    DataOutputStream doutput;
    MaxTPS pl;
    EntityPlayer p;
    LinkedBlockingQueue<Packet> sendq;
    List<Packet> chunksendq;
    ConcurrentLinkedQueue<Packet> recvq;
    List hiprio, loprio, oldrecvq;
    NetworkManager oldmanager;
    Socket psocket;
    Reader reader;
    Writer writer;
    NetHandler handler;
    public boolean migrating;
    public boolean killed;
    public boolean killthreads;
    String killmsg;
    int ticker;
    public NetHandler oldhandler;
    int crem, cbrem;
    public AtomicInteger aburst;
    public AtomicInteger lastread;
    public Deflater deflater;

    void init2(NetworkManager old, MaxTPS plugin, EntityPlayer player) {
        deflater = new Deflater();
        deflateBuffer = new byte[CHUNK_SIZE + 100];
        lastread = new AtomicInteger((int)(System.currentTimeMillis()/1000));
        killmsg = "Unknown reason";
        ticker = 0;
        oldhandler = null;
        killed = migrating = killthreads = false;
        aburst = new AtomicInteger();
        crem = cbrem = 0;
        oldhandler = handler = (NetHandler) plugin.get_field(old,"packetListener");
        oldmanager = old;
        p = player;

        // queues
        this.chunksendq = Collections.synchronizedList(new LinkedList<Packet>());
        this.sendq = new LinkedBlockingQueue<Packet>(9999);
        this.recvq = new ConcurrentLinkedQueue();


        // kill old instances
        psocket = old.socket;
        old.socket = new Socket();
        dinput = (DataInputStream)plugin.get_field(old, "input");
        doutput = (DataOutputStream)plugin.get_field(old, "output");
//         new DataInputStream(psocket.getInputStream());
//            doutput = new DataOutputStream(new BufferedOutputStream(psocket.getOutputStream(), 5120));

        try {
//        plugin.set_field(old, "input", new DataInputStream(new StringBufferInputStream("")));
        //plugin.set_field(old, "output", new DataOutputStream(new BufferedOutputStream(old.socket.getOutputStream(), 5120)));

        plugin.set_field(old, "l",false); // terminate early...
//        ((Thread)plugin.get_field(old, "s")).stop();
//        ((Thread)plugin.get_field(old, "r")).stop();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        // migrate
 
        // remember
        pl = plugin;

//        try {
//            psocket.setSoTimeout(30000);
//            dinput = new DataInputStream(psocket.getInputStream());
//            doutput = new DataOutputStream(new BufferedOutputStream(psocket.getOutputStream(), 5120));
//        } catch (Exception ex) {
//            ex.printStackTrace();
//        }
        reader = new Reader(this, "MaxTPS reader");
        writer = new Writer(this, "MaxTPS reader");
        reader.start();
        writer.start();
//        init_burst();
    }
    @Override
    public void a() {b();}

    private boolean ischunk(Packet packet) {
        return packet instanceof Packet51MapChunk || packet instanceof Packet50PreChunk || packet instanceof Packet130UpdateSign || packet instanceof Packet132TileEntityData || packet instanceof Packet52MultiBlockChange || packet instanceof Packet53BlockChange;// || packet instanceof Packet24MobSpawn || packet instanceof Packet21PickupSpawn || packet instanceof Packet23VehicleSpawn;
    }

    @Override
    public void queue(Packet packet) {
//            pl.debug("queued "+packet);
        try {
        if (ischunk(packet)) {
/*            if (bursting)
                burst_sendq.add(packet);
            else*/
//            synchronized (chunksendq) {
  //              Packet51MapChunk pk = (Packet51MapChunk)packet;
//                Thread.dumpStack();
                //pl.debug("@@queued! ");
                chunksendq.add(packet);
//            }
        } else {
//            pl.debug("queued normal packet!");
            sendq.add(packet);
        }
        } catch (Exception ex) {
            kill("Internal error", ex);
        }
    }
    long fixts(Packet p) {
        if (p instanceof Packet132TileEntityData) {
            return p.timestamp+50;
        }
        return p.timestamp;
    }

    private final static int CHUNK_SIZE = 16 * 128 * 16 * 5 / 2;
    private byte[] deflateBuffer;

    void obfuscate(byte[] data)
    {
        int count=0;
        if (data.length < 4096) return;
        for (int i = 0; i < 4096; i++) {
            if (data[i] == 56) {
                data[i] = 1;
                count++;
            }
        }
//        pl.debug("obfuscated! "+count);
    }

    void compress(Packet51MapChunk pkt) {
        if (pl.oreditor > 0 && p.dimension == 0 && (pkt.f)) obfuscate(pkt.rawData);
        int dataSize = pkt.rawData.length;
        if (deflateBuffer.length < dataSize + 100)
            deflateBuffer = new byte[dataSize + 100];
        deflater.reset();
        deflater.setLevel(1);
        deflater.setInput(pkt.rawData);
        deflater.finish();
        int size = deflater.deflate(deflateBuffer);
        if (size == 0)
            size = deflater.deflate(deflateBuffer);
        pkt.buffer = new byte[size];
        pkt.size = size;
        System.arraycopy(deflateBuffer, 0, pkt.buffer, 0, size);
    }

    // prevent spam, lol
    public void socket_write() {
        int ticker = 0;
        Packet pkt = null;
        Packet chunkpkt = null;

        try {
            ((Thread)pl.get_field(oldmanager, "r")).join();
            pl.set_field(oldmanager, "input", new DataInputStream(new StringBufferInputStream("")));
    //        pl.set_field(oldmanager, "t", true);
            hiprio = (List)pl.get_field(oldmanager, "highPriorityQueue");
            loprio = (List)pl.get_field(oldmanager, "lowPriorityQueue");
            chunksendq.addAll(loprio);
            sendq.addAll(hiprio);
        } catch (Exception ex) {
            kill("Internal error", ex);
            return;
        }

        long now, oldnow = 0;
        try { while (!killthreads) {
            int burst = aburst.get();
            int limit = 0;
            int burstpower = pl.viewdist*pl.viewdist;
            int burstsent = 0;
            if (ticker > 20) {
                if (burst > 0) {
                    if (ticker % pl.cb2 == 0) limit = pl.cb1;
                } else {
                    if (ticker % pl.cs2 == 0) limit = pl.cs1;
                }
            }

/*
            if ((ticker % 20 == 0)||(burst>0 && ticker % 4 == 0)) {*/
//                 synchronized (chunksendq) {
//                     Collections.sort(chunksendq, this); // lets just hope the list is small ...
//                 }
/*            }*/

//            pl.debug("send enter");
            while (true) {
                if (pkt == null)
                    pkt = sendq.peek();
                boolean csent=false,sent=false;
                if (pkt!=null) {
                    //!=null && (chunkpkt == null || (pkt.timestamp <= chunkpkt.timestamp))) 
                    sendq.remove(pkt);
                    pl.debug("DEQUEUE (HIPRIO) "+pkt);
                    Packet.a(pkt, doutput);
//                    pl.debug("sendq="+sendq.size()+" chunksendq="+chunksendq.size()+" pkt="+pkt);
                    pkt = null;
                    csent = true;
                }


                if (limit > 0) {
                    if (chunkpkt == null && !chunksendq.isEmpty())
                        chunkpkt = chunksendq.get(0);
                    if (chunkpkt!=null) {
                        if (pkt==null)
                            pkt=sendq.peek();
                        if ((pkt==null)||pkt.timestamp>chunkpkt.timestamp) {
                            if (burst > 0) {
                                if (chunkdist(chunkpkt) < burstpower) {
//                                    pl.debug("burst ok!");
                                    burstsent++;
                                    burst--;
                                } else {
//                                    pl.debug("burst out of range!");
//                                    chunkpkt = null;
                                }
                            }
                        } else {
                            chunkpkt = null;
                        }
                    }
                }
                if (chunkpkt!=null) {
                    chunksendq.remove(chunkpkt);
                    if (chunkpkt instanceof Packet51MapChunk)
                        compress((Packet51MapChunk)chunkpkt);
                    pl.debug("DEQUEUE (LOPRIO) "+chunkpkt);
                    Packet.a(chunkpkt, doutput);
                    limit--;
                    chunkpkt = null;
                    sent = true;
                }
                if (!csent && !sent && pkt == null) break;
            }
            aburst.addAndGet(-burstsent);

            now = System.currentTimeMillis();
            doutput.flush();
            pkt = sendq.poll(50, TimeUnit.MILLISECONDS);
            if (now - oldnow >= 50 || pkt == null) {
                oldnow = now;
                ticker++;
            }
        } } catch (Exception ex) {
            kill("Internal error", ex);
        }
    }
    public SocketAddress getSocketAddress() {
        return psocket.getRemoteSocketAddress();
    }

    public void socket_read() {
        try {
            Thread t = ((Thread)pl.get_field(oldmanager, "s"));
            t.interrupt();
            t.join();
            oldrecvq = (List)pl.get_field(oldmanager, "m");
            recvq.addAll(oldrecvq);

            while (!killthreads) {
                Packet pkt = Packet.a(this.dinput, this.handler.c());
                lastread.set((int)(System.currentTimeMillis()/1000));
                if (pkt != null) {
                    recvq.add(pkt);
                } else {
                    kill("EOF", null);
                    break;
                    // TODO disconnect
                }
            }
        } catch (Exception ex) {
            kill("Internal error", ex);
        }
    }
    public void kill(String s, Exception ex) {
//        pl.debug("performing suicide"+s+"killed="+killed);
        if (!killed) {
            if (ex != null)
                ex.printStackTrace();
            a(s, null);
        }
    }
    public void d() {
        kill("disconnected", null);
    }
    public void shutdown() {
        try {
            dinput.close();
            dinput = null;
        } catch (Exception ex) {}
        try {
            doutput.close();
            doutput = null;
        } catch (Exception ex) {}
        try {
            psocket.close();
            psocket = null;
        } catch (Exception ex) {}
    }
    public void a(String s, Object... aobject) {
        if (killed) return;
        killed = true;
        killmsg = s;
        (new Killthreads(this)).start();
    }
    // called every tick
    public void b() { tick(); }

    public void a(NetHandler nethandler) {
        if (nethandler == this.handler) return;
        pl.debug("changing handler to "+nethandler);
        oldhandler = this.handler;
        this.handler = nethandler;
    }



    public void tick() {
        px = (int) p.locX >> 4;
        pz = (int) p.locZ >> 4;

        if ((lastread.get() + 300) < ((int)(System.currentTimeMillis()/1000))) {
            kill("timed out", null);
        }

        if (migrating) return;
        if (killed) {
            handler.a(killmsg, new Object[0]);
            return;
        }
        try {
            while (!recvq.isEmpty()) {
                Packet pkt = (Packet) recvq.remove();
                pkt.handle(this.handler);
            }
        } catch (Exception ex) {
            kill("Internal error", ex);
        }
    }

/*
    boolean bursting = false;
    public void init_burst() {
        pl.debug("burst init!");
        if (bursting) return;
        burst_sendq = new LinkedBlockingQueue<Packet>(99999);
        bursting = true;
    }
    public void finish_burst() {
        pl.debug("burst finished!");
        burst_sendq.drainTo(psendq);
        burst_sendq = null;
        bursting = false;
    }*/
}

class Reader extends Thread {
    MyNetworkManager mgr;
    public Reader(MyNetworkManager manager, String s) {
        super(s);
        mgr = manager;
    }
    public void run() {
        mgr.socket_read();
    }
}

class Writer extends Thread {
    MyNetworkManager mgr;
    public Writer(MyNetworkManager manager, String s) {
        super(s);
        mgr = manager;
    }
    public void run() {
        mgr.socket_write();
    }
}

class Killthreads extends Thread {
    MyNetworkManager mgr;
    public Killthreads (MyNetworkManager manager) {
        super("kill thread");
        mgr = manager;
    }
    public void run() {
        mgr.pl.debug("kill thread starting");
        try {
        Thread.sleep(2000);
        mgr.killthreads = true;
        } catch (Exception ex) {}
        mgr.shutdown();
    }
}

