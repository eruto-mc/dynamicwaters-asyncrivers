package net.erutobusiness.asyncrivers.mixin;

import com.AdamNew.dynamicwaters.DynamicWaters;
import net.erutobusiness.asyncrivers.MountainRiverScheduler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.level.ChunkWatchEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
    private static void asyncrivers$deferToWorker(ChunkWatchEvent.Watch event, CallbackInfo ci) {
        ci.cancel();

        ServerPlayer player = event.getPlayer();
        ServerLevel level = event.getLevel();
        if (player == null || level == null) {
            return;
        }
        MountainRiverScheduler.onChunkWatched(player, level, event.getPos().x, event.getPos().z);
    }

    /**
     * Drops the compatibility chat lines Dynamic Waters sends on every join.
     *
     * <p>{@code onPlayerLogin} does three things, read out of {@code dynamicwaters-11.1.2.jar}:
     *
     * <pre>
     * SENT_REGIONS.put(player.getUUID(), newKeySet())          // keep
     * CHANNEL.send(player, new SyncSeedPacket(seed, seaLevel))  // keep - the client needs this
     * if (ModList.isLoaded("tectonic"))       { sendSystemMessage x2 }   // drop
     * if (ModList.isLoaded("mr_lithosphere")) { sendSystemMessage x1 }   // drop
     * if (ModList.isLoaded("larion"))         { sendSystemMessage x1 }   // drop
     * </pre>
     *
     * <p>The four messages are gated only on {@code ModList.isLoaded}; there is no config read
     * anywhere on that path, so they cannot be turned off from {@code dynamicwaters-common.toml}.
     * We run Tectonic, so two of them printed on every single join:
     *
     * <pre>
     * [Dynamic Waters] Compatibility with mod Tectonic is still beta.
     * [Dynamic Waters] Tectonic underground rivers were automatically disabled for better performance.
     * </pre>
     *
     * <p>Both statements stay true — we are not changing behaviour, only the announcement. The
     * automatic disabling is {@code disableTectonicUndergroundRivers}, which is still on.
     *
     * <p>Redirecting the call rather than cancelling the handler is deliberate: the seed packet is
     * sent earlier in the same method and the client renders rivers wrong without it.
     *
     * <p><b>{@code require = 4}</b> pins the message count. If a later Dynamic Waters adds or drops
     * a compatibility line this fails loudly at load instead of quietly letting the ads back in —
     * the version range in {@code mods.toml} is what keeps that from happening unnoticed.
     */
    @Redirect(
            method = "onPlayerLogin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V"),
            remap = true,
            require = 4)
    private static void asyncrivers$dropCompatChat(Player player, Component message) {
        // 出さない
    }
}
