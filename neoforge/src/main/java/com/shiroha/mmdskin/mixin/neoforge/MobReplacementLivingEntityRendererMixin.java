/* 文件职责：在 NeoForge 生物渲染状态阶段接入 MMD 替换渲染。 */
package com.shiroha.mmdskin.mixin.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.renderer.integration.entity.MobReplacementRenderer;
import com.shiroha.mmdskin.renderer.integration.state.LivingEntityRenderStateBridge;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class MobReplacementLivingEntityRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mmdskin$renderMobReplacement(LivingEntityRenderState state, PoseStack poseStack,
                                              MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        LivingEntity entity = ((LivingEntityRenderStateBridge) state).mmdskin$getLivingEntity();
        if (entity == null || entity instanceof AbstractClientPlayer) {
            return;
        }

        float tickDelta = ((LivingEntityRenderStateBridge) state).mmdskin$getTickDelta();
        if (MobReplacementRenderer.render(entity, state.bodyRot, tickDelta, poseStack, packedLight)) {
            ci.cancel();
        }
    }
}
