# Thaumcraft Vis Relay Fix

First iteration for Minecraft 1.7.10 and Thaumcraft 4.2.3.5.

This is a pure Forge coremod, not a networked `@Mod`. It can be installed on the dedicated server without requiring clients to install it. For singleplayer, place it in the client instance's `mods` directory because the integrated server runs inside that process.

The coremod patches Thaumcraft's `TileVisNode` lifecycle. When a relay is loaded, it clears its relay links and waits 40 ticks for the local grid to settle. Recovery then loads neighbouring chunks, starts from an energized source, and grows a fresh graph through every reachable compatible relay. The exact route may differ after a reload, but every completed route is verified to end at a live source node in a loaded chunk. Recovery verifies that each relay is the world's current tile entity, so stale objects from a just-unloaded chunk cannot steal links from vertical or horizontal branches. Live-node references are weak and stale coordinate bookkeeping is periodically pruned.

This is an on-demand load, not a Forge chunk-loading ticket. The chunks are therefore still eligible for normal unloading after the connection attempt finishes.

The server log reports one compact summary for a rebuild: the number of relays reset and energized links rebuilt. If no energized route is currently loaded, it emits a rate-limited waiting message instead of one line per relay.

Logging is enabled by default. After the game or server starts once, set `B:enableLogging=false` in `config/ThaumcraftVisRelayFix.cfg` to silence these messages.

## Command

Server operators can manually rebuild loaded vis relay graphs with:

```text
/visrelayfix
```

The command scans the currently loaded chunks around all online players, registers any Thaumcraft vis nodes it finds, clears relay links in that loaded area, and rebuilds the energized graph from live source nodes. It sends private chat feedback to the command sender while it works, including source-location/build phases, periodic percentage updates, and a final count of potentially broken relays fixed.

The command implementation uses reflection on the Minecraft command and chat classes on purpose. This project is built as a compact Forge coremod against the 1.7.10 Forge universal jar, where Forge lifecycle classes are available to javac but many Minecraft command/chat types are obfuscated or missing from the compile class path. Reflection keeps the jar server-side and avoids requiring a ForgeGradle deobfuscated development setup just to expose one administrative command.

## Build

```text
mvn clean package
```

The server-ready jar is created at:

```text
target/ThaumcraftVisRelayFix-1.1.21.jar
```

Place that jar in the server's `mods` directory alongside Thaumcraft 4.2.3.5. For singleplayer, place it in the client `mods` directory instead.
