# jtroop

**J**ava **T**yped **R**ecord-**O**riented **O**ptimized **P**rotocol

A zero-dependency networking module for JDK 26+ that eliminates hot-path allocation through EA-friendly design and runtime bytecode generation.

## Benchmark Results

Game scenario: TCP server + client, length-prefix framing, fire-and-forget sends. Position updates (16B payload), chat messages (~70B CharSequence payload), mixed traffic (80% position + 20% chat, 10 messages per batch). JDK 26, JMH with `-prof gc`, single fork, 5 measurement iterations.

### Throughput (ops/ms) — higher is better

| Benchmark | jtroop | jtroop blocking | Netty 4.2 | Netty blocking | SpiderMonkey 3.7 |
|-----------|-------:|----------------:|----------:|---------------:|-----------------:|
| positionUpdate | **45,672** | 747 | 12,701 | 174 | 629 |
| chatMessage | **20,593** | 735 | 13,272 | 175 | 603 |
| mixedTraffic (10 msg) | **3,835** | — | 1,295 | — | 60 |
| requestResponse (RPC) | **146** | — | — | — | — |

### Allocation (B/op) — lower is better

| Benchmark | jtroop | jtroop blocking | Netty 4.2 | Netty blocking | SpiderMonkey 3.7 |
|-----------|-------:|----------------:|----------:|---------------:|-----------------:|
| positionUpdate | **0.021** | **1.4** | 1,109 | 785 | 443 |
| chatMessage | **0.048** | **57** | 1,606 | 1,096 | 855 |
| mixedTraffic (10 msg) | **304** | — | 11,220 | — | 6,139 |
| requestResponse (RPC) | **71** | — | — | — | — |

### HTTP/1.1 throughput (wrk)

External load generator, "Hello, World!" responses. Same JDK 26, same box, TCP_NODELAY, SO_REUSEPORT.

| Load | jtroop | Netty 4.2 | jtroop advantage |
|------|-------:|----------:|-----------------:|
| `wrk -t4 -c100 -d15s` | **1,179,032 req/s** | 1,099,491 req/s | +7.2% |
| `wrk -t8 -c400 -d15s` | **2,022,389 req/s** | 1,756,998 req/s | +15.1% |
| `wrk -t16 -c400 -d15s` | **3,267,139 req/s** | 2,660,802 req/s | +22.8% |
| `wrk -t32 -c400 -d15s` | **3,560,165 req/s** | 2,777,301 req/s | +28.2% |

### UDP (game-shaped workload)

| Benchmark | ops/ms | B/op |
|---|---:|---:|
| `udpPositionUpdate` (connected) | 1,202 | **0.937** |
| `udpReliable` (seq+dup+ack) | 861 | **1.272** |

### Latency (SampleTime, nanoseconds)

| Benchmark | p50 | p90 | p99 | p999 |
|---|---:|---:|---:|---:|
| positionUpdate (fire-forget) | 60 ns | 90 ns | 120 ns | 2,420 ns |
| positionUpdate (blocking) | 420 ns | 1,150 ns | 5,984 ns | 39,808 ns |
| chatMessage (fire-forget) | 100 ns | 160 ns | 410 ns | 5,016 ns |
| requestResponse (RPC round-trip) | **7.5 µs** | 8.8 µs | 13.5 µs | 54 µs |

### Game simulation (100 TCP clients, 4 event loops)

| Benchmark | Ticks/sec | B/op | 60Hz headroom |
|---|---:|---:|---:|
| gameTick (100 pos + broadcast) | **110,000** | 261 | 1,833× |
| gameTickWithChat (+ 5 chat broadcasts) | **2,200** | 22,195 | 36× |

### Comparison summary

| vs Netty 4.2 | Throughput | Allocation |
|---|---|---|
| positionUpdate | **3.6×** faster | **53,000×** less |
| positionUpdate (blocking) | **4.3×** faster | **560×** less |
| chatMessage | **1.6×** faster | **33,000×** less |
| chatMessage (blocking) | **4.2×** faster | **19×** less |
| mixedTraffic | **3.0×** faster | **37×** less |
| HTTP (wrk t8 c400) | **+15%** | — |
| HTTP (wrk t32 c400) | **+28%** | — |

### Notes

