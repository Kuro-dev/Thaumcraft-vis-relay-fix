# Thaumcraft Vis Relay Fix

First iteration for Minecraft 1.7.10 and Thaumcraft 4.2.3.5.

This is a pure Forge coremod, not a networked `@Mod`. It can be installed on the dedicated server without requiring clients to install it. For singleplayer, place it in the client instance's `mods` directory because the integrated server runs inside that process.

The coremod patches Thaumcraft's `TileVisNode` lifecycle. After a newly loaded relay grid has had 40 ticks to register, every non-source relay performs one complete parent-chain check to confirm it reaches a live source node in a loaded chunk. A relay whose chain is broken reports the problem in the server log, restores its remembered parent branch from the source outward when possible, and then retries only while that chain remains broken. Recovery verifies that each relay is the world's current tile entity, so stale objects from a just-unloaded chunk cannot steal links from vertical or horizontal branches. Live-node references are weak and stale coordinate bookkeeping is periodically pruned, while recently unloaded routes remain available for recovery. Established relay layouts are not rerouted.

When a relay has a live immediate parent but that parent chain is broken farther upstream, the fix repairs the first upstream relay with a missing parent rather than rerouting the downstream relay. This preserves stable branch layouts and prevents nearby sibling relays from trying to link to one another. Relays with no live parent still use Thaumcraft's normal connection attempt immediately.

This is an on-demand load, not a Forge chunk-loading ticket. The chunks are therefore still eligible for normal unloading after the connection attempt finishes.

When a node remains disconnected, the server log reports its type, dimension, coordinates, and world time. A successful recovery reports the node and the parent it linked to.

Logging is enabled by default. After the game or server starts once, set `B:enableLogging=false` in `config/ThaumcraftVisRelayFix.cfg` to silence these messages.

## Build

```text
mvn clean package
```

The server-ready jar is created at:

```text
target/ThaumcraftVisRelayFix-1.1.8.jar
```

Place that jar in the server's `mods` directory alongside Thaumcraft 4.2.3.5. For singleplayer, place it in the client `mods` directory instead.
