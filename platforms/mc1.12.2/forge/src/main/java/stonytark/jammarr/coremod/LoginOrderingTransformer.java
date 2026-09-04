package stonytark.jammarr.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Changes only Forge's server-side LOGIN to PLAY transition, after deobfuscation. */
public final class LoginOrderingTransformer implements IClassTransformer {
    private static final String DISPATCHER = "net.minecraftforge.fml.common.network.handshake.NetworkDispatcher";
    private static final String MANAGER = "net/minecraft/network/NetworkManager";
    private static final String STATE_DESCRIPTOR = "Lnet/minecraft/network/EnumConnectionState;";
    private static final String HELPER = "stonytark/jammarr/network/LoginProtocolOrder";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !DISPATCHER.equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] replacements = { 0 };
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"serverInitiateHandshake".equals(name) || !"()I".equals(descriptor)) return delegate;
                return new MethodVisitor(Opcodes.ASM5, delegate) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                          String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && MANAGER.equals(owner)
                                && ("setConnectionState".equals(method) || "func_150723_a".equals(method))
                                && ("(" + STATE_DESCRIPTOR + ")V").equals(descriptor)) {
                            // The static helper consumes the same receiver and argument as the original call.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "setConnectionState",
                                    "(L" + MANAGER + ";" + STATE_DESCRIPTOR + ")V", false);
                            replacements[0]++;
                        } else {
                            super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                        }
                    }
                };
            }
        }, 0);
        if (replacements[0] != 1) {
            throw new IllegalStateException("Expected one Forge server login protocol transition, found "
                    + replacements[0]);
        }
        LogManager.getLogger("jammarr").info("Ordered Forge server login protocol transition on its network thread");
        return writer.toByteArray();
    }
}
