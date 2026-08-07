package net.erutobusiness.asyncrivers;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Dynamic Waters Async Rivers — a one-fix compatibility mod.
 *
 * <p>Dynamic Waters builds its mountain river network from {@code ChunkWatchEvent.Watch}, on the
 * server thread, synchronously. On join that fires for every chunk in view distance, so the server
 * ends up generating the mountain rivers of roughly 36 regions (256 blocks each) before the player
 * is placed. Measured on this server, joining a freshly restarted world:
 *
 * <table>
 *   <tr><th>setup</th><th>server thread blocked</th></tr>
 *   <tr><td>stock, region cache cold</td><td>231,451 ms (3 m 51 s)</td></tr>
 *   <tr><td>stock, region cache warm</td><td>7,909 ms</td></tr>
 *   <tr><td>enableMountainRivers = false</td><td>0 ms</td></tr>
 * </table>
 *
 * <p>The cache is memory-only, so it goes cold on every restart and the first player to join pays
 * for everyone. To that player it looks like falling through the world until the connection times
 * out, because the server never answers with a position correction.
 *
 * <p>This mod cancels the original handler and does the same work on worker threads, sending each
 * region's packet from the server thread once it is ready. Mountain rivers are kept; they just
 * arrive a few seconds after joining instead of holding it up.
 *
 * <p>See the README for the disk format, why the pre-warm refuses to run on a world with no
 * border set, and what is deliberately left alone.
 */
@Mod(AsyncRivers.MOD_ID)
public class AsyncRivers {

    public static final String MOD_ID = "asyncrivers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AsyncRivers() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MountainRiverScheduler.start();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // ⚠ ServerStarted, not ServerStarting: the overworld's spawn position and world border
        //    are only settled once the server has finished loading it.
        MountainRiverScheduler.startPreWarm(event.getServer(),
                event.getServer().getLevel(Level.OVERWORLD));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MountainRiverScheduler.stop();
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MountainRiverScheduler.forget(event.getEntity().getUUID());
    }
}
