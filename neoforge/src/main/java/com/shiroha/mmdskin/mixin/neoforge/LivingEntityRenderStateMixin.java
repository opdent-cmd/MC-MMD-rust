/* 文件职责：在 NeoForge 生物渲染状态中缓存实体上下文。 */
package com.shiroha.mmdskin.mixin.neoforge;

import com.shiroha.mmdskin.renderer.integration.state.LivingEntityRenderStateBridge;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements LivingEntityRenderStateBridge {
    @Unique
    private LivingEntity mmdskin$livingEntity;

    @Unique
    private float mmdskin$tickDelta;

    @Override
    public LivingEntity mmdskin$getLivingEntity() {
        return mmdskin$livingEntity;
    }

    @Override
    public float mmdskin$getTickDelta() {
        return mmdskin$tickDelta;
    }

    @Override
    public void mmdskin$setLivingEntityContext(LivingEntity entity, float tickDelta) {
        this.mmdskin$livingEntity = entity;
        this.mmdskin$tickDelta = tickDelta;
    }
}
