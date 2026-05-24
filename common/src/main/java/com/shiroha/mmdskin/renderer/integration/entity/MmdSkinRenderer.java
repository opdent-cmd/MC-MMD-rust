/* 文件职责：将原版实体渲染接入 MMD 模型运行时。 */
package com.shiroha.mmdskin.renderer.integration.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.MmdSkin;
import com.shiroha.mmdskin.renderer.api.RenderContext;
import com.shiroha.mmdskin.renderer.api.RenderParams;
import com.shiroha.mmdskin.renderer.integration.ModelPropertyHelper;
import com.shiroha.mmdskin.renderer.integration.player.InventoryRenderHelper;
import com.shiroha.mmdskin.renderer.runtime.model.MMDModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MmdSkinRenderer<T extends Entity> extends EntityRenderer<T, MmdEntityRenderState> {

    private static final ResourceLocation PLACEHOLDER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MmdSkin.MOD_ID, "textures/entity/placeholder.png");

    protected final String modelName;

    private final RenderParams reusableParams = new RenderParams();
    private final Quaternionf reusableQuat = new Quaternionf();
    private final Vector3f reusableVec = new Vector3f();
    private final float[] reusableSize = new float[2];

    public MmdSkinRenderer(EntityRendererProvider.Context renderManager, String entityName) {
        super(renderManager);
        this.modelName = entityName.replace(':', '.');
    }

    @Override
    public MmdEntityRenderState createRenderState() {
        return new MmdEntityRenderState();
    }

    @Override
    public void extractRenderState(T entity, MmdEntityRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.entity = entity;
        state.entityYaw = entity.getYRot();
        state.tickDelta = partialTick;
    }

    @Override
    public void render(MmdEntityRenderState state, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        @SuppressWarnings("unchecked")
        T entity = (T) state.entity;
        if (entity == null) {
            return;
        }

        MMDModelManager.Model model = MMDModelManager.GetModel(modelName, entity.getStringUUID());
        if (model == null) {
            super.render(state, poseStack, bufferSource, packedLight);
            return;
        }

        model.loadModelProperties(false);
        float[] size = parseModelSize(model, reusableSize);

        reusableParams.reset();
        EntityAnimationResolver.resolve(entity, model, state.entityYaw, state.tickDelta, reusableParams);

        poseStack.pushPose();
        try {
            if (entity instanceof LivingEntity living && living.isBaby()) {
                poseStack.scale(0.5f, 0.5f, 0.5f);
            }

            if (InventoryRenderHelper.isInventoryScreen()) {
                renderInInventory(entity, model, state.entityYaw, state.tickDelta, poseStack, packedLight, size);
            } else {
                poseStack.scale(size[0], size[0], size[0]);
                RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_CUTOUT_NO_CULL);
                model.model.render(entity, reusableParams.bodyYaw, reusableParams.bodyPitch, reusableParams.translation,
                        state.tickDelta, poseStack, packedLight, RenderContext.WORLD);
            }
        } finally {
            poseStack.popPose();
        }

        super.render(state, poseStack, bufferSource, packedLight);
    }

    private void renderInInventory(T entity, MMDModelManager.Model model, float entityYaw,
                                   float tickDelta, PoseStack poseStack, int packedLight, float[] size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.scale(20.0f, 20.0f, -20.0f);
        poseStack.scale(size[1], size[1], size[1]);

        reusableQuat.identity()
                .rotateZ((float) Math.PI)
                .rotateX(-entity.getXRot() * ((float) Math.PI / 180F))
                .rotateY(-entity.getYRot() * ((float) Math.PI / 180F));
        poseStack.mulPose(reusableQuat);

        RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_CUTOUT_NO_CULL);
        reusableVec.set(0.0f);
        model.model.render(entity, entityYaw, 0.0f, reusableVec,
                tickDelta, poseStack, packedLight, RenderContext.INVENTORY);
        poseStack.popPose();
    }

    private static float[] parseModelSize(MMDModelManager.Model model, float[] out) {
        float[] size = ModelPropertyHelper.getModelSize(model.properties);
        out[0] = size[0];
        out[1] = size[1];
        return out;
    }

    public ResourceLocation getTextureLocation(MmdEntityRenderState state) {
        return PLACEHOLDER_TEXTURE;
    }
}
