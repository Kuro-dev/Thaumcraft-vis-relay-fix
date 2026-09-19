package com.kuro.visrelayfix;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class VisRelayFixTest {
    @Test
    public void transformerAddsRegistrationAndRecoveryHooks() {
        ClassWriter source = new ClassWriter(0);
        source.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "thaumcraft/api/visnet/TileVisNode", null, "java/lang/Object", null);
        MethodVisitor method = source.visitMethod(Opcodes.ACC_PUBLIC, "updateEntity", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "thaumcraft/api/visnet/VisNetHandler",
                "addSource",
                "(Lnet/minecraft/world/World;Lthaumcraft/api/visnet/TileVisNode;)V"
        );
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "thaumcraft/api/visnet/VisNetHandler",
                "addNode",
                "(Lnet/minecraft/world/World;Lthaumcraft/api/visnet/TileVisNode;)Ljava/lang/ref/WeakReference;"
        );
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        source.visitEnd();

        byte[] transformed = new VisRelayFixTransformer().transform(
                "thaumcraft.api.visnet.TileVisNode",
                "thaumcraft.api.visnet.TileVisNode",
                source.toByteArray()
        );

        List<String> hooks = new ArrayList<>();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM4) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM4, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor) {
                        if ("com/kuro/visrelayfix/VisRelayChunkLoader".equals(owner)) {
                            hooks.add(methodName);
                        }
                    }
                };
            }
        }, 0);

        assertEquals(3, hooks.size());
        assertEquals("registerNode", hooks.get(0));
        assertEquals("ensureNearbyChunksLoaded", hooks.get(1));
        assertEquals("recoverIfDisconnected", hooks.get(2));
    }

    @Test
    public void disconnectedNodeCanAttachToNearbySource() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode target = new FakeNode(world, 5, 64, 0, false);

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(target);
        WeakReference<Object> recovered = VisRelayChunkLoader.recoverIfDisconnected(null, target);

        assertSame(source, recovered.get());
        assertEquals(1, source.children.size());
        assertSame(target, source.children.get(0).get());
    }

    public static final class FakeWorld {
        public boolean isRemote;
    }

    public static final class FakeNode {
        public final FakeWorld worldObj;
        public final int xCoord;
        public final int yCoord;
        public final int zCoord;
        public final List<WeakReference<FakeNode>> children = new ArrayList<>();
        private final boolean source;

        private FakeNode(FakeWorld world, int x, int y, int z, boolean source) {
            this.worldObj = world;
            this.xCoord = x;
            this.yCoord = y;
            this.zCoord = z;
            this.source = source;
        }

        public boolean isInvalid() {
            return false;
        }

        public boolean isSource() {
            return source;
        }

        public WeakReference<FakeNode> getParent() {
            return null;
        }

        public List<WeakReference<FakeNode>> getChildren() {
            return children;
        }

        public int getRange() {
            return 8;
        }

        public byte getAttunement() {
            return -1;
        }
    }
}
