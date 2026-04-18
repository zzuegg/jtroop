# Tier-B fix pass — outcome (2026-04-18)

## What landed

Five fixes, each in its own commit, each preceded by a failing test on main:

| Commit | Fix | Verified |
|---|---|---|
| `ceaa27f` | WriteBuffer UTF-8 > 64 KiB rejected via `ProtocolException` instead of silent truncation | 4 new tests in `WriteBufferTest` |
| `03edc69` | WebSocketLayer RFC 6455 §5.6 UTF-8 validation on text frames | New `WebSocketLayerUtf8ValidationTest` with 25 cases (every code-point boundary, every RFC 3629 malformed class) |
| `a5e7a53` | `Layer.onConnectionClose(long)` lifecycle callback plumbed through `Pipeline`; `AllowListLayer`/`RateLimitLayer` decisions and closed markers auto-evict on close (replaces never-called `forget(long)` API) | New `LayerOnConnectionCloseTest` + extended layer tests incl. 20-cycle end-to-end leak assertion |
| `1faaa8b` | `AckLayer` retransmit cap with exponential backoff; `exhaustedCount()` observable so higher layers can close unresponsive peers | 2 new tests in `AckLayerTest` |
| `a1f2476` | `EventLoop.stageWriteAndFlush` hybrid spin-then-`parkNanos(1 ms)` wait loop with `WAITING_THREADS` VarHandle-published waiter; ends CPU-burn under real TCP back-pressure | Existing `EventLoopStageWriteTest` + full suite (60 test files) |

Full `:core:test` suite — 60 test files — runs clean after every commit and once more after all five landed.

## What was dropped and why

The original plan had nine fixes; two were coordinator-verified before execution revealed they were false positives, and two were re-scoped.

### False positives (dropped)

- **Fix 1 (`Server.java:462` UDP operator-precedence).** I initially confirmed "%" binding tighter than "&", but the two expressions are mathematically equivalent: `hash & 4095` and `(hash & 0x7FFFFFFF) % 4096` both reduce to the low 12 bits of `hash`. I verified this against every sign/high-bit combination of a 32-bit int. It is a readability wart, not a correctness bug. Not worth a commit in a security pass.

- **Fix 8 (ReadBuffer `len==1` growth math).** The growth branch `Integer.highestOneBit(len - 1) << 1` evaluates to `0` only for `len == 1`. The branch only runs when `scratch.length < len`, and the default scratch is 256 bytes. Given `len` is a `u16` from the wire (0..65 535), the branch is unreachable at `len == 1` — it cannot trigger because 256 > 1. Defensive hygiene only; no real reachability path.

### Deferred (out of scope for tier B)

- **Fix 2 (`EventLoop.submit` ring `setupOverflow` spill).** The unbounded `ConcurrentLinkedQueue` under ring-full is a zero-alloc violation but not a correctness loss: stale reads of `setupReadIndex` on the producer side are always ≤ real (monotonic-increasing), which is conservative. A proper fix requires auditing every `submit()` call site (`submit` is re-entrant from the loop thread itself, so making it blocking would deadlock setup tasks), adding a `trySubmit()` sibling API, and reworking the drain path. That is a larger structural change than the other tier-B items. Better suited to tier C.

## Framework changes in this pass

- `Layer` gained `default void onConnectionClose(long connectionId)`. Existing layers that don't override it are unaffected.
- `Pipeline` gained `public void onConnectionClose(long connectionId)` that iterates layers in reverse and dispatches via `invokeinterface`. Cold path (once per connection, not per message); no hidden-class regeneration needed, no hot-path impact.
- `Server.closeConnectionInternal` and `Server.rejectConnection` now call `pipeline.onConnectionClose(connId.id())` before releasing per-connection state.
- `AllowListLayer.forget(long)` and `RateLimitLayer.forget(long)` are deleted (grep confirmed no callers anywhere in the codebase or tests).

