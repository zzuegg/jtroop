# Net — Allocation-Free Network Module Design

## Overview

A high-performance, zero-dependency Java 26 network module targeting 0 B/op on hot paths through EA-friendly design. Supports TCP and UDP, SOAP-inspired service contracts, typed connection groups with capability negotiation, layered fused pipelines, and a test forwarder for simulating network conditions.

## Core Concepts

### Service Contract (shared interface)

Defines the wire contract between client and server. Shared as a dependency. Return type determines message pattern:

- `void` → fire-and-forget
- Non-void → request/response (blocking)
- `CompletableFuture<T>` → request/response (async)
- `void onX(Consumer<T> cb)` → server-to-client push

`@Datagram` on a method signals it should use the UDP transport within its connection group.

```java
interface MovementService {
    void teleport(TeleportCommand cmd);           // TCP, fire & forget
    @Datagram void position(PositionUpdate pos);  // UDP, fire & forget
    @Datagram void onWorldState(Consumer<WorldState> cb); // UDP, server push
}
```

All message types are records.

### Connection Type (typed handshake)

A record that identifies a connection group and carries handshake/capability negotiation data. Contains a nested `Accepted` record for the server's response.

```java
enum GameCapability { CHAT, MOVEMENT, INVENTORY, VOICE }

record GameConnection(
    int version,
    int minVersion,
    EnumSet<GameCapability> capabilities,
    int maxMessageSize
) {
    record Accepted(
        int negotiatedVersion,
        EnumSet<GameCapability> activeCapabilities,
        int tickRate
    ) {}
}
```

Used as a type-safe connection group identifier (replaces magic strings). Handshake is cold path — rich types are fine.

### Connection Group

A named group of transports (TCP, UDP, or both) identified by a connection type. Many services can share one group. Each transport in the group has its own layer stack and fused pipeline.

### Fused Pipeline

At `build()` time, layer configuration is composed into a single generated hidden class via `java.lang.classfile` + `MethodHandles.Lookup.defineHiddenClass()`. Each connection (transport endpoint) gets its own fused pipeline. The JIT sees one monomorphic `invokevirtual` and inlines the entire chain. All intermediate buffer objects are EA-eliminated.

### Layers

Composable transformations applied per-connection. Built-in layers:

| Layer | Purpose | Typical use |
|---|---|---|
| `Layers.framing()` | Length-prefix framing for TCP | TCP only (UDP self-delimits) |
| `Layers.encryption(key)` | AES-GCM via JDK crypto | Both |
| `Layers.compression()` | JDK Deflater/Inflater | TCP typically |
| `Layers.sequencing()` | Sequence numbers, stale detection | UDP |
| `Layers.ack()` | Acknowledge + retransmit | UDP (reliable UDP) |
| `Layers.duplicateFilter()` | Drop duplicate packets | UDP |

Custom layers implement the `Layer` interface (build-time only — fused at runtime).

## API

### Server

```java
var server = Server.builder()
    .listen(GameConnection.class, Transport.tcp(8080),
        Layers.framing(), Layers.encryption(gameKey), Layers.compression())
    .listen(GameConnection.class, Transport.udp(8081),
        Layers.encryption(gameKey), Layers.sequencing())
    .listen(AuthConnection.class, Transport.tcp(9090),
        Layers.framing(), Layers.encryption(adminKey))
    .onHandshake(GameConnection.class, req -> {
        var common = EnumSet.copyOf(req.capabilities());
        common.retainAll(SUPPORTED);
        if (req.version() < MIN_VERSION || common.isEmpty())
            return Handshake.reject("incompatible");
        return new GameConnection.Accepted(
            Math.min(req.version(), CURRENT_VERSION), common, 20);
    })
    .addService(ChatHandler.class, GameConnection.class)
    .addService(MovementHandler.class, GameConnection.class)
    .addService(AdminHandler.class, AuthConnection.class)
    .build();

server.start();
```

### Client

```java
var client = Client.builder()
    .connect(new GameConnection(2, 1, EnumSet.allOf(GameCapability.class), 65536),
        Transport.tcp("game.com", 8080),
        Layers.framing(), Layers.encryption(gameKey), Layers.compression())
    .connect(new GameConnection(2, 1, EnumSet.allOf(GameCapability.class), 65536),
        Transport.udp("game.com", 8081),
        Layers.encryption(gameKey), Layers.sequencing())
    .connect(new AuthConnection(1, "eu-west", true),
        Transport.tcp("auth.com", 9090),
        Layers.framing(), Layers.encryption(authKey))
    .addService(ChatService.class, GameConnection.class)
    .addService(MovementService.class, GameConnection.class)
    .addService(AuthService.class, AuthConnection.class)
    .build();

ChatService chat = client.service(ChatService.class);
MovementService move = client.service(MovementService.class);
AuthService auth = client.service(AuthService.class);

chat.send(new ChatMessage("hello", 1));           // → TCP game:8080
chat.typing(new TypingIndicator(true));            // → UDP game:8081
move.position(new PositionUpdate(1f, 2f, 3f, 0)); // → UDP game:8081
AuthResponse r = auth.login(new AuthRequest("t")); // → TCP auth:9090
```

