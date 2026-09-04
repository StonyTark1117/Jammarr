package stonytark.jammarr.client;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecoderCancellationTest {
    @Test void cancellationDoesNotInterruptLazyModuleClassLoading() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader isolated = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    if (name.equals("javazoom.jl.decoder.Decoder")) {
                        loading.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new ClassNotFoundException("Test loader timed out");
                        } catch (InterruptedException error) {
                            // SecureModuleClassLoader reports interrupted module I/O as
                            // a missing class. The JVM then caches the failed resolution.
                            interrupted.set(true);
                            throw new ClassNotFoundException(name, new ClosedByInterruptException());
                        }
                    }
                    if (name.equals("stonytark.jammarr.client.StreamingMp3Decoder")
                            || name.equals("stonytark.jammarr.client.ChunkInputStream")) {
                        try (InputStream bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (bytes == null) throw new ClassNotFoundException(name);
                            byte[] code = bytes.readAllBytes();
                            return defineClass(name, code, 0, code.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    return super.loadClass(name, resolve);
                }
            }
        };
        Class<?> type = isolated.loadClass("stonytark.jammarr.client.StreamingMp3Decoder");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        Method close = type.getDeclaredMethod("close");
        close.setAccessible(true);
        Field threadField = type.getDeclaredField("thread");
        threadField.setAccessible(true);
        Object decoder = constructor.newInstance(0, 0);
        Thread worker = (Thread) threadField.get(decoder);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        worker.setUncaughtExceptionHandler((thread, failure) -> uncaught.set(failure));
        try {
            assertTrue(loading.await(5, TimeUnit.SECONDS), "decoder did not reach lazy class loading");
            close.invoke(decoder);
        } finally {
            release.countDown();
            close.invoke(decoder);
            worker.join(2_000);
        }
        assertFalse(worker.isAlive(), "closing input must stop the worker without interrupting module I/O");
        assertFalse(interrupted.get(), "cancellation poisoned lazy JLayer class resolution");
        assertNull(uncaught.get(), "decoder failed while closing");
    }

    @Test void closeWakesWorkerWaitingForNetworkInput() throws Exception {
        StreamingMp3Decoder decoder = new StreamingMp3Decoder(0, 1);
        Field field = StreamingMp3Decoder.class.getDeclaredField("thread");
        field.setAccessible(true);
        Thread worker = (Thread) field.get(decoder);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (worker.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.TIMED_WAITING, worker.getState(), "worker must be waiting for input");
        } finally {
            decoder.close();
            worker.join(2_000);
        }
        assertFalse(worker.isAlive(), "closing the chunk stream must wake an idle decoder");
    }
}
