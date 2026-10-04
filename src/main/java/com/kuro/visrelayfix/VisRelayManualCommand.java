package com.kuro.visrelayfix;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class VisRelayManualCommand {
    private static final String COMMAND_NAME = "visrelayfix";
    private static final List<ManualRebuildJob> JOBS = new ArrayList<>();

    private VisRelayManualCommand() {
    }

    /**
     * This project intentionally builds as a small coremod against Forge's
     * universal jar, not a full deobfuscated ForgeGradle workspace. In that
     * compile setup the Forge event classes are visible, but Minecraft's
     * command and chat types are exposed through obfuscated descriptors or are
     * absent from the javac class path. Keeping the command bridge reflective
     * lets the released jar still bind to the real runtime classes on a 1.7.10
     * server without adding a hard client/mod dependency or changing the build.
     */
    static void register(FMLServerStartingEvent event) {
        try {
            Method register = findRegisterCommandMethod(event.getClass());
            if (register == null) {
                log("[VisRelayFix] Could not find Forge command registration hook.");
                return;
            }

            Class<?> commandType = register.getParameterTypes()[0];
            Object command = Proxy.newProxyInstance(
                    commandType.getClassLoader(),
                    new Class<?>[]{commandType},
                    new CommandHandler(event)
            );
            register.invoke(event, command);
        } catch (Throwable throwable) {
            log("[VisRelayFix] Failed to register /" + COMMAND_NAME + ": " + throwable);
        }
    }

    static void tick() {
        synchronized (JOBS) {
            for (java.util.Iterator<ManualRebuildJob> iterator = JOBS.iterator(); iterator.hasNext();) {
                if (iterator.next().tick()) {
                    iterator.remove();
                }
            }
        }
    }

    private static void start(Object server, Object sender) {
        synchronized (JOBS) {
            for (ManualRebuildJob job : JOBS) {
                if (job.isSameSender(sender)) {
                    send(sender, "Vis relay rebuild is already running.");
                    return;
                }
            }
            JOBS.add(new ManualRebuildJob(server, sender));
        }
        send(sender, "Locating vis sources...");
    }

    private static Method findRegisterCommandMethod(Class<?> eventType) {
        for (Method method : eventType.getMethods()) {
            if ("registerServerCommand".equals(method.getName()) && method.getParameterTypes().length == 1) {
                return method;
            }
        }
        return null;
    }

    private static Object getServer(FMLServerStartingEvent event) {
        try {
            Method method = event.getClass().getMethod("getServer");
            return method.invoke(event);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean canUse(Object sender) {
        Object value = invoke(sender, "canCommandSenderUseCommand", "func_70003_b", 2, COMMAND_NAME);
        return !(value instanceof Boolean) || (Boolean) value;
    }

    private static void send(Object sender, String message) {
        if (sender == null) {
            return;
        }

        try {
            ClassLoader loader = sender.getClass().getClassLoader();
            Class<?> componentType = Class.forName("net.minecraft.util.ChatComponentText", false, loader);
            Constructor<?> constructor = componentType.getConstructor(String.class);
            Object component = constructor.newInstance("[VisRelayFix] " + message);
            for (Method method : sender.getClass().getMethods()) {
                if (method.getReturnType() == Void.TYPE
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isAssignableFrom(componentType)) {
                    method.invoke(sender, component);
                    return;
                }
            }
        } catch (Throwable ignored) {
            log("[VisRelayFix] " + message);
        }
    }

    private static List<Object> getPlayers(Object server) {
        Object configurationManager = invoke(server, "getConfigurationManager", "func_71203_ab");
        Object players = readValue(configurationManager, "playerEntityList", "field_72404_b");
        if (players instanceof List) {
            return new ArrayList<Object>((List<?>) players);
        }
        return Collections.emptyList();
    }

    private static int getViewDistance(Object server) {
        Object configurationManager = invoke(server, "getConfigurationManager", "func_71203_ab");
        Object viewDistance = invoke(configurationManager, "getViewDistance", "func_72395_o");
        if (!(viewDistance instanceof Number)) {
            viewDistance = readValue(configurationManager, "viewDistance", "field_152611_a");
        }
        if (!(viewDistance instanceof Number)) {
            return 10;
        }
        int value = ((Number) viewDistance).intValue();
        return Math.max(2, Math.min(16, value));
    }

    private static Object readValue(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
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

    private static Object invoke(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Method method = findMethod(target.getClass(), name, 0);
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

    private static Object invoke(Object target, String firstName, String secondName, Object... arguments) {
        if (target == null) {
            return null;
        }
        String[] names = new String[]{firstName, secondName};
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

    private static void log(String message) {
        try {
            FMLLog.info(message);
        } catch (Throwable ignored) {
            System.out.println(message);
        }
    }

    private static final class CommandHandler implements InvocationHandler {
        private final FMLServerStartingEvent event;

        private CommandHandler(FMLServerStartingEvent event) {
            this.event = event;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            Class<?> returnType = method.getReturnType();
            int argumentCount = arguments == null ? 0 : arguments.length;

            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, arguments);
            }
            if (returnType == String.class && argumentCount == 0) {
                return COMMAND_NAME;
            }
            if (returnType == String.class) {
                return "/" + COMMAND_NAME;
            }
            if (List.class.isAssignableFrom(returnType)) {
                return Collections.emptyList();
            }
            if (returnType == Boolean.TYPE) {
                if (argumentCount == 1) {
                    return canUse(arguments[0]);
                }
                return false;
            }
            if (returnType == Integer.TYPE) {
                return 0;
            }
            if (returnType == Void.TYPE && argumentCount >= 1) {
                Object sender = arguments[0];
                if (!canUse(sender)) {
                    send(sender, "You do not have permission to run this command.");
                    return null;
                }
                start(getServer(event), sender);
                return null;
            }
            return defaultValue(returnType);
        }

        private Object handleObjectMethod(Object proxy, Method method, Object[] arguments) {
            if ("toString".equals(method.getName())) {
                return "/" + COMMAND_NAME;
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return arguments != null && arguments.length == 1 && proxy == arguments[0];
            }
            return null;
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == Boolean.TYPE) {
                return false;
            }
            if (type == Integer.TYPE || type == Short.TYPE || type == Byte.TYPE || type == Character.TYPE) {
                return 0;
            }
            if (type == Long.TYPE) {
                return 0L;
            }
            if (type == Float.TYPE) {
                return 0F;
            }
            if (type == Double.TYPE) {
                return 0D;
            }
            return null;
        }
    }

    private static final class ManualRebuildJob {
        private static final int CHUNKS_PER_TICK = 8;
        private static final int PROGRESS_INTERVAL_TICKS = 30;

        private final Object server;
        private final Object sender;
        private final int viewDistance;
        private final List<ChunkTarget> chunks = new ArrayList<>();
        private final Map<Object, List<ChunkTarget>> chunksByWorld = new IdentityHashMap<>();
        private int scannedChunks;
        private int tickCount;
        private int lastProgress = -1;
        private boolean collected;
        private boolean rebuilding;

        private ManualRebuildJob(Object server, Object sender) {
            this.server = server;
            this.sender = sender;
            this.viewDistance = getViewDistance(server);
        }

        private boolean isSameSender(Object other) {
            return sender == other;
        }

        private boolean tick() {
            tickCount++;
            if (!collected) {
                collectChunks();
                collected = true;
                if (chunks.isEmpty()) {
                    send(sender, "No loaded player chunks found.");
                    return true;
                }
            }

            if (!rebuilding) {
                scanSomeChunks();
                if (scannedChunks < chunks.size()) {
                    maybeSendProgress("Locating vis sources", locatePercent());
                    return false;
                }
                rebuilding = true;
                VisRelayChunkLoader.ManualRebuildStats located = VisRelayChunkLoader.countRegisteredNodes(chunksByWorld);
                send(sender, "Source found, building graph... " + located.sources + " source(s), "
                        + located.relays + " relay(s) located.");
                return false;
            }

            VisRelayChunkLoader.ManualRebuildStats stats = VisRelayChunkLoader.revalidateRegisteredRelays(chunksByWorld);
            send(sender, "100% - fixed " + stats.resetRelays + " potentially broken relay(s). "
                    + stats.rebuiltLinks + " energized link(s) rebuilt; "
                    + stats.fixedBrokenRelays + " relay(s) were disconnected before rebuilding.");
            return true;
        }

        private void collectChunks() {
            Map<String, ChunkTarget> uniqueChunks = new LinkedHashMap<>();
            for (Object player : getPlayers(server)) {
                Object world = readValue(player, "worldObj", "field_70170_p");
                Object x = readValue(player, "posX", "field_70165_t");
                Object z = readValue(player, "posZ", "field_70161_v");
                if (world == null || !(x instanceof Number) || !(z instanceof Number)) {
                    continue;
                }

                int centerX = floor(((Number) x).doubleValue()) >> 4;
                int centerZ = floor(((Number) z).doubleValue()) >> 4;
                for (int offsetX = -viewDistance; offsetX <= viewDistance; offsetX++) {
                    for (int offsetZ = -viewDistance; offsetZ <= viewDistance; offsetZ++) {
                        int chunkX = centerX + offsetX;
                        int chunkZ = centerZ + offsetZ;
                        ChunkTarget target = new ChunkTarget(world, chunkX, chunkZ);
                        uniqueChunks.put(System.identityHashCode(world) + ":" + chunkX + ":" + chunkZ, target);
                    }
                }
            }

            chunks.addAll(uniqueChunks.values());
            for (ChunkTarget target : chunks) {
                List<ChunkTarget> worldChunks = chunksByWorld.get(target.world);
                if (worldChunks == null) {
                    worldChunks = new ArrayList<>();
                    chunksByWorld.put(target.world, worldChunks);
                }
                worldChunks.add(target);
            }
        }

        private void scanSomeChunks() {
            int limit = Math.min(chunks.size(), scannedChunks + CHUNKS_PER_TICK);
            while (scannedChunks < limit) {
                ChunkTarget target = chunks.get(scannedChunks++);
                VisRelayChunkLoader.registerNodesInLoadedChunk(target.world, target.x, target.z);
            }
        }

        private int locatePercent() {
            if (chunks.isEmpty()) {
                return 50;
            }
            return Math.min(50, Math.max(10, scannedChunks * 50 / chunks.size()));
        }

        private void maybeSendProgress(String label, int percent) {
            if (percent == lastProgress || tickCount % PROGRESS_INTERVAL_TICKS != 0) {
                return;
            }
            lastProgress = percent;
            send(sender, percent + "% - " + label + "...");
        }

        private int floor(double value) {
            int integer = (int) value;
            return value < integer ? integer - 1 : integer;
        }
    }

    static final class ChunkTarget {
        final Object world;
        final int x;
        final int z;

        ChunkTarget(Object world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }
    }
}
