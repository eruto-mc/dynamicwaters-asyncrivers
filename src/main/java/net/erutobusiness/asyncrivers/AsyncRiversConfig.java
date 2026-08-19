package net.erutobusiness.asyncrivers;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Config for this mod. One switch, and it is about structures rather than threads.
 *
 * <p>Read out of {@code dynamicwaters-11.1.2.jar}, Dynamic Waters guards structures against its
 * rivers <b>twice</b>, and the two guards disagree about who should win:
 *
 * <table>
 *   <tr><th></th><th>when</th><th>what it does</th><th>reach</th></tr>
 *   <tr><td>{@code StructureMixin}</td><td>right after a structure start is made</td>
 *       <td>throws the structure away ({@code StructureStart.INVALID})</td>
 *       <td>about 23 blocks plus the structure's own radius, and <b>&plusmn;70 blocks
 *           vertically</b></td></tr>
 *   <tr><td>{@code NoiseChunkGeneratorMixin}</td><td>while carving the river</td>
 *       <td>skips the carve — the structure wins</td>
 *       <td>every structure piece's box, inflated by 8</td></tr>
 * </table>
 *
 * <p>The second guard is the one we want and it is already complete: it collects every start
 * referencing the chunk ({@code startsForStructure} with a {@code s -> true} predicate), builds a
 * per-column {@code BitSet[256]} of protected Y values, and gates <i>every</i> block write in the
 * carve routine on it. Nothing gets cut in half.
 *
 * <p>The first guard runs earlier and is far wider, so the second one rarely gets a chance. On our
 * world it costs 18% of all structure starts (128 down to 105 over 22,201 chunks). The vertical
 * &plusmn;70 is what hurts most: mountain rivers sit high, so a cabin 60 blocks underground is
 * thrown away by a river it could never touch. {@code alexscaves:underground_cabin} lost 10.
 *
 * <p>With this on, the first guard is switched off and the second one does the work: structures
 * stay, and the river declines to carve through them.
 *
 * <p><b>The visible trade.</b> Where a river meets a structure the riverbed now stops at the
 * structure instead of the structure disappearing. Whether that reads as a river running behind a
 * village or as a plug in the river is a look question, and looks are not ours to judge.
 */
@Mod.EventBusSubscriber(modid = AsyncRivers.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AsyncRiversConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue KEEP_STRUCTURES_NEAR_RIVERS = BUILDER
            .comment("Keep structures that generate near a river instead of deleting them.",
                     "Dynamic Waters deletes any structure within ~23 blocks horizontally and 70",
                     "blocks vertically of a river. Its carver already refuses to cut into a",
                     "structure, so the deletion is not what protects them -- it just removes them.",
                     "false restores stock Dynamic Waters behaviour.")
            .define("keepStructuresNearRivers", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    /**
     * Read on worldgen threads once per structure start, so it is cached rather than fetched from
     * the config each time. {@code volatile} because worldgen threads read what the config thread
     * wrote.
     */
    private static volatile boolean keepStructures = true;

    public static boolean keepStructuresNearRivers() {
        return keepStructures;
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent.Loading event) {
        sync(event);
    }

    @SubscribeEvent
    static void onReload(ModConfigEvent.Reloading event) {
        sync(event);
    }

    private static void sync(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        keepStructures = KEEP_STRUCTURES_NEAR_RIVERS.get();
        AsyncRivers.LOGGER.info("keepStructuresNearRivers = {}", keepStructures);
    }

    private AsyncRiversConfig() {
    }
}