- **positionUpdate 45.9K ops/ms**: per-message-type `SendCtx` cache reduces 5-7 map lookups per send to 1. For fixed-size primitive records with a single FramingLayer, a single-buffer shortcut writes the 4-byte length prefix + codec.encode directly into wireBuf — skipping the intermediate encodeBuf and the fused pipeline entirely. 64KB write buffers prevent back-pressure stalls at high throughput.
- **chatMessage 0.049 B/op**: round 4 discovered and fixed a **bytecode-generation bug** in `CodecClassGenerator.emitPut` — String fields emitted a `POP` after `invokestatic` (which returns void), causing stack underflow → silent fallback to reflective decode with `Object[]` + boxing (~312 B/op). Combined with `BufferCharSequence` (zero-copy `CharSequence` backed by the wire buffer's heap array), chatMessage went from 384 → 0.049 B/op and 13.5K → 20.3K ops/ms.
- **`FusedReceiverGenerator`**: generates a single hidden class per (pipeline-shape × handler-set) that fuses framing → typeId switch → inline field decode → monomorphic handler call. +60% throughput vs the 3-stage path (21.2K vs 13.2K ops/ms on the readLoop microbenchmark).
- All JMH benchmarks encode/decode through the full pipeline. Netty uses `MessageToByteEncoder`/`ByteToMessageDecoder`, jtroop uses its codec+pipeline, SpiderMonkey uses its `Serializer`.

## Design Approach

jtroop minimizes allocation through techniques driven by C2's Escape Analysis capabilities (see `CLAUDE.md` for the 10 rules):

- **Per-message-type `SendCtx` cache** — sealed interface with `Tcp` and `Udp` variants. One `ConcurrentHashMap.get` per send, everything pre-resolved.
- **Single-buffer framing shortcut** — for fixed-size primitive records with FramingLayer, encode directly into wireBuf with pre-known length prefix. Skip the intermediate encodeBuf and fused pipeline entirely.
- **Bytecode-generated codecs** — `java.lang.classfile` generates hidden classes with direct field access, monomorphic `invokevirtual`. `BufferCharSequence` for zero-copy String/CharSequence decode.
- **Fused pipeline generation** — hidden class per unique layer stack. `FusedReceiverGenerator` further fuses pipeline + codec + dispatch into one method.
- **SoA session storage** — connection state in parallel primitive arrays. `ConnectionId` is a packed long (index + generation).
- **Per-connection `Layer.Context`** — peer address, byte counters, close hooks threaded through every layer call.
- **Adaptive selector** — `selectNow()` when work is pending, `select(Consumer, timeout)` when idle. The `Consumer` form eliminates `selectedKeys().iterator()` allocation.

## Quick Start

### Server

```java
var server = Server.builder()
    .listen(GameConn.class, Transport.tcp(8080),
        new FramingLayer(), new EncryptionLayer(key), new CompressionLayer())
    .listen(GameConn.class, Transport.udpConnected(8081))
    .addService(new GameHandler(), GameConn.class)
    .eventLoops(4)
    .build();
server.start();
```

### Client

```java
var client = Client.builder()
    .connect(GameConn.class, Transport.tcp("game.example.com", 8080),
        new FramingLayer(), new EncryptionLayer(key), new CompressionLayer())
    .connect(GameConn.class, Transport.udpConnected("game.example.com", 8081))
    .addService(GameService.class, GameConn.class)
    .build();
client.start();

// Typed proxy — transport routing baked into generated bytecode
GameService game = client.service(GameService.class);
game.chat(new ChatMessage("hello", 1));                    // → TCP
game.position(new PositionUpdate(x, y, z, yaw));           // → UDP (@Datagram)

// Request/response (blocking RPC, always TCP)
ChatHistory h = game.getHistory(new HistoryRequest(1));
```

### Service Contract

```java
interface GameService {
    void chat(ChatMessage msg);                         // fire-and-forget, TCP
    @Datagram void position(PositionUpdate pos);        // fire-and-forget, UDP
    ChatHistory getHistory(HistoryRequest req);          // request/response, TCP
}
```

`@Datagram` on the interface method tells the generated proxy to route via UDP. The same record type can appear on both TCP and UDP methods — the proxy resolves transport **per method**, not per type. Dispatch on the server is always by record type regardless of transport.

### Handler

```java
@Handles(GameService.class)
class GameHandler {
    @OnMessage
    void chat(ChatMessage msg, ConnectionId sender, Broadcast broadcast) {
        broadcast.send(msg);  // fan-out to all connected clients
    }

    @OnMessage
    void position(PositionUpdate pos, ConnectionId sender) {
        // update world state — arrives via TCP or UDP, handler doesn't care
    }

    @OnMessage
    ChatHistory getHistory(HistoryRequest req, ConnectionId sender) {
        return new ChatHistory(loadHistory(req.room()));
    }

    @OnConnect void join(ConnectionId id) { }
    @OnDisconnect void leave(ConnectionId id) { }
}
```

Handler methods are matched to service interface methods by **record type** (the first `Record` parameter). Method names on the handler are irrelevant — only the record type matters. Injectables (`ConnectionId`, `Broadcast`, `Unicast`) are passed in any order, any subset.

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
        Transport.tcp("game.example.com", 8080), new FramingLayer())
