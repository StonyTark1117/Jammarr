package stonytark.jammarr.coremod;

import com.google.common.io.ByteStreams;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class LoginOrderingTransformerTest {
    private static final String DISPATCHER = "net.minecraftforge.fml.common.network.handshake.NetworkDispatcher";

    @ParameterizedTest
    @ValueSource(strings = { "setConnectionState", "func_150723_a" })
    void transformsOnlyTheServerTransitionInBothDevelopmentAndReleaseNames(String stateMethod) throws Exception {
        byte[] original;
        try (InputStream input = getClass().getResourceAsStream("/" + DISPATCHER.replace('.', '/') + ".class")) {
            original = ByteStreams.toByteArray(input);
        }
        ClassWriter fixture = new ClassWriter(0);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM5, fixture) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String descriptor, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner,
                                "setConnectionState".equals(name) ? stateMethod : name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        byte[] input = fixture.toByteArray();
        LoginOrderingTransformer transformer = new LoginOrderingTransformer();
        assertSame(input, transformer.transform("unrelated.Class", "unrelated.Class", input));
        byte[] result = transformer.transform(DISPATCHER, DISPATCHER, input);
        List<String> before = calls(input);
        List<String> after = calls(result);
        String oldCall = "serverInitiateHandshake:182:net/minecraft/network/NetworkManager:" + stateMethod
                + ":(Lnet/minecraft/network/EnumConnectionState;)V";
        String newCall = "serverInitiateHandshake:184:stonytark/jammarr/network/LoginProtocolOrder:setConnectionState"
                + ":(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/network/EnumConnectionState;)V";
        assertEquals(1, before.stream().filter(oldCall::equals).count());
        before.set(before.indexOf(oldCall), newCall);
        // All other calls, including the client handshake, retain their original behavior.
        assertEquals(before, after);
    }

    private static List<String> calls(byte[] bytes) {
        List<String> calls = new ArrayList<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String descriptor, boolean isInterface) {
                        calls.add(method + ":" + opcode + ":" + owner + ":" + name + ":" + descriptor);
                    }
                };
            }
        }, 0);
        return calls;
    }
}
