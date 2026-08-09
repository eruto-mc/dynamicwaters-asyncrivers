package net.erutobusiness.asyncrivers;

import com.AdamNew.dynamicwaters.MountainRiverPath;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps built mountain-river regions on disk, so a region is computed once for the life of a world
 * instead of once per server start.
 *
 * <p><b>Why.</b> Dynamic Waters' region store is memory-only. Every restart throws it away, and
 * whoever touches a region first pays to rebuild it — on a mountainous seed that is the expensive
 * half of chunk generation. Writing the result down turns "once per boot" into "once, ever".
 *
 * <p>The idea and the shape of it come from a club member's {@code dynamicwaters_async_backport}
 * (MIT). The file format here is our own; there is nothing to stay compatible with.
 *
 * <p><b>Layout.</b> {@code <world>/data/asyncrivers/v1/<dimension>/r.<x>.<z>.bin}, one file per
 * 256-block region. No seed in the path — the files already live inside the world that owns them.
 *
 * <p><b>Staleness.</b> Every file carries a stamp. A file whose stamp does not match the world it
 * is being read into is ignored and rebuilt, so a changed seed, a Dynamic Waters update, or an
 * edited config can never hand back paths that no longer match the terrain. The stamp covers:
 *
 * <ul>
 *   <li>the world seed,</li>
 *   <li>the installed Dynamic Waters version — we serialise that mod's own fields, so its layout
 *       is part of our format whether we like it or not,</li>
 *   <li>the bytes of its config file — river widths and the mountain-river toggle change what gets
 *       generated. ⚠ This is deliberately coarse: editing an unrelated setting (a flow speed, say)
 *       also invalidates the cache. Rebuilding costs time; handing back paths that disagree with
 *       the terrain costs correctness, so we spend the time.</li>
 * </ul>
 *
 * <p><b>Torn files.</b> Each region is written to a sibling {@code .tmp} and moved into place, so a
 * crash mid-write leaves either the old file or none — never half of one. A file that fails to read
 * for any reason is treated as absent.
 */
public final class MountainRegionStore {

    /** Bump when the field layout below changes. Old files then fail the stamp check and rebuild. */
    private static final int FORMAT_VERSION = 1;

    private static final int MAGIC = 0x41535231; // "ASR1"

    private final Path root;
    private final long stamp;

    /**
     * Regions we already know are on disk carrying {@link #stamp}.
     *
     * <p><b>Why this exists.</b> {@link #writeIfAbsent} is called from world generation, once per
     * region query — which during a pre-generation run means constantly. Without this set every
     * one of those calls would open a file and read sixteen bytes just to answer "already there?",
     * and the write-down would slow the very thing it is meant to speed up. Membership only ever
     * grows, and only from places that have just proven the file is good, so a hit needs no I/O.
     *
     * <p>It is deliberately one-way: we never remove on a failed write. A region wrongly believed
     * to be on disk is rebuilt next start, which is the cost this whole class exists to avoid but
     * is still correct. A region wrongly believed to be absent would be rewritten every query.
     */
    private final Set<Long> onDisk = ConcurrentHashMap.newKeySet();

    private MountainRegionStore(Path root, long stamp) {
        this.root = root;
        this.stamp = stamp;
    }

    private static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    /**
     * Builds the store for a level, or returns {@code null} if we cannot (in which case the caller
     * simply computes every time, as before — losing the cache is never worth failing a join over).
     */
    public static MountainRegionStore open(ServerLevel level) {
        try {
            ResourceLocation dim = level.dimension().location();
            // ⚠ normalize() を入れる。LevelResource.ROOT は "." なので、素で resolve すると
            //    ".\world3-...\.\data\..." のような読みにくいパスがログに出る（動きはする）。
            Path dir = level.getServer().getWorldPath(LevelResource.ROOT).normalize()
                    .resolve("data").resolve("asyncrivers").resolve("v" + FORMAT_VERSION)
                    .resolve(dim.getNamespace() + "_" + dim.getPath())
                    .toAbsolutePath().normalize();
            Files.createDirectories(dir);
            long stamp = stampFor(level);
            AsyncRivers.LOGGER.info("Async Rivers: region store at {} (stamp {}).",
                    dir, Long.toHexString(stamp));
            return new MountainRegionStore(dir, stamp);
        } catch (Throwable t) {
            AsyncRivers.LOGGER.warn("Async Rivers: no region store — regions will be rebuilt every start.", t);
            return null;
        }
    }

