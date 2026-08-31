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

    public static final ForgeConfigSpec.IntValue RIVER_MAX_HEIGHT_DELTA = BUILDER
            .comment("How far above or below a column's ground a river may run and still count as",
                     "being under it. Dynamic Waters' river query takes no Y at all -- it is purely",
                     "horizontal -- so without this a river in the valley makes the mountaintop",
                     "directly above it read as 'on a river'.",
                     "Measured: on ctov:large/village_mountain, ground median 247, the probe counted",
                     "58 wet columns where the burned world holds water in 9. Six times too many,",
                     "all of it from a river about 185 blocks below.",
                     "Flat rivers are compared against sea level; mountain rivers carry their own.")
            .defineInRange("riverMaxHeightDelta", 16, 1, 320);

    public static final ForgeConfigSpec.IntValue MAX_GROUND_SHIFT = BUILDER
            .comment("How many blocks the ground under a structure may rise or fall when it moves.",
                     "StructurePiece.move() shifts a piece horizontally only -- its Y was fixed when",
                     "the structure was laid out. On flat ground that is harmless; on a mountain the",
                     "houses stay put while the hillside moves under them and they end up buried.",
                     "Measured: ctov:large/village_mountain was moved +32,+32 to cut its wet columns",
                     "from 58 to 37, and the ground's median height went 247 -> 252. Five blocks was",
                     "enough to sink every house. Candidates whose median shift exceeds this are",
                     "refused, so a structure on steep ground simply stays where it is.",
                     "0 would refuse every move that changes the ground at all.")
            .defineInRange("maxGroundShift", 2, 0, 64);

    public static final ForgeConfigSpec.IntValue REFERENCE_CHUNKS = BUILDER
            .comment("How far, in chunks, a structure may end up from its own start chunk.",
                     "This is vanilla's constraint, not ours: ChunkGenerator collects a chunk's",
                     "structure references from start chunks within 8 chunks, and a piece landing",
                     "outside that window sits in a chunk that never learns the structure exists,",
                     "so it silently fails to generate. 8 is vanilla's value and the safe one.",
                     "Raising it needs a matching change to how references are collected, or",
                     "structures WILL come out with pieces missing. Left configurable only so the",
                     "cost of that change can be measured against a run that stays at 8.")
            .defineInRange("referenceChunks", 8, 1, 8);

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

    public static final ForgeConfigSpec.BooleanValue SKIP_SPAWN_CHECK_OFF_RIVER = BUILDER
            .comment("Stop asking Dynamic Waters whether a mob spawn attempt is in a river when it",
                     "cannot be. Its SpawningEvents.onPotentialSpawns calls getRiverFlow as its",
                     "first statement, with no cheap test in front of it, and vanilla fires that",
                     "event for every spawn attempt in every loaded chunk every tick.",
                     "Measured on the club's server: 20.89 ms of a 34 ms tick with one player",
                     "parked in dense mountain rivers; 93% of the difference between that spot and",
                     "one with no rivers. On the rental with three players it was 38.45 ms/tick.",
                     "Two tests are skipped, both of which Dynamic Waters itself implies:",
                     "  - not the overworld. Its own carver returns early unless dimension() is",
                     "    OVERWORLD, so no river exists anywhere else. The spawn side never checks:",
                     "    its cache key is (x,z) with no dimension, and the handler passes a null",
                     "    Level, so a Nether spawn reads the overworld's rivers.",
                     "  - the block is not water. Everything river has that the surrounding land",
                     "    biomes do not -- drowned, squid, salmon, glow squid -- is IN_WATER, so",
                     "    none of them could spawn on dry land whether we ask or not.",
                     "The one visible change: river's CREATURE list is empty, so today no cows or",
                     "sheep spawn near a river channel. With this on they do again.",
                     "false restores stock behaviour, for measuring against.")
            .define("skipSpawnCheckOffRiver", true);

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

    private static volatile int heightDelta = 16;

    public static int riverMaxHeightDelta() {
        return heightDelta;
    }

    private static volatile int maxShift = 2;

    public static int maxGroundShift() {
        return maxShift;
    }

    private static volatile int refChunks = 8;

    public static int referenceChunks() {
        return refChunks;
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

    /**
     * Read on the server thread once per mob spawn attempt -- the hottest read in this mod -- so
     * it is cached rather than fetched from the config each time.
     */
    private static volatile boolean skipSpawnCheck = true;

    public static boolean skipSpawnCheckOffRiver() {
        return skipSpawnCheck;
    }

    private static void sync(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        keepStructures = KEEP_STRUCTURES_NEAR_RIVERS.get();
        nudge = NUDGE_STRUCTURES_OFF_RIVERS.get();
        probeStep = RIVER_PROBE_STEP.get();
        heightDelta = RIVER_MAX_HEIGHT_DELTA.get();
        maxShift = MAX_GROUND_SHIFT.get();
        refChunks = REFERENCE_CHUNKS.get();
        channelFactor = RIVER_CHANNEL_FACTOR.get();
        probeCompare = RIVER_PROBE_COMPARE.get();
        probeMaxSamples = RIVER_PROBE_MAX_SAMPLES.get();
        skipSpawnCheck = SKIP_SPAWN_CHECK_OFF_RIVER.get();
        AsyncRivers.LOGGER.info("keepStructuresNearRivers = {} / nudgeStructuresOffRivers = {}"
                        + " / riverProbe step {} maxSamples {} factor {} compare {} refChunks {}"
                        + " maxGroundShift {} heightDelta {} / skipSpawnCheckOffRiver {}",
                keepStructures, nudge, probeStep, probeMaxSamples, channelFactor, probeCompare,
                refChunks, maxShift, heightDelta, skipSpawnCheck);
    }

    private AsyncRiversConfig() {
    }
}
