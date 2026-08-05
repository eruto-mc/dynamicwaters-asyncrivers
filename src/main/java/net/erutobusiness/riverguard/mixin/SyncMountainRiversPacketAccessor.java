package net.erutobusiness.riverguard.mixin;

import com.AdamNew.dynamicwaters.MountainRiverPath;
import com.AdamNew.dynamicwaters.network.SyncMountainRiversPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Reads the paths back out of a decoded {@link SyncMountainRiversPacket}.
 *
 * <p><b>Why go through the packet at all.</b> {@link net.erutobusiness.riverguard.RegionStore}
 * needs to write mountain river regions to disk and read them back. Dynamic Waters already has a
 * serialiser for exactly that shape — this packet — so the store encodes and decodes with it rather
 * than inventing a second format.
 *
 * <p>Writing our own would mean guessing what the two leading ints in each path mean (the decoder
 * reads {@code nodeCount}, {@code validNodeCount}, then sizes five arrays), and getting that wrong
 * would corrupt terrain data silently. Reusing the mod's own reader removes the guess.
 *
 * <p>The packet keeps its fields private, hence this accessor.
 */
@Mixin(value = SyncMountainRiversPacket.class, remap = false)
public interface SyncMountainRiversPacketAccessor {

    @Accessor("paths")
    List<MountainRiverPath> riverguard$paths();

    @Accessor("regionX")
    int riverguard$regionX();

    @Accessor("regionZ")
    int riverguard$regionZ();
}
