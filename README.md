# jtroop

**J**ava **T**yped **R**ecord-**O**riented **O**ptimized **P**rotocol

A zero-dependency networking module for JDK 26+ that minimizes hot-path allocation through EA-friendly design.

## Benchmark Results

Game scenario: TCP server + client, length-prefix framing, fire-and-forget sends. Position updates (16B payload), chat messages (~70B payload), mixed traffic (80% position + 20% chat, 10 messages per batch).

All benchmarks use fire-and-forget (non-blocking) sends for a fair comparison. JDK 26, JMH with `-prof gc`, single fork, 5 measurement iterations.

### Throughput (ops/ms) — higher is better

| Benchmark | jtroop | Netty 4.2 | SpiderMonkey 3.7 |
|-----------|--------|-----------|------------------|
| positionUpdate | 28,572 ± 494 | 2,521 ± 12,579 | 141 ± 7 |
| chatMessage | 19,955 ± 59 | 1,145 ± 6,750 | 144 ± 8 |
| mixedTraffic (10 msg) | 2,533 ± 29 | 217 ± 1,232 | 54 ± 2 |

### Allocation (B/op) — lower is better

| Benchmark | jtroop | Netty 4.2 | SpiderMonkey 3.7 |
|-----------|--------|-----------|------------------|
| positionUpdate | 5 ± 0 | 20,595 ± 175,663 | 576 ± 0 |
| chatMessage | 111 ± 0 | 70,159 ± 601,257 | 1,024 ± 0 |
| mixedTraffic (10 msg) | 521 ± 0 | 246,816 ± 2,105,978 | 6,101 ± 46 |

### Notes

- Netty's high error margins indicate GC-induced variance. Without blocking `.sync()` per write, Netty's fire-and-forget path generates significant GC pressure, causing some iterations to stall during collection while others run unimpeded.
- SpiderMonkey results are stable but throughput is limited by its thread-per-kernel model and reflection-based serialization.
- jtroop's error margins are consistently below 3%, indicating stable performance without GC interference.
- The mixedTraffic benchmark measures batches of 10 messages per operation (8 position + 2 chat). Per-message throughput is consistent with the single-message benchmarks.

## Design Approach

jtroop minimizes allocation on the hot path through these techniques:

- **Pre-allocated buffer reuse** — encode and wire buffers are allocated once per connection and reused across messages.
- **stageWrite** — encoded bytes are copied into pre-allocated per-slot write buffers in the EventLoop. No lambda, Runnable, or queue node allocation per send.
- **Direct ByteBuffer encoding** — codec reads record fields via MethodHandle and writes directly to ByteBuffer with primitive casts, avoiding boxing.
- **Bytecode-generated codecs** — for public record types, `java.lang.classfile` generates hidden classes with direct field access.
- **Fused pipeline generation** — `java.lang.classfile` generates a hidden class per unique layer stack, calling each layer via `invokevirtual` on the concrete type.
- **SoA session storage** — connection state in parallel primitive arrays. `ConnectionId` is a packed long (index + generation).

## Quick Start

### Server

```java
var server = Server.builder()
    .listen(GameConn.class, Transport.tcp(8080),
        Layers.framing(), Layers.encryption(key), Layers.compression())
    .listen(GameConn.class, Transport.udp(8081),
        Layers.encryption(key), Layers.sequencing())
    .addService(ChatHandler.class, GameConn.class)
    .addService(MovementHandler.class, GameConn.class)
    .eventLoops(4)
    .build();
server.start();
```

### Client

```java
var client = Client.builder()
    .connect(GameConn.class, Transport.tcp("game.example.com", 8080),
        Layers.framing(), Layers.encryption(key), Layers.compression())
    .connect(GameConn.class, Transport.udp("game.example.com", 8081),
        Layers.encryption(key), Layers.sequencing())
    .addService(ChatService.class, GameConn.class)
    .addService(MovementService.class, GameConn.class)
    .build();
client.start();

// Typed proxy
ChatService chat = client.service(ChatService.class);
chat.send(new ChatMessage("hello", 1));

// Request/response
ChatHistory h = chat.getHistory(new HistoryRequest(1));

// @Datagram routes to UDP
MovementService move = client.service(MovementService.class);
move.position(new PositionUpdate(x, y, z, yaw));
```

### Service Contract

```java
interface ChatService {
    void send(ChatMessage msg);                    // fire-and-forget, TCP
    ChatHistory getHistory(HistoryRequest req);    // request/response, TCP
    @Datagram void typing(TypingIndicator t);      // fire-and-forget, UDP
}
```

### Handler

