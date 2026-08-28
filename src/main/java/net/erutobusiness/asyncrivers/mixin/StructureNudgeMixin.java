package net.erutobusiness.asyncrivers.mixin;

import net.erutobusiness.asyncrivers.AsyncRiversConfig;
import net.erutobusiness.asyncrivers.RiverMask;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
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
import org.spongepowered.asm.mixin.Unique;
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
 * <p>So: move it to wherever the least water sits under it. <b>If nothing is better, keep the
 * structure where it is</b> — never delete.
 *
 * <p><b>Three questions were tried before this one worked.</b>
 *
 * <ul>
 *   <li>v2 asked {@code HydrologyManager.isTooCloseToRiver} with the whole bounding box. That
 *       method tests <b>nine points</b> of the box — four corners, four edge midpoints, centre.
 *       On an 80x120 village that misses water covering 8% of its columns.</li>
 *   <li>v3 added a river-<i>biome</i> check. The two villages it was written for contain
 *       <b>zero</b> river-biome cells; the water there is carved, not biome-driven. Diffing the
 *       v2 and v3 worlds: same 144 starts, four moved, no villages.</li>
 *   <li>v4 tried {@code calculateRiverCarving}, the carve itself. {@code RIVER_BED_NOISE} is
 *       still {@code null} while structures are placed — NPE on the first column.</li>
 * </ul>
 *
 * <p>What works is Dynamic Waters' geometry with our own threshold, sampled on a grid: see
 * {@link net.erutobusiness.asyncrivers.RiverProbe#inRiverChannel} and {@link RiverMask}.
 *
 * <p><b>How far it may move, and why that is no longer a fixed budget.</b> Vanilla's
 * {@code ChunkGenerator} collects a chunk's structure references from start chunks within
 * <b>8 chunks</b>. A piece landing outside that window sits in a chunk which never learns the
 * structure exists, so it silently fails to generate. Earlier versions turned that into a scalar,
 * {@code 128 - halfWidth - 16}, and the 16-block margin was a guess.
 *
 * <p>Measured on the burned world, that guess cost a village. {@code ctov:large/village_taiga}
 * got a budget of 40, so the search tried 16 and 32 blocks and stopped. It took the best of those
 * — and it genuinely was the best of the 17 candidates it could see — and still sat on water.
 * Counting real water columns at every offset afterwards:
 *
 * <pre>
 *   move   wet columns
 *      0   113  (17.4%)   staying put
 *     32    50  ( 7.7%)   what it chose, best within its budget
 *     48    12  ( 1.8%)   one step further
 *     64     8  ( 1.2%)
 *    128     7  ( 1.1%)   vanilla's limit
 * </pre>
 *
 * <p>One more step would have removed three quarters of the remaining water, and it was well
 * inside vanilla's window. So the scalar is gone: every candidate is now checked directly against
 * the reference window by {@link #asyncrivers$withinReferenceRange}. Nothing here exceeds what
 * vanilla allows — what went is the margin that was throwing usable distance away.
 *
 * <p><b>Why {@code move} and not just rewriting the box.</b> {@code PoolElementStructurePiece} —
 * what jigsaw structures like villages are made of — overrides
 * {@link StructurePiece#move(int, int, int)} to shift its placement position as well as its
 * bounding box. Rewriting boxes alone would leave the blocks behind.
 */
@Mixin(value = Structure.class, priority = 900)
public abstract class StructureNudgeMixin {

    private static final Logger ASYNCRIVERS$LOG =
            LoggerFactory.getLogger("asyncrivers/nudge");

    /** Step size when searching outwards. */
    private static final int ASYNCRIVERS$STEP = 16;

    /** Eight compass directions, as (x, z) unit offsets. */
    private static final int[][] ASYNCRIVERS$DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
    };

    /**
     * Whether every chunk this box touches is close enough to the start chunk that vanilla will
     * hand it a reference.
     *
     * <p>This is the real constraint, checked directly, instead of approximated by a scalar
     * budget. The window is {@code AsyncRiversConfig.referenceChunks()} either way — vanilla's
     * own 8 by default.
     */
    private static boolean asyncrivers$withinReferenceRange(BoundingBox box, ChunkPos start) {
        int r = AsyncRiversConfig.referenceChunks();
        return SectionPos.blockToSectionCoord(box.minX()) >= start.x - r
                && SectionPos.blockToSectionCoord(box.maxX()) <= start.x + r
                && SectionPos.blockToSectionCoord(box.minZ()) >= start.z - r
                && SectionPos.blockToSectionCoord(box.maxZ()) <= start.z + r;
    }

    /**
     * Whether to pick twice — once cheaply, once the old way — and complain if they differ.
     *
     * <p>Reordering two checks that are ANDed together cannot change which candidate wins, and
     * saying so is easy. Proving it on a real world is what this switch is for. It doubles the
     * work while on, so it is meant for one run, not for shipping. {@code asyncriversVerifyOrder}.
     */
    @Unique
    private static final boolean ASYNCRIVERS$VERIFY_ORDER =
            Boolean.getBoolean("asyncriversVerifyOrder");

    /**
     * Picks where the structure should move to, as {dx, dz, wet, considered, refusedByGround}.
     *
     * <p><b>Why the order of the two tests matters.</b> A candidate is taken only when it is
     * both drier than the best so far <i>and</i> lands on ground close enough to the original.
     * That is an AND, so the winner does not depend on which half is asked first — but the cost
     * does, enormously. Asking "does the ground move too much" first means measuring ground for
     * all 48 candidates, and each cell of that measurement can cost a surface-height query.
     * Asking "is it even drier" first is free — it reads the grid that was already sampled — and
     * most candidates fail it, so their ground is never measured at all.
     *
     * <p>Measured before the swap on {@code VolcanoStructure} at chunk [367, 288]:
     * <b>1313 of 1764</b> columns needed a height, with 47 candidates passing the ground test.
     *
     * @param oldOrder ask the expensive question first, the way this did before 3.4.0
     */
    @Unique
    private int[] asyncrivers$choose(RiverMask mask, BoundingBox box, ChunkPos chunkPos,
                                     int reach, int here, boolean oldOrder) {
        int bestWet = here;
        int bestDx = 0;
        int bestDz = 0;
        int fits = 0;
        int refusedByGround = 0;
        for (int stride = ASYNCRIVERS$STEP; stride <= reach; stride += ASYNCRIVERS$STEP) {
            for (int[] dir : ASYNCRIVERS$DIRS) {
                int dx = dir[0] * stride;
                int dz = dir[1] * stride;
                BoundingBox moved = box.moved(dx, 0, dz);
                // ⚠ 予算を先に丸めず、候補ごとに**バニラの窓に収まるか**を直接見る。
                if (!asyncrivers$withinReferenceRange(moved, chunkPos)) {
                    continue;
                }
                int wet = mask.wetCountIn(moved);
                if (oldOrder) {
                    // ⚠⚠ **地面が動く先へは移さない。** move() は水平にしか動かさないので、
                    //    地面の高さが変われば家がその分だけ埋まる／浮く。
                    //    山岳村を +32,+32 動かして地面が +5 上がり、家が全部沈んだ。
                    if (mask.groundShift(box, dx, dz) > AsyncRiversConfig.maxGroundShift()) {
                        refusedByGround++;
                        continue;
                    }
                    fits++;
                    if (wet < bestWet) {
                        bestWet = wet;
                        bestDx = dx;
                        bestDz = dz;
                    }
                } else {
                    // ⚠ **勝てない候補に、高い問いを投げない**（3.4.0）。
                    //    ⚠ 同点なら近いほうが勝つ規則も、`<` のままなので変わらない。
                    if (wet >= bestWet) {
                        continue;
                    }
                    if (mask.groundShift(box, dx, dz) > AsyncRiversConfig.maxGroundShift()) {
                        refusedByGround++;
                        continue;
                    }
                    fits++;
                    bestWet = wet;
                    bestDx = dx;
                    bestDz = dz;
                }
            }
        }
        return new int[] {bestDx, bestDz, bestWet, fits, refusedByGround};
    }

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

        // ⚠⚠ **安い問いを先に出す。** 川と無関係な構造物にまで
        //    「箱＋動ける範囲」の全域（村なら 600 柱超）を走らせていたので、
        //    ⚠ 焼き用サーバが**9分経っても起動を終えなかった**（spark も計測に失敗し続けた）。
        //    ここで見るのは箱だけ（村で 176 柱）。動かす気になった数件だけが広い走査を払う。
        RiverMask probe = RiverMask.sampleBoxOnly(
                box,
                AsyncRiversConfig.riverProbeStep(),
                AsyncRiversConfig.riverProbeMaxSamples(),
                AsyncRiversConfig.riverChannelFactor(),
                AsyncRiversConfig.riverMaxHeightDelta(),
                generator, level, randomState);
        if (probe.wetCountIn(box) == 0) {
            return;                                   // 水に触れていない。何もしない
        }

        // ⚠ 候補は全部この範囲に収まるので、格子は**1回だけ**採る。
        int reach = AsyncRiversConfig.referenceChunks() * 16;
        RiverMask mask = RiverMask.sample(
                box, reach,
                AsyncRiversConfig.riverProbeStep(),
                AsyncRiversConfig.riverProbeMaxSamples(),
                AsyncRiversConfig.riverChannelFactor(),
                AsyncRiversConfig.riverMaxHeightDelta(),
                AsyncRiversConfig.riverProbeCompare(),
                generator, level, randomState);

        int here = mask.wetCountIn(box);
        if (here == 0) {
            return;                                   // 粗い刻みに落ちた等。何もしない
        }

        // ⚠ **ゼロを要求しない。**「最も濡れない位置」を選ぶ。
        //    ゼロを条件にしていた版では、小さい構造物は9件動いたのに**村は1件も動かなかった**。
        //    周りが 15% 濡れているだけで、80x120 の箱は候補すべてでどこかが川に当たるため。
        //    近い候補から順に見て、**厳密に良いときだけ**更新するので、同点なら近いほうが勝つ。
        int[] chosen = asyncrivers$choose(mask, box, chunkPos, reach, here, false);
        // ⚠⚠ **確かめる回だけ、古い順序でも選び直して突き合わせる**（2026-08-28）。
        //    ⚠ 「見る順を変えても選ぶ先は同じ」は読めば分かるが、⚠ **読んだだけでは証拠にならない。**
        //    ⚠ 合わなければ ERROR で名指しし、⚠ **古い順序の答えを採る**（安全側）。
        if (ASYNCRIVERS$VERIFY_ORDER) {
            int[] old = asyncrivers$choose(mask, box, chunkPos, reach, here, true);
            if (old[0] != chosen[0] || old[1] != chosen[1] || old[2] != chosen[2]) {
                ASYNCRIVERS$LOG.error(
                        "⚠⚠ 候補の選び方が順序で変わった: {} at {} — 速い順 ({},{}) 濡れ {}"
                                + " ／ 元の順 ({},{}) 濡れ {}。⚠ **元の順を採る。**",
                        start.getStructure(), chunkPos,
                        chosen[0], chosen[1], chosen[2], old[0], old[1], old[2]);
                chosen = old;
            } else {
                ASYNCRIVERS$LOG.info("候補の選び方は順序で変わらなかった: {} at {} → ({},{}) 濡れ {}",
                        start.getStructure(), chunkPos, chosen[0], chosen[1], chosen[2]);
            }
        }
        int bestDx = chosen[0];
        int bestDz = chosen[1];
        int bestWet = chosen[2];
        int fits = chosen[3];
        int refusedByGround = chosen[4];

        if (bestDx == 0 && bestDz == 0) {
            // ⚠ 黙って諦めると「ずらしが効いていない」と「動いても良くならない」の区別がつかない。
            ASYNCRIVERS$LOG.info(
                    "stayed: {} at {} already the driest, {} wet under it,"
                            + " {} candidates fit, {} refused by ground,"
                            + " area water {}/{}, proximity {}/{} (step {}),"
                            + " ground {}/{} sampled (cache {}%)",
                    start.getStructure(), chunkPos, here, fits, refusedByGround,
                    mask.wetCount(), mask.size(), mask.nearCount(), mask.size(), mask.step(),
                    mask.groundSampled(), mask.size(), net.erutobusiness.asyncrivers.GroundCache
                            .hitRate());
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
                        + " {} candidates fit, {} refused by ground,"
                        + " area water {}/{}, proximity {}/{} (step {}),"
                        + " ground {}/{} sampled (cache {}%)",
                start.getStructure(), chunkPos, bestDx, bestDz, here, bestWet, fits,
                refusedByGround,
                mask.wetCount(), mask.size(), mask.nearCount(), mask.size(), mask.step(),
                mask.groundSampled(), mask.size(), net.erutobusiness.asyncrivers.GroundCache
                        .hitRate());
    }
}
