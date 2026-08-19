package net.erutobusiness.asyncrivers.mixin;

import net.erutobusiness.asyncrivers.AsyncRiversConfig;
import net.erutobusiness.asyncrivers.RiverMask;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Slides a structure sideways off a river instead of letting it sit in one.
 *
 * <p><b>Why not just delete it, like Dynamic Waters does?</b> Because deletion costs villages.
 * Measured on seed K over 22,201 chunks: with the deletion on, 2 villages and 105 structure
 * starts; with it off, 5 villages and 144. The deletion halo grows with the structure's own
 * radius, so the biggest things — villages — are the likeliest to be thrown away.
 *
 * <p><b>Why not leave it?</b> Because a village straddling a river reads badly: vanilla builds a
 * terrain "beard" under each house during the noise step ({@code beard_thin}, which every village
 * here declares), and Dynamic Waters carves that beard away afterwards in {@code buildSurface}.
 * The houses end up over water with the ground between them cut out.
 *
 * <p>So: move it. Try a ring of offsets outwards; take the first that clears the river.
 * <b>If nothing clears, keep the structure where it is</b> — never delete. The worst case is
 * today's behaviour, not yesterday's.
 *
 * <p><b>What changed in 2.7.0, and why the two attempts before it did nothing.</b> Both earlier
 * versions asked a question that could not see the water.
 *
 * <ul>
 *   <li>v2 asked {@code HydrologyManager.isTooCloseToRiver} with the whole bounding box. That
 *       method tests <b>nine points</b> of the box, not the box.</li>
 *   <li>v3 added a river-<i>biome</i> check on top. Measured afterwards, the two villages it was
 *       written for contain <b>zero</b> river-biome cells — 651/651 columns of one are
 *       {@code terralith:alpine_highlands} and 506/506 of the other are
 *       {@code terralith:forested_highlands}. Comparing the v2 and v3 worlds structure by
 *       structure: same 144 starts, same positions but four, none of them a village. The biome
 *       check changed nothing because there was nothing of that kind to find.</li>
 * </ul>
 *
 * <p>The water is real — 8.1% and 4.3% of those villages' columns have water at y=62 — it is just
 * carved rather than biome-driven, and sparse enough that nine points miss it. So 2.7.0 drops the
 * biome question entirely and samples the carve geometry on a grid ({@link RiverMask}).
 *
 * <p><b>Why the distance is capped.</b> Vanilla collects a chunk's structure references from
 * within 8 chunks (128 blocks). Move a start further than that and pieces land in chunks that
 * never learn about it, so parts of the structure silently fail to generate. The cap keeps a wide
 * margin under that.
 *
 * <p><b>Why {@code move} and not just rewriting the box.</b> {@code PoolElementStructurePiece} —
 * which is what jigsaw structures like villages are made of — overrides
 * {@link StructurePiece#move(int, int, int)} to shift its placement position as well as its
 * bounding box. Rewriting boxes alone would leave the blocks behind.
 */
@Mixin(value = Structure.class, priority = 900)
public abstract class StructureNudgeMixin {

    private static final Logger ASYNCRIVERS$LOG =
            LoggerFactory.getLogger("asyncrivers/nudge");

    /** Step size when searching outwards. */
    private static final int ASYNCRIVERS$STEP = 16;

    /** Vanilla collects a chunk's structure references from within 8 chunks. */
    private static final int ASYNCRIVERS$REFERENCE_RADIUS = 128;

    /** Left unused so a rounding error can never push a piece past the radius. */
    private static final int ASYNCRIVERS$MARGIN = 16;

    /**
     * How far this structure may travel before parts of it stop generating.
     *
     * <p>The 128-block limit is measured <b>from the start's own chunk</b>, and a structure's
     * far edge is already half its own width away from that. So the budget is what remains after
     * the structure pays for its own size — a wide village can move much less than a small
     * underground cabin, and a first attempt that used one fixed distance for everything moved
     * every cabin and not a single village.
     */
    private static int asyncrivers$budget(BoundingBox box) {
        int half = Math.max((box.maxX() - box.minX()) / 2, (box.maxZ() - box.minZ()) / 2);
        return ASYNCRIVERS$REFERENCE_RADIUS - half - ASYNCRIVERS$MARGIN;
    }

    /** Eight compass directions, as (x, z) unit offsets. */
    private static final int[][] ASYNCRIVERS$DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
    };

    @Inject(method = "generate", at = @At("RETURN"))
    private void asyncrivers$nudgeOffRiver(
            RegistryAccess registries, ChunkGenerator generator, BiomeSource biomeSource,
            RandomState randomState, StructureTemplateManager templates, long seed,
            ChunkPos chunkPos, int references, LevelHeightAccessor level,
            Predicate<Holder<Biome>> biomePredicate,
            CallbackInfoReturnable<StructureStart> cir) {

        if (!AsyncRiversConfig.nudgeStructuresOffRivers()) {
            return;
        }
        StructureStart start = cir.getReturnValue();
        if (start == null || !start.isValid()) {
            return;
        }
        BoundingBox box = start.getBoundingBox();
        int budget = asyncrivers$budget(box);
        if (budget < ASYNCRIVERS$STEP) {
            return;                                   // 1歩も動けない。測る意味が無い
        }

        // ⚠⚠ **安い問いを先に出す。** 川と無関係な構造物にまで
        //    「箱＋移動予算」の全域（村なら 600 柱超）を走らせていたので、
        //    ⚠ 焼き用サーバが**9分経っても起動を終えなかった**（spark も計測に失敗し続けた）。
        //    ここで見るのは箱だけ（村で 176 柱）。動かす気になった数件だけが広い走査を払う。
        RiverMask probe = RiverMask.sampleBoxOnly(
                box,
                AsyncRiversConfig.riverProbeStep(),
                AsyncRiversConfig.riverProbeMaxSamples(),
                AsyncRiversConfig.riverChannelFactor(),
                generator, level, randomState);
        if (probe.wetCountIn(box) == 0) {
            return;                                   // 水に触れていない。何もしない
        }

        // ⚠ 候補は全部この範囲に収まるので、格子は**1回だけ**採る。
        //    候補ごとに聞くと同じ問い合わせを数千回繰り返すことになる。
        RiverMask mask = RiverMask.sample(
                box, budget,
                AsyncRiversConfig.riverProbeStep(),
                AsyncRiversConfig.riverProbeMaxSamples(),
                AsyncRiversConfig.riverChannelFactor(),
                AsyncRiversConfig.riverProbeCompare(),
                generator, level, randomState);

        int here = mask.wetCountIn(box);
        if (here == 0) {
            return;                                   // 粗い刻みに落ちた等。何もしない
        }

        // ⚠ **ゼロを要求しない。**「最も濡れない位置」を選ぶ。
        //    2.7.0 は「濡れたセルが1つも無い移動先」を条件にしていて、
        //    ⚠ 小さい構造物は9件動いたのに**村は1件も動かなかった**。
        //    村の箱は 80x120 あり、周りが 15% 濡れているだけで
        //    32通りの候補すべてがどこかで川に当たるため。
        //    近い候補から順に見て、**厳密に良いときだけ**更新するので、同点なら近いほうが勝つ。
        int bestWet = here;
        int bestDx = 0;
        int bestDz = 0;
        for (int stride = ASYNCRIVERS$STEP; stride <= budget; stride += ASYNCRIVERS$STEP) {
            for (int[] dir : ASYNCRIVERS$DIRS) {
                int dx = dir[0] * stride;
                int dz = dir[1] * stride;
                int wet = mask.wetCountIn(box.moved(dx, 0, dz));
                if (wet < bestWet) {
                    bestWet = wet;
                    bestDx = dx;
                    bestDz = dz;
                }
            }
        }

        if (bestDx == 0 && bestDz == 0) {
            // ⚠ 黙って諦めると「ずらしが効いていない」と「動いても良くならない」の区別がつかない。
            //    2.6.0 まではこれが分からず、効果の無い変更を2回重ねた。
            ASYNCRIVERS$LOG.info(
                    "stayed: {} at {} already the driest, {} wet under it,"
                            + " budget {}, area water {}/{}, proximity {}/{} (step {})",
                    start.getStructure(), chunkPos, here, budget,
                    mask.wetCount(), mask.size(), mask.nearCount(), mask.size(), mask.step());
            return;
        }

        for (StructurePiece piece : start.getPieces()) {
            piece.move(bestDx, 0, bestDz);
        }
        // ⚠ 箱は結果を覚え込むので、動かしたら捨てる。
        //    残すと参照の集め方がずれて、村の一部が生成されない。
        // ⚠ `(Object)` を挟む。mixin がインターフェースを足すのは実行時なので、
        //    コンパイラは `StructureStart` が実装していることを知らない。
        ((StructureStartAccessor) (Object) start).asyncrivers$setCachedBoundingBox(null);
        ASYNCRIVERS$LOG.info(
                "moved {} at {} by {},{}: {} -> {} wet under it,"
                        + " budget {}, area water {}/{}, proximity {}/{} (step {})",
                start.getStructure(), chunkPos, bestDx, bestDz, here, bestWet, budget,
                mask.wetCount(), mask.size(), mask.nearCount(), mask.size(), mask.step());
    }
}
