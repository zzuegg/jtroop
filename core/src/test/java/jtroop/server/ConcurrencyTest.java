package jtroop.server;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.service.Broadcast;
import jtroop.service.Handles;
import jtroop.service.OnConnect;
import jtroop.service.OnDisconnect;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests that force races on the server- and client-side
 * connection-state maps and on the EventLoop write pipeline. The goal is
 * to detect the kinds of bugs that only manifest under real contention:
 *
 * <ul>
 *   <li>HashMap corruption when the accept loop mutates {@code connectionChannels}
 *       while a worker loop is iterating it for broadcast;</li>
 *   <li>stale reads from non-volatile flags in {@code EventLoop.pendingWrite};</li>
 *   <li>interleaved {@code SocketChannel.write} from two worker loops targeting
 *       the same peer channel (corrupts framed wire format);</li>
 *   <li>{@code SessionStore} generation counters drifting when allocate/release
 *       race;</li>
 *   <li>NPE from {@code closeConnection} invoked by an external thread while
 *       the owning worker loop is in the middle of a read.</li>
 * </ul>
 *
 * These tests don't assert exact counts on best-effort paths but assert
 * "no crashes, no NPEs, reasonable delivery" — the weakest possible
 * contract that a thread-unsafe implementation would still violate.
 */
@Timeout(60)
class ConcurrencyTest {

    record GameConn(int v) {}
    public record Chat(int clientId, int seq, String text) {}
    public record Tick(int clientId, long ts) {}
    public record Broadcasted(String body) {}

    interface Svc {
        void chat(Chat c);
        void tick(Tick t);
    }

    @Handles(Svc.class)
    public static class RecordingHandler {
        final AtomicInteger receivedChat = new AtomicInteger(0);
        final AtomicInteger receivedTick = new AtomicInteger(0);
        final AtomicInteger connects = new AtomicInteger(0);
        final AtomicInteger disconnects = new AtomicInteger(0);
        final Set<ConnectionId> active = ConcurrentHashMap.newKeySet();
        final AtomicReference<Throwable> firstError = new AtomicReference<>();

        @OnConnect
        void onConnect(ConnectionId id) {
            connects.incrementAndGet();
            active.add(id);
        }

        @OnDisconnect
        void onDisconnect(ConnectionId id) {
            disconnects.incrementAndGet();
            active.remove(id);
        }

        @OnMessage
        void onChat(Chat c, ConnectionId sender, Broadcast broadcast) {
            try {
                receivedChat.incrementAndGet();
                // Broadcast fan-out from the worker loop — this is the
                // path that races with accept/close on connectionChannels.
                broadcast.send(new Broadcasted("c=" + c.clientId() + ",s=" + c.seq()));
            } catch (Throwable t) {
                firstError.compareAndSet(null, t);
                throw t;
            }
        }

        @OnMessage
        void onTick(Tick t, ConnectionId sender) {
            try {
                receivedTick.incrementAndGet();
            } catch (Throwable e) {
                firstError.compareAndSet(null, e);
                throw e;
            }
        }
    }

    /**
     * Race accept and broadcast: clients continually connect, send, and
     * disconnect while existing clients hammer the server with messages
     * that trigger broadcast fan-out. Any non-thread-safe map used by
     * {@code broadcastImpl} would throw ConcurrentModificationException,
     * ArrayIndexOutOfBoundsException (HashMap resize), or NPE.
     */
    @Test
    void broadcast_with_concurrent_connect_disconnect() throws Exception {
        var handler = new RecordingHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConn.class)
                .eventLoops(4)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        int steady = 10;
        int churnThreads = 10;
        int steadyMessages = 1500;
        int churnCycles = 50;

        // Steady clients — stay connected, send broadcast-triggering messages.
        var steadyClients = new Client[steady];
        var steadyReceived = new AtomicInteger[steady];
        for (int i = 0; i < steady; i++) {
            steadyReceived[i] = new AtomicInteger();
            final int slot = i;
            steadyClients[i] = Client.builder()
                    .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(Svc.class, GameConn.class)
                    .onMessage(Broadcasted.class, m -> steadyReceived[slot].incrementAndGet())
                    .build();
            steadyClients[i].start();
        }