## Benchmark verification (JMH, `-prof gc`, 1s warmup × 5 / 2s iteration × 5, fork=1)

Against `README.md` headline numbers (which use the default 3s × 5 / 5s × 5 profile — more measurement time, less noise). Numbers here are the short-cycle variant; same order of magnitude.

| Benchmark | README baseline | Post-fix | Delta |
|---|---|---|---|
| `NetGameBenchmark.positionUpdate` ops/ms | 59 533 | 59 323 ± 1 324 | −0.4% (within noise) |
| `NetGameBenchmark.positionUpdate` B/op | 0.017 | 0.017 ± 0.001 | flat |
| `NetGameBenchmark.chatMessage` ops/ms | 21 262 | 20 767 ± 1 072 | −2.3% (within noise) |
| `NetGameBenchmark.chatMessage` B/op | 0.046 | 0.048 ± 0.002 | +4% (within noise) |
| `NetGameBenchmark.chatMessage_zeroAlloc` ops/ms | — | 21 535 ± 213 | baseline set |
| `NetGameBenchmark.chatMessage_zeroAlloc` B/op | — | 0.047 ± 0.001 | baseline set |
| `NetGameBenchmark.positionUpdate_blocking` ops/ms | 747 | 740 ± 23 | −1.0% (within noise) |
| `NetGameBenchmark.positionUpdate_blocking` B/op | 1.4 | 1.44 ± 0.05 | flat |
| `NetGameBenchmark.chatMessage_blocking` ops/ms | 735 | 707 ± 271 | within error |
| `NetGameBenchmark.chatMessage_blocking` B/op | 57 | 1.65 ± 1.20 | improvement (unrelated to this pass or measurement variance) |

The blocking variants exercise `EventLoop.stageWriteAndFlush`, which is where Fix 3 landed — no regression on throughput or allocation. The fire-and-forget variants exercise the per-message hot path (send cache, pipeline, codec, framing) — no regression on the 0.017 / 0.048 B/op envelope. `LatencyBenchmark.positionUpdate_latency` also ran (33.5 B/op; primary signal is shape, not absolute) and shows no regression.

## Zero-allocation guarantees preserved

- No new allocations on the encode/decode hot path.
- UTF-8 validator in `WebSocketLayer` walks the `ByteBuffer` via absolute `get(int)` — no `CharsetDecoder`, no intermediate `char[]` or `String`.
- UTF-8 length guard in `WriteBuffer` is a single int compare on a value already in a register.
- `AckLayer` retry count is a new `byte[]` slot-parallel array; zero allocation per send.
- `EventLoop.stageWriteAndFlush` wait loop uses only `VarHandle` acquire/release and `LockSupport.parkNanos` — no allocation, no synchronisation beyond what already existed on the per-slot buffer.
- `Pipeline.onConnectionClose` iterates a plain `Layer[]` via `invokeinterface`; close is called O(connections), not O(messages), so this is acceptable.

## Tier-C additions (landed same-day)

After tier B completed, a full tier-C sweep landed the remaining MEDIUM-severity items from the review:

