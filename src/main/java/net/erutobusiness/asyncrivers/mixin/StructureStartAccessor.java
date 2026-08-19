package net.erutobusiness.asyncrivers.mixin;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code StructureStart}'s remembered bounding box so a nudge can throw it away.
 *
 * <p>{@link StructureStart#getBoundingBox()} computes the box once and keeps it. After
 * {@link net.erutobusiness.asyncrivers.mixin.StructureNudgeMixin} shifts the pieces, that
 * remembered box still describes where the structure <i>used</i> to be — and vanilla uses it to
 * decide which chunks hold a reference to this start. A stale box means some chunks never learn
 * about the structure and its pieces there are simply never built.
 */
@Mixin(StructureStart.class)
public interface StructureStartAccessor {

    @Accessor("cachedBoundingBox")
    void asyncrivers$setCachedBoundingBox(BoundingBox box);
}
