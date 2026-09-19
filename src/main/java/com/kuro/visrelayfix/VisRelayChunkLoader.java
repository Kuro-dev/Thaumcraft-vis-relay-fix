package com.kuro.visrelayfix;

import cpw.mods.fml.common.FMLLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class VisRelayChunkLoader {
    private static final long RECOVERY_INTERVAL_TICKS = 200L;
    private static final Map<Object, Map<NodeKey, WeakReference<Object>>> NODES = new WeakHashMap<>();
    private static final Map<Object, Long> NEXT_RECOVERY = new WeakHashMap<>();
    private static final Map<Object, Boolean> BROKEN_LOGGED = new WeakHashMap<>();

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
                if (!invokeBoolean(provider, new String[]{"chunkExists", "func_73149_a"}, chunkX, chunkZ)) {
                    invoke(provider, new String[]{"loadChunk", "func_73158_c"}, chunkX, chunkZ);
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
        if (isValidReference(originalParent)) {
            synchronized (NODES) {
                BROKEN_LOGGED.remove(node);
            }
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

        WeakReference<Object> recovered = findAndLinkNearbyNode(world, node, originalParent);
        if (isValidReference(recovered)) {
            Object parent = recovered.get();
            synchronized (NODES) {
                BROKEN_LOGGED.remove(node);
            }
            logFixed(world, node, parent);
        } else {
            logBrokenIfNeeded(world, node);
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
                if (candidate == node || !isConnected(candidate)) {
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

    private static boolean isConnected(Object node) {
        Object source = invokeNoArgs(node, "isSource()");
        if (source instanceof Boolean && (Boolean) source) {
            return true;
        }
        Object parent = invokeNoArgs(node, "getParent()");
        return isValidReference(parent);
    }

    private static boolean isValidReference(Object reference) {
        if (!(reference instanceof WeakReference)) {
            return false;
        }
        Object node = ((WeakReference<?>) reference).get();
        return node != null && !isInvalid(node);
    }

    private static boolean isInvalid(Object node) {
        Object invalid = readValue(node, "isInvalid()", "func_145837_r()");
        return invalid instanceof Boolean && (Boolean) invalid;
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
        Object value = readValue(world, "getTotalWorldTime()", "func_72820_D()");
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static void logBrokenIfNeeded(Object world, Object node) {
        synchronized (NODES) {
            if (BROKEN_LOGGED.put(node, Boolean.TRUE) != null) {
                return;
            }
        }
        logInfo(String.format(
                "[VisRelayFix] Disconnected %s at %s; retrying recovery every %d ticks.",
                nodeLabel(node), describeLocation(world, node), RECOVERY_INTERVAL_TICKS));
    }

    private static void logFixed(Object world, Object node, Object parent) {
        logInfo(String.format(
                "[VisRelayFix] Fixed %s at %s by linking to %s at %s.",
                nodeLabel(node), describeLocation(world, node), nodeLabel(parent), describeLocation(world, parent)));
    }

    private static void logInfo(String message) {
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
                    return invokeNoArgs(target, name.substring(0, name.indexOf('(')));
                }

                Field field = findField(target.getClass(), name);
                if (field != null) {
                    field.setAccessible(true);
                    return field.get(target);
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
}
