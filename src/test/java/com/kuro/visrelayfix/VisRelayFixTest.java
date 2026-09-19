package com.kuro.visrelayfix;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

        assertEquals(4, hooks.size());
        assertEquals("validateExistingConnection", hooks.get(0));
        assertEquals("registerNode", hooks.get(1));
        assertEquals("ensureNearbyChunksLoaded", hooks.get(2));
        assertEquals("recoverIfDisconnected", hooks.get(3));
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

    @Test
    public void relayWithLiveButDisconnectedParentRepairsBrokenAncestor() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode disconnectedRelay = new FakeNode(world, 3, 64, 0, false);
        FakeNode target = new FakeNode(world, 5, 64, 0, false);
        target.setParent(new WeakReference<>(disconnectedRelay));
        disconnectedRelay.children.add(new WeakReference<>(target));

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(disconnectedRelay);
        VisRelayChunkLoader.registerNode(target);
        VisRelayChunkLoader.validateExistingConnection(target);

        assertSame(disconnectedRelay, target.getParent().get());
        assertEquals(1, source.children.size());
        assertSame(disconnectedRelay, source.children.get(0).get());
        assertEquals(1, disconnectedRelay.children.size());
        assertSame(target, disconnectedRelay.children.get(0).get());
        assertSame(source, disconnectedRelay.getParent().get());
        assertFalse(target.nodeRefresh);
    }

    @Test
    public void downstreamRelayDoesNotJumpToNearbySiblingDuringAncestorRepair() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode healthySibling = new FakeNode(world, 3, 64, 0, false);
        FakeNode brokenAncestor = new FakeNode(world, 8, 64, 0, false);
        FakeNode downstream = new FakeNode(world, 11, 64, 0, false);
        healthySibling.setParent(new WeakReference<>(source));
        brokenAncestor.children.add(new WeakReference<>(downstream));
        downstream.setParent(new WeakReference<>(brokenAncestor));
        source.children.add(new WeakReference<>(healthySibling));

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(healthySibling);
        VisRelayChunkLoader.registerNode(brokenAncestor);
        VisRelayChunkLoader.registerNode(downstream);
        VisRelayChunkLoader.validateExistingConnection(downstream);

        assertSame(brokenAncestor, downstream.getParent().get());
        assertSame(healthySibling, brokenAncestor.getParent().get());
        assertEquals(1, brokenAncestor.children.size());
        assertSame(downstream, brokenAncestor.children.get(0).get());
    }

    @Test
    public void refreshRestoresRememberedParentInsteadOfNearbySibling() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode rememberedParent = new FakeNode(world, 3, 64, 0, false);
        FakeNode nearbySibling = new FakeNode(world, 4, 64, 0, false);
        FakeNode target = new FakeNode(world, 5, 64, 0, false);
        rememberedParent.setParent(new WeakReference<>(source));
        nearbySibling.setParent(new WeakReference<>(source));
        target.setParent(new WeakReference<>(rememberedParent));
        source.children.add(new WeakReference<>(rememberedParent));
        source.children.add(new WeakReference<>(nearbySibling));
        rememberedParent.children.add(new WeakReference<>(target));

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(rememberedParent);
        VisRelayChunkLoader.registerNode(nearbySibling);
        VisRelayChunkLoader.validateExistingConnection(target);

        target.setParent(null);
        nearbySibling.children.add(new WeakReference<>(target));
        WeakReference<Object> recovered = VisRelayChunkLoader.recoverIfDisconnected(
                new WeakReference<Object>(nearbySibling),
                target
        );

        assertSame(rememberedParent, recovered.get());
        assertEquals(1, rememberedParent.children.size());
        assertSame(target, rememberedParent.children.get(0).get());
        assertTrue(nearbySibling.children.isEmpty());
    }

    @Test
    public void relayWithoutAnyParentRequestsThaumcraftConnectionRetry() {
        FakeWorld world = new FakeWorld();
        FakeNode target = new FakeNode(world, 5, 64, 0, false);

        VisRelayChunkLoader.validateExistingConnection(target);

        assertTrue(target.nodeRefresh);
    }

    @Test
    public void relayRetriesValidationWhileItsChainIsBroken() {
        FakeWorld world = new FakeWorld();
        FakeNode target = new FakeNode(world, 5, 64, 0, false);

        VisRelayChunkLoader.validateExistingConnection(target);
        target.nodeRefresh = false;

        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.validateExistingConnection(target);

        assertSame(source, target.getParent().get());
        assertFalse(target.nodeRefresh);
        assertEquals(1, source.children.size());
        assertSame(target, source.children.get(0).get());
    }

    @Test
    public void relayValidationWaitsForLoadedGridToSettle() {
        TimedFakeWorld world = new TimedFakeWorld();
        FakeNode target = new FakeNode(world, 5, 64, 0, false);

        VisRelayChunkLoader.validateExistingConnection(target);
        assertFalse(target.nodeRefresh);

        world.totalWorldTime = 39L;
        VisRelayChunkLoader.validateExistingConnection(target);
        assertFalse(target.nodeRefresh);

        world.totalWorldTime = 40L;
        VisRelayChunkLoader.validateExistingConnection(target);
        assertTrue(target.nodeRefresh);
    }

    @Test
    public void relayDoesNotTreatSourceInUnloadedChunkAsEnergized() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 32, 64, 0, true);
        FakeNode target = new FakeNode(world, 28, 64, 0, false);
        target.setParent(new WeakReference<>(source));
        source.children.add(new WeakReference<>(target));
        world.chunkProvider.setLoaded(2, 0, false);

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(target);
        VisRelayChunkLoader.validateExistingConnection(target);

        assertTrue(target.nodeRefresh);
    }

    @Test
    public void reloadRestoresEntireRememberedRelayBranch() {
        FakeWorld world = new FakeWorld();
        FakeNode oldSource = new FakeNode(world, 0, 64, 0, true);
        FakeNode oldFirst = new FakeNode(world, 4, 64, 0, false);
        FakeNode oldSecond = new FakeNode(world, 8, 64, 0, false);
        FakeNode oldThird = new FakeNode(world, 12, 64, 0, false);
        connect(oldSource, oldFirst);
        connect(oldFirst, oldSecond);
        connect(oldSecond, oldThird);

        VisRelayChunkLoader.registerNode(oldSource);
        VisRelayChunkLoader.validateExistingConnection(oldFirst);
        VisRelayChunkLoader.validateExistingConnection(oldSecond);
        VisRelayChunkLoader.validateExistingConnection(oldThird);

        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode first = new FakeNode(world, 4, 64, 0, false);
        FakeNode second = new FakeNode(world, 8, 64, 0, false);
        FakeNode third = new FakeNode(world, 12, 64, 0, false);
        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(first);
        VisRelayChunkLoader.registerNode(second);
        VisRelayChunkLoader.validateExistingConnection(third);

        assertSame(source, first.getParent().get());
        assertSame(first, second.getParent().get());
        assertSame(second, third.getParent().get());
        assertEquals(1, source.children.size());
        assertSame(first, source.children.get(0).get());
        assertEquals(1, first.children.size());
        assertSame(second, first.children.get(0).get());
        assertEquals(1, second.children.size());
        assertSame(third, second.children.get(0).get());
    }

    private static void connect(FakeNode parent, FakeNode child) {
        child.setParent(new WeakReference<>(parent));
        parent.children.add(new WeakReference<>(child));
    }

    public static class FakeWorld {
        public boolean isRemote;
        public final FakeChunkProvider chunkProvider = new FakeChunkProvider();

        public FakeChunkProvider getChunkProvider() {
            return chunkProvider;
        }
    }

    public static final class TimedFakeWorld extends FakeWorld {
        public long totalWorldTime;

        public long getTotalWorldTime() {
            return totalWorldTime;
        }
    }

    public static final class FakeChunkProvider {
        private final Set<String> unavailableChunks = new HashSet<>();

        public boolean chunkExists(int x, int z) {
            return !unavailableChunks.contains(x + ":" + z);
        }

        public void setLoaded(int x, int z, boolean loaded) {
            String key = x + ":" + z;
            if (loaded) {
                unavailableChunks.remove(key);
            } else {
                unavailableChunks.add(key);
            }
        }
    }

    public static final class FakeNode {
        public final FakeWorld worldObj;
        public final int xCoord;
        public final int yCoord;
        public final int zCoord;
        public final List<WeakReference<FakeNode>> children = new ArrayList<>();
        public boolean nodeRefresh;
        private final boolean source;
        private WeakReference<FakeNode> parent;

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
            return parent;
        }

        public void setParent(WeakReference<FakeNode> parent) {
            this.parent = parent;
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
