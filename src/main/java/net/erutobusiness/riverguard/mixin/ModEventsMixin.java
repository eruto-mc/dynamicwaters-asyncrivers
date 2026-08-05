package net.erutobusiness.riverguard.mixin;

import com.AdamNew.dynamicwaters.DynamicWaters;
import net.erutobusiness.riverguard.MountainRiverScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.ChunkWatchEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes over Dynamic Waters' {@code ChunkWatchEvent.Watch} handler.
 *
 * <p>The original body, read out of {@code dynamicwaters-11.1.2.jar}:
 *
 * <pre>
 * region = floor(chunkPos * 16 / 256)
 * for dx in -2..2:
 *   for dz in -2..2:
 *     if SENT_REGIONS[playerUUID].add(regionKey):
 *         paths = HydrologyManager.getMountainRegionPaths(...)   // builds terrain noise columns
 *         CHANNEL.send(player, new SyncMountainRiversPacket(...))
 * </pre>
 *
 * <p>Everything on that path runs on the server thread. Captured with jstack while the server was
 * unresponsive (three dumps during one stall, all identical):
 *
 * <pre>
 * ServerLoginPacketListenerImpl.placeNewPlayer
 *   -&gt; PlayerList.placeNewPlayer
 *   -&gt; ChunkMap.updatePlayerStatus -&gt; ForgeEventFactory.fireChunkWatch
 *   -&gt; DynamicWaters$ModEvents.onChunkWatch
 *   -&gt; HydrologyManager.getMountainRegionPaths     (Guava cache miss, segment lock held)
 *   -&gt; HydrologyManager.generateMountainPathsForRegion
 *   -&gt; MountainRiverPath.&lt;init&gt;
 *   -&gt; HydrologyManager.getCachedBaseHeight
 *   -&gt; NoiseBasedChunkGenerator.getBaseHeight
 *   -&gt; DensityFunctions / CubicSpline               (terrain noise)
 * </pre>
 *
 * <p>We cancel it and re-do the same work in {@link MountainRiverScheduler}, off-thread.
 *
 * <p><b>Note on the original's bookkeeping.</b> Dynamic Waters' own {@code SENT_REGIONS} map stays
 * empty once we cancel. That is harmless today — only {@code onChunkWatch} and
 * {@code onPlayerLogout} touch it — but it is why {@code mods.toml} pins the dynamicwaters version
 * range. If a later version starts reading that map elsewhere, this mixin has to be revisited.
 */
@Mixin(value = DynamicWaters.ModEvents.class, remap = false)
public class ModEventsMixin {

    @Inject(method = "onChunkWatch", at = @At("HEAD"), cancellable = true, remap = false)
    private static void riverguard$deferToWorker(ChunkWatchEvent.Watch event, CallbackInfo ci) {
        ci.cancel();

        ServerPlayer player = event.getPlayer();
        ServerLevel level = event.getLevel();
        if (player == null || level == null) {
            return;
        }
        MountainRiverScheduler.onChunkWatched(player, level, event.getPos().x, event.getPos().z);
    }
}
