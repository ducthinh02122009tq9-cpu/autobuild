package com.example.autobuild;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Đọc file .litematic (NBT nén gzip) bằng JDK thuần, không phụ thuộc Minecraft. */
public final class Litematic {
    public record Entry(String name, Map<String, String> props) {}

    public static final class Region {
        public int minX, minY, minZ, sx, sy, sz;
        public final List<Entry> palette = new ArrayList<>();
        public int[] idx; // thứ tự: (y * sz + z) * sx + x
    }

    public String name = "";
    public final List<Region> regions = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public static Litematic read(Path path) throws IOException {
        Object root;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            int t = in.readByte();
            in.readUTF();
            root = payload(in, t);
        }
        Map<String, Object> r = (Map<String, Object>) root;
        Litematic out = new Litematic();
        Object md = r.get("Metadata");
        if (md instanceof Map<?, ?> m && m.get("Name") instanceof String s) out.name = s;

        Map<String, Object> regs = (Map<String, Object>) r.get("Regions");
        for (Map.Entry<String, Object> e : regs.entrySet()) {
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            Map<String, Object> pos = (Map<String, Object>) m.get("Position");
            Map<String, Object> size = (Map<String, Object>) m.get("Size");
            int rx = num(size, "x"), ry = num(size, "y"), rz = num(size, "z");
            Region g = new Region();
            g.sx = Math.abs(rx);
            g.sy = Math.abs(ry);
            g.sz = Math.abs(rz);
            g.minX = num(pos, "x") + (rx < 0 ? rx + 1 : 0);
            g.minY = num(pos, "y") + (ry < 0 ? ry + 1 : 0);
            g.minZ = num(pos, "z") + (rz < 0 ? rz + 1 : 0);

            for (Object o : (List<Object>) m.get("BlockStatePalette")) {
                Map<String, Object> pm = (Map<String, Object>) o;
                Map<String, String> props = new LinkedHashMap<>();
                if (pm.get("Properties") instanceof Map<?, ?> pp) {
                    for (Map.Entry<?, ?> pe : pp.entrySet())
                        props.put(String.valueOf(pe.getKey()), String.valueOf(pe.getValue()));
                }
                g.palette.add(new Entry((String) pm.get("Name"), props));
            }

            long[] longs = (long[]) m.get("BlockStates");
            int total = g.sx * g.sy * g.sz;
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(g.palette.size() - 1));
            long mask = (1L << bits) - 1;
            g.idx = new int[total];
            for (int i = 0; i < total; i++) {
                long bit = (long) i * bits;
                int li = (int) (bit >>> 6);
                int off = (int) (bit & 63);
                long v = longs[li] >>> off;
                if (off + bits > 64) v |= longs[li + 1] << (64 - off);
                g.idx[i] = (int) (v & mask);
            }
            out.regions.add(g);
        }
        return out;
    }

    private static int num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).intValue();
    }

    private static Object payload(DataInputStream in, int t) throws IOException {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                int it = in.readByte();
                int n = in.readInt();
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) l.add(payload(in, it));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int tt = in.readByte();
                    if (tt == 0) break;
                    String k = in.readUTF();
                    m.put(k, payload(in, tt));
                }
                return m;
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("Kiểu NBT không hợp lệ: " + t);
        }
    }
}
