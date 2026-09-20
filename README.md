# Thaumcraft Vis Relay Fix

First iteration for Minecraft 1.7.10 and Thaumcraft 4.2.3.5.

This is a pure Forge coremod, not a networked `@Mod`. It can be installed on the dedicated server without requiring clients to install it. For singleplayer, place it in the client instance's `mods` directory because the integrated server runs inside that process.

The coremod patches Thaumcraft's `TileVisNode` lifecycle. When a relay is loaded, it clears its relay links and waits 40 ticks for the local grid to settle. Recovery then loads neighbouring chunks, starts from an energized source, and grows a fresh graph through every reachable compatible relay. The exact route may differ after a reload, but every completed route is verified to end at a live source node in a loaded chunk. Recovery verifies that each relay is the world's current tile entity, so stale objects from a just-unloaded chunk cannot steal links from vertical or horizontal branches. Live-node references are weak and stale coordinate bookkeeping is periodically pruned.

This is an on-demand load, not a Forge chunk-loading ticket. The chunks are therefore still eligible for normal unloading after the connection attempt finishes.

The server log reports one compact summary for a rebuild: the number of relays reset and energized links rebuilt. If no energized route is currently loaded, it emits a rate-limited waiting message instead of one line per relay.

Logging is enabled by default. After the game or server starts once, set `B:enableLogging=false` in `config/ThaumcraftVisRelayFix.cfg` to silence these messages.

## Build

```text
mvn clean package
```

The server-ready jar is created at:

```text
target/ThaumcraftVisRelayFix-1.1.18.jar
```

Place that jar in the server's `mods` directory alongside Thaumcraft 4.2.3.5. For singleplayer, place it in the client `mods` directory instead.