```java
@Handles(ChatService.class)
class ChatHandler {
    @OnMessage
    void send(ChatMessage msg, ConnectionId sender, Broadcast broadcast) {
        broadcast.send(new ServerPush("echo:" + msg.text()));
    }

    @OnMessage
    ChatHistory getHistory(HistoryRequest req, ConnectionId sender) {
        return new ChatHistory(loadHistory(req.room()));
    }

    @OnConnect void join(ConnectionId id) { }
    @OnDisconnect void leave(ConnectionId id) { }
}
```

### Handshake / Capability Negotiation

```java
record GameConn(int version, int capabilityMask) {
    static final int CHAT = 1, MOVE = 2, VOICE = 4;
    record Accepted(int negotiatedVersion, int activeMask) {}
}

Server.builder()
    .onHandshake(GameConn.class, req -> {
        int common = req.capabilityMask() & SUPPORTED;
        return common != 0
            ? new GameConn.Accepted(req.version(), common)
            : null;  // reject
    })

Client.builder()
    .connect(new GameConn(2, GameConn.CHAT | GameConn.MOVE),
        Transport.tcp("game.example.com", 8080), Layers.framing())
```

### Virtual Thread Dispatch

```java
Server.builder()
    .executor(Executors.newVirtualThreadPerTaskExecutor())
    .listen(...)
```

### Test Forwarder

```java
var forwarder = Forwarder.builder()
    .forward(Transport.tcp(19080), "localhost", 8080)
        .latency(Duration.ofMillis(20), Duration.ofMillis(80))
        .packetLoss(0.02)
    .forward(Transport.udp(19081), "localhost", 8081)
        .packetLoss(0.10)
        .reorder(0.05)
    .build();
forwarder.start();
```

## Features

| Feature | Status |
|---------|--------|
| TCP + UDP transport | Done |
| Composable layer pipeline (framing, encryption, compression) | Done |
| UDP reliability layers (sequencing, duplicate filter, ack/retransmit) | Done |
| Service contracts (shared interfaces) | Done |
| Annotation-driven handlers (@OnMessage, @OnConnect, @OnDisconnect) | Done |
| @Datagram routing (TCP default, UDP opt-in) | Done |
| Typed connection groups (multiple servers, mixed transports) | Done |
| Handshake / capability negotiation | Done |
| Request/response + fire-and-forget + server push | Done |
| Typed service proxies (client.service(Interface.class)) | Done |
| Broadcast / Unicast injectables | Done |
| Configurable executor (virtual thread support) | Done |
| Test forwarder (latency, packet loss, reorder) — TCP + UDP | Done |
| EventLoopGroup (round-robin connection distribution) | Done |
| Protocol upgrade (server.switchPipeline()) | Done |
| Bytecode-generated codecs (java.lang.classfile + hidden classes) | Done |
| Fused pipeline generation (hidden classes) | Done |
| MPSC ring buffer (lock-free, zero-alloc) | Done |
| JMH benchmark suite (vs Netty 4.2, vs SpiderMonkey 3.7) | Done |

## Architecture

```
jtroop/
├── core/           EventLoop, EventLoopGroup, ReadBuffer, WriteBuffer, MpscRingBuffer
├── transport/      Transport (sealed: TCP, UDP), TcpTransport, UdpTransport
├── pipeline/       Layer, Pipeline, Layers factory
│   └── layers/     Framing, Compression, Encryption, Sequencing, DuplicateFilter, Ack
├── codec/          CodecRegistry (record <-> ByteBuffer, deterministic type IDs)
├── service/        @OnMessage, @Handles, @Datagram, ServiceRegistry, Broadcast, Unicast
├── session/        ConnectionId (packed long), SessionStore (SoA arrays)
├── server/         Server, Server.Builder
├── client/         Client, Client.Builder, service proxy generation
├── generate/       CodecClassGenerator, FusedPipelineGenerator (java.lang.classfile)
└── testing/        Forwarder (TCP + UDP proxy with impairment profiles)
```

## Optimization History

| Pass | positionUpdate B/op | positionUpdate ops/ms | Change |
|------|--------------------|-----------------------|--------|
| Naive | 197,768 | 150 | Baseline |
| Buffer reuse | 150 | 5,799 | Pre-allocate and reuse encode/wire buffers |
| stageWrite | 103 | 7,464 | Eliminate lambda + queue node via pre-allocated write slots |
| No boxing | 38 | 8,265 | Direct ByteBuffer writes, no primitive boxing |
| Generated codecs | 5 | 28,572 | Bytecode codecs via java.lang.classfile + 1ms select |

See [docs/performance-journey.md](docs/performance-journey.md) for details.

## Requirements

- JDK 26+ with `--enable-preview`
- Zero external dependencies (java.base only)
- Gradle 9+ with Kotlin DSL

## Running Benchmarks

```bash
gradle :benchmark:net:jmh          # jtroop
gradle :benchmark:netty:jmh        # Netty 4.2
gradle :benchmark:spidermonkey:jmh # SpiderMonkey 3.7
gradle :core:test                  # all tests
```

## License

TBD
