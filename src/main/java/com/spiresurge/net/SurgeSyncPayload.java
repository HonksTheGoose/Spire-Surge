package com.spiresurge.net;

import com.spiresurge.SpireSurge;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: the packed block positions of every Spire Heart that is currently surging in the
 * player's dimension. The client uses it to turn those Hearts red.
 *
 * The codec is written by hand (plain Netty reads/writes) so it does not depend on any
 * StreamCodec helper that changed between Minecraft versions.
 */
public record SurgeSyncPayload(List<Long> cores) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SurgeSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpireSurge.MOD_ID, "surge_sync"));

    /** Hard cap so a corrupt packet can never make the client allocate a huge list. */
    private static final int MAX_CORES = 4096;

    public static final StreamCodec<ByteBuf, SurgeSyncPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SurgeSyncPayload decode(ByteBuf buf) {
            int count = Math.min(Math.max(buf.readInt(), 0), MAX_CORES);
            List<Long> cores = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                cores.add(buf.readLong());
            }
            return new SurgeSyncPayload(cores);
        }

        @Override
        public void encode(ByteBuf buf, SurgeSyncPayload payload) {
            int count = Math.min(payload.cores().size(), MAX_CORES);
            buf.writeInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeLong(payload.cores().get(i));
            }
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
