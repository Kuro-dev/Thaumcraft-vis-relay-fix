package com.kuro.visrelayfix;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
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
    private static final String VALIDATED_FIELD = "visRelayFixValidated";

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
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            FieldVisitor field = super.visitField(Opcodes.ACC_PRIVATE, VALIDATED_FIELD, "Z", null, null);
            if (field != null) {
                field.visitEnd();
            }
            patched = true;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            final boolean isNodeUpdate = "updateEntity".equals(name) && "()V".equals(descriptor);
            return new MethodVisitor(Opcodes.ASM4, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    if (isNodeUpdate) {
                        Label continueUpdate = new Label();
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS.replace('.', '/'), VALIDATED_FIELD, "Z");
                        visitJumpInsn(Opcodes.IFNE, continueUpdate);
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                HELPER_OWNER,
                                "validateExistingConnection",
                                "(Ljava/lang/Object;)Z"
                        );
                        Label markValidated = new Label();
                        visitJumpInsn(Opcodes.IFNE, markValidated);
                        visitInsn(Opcodes.RETURN);
                        visitLabel(markValidated);
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitInsn(Opcodes.ICONST_1);
                        visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS.replace('.', '/'), VALIDATED_FIELD, "Z");
                        visitLabel(continueUpdate);
                        patched = true;
                    }
                }

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
