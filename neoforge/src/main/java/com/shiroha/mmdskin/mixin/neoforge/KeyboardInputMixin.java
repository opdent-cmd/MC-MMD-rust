/* 文件职责：在舞台相机接管输入时清空 NeoForge 键盘移动状态。 */
package com.shiroha.mmdskin.mixin.neoforge;

import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeyboardInput Mixin — 舞台模式下清零移动输入
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onStageTick(boolean isSneaking, float sneakSpeedModifier, CallbackInfo ci) {
        if (MMDCameraController.getInstance().shouldBlockInput()) {
            this.keyPresses = Input.EMPTY;
            this.forwardImpulse = 0.0f;
            this.leftImpulse = 0.0f;
        }
    }
}

