/* 文件职责：在 Fabric 生物渲染状态提取后写入实体上下文。 */
package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.renderer.integration.state.LivingEntityRenderStateBridge;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererStateCaptureMixin {
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void mmdskin$captureRenderState(LivingEntity entity, LivingEntityRenderState state,
                                            float partialTick, CallbackInfo ci) {
        ((LivingEntityRenderStateBridge) state).mmdskin$setLivingEntityContext(entity, partialTick);
    }
}
