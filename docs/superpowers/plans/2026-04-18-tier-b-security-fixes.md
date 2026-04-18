# Tier-B security + bugfix implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the 9 HIGH-severity fixes from the 2026-04-17 review (design at `docs/superpowers/specs/2026-04-17-tier-b-security-fixes-design.md`) with reproduce-first TDD and zero performance compromise.

**Architecture:** Each fix is an independent commit. Concurrency fixes use VarHandle acquire/release, not coarse synchronization. The two unbounded-map fixes are resolved at the framework level by adding a `Layer.onConnectionClose(long)` callback, not by per-layer caps. All fixes preserve zero-alloc on hot paths.

**Tech Stack:** JDK 26+ (`--enable-preview`), `java.lang.classfile` bytecode generation, `java.lang.invoke.VarHandle`, JUnit 5, JMH.

**Execution order** puts simple isolated fixes first to build momentum, concurrency fixes next, then the framework-level `Layer.onConnectionClose` addition, which unlocks fixes 4/5/6.

---

## Task 1: Fix 8 — ReadBuffer len==1 growth bug

**Files:**
- Modify: `core/src/main/java/jtroop/core/ReadBuffer.java` (the `readUtf8` / growth path at ~line 99 and ~line 124)
- Test: `core/src/test/java/jtroop/core/ReadBufferTest.java` (extend)

- [ ] Step 1: Read current `ReadBuffer.java` and `ReadBufferTest.java` to understand structure and existing test patterns.
- [ ] Step 2: Add a failing test `readUtf8_singleByteString_doesNotOverflow()` and `readUtf8CharSequence_singleByteString_doesNotOverflow()`. Encode one-byte UTF-8 string using WriteBuffer on a direct ByteBuffer. Read back via ReadBuffer. Assert the right value; on current main this throws AIOOBE or corrupts.
- [ ] Step 3: Run the two new tests, confirm they FAIL.
- [ ] Step 4: Fix the growth math — `int newCap = Math.max(Integer.highestOneBit(len - 1) << 1, 2);` at both sites. Preserve zero alloc on happy path.
- [ ] Step 5: Run the two new tests, confirm PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(readbuffer): correct power-of-two growth when len==1 (heap corruption on direct buffers)`.

---

## Task 2: Fix 9 — WriteBuffer silent UTF-8 truncation > 64 KB

**Files:**
- Modify: `core/src/main/java/jtroop/core/WriteBuffer.java` (the `writeUtf8` variants near lines 142, 196)
- Test: `core/src/test/java/jtroop/core/WriteBufferTest.java` (extend)

- [ ] Step 1: Read `WriteBuffer.java` around the `(short) byteLen` sites; identify both heap-backed and direct-buffer paths.
- [ ] Step 2: Add failing tests:
  - `writeUtf8_exactly64KiB_encodesSuccessfully()` — string whose UTF-8 encoding is exactly 65 535 bytes. Should succeed.
  - `writeUtf8_above64KiB_throwsProtocolException()` — 65 536 bytes. On current main silently truncates; after fix throws `ProtocolException`.
- [ ] Step 3: Run the tests, confirm FAIL (oversize case silently succeeds today).
- [ ] Step 4: Add a guard before the length prefix write: `if (byteLen > 0xFFFF) throw new ProtocolException("UTF-8 payload exceeds 64 KB (" + byteLen + ")");`. Apply to all `(short) byteLen` cast sites.
- [ ] Step 5: Run the tests, confirm PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(writebuffer): reject UTF-8 payloads over 64 KB instead of silently truncating`.

---

## Task 3: Fix 1 — Server UDP ConnectionId operator-precedence

**Files:**
- Modify: `core/src/main/java/jtroop/server/Server.java:462`
- Test: `core/src/test/java/jtroop/server/` — new `MultiPeerUdpCollisionTest.java` (or extend existing `UdpIntegrationTest` / `MultiPeerUdpTest` if present)

