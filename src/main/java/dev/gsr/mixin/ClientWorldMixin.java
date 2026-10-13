package dev.gsr.mixin;

import dev.gsr.GsrClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World modification listener.
 *
 * Deliberate choice: we hook into ClientWorld and NOT into WorldRenderer / ChunkBuilder.
 * Sodium entirely replaces chunk rebuilding (and Iris builds on Sodium); ClientWorld,
 * on the other hand, is modified by neither. GSR therefore stays compatible with vanilla / Sodium / Iris with a single
 * mixin, and its splat generation is decoupled from meshing.
 * (Yarn 1.21.1 names.)
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    /** Called by setBlockState on the client side (block update packets, predictions). */
    @Inject(method = "updateListeners", at = @At("TAIL"))
    private void gsr$onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState != newState) GsrClient.onBlockChanged(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Called on light updates (section coordinates). */
    @Inject(method = "scheduleBlockRenders", at = @At("TAIL"))
    private void gsr$onLightUpdate(int sx, int sy, int sz, CallbackInfo ci) {
        GsrClient.onSectionDirty(sx, sy, sz);
    }
}
