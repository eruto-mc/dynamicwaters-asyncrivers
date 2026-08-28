package net.erutobusiness.asyncrivers;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A grid of "is there river water here" answers, sampled once and reused.
 *
 * <p><b>Why a grid at all.</b> Dynamic Waters' own
 * {@code HydrologyManager.isTooCloseToRiver} takes a bounding box but only tests <b>nine
 * points</b> of it — the four corners, the four edge midpoints, and the centre. Read out of
 * {@code dynamicwaters-11.1.2.jar}, the method builds {@code {minX, maxX, (minX+maxX)/2}} and the
 * matching array for Z, then loops the 3x3 product. On a village that is 80x120 blocks, nine
 * points is not a test of the box; it is a test of nine columns.
 *
 * <p>That is exactly how two villages on seed K ended up straddling water and nothing moved them.
 * Measured on the burned world: {@code ctov:large/village_taiga} has water in <b>8.1%</b> of its
 * 2,501 columns and {@code terralith:fortified_village} in <b>4.3%</b> of 1,980 — and every one of
 * the nine sampled points landed on dry ground.
 *
 * <p><b>Why sampled once.</b> The nudge tries up to eight directions at several distances, so a
 * naive "ask per candidate" would repeat the same expensive query thousands of times per
 * structure. Every candidate lives inside the original box grown by the movement budget, so one
 * grid over that area answers all of them with array lookups.
 *
 * <p><b>The second grid is a measurement, not a decision.</b> Decisions use
 * {@link RiverProbe#inRiverChannel}. {@link RiverProbe#nearRiver} is sampled alongside it, when
 * {@code riverProbeCompare} is on, purely so the log line can state both numbers. Two versions in
 * a row changed the question the nudge asked and neither moved a single village, and in both cases
 * that only became provable hours later by diffing whole burned worlds. Carrying the comparison in
 * the log makes the next answer arrive with the first structure instead.
 */
public final class RiverMask {

    private final int originX;
    private final int originZ;
    private final int step;
    private final int cellsX;
    private final int cellsZ;
    private final boolean[] wet;
    private final boolean[] near;

    /**
     * Surface heights, filled in only where something actually asks for one.
     *
     * <p><b>Why lazily.</b> One entry costs about 28 milliseconds to compute (see
     * {@link GroundCache}). Two things read it: the wet test, which reaches the height only for
     * columns that already passed a horizontal distance check against a river, and
     * {@link #groundShift}, which reads the structure's own footprint and wherever a candidate
     * would put it. Both are a small part of the sampled square — measured on the burn logs,
     * columns near a river run 18-35% of the area — so filling the whole grid up front paid for
     * heights nothing ever looked at.
     */
    private final int[] ground;

    /** Not sampled yet. Chosen because no real surface height can be this. */
    private static final int UNSAMPLED = Integer.MIN_VALUE;

    private final ChunkGenerator generator;
    private final LevelHeightAccessor level;
    private final RandomState randomState;

    private RiverMask(int originX, int originZ, int step, int cellsX, int cellsZ,
                      boolean[] wet, boolean[] near, int[] ground,
                      ChunkGenerator generator, LevelHeightAccessor level,
                      RandomState randomState) {
        this.originX = originX;
        this.originZ = originZ;
        this.step = step;
        this.cellsX = cellsX;
        this.cellsZ = cellsZ;
        this.wet = wet;
        this.near = near;
        this.ground = ground;
        this.generator = generator;
        this.level = level;
        this.randomState = randomState;
    }

    /**
     * The surface height at a grid cell, computed on first ask and remembered after.
     *
     * <p>The value is whatever {@code getBaseHeight} would have returned; only the timing
     * differs. Answers are shared across masks by {@link GroundCache}, because the squares
     * sampled for nearby structures overlap by half.
     */
    private int groundAt(int ix, int iz) {
        int at = ix * cellsZ + iz;
        int got = ground[at];
        if (got != UNSAMPLED) {
            return got;
        }
        int value = GroundCache.baseHeight(generator, level, randomState,
                originX + ix * step, originZ + iz * step);
        ground[at] = value;
        return value;
    }

    /**
     * Samples the box grown by {@code reach} on every side.
     *
     * <p>{@code step} is coarsened if the area would need more samples than {@code maxSamples},
     * so a huge structure with a huge budget cannot turn one structure placement into a
     * six-figure pile of noise queries. Coarsening is reported by {@link #step()} rather than
     * being silent, because a mask sampled every 32 blocks can miss a narrow river and the caller
     * deserves to be able to say so.
     */
    public static RiverMask sample(BoundingBox box, int reach, int step, int maxSamples,
                                   double factor, int heightDelta, boolean compare,
                                   ChunkGenerator generator, LevelHeightAccessor level,
                                   RandomState randomState) {
        int minX = box.minX() - reach;
        int minZ = box.minZ() - reach;
        int spanX = (box.maxX() + reach) - minX;
        int spanZ = (box.maxZ() + reach) - minZ;

        int use = Math.max(1, step);
        while (((spanX / use) + 1L) * ((spanZ / use) + 1L) > maxSamples) {
            use *= 2;
        }

        int cellsX = (spanX / use) + 1;
        int cellsZ = (spanZ / use) + 1;
        boolean[] wet = new boolean[cellsX * cellsZ];
        boolean[] near = compare ? new boolean[cellsX * cellsZ] : null;
        int[] ground = new int[cellsX * cellsZ];
        java.util.Arrays.fill(ground, UNSAMPLED);
        RiverMask mask = new RiverMask(minX, minZ, use, cellsX, cellsZ, wet, near, ground,
                generator, level, randomState);
        for (int ix = 0; ix < cellsX; ix++) {
            int x = minX + ix * use;
            final int cellX = ix;
            for (int iz = 0; iz < cellsZ; iz++) {
                int z = minZ + iz * use;
                int at = ix * cellsZ + iz;
                final int cellZ = iz;
                // ⚠⚠ **地面の高さは、要ると分かってから採る**（2026-08-28）。
                //    ⚠ 以前はここで全列ぶん先に採っていた——⚠ **1列 約 28ms** で、
                //    ⚠ **1つの構造物あたり 3,364 列＝約2分**かかっていた。
                //    ⚠ 高さの使い道は2つ:
                //      ① 川の判定（⚠ **横の距離を通った列でしか見ない**）
                //      ② 移動で地面がどれだけ動くか（{@link #groundShift}。⚠ **箱と候補の範囲だけ**）
                //    ⚠ どちらも走査した正方形の一部でしかないので、⚠ **先に全部採ると捨て代になる。**
                //    ⚠⚠ **返る値は前と同じ。** 採る時機だけが違う。
                wet[at] = RiverProbe.inRiverChannel(x, z, factor,
                        () -> mask.groundAt(cellX, cellZ), heightDelta,
                        generator, level, randomState);
                if (near != null) {
                    near[at] = RiverProbe.nearRiver(x, z, 0, generator, level, randomState);
                }
            }
        }
        return mask;
    }

    /**
     * How much the ground under this box would rise or fall if the structure moved by
     * {@code (dx, dz)} — the median over the sampled columns, in blocks.
     *
     * <p><b>Why this decides anything.</b> {@code StructurePiece.move(dx, 0, dz)} moves a piece
     * <b>horizontally only</b>; its Y is fixed when the structure is laid out. On flat ground that
     * is harmless. On a mountain it is not: the houses stay at their old height while the ground
     * beneath them changes, and they end up buried or floating.
     *
     * <p>Measured on {@code ctov:large/village_mountain} at chunk (-61, 35), which this nudge moved
     * by +32,+32 to cut its wet columns from 58 to 37. The ground's median height there goes from
     * <b>247 to 252</b>. The village's pieces stayed at Y 197..275 in both worlds — so every house
     * sank five blocks into the hillside. Comparing the two burns by eye, the earlier one (which
     * did not move it) looks right and this one has houses in the ground.
     *
     * <p>The relief of the box does not catch this: the moved position is actually <i>flatter</i>
     * (96 blocks of relief against 112). What matters is not how rough the destination is but how
     * far its ground sits from the ground the structure was built for.
     */
    public int groundShift(BoundingBox box, int dx, int dz) {
        int fromX = index(box.minX() - originX);
        int toX = Math.min(cellsX - 1, (box.maxX() - originX) / step);
        int fromZ = index(box.minZ() - originZ);
        int toZ = Math.min(cellsZ - 1, (box.maxZ() - originZ) / step);
        int shiftX = dx / step;
        int shiftZ = dz / step;
        int[] diffs = new int[Math.max(1, (toX - fromX + 1) * (toZ - fromZ + 1))];
        int n = 0;
        for (int ix = fromX; ix <= toX; ix++) {
            int jx = ix + shiftX;
            if (jx < 0 || jx >= cellsX) {
                continue;
            }
            for (int iz = fromZ; iz <= toZ; iz++) {
                int jz = iz + shiftZ;
                if (jz < 0 || jz >= cellsZ) {
                    continue;
                }
                // ⚠ ここで初めて高さが要る。⚠ **採っていなければこの場で採る**（2026-08-28）
                diffs[n++] = groundAt(jx, jz) - groundAt(ix, iz);
            }
        }
        if (n == 0) {
            return Integer.MAX_VALUE;                 // 測れないなら動かさない側へ倒す
        }
        int[] used = java.util.Arrays.copyOf(diffs, n);
        java.util.Arrays.sort(used);
        return Math.abs(used[n / 2]);
    }

    /**
     * Samples <b>just the box</b>, with no room to move around it.
     *
     * <p><b>Why this exists.</b> Deciding "does this structure sit on a river" needs only the
     * structure's own footprint; the surrounding search area is needed only for the few that turn
     * out to be wet and are going to move. Sampling the full area for every structure start made
     * the burn server take over nine minutes to finish loading and never reach {@code Done}, with
     * spark timing out on world statistics the whole way — every structure, river or not, was
     * paying for roughly 600 columns times two predicates, and each miss can push Dynamic Waters
     * into generating a whole mountain river region synchronously.
     *
     * <p>For an 80x120 village this is 176 columns instead of 600-odd, and the expensive one runs
     * only when there is actually something to move.
     */
    public static RiverMask sampleBoxOnly(BoundingBox box, int step, int maxSamples, double factor,
                                          int heightDelta, ChunkGenerator generator,
                                          LevelHeightAccessor level, RandomState randomState) {
        return sample(box, 0, step, maxSamples, factor, heightDelta, false,
                generator, level, randomState);
    }

    /** The spacing actually used, which may be coarser than asked for. */
    public int step() {
        return step;
    }

    /**
     * How many sampled columns inside this box hold river water.
     *
     * <p><b>Why a count and not a yes/no.</b> Demanding a destination with <i>zero</i> wet columns
     * sounds right and is unreachable for anything large. Measured on the 2.7.0 burn: the village
     * at chunk [20, 13] sits in a search area only <b>15% wet</b> (106 of 696 columns) and still
     * had nowhere to go, because its box is 80x120 and a river winding through the area clips some
     * corner of it at every one of the 32 candidate offsets. Nine smaller structures moved in the
     * same run; not one village did.
     *
     * <p>So the nudge minimises overlap instead of eliminating it. A village with a stream along
     * one edge is what was wanted all along — it is a village with a stream through its middle
     * that reads badly.
     */
    public int wetCountIn(BoundingBox box) {
        int fromX = index(box.minX() - originX);
        int toX = Math.min(cellsX - 1, (box.maxX() - originX) / step);
        int fromZ = index(box.minZ() - originZ);
        int toZ = Math.min(cellsZ - 1, (box.maxZ() - originZ) / step);
        int n = 0;
        for (int ix = fromX; ix <= toX; ix++) {
            for (int iz = fromZ; iz <= toZ; iz++) {
                if (wet[ix * cellsZ + iz]) {
                    n++;
                }
            }
        }
        return n;
    }

    /** How many sampled columns hold water — for logging why a search gave up. */
    public int wetCount() {
        return count(wet);
    }

    /** The same under Dynamic Waters' proximity rule, or -1 if the comparison is switched off. */
    public int nearCount() {
        return near == null ? -1 : count(near);
    }

    /** Total sampled columns. */
    public int size() {
        return wet.length;
    }

    /**
     * How many columns actually needed a surface height, out of {@link #size()}.
     *
     * <p><b>Why it is in the log.</b> Heights are the expensive part — about 28 milliseconds
     * each — and after 3.3.0 they are fetched only where something reads one. Printing the count
     * is how a single nudge line says whether that is working, instead of waiting for hours of
     * chunks-per-second to average out. Before 3.3.0 this number was always the whole grid.
     */
    public int groundSampled() {
        int n = 0;
        for (int g : ground) {
            if (g != UNSAMPLED) {
                n++;
            }
        }
        return n;
    }

    private static int count(boolean[] a) {
        int n = 0;
        for (boolean b : a) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /** Round up, so a box edge that falls between samples still starts inside the box. */
    private int index(int offset) {
        return Math.max(0, (offset + step - 1) / step);
    }
}