- [ ] Step 1: Confirm the bug site at `Server.java:462`. Locate the surrounding `handleMultiPeerUdpListener`.
- [ ] Step 2: Add failing test: send two UDP datagrams from addresses whose `hashCode()` bits differ only above bit-12 (e.g., values `4097` and `8193` via mock or deterministic address generation). Assert both datagrams dispatch to distinct `ConnectionId`s.
- [ ] Step 3: Run the test, confirm FAIL (collision on current main).
- [ ] Step 4: Change `remoteAddr.hashCode() & 0x7FFFFFFF % 4096` → `(remoteAddr.hashCode() & 0x7FFFFFFF) % 4096`.
- [ ] Step 5: Run the test, confirm PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(server): parenthesise UDP ConnectionId modulo to avoid 12-bit entropy collapse`.

---

## Task 4: Fix 7 — WebSocketLayer UTF-8 validation on text frames

**Files:**
- Modify: `core/src/main/java/jtroop/pipeline/layers/WebSocketLayer.java` (after demasking, before `decoded.flip()`)
- Test: `core/src/test/java/jtroop/pipeline/` — new `WebSocketLayerUtf8ValidationTest.java`

- [ ] Step 1: Read `WebSocketLayer.java` around lines 100–150 (decode path).
- [ ] Step 2: Add failing tests for each malformed UTF-8 class (overlong `0xC0 0xAF`, surrogate `0xED 0xA0 0x80`, truncated `0xC2`, out-of-range start byte `0xF5`, unexpected continuation `0x80`). All masked text frames. Assert `ProtocolException("invalid UTF-8 in text frame")`. Positive tests at boundaries U+0000, U+007F, U+0080, U+07FF, U+0800, U+FFFF, U+10000, U+10FFFF.
- [ ] Step 3: Run tests, confirm all malformed cases currently PASS the layer (bug) — so the negative tests FAIL.
- [ ] Step 4: Add a private static `validateUtf8(ByteBuffer, int start, int len)` method that state-machine-walks the bytes; throw on invalid. Call it after the demasking loop in `decodeInbound` for opcode text frames. Zero allocation.
- [ ] Step 5: Run tests, confirm all PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(websocket): validate UTF-8 on text-frame payloads per RFC 6455 §5.6`.

---

## Task 5: Fix 3 — EventLoop.stageWriteAndFlush lost-wakeup race

**Files:**
- Modify: `core/src/main/java/jtroop/core/EventLoop.java` (`stageWriteAndFlush`, `flushPendingWrites`, waiting-thread publish/clear)
- Test: `core/src/test/java/jtroop/core/` — new `EventLoopStageWriteFlushRaceTest.java` (or extend `EventLoopStageWriteTest.java`)

- [ ] Step 1: Read `EventLoop.java` lines 220–300 and the flush path around lines 380–420. Note existing VarHandles (`PENDING_WRITE`, etc.) and whether `waitingThreads` has a VarHandle — if not, add one.
- [ ] Step 2: Add a stress test: one writer thread calls `stageWriteAndFlush` in a tight loop for N iterations against a connection whose consumer is only flushing on another thread. Use a deadline; assert all N calls return within the deadline. On current main this can hang.
- [ ] Step 3: Run the test, confirm it FAILS or hangs (kill via deadline assertion).
- [ ] Step 4: Refactor:
  - Add `WAITING_THREADS` VarHandle on the `Thread[]` array if missing.
  - In `stageWriteAndFlush`: `WAITING_THREADS.setRelease(waitingThreads, slot, Thread.currentThread());` BEFORE `stageWrite`.
  - In `flushPendingWrites`: `Thread t = (Thread) WAITING_THREADS.getAndSet(waitingThreads, slot, null);` after successful flush; `if (t != null) LockSupport.unpark(t);`.
  - Replace the `Thread.onSpinWait()` loop in the caller with `LockSupport.park()` + recheck `PENDING_WRITE.getAcquire()`; unpark-driven, no spin.
  - Handle spurious wakeup (standard park-loop pattern).