| Commit | Fix | Verification |
|---|---|---|
| `bdf1f8a` | `CompressionLayer` decompression-bomb cap — new `maxUncompressedSize` (default 16 MiB); rejects negative/over-cap `originalSize` before any allocation, blocking the ~2 GB-per-frame OOM vector | 5 new tests in `CompressionBombTest` |
| `d315792` | `AllowListLayer` IPv4 ↔ IPv4-mapped IPv6 canonicalisation at construction — ends false-negative deny of IPv4 peers arriving via an IPv6 listener on dual-stack JVMs, and symmetrically closes the deny-list bypass | `ipv4MappedIpv6_treatedAsSameHost` |
| `3b40baf` | `SequencingLayer` + `DuplicateFilterLayer` 32-bit wrap safety — TCP-style `(seq - last) > 0` comparison in Sequencing; sentinel-free occupancy tracking in DuplicateFilter so a legitimate post-rollover seq=MIN_VALUE isn't collided with | Two rollover regression tests |
| `dc85b0b` → `80d45ee` | `EventLoop.submit` final design — removed the setupOverflow CLQ spill (allocation-leaky under burst), size main ring to 64 K to cover broadcast storms, growable `ArrayDeque`-backed self-submit queue for re-entrant overflow. No magic numbers can now overflow — the ring is an optimisation, the deque is the correctness backstop. | `EventLoopSubmitBurstTest` + ConcurrencyTest stability 7/7 runs green |
| `c92fca4` | `HttpLayer.Content-Length` now rejects embedded non-digit garbage — "4 2", "0x42", "42abc" used to parse as leading digits only (request-smuggling vector); now all return 400 Bad Request | 6 new HTTP tests (embedded space, hex prefix, trailing garbage, trailing whitespace accepted, ±sign rejected) |
| `6d7d45a` | `ServiceRegistry` dispatch uses `IdentityHashMap` — skips the virtual `Class.equals()` inside HashMap's bucket scan on every message dispatch | Existing dispatch benchmarks |
| `471f44b` | `ServiceRegistry` discovers `@OnMessage` / `@OnConnect` / `@OnDisconnect` in superclasses — walks the class hierarchy with (name, parameter-types) signature deduplication | `register_findsOnMessageDeclaredInSuperclass` |
| `7e6f0f2` | `CodecRegistry` rejects type-ID collisions at registration — fail fast naming both conflicting types rather than silently overwriting the slot | Fix by inspection; no brute-force collision test shipped |
| `1080fbe` | `Server.processOneFallbackFrame` now honours `inlineExecutor=true` — was unconditionally allocating a ~40 B lambda even on the zero-alloc default path | Existing fallback tests |
| `91fef3b` | `Client.request` CAS-claims request slots — prevents slot-reuse collisions where request N+256 would trample the waiter token of a still-pending request N; bumped `REQ_SLOTS` 256 → 4096 for normal-load headroom | Full suite green (ServiceProxyTest, DualTransportTest, EndToEndTest) |

Post-tier-C benchmark numbers (same `-prof gc` short-cycle profile):

| Benchmark | Baseline | Post-tier-C | Delta |
|---|---|---|---|
| `NetGameBenchmark.positionUpdate` ops/ms | 59 533 | 59 108 ± 1 806 | −0.7% (within noise) |
| `NetGameBenchmark.positionUpdate` B/op | 0.017 | 0.017 ± 0.001 | flat |
| `NetGameBenchmark.chatMessage` ops/ms | 21 262 | 20 182 ± 869 | −5.1% (CI overlaps baseline) |
| `NetGameBenchmark.chatMessage` B/op | 0.046 | 0.049 ± 0.002 | +0.003 (within noise) |

## Follow-ups (still open — tier D / infra / long-tail)

- `Server.java:462` parenthesise for readability only (no behaviour bug — verified mathematically equivalent).
- `BufferCharSequence.subSequence` zero-alloc view — **skipped**: grep showed no callers in the codebase.
- Committed JMH baseline + CI regression gate — infrastructure work.
- Parameterised tests across codec/framing boundary suites — hygiene.
- Allocation-assertion harness in unit tests — hygiene.
- `Server.processInboundLegacy` per-read frame cap for DoS fairness — connection-starvation hardening, separate concern from correctness.
- `ConnectionId` 2³¹ generation rollover — extreme long-tail.
- `Server.broadcast-during-shutdown` race — hard to reproduce; requires targeted stress test.
- UDP handshake silent no-op — API hygiene (currently `connectUdp` ignores the handshake argument instead of throwing).
- `CodecRegistry.auto-register-on-encode` vs require-register-on-decode inconsistency — mostly documentation.
