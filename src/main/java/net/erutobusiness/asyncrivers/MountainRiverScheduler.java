package net.erutobusiness.asyncrivers;

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
 * the server thread. {@link net.erutobusiness.asyncrivers.mixin.ModEventsMixin} cancels the
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
     * How many regions Dynamic Waters' {@code MOUNTAIN_REGION_CACHE} holds. Past this, pre-warming
     * evicts what it just built, so we say so rather than pretending the far edge stays warm.
     */
    private static final int REGION_CACHE_CAPACITY = 2048;

    /**
     * Refuse to pre-warm past this radius, in regions (≈ 16,000 blocks).
     *
     * <p>This is a sanity check, not a tuning knob. A world with no border set reports vanilla's
     * default of 59,999,968 blocks across — 117,188 regions of radius, 5.5e10 regions in total.
     * Nothing real is that big, so a number in that range means "the border has not been set yet",
     * which is exactly what a freshly created world looks like before the pre-generation tooling
     * runs. Pre-warming there is not slow; it never finishes at all.
     */
    private static final int MAX_PREWARM_RADIUS_REGIONS = 64;

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
                Thread t = new Thread(r, "asyncrivers-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 2);
                return t;
            }
        });
        AsyncRivers.LOGGER.info("Async Rivers: mountain river regions will be built on {} worker thread(s).", threads);
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
     * it to itself whenever the pre-warm is holding off.
     */
    public static void startPreWarm(MinecraftServer server, ServerLevel level) {
        ExecutorService pool = workers;
        if (pool == null || pool.isShutdown()) {
            return;
        }
        Thread preWarmer = new Thread(() -> preWarm(server, level, pool), "asyncrivers-prewarm");
        preWarmer.setDaemon(true);
        preWarmer.setPriority(Thread.MIN_PRIORITY);
        preWarmer.start();
    }

    private static void preWarm(MinecraftServer server, ServerLevel level, ExecutorService pool) {
        BlockPos spawn = level.getSharedSpawnPos();
        int centerX = Math.floorDiv(spawn.getX(), REGION_SIZE);
        int centerZ = Math.floorDiv(spawn.getZ(), REGION_SIZE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        double borderSize = level.getWorldBorder().getSize();
        int radius = (int) Math.ceil(borderSize / 2.0 / REGION_SIZE);

        // ⚠ ボーダーが設定されていないワールドでは、ここに入ってはいけない（2026-08-07 追加）。
        //    バニラの既定は直径 59,999,968 で、半径 117,188 リージョン＝549億リージョンになる。
        //    それ以前の版はこれを int で数えていたので total が溢れて **負数**になり
        //    （実ログ: "sweeping -901996719 regions ... radius 117188 regions"）、
        //    さらに全リージョンを ArrayList へ積む作りだったため、
        //    先読みスレッドが延々とメモリを確保し続けて 1 リージョンも仕上げないまま回り続けた。
        //    検証台本サーバの 33 本のログすべてと、本番同等サーバの新規ワールド初回起動で再現した。
        if (radius > MAX_PREWARM_RADIUS_REGIONS) {
            AsyncRivers.LOGGER.warn(
                    "Async Rivers: pre-warm skipped — the world border is {} blocks across "
                            + "({} regions of radius), which is past the {}-region limit. "
                            + "This is what a world with no border set looks like. Set the border to the "
                            + "pre-generated area (this server does that from pregen.py) and restart to enable it.",
                    (long) borderSize, radius, MAX_PREWARM_RADIUS_REGIONS);
            return;
        }

        long total = (2L * radius + 1L) * (2L * radius + 1L);
        AsyncRivers.LOGGER.info(
                "Async Rivers: pre-warming {} regions out to the world border (radius {} regions) while the server is empty.",
                total, radius);

        if (total > REGION_CACHE_CAPACITY) {
            // ⚠ 先読みしても、仕上げた端から Dynamic Waters 側のキャッシュから追い出される。
            //    上限を付けるだけでは解決しない（外側が冷えるので遅さが移動するだけ）ので、
            //    ここでは黙って進めず、数字を出して人に判断させる。
            AsyncRivers.LOGGER.warn(
                    "Async Rivers: {} regions is more than Dynamic Waters keeps ({}). "
                            + "The outer ones will evict the inner ones as we go. Pre-warming anyway, "
                            + "but the far edge will not stay warm.",
                    total, REGION_CACHE_CAPACITY);
        }

        int done = 0;
        boolean wasPaused = false;
        // 中心から外へ。近い場所ほど先に要るので、リングの順に並べる。
        // ⚠ 上の半径ガードで件数が抑えられているので、ここで一度に積んでよい。
        //    ガードを緩めるなら、この積み方も同時に見直すこと（それが元の穴だった）。
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

        for (int i = 0; i < order.size(); i += workerCount) {
            if (Thread.currentThread().isInterrupted() || workers == null) {
                return;
            }
            // Autopause と同じ約束: 人が居る間は進めない
            while (server.getPlayerList().getPlayerCount() > 0) {
                if (!wasPaused) {
                    AsyncRivers.LOGGER.info("Async Rivers: player online — pre-warm paused at {} / {} regions.",
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
                AsyncRivers.LOGGER.info("Async Rivers: server empty again — pre-warm resumed at {} / {} regions.",
                        done, total);
                wasPaused = false;
            }

            // ワーカーの本数ぶんまとめて投げ、全部そろってから次へ（進み具合を数えられるように）
            List<Future<?>> batch = new ArrayList<>();
            for (int j = i; j < Math.min(i + workerCount, order.size()); j++) {
                int[] r = order.get(j);
                batch.add(pool.submit(() -> HydrologyManager.getMountainRegionPaths(
                        r[0], r[1], generator, level, randomState)));
            }
            for (Future<?> f : batch) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    AsyncRivers.LOGGER.warn("Async Rivers: a pre-warmed region failed to build", t);
                }
                done++;
            }
            if (done % 25 < workerCount) {
                AsyncRivers.LOGGER.info("Async Rivers: pre-warmed {} / {} regions.", done, total);
            }
        }
        AsyncRivers.LOGGER.info("Async Rivers: pre-warm finished ({} regions). Joins inside the border no longer "
                + "wait for mountain rivers to be built.", done);
    }

    /**
     * Queues every not-yet-sent region around the watched chunk. Called on the server thread from
     * the mixin, and returns immediately — the generation itself happens on a worker.
     *
     * <p>Never paused, unlike {@link #startPreWarm}: this is work a player is actually waiting
     * on, and the pre-warm steps aside while anyone is online.
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
                    AsyncRivers.LOGGER.warn("Async Rivers: could not queue region {},{}", regionX, regionZ, t);
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
            AsyncRivers.LOGGER.warn("Async Rivers: region {},{} failed to build", regionX, regionZ, t);
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
                AsyncRivers.LOGGER.warn("Async Rivers: region {},{} failed to send", regionX, regionZ, t);
            }
        });
    }
}