- [ ] Step 5: Run the test, confirm PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(eventloop): publish stageWriteAndFlush waiter before stageWrite; park instead of spin`.

---

## Task 6: Fix 2 — EventLoop.submit ring stale read + remove setupOverflow

**Files:**
- Modify: `core/src/main/java/jtroop/core/EventLoop.java` (submit ring, setupOverflow removal, consumer drain)
- Test: `core/src/test/java/jtroop/core/` — new `EventLoopSubmitSaturationTest.java` (or extend `EventLoopBackPressureTest.java`)

- [ ] Step 1: Audit every `eventLoop.submit(...)` call site in `core/src/main`. For each, assess whether bounded blocking is acceptable. Results inform whether a `trySubmit()` sibling is needed.
- [ ] Step 2: Add failing test: one producer thread fires `N = 100 × SETUP_RING_CAPACITY` submits while the consumer is paused briefly; allocation measured with `ThreadMXBean.getThreadAllocatedBytes()` around the producer loop should stay under a small constant budget (e.g., 1 KB total, accounting for warmup noise). Also assert all tasks eventually run when consumer resumes.
- [ ] Step 3: Run test, confirm FAILS on current main (CLQ allocation scales linearly with excess submissions).
- [ ] Step 4: Refactor:
  - Replace plain long read of `setupReadIndex` with `VarHandle.getAcquire()`; consumer updates via `setRelease()`.
  - Remove the `setupOverflow` field, its adds, and its drains.
  - On full-ring CAS-fail: 32-spin with `Thread.onSpinWait()`, then `LockSupport.parkNanos(eventLoop.submitWaiter, 1_000_000L)` loop, re-reading `setupReadIndex` via acquire.
  - Consumer: after draining a batch, if any producer is parked on the submit-waiter slot, `LockSupport.unpark` it.
  - If audit (Step 1) found a call site that can't block, add a public `trySubmit(Runnable) -> boolean` that returns false on full.
- [ ] Step 5: Run test, confirm PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(eventloop): bounded submit-ring backpressure; drop setupOverflow heap escape hatch`.

---

## Task 7: Framework — add `Layer.onConnectionClose(long)` callback

**Files:**
- Modify: `core/src/main/java/jtroop/pipeline/Layer.java` (add default method)
- Modify: `core/src/main/java/jtroop/pipeline/Pipeline.java` (delegate in reverse order)
- Modify: `core/src/main/java/jtroop/generate/FusedPipelineGenerator.java` (emit `onConnectionClose` dispatch)
- Modify: `core/src/main/java/jtroop/generate/FusedReceiverGenerator.java` (same)
- Modify: `core/src/main/java/jtroop/server/Server.java` (call from every close site)
- Modify: `core/src/main/java/jtroop/client/Client.java` (call from every close site)
- Test: `core/src/test/java/jtroop/pipeline/` — new `LayerOnConnectionCloseTest.java`

This task is infrastructure — it doesn't itself close a specific bug; it enables Tasks 8, 9, 10.

