package stonytark.jammarr.network;

/** Concrete FML discriminator for packets received by the dedicated server. */
public final class LegacyServerboundEnvelope extends LegacyEnvelope {
    public LegacyServerboundEnvelope() {}

    private LegacyServerboundEnvelope(int messageId, byte[] payload) {
        super(messageId, payload);
    }

    public static <T> LegacyServerboundEnvelope of(LegacyPacketTypes.Type<T> type, T value) {
        if (type.direction() != LegacyPacketTypes.Direction.SERVERBOUND) {
            throw new IllegalArgumentException("Packet is not serverbound: " + type.name());
        }
        return new LegacyServerboundEnvelope(type.id(), encodePayload(type, value));
    }
}
