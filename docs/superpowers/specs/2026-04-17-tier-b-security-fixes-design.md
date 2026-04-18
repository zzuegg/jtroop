# Tier-B security & bugfix pass — design

**Date:** 2026-04-17
**Scope:** 9 fixes originating from the parallel 10-agent code review recorded in graphiti under `group_id: jtroop-review-20260417`.
**Discipline:** Reproduce-first TDD. Every fix starts with a test that fails on the current `main`, then the minimal correct change, then `green`. Every fix is its own commit.
**Guiding constraint:** Never sacrifice performance for an easy solution — always implement the correct, best-performing fix even if the diff grows or touches the framework.

---

## Scope

Nine independent fixes. Each one includes its own reproduction test and lands in its own commit.

| # | File(s) | Bug class | Severity |
|---|---|---|---|
| 1 | `server/Server.java:462` | operator-precedence UDP ConnectionId collision | HIGH (verified) |
| 2 | `core/EventLoop.java` (submit ring) | stale read → unbounded `setupOverflow` spill | HIGH |
| 3 | `core/EventLoop.java` (stageWriteAndFlush) | lost-wakeup race on waiter publish | HIGH |
| 4 | `pipeline/layers/AllowListLayer.java` + framework | unbounded per-connection decision cache | HIGH |
| 5 | `pipeline/layers/RateLimitLayer.java` + framework | unbounded closed-connection tracking | HIGH |
| 6 | `pipeline/layers/AckLayer.java` | no retransmit cap or backoff | HIGH |
| 7 | `pipeline/layers/WebSocketLayer.java` | text frames not UTF-8 validated | HIGH |
| 8 | `core/ReadBuffer.java` | power-of-two growth bug at `len == 1` | HIGH |
| 9 | `core/WriteBuffer.java` | silent UTF-8 truncation above 64 KB via `(short) byteLen` | HIGH |

Out of scope for this pass: MEDIUM/LOW findings, TDD-infrastructure work (parameterized tests, allocation-assertion harness, committed JMH baseline, CI gate), and perf quick-wins that aren't tied to a bug.

---

## Fix 1 — Server.java:462 operator precedence

**Root cause.** Java binds `%` tighter than `&`, so `remoteAddr.hashCode() & 0x7FFFFFFF % 4096` evaluates as `hashCode() & (0x7FFFFFFF % 4096)` = `hashCode() & 4095`. Only 12 bits of entropy survive, and many distinct UDP peers collide onto the same synthetic ConnectionId.

**Fix.** Parenthesise: `ConnectionId.of((remoteAddr.hashCode() & 0x7FFFFFFF) % 4096, 1)`.

**Test.** Extend `UdpIntegrationTest` (or a new `Server.multiPeerUdpCollisionTest`): feed two datagrams from two distinct InetSocketAddresses whose hashCodes differ only in bits above bit-12, assert the two arrive under distinct `ConnectionId`s. On current `main` this test fails because they collide onto the same id.

---

## Fix 2 — EventLoop.submit ring-full stale read + setupOverflow spill

**Root cause.** Producers read `setupReadIndex` as a plain `long`. A stale value may report the ring as full when it isn't; the code then spills to `setupOverflow`, a `ConcurrentLinkedQueue` that is unbounded and heap-allocating. This violates the zero-alloc contract under producer bursts, and a malicious/misbehaving caller flooding `submit()` can exhaust heap.

**Fix (correct, best-performing).**
- Read `setupReadIndex` via `VarHandle.getAcquire()`; consumer updates it via `setRelease()`.
- Remove `setupOverflow` entirely. It is a heap-escape hatch, not a backpressure mechanism.
- On a genuinely-full ring: bounded CAS-spin (e.g., 32 attempts with `Thread.onSpinWait()`), then `LockSupport.parkNanos(...)` looped against the consumer's release cursor until a slot frees. Consumer calls `LockSupport.unpark` on the waiter when draining.

