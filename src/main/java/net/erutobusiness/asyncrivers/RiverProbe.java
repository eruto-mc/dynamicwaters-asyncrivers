package net.erutobusiness.asyncrivers;

import com.AdamNew.dynamicwaters.HydrologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Two ways to ask "is there a river at this column", and the flag that makes either answer
 * honestly.
 *
 * <p>{@link net.erutobusiness.asyncrivers.mixin.HydrologyManagerMixin} makes
 * {@code isNearRiver} and {@code isTooCloseToRiver} return {@code false} so that Dynamic Waters
 * stops deleting structures. That is what we want for the deletion — and exactly what we do
 * <i>not</i> want when we are looking for somewhere to move a structure to, because then every
 * candidate spot looks clear and nothing ever moves. So the nudge sets a thread-local flag, asks,
 * and clears it. The flag is per-thread because world generation runs on many threads at once and
 * each is asking about its own chunk.
 */
public final class RiverProbe {

    private static final Logger LOG = LoggerFactory.getLogger("asyncrivers/probe");

    private static final ThreadLocal<Boolean> RAW = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * ⚠ **退避したことを1度だけ言う。**
     *
     * <p>Swallowing the exception and quietly answering with the proximity rule turns a broken
     * carve query into a mod that still works and still logs numbers — the numbers are just the
     * old rule's, under the new rule's name. On the first 2.8.0 structure the two counts came
     * back identical (296/609 and 296/609) where earlier measurement had them 6x apart, which is
     * exactly what a silent fallback looks like. So it says so, once.
     */
    private static final AtomicBoolean FELL_BACK = new AtomicBoolean(false);

    /** True while a nudge is asking. The hooks stand aside for the duration. */
    public static boolean asking() {
        return RAW.get();
    }

    /**
     * <b>Our own test: is this column inside the river channel?</b>
     *
     * <p>Dynamic Waters' geometry, our threshold. {@code evaluateRiverContext} hands back the
     * distance from this column to the nearest river centreline and that river's width; the
     * mountain-river equivalent does the same for the other kind. We call it wet when
     * {@code distance < width * factor} — the channel itself, and nothing else.
     *
     * <p><b>Why not Dynamic Waters' own {@link #nearRiver}.</b> That adds a buffer:
     * {@code distance < width + margin + 10}. The buffer is there because {@code isNearRiver}
     * exists to decide which structures to <i>delete</i>, and deleting generously is safe while
     * placing generously is not. Borrowed as a placement rule it makes the forbidden area far
     * larger than the water. Measured on the 2.7.0 burn, it called <b>296 of 609</b> sampled
     * columns wet around one mineshaft — 49% — where the burned world has water in 8.1% of a
     * village's columns and mountain rivers cover 1.19% of the surface.
     *
     * <p><b>Why not the carve result, which would be exact.</b> That was tried first and it
     * cannot work here: {@code calculateRiverCarving} reaches {@code getRiverBedNoise}, and
     * {@code RIVER_BED_NOISE} is still {@code null} while structures are being placed —
     * {@code NullPointerException} on the first column of the first structure. The field is built
     * by {@code setSeed}, which also invalidates both region caches, so calling it ourselves
     * mid-generation would be worse than the problem.
     *
     * <p><b>The fallback is loud on purpose.</b> Answering quietly with the proximity rule turns a
     * broken query into a mod that still works and still logs numbers — the old rule's numbers
     * under the new rule's name. That is exactly what happened on the first 2.8.0 burn: both
     * counts came back 296/609 where they should have been far apart, and only the identical
     * figures gave it away.
     */
    public static boolean inRiverChannel(int x, int z, double factor, int groundY,
                                         int maxHeightDelta, ChunkGenerator generator,
                                         LevelHeightAccessor level, RandomState randomState) {
        RAW.set(Boolean.TRUE);
        try {
            HydrologyManager.RiverContext river = HydrologyManager.evaluateRiverContext(x, z);
            // ⚠⚠ **高さを見る。** `evaluateRiverContext` は引数に Y を取らない＝水平だけの問い。
            //    高さを見ないと、**谷底の川が真上の山頂を「川の上」にしてしまう**。
            //    平地の川は海面に彫られるので、そこと比べる。
            if (river != null && river.bestFractal != null
                    && river.distance < river.width * factor
                    && Math.abs(groundY - HydrologyManager.getSeaLevel()) <= maxHeightDelta) {
                return true;
            }
            HydrologyManager.MountainRiverContext mountain =
                    HydrologyManager.evaluateMountainRiverContext(
                            x, z, generator, level, randomState, false);
            // ⚠ 山岳河川は自分の高さ（bestY）を持っている。これも使っていなかった。
            return mountain != null && mountain.bestPath != null
                    && mountain.distance < mountain.width * factor
                    && Math.abs(groundY - mountain.bestY) <= maxHeightDelta;
        } catch (RuntimeException | LinkageError e) {
            if (FELL_BACK.compareAndSet(false, true)) {
                LOG.warn("⚠ inRiverChannel が使えないので近接判定に退避する"
                        + " — 以後この行は出ない。最初の失敗: ", e);
            }
            return nearRiverRaw(x, z, 0, generator, level, randomState);
        } finally {
            RAW.set(Boolean.FALSE);
        }
    }

    /**
     * Dynamic Waters' own point query, kept for comparison only.
     *
     * <p>Read out of {@code dynamicwaters-11.1.2.jar} it is {@code distance < width + margin + 10}
     * against the meandering river path, then the equivalent against the mountain river path.
     * The nudge no longer decides anything with this; it is measured alongside
     * {@link #carvesWater} so a single burn shows how far apart the two are.
     */
    public static boolean nearRiver(int x, int z, int margin, ChunkGenerator generator,
                                    LevelHeightAccessor level, RandomState randomState) {
        RAW.set(Boolean.TRUE);
        try {
            return nearRiverRaw(x, z, margin, generator, level, randomState);
        } finally {
            RAW.set(Boolean.FALSE);
        }
    }

    /** ⚠ 呼ぶ側が既に旗を立てている前提。単体では使わない。 */
    private static boolean nearRiverRaw(int x, int z, int margin, ChunkGenerator generator,
                                        LevelHeightAccessor level, RandomState randomState) {
        return HydrologyManager.isNearRiver(x, z, margin, generator, level, randomState);
    }

    private RiverProbe() {
    }
}
