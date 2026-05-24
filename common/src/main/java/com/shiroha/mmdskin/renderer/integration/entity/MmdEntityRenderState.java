/* 文件职责：承载 MMD 实体渲染所需的中间状态数据。 */
package com.shiroha.mmdskin.renderer.integration.entity;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

final class MmdEntityRenderState extends EntityRenderState {
    Entity entity;
    float entityYaw;
    float tickDelta;
}