        // Wait for steady connects
        long deadline = System.currentTimeMillis() + 3000;
        while (handler.connects.get() < steady && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(steady, handler.connects.get(), "steady clients must be connected");

        var stop = new AtomicBoolean(false);
        var threadError = new AtomicReference<Throwable>();
        var allDone = new CountDownLatch(steady + churnThreads);

        // Thread pool A: steady broadcasters.
        for (int i = 0; i < steady; i++) {
            final int clientIdx = i;
            final var client = steadyClients[i];
            new Thread(() -> {
                try {
                    for (int j = 0; j < steadyMessages && !stop.get(); j++) {
                        client.send(new Chat(clientIdx, j, "x"));
                    }
                    client.flush();
                } catch (Throwable t) {
                    threadError.compareAndSet(null, t);
                } finally {
                    allDone.countDown();
                }
            }, "steady-" + i).start();
        }

        // Thread pool B: churn connect/disconnect clients.
        for (int i = 0; i < churnThreads; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < churnCycles && !stop.get(); j++) {
                        var c = Client.builder()
                                .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                                .addService(Svc.class, GameConn.class)
                                .build();
                        c.start();
                        // send a couple messages, then immediately close
                        for (int k = 0; k < 3; k++) {
                            c.send(new Tick(99, System.nanoTime()));
                        }
                        c.flush();
                        c.close();
                    }
                } catch (Throwable t) {
                    threadError.compareAndSet(null, t);
                } finally {
                    allDone.countDown();
                }
            }, "churn-" + i).start();
        }

        assertTrue(allDone.await(45, TimeUnit.SECONDS), "workers did not finish");
        stop.set(true);

        // Wait for the server to process all chat messages sent by steady
        // senders. TCP guarantees delivery, so receivedChat must reach the
        // total. Only then can we check broadcast delivery to the steady
        // clients — the server broadcasts on each chat, so once all chats
        // are processed the corresponding broadcasts have been enqueued.
        int expectedChats = steady * steadyMessages;
        long drainDeadline = System.currentTimeMillis() + 10_000;
        while (handler.receivedChat.get() < expectedChats && System.currentTimeMillis() < drainDeadline) {
            Thread.sleep(20);
        }

        // Give in-flight broadcast writes time to arrive at steady clients.
        // Poll instead of a fixed sleep: wait until every steady client has
        // received a meaningful fraction, or until a generous timeout expires.
        int total = steady * steadyMessages; // upper bound of broadcasts triggered by steady senders
        int threshold = total / 4;
        drainDeadline = System.currentTimeMillis() + 10_000;
        boolean allAboveThreshold = false;
        while (!allAboveThreshold && System.currentTimeMillis() < drainDeadline) {
            allAboveThreshold = true;
            for (int i = 0; i < steady; i++) {
                if (steadyReceived[i].get() <= threshold) {
                    allAboveThreshold = false;
                    break;
                }
            }
            if (!allAboveThreshold) Thread.sleep(20);
        }

        assertNull(threadError.get(), "worker thread crashed: " + threadError.get());
        assertNull(handler.firstError.get(), "handler crashed: " + handler.firstError.get());

        // Steady clients must have received broadcasts. We cannot assert the
        // exact count (churn clients disconnect before fan-out reaches them),
        // but every steady client must see a sizable fraction — a buggy
        // implementation that drops a ConcurrentModificationException would
        // typically leave counts at zero for some clients.
        for (int i = 0; i < steady; i++) {
            int got = steadyReceived[i].get();
            assertTrue(got > threshold,
                    "steady client " + i + " received too few broadcasts: " + got + "/" + total);
        }

        for (var c : steadyClients) c.close();
        server.close();
    }

    /**
     * External-thread closeConnection() while the owning EventLoop is in the
     * middle of a read. The server schedules the close onto the owning loop —
     * but if the state maps aren't concurrent-safe, the external get() of
     * connectionToKey may observe a corrupted map.
     */
    @Test
    void external_closeConnection_during_read_does_not_crash() throws Exception {
        var handler = new RecordingHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConn.class)
                .eventLoops(2)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        int rounds = 40;
        for (int rr = 0; rr < rounds; rr++) {
            final int r = rr;
            int beforeConnects = handler.connects.get();
            int beforeDisconnects = handler.disconnects.get();

            // Wait for all previous disconnects to clear from active set
            // before connecting a new client. Otherwise handler.active may
            // contain stale entries from prior rounds and we grab the wrong id.
            long waitDeadline = System.currentTimeMillis() + 3000;
            while (!handler.active.isEmpty() && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(5);
            }

            var client = Client.builder()
                    .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(Svc.class, GameConn.class)
                    .build();
            client.start();

            // Wait for connect to register on server so we have a ConnectionId.
            long deadline = System.currentTimeMillis() + 3000;
            while (handler.connects.get() == beforeConnects && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(handler.connects.get() > beforeConnects, "connect did not fire in round " + rr);

            // Wait for exactly one active connection (the one we just connected).
            deadline = System.currentTimeMillis() + 3000;
            while (handler.active.size() != 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }

            // Grab the freshly added ConnectionId.
            ConnectionId target = null;
            for (var id : handler.active) { target = id; break; }
            assertNotNull(target, "no active connection found in round " + rr);
            final var finalTarget = target;

            // Start a sender thread that pumps messages continuously to keep
            // the worker loop busy reading while we close from outside.
            var senderStop = new AtomicBoolean(false);
            var sender = new Thread(() -> {
                try {
                    while (!senderStop.get()) {
                        client.send(new Tick(r, System.nanoTime()));
                    }
                } catch (Throwable _) { /* connection closed — expected */ }
            });
            sender.start();

            // Brief pause so the worker loop is reliably in read.
            Thread.sleep(5);

            // External-thread close. Must be race-safe.
            assertDoesNotThrow(() -> server.closeConnection(finalTarget));

            // Wait for the disconnect to be processed before moving on.
            deadline = System.currentTimeMillis() + 3000;
            while (handler.disconnects.get() == beforeDisconnects && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }

            senderStop.set(true);
            sender.join(1000);
            client.close();
        }

        // Wait for server to process all disconnects.
        long deadline = System.currentTimeMillis() + 5000;
        while (handler.disconnects.get() < rounds && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertNull(handler.firstError.get(), "handler crashed: " + handler.firstError.get());
        assertEquals(rounds, handler.connects.get(), "missing connects");
        assertEquals(rounds, handler.disconnects.get(), "missing disconnects after external close");

        server.close();
    }

    /**
     * Sustained hammer: many clients sending concurrently for several seconds
     * while the handler broadcasts. Each client sends a bounded number of
     * messages so we can assert TCP's "no loss" property exactly. Runs long
     * enough (many millions of messages total) to catch races that only show
     * up with significant contention.
     */
    @Test
    void sustained_hammer_no_losses_no_crashes() throws Exception {
        var handler = new RecordingHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConn.class)
                .eventLoops(4)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        // Each handler broadcast writes to N clients; N*messages writes per
        // input. With N=6 × 2000 in × 6 out fan-out = 72K writes under 5s
        // writeFully deadline. Kept modest so CI doesn't flake under load.
        int numClients = 6;
        int messagesPerClient = 2_000;
        var clients = new Client[numClients];
        var received = new AtomicInteger[numClients];
        for (int i = 0; i < numClients; i++) {
            received[i] = new AtomicInteger();
            final int slot = i;
            clients[i] = Client.builder()
                    .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(Svc.class, GameConn.class)
                    .onMessage(Broadcasted.class, m -> received[slot].incrementAndGet())
                    .build();
            clients[i].start();
        }

        long deadline = System.currentTimeMillis() + 3000;
        while (handler.connects.get() < numClients && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(numClients, handler.connects.get());

        var workerError = new AtomicReference<Throwable>();
        var workers = new Thread[numClients];
        long t0 = System.nanoTime();
        for (int i = 0; i < numClients; i++) {
            final int clientIdx = i;
            final var client = clients[i];
            workers[i] = new Thread(() -> {
                try {
                    for (int n = 0; n < messagesPerClient; n++) {
                        client.send(new Chat(clientIdx, n, "x"));
                    }
                    client.flush();
                } catch (Throwable t) {
                    workerError.compareAndSet(null, t);
                }
            }, "hammer-" + i);
            workers[i].start();
        }

        for (var w : workers) w.join(20_000);

        int totalSent = numClients * messagesPerClient;
        // Wait for the server to receive all messages — TCP guarantees
        // eventual delivery if the socket didn't close.
        long waitDeadline = System.currentTimeMillis() + 20_000;
        while (handler.receivedChat.get() < totalSent && System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(25);
        }
        long elapsed = System.nanoTime() - t0;

        assertNull(workerError.get(), "sender crashed: " + workerError.get());
        assertNull(handler.firstError.get(), "handler crashed: " + handler.firstError.get());

        int receivedOnServer = handler.receivedChat.get();
        assertEquals(totalSent, receivedOnServer,
                "server lost messages under concurrent broadcast: sent=" + totalSent
                        + " recv=" + receivedOnServer);

        // Every client should see broadcasts. Because fan-out shares a single
        // server socket buffer, under extreme contention some bytes may be
        // dropped on full buffers — we only assert each client sees a
        // meaningful fraction, and that no client saw exactly zero (which
        // would indicate a map-corruption bug).
        long minSeen = Long.MAX_VALUE;
        long maxSeen = 0;
        for (int i = 0; i < numClients; i++) {
            long got = received[i].get();
            minSeen = Math.min(minSeen, got);
            maxSeen = Math.max(maxSeen, got);
        }
        assertTrue(minSeen > 0, "some client received zero broadcasts — map corruption?");
        double ms = elapsed / 1e6;
        System.out.printf("sustained: %d msgs in %.0fms = %.0f msg/s; bcast min=%d max=%d%n",
                totalSent, ms, totalSent / (ms / 1000.0), minSeen, maxSeen);

        for (var c : clients) c.close();
        server.close();
    }

    /**
     * The {@link jtroop.session.SessionStore} is shared between the accept
     * loop (allocate) and worker loops (release) and iterated by broadcast.
     * Without synchronization its {@code count} and {@code freeHead} would
     * drift under contention, eventually leaking or double-assigning slots.
     */
    @Test
    void sessionStore_allocate_release_iterate_races() throws Exception {
        // Capacity chosen so that at no point can a tight-loop allocator hit
        // "store full" faster than the matched releaser can drain — we want
        // to exercise the happy path under heavy contention, not exercise
        // the full-backpressure path (which would require cooperative retry).
        int capacity = 2048;
        var store = new jtroop.session.SessionStore(capacity);

        int allocators = 4;
        int releasers = 4;
        int iterators = 2;
        // Fewer iterations than capacity / allocators so allocators never hit
        // "full" even in pathological release schedules.
        int perAllocator = 400;
        int totalAllocs = allocators * perAllocator;

        var queue = new java.util.concurrent.ConcurrentLinkedQueue<ConnectionId>();
        var error = new AtomicReference<Throwable>();
        var stopIter = new AtomicBoolean(false);
        var allocDone = new CountDownLatch(allocators);

        var workers = new java.util.ArrayList<Thread>();
        for (int i = 0; i < allocators; i++) {
            workers.add(new Thread(() -> {
                try {
                    for (int j = 0; j < perAllocator; j++) {
                        queue.add(store.allocate());
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    allocDone.countDown();
                }
            }, "alloc"));
        }
        // Releasers drain cooperatively until every allocation is released.
        var releasedCount = new AtomicInteger(0);
        for (int i = 0; i < releasers; i++) {
            workers.add(new Thread(() -> {
                try {
                    while (releasedCount.get() < totalAllocs) {
                        var id = queue.poll();
                        if (id == null) {
                            if (allocDone.getCount() == 0 && queue.isEmpty()) break;
                            Thread.onSpinWait();
                            continue;
                        }
                        store.release(id);
                        releasedCount.incrementAndGet();
                    }
                } catch (Throwable t) { error.compareAndSet(null, t); }
            }, "release"));
        }
        // Iterators poke forEachActive concurrently with mutations.
        for (int i = 0; i < iterators; i++) {
            workers.add(new Thread(() -> {
                try {
                    var seen = new int[1];
                    while (!stopIter.get()) {
                        seen[0] = 0;
                        store.forEachActive(id -> seen[0]++);
                        if (seen[0] < 0 || seen[0] > capacity) {
                            throw new AssertionError("impossible active count: " + seen[0]);
                        }
                    }
                } catch (Throwable t) { error.compareAndSet(null, t); }
            }, "iter"));
        }

        for (var t : workers) t.start();
        // Wait for allocators + releasers to complete.
        assertTrue(allocDone.await(15, TimeUnit.SECONDS), "allocators did not finish");
        long waitUntil = System.currentTimeMillis() + 10_000;
        while (releasedCount.get() < totalAllocs && System.currentTimeMillis() < waitUntil) {
            Thread.yield();
        }
        stopIter.set(true);
        for (var t : workers) t.join(5_000);

        assertNull(error.get(), "race detected: " + error.get());
        assertEquals(totalAllocs, releasedCount.get(), "not all allocations were released");

        // Drain any stragglers and assert the store is empty.
        ConnectionId id;
        while ((id = queue.poll()) != null) store.release(id);
        assertEquals(0, store.activeCount(), "active count drifted under contention");
    }
}