    private static long stampFor(ServerLevel level) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        DataOutputStream d = new DataOutputStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                md.update((byte) b);
            }
        });
        d.writeInt(FORMAT_VERSION);
        d.writeLong(level.getSeed());
        d.writeUTF(ModList.get().getModContainerById("dynamicwaters")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("absent"));

        // ⚠ config は「あれば混ぜる」。無いこと自体は異常ではない（既定値で動いている）ので、
        //    読めなければ「無し」として同じ印を出す。読めたのに中身が変わったときだけ印が変わる。
        Path cfg = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("dynamicwaters-common.toml");
        if (Files.isReadable(cfg)) {
            d.write(Files.readAllBytes(cfg));
        } else {
            d.writeUTF("no-config");
        }
        d.flush();

        byte[] h = md.digest();
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[i] & 0xFFL);
        }
        return v;
    }

    private Path fileFor(int regionX, int regionZ) {
        return root.resolve("r." + regionX + "." + regionZ + ".bin");
    }

    /** Returns the stored paths for a region, or {@code null} if there is nothing usable on disk. */
    public List<MountainRiverPath> read(int regionX, int regionZ) {
        Path p = fileFor(regionX, regionZ);
        if (!Files.isReadable(p)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(p);
             DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in))) {
            if (d.readInt() != MAGIC || d.readInt() != FORMAT_VERSION || d.readLong() != stamp) {
                return null;   // 別の世界・別の版・別の設定で書かれたもの。作り直させる
            }
            int count = d.readInt();
            List<MountainRiverPath> paths = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int nodeCount = d.readInt();
                int validNodeCount = d.readInt();
                boolean hitMainRiver = d.readBoolean();
                double[] xs = readDoubles(d);
                double[] zs = readDoubles(d);
                int[] ys = readInts(d);
                int[] terrainYs = readInts(d);
                double[] widths = readDoubles(d);
                // ⚠ この公開コンストラクタが外接範囲（minX/maxX/minZ/maxZ）を配列から計算し直し、
                //    intersectionsResolved も立てる。保存しているのは交差解決を済ませた後の値。
                paths.add(new MountainRiverPath(nodeCount, validNodeCount, hitMainRiver,
                        xs, zs, ys, terrainYs, widths));
            }
            // ここまで来たのは印まで合っていた証拠。以後 writeIfAbsent はファイルを開かない
            onDisk.add(key(regionX, regionZ));
            return paths;
        } catch (Throwable t) {
            // 壊れていても落とさない。作り直せば済む
            AsyncRivers.LOGGER.warn("Async Rivers: could not read region {},{} — rebuilding it.", regionX, regionZ, t);
            return null;
        }
    }

    /**
     * Writes a region only if there is no usable file for it yet.
     *
     * <p>For regions that arrived from somewhere else — world generation computes them while
     * carving, and puts them in Dynamic Waters' memory cache — so that they survive the restart
     * too. Checking the stamp matters: a file left over from a different seed or a different
     * Dynamic Waters version is not "already there", it is something to replace.
     */
    public void writeIfAbsent(int regionX, int regionZ, List<MountainRiverPath> paths) {
        if (onDisk.contains(key(regionX, regionZ))) {
            return;                                   // 一番多い経路。ファイルには触らない
        }
        if (hasCurrent(regionX, regionZ)) {
            onDisk.add(key(regionX, regionZ));        // 前の起動で書いたもの。以後は上の行で済む
            return;
        }
        write(regionX, regionZ, paths);
    }

    /** True when a file exists for this region and carries our stamp. */
    private boolean hasCurrent(int regionX, int regionZ) {
        Path p = fileFor(regionX, regionZ);
        if (!Files.isReadable(p)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(p);
             DataInputStream d = new DataInputStream(in)) {
            return d.readInt() == MAGIC && d.readInt() == FORMAT_VERSION && d.readLong() == stamp;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Writes a region. Failures are logged and ignored; the cache is an optimisation, not state. */
    public void write(int regionX, int regionZ, List<MountainRiverPath> paths) {
        Path dst = fileFor(regionX, regionZ);
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp);
                 DataOutputStream d = new DataOutputStream(new java.io.BufferedOutputStream(out))) {
                d.writeInt(MAGIC);
                d.writeInt(FORMAT_VERSION);
                d.writeLong(stamp);
                d.writeInt(paths.size());
                for (MountainRiverPath path : paths) {
                    d.writeInt(path.nodeCount);
                    d.writeInt(path.validNodeCount);
                    d.writeBoolean(path.hitMainRiver);
                    writeDoubles(d, path.segmentsX);
                    writeDoubles(d, path.segmentsZ);
                    writeInts(d, path.segmentsY);
                    writeInts(d, path.segmentsTerrainY);
                    writeDoubles(d, path.segmentWidths);
                }
            }
            try {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            onDisk.add(key(regionX, regionZ));   // ⚠ move が通ってから。失敗時は覚えない
        } catch (Throwable t) {
            AsyncRivers.LOGGER.warn("Async Rivers: could not write region {},{}.", regionX, regionZ, t);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 消せなくても次の書き込みが上書きする
            }
        }
    }

    private static double[] readDoubles(DataInputStream d) throws IOException {
        int n = d.readInt();
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = d.readDouble();
        }
        return a;
    }

    private static int[] readInts(DataInputStream d) throws IOException {
        int n = d.readInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = d.readInt();
        }
        return a;
    }

    private static void writeDoubles(DataOutputStream d, double[] a) throws IOException {
        d.writeInt(a.length);
        for (double v : a) {
            d.writeDouble(v);
        }
    }

    private static void writeInts(DataOutputStream d, int[] a) throws IOException {
        d.writeInt(a.length);
        for (int v : a) {
            d.writeInt(v);
        }
    }
}
