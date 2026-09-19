package com.kuro.visrelayfix;

import cpw.mods.fml.common.FMLLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class VisRelayChunkLoader {
    private static final long LOAD_SETTLE_TICKS = 40L;
    private static final long RECOVERY_INTERVAL_TICKS = 200L;
    private static final long STATE_CLEANUP_INTERVAL_TICKS = 1_200L;
    private static final long REMEMBERED_PARENT_RETENTION_TICKS = 432_000L;
    private static final Map<Object, Map<NodeKey, WeakReference<Object>>> NODES = new WeakHashMap<>();
    private static final Map<Object, Map<NodeKey, RememberedParent>> LAST_KNOWN_PARENTS = new WeakHashMap<>();
    private static final Map<Object, Long> VALIDATION_DUE = new WeakHashMap<>();
    private static final Map<Object, Long> NEXT_RECOVERY = new WeakHashMap<>();
    private static final Map<Object, Boolean> BROKEN_LOGGED = new WeakHashMap<>();
    private static final Map<Object, Boolean> VALIDATED_ON_LOAD = new WeakHashMap<>();
    private static final Map<Object, Boolean> OBSERVED_NODES = new WeakHashMap<>();
    private static final Map<Object, Long> NEXT_STATE_CLEANUP = new WeakHashMap<>();

    private VisRelayChunkLoader() {
    }

    public static void ensureNearbyChunksLoaded(Object node) {
        if (node == null) {
            return;
        }

        Object world = readValue(node, "worldObj", "field_145850_b", "getWorldObj()", "func_145831_w()");
        if (world == null || readBoolean(world, "isRemote", "field_72995_K")) {
            return;
        }

        register(world, node);

        Object provider = invokeNoArgs(world, "getChunkProvider", "func_72863_F");
        if (provider == null) {
            return;
        }

        Object xValue = readValue(node, "xCoord", "field_145851_c");
        Object zValue = readValue(node, "zCoord", "field_145849_e");
        if (!(xValue instanceof Number) || !(zValue instanceof Number)) {
            return;
        }

        int centerChunkX = ((Number) xValue).intValue() >> 4;
        int centerChunkZ = ((Number) zValue).intValue() >> 4;

        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                int chunkX = centerChunkX + offsetX;
                int chunkZ = centerChunkZ + offsetZ;
                Object chunk;
                if (!invokeBoolean(provider, new String[]{"chunkExists", "func_73149_a"}, chunkX, chunkZ)) {
                    chunk = invoke(provider, new String[]{"loadChunk", "func_73158_c"}, chunkX, chunkZ);
                } else {
                    chunk = invoke(provider, new String[]{"provideChunk", "func_73154_d"}, chunkX, chunkZ);
                }
                if (chunk != null) {
                    registerNodesInChunk(world, chunk);
                }
            }
        }
    }

    public static void registerNode(Object node) {
        if (node == null || isInvalid(node)) {
            return;
        }

        Object world = readValue(node, "worldObj", "field_145850_b", "getWorldObj()", "func_145831_w()");
        if (world == null || readBoolean(world, "isRemote", "field_72995_K")) {
            return;
        }

        register(world, node);
        rememberCurrentParent(world, node);
    }

    /**
     * Runs from TileVisNode.updateEntity. A live direct parent is not enough:
     * every relay in its parent chain must eventually reach a source node.
     */
    public static void validateExistingConnection(Object node) {
        if (node == null || isValidationComplete(node)) {
            return;
        }
        if (isInvalid(node)) {
            return;
        }

        Object world = readValue(node, "worldObj", "field_145850_b", "getWorldObj()", "func_145831_w()");
        if (world == null || readBoolean(world, "isRemote", "field_72995_K")) {
            return;
        }

        if (markNodeObserved(node)) {
            register(world, node);
            rememberCurrentParent(world, node);
        }

        if (isSource(node)) {
            markValidationComplete(node);
            return;
        }
        if (!shouldValidateOnLoad(node, world)) {
            return;
        }

        rememberCurrentParent(world, node);

        if (restoreRememberedParentChain(world, node) || isConnected(node)) {
            logFixedIfPreviouslyBroken(world, node, getReference(node, "getParent()"));
            return;
        }

        Object repairTarget = findDisconnectedAncestor(node);
        if (repairTarget == null) {
            if (markBrokenIfNeeded(node)) {
                logBroken(world, node);
            }
            scheduleValidationRetry(node, world);
            return;
        }

        repairDisconnectedNode(world, repairTarget);
        if (!isConnected(node)) {
            scheduleValidationRetry(node, world);
        }
    }

    private static void repairDisconnectedNode(Object world, Object node) {
        if (isConnected(node)) {
            logFixedIfPreviouslyBroken(world, node, getReference(node, "getParent()"));
            return;
        }

        if (markBrokenIfNeeded(node)) {
            logBroken(world, node);
        }

        // Repair the first broken edge rather than rerouting a healthy child
        // farther down the chain. This keeps branching relay layouts stable.
        ensureNearbyChunksLoaded(node);

        WeakReference<Object> oldParent = getReference(node, "getParent()");
        WeakReference<Object> recovered = findAndLinkNearbyNode(world, node, oldParent);
        if (isConnectedReference(recovered) && setParent(node, recovered)) {
            removeChildReference(oldParent, node);
            rememberParent(world, node, recovered);
            notifyParentChanged(world, node);
            logFixedIfPreviouslyBroken(world, node, recovered);
            return;
        }

        // When an entire relay branch has lost its parents, there is no
        // connected relay for the normal search to grow from. Root the nearest
        // valid relay beside a source, then retry this relay against that new
        // energized branch.
        if (bootstrapRelayFromSource(world)) {
            if (isConnected(node)) {
                return;
            }
            recovered = findAndLinkNearbyNode(world, node, oldParent);
            if (isConnectedReference(recovered) && setParent(node, recovered)) {
                removeChildReference(oldParent, node);
                rememberParent(world, node, recovered);
                notifyParentChanged(world, node);
                logFixedIfPreviouslyBroken(world, node, recovered);
                return;
            }
        }

        // Keep a live but disconnected parent in place while the local area
        // settles. Clearing it would make Thaumcraft redraw the relay every
        // forty ticks even though no better route has appeared yet.
        if (!isValidReference(oldParent)) {
            setNodeRefresh(node);
        }
    }

    public static WeakReference<Object> recoverIfDisconnected(WeakReference<Object> originalParent, Object node) {
        if (node == null || isInvalid(node)) {
            return originalParent;
        }

        Object world = readValue(node, "worldObj", "field_145850_b", "getWorldObj()", "func_145831_w()");
        if (world == null || readBoolean(world, "isRemote", "field_72995_K")) {
            return originalParent;
        }

        register(world, node);
        WeakReference<Object> rememberedParent = findRememberedParent(world, node);
        if (canUseAsParent(node, rememberedParent)) {
            removeChildReference(originalParent, node);
            linkChild(rememberedParent.get(), node);
            rememberParent(world, node, rememberedParent);
            logFixedIfPreviouslyBroken(world, node, rememberedParent);
            return rememberedParent;
        }

        if (isConnectedReference(originalParent)) {
            rememberParent(world, node, originalParent);
            logFixedIfPreviouslyBroken(world, node, originalParent);
            return originalParent;
        }

        long worldTime = readWorldTime(world);
        synchronized (NODES) {
            Long nextAttempt = NEXT_RECOVERY.get(node);
            if (worldTime >= 0L && nextAttempt != null && worldTime < nextAttempt) {
                return originalParent;
            }
            if (worldTime >= 0L) {
                NEXT_RECOVERY.put(node, worldTime + RECOVERY_INTERVAL_TICKS);
            }
        }

        if (markBrokenIfNeeded(node)) {
            logBroken(world, node);
        }

        WeakReference<Object> recovered = findAndLinkNearbyNode(world, node, originalParent);
        if (!isConnectedReference(recovered) && bootstrapRelayFromSource(world)) {
            if (isConnected(node)) {
                return getReference(node, "getParent()");
            }
            recovered = findAndLinkNearbyNode(world, node, originalParent);
        }
        if (isConnectedReference(recovered)) {
            removeChildReference(originalParent, node);
            rememberParent(world, node, recovered);
            logFixedIfPreviouslyBroken(world, node, recovered);
        }
        return recovered;
    }

    private static void register(Object world, Object node) {
        Object xValue = readValue(node, "xCoord", "field_145851_c");
        Object yValue = readValue(node, "yCoord", "field_145848_d");
        Object zValue = readValue(node, "zCoord", "field_145849_e");
        if (!(xValue instanceof Number) || !(yValue instanceof Number) || !(zValue instanceof Number)) {
            return;
        }

        cleanupWorldState(world);
        synchronized (NODES) {
            Map<NodeKey, WeakReference<Object>> worldNodes = NODES.get(world);
            if (worldNodes == null) {
                worldNodes = new HashMap<>();
                NODES.put(world, worldNodes);
            }
            worldNodes.put(
                    new NodeKey(((Number) xValue).intValue(), ((Number) yValue).intValue(), ((Number) zValue).intValue()),
                    new WeakReference<>(node)
            );
        }
    }

    private static void registerNodesInChunk(Object world, Object chunk) {
        Object tileEntities = readValue(chunk, "chunkTileEntityMap", "field_150816_i");
        if (!(tileEntities instanceof Map)) {
            return;
        }

        for (Object tileEntity : ((Map<?, ?>) tileEntities).values()) {
            if (isVisNode(tileEntity)) {
                register(world, tileEntity);
                rememberCurrentParent(world, tileEntity);
            }
        }
    }

    private static WeakReference<Object> findAndLinkNearbyNode(Object world, Object node, WeakReference<Object> originalParent) {
        Object xValue = readValue(node, "xCoord", "field_145851_c");
        Object yValue = readValue(node, "yCoord", "field_145848_d");
        Object zValue = readValue(node, "zCoord", "field_145849_e");
        Object rangeValue = readValue(node, "getRange()");
        Object attunementValue = readValue(node, "getAttunement()");
        if (!(xValue instanceof Number) || !(yValue instanceof Number) || !(zValue instanceof Number)
                || !(rangeValue instanceof Number) || !(attunementValue instanceof Number)) {
            return originalParent;
        }

        int x = ((Number) xValue).intValue();
        int y = ((Number) yValue).intValue();
        int z = ((Number) zValue).intValue();
        int range = ((Number) rangeValue).intValue();
        int attunement = ((Number) attunementValue).intValue();
        long maxDistance = (long) range * range;

        Object closest = null;
        long closestDistance = Long.MAX_VALUE;
        synchronized (NODES) {
            Map<NodeKey, WeakReference<Object>> worldNodes = NODES.get(world);
            if (worldNodes == null) {
                return originalParent;
            }

            for (java.util.Iterator<Map.Entry<NodeKey, WeakReference<Object>>> iterator = worldNodes.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<NodeKey, WeakReference<Object>> entry = iterator.next();
                Object candidate = entry.getValue().get();
                if (candidate == null || isInvalid(candidate)) {
                    iterator.remove();
                    continue;
                }
                if (candidate == node || !isConnected(candidate) || wouldCreateCycle(node, candidate)) {
                    continue;
                }

                NodeKey key = entry.getKey();
                long dx = (long) key.x - x;
                long dy = (long) key.y - y;
                long dz = (long) key.z - z;
                long distance = dx * dx + dy * dy + dz * dz;
                if (distance > maxDistance || distance >= closestDistance) {
                    continue;
                }

                Object candidateAttunement = readValue(candidate, "getAttunement()");
                if (!(candidateAttunement instanceof Number)) {
                    continue;
                }
                int candidateColor = ((Number) candidateAttunement).intValue();
                if (attunement != -1 && candidateColor != -1 && attunement != candidateColor) {
                    continue;
                }
                if (!canNodeBeSeen(node, candidate)) {
                    continue;
                }

                closest = candidate;
                closestDistance = distance;
            }

            if (closest == null) {
                return originalParent;
            }

            Object children = invokeNoArgs(closest, "getChildren()");
            if (!(children instanceof List)) {
                return originalParent;
            }

            List<?> childList = (List<?>) children;
            for (Object childRef : childList) {
                if (childRef instanceof WeakReference && ((WeakReference<?>) childRef).get() == node) {
                    clearNearbyNodeCache();
                    return new WeakReference<>(closest);
                }
            }
            ((List<Object>) childList).add(new WeakReference<>(node));
            clearNearbyNodeCache();
            return new WeakReference<>(closest);
        }
    }

    /**
     * Restarts a fully disconnected relay branch from the source outward. The
     * normal recovery path deliberately ignores unrooted relays; this picks
     * one deterministic, visible source-to-relay edge to become the new root.
     */
    private static boolean bootstrapRelayFromSource(Object world) {
        Object closestRelay = null;
        Object closestSource = null;
        NodeKey closestRelayKey = null;
        NodeKey closestSourceKey = null;
        long closestDistance = Long.MAX_VALUE;

        synchronized (NODES) {
            Map<NodeKey, WeakReference<Object>> worldNodes = NODES.get(world);
            if (worldNodes == null) {
                return false;
            }

            for (Map.Entry<NodeKey, WeakReference<Object>> relayEntry : worldNodes.entrySet()) {
                Object relay = relayEntry.getValue().get();
                if (relay == null || isInvalid(relay) || isSource(relay) || isConnected(relay)) {
                    continue;
                }

                for (Map.Entry<NodeKey, WeakReference<Object>> sourceEntry : worldNodes.entrySet()) {
                    Object source = sourceEntry.getValue().get();
                    if (source == null || !isSource(source)
                            || !canUseAsParent(relay, new WeakReference<>(source))) {
                        continue;
                    }

                    long distance = squaredDistance(relayEntry.getKey(), sourceEntry.getKey());
                    if (distance < closestDistance
                            || distance == closestDistance && isEarlierPair(
                            relayEntry.getKey(), sourceEntry.getKey(), closestRelayKey, closestSourceKey)) {
                        closestRelay = relay;
                        closestSource = source;
                        closestRelayKey = relayEntry.getKey();
                        closestSourceKey = sourceEntry.getKey();
                        closestDistance = distance;
                    }
                }
            }
        }

        if (closestRelay == null || closestSource == null) {
            return false;
        }

        WeakReference<Object> sourceReference = new WeakReference<>(closestSource);
        WeakReference<Object> oldParent = getReference(closestRelay, "getParent()");
        if (!setParent(closestRelay, sourceReference)) {
            return false;
        }

        if (markBrokenIfNeeded(closestRelay)) {
            logBroken(world, closestRelay);
        }
        removeChildReference(oldParent, closestRelay);
        linkChild(closestSource, closestRelay);
        rememberParent(world, closestRelay, sourceReference);
        notifyParentChanged(world, closestRelay);
        logFixedIfPreviouslyBroken(world, closestRelay, sourceReference);
        return true;
    }

    private static long squaredDistance(NodeKey first, NodeKey second) {
        long dx = (long) first.x - second.x;
        long dy = (long) first.y - second.y;
        long dz = (long) first.z - second.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isEarlierPair(NodeKey relay, NodeKey source, NodeKey currentRelay, NodeKey currentSource) {
        if (currentRelay == null || currentSource == null) {
            return true;
        }

        int relayComparison = compareNodeKeys(relay, currentRelay);
        return relayComparison < 0 || relayComparison == 0 && compareNodeKeys(source, currentSource) < 0;
    }

    private static int compareNodeKeys(NodeKey first, NodeKey second) {
        if (first.x != second.x) {
            return first.x < second.x ? -1 : 1;
        }
        if (first.y != second.y) {
            return first.y < second.y ? -1 : 1;
        }
        if (first.z == second.z) {
            return 0;
        }
        return first.z < second.z ? -1 : 1;
    }

    private static boolean isConnected(Object node) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Object current = node;
        for (int depth = 0; depth < 512 && current != null; depth++) {
            if (!visited.add(current)) {
                return false;
            }
            if (!isLiveNode(current)) {
                return false;
            }

            if (isSource(current)) {
                return true;
            }

            Object parent = invokeNoArgs(current, "getParent()");
            if (!isValidReference(parent)) {
                return false;
            }
            current = ((WeakReference<?>) parent).get();
        }
        return false;
    }

    private static boolean isConnectedReference(Object reference) {
        return isValidReference(reference) && isConnected(((WeakReference<?>) reference).get());
    }

    private static void rememberCurrentParent(Object world, Object node) {
        rememberParent(world, node, getReference(node, "getParent()"));
    }

    private static void rememberParent(Object world, Object node, WeakReference<Object> parentReference) {
        if (!isConnectedReference(parentReference)) {
            return;
        }

        NodeKey nodeKey = getNodeKey(node);
        NodeKey parentKey = getNodeKey(parentReference.get());
        if (nodeKey == null || parentKey == null) {
            return;
        }

        synchronized (NODES) {
            Map<NodeKey, RememberedParent> worldParents = LAST_KNOWN_PARENTS.get(world);
            if (worldParents == null) {
                worldParents = new HashMap<>();
                LAST_KNOWN_PARENTS.put(world, worldParents);
            }
            worldParents.put(nodeKey, new RememberedParent(parentKey, readWorldTime(world)));
        }
    }

    private static WeakReference<Object> findRememberedParent(Object world, Object node) {
        NodeKey nodeKey = getNodeKey(node);
        if (nodeKey == null) {
            return null;
        }

        synchronized (NODES) {
            Map<NodeKey, RememberedParent> worldParents = LAST_KNOWN_PARENTS.get(world);
            Map<NodeKey, WeakReference<Object>> worldNodes = NODES.get(world);
            if (worldParents == null || worldNodes == null) {
                return null;
            }

            RememberedParent remembered = worldParents.get(nodeKey);
            WeakReference<Object> parent = remembered == null ? null : worldNodes.get(remembered.parentKey);
            if (!isValidReference(parent)) {
                return null;
            }
            remembered.lastUsedAt = readWorldTime(world);
            return parent;
        }
    }

    /**
     * The weak maps release worlds and TileEntities, but their coordinate keys
     * still need pruning while a server world remains open. Keep remembered
     * links long enough for ordinary chunk travel, then drop inactive routes.
     */
    private static void cleanupWorldState(Object world) {
        long worldTime = readWorldTime(world);
        if (worldTime < 0L) {
            return;
        }

        synchronized (NODES) {
            Long nextCleanup = NEXT_STATE_CLEANUP.get(world);
            if (nextCleanup != null && worldTime < nextCleanup) {
                return;
            }
            NEXT_STATE_CLEANUP.put(world, worldTime + STATE_CLEANUP_INTERVAL_TICKS);

            Map<NodeKey, WeakReference<Object>> worldNodes = NODES.get(world);
            if (worldNodes != null) {
                for (java.util.Iterator<Map.Entry<NodeKey, WeakReference<Object>>> iterator = worldNodes.entrySet().iterator(); iterator.hasNext();) {
                    Object candidate = iterator.next().getValue().get();
                    if (candidate == null || !isLiveNode(candidate)) {
                        iterator.remove();
                    }
                }
                if (worldNodes.isEmpty()) {
                    NODES.remove(world);
                }
            }

            Map<NodeKey, RememberedParent> worldParents = LAST_KNOWN_PARENTS.get(world);
            if (worldParents != null) {
                for (java.util.Iterator<RememberedParent> iterator = worldParents.values().iterator(); iterator.hasNext();) {
                    RememberedParent remembered = iterator.next();
                    if (remembered.lastUsedAt >= 0L
                            && worldTime - remembered.lastUsedAt > REMEMBERED_PARENT_RETENTION_TICKS) {
                        iterator.remove();
                    }
                }
                if (worldParents.isEmpty()) {
                    LAST_KNOWN_PARENTS.remove(world);
                }
            }
        }
    }

    /**
     * Rebuilds a remembered relay branch from its rooted parent outward. This
     * lets a whole loaded branch recover in one validation pass instead of
     * relying on the order in which TileEntities happen to tick after reload.
     */
    private static boolean restoreRememberedParentChain(Object world, Object node) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        return restoreRememberedParentChain(world, node, visited);
    }

    private static boolean restoreRememberedParentChain(Object world, Object node, Set<Object> visited) {
        if (isConnected(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }

        WeakReference<Object> parentReference = findRememberedParent(world, node);
        if (parentReference == null) {
            return false;
        }

        Object parent = parentReference.get();
        if (!restoreRememberedParentChain(world, parent, visited)
                || !canUseAsParent(node, parentReference)) {
            return false;
        }

        WeakReference<Object> oldParent = getReference(node, "getParent()");
        if (!setParent(node, parentReference)) {
            return false;
        }
        removeChildReference(oldParent, node);
        linkChild(parent, node);
        rememberParent(world, node, parentReference);
        notifyParentChanged(world, node);
        logFixedIfPreviouslyBroken(world, node, parentReference);
        return true;
    }

    private static boolean canUseAsParent(Object node, WeakReference<Object> parentReference) {
        if (!isConnectedReference(parentReference)) {
            return false;
        }

        Object parent = parentReference.get();
        if (parent == node || wouldCreateCycle(node, parent)) {
            return false;
        }

        NodeKey nodeKey = getNodeKey(node);
        NodeKey parentKey = getNodeKey(parent);
        Object rangeValue = readValue(node, "getRange()");
        Object attunementValue = readValue(node, "getAttunement()");
        Object parentAttunementValue = readValue(parent, "getAttunement()");
        if (nodeKey == null || parentKey == null || !(rangeValue instanceof Number)
                || !(attunementValue instanceof Number) || !(parentAttunementValue instanceof Number)) {
            return false;
        }

        long dx = (long) parentKey.x - nodeKey.x;
        long dy = (long) parentKey.y - nodeKey.y;
        long dz = (long) parentKey.z - nodeKey.z;
        long range = ((Number) rangeValue).longValue();
        if (dx * dx + dy * dy + dz * dz > range * range) {
            return false;
        }

        int attunement = ((Number) attunementValue).intValue();
        int parentAttunement = ((Number) parentAttunementValue).intValue();
        return (attunement == -1 || parentAttunement == -1 || attunement == parentAttunement)
                && canNodeBeSeen(node, parent);
    }

    /**
     * Finds the first upstream relay without a live parent. Repairing that
     * relay restores its entire child branch and avoids side-linking siblings.
     * A fully live cycle has no safe automatic repair point.
     */
    private static Object findDisconnectedAncestor(Object node) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Object current = node;
        for (int depth = 0; depth < 512 && current != null; depth++) {
            if (!visited.add(current) || isSource(current)) {
                return null;
            }

            Object parent = invokeNoArgs(current, "getParent()");
            if (!isValidReference(parent)) {
                return current;
            }
            current = ((WeakReference<?>) parent).get();
        }
        return null;
    }

    private static boolean wouldCreateCycle(Object node, Object candidate) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Object current = candidate;
        for (int depth = 0; depth < 512 && current != null; depth++) {
            if (current == node) {
                return true;
            }
            if (!visited.add(current)) {
                return true;
            }

            Object parent = invokeNoArgs(current, "getParent()");
            if (!isValidReference(parent)) {
                return false;
            }
            current = ((WeakReference<?>) parent).get();
        }
        return true;
    }

    private static boolean isValidReference(Object reference) {
        if (!(reference instanceof WeakReference)) {
            return false;
        }
        Object node = ((WeakReference<?>) reference).get();
        return node != null && isLiveNode(node);
    }

    private static boolean isInvalid(Object node) {
        Object invalid = readValue(node, "isInvalid()", "func_145837_r()");
        return invalid instanceof Boolean && (Boolean) invalid;
    }

    private static boolean isLiveNode(Object node) {
        if (isInvalid(node)) {
            return false;
        }

        Object world = readValue(node, "worldObj", "field_145850_b", "getWorldObj()", "func_145831_w()");
        Object x = readValue(node, "xCoord", "field_145851_c");
        Object y = readValue(node, "yCoord", "field_145848_d");
        Object z = readValue(node, "zCoord", "field_145849_e");
        if (world == null || !(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
            return true;
        }

        Object provider = invokeNoArgs(world, "getChunkProvider", "func_72863_F");
        if (provider == null) {
            return true;
        }

        Object loaded = invoke(
                provider,
                new String[]{"chunkExists", "func_73149_a"},
                ((Number) x).intValue() >> 4,
                ((Number) z).intValue() >> 4
        );
        if (loaded instanceof Boolean && !(Boolean) loaded) {
            return false;
        }

        // A chunk can be loaded while its registry entry still points at the
        // TileEntity instance from before that chunk was unloaded. Linking to
        // that stale instance loses the branch after a reload, most visibly
        // with vertical relay runs that all share one chunk.
        Method getTileEntity = findMethod(world.getClass(), "getTileEntity", 3);
        if (getTileEntity == null) {
            getTileEntity = findMethod(world.getClass(), "func_147438_o", 3);
        }
        if (getTileEntity == null) {
            return true;
        }

        try {
            getTileEntity.setAccessible(true);
            return getTileEntity.invoke(
                    world,
                    ((Number) x).intValue(),
                    ((Number) y).intValue(),
                    ((Number) z).intValue()
            ) == node;
        } catch (ReflectiveOperationException ignored) {
            // The chunk check above remains a safe fallback on unusual worlds.
            return true;
        }
    }

    private static boolean isSource(Object node) {
        Object source = invokeNoArgs(node, "isSource()");
        return source instanceof Boolean && (Boolean) source;
    }

    private static boolean isVisNode(Object tileEntity) {
        return tileEntity != null
                && findMethod(tileEntity.getClass(), "isSource", 0) != null
                && findMethod(tileEntity.getClass(), "getRange", 0) != null
                && findMethod(tileEntity.getClass(), "getParent", 0) != null
                && findMethod(tileEntity.getClass(), "getChildren", 0) != null;
    }

    private static boolean shouldValidateOnLoad(Object node, Object world) {
        long worldTime = readWorldTime(world);
        synchronized (NODES) {
            if (VALIDATED_ON_LOAD.containsKey(node)) {
                return false;
            }
            if (worldTime < 0L) {
                VALIDATED_ON_LOAD.put(node, Boolean.TRUE);
                return true;
            }

            Long due = VALIDATION_DUE.get(node);
            if (due == null) {
                VALIDATION_DUE.put(node, worldTime + LOAD_SETTLE_TICKS);
                return false;
            }
            if (worldTime < due) {
                return false;
            }

            VALIDATION_DUE.remove(node);
            VALIDATED_ON_LOAD.put(node, Boolean.TRUE);
            return true;
        }
    }

    private static boolean isValidationComplete(Object node) {
        synchronized (NODES) {
            return VALIDATED_ON_LOAD.containsKey(node);
        }
    }

    private static boolean markNodeObserved(Object node) {
        synchronized (NODES) {
            return OBSERVED_NODES.put(node, Boolean.TRUE) == null;
        }
    }

    private static void markValidationComplete(Object node) {
        synchronized (NODES) {
            VALIDATION_DUE.remove(node);
            VALIDATED_ON_LOAD.put(node, Boolean.TRUE);
        }
    }

    private static void scheduleValidationRetry(Object node, Object world) {
        long worldTime = readWorldTime(world);
        synchronized (NODES) {
            VALIDATED_ON_LOAD.remove(node);
            if (worldTime >= 0L) {
                VALIDATION_DUE.put(node, worldTime + LOAD_SETTLE_TICKS);
            }
        }
    }

    private static WeakReference<Object> getReference(Object node, String... names) {
        Object reference = readValue(node, names);
        return reference instanceof WeakReference ? (WeakReference<Object>) reference : null;
    }

    private static boolean setParent(Object node, WeakReference<Object> parent) {
        try {
            Method method = findMethod(node.getClass(), "setParent", 1);
            if (method == null) {
                return false;
            }
            method.setAccessible(true);
            method.invoke(node, parent);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void removeChildReference(WeakReference<Object> parentReference, Object node) {
        if (!isValidReference(parentReference)) {
            return;
        }

        Object children = invokeNoArgs(parentReference.get(), "getChildren()");
        if (!(children instanceof List)) {
            return;
        }

        boolean removed = false;
        for (java.util.Iterator<?> iterator = ((List<?>) children).iterator(); iterator.hasNext();) {
            Object childReference = iterator.next();
            if (childReference instanceof WeakReference && ((WeakReference<?>) childReference).get() == node) {
                iterator.remove();
                removed = true;
            }
        }
        if (removed) {
            clearNearbyNodeCache();
        }
    }

    private static void linkChild(Object parent, Object node) {
        Object children = invokeNoArgs(parent, "getChildren()");
        if (!(children instanceof List)) {
            return;
        }

        for (Object childReference : (List<?>) children) {
            if (childReference instanceof WeakReference && ((WeakReference<?>) childReference).get() == node) {
                return;
            }
        }
        ((List<Object>) children).add(new WeakReference<>(node));
        clearNearbyNodeCache();
    }

    private static void setNodeRefresh(Object node) {
        try {
            Field field = findField(node.getClass(), "nodeRefresh");
            if (field != null) {
                field.setAccessible(true);
                field.setBoolean(node, true);
            }
        } catch (IllegalAccessException ignored) {
            // Falling back to Thaumcraft's next normal connection attempt is safe.
        }
    }

    private static void notifyParentChanged(Object world, Object node) {
        invokeNoArgs(node, "parentChanged()");

        Object x = readValue(node, "xCoord", "field_145851_c");
        Object y = readValue(node, "yCoord", "field_145848_d");
        Object z = readValue(node, "zCoord", "field_145849_e");
        if (x instanceof Number && y instanceof Number && z instanceof Number) {
            invokeWithArgs(
                    world,
                    new String[]{"markBlockForUpdate", "func_147479_m"},
                    ((Number) x).intValue(),
                    ((Number) y).intValue(),
                    ((Number) z).intValue()
            );
        }
    }

    private static boolean canNodeBeSeen(Object source, Object target) {
        try {
            Class<?> handler = Class.forName("thaumcraft.api.visnet.VisNetHandler", false, source.getClass().getClassLoader());
            Method method = findMethod(handler, "canNodeBeSeen", 2);
            if (method == null) {
                return true;
            }
            method.setAccessible(true);
            Object result = method.invoke(null, source, target);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (ReflectiveOperationException ignored) {
            return true;
        }
    }

    private static void clearNearbyNodeCache() {
        try {
            Class<?> handler = Class.forName("thaumcraft.api.visnet.VisNetHandler");
            Field field = findField(handler, "nearbyNodes");
            if (field != null) {
                field.setAccessible(true);
                Object cache = field.get(null);
                if (cache instanceof Map) {
                    ((Map<?, ?>) cache).clear();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Cache clearing is an optimization; the new link remains valid without it.
        }
    }

    private static long readWorldTime(Object world) {
        Object value = readValue(world, "getTotalWorldTime()", "func_82737_E()", "func_72820_D()");
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static boolean markBrokenIfNeeded(Object node) {
        synchronized (NODES) {
            return BROKEN_LOGGED.put(node, Boolean.TRUE) == null;
        }
    }

    private static void logFixedIfPreviouslyBroken(Object world, Object node, WeakReference<Object> parentReference) {
        Object parent = isValidReference(parentReference) ? parentReference.get() : null;
        if (parent == null) {
            return;
        }

        synchronized (NODES) {
            if (BROKEN_LOGGED.remove(node) == null) {
                return;
            }
            NEXT_RECOVERY.remove(node);
        }
        logFixed(world, node, parent);
    }

    private static void logBroken(Object world, Object node) {
        logInfo(String.format(
                "[VisRelayFix] Broken parent chain for %s at %s; attempting repair.",
                nodeLabel(node), describeLocation(world, node)));
    }

    private static void logFixed(Object world, Object node, Object parent) {
        logInfo(String.format(
                "[VisRelayFix] Fixed %s at %s by linking to %s at %s.",
                nodeLabel(node), describeLocation(world, node), nodeLabel(parent), describeLocation(world, parent)));
    }

    private static void logInfo(String message) {
        if (!VisRelayFixConfig.isLoggingEnabled()) {
            return;
        }
        try {
            FMLLog.info(message);
        } catch (Throwable ignored) {
            // Forge logging is not initialized in standalone tests or very early startup.
            System.out.println(message);
        }
    }

    private static String nodeLabel(Object node) {
        String simpleName = node.getClass().getSimpleName();
        return simpleName.length() == 0 ? node.getClass().getName() : simpleName;
    }

    private static String describeLocation(Object world, Object node) {
        Object x = readValue(node, "xCoord", "field_145851_c");
        Object y = readValue(node, "yCoord", "field_145848_d");
        Object z = readValue(node, "zCoord", "field_145849_e");
        Object provider = readValue(world, "provider", "field_73011_w");
        Object dimension = provider == null ? null : readValue(provider, "dimensionId", "field_76574_a");
        long time = readWorldTime(world);
        return "dim=" + (dimension == null ? "?" : dimension)
                + " (" + valueOrUnknown(x) + ", " + valueOrUnknown(y) + ", " + valueOrUnknown(z) + ")"
                + " worldTime=" + (time < 0L ? "?" : time);
    }

    private static String valueOrUnknown(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }

    private static boolean readBoolean(Object target, String... names) {
        Object value = readValue(target, names);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Object readValue(Object target, String... names) {
        for (String name : names) {
            try {
                if (name.indexOf('(') >= 0) {
                    Object value = invokeNoArgs(target, name.substring(0, name.indexOf('(')));
                    if (value != null) {
                        return value;
                    }
                    continue;
                }

                Field field = findField(target.getClass(), name);
                if (field != null) {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) {
                        return value;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next deobfuscated/obfuscated name.
            }
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String... names) {
        for (String name : names) {
            try {
                String methodName = name.endsWith("()") ? name.substring(0, name.length() - 2) : name;
                Method method = findMethod(target.getClass(), methodName, 0);
                if (method != null) {
                    method.setAccessible(true);
                    return method.invoke(target);
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next deobfuscated/obfuscated name.
            }
        }
        return null;
    }

    private static boolean invokeBoolean(Object target, String[] names, int x, int z) {
        Object value = invoke(target, names, x, z);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Object invoke(Object target, String[] names, int x, int z) {
        for (String name : names) {
            try {
                Method method = findMethod(target.getClass(), name, 2);
                if (method != null) {
                    method.setAccessible(true);
                    return method.invoke(target, x, z);
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next deobfuscated/obfuscated name.
            }
        }
        return null;
    }

    private static Object invokeWithArgs(Object target, String[] names, Object... arguments) {
        for (String name : names) {
            try {
                Method method = findMethod(target.getClass(), name, arguments.length);
                if (method != null) {
                    method.setAccessible(true);
                    return method.invoke(target, arguments);
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next deobfuscated/obfuscated name.
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue up the hierarchy.
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == parameterCount) {
                    return method;
                }
            }
        }
        return null;
    }

    private static NodeKey getNodeKey(Object node) {
        Object x = readValue(node, "xCoord", "field_145851_c");
        Object y = readValue(node, "yCoord", "field_145848_d");
        Object z = readValue(node, "zCoord", "field_145849_e");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
            return null;
        }
        return new NodeKey(
                ((Number) x).intValue(),
                ((Number) y).intValue(),
                ((Number) z).intValue()
        );
    }

    private static final class NodeKey {
        private final int x;
        private final int y;
        private final int z;

        private NodeKey(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof NodeKey)) {
                return false;
            }
            NodeKey key = (NodeKey) other;
            return x == key.x && y == key.y && z == key.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            return 31 * result + z;
        }
    }

    private static final class RememberedParent {
        private final NodeKey parentKey;
        private long lastUsedAt;

        private RememberedParent(NodeKey parentKey, long lastUsedAt) {
            this.parentKey = parentKey;
            this.lastUsedAt = lastUsedAt;
        }
    }
}
