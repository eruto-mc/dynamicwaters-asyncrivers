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

    public static final ForgeConfigSpec.BooleanValue NUDGE_STRUCTURES_OFF_RIVERS = BUILDER
            .comment("Slide a structure sideways off a river instead of leaving it in one.",
                     "Tries 16, 32 then 48 blocks in eight directions and takes the first spot",
                     "that clears. If nothing clears, the structure stays where it is -- it is",
                     "never deleted. Needs keepStructuresNearRivers = true to matter.")
            .define("nudgeStructuresOffRivers", true);

    public static final ForgeConfigSpec.IntValue RIVER_PROBE_STEP = BUILDER
            .comment("Spacing, in blocks, of the grid used to ask whether river water runs under",
                     "a structure. Dynamic Waters' own box check tests only nine points of a",
                     "bounding box; on an 80x120 village that missed water covering 8% of its",
                     "columns. Smaller is more thorough and slower. 8 is roughly half a narrow",
                     "river's width, so a river cannot pass through a box unsampled.")
            .defineInRange("riverProbeStep", 8, 1, 64);

    public static final ForgeConfigSpec.DoubleValue RIVER_CHANNEL_FACTOR = BUILDER
            .comment("How much of a river counts as 'in it'. A column is wet when its distance",
                     "to the nearest river centreline is under width * this. 1.0 is the channel",
                     "itself; Dynamic Waters' own rule is effectively width + 10, which exists to",
                     "decide deletions and is far too generous for deciding placement -- it called",
                     "49% of the area around one mineshaft wet where the world holds water in 8%.",
                     "Raise it to push structures further from the bank; too high and nothing can",
                     "find a drier spot within its movement budget and everything stays put.")
            .defineInRange("riverChannelFactor", 1.0, 0.1, 8.0);

    public static final ForgeConfigSpec.BooleanValue RIVER_PROBE_COMPARE = BUILDER
            .comment("Also measure Dynamic Waters' own proximity rule and print both numbers in",
                     "the nudge log line. The decision is always made on the carve result; this",
                     "only adds a second measurement so one burn shows how far apart the two are.",
                     "Two versions in a row changed the question the nudge asked and neither moved",
                     "a village, and both times that took hours of world-diffing to prove.",
                     "Costs one extra query per sampled column. Turn off once the gap is known.")
            .define("riverProbeCompare", true);

    public static final ForgeConfigSpec.IntValue RIVER_PROBE_MAX_SAMPLES = BUILDER
            .comment("Upper bound on grid samples per structure. If the area would need more, the",
                     "step is doubled until it fits. This is a guard against one enormous structure",
                     "turning a single placement into a six-figure pile of noise queries; when it",
                     "trips, the coarser step is written into the 'gave up' log line.")
            .defineInRange("riverProbeMaxSamples", 4096, 64, 262144);

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

    private static volatile boolean nudge = true;

    public static boolean nudgeStructuresOffRivers() {
        return nudge;
    }

    private static volatile int probeStep = 8;

    public static int riverProbeStep() {
        return probeStep;
    }

    private static volatile double channelFactor = 1.0;

    public static double riverChannelFactor() {
        return channelFactor;
    }

    private static volatile boolean probeCompare = true;

    public static boolean riverProbeCompare() {
        return probeCompare;
    }

    private static volatile int probeMaxSamples = 4096;

    public static int riverProbeMaxSamples() {
        return probeMaxSamples;
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
        nudge = NUDGE_STRUCTURES_OFF_RIVERS.get();
        probeStep = RIVER_PROBE_STEP.get();
        channelFactor = RIVER_CHANNEL_FACTOR.get();
        probeCompare = RIVER_PROBE_COMPARE.get();
        probeMaxSamples = RIVER_PROBE_MAX_SAMPLES.get();
        AsyncRivers.LOGGER.info("keepStructuresNearRivers = {} / nudgeStructuresOffRivers = {}"
                        + " / riverProbe step {} maxSamples {} factor {} compare {}",
                keepStructures, nudge, probeStep, probeMaxSamples, channelFactor, probeCompare);
    }

    private AsyncRiversConfig() {
    }
}