### Server Handlers

```java
@Handles(ChatService.class)
class ChatHandler {
    @OnMessage void send(ChatMessage msg, ConnectionId sender, Broadcast broadcast) {
        broadcast.send(msg);
    }

    @OnMessage ChatHistory getHistory(HistoryRequest req, ConnectionId sender) {
        return loadHistory(req.room());
    }

    @OnConnect void join(ConnectionId id) { }
    @OnDisconnect void leave(ConnectionId id) { }
}
```

Injectable parameters: `ConnectionId`, `Broadcast`, `Unicast`, `SessionStore`, `Res<T>`.

### Forwarder

```java
var forwarder = Forwarder.builder()
    .forward(Transport.tcp(19080), "localhost", 8080)
        .latency(Duration.ofMillis(20), Duration.ofMillis(80))
        .packetLoss(0.02)
    .forward(Transport.udp(19081), "localhost", 8081)
        .latency(Duration.ofMillis(5), Duration.ofMillis(30))
        .packetLoss(0.10)
        .reorder(0.05)
    .forward(Transport.tcp(19090), "auth-host", 9090)
    .build();

forwarder.start();
```

## Wire Format

### TCP Frame
```
[4 bytes: frame length][2 bytes: message type id][payload bytes...]
```

### UDP Datagram
```
[2 bytes: message type id][optional: sequence number, ack fields per layers][payload bytes...]
```

### Handshake (connection establishment)
```
[4 bytes: magic][2 bytes: connection type id][handshake record payload]
→ server responds with Accepted record or rejection
```

## Internals

### EventLoop
- Single-threaded selector loop per EventLoop
- No locks on hot path — each loop owns its connections, session store slice, and buffers
- EventLoopGroup distributes connections round-robin
- Scheduled tasks use flat `long[] deadlineNs` + `int[] taskTypes` arrays (no Runnable allocation)

### Session Store (SoA)
- `long[] connectionIds` — packed index + generation
- `int[] states` — connection lifecycle state
- `long[] lastActivityNs` — timestamp
- Per-EventLoop ownership, no cross-thread access on hot path

### Generated Hidden Classes
- **Fused pipelines**: one per unique layer stack, all layers inlined into one method
- **Codecs**: one per record type, reads/writes fields directly to/from buffers
- **Service proxies**: client-side, implements the service interface, routes to correct connection
- **Dispatch tables**: server-side, message type id → handler method handle

All generated via `java.lang.classfile` API + `MethodHandles.Lookup.defineHiddenClass()`.

### Message Type Resolution
At `build()` time:
1. Discover all service interfaces and their methods
2. Assign each message record type a unique 16-bit ID
3. Build routing table: message type → connection (client) or message type → handler (server)
4. For `@Datagram` methods: route to UDP transport in the group
5. Default methods: route to TCP transport in the group
6. Build-time error if a group lacks the required transport

## Module Structure

```
net/
├── core/           EventLoop, EventLoopGroup, ReadBuffer, WriteBuffer, CommandBuffer
├── transport/      Transport, TcpTransport, UdpTransport, ChannelOps
├── pipeline/       Layer, FusedPipeline, PipelineCompiler
│   └── layers/     Framing, Compression, Encryption, Sequencing, Ack, DuplicateFilter
├── codec/          CodecGenerator, SoACodec (generated per record type)
├── service/        ServiceRegistry, DispatchGenerator, annotations, injectable types
├── session/        ConnectionId, SessionStore (SoA arrays)
├── server/         Server, Server.Builder
├── client/         Client, Client.Builder, ProxyGenerator
├── testing/        Forwarder, Forwarder.Builder, impairment profiles
└── generate/       ClassEmitter (java.lang.classfile helpers)
```

## Dependencies

None. `java.base` + `java.lang.classfile` (JDK 26 preview) only.

## JDK Requirements

- JDK 26+ with `--enable-preview`
- `java.lang.classfile` API for hidden class generation
- `MethodHandles.Lookup.defineHiddenClass()` for monomorphic dispatch
- `javax.crypto` for encryption layers
- `java.util.zip` for compression layers

## Performance Targets

- Hot path (per-message): 0 B/op
- Pipeline dispatch: 1 monomorphic invokevirtual (vs Netty's N invokeinterface)
- Connection state: ~40 B/conn in SoA arrays (vs Netty's ~500 B/conn)
- Cold path (handshake, build): allocation is fine
