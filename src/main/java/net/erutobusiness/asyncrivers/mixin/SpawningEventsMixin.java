package net.erutobusiness.asyncrivers.mixin;

import com.AdamNew.dynamicwaters.events.SpawningEvents;
import net.erutobusiness.asyncrivers.AsyncRiversConfig;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.event.level.LevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Dynamic Waters asking "is this a river?" for mob spawn attempts that cannot be in one.
 *
 * <p>Read out of {@code dynamicwaters-11.1.2.jar}, {@code SpawningEvents.onPotentialSpawns} is:
 *
 * <pre>
 * flow = HydrologyManager.getRiverFlow(pos.getX(), pos.getY(), pos.getZ(), null);  // first statement
 * if (!flow.equals(Vec3.ZERO)) {
 *     event.getSpawnerDataList().clear();
 *     event.getSpawnerDataList().addAll(river biome's mobs for event.getMobCategory());
 * }
 * </pre>
 *
 * <p>There is no cheap test in front of that call, and vanilla fires the event for every spawn
 * attempt in every chunk that has a player within 128 blocks, every tick. What the call does is
 * not cheap either -- {@code evaluateMountainRiverContext} walks a 5x5 block of 256-block regions
 * (1280x1280 blocks), collects every {@code MountainRiverPath} in them into a fresh
 * {@code ArrayList}, then per path runs {@code resolveIntersections} over that whole list and
 * scans the path's nodes. So the cost is 25 cache lookups plus (paths x nodes) per spawn attempt.
 *
 * <p><b>Measured.</b> On the club's play server, one player parked in dense mountain rivers
 * (x=-5760 z=-5504, 163 paths and 7,922 nodes within the 5x5 window):
 *
 * <table>
 *   <tr><th></th><th>tick work</th><th>this mod's target</th></tr>
 *   <tr><td>no rivers within reach (x=-5504 z=-128)</td><td>10.0 ms</td><td>--</td></tr>
 *   <tr><td>dense rivers</td><td>32.5 ms</td>
 *       <td>20.89 ms/tick under {@code com.AdamNew.dynamicwaters},
 *           18.51 of it through this handler</td></tr>
 * </table>
 *
 * <p>93% of the difference between those two spots is Dynamic Waters. On the rental server with
 * three players it was 38.45 ms of a 76.73 ms tick, and the tick ran 78.73 ms against a 50 ms
 * budget.
 *
 * <h2>Why these two tests are safe</h2>
 *
 * <p><b>Not the overworld.</b> Dynamic Waters carves rivers in the overworld only:
 * {@code NoiseChunkGeneratorMixin.dynamicwaters$carveAndFillRiver} and
 * {@code dynamicwaters$saveHeightsBeforeCarvers} both open with
 * {@code if (level.dimension() != Level.OVERWORLD) return}. The spawn side never learned that --
 * {@code getRegionKey(int, int)} packs two ints into a long with no dimension in it, and this
 * handler hands {@code getRiverFlow} a {@code null} Level, so nothing can tell it where it is.
 * A Nether spawn attempt therefore reads the overworld's river geometry at the same (x, z), and
 * if it hits, Nether mobs are replaced by river ones. That is a bug on its own.
 *
 * <p><b>The block is not water.</b> Compared against the six land biomes the club's rivers run
 * through, {@code minecraft:river} adds exactly four entities: drowned, squid, salmon and glow
 * squid. All four are registered {@code SpawnPlacements.Type.IN_WATER}, so none of them could
 * spawn on dry land whether the list is swapped or not. Checked with mod biome modifiers applied
 * ({@code verify/compare_biome_spawns.py}), not just vanilla.
 *
 * <p><b>The one thing that does change.</b> {@code minecraft:river} has an empty CREATURE list, so
 * today the swap suppresses cows, sheep and the rest anywhere the river geometry reports a hit --
 * including the dry bank. With this on they spawn there again. That was put to the club and the
 * answer was that they should ("川岸に受動的な動物が湧いてもよさそう。というか、湧いた方がよさそう",
 * 2026-09-01), so no exemption for CREATURE is kept: leaving it out also removes its calls.
 *
 * <h2>Deliberately not done</h2>
 *
 * <p>Passing the real {@code Level} instead of {@code null} would restore Dynamic Waters' own
 * ocean early-out. It would also flip {@code cachedOnly} to false further down the same method,
 * which lets the flow tick generate regions synchronously on the server thread -- the very thing
 * {@link HydrologyManagerMixin} exists to stop. The {@code null} stays; we cut in front of it.
 */
@Mixin(value = SpawningEvents.class, remap = false)
public class SpawningEventsMixin {

    @Inject(method = "onPotentialSpawns", at = @At("HEAD"), cancellable = true)
    private static void asyncrivers$skipWhereNoRiverCanBe(LevelEvent.PotentialSpawns event,
                                                          CallbackInfo ci) {
        if (!AsyncRiversConfig.skipSpawnCheckOffRiver()) {
            return;
        }
        LevelAccessor level = event.getLevel();
        if (level == null) {
            return;
        }
        // Only cancel when we can positively tell it is the wrong dimension. An unexpected
        // LevelAccessor falls through to the water test rather than being dropped.
        if (level instanceof Level && ((Level) level).dimension() != Level.OVERWORLD) {
            ci.cancel();
            return;
        }
        if (!level.getFluidState(event.getPos()).is(FluidTags.WATER)) {
            ci.cancel();
        }
    }
}
