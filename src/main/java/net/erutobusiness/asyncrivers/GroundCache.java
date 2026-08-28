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

    /**
     * Answers, kept separately for each random state.
     *
     * <p><b>Why the random state is a key and not an assumption.</b> 3.4.1 kept one map per
     * generator and complained if the random state ever changed, on the reasoning that a
     * dimension builds exactly one. ⚠ That reasoning was never checked — and the sibling
     * assumption about the height accessor turned out to be wrong the same day, which is poor
     * grounds for trusting this one. Keying on it removes the question: a different random state
     * simply gets its own answers, and an unused one is collected.
     */
    private static final Map<RandomState, Map<ChunkGenerator, Map<Long, Integer>>> BY_RANDOM =
            new WeakHashMap<>();

    /**
     * The world height and random state each generator was first seen with.
     *
     * <p>Answers are keyed on the generator and the column, which is only sound if a generator
     * always comes with the same height accessor and random state — true for a dimension, and
     * an assumption everywhere else. Rather than write the assumption down and hope, the entries
     * are kept so the assumption can be checked every time and the cache dropped if it breaks.
     */
    private static final Map<ChunkGenerator, Object[]> OWNER = new WeakHashMap<>();

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
     * What the cached answers actually depend on, besides the generator and the column.
     *
     * <p>{@code getBaseHeight} reads only the bottom and top of the world out of the height
     * accessor, so two accessors describing the same dimension are interchangeable — and world
     * generation hands out different ones freely. Comparing the objects themselves said "this is
     * a different world" every time that happened. The random state is compared by identity
     * because a dimension builds exactly one and never replaces it.
     */
    private static Object[] shapeOf(LevelHeightAccessor level, RandomState randomState) {
        return new Object[] {level.getMinBuildHeight(), level.getMaxBuildHeight()};
    }

    /** Whether the remembered answers were computed for the world this call describes. */
    private static boolean sameWorldShape(Object[] had, LevelHeightAccessor level,
                                          RandomState randomState) {
        return had.length == 2
            && Integer.valueOf(level.getMinBuildHeight()).equals(had[0])
            && Integer.valueOf(level.getMaxBuildHeight()).equals(had[1]);
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
        synchronized (BY_RANDOM) {
            // ⚠⚠ **乱数は鍵にする**（3.4.3）。⚠ 作り直されないという前提を**確かめられなかった**ので、
            //    ⚠ **前提を要らなくした**——別の乱数が来れば、勝手に別の控えになる。
            Map<ChunkGenerator, Map<Long, Integer>> byGen =
                BY_RANDOM.computeIfAbsent(randomState, r -> new WeakHashMap<>());
            map = byGen.get(generator);
            if (map == null) {
                map = new HashMap<>();
                byGen.put(generator, map);
                OWNER.put(generator, shapeOf(level, randomState));
            } else {
                // ⚠⚠ **鍵は列の座標と生成器だけ。** 高さの入れ物と乱数は
                //    **生成器ごとに1つ**という前提で入れていない。
                //    ⚠ **前提は書いて終わりにせず、破れたら鳴らす。**
                //    ⚠ もし別の高さや別の乱数で同じ生成器が使われたら、
                //    ⚠ **覚えている答えは別の世界のもの**になり、村が違う場所へ動く。
                //
                // ⚠⚠ **比べるのは「同じオブジェクトか」ではなく「同じ値か」**（2026-08-28 に直した）。
                //    ⚠ 3.4.1 では `level != had[0]` と**入れ物の同一性**で見ていた。
                //    ⚠⚠ **ワールド生成は次元が同じでも別の入れ物を渡してくる**ので、
                //    ⚠ **正しい状態なのに鳴って控えを捨てていた**（新規ワールドを作る検査で実際に1本出た）。
                //    ⚠ `getBaseHeight` が高さの入れ物から使うのは
                //    **最低の高さ**と**高さ**だけなので、⚠ **その2つを比べれば足りる。**
                //
                // ⚠⚠ **乱数はここで比べない**（3.4.3）。⚠ 3.4.2 では「次元ごとに1つ作られ、
                //    作り直されない」と書いて同一性で見ていたが、⚠⚠ **それは確かめていない前提**だった。
                //    ⚠ 同じ日に、その兄弟の前提（高さの入れ物）が**外れたばかり**なのに、
                //    ⚠ もう片方を信じる理由が無い。⚠ **乱数は控えの鍵そのものにして、前提を要らなくした。**
                Object[] had = OWNER.get(generator);
                if (had != null && !sameWorldShape(had, level, randomState)) {
                    AsyncRivers.LOGGER.error(
                            "⚠⚠ 同じ生成器が別の世界の形で使われた（高さ {}..{} / 乱数 {}）。"
                                    + "⚠ **控えを捨てる**（覚えていた答えは別の世界のもの）",
                            level.getMinBuildHeight(), level.getMaxBuildHeight(),
                            System.identityHashCode(randomState));
                    map.clear();
                    OWNER.put(generator, shapeOf(level, randomState));
                }
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
            synchronized (BY_RANDOM) {
                Map<ChunkGenerator, Map<Long, Integer>> byGen = BY_RANDOM.get(randomState);
                map = byGen == null ? null : byGen.get(generator);
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
