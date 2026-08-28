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

    /**
     * When on, every reused answer is checked against a freshly computed one.
     *
     * <p><b>Why this exists.</b> The claim behind 3.3.0 is "the same value, asked for less
     * often". Reading the code is not evidence that the key is injective or that the grid index
     * still lines up with the column it names, and a wrong height here does not crash — it moves
     * a village, quietly, in a world nobody diffs for hours. So the claim gets a switch that
     * makes the mod prove it: compute both, compare, and say so.
     *
     * <p>It throws away the entire speed gain while it is on, which is the point — it is meant to
     * be run once against a real world, checked, and turned off. {@code asyncriversVerifyGround}.
     */
    private static volatile boolean verify =
            Boolean.getBoolean("asyncriversVerifyGround");

    /** Mismatches seen while {@link #verify} was on. Zero is the only acceptable number. */
    private static long mismatches;

    /** Columns checked while {@link #verify} was on. */
    private static long verified;

    /**
     * True only while the self-test has a deliberately wrong value planted.
     *
     * <p>Without this the planted value is reported the same way a real disagreement would be,
     * and a log that cries wolf once teaches the next reader to skim past the time it matters.
     */
    private static volatile boolean planting;

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
                if (!verify) {
                    return got;
                }
                // ⚠⚠ **覚えていた答えを、いま計算し直した答えと突き合わせる**（確かめる回だけ）。
                //    ⚠ 速さは全部捨てるが、⚠ **それがこの旗の役目。**
                int fresh = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                        level, randomState);
                verified++;
                if (fresh != got) {
                    mismatches++;
                    // ⚠⚠ **仕込んだ1件を ERROR で出さない**（2026-08-28）。
                    //    ⚠ 自己試験は**わざと壊した値**を1件置いて、捕まえられるかを見る。
                    //    ⚠ それを ERROR にすると、⚠ **本物の1件が同じ顔で並ぶ**——
                    //    当部の「鳴り続ける検査は本物を埋める」に当たる。
                    if (planting) {
                        AsyncRivers.LOGGER.info(
                                "自己試験の仕込みを捕まえた: ({}, {}) 控え {} / 計算し直し {}",
                                x, z, got, fresh);
                    } else {
                        AsyncRivers.LOGGER.error(
                                "⚠⚠ 地面の高さの控えが合わない: ({}, {}) 控え {} / 計算し直し {}"
                                        + " — 合わない数 {} / 見た数 {}",
                                x, z, got, fresh, mismatches, verified);
                    }
                }
                // ⚠⚠ **走った証拠を出す。** ⚠ 「合わない 0 件」は、
                //    ⚠ **確かめが走っていないときも 0 件**なので、
                //    ⚠ **見た数を印字しないと何も言えない**（当部の「陽性対照が無い NG は報告しない」と同じ）。
                if (verified == 1 || verified % 5000 == 0) {
                    AsyncRivers.LOGGER.info("地面の高さの控えを確かめている: {}", verdict());
                }
                return fresh;
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

    /** Whether every reused answer is being checked against a fresh one. */
    public static boolean verifying() {
        return verify;
    }

    /**
     * Proves, at startup, that a remembered height is the height the generator gives.
     *
     * <p><b>Why a self-test and not just waiting.</b> Remembered answers are only reused where
     * two sampled squares overlap, and on a quiet stretch of a bake that can be hours away. A
     * run that reports "no disagreements" without ever having compared anything says nothing —
     * it looks exactly like a run where the check never fired. So the check is made to fire on
     * demand, over a handful of columns, before anything depends on it.
     *
     * <p><b>Both directions are tested.</b> Real columns must agree, and a deliberately
     * corrupted entry must be caught. A test that only checks the agreeing case passes just as
     * happily when the comparison is broken.
     *
     * @return true when the remembered answers agreed and the planted wrong one was caught
     */
    public static boolean selfTest(ChunkGenerator generator, LevelHeightAccessor level,
                                   RandomState randomState, int columns) {
        int[] xs = new int[columns];
        int[] zs = new int[columns];
        int[] first = new int[columns];
        for (int i = 0; i < columns; i++) {
            // ⚠ 実際に焼いている辺りから採る（原点まわりは既に生成済みで偏る）
            xs[i] = 4096 + i * 97;
            zs[i] = -4096 - i * 89;
            first[i] = baseHeight(generator, level, randomState, xs[i], zs[i]);
        }
        int disagreed = 0;
        for (int i = 0; i < columns; i++) {
            if (baseHeight(generator, level, randomState, xs[i], zs[i]) != first[i]) {
                disagreed++;
            }
        }

        // ⚠⚠ **陽性対照**: わざと1件だけ違う値を覚えさせ、⚠ **突き合わせが捕まえるか**を見る。
        //    ⚠ 捕まえられないなら、⚠ **「合わない 0 件」は何の証拠にもならない。**
        boolean caught = false;
        if (verify) {
            long before = mismatches;
            Long key = (((long) xs[0]) << 32) ^ (zs[0] & 0xFFFFFFFFL);
            Map<Long, Integer> map;
            synchronized (BY_GENERATOR) {
                map = BY_GENERATOR.get(generator);
            }
            if (map != null) {
                synchronized (map) {
                    map.put(key, first[0] + 1234);
                }
                planting = true;                // ⚠ この1件は ERROR で出さない
                try {
                    baseHeight(generator, level, randomState, xs[0], zs[0]);
                } finally {
                    planting = false;
                }
                caught = mismatches > before;
                mismatches = before;            // ⚠ 仕込んだぶんは数から戻す
                synchronized (map) {
                    map.put(key, first[0]);     // ⚠ 正しい値へ戻す
                }
            }
        }

        boolean ok = disagreed == 0 && (!verify || caught);
        AsyncRivers.LOGGER.info(
                "地面の高さの控えの自己試験: {} 列 ／ 食い違い {} 件（0 が正）"
                        + " ／ わざと壊した1件を捕まえた: {}（確かめる旗 {}）／ 判定 {}",
                columns, disagreed, caught, verify, ok ? "OK" : "⚠ **NG**");
        if (!ok) {
            AsyncRivers.LOGGER.error(
                    "⚠⚠ 地面の高さの控えが信用できない。⚠ **この版を本番へ入れないこと。**");
        }
        return ok;
    }

    /** Columns checked while verifying, and how many disagreed. Zero disagreements is the goal. */
    public static String verdict() {
        if (!verify && verified == 0) {
            return "確かめていない（-DasyncriversVerifyGround=true で確かめる）";
        }
        return String.format("控えを %d 列ぶん突き合わせて、合わないもの %d 件",
                verified, mismatches);
    }
}
