package cz.majncraft.maxtps;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException; // CraftBukkit
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.lang.reflect.*;
import net.minecraft.server.*;

public class MyChunkRegionLoader extends ChunkRegionLoader {
    private List _a;
    private Set _b;
    private Object _c;
    private final File _d;
    private Field Fa=null,Fb=null;

    public MyChunkRegionLoader(List aa, Set bb, Object cc, File dd) throws Exception {
        super(dd);
        Class parent = this.getClass().getSuperclass();
        Field fa = parent.getDeclaredField("a");
        Field fb = parent.getDeclaredField("b");
        Field fc = parent.getDeclaredField("c");
        fa.setAccessible(true);
        fb.setAccessible(true);
        fc.setAccessible(true);
        fa.set(this, aa);
        fb.set(this, bb);
        fc.set(this, cc);
        _a = aa;
        _b = bb;
        _c = cc;
        _d = dd;
    }

    public NBTTagCompound load_async(World world, int i, int j) {
        NBTTagCompound nbttagcompound = null;
        ChunkCoordIntPair chunkcoordintpair = new ChunkCoordIntPair(i, j);
        Object object = this._c;

        synchronized (this._c) {
            try {
            if (this._b.contains(chunkcoordintpair)) {
                for (int k = 0; k < this._a.size(); ++k) {
                    Object o = this._a.get(k);
                    if (Fa==null) {
                        Fa=o.getClass().getDeclaredField("a");
                        Fb=o.getClass().getDeclaredField("b");
                        Fa.setAccessible(true);
                        Fb.setAccessible(true);
                    }
                    if (((ChunkCoordIntPair)Fa.get(o)).equals(chunkcoordintpair)) {
                        nbttagcompound = (NBTTagCompound)Fb.get(o);
                        break;
                    }
                }
            }
            } catch(Exception ex) {
                ex.printStackTrace();
            }
        }

        if (nbttagcompound == null) {
            DataInputStream datainputstream = RegionFileCache.b(this._d, i, j);

            if (datainputstream == null) {
                return null;
            }

            nbttagcompound = NBTCompressedStreamTools.a((DataInput) datainputstream);
        }

        return nbttagcompound;
    }

    public Chunk a(World world, int i, int j) {
        NBTTagCompound tags = this.load_async(world, i, j);
        return tags!=null?this.load_nbt(world, i, j, tags):null;
    }
    public Chunk load_nbt(World world, int i, int j, NBTTagCompound nbt) {
        return super.a(world, i, j, nbt);
    }

}
