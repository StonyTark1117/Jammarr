package stonytark.jammarr.network;

/** Concrete FML discriminator for packets received by a Forge 1.12.2 client. */
public final class LegacyClientboundEnvelope extends LegacyEnvelope {
    public LegacyClientboundEnvelope() {}

    private LegacyClientboundEnvelope(int messageId, byte[] payload) {
        super(messageId, payload);
    }

    public static <T> LegacyClientboundEnvelope of(LegacyPacketTypes.Type<T> type, T value) {
        if (type.direction() != LegacyPacketTypes.Direction.CLIENTBOUND) {
            throw new IllegalArgumentException("Packet is not clientbound: " + type.name());
        }
        return new LegacyClientboundEnvelope(type.id(), encodePayload(type, value));
    }
}
