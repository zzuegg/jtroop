# jtroop net benchmarks

JMH benchmarks for the `jtroop` net library. Targets **0 B/op** on hot paths
(see `CLAUDE.md` for the allocation-free design rules).

## Running

```bash
# Default — runs NetGameBenchmark only.
gradle :benchmark:net:jmh

# Run everything:
gradle :benchmark:net:jmh -Pjmh.include='bench\.net\..*'

# Run one class:
gradle :benchmark:net:jmh -Pjmh.include=HttpLayerBenchmark

# Override warmup/measurement time (defaults follow JMH: 10s each):
gradle :benchmark:net:jmh -Pjmh.include=CodecMicroBenchmark \
    -Pjmh.warmup=3s -Pjmh.timeOnIteration=3s

# Or invoke the JMH jar directly (full JMH CLI available):
java --enable-preview -jar benchmark/net/build/libs/net-0.1.0-SNAPSHOT-jmh.jar \
    HttpLayerBenchmark -f 1 -wi 3 -i 5 -w 2s -r 3s -prof gc
```

Gradle plugin defaults: `fork=1, warmupIterations=1, iterations=2, mode=thrpt,
-prof gc, forceGC=true`. JMH defaults handle warmup/iteration durations (10s)
unless overridden via `-Pjmh.warmup` / `-Pjmh.timeOnIteration`.

## What each benchmark measures

### End-to-end send/receive (full network stack)

| Benchmark | What it measures | Key signal |
|---|---|---|
| `NetGameBenchmark.positionUpdate` | 4-float record through full client → server loopback | Baseline: if this regresses, something broke in the encode/pipeline/dispatch path |
| `NetGameBenchmark.positionUpdate_blocking` | Same, but `sendBlocking` — waits for EventLoop flush | Compare against `positionUpdate` to see fire-and-forget vs blocking overhead |
| `NetGameBenchmark.chatMessage` | Record with embedded String (UTF-8 encode) | Measures StringCodec cost vs primitive-only records |
| `NetGameBenchmark.mixedTraffic` | 80% position / 20% chat per op | Realistic game-loop mix; per-op allocation is 10x single send |
| `NetGameBenchmark.requestResponse` | Round-trip RPC through `EchoService` | Covers client.request + server reply path |

### Payload size sweep

| Benchmark | Payload | What to look for |
|---|---|---|
| `PayloadSizeBenchmark.positionUpdate_small` | 16 B (4 floats) | Per-message fixed overhead |
| `PayloadSizeBenchmark.positionUpdate_medium` | 256 B string | Linear payload copy cost starts to dominate |
| `PayloadSizeBenchmark.positionUpdate_large` | 4096 B string | Memory bandwidth / large-buffer behavior |

A constant B/op across sizes = per-message overhead only.
B/op growing with size = copying-allocation (look at `String.getBytes`,
`ByteBuffer.duplicate`, per-message `byte[]` clones).

### Many clients

| Benchmark | Clients | Signal |
|---|---|---|
| `ManyClientsBenchmark.positionUpdate_manyClients` | 100 | Exposes per-connection cost: if ops/ms ≪ 100 × single-client rate, server-side state (ConcurrentHashMap on `connectionChannels` / `keyToConnection`) is contended |

### Layer stack overhead

| Benchmark | Layers | Signal |
|---|---|---|
| `LayerStackBenchmark.positionUpdate_framingOnly` | `FramingLayer` | Baseline for layer delta |
| `LayerStackBenchmark.positionUpdate_compressed` | `FramingLayer + CompressionLayer` | `CompressionLayer` currently allocates `byte[]` per direction — expect ≫0 B/op. Regression check for when it's fixed |
| `LayerStackBenchmark.positionUpdate_encrypted` | `FramingLayer + EncryptionLayer` | `Cipher.getInstance` + `doFinal` allocates; expect high B/op until cipher reuse lands |

### Latency