**Behaviour change.** `submit()` becomes blocking (bounded) under saturation. Callers that relied on non-blocking semantics will see the change. Implementation first step: audit every existing `submit()` call site; if any caller cannot tolerate blocking, introduce a `trySubmit()` variant that returns `false` on full-ring. Default `submit()` blocks. This audit is part of the fix-2 commit.

**Test.** New `EventLoopSubmitSaturationTest`: one producer thread fires `N >> SETUP_RING_CAPACITY` submits against a consumer deliberately paused. Assert that heap allocation per submit stays flat (use `ThreadMXBean.getThreadAllocatedBytes` around the loop, with an allocation budget of ~0 B/op after warmup), and that every submitted task eventually completes when the consumer resumes. On current `main` the test would show linear heap growth (CLQ nodes).

---

## Fix 3 — stageWriteAndFlush lost-wakeup

**Root cause.** The caller publishes itself as a waiter (`waitingThreads[slot] = Thread.currentThread()`) *after* calling `stageWrite()`. A concurrent `flushPendingWrites()` between those two steps unparks nothing (the slot still holds the previous waiter or null), then the caller enters its spin-wait and never wakes.

**Fix (correct, best-performing).**
- Publish the waiter via `VarHandle.setRelease(waitingThreads, slot, Thread.currentThread())` **before** `stageWrite`.
- Flush side reads via `getAcquire`, clears with `setRelease(null)`, then `LockSupport.unpark(thread)`.
- Caller waits with `LockSupport.park()` (no spin) in a loop, re-reading the pending-write flag via `getAcquire`. No `Thread.onSpinWait()`, no timeout — unpark is guaranteed by the acquire/release pair.
- Zero allocation. No new fields.

**Test.** New `EventLoopStageWriteAndFlushRaceTest`: tight loop that submits a `stageWriteAndFlush` on one thread while another thread triggers flushes from the consumer side. Assert progress (all N writes acknowledged) within a deadline. On current `main` this test can hang under CPU contention.

---

## Fix 4 & 5 — AllowList / RateLimit unbounded maps (via `Layer.onConnectionClose`)

**Root cause.** Both layers maintain `ConcurrentHashMap<Long, Boolean>` keyed by connection id. Eviction relies on the user wiring a manual `forget(long)` call from an `@OnDisconnect` handler. If that wiring is missed — or if a connection closes through an abnormal path — the maps grow without bound. Rapid connect/disconnect is a memory-DoS vector.

**Fix (correct, structural).** Introduce a lifecycle callback in `Layer`:

```java
public interface Layer {
    // existing methods …

    /** Called once per connection when it closes (normal or abnormal).
     *  Default no-op. Override in layers that hold per-connection state. */
    default void onConnectionClose(long connectionId) {}
}
```

- **`Pipeline`**: adds `onConnectionClose(long)` that iterates layers in reverse and delegates. `FusedPipeline` (generated) adds a direct-dispatch call through each layer's override.
- **`Server`**: calls `pipeline.onConnectionClose(connId)` from every connection-close site — normal close, handshake reject, error close, shutdown drain.
- **`Client`**: same, from `closeConnection(connType)` / `close()` / reconnect.
- **`AllowListLayer`, `RateLimitLayer`**: override `onConnectionClose` to remove the connection from their maps. `forget()` is removed (public API change — it was documented as manual wiring; now automated). Before deletion the implementation audits the codebase and user-facing javadocs for any remaining callers; none are expected outside tests. `AckLayer`, `DuplicateFilterLayer`, `SequencingLayer` also override it to release per-slot state.
- **Generators**: `FusedPipelineGenerator` and `FusedReceiverGenerator` emit a dispatch method for `onConnectionClose` that calls each layer's override via `invokevirtual` (monomorphic, matches the existing encode/decode generation pattern).

**Hot-path cost.** Zero. `onConnectionClose` only fires on close (cold path). Maps that were previously unbounded now shrink deterministically on every disconnect. No allocations added on encode/decode.

**Test.** New `AllowListRateLimitCloseHookTest`: connect 10 000 times, disconnect each, assert that `AllowListLayer` and `RateLimitLayer` internal maps are empty afterward. Use reflection or a package-private accessor. On current `main` these maps retain all 10 000 entries.

