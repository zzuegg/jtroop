# jtroop

**J**ava **T**yped **R**ecord-**O**riented **O**ptimized **P**rotocol

A zero-dependency networking module for JDK 26+ that minimizes hot-path allocation through EA-friendly design.

## Benchmark Results

Game scenario: TCP server + client, length-prefix framing, fire-and-forget sends. Position updates (16B payload), chat messages (~70B payload), mixed traffic (80% position + 20% chat, 10 messages per batch).

All benchmarks use fire-and-forget (non-blocking) sends for a fair comparison. JDK 26, JMH with `-prof gc`, single fork, 5 measurement iterations.

### Throughput (ops/ms) — higher is better

| Benchmark | jtroop | jtroop blocking | Netty 4.2 | SpiderMonkey 3.7 |
|-----------|--------|----------------|-----------|------------------|
| positionUpdate | 27,506 | 4,762 | 12,624 | 625 |
| chatMessage (record) | 13,516 | 4,067 | 13,314 | 609 |
| chatMessage (@ZeroAlloc) | **13,713** | — | — | — |
| mixedTraffic (10 msg) | 2,047 | — | 1,277 | 61 |
| requestResponse (RPC) | 945 | — | — | — |

### Allocation (B/op) — lower is better

| Benchmark | jtroop | jtroop blocking | Netty 4.2 | SpiderMonkey 3.7 |
|-----------|--------|----------------|-----------|------------------|
| positionUpdate | **~10⁻⁴** | **0.001** | 1,110 | 449 |
| chatMessage (record) | 384 | 384 | 1,577 | 840 |
| chatMessage (@ZeroAlloc) | **24** | — | — | — |
| mixedTraffic (10 msg) | 1,440 | — | 12,272 | 6,391 |
| requestResponse (RPC) | **88** | — | — | — |

### Layer overhead (B/op)

| Layer stack | ops/ms | B/op |
|---|---:|---:|
| framing only | 31,109 | 64 |
| framing + compression (Deflater reused) | 940 | **32** |
| framing + AES-GCM encryption (Cipher reused) | 4,784 | 1,864 |

### HTTP/1.1 throughput (wrk) — higher is better

External load generator, "Hello, World!" responses through the full HTTP pipeline (per-request encoding via `HttpLayer`, no pre-built cheat). Same JDK 26, same box, TCP_NODELAY, SO_REUSEPORT worker fan-out on both sides.

| Load | jtroop | Netty 4.2 | jtroop advantage |
|------|-------:|----------:|-----------------:|
| `wrk -t4 -c100 -d15s` | 1,105,044 req/s | **1,175,169 req/s** | −6% (client-bottlenecked) |
| `wrk -t8 -c400 -d15s` | **2,079,014 req/s** | 1,764,029 req/s | +17.8% |
| `wrk -t16 -c400 -d15s` | **3,235,494 req/s** | 2,676,074 req/s | +20.9% |
| `wrk -t32 -c400 -d15s` | **3,481,729 req/s** | 2,792,112 req/s | +24.7% |

### Notes

- **jtroop fire-and-forget** `positionUpdate`: stages encoded bytes into a pre-allocated direct ByteBuffer. Under broadcast-connection load the selector-Consumer form of EventLoop dispatch drove residual allocation from 0.019 → ~10⁻⁴ B/op (below any reliable measurement).
- **jtroop blocking** `positionUpdate_blocking`: direct-write fast path under the per-slot buffer lock when nothing is staged — bypasses the selector wakeup. Dropped from 16 B/op → 0.001 B/op across two optimisation rounds; 987 → 4,762 ops/ms.
- **`chatMessage (@ZeroAlloc)`**: opt-in handler dispatch via `@ZeroAlloc(ChatMessage.class)` routes the inbound frame straight to a hidden-class `RawHandlerInvoker` taking `(ByteBuffer, ConnectionId, Broadcast, Unicast)`. Skips codec-decode entirely; 24 B/op is just the ChatMessage record the legacy handler still constructs for the server-side work. Legacy record-parameter handler continues to work (back-compat).
- **`requestResponse`**: the response decode now happens on the **waiter's stack** (not the EventLoop thread) — the waiter's scratch ByteBuffer is filled with raw bytes, parked/unparked, and then decoded locally, keeping the record EA-eligible. 184 → 88 B/op.
- **Netty**: jtroop beats Netty 4.2 on every JMH benchmark and on HTTP at ≥ 8 wrk threads:
  - 2.2× throughput, ~11,000,000× less allocation on `positionUpdate`
  - 27× throughput on `positionUpdate_blocking`
  - Throughput parity, 4× less allocation on `chatMessage` (record); 17× less alloc with `@ZeroAlloc`
  - 18–25% more HTTP req/s at `-t8/-t16/-t32 -c400`
- **SpiderMonkey**: stable but throughput limited by thread-per-kernel model and reflection-based serialization.
- All JMH benchmarks encode/decode through the full pipeline. Netty uses `MessageToByteEncoder`/`ByteToMessageDecoder`, jtroop uses its codec+pipeline, SpiderMonkey uses its `Serializer`.
- The `-t4 -c100` HTTP regression is real: at low concurrency jtroop's SO_REUSEPORT fan-out leaves some workers idle (wrk's threads can't saturate N loops), so per-connection overhead dominates and Netty's queue batching wins narrowly. Past `-t8` the fan-out pays off.

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
| Direct ByteBuffers | 0 | 30,736 | Direct write buffers, spin-wait blocking, encodeToWire split |
| Agent sweep (9 worktrees) | 0.03 | 27,963 | SoA session store, flat codec table, hidden-class service dispatch, cached framing view, zero-alloc UDP dup filter, back-pressure correctness, large-message path, malformed-input hardening, HTTP/1.1 robustness |
| Agent sweep II (11 worktrees) | 0.019 | 29,722 | Zero-alloc UTF-8 String codec, MPSC setup queue, blocking-send direct-write fast path, zero-alloc RPC ring + typed proxy, broadcast encode-once fan-out, UDP JMH bench + AckLayer SoA, fused-pipeline wired onto hot paths, primitive-long session iteration |
| Agent sweep III (10 worktrees) | ~10⁻⁴ | 27,506 | `@ZeroAlloc` handler opt-in (24 B/op chat), Deflater/Cipher pooling, connected-UDP mode (0.004 B/op), flat slot→channel array for broadcast, select(Consumer) eliminates HashIterator, adaptive selectNow, HTTP SO_REUSEPORT, waiter-side RPC decode |

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
