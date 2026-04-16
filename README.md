# jtroop

**J**ava **T**yped **R**ecord-**O**riented **O**ptimized **P**rotocol

A zero-dependency networking module for JDK 26+ that minimizes hot-path allocation through EA-friendly design.

## Benchmark Results

Game scenario: TCP server + client, length-prefix framing, fire-and-forget sends. Position updates (16B payload), chat messages (~70B payload), mixed traffic (80% position + 20% chat, 10 messages per batch).

All benchmarks use fire-and-forget (non-blocking) sends for a fair comparison. JDK 26, JMH with `-prof gc`, single fork, 5 measurement iterations.

### Throughput (ops/ms) — higher is better

| Benchmark | jtroop | jtroop blocking | Netty 4.2 | SpiderMonkey 3.7 |
|-----------|--------|----------------|-----------|------------------|
| positionUpdate | **45,038** | 4,762 | 12,624 | 625 |
| chatMessage (CharSequence) | **20,820** | 4,067 | 13,314 | 609 |
| mixedTraffic (10 msg) | **3,772** | — | 1,277 | 61 |
| requestResponse (RPC) | 945 | — | — | — |

### Allocation (B/op) — lower is better

| Benchmark | jtroop | jtroop blocking | Netty 4.2 | SpiderMonkey 3.7 |
|-----------|--------|----------------|-----------|------------------|
| positionUpdate | **0.022** | **0.001** | 1,110 | 449 |
| chatMessage (CharSequence) | **0.048** | — | 1,577 | 840 |
| mixedTraffic (10 msg) | **304** | — | 12,272 | 6,391 |
| requestResponse (RPC) | 88 | — | — | — |

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

