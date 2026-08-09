package net.erutobusiness.asyncrivers.mixin;

import com.AdamNew.dynamicwaters.HydrologyManager;
import com.AdamNew.dynamicwaters.MountainRiverPath;
import net.erutobusiness.asyncrivers.MountainRiverScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Two changes to {@code HydrologyManager}, both about <i>when</i> mountain river regions get built
 * and what happens to them afterwards. Neither changes <i>what</i> gets built.
 *
 * <ul>
 *   <li>{@link #asyncrivers$neverGenerateOnTheTick} — stops the per-tick water push from building
 *       regions on the server thread.</li>
 *   <li>{@link #asyncrivers$keepWhatWorldgenBuilt} — writes down every region that anyone builds,
 *       including the ones world generation carves, so a restart does not rebuild them.</li>
 * </ul>
 *
 * <p><b>Deliberately not touched:</b> {@code calculateRiverCarving}, {@code isNearRiver} and
 * {@code isTooCloseToRiver} also pass {@code cachedOnly = false}, but they are worldgen and run on
 * worldgen threads. Forcing them to the cached path would change the terrain that gets carved.
 */
@Mixin(value = HydrologyManager.class, remap = false)
public class HydrologyManagerMixin {

    /**
     * Stops the per-tick water push from building mountain river regions on the server thread.
     *
     * <p>{@code getRiverFlow} is what pushes entities standing in a river. It runs on the server
     * thread, every tick, for every entity in water. Read out of {@code dynamicwaters-11.1.2.jar},
     * its {@code cachedOnly} argument is decided like this:
     *
     * <pre>
     * 167: iconst_1 -&gt; istore 8          // default: cachedOnly = true
     * 170: aload 5; instanceof ServerLevel
     * 175: ifeq 208                       // client: keep true
     * 205: iconst_0 -&gt; istore 8          // dedicated server: cachedOnly = FALSE
     * 216: iload 8 -&gt; evaluateMountainRiverContext(...)
     * </pre>
     *
     * <p>So on a dedicated server the flow tick is allowed to generate regions synchronously. With
     * a warm cache nobody notices. With a cold one — every restart — the first entity to touch
     * water pays for 25 regions of terrain noise while the server thread holds the tick.
     *
     * <p><b>Measured.</b> {@link ModEventsMixin} alone moved the freeze rather than removing it:
     * login became instant (was 84 s) but the server thread then blocked 141,599 ms inside the
     * first ticks, which players see as "I got in but the world is frozen".
     *
     * <p>Forcing {@code cachedOnly = true} here makes the flow tick use whatever
     * {@link MountainRiverScheduler} has finished building. A region that is not ready yet simply
     * produces no push for a moment; the workers fill it in seconds.
     */
    @ModifyArg(
            method = "getRiverFlow(DIDLnet/minecraft/world/level/Level;)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE",
                    target = "Lcom/AdamNew/dynamicwaters/HydrologyManager;evaluateMountainRiverContext"
                            + "(DDLnet/minecraft/world/level/chunk/ChunkGenerator;"
                            + "Lnet/minecraft/world/level/LevelHeightAccessor;"
                            + "Lnet/minecraft/world/level/levelgen/RandomState;Z)"
                            + "Lcom/AdamNew/dynamicwaters/HydrologyManager$MountainRiverContext;"),
            index = 5,
            remap = false)
    private static boolean asyncrivers$neverGenerateOnTheTick(boolean cachedOnly) {
        return true;
    }

    /**
     * Writes down whatever this call produced, so the next server start does not build it again.
     *
     * <p><b>Why here and not in {@link MountainRiverScheduler#obtain}.</b> {@code obtain} only sees
     * regions this mod asks for: the start-up pre-warm's innermost 2048, and whatever a player
     * walks into. Terrain carving does not go through it — {@code calculateRiverCarving} calls
     * {@code getMountainRegionPaths} straight from world generation threads. During an unattended
     * pre-generation run that is nearly every region there is, and until this hook existed all of
     * them landed in a 2048-entry memory cache and were evicted without ever reaching disk. The
     * next start then rebuilt them one slow query at a time.
     *
     * <p>Putting the hook on {@code getMountainRegionPaths} catches every caller at once, because
     * every path into a built region goes through it — carving, the pre-warm, joins and the flow
     * tick alike.
     *
     * <p><b>This is a pure observer and must stay one.</b> It reads {@code cir.getReturnValue()}
     * and hands it to the store. It takes no argument, cancels nothing, and returns nothing, so it
     * cannot affect whether or how a region is built — which is the whole reason it is safe to put
     * on a method that world generation depends on.
     *
     * <p><b>Cost.</b> This runs on cache hits too, so it has to be nearly free when there is
     * nothing to do. {@code writeIfAbsent} answers from an in-memory set of regions already known
     * to be on disk; only the first sighting of a region touches the filesystem.
     */
    @Inject(method = "getMountainRegionPaths", at = @At("RETURN"), remap = false)
    private static void asyncrivers$keepWhatWorldgenBuilt(
            int regionX, int regionZ,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.LevelHeightAccessor level,
            net.minecraft.world.level.levelgen.RandomState randomState,
            CallbackInfoReturnable<List<MountainRiverPath>> cir) {
        MountainRiverScheduler.rememberIfBuilt(regionX, regionZ, cir.getReturnValue());
    }
}
