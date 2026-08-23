package stonytark.jammarr.core.protocol;

public interface WireCodec<T> {
    T decode(WireInput input);
    void encode(WireOutput output, T value);
}
