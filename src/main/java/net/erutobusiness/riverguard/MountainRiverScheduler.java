package net.erutobusiness.riverguard;

import com.AdamNew.dynamicwaters.HydrologyManager;
import com.AdamNew.dynamicwaters.MountainRiverPath;
import com.AdamNew.dynamicwaters.network.ModNetwork;
import com.AdamNew.dynamicwaters.network.SyncMountainRiversPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Dynamic Waters' mountain-river region generation off the server thread.
 *
 * <p>This mirrors what {@code DynamicWaters$ModEvents.onChunkWatch} does, with one difference:
 * the expensive part happens on a worker thread, and only the packet send is bounced back to
 * the server thread. {@link net.erutobusiness.riverguard.mixin.ModEventsMixin} cancels the
 * original handler so the two never both run.
 *
 * <p><b>Why this is safe off-thread.</b> Dynamic Waters already calls
 * {@code HydrologyManager.getMountainRegionPaths} from worldgen worker threads (the carving
 * call sites pass {@code cachedOnly=false}). Its scratch state is {@code ThreadLocal}
 * ({@code BASE_HEIGHT_CACHE}, {@code MOUNTAIN_RIVER_CONTEXT}, {@code RIVER_CONTEXT}) and the
 * region store is a Guava cache, which is thread-safe. Concurrency is the mod's normal mode.
 *
 * <p><b>What changes for the player.</b> Mountain river flow arrives a few seconds after joining
 * instead of blocking the join. Terrain is unaffected — carving happens during world generation,
 * on worldgen threads, and this class does not touch it.
 */
public final class MountainRiverScheduler {

    /** Dynamic Waters uses 256-block regions ({@code HydrologyManager.MOUNTAIN_REGION_SIZE}). */
    private static final int REGION_SIZE = 256;

    /** Dynamic Waters scans this many regions either side of the watched chunk's region. */
    private static final int REGION_SPAN = 2;

    /**
     * Regions already handed to each player, so a region is computed once per player per session.
     * Kept here rather than reusing Dynamic Waters' own {@code SENT_REGIONS}: we cancel its
     * handler, so its map stays empty and is no longer the source of truth.
     */
    private static final Map<UUID, Set<Long>> CLAIMED = new ConcurrentHashMap<>();

    private static volatile ExecutorService workers;
    private static volatile int workerCount = 1;

    private MountainRiverScheduler() {
    }

