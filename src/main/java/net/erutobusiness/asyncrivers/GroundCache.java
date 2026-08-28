package net.erutobusiness.asyncrivers;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Remembers {@code getBaseHeight} answers, because asking again costs about 28 milliseconds.
 *
 * <p><b>Why this exists.</b> {@code NoiseBasedChunkGenerator.getBaseHeight} builds a whole
 * {@code NoiseChunk} on every call, and building one runs {@code NoiseRouter.mapAll} over the
 * entire density-function graph. On a world using Tectonic that graph carries cubic splines
 * nested dozens deep, so the walk is enormous — and it happens once per column, not once per
 * chunk. Two {@code jstack} dumps taken 48 seconds apart on 2026-08-28 both landed inside it:
 * one in {@code CubicSpline$Multipoint.hashCode} (the wrap cache's map lookup), one in
 * {@code CubicSpline$Multipoint.mapAll} (rebuilding the tree). Neither dump showed anything else.
 *
 * <p><b>Why a cache helps.</b> The nudge samples a square around each structure, and those
 * squares overlap. Measured over 251 nudges from one burn: against the previous twenty squares
 * the median overlap is <b>55.6%</b>, and 52% of squares overlap an earlier one by more than
 * half. Every overlapping column is the same question with the same answer.
 *
 * <p><b>Why it is safe.</b> {@code getBaseHeight} is a pure function of the generator, the world
 * height, the random state and the column. Nothing here changes an answer; it only avoids asking
 * twice. The map is keyed on the generator so two dimensions cannot share entries, and it is
 * weak so a world that unloads takes its entries with it.
 *
 * <p><b>Why it is capped.</b> A bake walks millions of columns and would otherwise keep every
 * one. At the cap the whole map for that generator is dropped rather than evicted one by one —
 * the sampling walks a moving front, so the oldest entries are the ones that stop being asked
 * for, and rebuilding the working set costs one pass.
 */
public final class GroundCache {

    /**
     * Columns kept per dimension before the map is dropped.
     *
     * <p>262,144 entries is roughly 78 squares of 3,364 columns — comfortably more than the
     * twenty-square window the overlap was measured over, and a few megabytes of boxed longs.
     */
    private static final int MAX_PER_LEVEL = 1 << 18;

    private static final Map<ChunkGenerator, Map<Long, Integer>> BY_GENERATOR =
            new WeakHashMap<>();

    /** How many answers were reused, and how many had to be computed. Logged on request. */
    private static long hits;
    private static long misses;

    private GroundCache() {
    }

    /**
     * The surface height at this column, computed once per (dimension, column).
     *
     * <p>Callers must treat this exactly as they would treat calling the generator directly:
     * the value is the same one, and the only difference is how long it took.
     */
    public static int baseHeight(ChunkGenerator generator, LevelHeightAccessor level,
                                 RandomState randomState, int x, int z) {
        Long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        Map<Long, Integer> map;
        synchronized (BY_GENERATOR) {
            map = BY_GENERATOR.get(generator);
            if (map == null) {
                map = new HashMap<>();
                BY_GENERATOR.put(generator, map);
            }
        }
        synchronized (map) {
            Integer got = map.get(key);
            if (got != null) {
                hits++;
                return got;
            }
        }
        // ⚠⚠ **計算は錠の外でやる。** 1回 28ms なので、⚠ ここを錠の中に入れると
        //    ⚠ **31 本のワーカーが1本ずつ順番待ちになり、いまより遅くなる。**
        //    ⚠ 同じ列を2本が同時に計算することは在るが、⚠ **答えは同じなので害は無い。**
        int value = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                level, randomState);
        synchronized (map) {
            if (map.size() >= MAX_PER_LEVEL) {
                map.clear();
            }
            map.put(key, value);
            misses++;
        }
        return value;
    }

    /** Reused answers, for a log line that says whether the cache is doing anything. */
    public static long hits() {
        return hits;
    }

    /** Answers that had to be computed. */
    public static long misses() {
        return misses;
    }

    /** Percentage of asks that were answered from memory, or -1 before anything was asked. */
    public static int hitRate() {
        long total = hits + misses;
        return total == 0 ? -1 : (int) (hits * 100 / total);
    }
}