Also: extend one of the existing integration tests to assert that a pipeline-level close hook runs before the connection slot is freed — pins the invocation order.

---

## Fix 6 — AckLayer retransmit cap with exponential backoff

**Root cause.** `AckLayer.writeRetransmits()` retransmits any unacked packet whose timeout has elapsed, indefinitely. A silent peer causes continuous retransmission of every in-flight packet every tick, which is an amplification vector and a network-congestion hazard.

**Fix (correct, best-performing).**
- Add per-slot parallel primitive arrays: `byte[] retriesAtSlot`, `long[] nextRetryAtSlot`. No boxed state, SoA layout.
- On send: `retriesAtSlot[slot] = 0; nextRetryAtSlot[slot] = now + baseTimeoutNanos`.
- On ack: release slot (existing path).
- On retransmit tick: for each slot whose `nextRetryAtSlot[slot] <= now`:
  - If `retriesAtSlot[slot] >= MAX_RETRIES` (final, default `10`, constructor-configurable): drop the packet, release the slot, and flag the connection for close. The close-request mechanism is a new cold-path method added to `LayerContext` — `requestClose(String reason)` — that the `Server`/`Client` reads and acts on after the current encode/decode cycle. `Server` closes the connection with `ProtocolException("ack retry limit exceeded")`. Adding `requestClose` to `LayerContext` is part of the fix-6 commit and is used nowhere else in this pass.
  - Else: retransmit; `retriesAtSlot[slot]++`; `nextRetryAtSlot[slot] = now + (baseTimeoutNanos << min(retries, 6))` (capped exponential backoff).

**Hot-path cost.** Per-slot primitive updates only. No heap. The backoff computation uses a bounded shift — no `Math.pow`, no allocation.

**Test.** New `AckLayerRetransmitCapTest`: send N packets, simulate zero acks, step the clock past `MAX_RETRIES` × `baseTimeout`. Assert exactly `MAX_RETRIES` retransmits per packet, then slot released, then connection-close requested. On current `main` the loop retransmits forever.

---

## Fix 7 — WebSocketLayer UTF-8 validation on text frames

**Root cause.** RFC 6455 §5.6 requires text-frame payloads to be valid UTF-8. Current `WebSocketLayer` doesn't validate; malformed UTF-8 propagates upstream where downstream handlers assume valid UTF-8.

**Fix (correct, best-performing).** Hand-rolled zero-allocation UTF-8 validator operating directly on the unmasked `decoded` ByteBuffer slice after demasking. ~30 lines, state-machine or bit-check style. On first invalid byte sequence: throw `ProtocolException("invalid UTF-8 in text frame")`.

No `CharsetDecoder`, no thread-local decoder instance — both allocate on cold paths and add indirection. A direct byte-level validator is tighter, allocation-free, and matches the RFC tables exactly.

Validation runs only on text frames (opcode 0x1), after demasking, before `decoded.flip()`.

**Test.** New `WebSocketLayerUtf8ValidationTest`: feed a masked text frame with each of the RFC 6455 §4.3 / §6 malformed-UTF-8 cases (overlong, surrogate, truncated, out-of-range). Assert `ProtocolException`. Positive case: every valid UTF-8 sequence (ASCII, 2-byte, 3-byte, 4-byte, boundary code points `U+0080`, `U+0800`, `U+10000`, `U+10FFFF`) decodes.

---

## Fix 8 — ReadBuffer len==1 power-of-two growth

**Root cause.** `int newCap = Integer.highestOneBit(len - 1) << 1;` returns `0` when `len == 1` (because `highestOneBit(0) == 0`). A subsequent `buf.get(scratch, 0, len)` writes past the allocated array.

**Fix.** `int newCap = Math.max(Integer.highestOneBit(len - 1) << 1, 2);` — or more simply, short-circuit `if (len <= 1) newCap = 2;`. Zero performance impact.

**Test.** Extend `ReadBufferTest` with a `readUtf8` / `readUtf8CharSequence` at `len == 1` on a direct buffer. Current `main` throws `ArrayIndexOutOfBoundsException` or silently corrupts on direct buffers.

