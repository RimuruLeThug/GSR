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
 * Point d'écoute des modifications du monde.
 *
 * Choix délibéré : on s'accroche à ClientWorld et NON au WorldRenderer / au ChunkBuilder.
 * Sodium remplace entièrement la reconstruction des chunks (et Iris s'appuie sur Sodium) ; ClientWorld,
 * lui, n'est modifié par aucun des deux. GSR reste donc compatible vanilla / Sodium / Iris avec un seul
 * mixin, et sa génération de splats est découplée du maillage.
 * (Noms Yarn 1.21.1.)
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    /** Appelé par setBlockState côté client (paquets de mise à jour de blocs, prédictions). */
    @Inject(method = "updateListeners", at = @At("TAIL"))
    private void gsr$onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState != newState) GsrClient.onBlockChanged(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Appelé lors des mises à jour de lumière (coordonnées de section). */
    @Inject(method = "scheduleBlockRenders", at = @At("TAIL"))
    private void gsr$onLightUpdate(int sx, int sy, int sz, CallbackInfo ci) {
        GsrClient.onSectionDirty(sx, sy, sz);
    }
}
