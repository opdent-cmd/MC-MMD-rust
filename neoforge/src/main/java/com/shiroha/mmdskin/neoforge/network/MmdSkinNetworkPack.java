/* 文件职责：处理 NeoForge 网络包的编解码与双端逻辑。 */
package com.shiroha.mmdskin.neoforge.network;

import com.shiroha.mmdskin.MmdSkin;
import com.shiroha.mmdskin.neoforge.register.MmdSkinAttachments;
import com.shiroha.mmdskin.neoforge.stage.NeoForgeStageSessionRegistry;
import com.shiroha.mmdskin.player.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.player.sync.MorphSyncHelper;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public record MmdSkinNetworkPack(int opCode, UUID playerUUID, String animId, int arg0, byte[] binaryData)
        implements CustomPacketPayload {
    private static final Logger logger = LogManager.getLogger();

    public static final Type<MmdSkinNetworkPack> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(MmdSkin.MOD_ID, "network_pack"));

    public static final StreamCodec<FriendlyByteBuf, MmdSkinNetworkPack> STREAM_CODEC = StreamCodec.of(
        MmdSkinNetworkPack::encode,
        MmdSkinNetworkPack::decode
    );

    public static MmdSkinNetworkPack withAnimId(int opCode, UUID playerUUID, String animId) {
        return new MmdSkinNetworkPack(opCode, playerUUID, animId, 0, new byte[0]);
    }

    public static MmdSkinNetworkPack withArg(int opCode, UUID playerUUID, int arg0) {
        return new MmdSkinNetworkPack(opCode, playerUUID, "", arg0, new byte[0]);
    }

    public static MmdSkinNetworkPack withBinary(int opCode, UUID playerUUID, byte[] data) {
        return new MmdSkinNetworkPack(opCode, playerUUID, "", 0, data);
    }

    private static void encode(FriendlyByteBuf buffer, MmdSkinNetworkPack pack) {
        buffer.writeInt(pack.opCode);
        buffer.writeUUID(pack.playerUUID);
        if (NetworkOpCode.isStringPayload(pack.opCode)) {
            buffer.writeUtf(pack.animId);
        } else {
            buffer.writeInt(pack.arg0);
        }
        buffer.writeByteArray(pack.binaryData);
    }

    private static MmdSkinNetworkPack decode(FriendlyByteBuf buffer) {
        int opCode = buffer.readInt();
        UUID playerUUID = buffer.readUUID();
        String animId = "";
        int arg0 = 0;

        if (NetworkOpCode.isStringPayload(opCode)) {
            animId = buffer.readUtf();
        } else {
            arg0 = buffer.readInt();
        }
        byte[] binaryData = buffer.readByteArray();
        return new MmdSkinNetworkPack(opCode, playerUUID, animId, arg0, binaryData);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MmdSkinNetworkPack pack, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sender) {
                handleServer(pack, sender);
            } else {
                pack.handleClient();
            }
        });
    }

    private static void handleServer(MmdSkinNetworkPack pack, ServerPlayer sender) {
        UUID realUUID = sender.getUUID();
        if (!realUUID.equals(pack.playerUUID)) {
            logger.warn("UUID 不匹配，丢弃数据包 claimed={}, real={}", pack.playerUUID, realUUID);
            return;
        }

        if (pack.opCode == NetworkOpCode.REQUEST_ALL_MODELS) {
            for (ServerPlayer player : sender.getServer().getPlayerList().getPlayers()) {
                String modelName = player.getData(MmdSkinAttachments.PLAYER_MMD_MODEL.get());
                if (modelName != null && !modelName.isEmpty()) {
                    PacketDistributor.sendToPlayer(sender,
                        MmdSkinNetworkPack.withAnimId(NetworkOpCode.MODEL_SELECT, player.getUUID(), modelName));
                }
            }
            return;
        }

        if (pack.opCode == NetworkOpCode.STAGE_MULTI) {
            NeoForgeStageSessionRegistry.getInstance().handlePacket(sender.getServer(), sender, pack.animId);
            return;
        }

        if (pack.opCode == NetworkOpCode.MODEL_SELECT) {
            sender.setData(MmdSkinAttachments.PLAYER_MMD_MODEL.get(), pack.animId);
        }

        MmdSkinNetworkPack corrected = new MmdSkinNetworkPack(
            pack.opCode, realUUID, pack.animId, pack.arg0, pack.binaryData
        );
        for (ServerPlayer player : sender.getServer().getPlayerList().getPlayers()) {
            if (!player.equals(sender)) {
                PacketDistributor.sendToPlayer(player, corrected);
            }
        }
    }

    private void handleClient() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (this.opCode == NetworkOpCode.STAGE_MULTI) {
            com.shiroha.mmdskin.stage.client.StageClientPacketHandler.getInstance().handle(this.playerUUID, this.animId);
            return;
        }
        if (mc.level == null || this.playerUUID.equals(mc.player.getUUID())) {
            return;
        }

        Player target = mc.level.getPlayerByUUID(this.playerUUID);
        if (NetworkOpCode.isStringPayload(this.opCode)) {
            switch (this.opCode) {
                case NetworkOpCode.CUSTOM_ANIM -> {
                    if (target != null) {
                        MmdSkinRendererPlayerHelper.CustomAnim(target, this.animId);
                    }
                }
                case NetworkOpCode.MODEL_SELECT -> PlayerModelSyncManager.onRemotePlayerModelReceived(this.playerUUID, this.animId);
                case NetworkOpCode.MORPH_SYNC -> {
                    if (target != null) {
                        MorphSyncHelper.applyRemoteMorph(target, this.animId);
                    }
                }
                default -> {
                }
            }
        } else if (this.opCode == NetworkOpCode.RESET_PHYSICS) {
            if (target != null) {
                MmdSkinRendererPlayerHelper.ResetPhysics(target);
            } else {
                PendingAnimSignalCache.put(this.playerUUID, PendingAnimSignalCache.SignalType.RESET);
            }
        }
    }
}
