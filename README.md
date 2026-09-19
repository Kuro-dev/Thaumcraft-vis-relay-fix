# Thaumcraft Vis Relay Fix

First iteration for Minecraft 1.7.10 and Thaumcraft 4.2.3.5.

This is a pure Forge coremod, not a networked `@Mod`. It can be installed on the dedicated server without requiring clients to install it. For singleplayer, place it in the client instance's `mods` directory because the integrated server runs inside that process.

The coremod patches Thaumcraft's `TileVisNode` connection attempt. Immediately before `VisNetHandler.addNode(...)` runs, it synchronously loads the relay's 3x3 chunk area when any of those chunks are not currently loaded. If Thaumcraft still returns no parent, the fix keeps a weak in-memory registry of nodes seen during connection attempts and tries at most once every 200 ticks to attach the node to a nearby already-connected node, while preserving range, attunement, and line-of-sight checks.

This is an on-demand load, not a Forge chunk-loading ticket. The chunks are therefore still eligible for normal unloading after the connection attempt finishes.

When a node remains disconnected, the server log reports its type, dimension, coordinates, and world time. A successful recovery reports the node and the parent it linked to.

## Build

```text
mvn clean package
```

The server-ready jar is created at:

```text
target/ThaumcraftVisRelayFix-1.1.1.jar
```

Place that jar in the server's `mods` directory alongside Thaumcraft 4.2.3.5. For singleplayer, place it in the client `mods` directory instead.