```

### Early Filters

Layers receive a per-connection `Layer.Context` on every call. Filter layers at the front of the pipeline reject bad peers before framing/codec layers see their bytes.

```java
Server.builder()
    .listen(GameConn.class, Transport.tcp(8080),
            new AllowListLayer(allowedIps),     // rejects non-allowed peers
            new RateLimitLayer(1_000_000),      // bytes/sec budget
            new FramingLayer());
```

`Layer.Context`: `connectionId()`, `remoteAddress()`, `bytesRead()`, `bytesWritten()`, `connectedAtNanos()`, `closeAfterFlush()`, `closeNow()`.

### Runtime Pipeline Mutation

For protocols that swap the parser mid-stream (HTTP→WebSocket, STARTTLS, ALPN):

```java
var wsPipeline = httpPipeline.replace(HttpLayer.class, new WebSocketLayer());
server.switchPipeline(connId, wsPipeline);
```

The fused hidden class is shape-indexed and cached — mutation costs one-time codegen on shape miss, pointer write on hit. The swap runs on the connection's owning EventLoop (frame-aligned, race-free).

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
| @Datagram per-method routing (same record on TCP + UDP) | Done |
| Typed connection groups (multiple servers, mixed transports) | Done |
| Handshake / capability negotiation | Done |
| Request/response + fire-and-forget + server push | Done |
| Typed service proxies (client.service(Interface.class)) | Done |
| Broadcast / Unicast injectables | Done |
| Configurable executor (virtual thread support) | Done |
| Per-connection Layer.Context (peer addr, byte counters, close hooks) | Done |
| Early-filter layers (AllowListLayer, RateLimitLayer) | Done |
| Runtime pipeline mutation (shape-cached fused class, safe swap) | Done |
| HTTP/1.1 layer + HTTP → WebSocket upgrade | Done |
| EventLoopGroup (round-robin connection distribution) | Done |
| Bytecode-generated codecs (java.lang.classfile + hidden classes) | Done |
| Fused pipeline + codec + dispatch generation | Done |
| MPSC ring buffer (lock-free, zero-alloc) | Done |
| Test forwarder (latency, packet loss, reorder) — TCP + UDP | Done |
| JMH benchmark suite (vs Netty 4.2, vs SpiderMonkey 3.7) | Done |

## Architecture

```
jtroop/
├── core/           EventLoop, EventLoopGroup, ReadBuffer, WriteBuffer, MpscRingBuffer
├── transport/      Transport (sealed: TCP, UDP, UdpConnected)
├── pipeline/       Layer, Layer.Context, Pipeline, Layers factory
│   └── layers/     Framing, Compression, Encryption, Sequencing, DuplicateFilter, Ack,
│                   Http, WebSocket, AllowList, RateLimit
├── codec/          CodecRegistry (record ↔ ByteBuffer, deterministic type IDs)
├── service/        @OnMessage, @Handles, @Datagram, @ZeroAlloc, ServiceRegistry,
│                   Broadcast, Unicast
├── session/        ConnectionId (packed long), SessionStore (SoA arrays)
├── server/         Server, Server.Builder
├── client/         Client, Client.Builder, SendCtx (sealed: Tcp, Udp)
├── generate/       CodecClassGenerator, FusedPipelineGenerator, FusedReceiverGenerator,
│                   HandlerInvokerGenerator, RawHandlerInvokerGenerator, ServiceProxyGenerator
│                   (all via java.lang.classfile → defineHiddenClass)
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
| Agent sweep I (9) | 0.03 | 27,963 | SoA session, flat codec table, hidden-class dispatch, cached framing view, UDP dup filter, back-pressure, large-message, malformed-input, HTTP/1.1 |
| Agent sweep II (11) | 0.019 | 29,722 | Zero-alloc UTF-8 codec, MPSC setup, direct-write blocking, RPC ring + typed proxy, encode-once broadcast, AckLayer SoA, fused pipeline on hot paths |
| Agent sweep III (10) | ~10⁻⁴ | 27,506 | Deflater/Cipher pooling, connected-UDP, flat slot→channel, select(Consumer), adaptive selectNow, HTTP SO_REUSEPORT, waiter-side RPC decode |
| Agent sweep IV (20) | **0.021** | **45,938** | **CodecClassGenerator bug fix**, BufferCharSequence, SendCtx cache, single-buffer framing shortcut, 64KB buffers, FusedReceiverGenerator, inline executor dispatch, per-method @Datagram proxy routing |
| Review sweep (20) | 0.021 | 45,672 | Production hardening: 5 resource leaks, 3 thread-safety races, exception hierarchy, graceful shutdown, SessionStore security, flaky tests stabilized, 34 null checks, 214 javadocs, dead code removal |
| Agent sweep V (20) | **0.021** | **45,672** | request() sendBlocking fix (RPC p50 1ms→7µs), compression small-payload bypass, per-loop broadcast dispatch, lazy write buffers, decodeConsumer 0 B/op, TCP_NODELAY on accept/connect, game simulation benchmark |

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
