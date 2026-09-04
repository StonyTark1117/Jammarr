package stonytark.jammarr.mixin;

import java.util.Collections;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Forge's login acknowledgement list consistent across its two threads. */
@Mixin(targets = "net.minecraftforge.network.HandshakeHandler", remap = false)
public abstract class HandshakePendingMessagesMixin {
    @Shadow private List<Integer> sentMessages;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jammarr$synchronizePendingMessages(CallbackInfo callback) {
        // tickServer adds on the server thread; handleIndexedMessage removes
        // on Netty. ArrayList.removeIf can otherwise race with add, leaving a
        // null entry or throwing ConcurrentModificationException during login.
        sentMessages = Collections.synchronizedList(sentMessages);
    }
}