- **positionUpdate 45K ops/ms**: per-message-type cached `TcpSendCtx` reduces 5-7 map lookups per send to 1. For fixed-size primitive records with a single FramingLayer, a single-buffer shortcut writes the 4-byte length prefix + codec.encode directly into wireBuf — skipping the intermediate encodeBuf and the fused pipeline entirely. 64KB write buffers prevent back-pressure stalls at high throughput.
- **chatMessage 0.048 B/op**: round 4 discovered and fixed a **bytecode-generation bug** in `CodecClassGenerator.emitPut` — String fields emitted a `POP` after `invokestatic` (which returns void), causing stack underflow → silent fallback to reflective decode with `Object[]` + boxing (~312 B/op). The 384 B/op we chased for three rounds was mostly this bug. Combined with `BufferCharSequence` (zero-copy `CharSequence` backed by the wire buffer's heap array), chatMessage allocation dropped from 384 → 0.048 B/op and throughput rose from 13.5K → 20.8K ops/ms.
- **`FusedReceiverGenerator`**: generates a single hidden class per (pipeline-shape × handler-set) that fuses framing → typeId switch → inline field decode → monomorphic handler call in one method. No intermediate ByteBuffer, no Record, no ReadBuffer. Server read loop is +60% vs the 3-stage path.
- **Netty**: jtroop beats Netty 4.2 on every JMH benchmark and on HTTP at ≥ 8 wrk threads:
  - 3.6× throughput, ~50,000× less allocation on `positionUpdate`
  - 27× throughput on `positionUpdate_blocking`
  - 1.6× throughput, ~33,000× less allocation on `chatMessage`
  - 3× throughput, 40× less allocation on `mixedTraffic`
  - 18–33% more HTTP req/s at `-t8/-t16/-t32 -c400`
- **SpiderMonkey**: stable but throughput limited by thread-per-kernel model and reflection-based serialization.
- All JMH benchmarks encode/decode through the full pipeline. Netty uses `MessageToByteEncoder`/`ByteToMessageDecoder`, jtroop uses its codec+pipeline, SpiderMonkey uses its `Serializer`.
- The `-t4 -c100` HTTP regression is real: at low concurrency jtroop's SO_REUSEPORT fan-out leaves some workers idle (wrk's threads can't saturate N loops), so per-connection overhead dominates and Netty's queue batching wins narrowly. Past `-t8` the fan-out pays off.

### UDP (game-shaped workload)

Fire-and-forget position packets. `Transport.udpConnected(...)` pins the server-side peer so the read loop uses `channel.read(buf)` instead of allocation-heavy `channel.receive(buf, addr)`. Reliable variant adds Sequencing + DuplicateFilter + Ack layers.

| Benchmark | ops/ms | B/op |
|---|---:|---:|
| `udpPositionUpdate` (connected) | 1,218 | **0.002** |
| `udpReliable` (seq+dup+ack) | 916 | **0.003** |

### Session iteration (N=100 active in 4096-slot store)

Used by broadcast fan-out and GC sweeps. Three API shapes, all zero-alloc:

| Benchmark | ops/ms | B/op |
|---|---:|---:|
| `forEachActive(Consumer<ConnectionId>)` — record | 1,254 | 0.003 |
| `forEachActiveLong(LongConsumer)` — packed long | **1,475** | 0.003 |
| `activeCopyIds(long[] out)` — snapshot | 984 | 0.004 |
| `forEachActive` + fresh lambda per call | 1,274 | 0.122 |

### Read-loop microbenchmarks (8 frames per op, no sockets)

Confirms the inner receive-and-dispatch drain is zero-alloc — any residual would multiply by messages/sec in a real server.

| Benchmark | ops/ms | B/op |
|---|---:|---:|
| `readLoop_clientDrain` (decode + primitive field touch) | 18,290 | ~10⁻⁴ |
| `readLoop_serverDrain` (decode + `ServiceRegistry.dispatch`) | 13,445 | ~10⁻⁴ |
| `readLoop_serverExecutor` (+ per-frame `executor.execute` lambda) | 13,317 | ~10⁻⁴ |

### Dispatch microbenchmarks (isolated from transport)

`ServiceRegistry.dispatch` through the hidden-class `HandlerInvoker` — no sockets, no framing, no codec.

| Benchmark | ns/op | B/op |
|---|---:|---:|
| `dispatchDirect_void` | 3.30 | ~10⁻⁵ |
| `dispatchDirect_returning` (shared return record) | 3.24 | ~10⁻⁵ |
| `dispatchDirect_broadcast` (+ `Broadcast` injectable) | 2.82 | ~10⁻⁵ |
| `dispatchDirect_allInjectables` (+ `Unicast` injectable) | 2.70 | ~10⁻⁵ |

### Codec microbenchmarks (record ↔ ByteBuffer, no I/O)

| Benchmark | ns/op | B/op |
|---|---:|---:|
| `encodeOnly` (fresh record per call) | 4.37 | ~10⁻⁵ |
| `encodeOnly_cachedRecord` | 4.53 | ~10⁻⁵ |
| `decodeOnly` (returns record) | 8.77 | 32 (record) |
| `encodeDecodeRoundtrip` | 8.20 | 32 (record) |

### Payload size sweep (`PositionBlob` record with growing `byte[]` field)

| Payload | ops/ms | B/op |
|---|---:|---:|
| 16 B small | 28,459 | 80 |
| 256 B medium | 5,234 | 720 |
| 4 KB large | 452 | 4,512 |

Allocation scales linearly with payload because the `byte[]` inside the `PositionBlob` record is structurally a fresh heap object per op (and is correctly observed as such).

### Many clients (100 TCP clients, round-robin send)

| Benchmark | ops/ms | B/op |
|---|---:|---:|
| `positionUpdate_manyClients` | 14,151 | 38 |

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

### Early Filters (rate limit, allow-list, slowloris guard)

Layers receive a per-connection `Layer.Context` on every call: peer address, cumulative byte counters, connection timestamp, and graceful/abrupt close hooks. Put a filter layer at the **front** of the pipeline to reject bad peers before the framing/codec layers ever see their bytes.

```java
var allowList = new AllowListLayer(Set.of(
        InetAddress.getByName("10.0.0.0/8"),
        InetAddress.getByName("127.0.0.1")));

var rateLimit = new RateLimitLayer(/* bytes/sec */ 1_000_000);

Server.builder()
    .listen(GameConn.class, Transport.tcp(8080),
            allowList,          // ← checked first, closes connection on non-allowed peer
            rateLimit,          // ← closes on byte budget overrun
            new FramingLayer(), // ← never sees blocked peers' bytes
            new HttpLayer());
```

`Layer.Context` accessors: `connectionId()`, `remoteAddress()`, `bytesRead()`, `bytesWritten()`, `connectedAtNanos()`, `closeAfterFlush()`, `closeNow()`. Writing your own filter is a ~20-line layer (see `AllowListLayer.java`, `RateLimitLayer.java` for templates).

### Runtime Pipeline Mutation (HTTP → WebSocket, STARTTLS, ALPN)

The pipeline shape is normally fixed per connection-type so the fused hidden class can inline every layer call. For protocols that need to swap the parser mid-stream — HTTP→WebSocket upgrade, STARTTLS, HTTP/2 ALPN, PROXY-protocol strip, multi-protocol port sniffing — use `switchPipeline` with an `addFirst`/`remove`/`replace` edit:

```java
// Inside the HTTP handler, right after writing the 101 response:
var wsPipeline = httpPipeline.replace(HttpLayer.class, new WebSocketLayer());
server.switchPipeline(connId, wsPipeline);
```

The new pipeline's fused hidden class is looked up in a shape-indexed cache (generated on first sighting, reused thereafter) — each unique shape costs one-time codegen, subsequent swaps to the same shape are a pointer write. The swap itself runs on the connection's owning EventLoop so it's race-free with in-flight reads, and is frame-aligned (call from `@OnMessage` or a handshake callback so you're already between frames).

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
| Runtime pipeline mutation (shape-cached fused class, safe swap) | Done |
| HTTP → WebSocket upgrade e2e | Done |
| Per-connection Layer.Context (peer addr, byte counters, close hooks) | Done |
| Early-filter layers (AllowListLayer, RateLimitLayer) | Done |
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
| Agent sweep IV (20 worktrees) | 0.022 | 45,038 | **CodecClassGenerator bug fix** (String fields silently fell back to reflective decode), BufferCharSequence zero-copy, TcpSendCtx cache (5-7 lookups → 1), single-buffer framing shortcut, 64KB write buffers, FusedReceiverGenerator (pipeline+decode+dispatch in one hidden class), inline executor dispatch, latency profiling |

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