---

## Fix 9 — WriteBuffer UTF-8 > 64 KB silent truncation

**Root cause.** `writeUtf8` writes a 2-byte unsigned length prefix via `(short) byteLen`. A String whose UTF-8 encoding exceeds 65535 bytes silently truncates.

**Fix.** Before writing the length prefix, check `if (byteLen > 0xFFFF) throw new ProtocolException("UTF-8 payload exceeds 64 KB (" + byteLen + ")");`. Zero performance impact on the normal path — single compare on a value already in a register.

**Test.** Extend `WriteBufferTest` with a ~16 384-surrogate-pair string (encodes to > 65 535 bytes) and assert `ProtocolException`. Add a negative test at `byteLen == 0xFFFF` to confirm the boundary is inclusive and still encodes.

---

## Invariants preserved

- **Zero-allocation hot path.** Every fix either adds zero hot-path allocation or runs on a cold path (connection close, handshake reject). `onConnectionClose` callback fires only on close.
- **Monomorphic dispatch.** The new `Layer.onConnectionClose` default method is overridden by sealed/final layer implementations; generators emit `invokevirtual` through the hidden class. No new interface megamorphism.
- **No new public API besides `Layer.onConnectionClose`.** `AllowListLayer.forget` / `RateLimitLayer.forget` are deleted (previously public; their docs flagged them as "call from @OnDisconnect" — this is now automatic).
- **No backwards-compatibility shims.** `forget()` is gone, not deprecated.

---

## Verification order

1. Run the full `./gradlew :core:test` baseline on current `main`, note any pre-existing failures.
2. For each fix 1…9 in order: write failing test, confirm red, implement, confirm green, run the full `core` test suite, commit.
3. After all fixes: run the full suite once more end-to-end.
4. Run `NetGameBenchmark.positionUpdate` and `NetGameBenchmark.chatMessage` to confirm no perf regression on the zero-alloc hot path. Compare against the numbers in `README.md` (`positionUpdate` 0.021 B/op, 45.9K ops/ms; `chatMessage` 0.046 B/op). A regression of more than ±3% pauses the work and triggers an investigation before continuing.

---

## Risk matrix

| Fix | Risk | Mitigation |
|---|---|---|
| 2, 3 (EventLoop) | Concurrency changes — easy to introduce new races | Reproduce-first TDD with stress test; VarHandle semantics are standard; no new shared state |
| 4–5 (`onConnectionClose`) | Framework API expansion — touches Layer, Pipeline, Server, Client, two generators | Structural change, but the pattern mirrors the existing `@OnDisconnect` at the service layer. Integration tests exercise the plumbing. |
| 6 (AckLayer cap) | Behaviour change — previously silent-peer scenarios now close the connection | New semantic is documented in commit message and javadoc; existing tests updated to reflect the cap |
| 7 (WS UTF-8) | Hand-rolled validator — easy to get UTF-8 rules wrong | Test against the full RFC 6455 §6 malformed-UTF-8 table plus every valid-code-point boundary |
| 1, 8, 9 | Low — math / parenthesis fixes | Short enough that test coverage is conclusive |

---

## Out of scope (tracked for future passes)

- MEDIUM findings (CompressionLayer decompression-bomb cap, Sequencing/DuplicateFilter 32-bit rollover, IPv4-mapped IPv6 AllowList bypass, `processOneFallbackFrame` lambda regression, handshake decode-exception state reset, ServiceRegistry inheritance + duplicate-registration divergence, Client request-slot timeout race, BufferCharSequence malformed-UTF-8 bounds, WriteBuffer heap-backed path overflow).
- Performance quick-wins that aren't security bugs (BufferCharSequence.subSequence toString, WriteBuffer three-way instanceof, identity-map ServiceRegistry, FusedReceiver method-split at many bindings).
- TDD and benchmark infrastructure (parameterized tests, allocation-assertion harness, committed JMH baseline, CI regression gate, 1K+ client scaling benchmarks).