| Benchmark | Mode | Signal |
|---|---|---|
| `LatencyBenchmark.positionUpdate_latency` | `AverageTime + SampleTime` | Mean + p50/p90/p99/p999 from SampleTime. p99/p50 &gt; 10× → jitter (scheduler or GC); p99 close to p50 → pipeline is steady-state |

### Codec microbenches (no I/O, no pipeline)

| Benchmark | What | Signal |
|---|---|---|
| `CodecMicroBenchmark.encodeOnly` | Record → `ByteBuffer` with per-op record allocation | Should be 0 B/op post-warmup (EA scalar-replaces the record) |
| `CodecMicroBenchmark.encodeOnly_cachedRecord` | Record → `ByteBuffer` with cached field record | Pure wire-write cost, no record construction |
| `CodecMicroBenchmark.decodeOnly` | Prebuilt frame → Record in tight loop | Isolates reflective constructor cost / generated-codec dispatch |
| `CodecMicroBenchmark.encodeDecodeRoundtrip` | Encode + decode in-memory | Sanity check: ≈ encode + decode |

### HTTP handler inlining (in-process, no sockets)

| Benchmark | What | Signal |
|---|---|---|
| `HttpLayerBenchmark.http_decodeGet` | GET request → frame | Parse-only CPU cost — should be 0 B/op |
| `HttpLayerBenchmark.http_decodePost` | POST + 256 B body → frame | Compare to GET to see body-copy overhead |
| `HttpLayerBenchmark.http_encodeResponse` | 200 OK text/plain response frame → wire | Fast-path encode |
| `HttpLayerBenchmark.http_roundtripGet` | decode GET + encode 200 OK | Full CPU path a minimal HTTP server runs |

### Broadcast fan-out

| Benchmark | Recipients | Signal |
|---|---|---|
| `BroadcastFanOutBenchmark.broadcast_1` | 1 | Baseline — handler with `Broadcast broadcast` parameter, single-peer send |
| `BroadcastFanOutBenchmark.broadcast_10` | 10 | Should scale ≈1/10 of broadcast_1 |
| `BroadcastFanOutBenchmark.broadcast_100` | 100 | Sub-linear scaling → contention on `sessions.forEachActive` or per-channel `synchronized` block |

B/op proportional to N = per-recipient allocation (should be 0).

### Dispatch microbench

| Benchmark | Shape | Signal |
|---|---|---|
| `ServiceRegistryDispatchBenchmark.dispatch_void` | `(Record, ConnectionId) -> void` | Simplest generated invoker |
| `ServiceRegistryDispatchBenchmark.dispatch_returning` | `(Record, ConnectionId) -> Record` | Response record allocation |
| `ServiceRegistryDispatchBenchmark.dispatch_broadcast` | `(Record, ConnectionId, Broadcast) -> void` | Injectable parameter |

### Profiling helpers (existing)

`AllocTraceBenchmark`, `ProfileBenchmark`, `CpuProfileBenchmark`,
`SessionStoreBenchmark` are micro-slices used to narrow down allocation
sources or CPU hot spots during optimization work.

## Reading numbers

- **`ops/ms`** (thrpt): higher is better. Rough scale in this repo:
    - ≥ 20 k ops/ms — zero-alloc hot path working as intended
    - 5–20 k ops/ms — some per-op work (string encode, pipeline stages)
    - &lt; 2 k ops/ms — likely per-op allocation or syscall (broadcast, layer with memcpy)
- **`B/op`** (`gc.alloc.rate.norm`): target **0**. Anything &gt;0 means
  an allocation happens per message.
- **`gc.count`**: 0 across all forks means no GC triggered during measurement.
  If a benchmark shows 0 B/op but gc.count &gt; 0, setup/teardown allocated
  (usually fine).

## Gotchas

- `forceGC=true` in the gradle config triggers a full GC between iterations
  so the normalized allocation rate is stable.
- Running multiple JMH forks in parallel (e.g. across git worktrees) will
  skew throughput numbers. Prefer a quiescent machine for the canonical
  baseline.
- `Mode.AverageTime` benchmarks report ns/op, not ops/ms — don't compare
  across modes.
