package com.kuro.visrelayfix;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class VisRelayFixTransformer implements IClassTransformer {
    private static final String TARGET_CLASS = "thaumcraft.api.visnet.TileVisNode";
    private static final String TARGET_OWNER = "thaumcraft/api/visnet/VisNetHandler";
    private static final String ADD_SOURCE_DESCRIPTOR =
            "(Lnet/minecraft/world/World;Lthaumcraft/api/visnet/TileVisNode;)V";
    private static final String TARGET_DESCRIPTOR =
            "(Lnet/minecraft/world/World;Lthaumcraft/api/visnet/TileVisNode;)Ljava/lang/ref/WeakReference;";
    private static final String HELPER_OWNER = "com/kuro/visrelayfix/VisRelayChunkLoader";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(name) && !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        PatchVisitor visitor = new PatchVisitor(writer);
        reader.accept(visitor, 0);

        return visitor.patched ? writer.toByteArray() : basicClass;
    }

    private static final class PatchVisitor extends ClassVisitor {
        private boolean patched;

        private PatchVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM4, delegate);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM4, delegate) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor) {
                    if (opcode == Opcodes.INVOKESTATIC && TARGET_OWNER.equals(owner)
                            && "addSource".equals(methodName)
                            && ADD_SOURCE_DESCRIPTOR.equals(methodDescriptor)) {
                        visitInsn(Opcodes.DUP);
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                HELPER_OWNER,
                                "registerNode",
                                "(Ljava/lang/Object;)V"
                        );
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor);
                        patched = true;
                        return;
                    }

                    if (opcode == Opcodes.INVOKESTATIC
                            && TARGET_OWNER.equals(owner)
                            && "addNode".equals(methodName)
                            && TARGET_DESCRIPTOR.equals(methodDescriptor)) {
                        // The TileVisNode argument is already the top stack value here.
                        // Duplicate it so the helper can load chunks without changing the
                        // original addNode call or its return value.
                        visitInsn(Opcodes.DUP);
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                HELPER_OWNER,
                                "ensureNearbyChunksLoaded",
                                "(Ljava/lang/Object;)V"
                        );

                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor);
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                HELPER_OWNER,
                                "recoverIfDisconnected",
                                "(Ljava/lang/ref/WeakReference;Ljava/lang/Object;)Ljava/lang/ref/WeakReference;"
                        );
                        patched = true;
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor);
                }
            };
        }
    }
}