    public static synchronized void start() {
        if (workers != null) {
            return;
        }
        // Leave most of the machine to the server thread and to vanilla worldgen.
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        workerCount = threads;
        workers = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "riverguard-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 2);
                return t;
            }
        });
        RiverGuard.LOGGER.info("River Guard: mountain river regions will be built on {} worker thread(s).", threads);
    }

    public static synchronized void stop() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        CLAIMED.clear();
    }

    public static void forget(UUID playerId) {
        CLAIMED.remove(playerId);
    }

    /**
     * Fills the region cache outward from spawn while the server is empty, and stops while anyone
     * is online — the same deal Chunky Autopause gives the chunk pre-generation.
     *
     * <p><b>Why.</b> A fixed block of regions around spawn is not enough. Step outside it
     * and chunk generation hits a cold region again, and the server thread waits for it in
     * {@code getChunkBlocking} (measured: 26,518 ms on a join just past the pre-warmed edge). The
     * work has to happen either way; the only question is whether a player is sitting there while
     * it does. So do it when nobody is.
     *
     * <p>Bounded by the overworld world border, which this server keeps in step with how far
     * Chunky has actually pre-generated. At a border radius of 1,719 that is about 196 regions —
     * roughly 8 minutes of empty-server time, and well inside the mod's 2,048-region cache.
     *
     * <p>On-demand work ({@link #onChunkWatched}) is never paused; it shares the same pool and gets
     * it to itself whenever the sweep is holding off.
     */
    public static void startBorderSweep(MinecraftServer server, ServerLevel level) {
        ExecutorService pool = workers;
        if (pool == null || pool.isShutdown()) {
            return;
        }
        Thread sweeper = new Thread(() -> sweep(server, level, pool), "riverguard-sweeper");
        sweeper.setDaemon(true);
        sweeper.setPriority(Thread.MIN_PRIORITY);
        sweeper.start();
    }

    private static void sweep(MinecraftServer server, ServerLevel level, ExecutorService pool) {
        BlockPos spawn = level.getSharedSpawnPos();
        int centerX = Math.floorDiv(spawn.getX(), REGION_SIZE);
        int centerZ = Math.floorDiv(spawn.getZ(), REGION_SIZE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        int radius = (int) Math.ceil(level.getWorldBorder().getSize() / 2.0 / REGION_SIZE);
        int total = (radius * 2 + 1) * (radius * 2 + 1);
        RiverGuard.LOGGER.info(
                "River Guard: sweeping {} regions out to the world border (radius {} regions) while the server is empty.",
                total, radius);

        // 中心から外へ。近い場所ほど先に要るので、リングの順に並べる
        List<int[]> order = new ArrayList<>();
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        order.add(new int[]{centerX + dx, centerZ + dz});
                    }
                }
            }
        }

        int done = 0;
        int skipped = 0;
        boolean wasPaused = false;
        for (int i = 0; i < order.size(); i += workerCount) {
            if (Thread.currentThread().isInterrupted() || workers == null) {
                return;
            }
            // Autopause と同じ約束: 人が居る間は進めない
            while (server.getPlayerList().getPlayerCount() > 0) {
                if (!wasPaused) {
                    RiverGuard.LOGGER.info("River Guard: player online — sweep paused at {} / {} regions.",
                            done, total);
                    wasPaused = true;
                }
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (wasPaused) {
                RiverGuard.LOGGER.info("River Guard: server empty again — sweep resumed at {} / {} regions.",
                        done, total);
                wasPaused = false;
            }

            // ワーカーの本数ぶんまとめて投げ、全部そろってから次へ（進み具合を数えられるように）
            List<Future<?>> batch = new ArrayList<>();
            for (int j = i; j < Math.min(i + workerCount, order.size()); j++) {
                int[] r = order.get(j);
                if (RegionStore.has(r[0], r[1])) {
                    // 前回の起動で作って保存してある。読み込み済みなので作り直さない
                    skipped++;
                    done++;
                    continue;
                }
                batch.add(pool.submit(() -> {
                    List<MountainRiverPath> built = HydrologyManager.getMountainRegionPaths(
                            r[0], r[1], generator, level, randomState);
                    RegionStore.remember(r[0], r[1], built);
                    return built;
                }));
            }
            for (Future<?> f : batch) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    RiverGuard.LOGGER.warn("River Guard: a swept region failed to build", t);
                }
                done++;
            }
            if (done % 25 < workerCount) {
                RiverGuard.LOGGER.info("River Guard: sweep {} / {} regions ({} read from disk).",
                        done, total, skipped);
                RegionStore.save();   // 途中で落ちても、ここまでの分は次の起動で効く
            }
        }
        RegionStore.save();
        RiverGuard.LOGGER.info("River Guard: sweep finished ({} regions, {} of them read from disk). "
                + "Joins inside the border no longer wait for mountain rivers to be built.", done, skipped);
    }

    /**
     * Queues every not-yet-sent region around the watched chunk. Called on the server thread from
     * the mixin, and returns immediately — the generation itself happens on a worker.
     *
     * <p>Never paused, unlike {@link #startBorderSweep}: this is work a player is actually waiting
     * on, and the sweep steps aside while anyone is online.
     */
    public static void onChunkWatched(ServerPlayer player, ServerLevel level, int chunkX, int chunkZ) {
        ExecutorService pool = workers;
        if (pool == null || pool.isShutdown()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }

        int baseRegionX = Math.floorDiv(chunkX * 16, REGION_SIZE);
        int baseRegionZ = Math.floorDiv(chunkZ * 16, REGION_SIZE);
        UUID playerId = player.getUUID();
        Set<Long> claimed = CLAIMED.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet());

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        for (int dx = -REGION_SPAN; dx <= REGION_SPAN; dx++) {
            for (int dz = -REGION_SPAN; dz <= REGION_SPAN; dz++) {
                int regionX = baseRegionX + dx;
                int regionZ = baseRegionZ + dz;
                long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
                if (!claimed.add(key)) {
                    continue;
                }
                try {
                    pool.execute(() -> build(server, playerId, claimed, key, regionX, regionZ,
                            generator, level, randomState));
                } catch (Throwable t) {
                    // Could not even queue it. Release the claim so a later ChunkWatch retries.
                    claimed.remove(key);
                    RiverGuard.LOGGER.warn("River Guard: could not queue region {},{}", regionX, regionZ, t);
                }
            }
        }
    }

    private static void build(MinecraftServer server, UUID playerId, Set<Long> claimed, long key,
                              int regionX, int regionZ, ChunkGenerator generator,
                              ServerLevel level, RandomState randomState) {
        List<MountainRiverPath> paths;
        try {
            paths = HydrologyManager.getMountainRegionPaths(regionX, regionZ, generator, level, randomState);
        } catch (Throwable t) {
            // ⚠ Release the claim. Without this the region is never retried and that player
            //    silently loses mountain river flow there for the rest of the session.
            claimed.remove(key);
            RiverGuard.LOGGER.warn("River Guard: region {},{} failed to build", regionX, regionZ, t);
            return;
        }

        server.execute(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(playerId);
            if (online == null) {
                // Logged out mid-build. forget() has already dropped the set; nothing to send.
                return;
            }
            try {
                ModNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> online),
                        new SyncMountainRiversPacket(regionX, regionZ, paths));
            } catch (Throwable t) {
                claimed.remove(key);
                RiverGuard.LOGGER.warn("River Guard: region {},{} failed to send", regionX, regionZ, t);
            }
        });
    }
}
