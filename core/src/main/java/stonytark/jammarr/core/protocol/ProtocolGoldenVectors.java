package stonytark.jammarr.core.protocol;

/**
 * Immutable protocol-6 byte vectors used to prove that every loader adapter
 * preserves the shared wire contract. These values are deliberately literal:
 * deriving them with the production codecs would make an incompatible codec
 * change update both sides of the assertion.
 */
public final class ProtocolGoldenVectors {
    public static final String CLIENT_HELLO = "06";
    public static final String BROWSE_REQUEST = "000341264202";
    public static final String STATION_REQUEST = "0107000c010002343204536f6e6706417274697374";
    public static final String CHUNK_REQUEST = "00112233445566778899aabbccddeeffac021108";
    public static final String QUEUE_ENTRY = "013101540141e8070300";
    public static final String ADVENTURE_PREVIEW_WITH_QUEUE_ENTRY = "000001" + QUEUE_ENTRY;

    private ProtocolGoldenVectors() {}
}
