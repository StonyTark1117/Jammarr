package stonytark.jammarr.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakePendingMessagesTest {
    @Test void sendingAnotherPacketCannotMutateAnAcknowledgementRemoval() throws Exception {
        HandshakePendingMessagesMixin mixin = new HandshakePendingMessagesMixin() {};
        Field field = HandshakePendingMessagesMixin.class.getDeclaredField("sentMessages");
        field.setAccessible(true);
        field.set(mixin, new ArrayList<>(List.of(11, 22)));
        Method initialize = HandshakePendingMessagesMixin.class.getDeclaredMethod(
                "jammarr$synchronizePendingMessages", CallbackInfo.class);
        initialize.setAccessible(true);
        initialize.invoke(mixin, new Object[] {null});
        @SuppressWarnings("unchecked") List<Integer> pending = (List<Integer>) field.get(mixin);

        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        CountDownLatch sending = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> acknowledgement = threads.submit(() -> pending.removeIf(index -> {
                if (index == 11) {
                    removing.countDown();
                    try {
                        if (!releaseRemoval.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Removal barrier timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                }
                return index == 11;
            }));
            assertTrue(removing.await(5, TimeUnit.SECONDS));
            Future<Boolean> nextPacket = threads.submit(() -> {
                sending.countDown();
                return pending.add(33);
            });
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> nextPacket.get(150, TimeUnit.MILLISECONDS));
            releaseRemoval.countDown();
            assertTrue(acknowledgement.get(5, TimeUnit.SECONDS));
            assertTrue(nextPacket.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(22, 33), pending);
        } finally {
            releaseRemoval.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
