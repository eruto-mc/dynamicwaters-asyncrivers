package net.erutobusiness.riverguard;

import com.AdamNew.dynamicwaters.HydrologyManager;
import com.AdamNew.dynamicwaters.MountainRiverPath;
import com.AdamNew.dynamicwaters.network.SyncMountainRiversPacket;
import io.netty.buffer.Unpooled;
import net.erutobusiness.riverguard.mixin.SyncMountainRiversPacketAccessor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps built mountain river regions on disk so a restart does not have to rebuild them.
 *
 * <h2>Why</h2>
 *
 * Dynamic Waters keeps its regions in memory only, so every restart starts cold and the sweep has
 * to run again — 289 regions, about 32 minutes, during which the server is not really usable.
 * Since the regions are a pure function of (seed, region coordinates), they can simply be saved.
 *
 * <p>Measured before this existed: startup 21:44:43 → sweep finished ~32 minutes later.
 *
 * <h2>Format</h2>
 *
 * {@code <world>/data/riverguard/mountain-regions.bin}
 *
 * <pre>
 * int    magic 'RGD1'
 * long   world seed        (mismatch = discard the whole file)
 * int    region count
 * repeat: int byteLength, then that many bytes = one SyncMountainRiversPacket
 * </pre>
 *
 * <p>Each record is encoded with the mod's own packet, so the per-path layout is never guessed at.
 * See {@link SyncMountainRiversPacketAccessor} for why.
 *
 * <p><b>Safety.</b> The seed check means a rebuilt world can never be served stale rivers. A file
 * that fails to read is discarded and rebuilt — losing it costs time, never correctness, because
 * everything in it can be recomputed.
 */
public final class RegionStore {

    private static final int MAGIC = 0x52474431;   // 'R','G','D','1'
    private static final String DIR = "riverguard";
    private static final String FILE = "mountain-regions.bin";

    /** What we have on disk, so a save can write everything back out. Region key → encoded bytes. */
    private static final Map<Long, byte[]> HELD = new LinkedHashMap<>();

    private static volatile Path file;
    private static volatile long seed;
    private static volatile boolean dirty;

    private RegionStore() {
    }

    public static synchronized int load(ServerLevel level) {
        HELD.clear();
        dirty = false;
        seed = level.getSeed();
        Path dir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR);
        file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        int restored = 0;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC) {
                RiverGuard.LOGGER.warn("River Guard: {} is not ours — ignoring it.", file);
                return 0;
            }
            long fileSeed = in.readLong();
            if (fileSeed != seed) {
                RiverGuard.LOGGER.warn("River Guard: saved regions are for seed {} but this world is {} "
                        + "— discarding them.", fileSeed, seed);
                return 0;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                if (len <= 0 || len > (1 << 24)) {
                    throw new IOException("region record length out of range: " + len);
                }
                byte[] bytes = in.readNBytes(len);
                if (bytes.length != len) {
                    throw new EOFException("truncated region record");
                }
                if (restore(bytes)) {
                    restored++;
                }
            }
        } catch (Throwable t) {
            // Everything here is recomputable, so a bad file is an inconvenience, not a fault.
            RiverGuard.LOGGER.warn("River Guard: could not read {} — starting from scratch.", file, t);
            HELD.clear();
            return 0;
        }
        return restored;
    }

    /** Turns one saved record back into cache entries. */
    private static boolean restore(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            SyncMountainRiversPacket packet = new SyncMountainRiversPacket(buf);
            SyncMountainRiversPacketAccessor access = (SyncMountainRiversPacketAccessor) (Object) packet;
            int rx = access.riverguard$regionX();
            int rz = access.riverguard$regionZ();
            List<MountainRiverPath> paths = access.riverguard$paths();
            if (paths == null) {
                return false;
            }
            long key = HydrologyManager.getRegionKey(rx, rz);
            HydrologyManager.MOUNTAIN_REGION_CACHE.put(key, new ArrayList<>(paths));
            HELD.put(key, bytes);
            return true;
        } catch (Throwable t) {
            RiverGuard.LOGGER.warn("River Guard: skipped an unreadable saved region", t);
            return false;
        } finally {
            buf.release();
        }
    }

    /** Remembers a freshly built region. Cheap: encodes now, writes to disk later. */
    public static void remember(int regionX, int regionZ, List<MountainRiverPath> paths) {
        if (file == null || paths == null) {
            return;
        }
        long key = HydrologyManager.getRegionKey(regionX, regionZ);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new SyncMountainRiversPacket(regionX, regionZ, paths).encode(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            synchronized (RegionStore.class) {
                if (HELD.put(key, bytes) == null) {
                    dirty = true;
                }
            }
        } catch (Throwable t) {
            RiverGuard.LOGGER.warn("River Guard: could not encode region {},{} for saving",
                    regionX, regionZ, t);
        } finally {
            buf.release();
        }
    }

    /** Writes everything held, via a temporary file so a crash cannot leave a half-written store. */
    public static synchronized void save() {
        Path target = file;
        if (target == null || !dirty || HELD.isEmpty()) {
            return;
        }
        Path tmp = target.resolveSibling(FILE + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(MAGIC);
                out.writeLong(seed);
                out.writeInt(HELD.size());
                for (byte[] bytes : HELD.values()) {
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            RiverGuard.LOGGER.info("River Guard: saved {} mountain river regions to disk.", HELD.size());
        } catch (Throwable t) {
            RiverGuard.LOGGER.warn("River Guard: could not save regions to {}", target, t);
        }
    }

    public static synchronized boolean has(int regionX, int regionZ) {
        return HELD.containsKey(HydrologyManager.getRegionKey(regionX, regionZ));
    }

    public static synchronized void clear() {
        HELD.clear();
        file = null;
        dirty = false;
    }
}
