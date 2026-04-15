package jtroop.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class EventLoopGroupTest {

    @Test
    void creates_requestedNumberOfLoops() throws Exception {
        var group = new EventLoopGroup(4);
        group.start();
        assertEquals(4, group.size());
        group.close();
    }

    @Test
    void next_roundRobinsAcrossLoops() throws Exception {
        var group = new EventLoopGroup(3);
        group.start();

        var first = group.next();
        var second = group.next();
        var third = group.next();
        var fourth = group.next(); // wraps back

        assertNotSame(first, second);
        assertNotSame(second, third);
        assertSame(first, fourth); // round-robin wraps

        group.close();
    }

    @Test
    void allLoops_areRunning() throws Exception {
        var group = new EventLoopGroup(2);
        group.start();

        for (int i = 0; i < group.size(); i++) {
            assertTrue(group.get(i).isRunning());
        }

        group.close();
    }

    @Test
    void close_stopsAllLoops() throws Exception {
        var group = new EventLoopGroup(3);
        group.start();
        group.close();

        for (int i = 0; i < group.size(); i++) {
            assertFalse(group.get(i).isRunning());
        }
    }

    @Test
    void submit_executesOnLoop() throws Exception {
        var group = new EventLoopGroup(2);
        group.start();

        var latch = new CountDownLatch(2);
        group.get(0).submit(latch::countDown);
        group.get(1).submit(latch::countDown);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        group.close();
    }

    @Test
    void defaultSize_isProcessorCount() throws Exception {
        var group = new EventLoopGroup();
        group.start();
        assertEquals(Runtime.getRuntime().availableProcessors(), group.size());
        group.close();
    }
}
