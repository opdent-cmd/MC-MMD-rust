/* 文件职责：定义生物渲染状态与实体上下文之间的桥接接口。 */
package com.shiroha.mmdskin.renderer.integration.state;

import net.minecraft.world.entity.LivingEntity;

public interface LivingEntityRenderStateBridge {
    LivingEntity mmdskin$getLivingEntity();

    float mmdskin$getTickDelta();

    void mmdskin$setLivingEntityContext(LivingEntity entity, float tickDelta);
}
