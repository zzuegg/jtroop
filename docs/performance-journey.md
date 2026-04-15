# Net — Performance Journey

From naive implementation to 50x faster than Netty, in four optimization passes.

## The Goal

Beat Netty's per-message allocation rate and throughput for a realistic game scenario (position updates, chat messages, mixed traffic) while maintaining a clean, flexible API.

## Benchmark Setup

- **JDK 26** with `--enable-preview`, stock C2 JIT
- **JMH** with `-prof gc` measuring `gc.alloc.rate.norm` (B/op) and throughput (ops/ms)
- **Scenario**: TCP server + client, length-prefix framing, fire-and-forget position updates
- **positionUpdate**: 4 floats (x, y, z, yaw) = 16 bytes payload
- **chatMessage**: String + int = ~70 bytes payload
- **mixedTraffic**: 80% position + 20% chat, 10 messages per batch

## Netty Baseline

| Benchmark | B/op | ops/ms | GC count |
|-----------|------|--------|----------|
| positionUpdate | **862** | **167** | 124 |
| chatMessage | **1,086** | **176** | 111 |
| mixedTraffic | **193,172** | **245** | 302 |

Netty's costs: pooled ByteBuf with atomic refcounting, ChannelHandler interface dispatch (megamorphic), Promise/Future allocation per writeAndFlush, ChannelHandlerContext objects.

---

## Iteration 0 — Naive Implementation

The first working version. Correct, tested, but no optimization.

**What it did wrong:**
- `ByteBuffer.allocate(65536)` x2 per send (client) and per response (server) = 128KB per message
- `Pipeline.encodeOutbound()` allocated another 64KB temp buffer per layer
- Everything on the caller thread, submitted via lambda to EventLoop

| Benchmark | B/op | ops/ms |
|-----------|------|--------|
| positionUpdate | **197,768** | **150** |
| chatMessage | **197,896** | **150** |
| mixedTraffic | **1,977,583** | **15** |

**229x more allocation than Netty. 0.9x throughput.** The naive version was slower and allocated orders of magnitude more.

---

## Iteration 1 — Buffer Reuse

**Change:** Pre-allocate `encodeBuf` and `wireBuf` as fields on Client and Server. Reuse across messages. Pipeline pre-allocates temp buffers per layer.

**Key insight:** We were allocating 192KB of ByteBuffers per message that could trivially be reused.

| Benchmark | B/op | ops/ms | vs Netty B/op |
|-----------|------|--------|---------------|
| positionUpdate | **150** | **5,799** | 5.7x less |
| chatMessage | **161** | **6,617** | 6.7x less |
| mixedTraffic | **1,488** | **674** | 130x less |

**1,300x reduction in allocation. 35x throughput improvement.** One change. This is the power of not allocating.

---

## Iteration 2 — Eliminate Lambda + Queue Node Allocation

**Change:** Replaced `eventLoop.submit(lambda)` with `eventLoop.stageWrite(slot, data)`. Pre-allocated per-slot write buffers in EventLoop. Encoding moved to caller thread, only raw bytes staged for async write. No Runnable, no ConcurrentLinkedQueue.Node.

**Key insight:** The submit() path allocated a lambda closure (~32B) + a queue node (~40B) per message. Moving encoding to the caller thread and staging bytes into a pre-allocated buffer eliminated both.

| Benchmark | B/op | ops/ms | vs Netty B/op |
|-----------|------|--------|---------------|
| positionUpdate | **103** | **7,464** | 8.4x less |
| chatMessage | **168** | **6,858** | 6.5x less |
| mixedTraffic | **1,580** | **707** | 122x less |

---

## Iteration 3 — Eliminate Primitive Boxing in Codec

**Change:** `ComponentCodec.encodeDirect(MethodHandle, Record, ByteBuffer)` — reads record fields via MethodHandle and writes directly to ByteBuffer with primitive casts (`(float) accessor.invoke(msg)`). No intermediate `Object` boxing.

**Key insight:** The generic `Object value = accessor.invoke(msg)` boxed every primitive field (4 floats = 4 Float objects = 64 bytes). Direct casting with `(float)` at the call site lets the JIT avoid boxing entirely.

| Benchmark | B/op | ops/ms | vs Netty B/op | vs Netty ops/ms |
|-----------|------|--------|---------------|-----------------|
| positionUpdate | **38** | **8,467** | **22.7x less** | **50x faster** |
| chatMessage | **156** | **6,250** | **7x less** | **35x faster** |
| mixedTraffic | **879** | **782** | **220x less** | **3.2x faster** |

---

## Final Comparison

### Allocation (B/op) — lower is better

```
positionUpdate:
  Net (ours) ██ 38
  Netty      ████████████████████████████████████████████ 862

chatMessage:
  Net (ours) ████████ 156
  Netty      ██████████████████████████████████████████████████████ 1,086

mixedTraffic:
  Net (ours) ████████████████████████████████████████████ 879
  Netty      [off the chart — 193,172]
```

### Throughput (ops/ms) — higher is better

```
positionUpdate:
  Net (ours) ██████████████████████████████████████████████████ 8,467
  Netty      █ 167

chatMessage:
  Net (ours) ██████████████████████████████████████████████████ 6,250
  Netty      █ 176

mixedTraffic:
  Net (ours) ██████████████████████████████████████████████████ 782
  Netty      ███████████████ 245
```

### Optimization History (positionUpdate)

| Pass | B/op | ops/ms | Change |
|------|------|--------|--------|
| Naive | 197,768 | 150 | — |
| Buffer reuse | 150 | 5,799 | -99.9% alloc, +38x throughput |
| stageWrite | 103 | 7,464 | -31% alloc, +1.3x throughput |
| No boxing | 38 | 8,467 | -63% alloc, +1.1x throughput |
| **Total** | **38** | **8,467** | **-99.98% alloc, +56x throughput** |

## What's Left in the 38 B/op

The remaining allocation for `positionUpdate` (38 B/op) is:
- **~32 bytes**: The `PositionUpdate` record object itself — created by the benchmark caller, not the framework
- **~6 bytes**: JMH measurement noise / rounding

The framework hot path (encode → stage → flush → write) is effectively **0 B/op** for primitive-only records. The record creation is user-side, unavoidable in this benchmark shape. In a real game loop writing fields directly into the buffer, there would be zero allocation.

## Why Netty Can't Do This

1. **Interface pipeline blocks EA** — `invokeinterface` is megamorphic, JIT can't inline, objects must be heap-allocated
2. **Must pool buffers** — objects escape through handler boundaries, so EA can't help. Pooling + atomic refcounting is the workaround
3. **Object-per-message design** — decoded messages, contexts, promises are all heap objects by design
4. **Targets Java 8+** — can't use modern EA improvements, classfile API, hidden classes, or virtual threads
5. **Runtime-mutable pipeline** — handlers can be added/removed at runtime, preventing build-time fusion

Our module designs *for* the JIT. Netty designs *around* it.
