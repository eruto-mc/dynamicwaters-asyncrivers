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

    private RiverMask(int originX, int originZ, int step, int cellsX, int cellsZ,
                      boolean[] wet, boolean[] near) {
        this.originX = originX;
        this.originZ = originZ;
        this.step = step;
        this.cellsX = cellsX;
        this.cellsZ = cellsZ;
        this.wet = wet;
        this.near = near;
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
                                   double factor, boolean compare, ChunkGenerator generator,
                                   LevelHeightAccessor level, RandomState randomState) {
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
        for (int ix = 0; ix < cellsX; ix++) {
            int x = minX + ix * use;
            for (int iz = 0; iz < cellsZ; iz++) {
                int z = minZ + iz * use;
                int at = ix * cellsZ + iz;
                wet[at] = RiverProbe.inRiverChannel(x, z, factor, generator, level, randomState);
                if (near != null) {
                    near[at] = RiverProbe.nearRiver(x, z, 0, generator, level, randomState);
                }
            }
        }
        return new RiverMask(minX, minZ, use, cellsX, cellsZ, wet, near);
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
                                          ChunkGenerator generator, LevelHeightAccessor level,
                                          RandomState randomState) {
        return sample(box, 0, step, maxSamples, factor, false, generator, level, randomState);
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