- [ ] Step 1: Read `Layer.java`, `Pipeline.java`, generators to understand dispatch and generation patterns.
- [ ] Step 2: Add failing test: a fake `Layer` that counts `onConnectionClose` invocations. Wire into a `Pipeline`. Simulate a close via `pipeline.onConnectionClose(123L)`. Assert counter == 1 with correct `connId`. Then test via `FusedPipeline` and `FusedReceiver`.
- [ ] Step 3: Run test, confirm FAIL (method doesn't exist).
- [ ] Step 4: Implement:
  - `Layer.onConnectionClose(long connectionId)` default no-op.
  - `Pipeline.onConnectionClose(long)` iterates `layers` array (reverse order to mirror close ordering).
  - `FusedPipelineGenerator`: emit a method `onConnectionClose(long)` in the generated class that calls `this.layer0.onConnectionClose(connId); this.layer1.onConnectionClose(connId); ...`. Monomorphic `invokevirtual` per layer.
  - `FusedReceiverGenerator`: same.
- [ ] Step 5: Run test, confirm PASS.
- [ ] Step 6: Wire into `Server`:
  - After normal connection close (e.g., `closeConnectionInternal`): `pipeline.onConnectionClose(connId.id());`.
  - After `rejectConnection`: same.
  - On shutdown drain for each active connection.
- [ ] Step 7: Wire into `Client`:
  - After `closeConnection(connType)`, `close()`, reconnect paths.
- [ ] Step 8: Run full `:core:test`. If any previously-passing test now breaks because a layer expected not to see close callbacks, investigate — likely a bug in the test, since the default is no-op.
- [ ] Step 9: Commit: `feat(layer): add onConnectionClose lifecycle callback plumbed through Pipeline + generators`.

---

## Task 8: Fix 4 — AllowListLayer eviction via onConnectionClose

**Files:**
- Modify: `core/src/main/java/jtroop/pipeline/layers/AllowListLayer.java` (override `onConnectionClose`; remove `forget(long)`)
- Modify: any caller of `AllowListLayer.forget` (call-site audit in Step 1)
- Test: `core/src/test/java/jtroop/pipeline/` — new `AllowListLayerLifecycleTest.java` or extend existing `AllowListLayerTest.java`

- [ ] Step 1: grep for `AllowListLayer.forget` across the codebase. Update or delete callers — they become redundant.
- [ ] Step 2: Add failing test: allocate an `AllowListLayer`, register `K = 10 000` distinct connection decisions, call `onConnectionClose(connId)` for each, assert the internal map is empty. (Use a package-private accessor for the map size, or reflection.)
- [ ] Step 3: Run, confirm FAIL (forget is manual).
- [ ] Step 4: Implement `onConnectionClose(long)` override that removes the entry. Remove the public `forget(long)` method.
- [ ] Step 5: Run test, confirm PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(allowlist): auto-evict per-connection decision on Layer.onConnectionClose`.

---

## Task 9: Fix 5 — RateLimitLayer eviction via onConnectionClose

**Files:**
- Modify: `core/src/main/java/jtroop/pipeline/layers/RateLimitLayer.java` (override `onConnectionClose`; remove `forget(long)`)
- Test: `core/src/test/java/jtroop/pipeline/` — extend existing `RateLimitLayerTest.java`

- [ ] Step 1: grep for `RateLimitLayer.forget` across the codebase. Update or delete callers.
- [ ] Step 2: Failing test — same pattern as Task 8 but for `RateLimitLayer`'s internal map(s).
- [ ] Step 3: Confirm FAIL.
- [ ] Step 4: Implement `onConnectionClose(long)` override; remove `forget(long)`.
- [ ] Step 5: Confirm PASS, run full suite.
- [ ] Step 6: Commit: `fix(ratelimit): auto-evict per-connection token-bucket state on Layer.onConnectionClose`.

---

## Task 10: Fix 6 — AckLayer retransmit cap with exponential backoff + LayerContext.requestClose

**Files:**
- Modify: `core/src/main/java/jtroop/pipeline/LayerContext.java` (add `requestClose(String reason)`)
- Modify: `core/src/main/java/jtroop/pipeline/layers/AckLayer.java` (cap + backoff + requestClose)
- Modify: `core/src/main/java/jtroop/server/Server.java` (respond to `requestClose` after decode/encode)
- Modify: `core/src/main/java/jtroop/client/Client.java` (same)
- Test: `core/src/test/java/jtroop/pipeline/AckLayerTest.java` (extend)

- [ ] Step 1: Read `LayerContext.java` and `AckLayer.java`. Identify current retransmit loop and state storage (slot arrays).
- [ ] Step 2: Failing test `ackLayer_dropsPacketAfterMaxRetries_requestsClose`:
  - Send N packets, no acks.
  - Advance virtual clock past `MAX_RETRIES × baseTimeout × 2^5` (exponential backoff cap).
  - Assert exactly `MAX_RETRIES` retransmissions per packet, slot released, and `requestClose` was called on the LayerContext with reason `"ack retry limit exceeded"`.
- [ ] Step 3: Confirm FAIL (infinite retries today).
- [ ] Step 4: Implement:
  - Add `requestClose(String reason)` to `LayerContext` as a default no-op; real implementations in `Server`/`Client` flag the connection for close after the current cycle, then close with `ProtocolException(reason)`.
  - Add primitive parallel arrays in `AckLayer`: `byte[] retriesAtSlot`, `long[] nextRetryAtSlot`; initialize in `attach` / `prepare`.
  - Rewrite `writeRetransmits()`: on timeout, if `retries >= MAX_RETRIES`, release slot + `ctx.requestClose("ack retry limit exceeded")`, else retransmit + `retries++` + `nextRetry = now + (baseTimeoutNanos << min(retries, 6))`.
  - Override `onConnectionClose` to reset the arrays (cheap, cold path).
- [ ] Step 5: Confirm PASS. Run full `:core:test`.
- [ ] Step 6: Commit: `fix(ack): cap retransmits with exponential backoff; request close on exhaustion`.

---

## Final verification (runs after Task 10)

- [ ] Run `./gradlew :core:test` once more end-to-end — expect green.
- [ ] Run `NetGameBenchmark.positionUpdate` and `NetGameBenchmark.chatMessage` quick-mode (reduced iterations if needed for time); compare B/op and ops/ms to the README headline numbers. Regression > 3% pauses before declaring complete.
- [ ] Final commit if verification surfaces anything; otherwise stop.

---

## Risk reminders

- Concurrency fixes (Tasks 5, 6) are the highest-risk. Take time on the failing-test design — a weak test here lets a race survive.
- Task 7 is wide-touch but shallow — most modifications are one-line delegation calls.
- Task 10's `requestClose` addition is the minimum LayerContext expansion needed; do not expand the LayerContext API beyond that in this pass.
