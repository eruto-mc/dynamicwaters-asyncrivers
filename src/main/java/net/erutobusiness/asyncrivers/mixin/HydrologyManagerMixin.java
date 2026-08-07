package net.erutobusiness.asyncrivers.mixin;

import com.AdamNew.dynamicwaters.HydrologyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

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
 * <p>So on a dedicated server the flow tick is allowed to generate regions synchronously. With a
 * warm cache nobody notices. With a cold one — every restart — the first entity to touch water
 * pays for 25 regions of terrain noise while the server thread holds the tick.
 *
 * <p><b>Measured.</b> {@link ModEventsMixin} alone moved the freeze rather than removing it:
 * login became instant (was 84 s) but the server thread then blocked 141,599 ms inside the first
 * ticks, which players see as "I got in but the world is frozen".
 *
 * <p>Forcing {@code cachedOnly = true} here makes the flow tick use whatever
 * {@link net.erutobusiness.asyncrivers.MountainRiverScheduler} has finished building. A region that
 * is not ready yet simply produces no push for a moment; the workers fill it in seconds.
 *
 * <p><b>Deliberately not touched:</b> {@code calculateRiverCarving}, {@code isNearRiver} and
 * {@code isTooCloseToRiver} also pass {@code cachedOnly = false}, but they are worldgen and run on
 * worldgen threads. Forcing them to the cached path would change the terrain that gets carved.
 */
@Mixin(value = HydrologyManager.class, remap = false)
public class HydrologyManagerMixin {

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
}
