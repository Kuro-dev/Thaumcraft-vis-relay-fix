package com.kuro.visrelayfix;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        final boolean[] hasValidationField = new boolean[1];
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM4) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if ("visRelayFixValidated".equals(name) && "Z".equals(descriptor)) {
                    hasValidationField[0] = true;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

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
        assertTrue(hasValidationField[0]);
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
    public void relayWithLiveButDisconnectedParentRejoinsTheEnergizedGrid() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode disconnectedRelay = new FakeNode(world, 3, 64, 0, false);
        FakeNode target = new FakeNode(world, 5, 64, 0, false);
        target.setParent(new WeakReference<>(disconnectedRelay));
        disconnectedRelay.children.add(new WeakReference<>(target));

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(disconnectedRelay);
        VisRelayChunkLoader.registerNode(target);
        assertTrue(VisRelayChunkLoader.validateExistingConnection(target));
        assertTrue(VisRelayChunkLoader.validateExistingConnection(disconnectedRelay));

        assertTrue(reachesSource(source, target));
        assertTrue(reachesSource(source, disconnectedRelay));
        assertFalse(target.nodeRefresh);
    }

    @Test
    public void recoveredLinksRefreshBothVisualEndpoints() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode oldParent = new FakeNode(world, 12, 64, 0, false);
        FakeNode target = new FakeNode(world, 5, 64, 0, false);
        target.setParent(new WeakReference<>(oldParent));
        oldParent.children.add(new WeakReference<>(target));

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(oldParent);
        VisRelayChunkLoader.registerNode(target);
        VisRelayChunkLoader.validateExistingConnection(target);

        assertTrue(world.updatedBlocks.contains("5:64:0"));
        assertTrue(world.updatedBlocks.contains("12:64:0"));
        assertTrue(world.updatedBlocks.contains("0:64:0"));
    }

    @Test
    public void chunkReloadClearsRelayLinksBeforeRebuildingFromTheSource() {
        TimedFakeWorld world = new TimedFakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode firstRelay = new FakeNode(world, 0, 70, 0, false);
        FakeNode downstreamRelay = new FakeNode(world, 0, 76, 0, false);
        connect(source, firstRelay);
        connect(firstRelay, downstreamRelay);

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(firstRelay);
        VisRelayChunkLoader.registerNode(downstreamRelay);
        VisRelayChunkLoader.validateExistingConnection(source);
        assertFalse(VisRelayChunkLoader.validateExistingConnection(firstRelay));
        assertFalse(VisRelayChunkLoader.validateExistingConnection(downstreamRelay));

        assertNull(firstRelay.getParent());
        assertNull(downstreamRelay.getParent());
        assertTrue(source.children.isEmpty());
        assertTrue(firstRelay.children.isEmpty());

        world.totalWorldTime = 40L;
        assertTrue(VisRelayChunkLoader.validateExistingConnection(downstreamRelay));

        assertTrue(reachesSource(source, firstRelay));
        assertTrue(reachesSource(source, downstreamRelay));
        assertFalse(firstRelay.nodeRefresh);
        assertFalse(downstreamRelay.nodeRefresh);
    }

    @Test
    public void disconnectedRelaysRejoinTheEnergizedGridThroughAnyValidRoute() {
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
        assertTrue(VisRelayChunkLoader.validateExistingConnection(downstream));
        assertTrue(VisRelayChunkLoader.validateExistingConnection(brokenAncestor));

        assertTrue(reachesSource(source, downstream));
        assertTrue(reachesSource(source, brokenAncestor));
    }

    @Test
    public void fullyDisconnectedBranchBootstrapsFromNearestSourceRelay() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode firstRelay = new FakeNode(world, 0, 70, 0, false);
        FakeNode downstreamRelay = new FakeNode(world, 0, 76, 0, false);

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(firstRelay);
        VisRelayChunkLoader.registerNode(downstreamRelay);
        VisRelayChunkLoader.validateExistingConnection(downstreamRelay);

        assertSame(source, firstRelay.getParent().get());
        assertSame(firstRelay, downstreamRelay.getParent().get());
        assertEquals(1, source.children.size());
        assertSame(firstRelay, source.children.get(0).get());
        assertEquals(1, firstRelay.children.size());
        assertSame(downstreamRelay, firstRelay.children.get(0).get());
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
        VisRelayChunkLoader.registerNode(target);

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
    public void relayWithoutAnyParentStaysUnderTheCustomRetryScheduler() {
        FakeWorld world = new FakeWorld();
        FakeNode target = new FakeNode(world, 5, 64, 0, false);

        VisRelayChunkLoader.validateExistingConnection(target);

        assertFalse(target.nodeRefresh);
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
        assertFalse(target.nodeRefresh);
    }

    @Test
    public void healthyRelayValidationBecomesQuiescentAfterLoadCheck() {
        TimedFakeWorld world = new TimedFakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode relay = new FakeNode(world, 5, 64, 0, false);
        connect(source, relay);

        VisRelayChunkLoader.registerNode(source);
        assertFalse(VisRelayChunkLoader.validateExistingConnection(relay));
        world.totalWorldTime = 40L;
        assertTrue(VisRelayChunkLoader.validateExistingConnection(relay));
        relay.resetParentAccesses();

        for (int tick = 0; tick < 100; tick++) {
            VisRelayChunkLoader.validateExistingConnection(relay);
        }

        assertEquals(0, relay.parentAccesses);
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

        assertFalse(target.nodeRefresh);
    }

    @Test
    public void reloadRebuildsEntireRelayBranchFromSource() {
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

    @Test
    public void verticalReloadWaitsForCurrentRelayInstancesBeforeRebuilding() {
        FakeWorld world = new FakeWorld();
        FakeNode oldSource = new FakeNode(world, 0, 64, 0, true);
        FakeNode oldFirst = new FakeNode(world, 0, 70, 0, false);
        FakeNode oldSecond = new FakeNode(world, 0, 76, 0, false);
        FakeNode oldThird = new FakeNode(world, 0, 82, 0, false);
        connect(oldSource, oldFirst);
        connect(oldFirst, oldSecond);
        connect(oldSecond, oldThird);

        VisRelayChunkLoader.registerNode(oldSource);
        VisRelayChunkLoader.validateExistingConnection(oldFirst);
        VisRelayChunkLoader.validateExistingConnection(oldSecond);
        VisRelayChunkLoader.validateExistingConnection(oldThird);

        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode first = new FakeNode(world, 0, 70, 0, false);
        FakeNode second = new FakeNode(world, 0, 76, 0, false);
        FakeNode third = new FakeNode(world, 0, 82, 0, false);
        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(third);

        VisRelayChunkLoader.validateExistingConnection(third);
        assertNull(third.getParent());
        assertFalse(third.nodeRefresh);

        VisRelayChunkLoader.registerNode(first);
        VisRelayChunkLoader.registerNode(second);
        VisRelayChunkLoader.validateExistingConnection(third);

        assertSame(source, first.getParent().get());
        assertSame(first, second.getParent().get());
        assertSame(second, third.getParent().get());
    }

    @Test
    public void verticalRoutesSurviveRepeatedPartialReloads() {
        FakeWorld world = new FakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode first = new FakeNode(world, 0, 70, 0, false);
        FakeNode second = new FakeNode(world, 0, 76, 0, false);
        connect(source, first);
        connect(first, second);

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.validateExistingConnection(first);
        VisRelayChunkLoader.validateExistingConnection(second);

        for (int reload = 0; reload < 3; reload++) {
            source = new FakeNode(world, 0, 64, 0, true);
            first = new FakeNode(world, 0, 70, 0, false);
            second = new FakeNode(world, 0, 76, 0, false);
            VisRelayChunkLoader.registerNode(source);
            VisRelayChunkLoader.registerNode(second);

            VisRelayChunkLoader.validateExistingConnection(second);
            assertNull(second.getParent());

            VisRelayChunkLoader.registerNode(first);
            VisRelayChunkLoader.validateExistingConnection(second);

            assertSame(source, first.getParent().get());
            assertSame(first, second.getParent().get());
        }
    }

    @Test
    public void inactiveRememberedRoutesArePrunedFromLongLivedWorlds() throws Exception {
        TimedFakeWorld world = new TimedFakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode relay = new FakeNode(world, 0, 70, 0, false);
        connect(source, relay);

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(relay);
        assertEquals(1, rememberedParentCount(world));

        world.totalWorldTime = 1_000_000L;
        VisRelayChunkLoader.validateExistingConnection(source);

        assertEquals(0, rememberedParentCount(world));
    }

    @Test
    public void staleNodeReferencesArePrunedFromLongLivedWorlds() throws Exception {
        TimedFakeWorld world = new TimedFakeWorld();
        FakeNode source = new FakeNode(world, 0, 64, 0, true);
        FakeNode removedRelay = new FakeNode(world, 10, 64, 0, false);

        VisRelayChunkLoader.registerNode(source);
        VisRelayChunkLoader.registerNode(removedRelay);
        assertEquals(2, registeredNodeCount(world));

        world.removeTileEntity(10, 64, 0);
        world.totalWorldTime = 1_000_000L;
        VisRelayChunkLoader.validateExistingConnection(source);

        assertEquals(1, registeredNodeCount(world));
    }

    @Test
    public void partialChunkUnloadPrunesNodesButKeepsTheRememberedRoute() throws Exception {
        TimedFakeWorld world = new TimedFakeWorld();
        FakeNode oldSource = new FakeNode(world, 32, 64, 0, true);
        FakeNode relay = new FakeNode(world, 28, 64, 0, false);
        connect(oldSource, relay);

        VisRelayChunkLoader.registerNode(oldSource);
        VisRelayChunkLoader.registerNode(relay);
        assertEquals(2, registeredNodeCount(world));
        assertEquals(1, rememberedParentCount(world));

        world.chunkProvider.setLoaded(2, 0, false);
        world.totalWorldTime = 1_200L;
        VisRelayChunkLoader.registerNode(relay);

        assertEquals(1, registeredNodeCount(world));
        assertEquals(1, rememberedParentCount(world));

        FakeNode reloadedSource = new FakeNode(world, 32, 64, 0, true);
        world.chunkProvider.setLoaded(2, 0, true);
        VisRelayChunkLoader.registerNode(reloadedSource);
        WeakReference<Object> recovered = VisRelayChunkLoader.recoverIfDisconnected(null, relay);

        assertSame(reloadedSource, recovered.get());
        relay.setParent(new WeakReference<>(reloadedSource));
        assertSame(reloadedSource, relay.getParent().get());
    }

    @SuppressWarnings("unchecked")
    private static int rememberedParentCount(FakeWorld world) throws Exception {
        Field cache = VisRelayChunkLoader.class.getDeclaredField("LAST_KNOWN_PARENTS");
        cache.setAccessible(true);
        Map<Object, Map<?, ?>> parents = (Map<Object, Map<?, ?>>) cache.get(null);
        Map<?, ?> worldParents = parents.get(world);
        return worldParents == null ? 0 : worldParents.size();
    }

    @SuppressWarnings("unchecked")
    private static int registeredNodeCount(FakeWorld world) throws Exception {
        Field cache = VisRelayChunkLoader.class.getDeclaredField("NODES");
        cache.setAccessible(true);
        Map<Object, Map<?, ?>> nodes = (Map<Object, Map<?, ?>>) cache.get(null);
        Map<?, ?> worldNodes = nodes.get(world);
        return worldNodes == null ? 0 : worldNodes.size();
    }

    private static void connect(FakeNode parent, FakeNode child) {
        child.setParent(new WeakReference<>(parent));
        parent.children.add(new WeakReference<>(child));
    }

    private static boolean reachesSource(FakeNode source, FakeNode node) {
        Set<FakeNode> visited = new HashSet<>();
        FakeNode current = node;
        for (int depth = 0; depth < 512 && current != null && visited.add(current); depth++) {
            if (current == source) {
                return true;
            }
            WeakReference<FakeNode> parent = current.getParent();
            current = parent == null ? null : parent.get();
        }
        return false;
    }

    public static class FakeWorld {
        public boolean isRemote;
        public final FakeChunkProvider chunkProvider = new FakeChunkProvider();
        public final Set<String> updatedBlocks = new HashSet<>();
        private final java.util.Map<String, FakeNode> tileEntities = new java.util.HashMap<>();

        public FakeChunkProvider getChunkProvider() {
            return chunkProvider;
        }

        public FakeNode getTileEntity(int x, int y, int z) {
            return tileEntities.get(x + ":" + y + ":" + z);
        }

        private void setTileEntity(FakeNode node) {
            tileEntities.put(node.xCoord + ":" + node.yCoord + ":" + node.zCoord, node);
        }

        void removeTileEntity(int x, int y, int z) {
            tileEntities.remove(x + ":" + y + ":" + z);
        }

        public void markBlockForUpdate(int x, int y, int z) {
            updatedBlocks.add(x + ":" + y + ":" + z);
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
        public int parentAccesses;
        private final boolean source;
        private WeakReference<FakeNode> parent;

        private FakeNode(FakeWorld world, int x, int y, int z, boolean source) {
            this.worldObj = world;
            this.xCoord = x;
            this.yCoord = y;
            this.zCoord = z;
            this.source = source;
            world.setTileEntity(this);
        }

        public boolean isInvalid() {
            return false;
        }

        public boolean isSource() {
            return source;
        }

        public WeakReference<FakeNode> getParent() {
            parentAccesses++;
            return parent;
        }

        public void resetParentAccesses() {
            parentAccesses = 0;
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
