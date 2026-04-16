package jtroop.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    @Test
    void connectionId_packsIndexAndGeneration() {
        var id = ConnectionId.of(5, 3);
        assertEquals(5, id.index());
        assertEquals(3, id.generation());
    }

    @Test
    void connectionId_invalidDetected() {
        var invalid = ConnectionId.INVALID;
        assertFalse(invalid.isValid());
    }

    @Test
    void connectionId_validDetected() {
        var id = ConnectionId.of(0, 1);
        assertTrue(id.isValid());
    }

    @Test
    void connectionId_differentGenerationNotEqual() {
        var id1 = ConnectionId.of(5, 1);
        var id2 = ConnectionId.of(5, 2);
        assertNotEquals(id1, id2);
    }

    @Test
    void connectionId_sameIndexAndGenerationEqual() {
        var id1 = ConnectionId.of(5, 3);
        var id2 = ConnectionId.of(5, 3);
        assertEquals(id1, id2);
    }

    @Test
    void sessionStore_allocate_returnsValidId() {
        var store = new SessionStore(16);
        var id = store.allocate();
        assertTrue(id.isValid());
        assertEquals(0, id.index());
    }

    @Test
    void sessionStore_allocateMultiple_uniqueIndices() {
        var store = new SessionStore(16);
        var id1 = store.allocate();
        var id2 = store.allocate();
        var id3 = store.allocate();
        assertNotEquals(id1.index(), id2.index());
        assertNotEquals(id2.index(), id3.index());
    }

    @Test
    void sessionStore_release_incrementsGeneration() {
        var store = new SessionStore(16);
        var id1 = store.allocate();
        assertEquals(1, id1.generation());
        store.release(id1);
        var id2 = store.allocate();
        // Same index reused but different generation
        assertEquals(id1.index(), id2.index());
        assertEquals(2, id2.generation());
    }

    @Test
    void sessionStore_isActive_trueForAllocated() {
        var store = new SessionStore(16);
        var id = store.allocate();
        assertTrue(store.isActive(id));
    }

    @Test
    void sessionStore_isActive_falseAfterRelease() {
        var store = new SessionStore(16);
        var id = store.allocate();
        store.release(id);
        assertFalse(store.isActive(id));
    }

    @Test
    void sessionStore_isActive_falseForStaleGeneration() {
        var store = new SessionStore(16);
        var id1 = store.allocate();
        store.release(id1);
        store.allocate(); // same slot, new generation
        assertFalse(store.isActive(id1)); // stale
    }

    @Test
    void sessionStore_allocate_throwsWhenFull() {
        var store = new SessionStore(2);
        store.allocate();
        store.allocate();
        assertThrows(IllegalStateException.class, store::allocate);
    }

    @Test
    void sessionStore_setState_andGetState() {
        var store = new SessionStore(16);
        var id = store.allocate();
        store.setState(id, 42);
        assertEquals(42, store.getState(id));
    }

    @Test
    void sessionStore_setLastActivity_andGetLastActivity() {
        var store = new SessionStore(16);
        var id = store.allocate();
        long now = System.nanoTime();
        store.setLastActivity(id, now);
        assertEquals(now, store.getLastActivity(id));
    }

    @Test
    void sessionStore_activeCount() {
        var store = new SessionStore(16);
        assertEquals(0, store.activeCount());
        var id1 = store.allocate();
        assertEquals(1, store.activeCount());
        var id2 = store.allocate();
        assertEquals(2, store.activeCount());
        store.release(id1);
        assertEquals(1, store.activeCount());
    }

    @Test
    void sessionStore_forEachActive() {
        var store = new SessionStore(16);
        var id1 = store.allocate();
        var id2 = store.allocate();
        var id3 = store.allocate();
        store.release(id2);

        var active = new java.util.ArrayList<ConnectionId>();
        store.forEachActive(active::add);
        assertEquals(2, active.size());
        assertTrue(active.contains(id1));
        assertTrue(active.contains(id3));
    }

    @Test
    void sessionStore_allocate_beyondCapacityBoundary_reusesReleasedSlots() {
        var store = new SessionStore(3);
        var a = store.allocate();
        var b = store.allocate();
        var c = store.allocate();
        assertThrows(IllegalStateException.class, store::allocate);

        // Release two, re-allocate — should succeed and then fail on the fourth.
        store.release(a);
        store.release(b);
        var d = store.allocate();
        var e = store.allocate();
        assertTrue(store.isActive(c));
        assertTrue(store.isActive(d));
        assertTrue(store.isActive(e));
        assertThrows(IllegalStateException.class, store::allocate);
    }

    @Test
    void sessionStore_releaseTwice_isSafeNoOp() {
        var store = new SessionStore(4);
        var id = store.allocate();
        store.release(id);
        assertEquals(0, store.activeCount());
        // Second release with the same (now-stale) handle must be a no-op.
        store.release(id);
        assertEquals(0, store.activeCount());
        // And still allocates correctly after the double-release.
        var next = store.allocate();
        assertTrue(store.isActive(next));
        assertEquals(1, store.activeCount());
    }

    @Test
    void sessionStore_release_withStaleGeneration_isSafeNoOp() {
        var store = new SessionStore(4);
        var id1 = store.allocate();
        store.release(id1);
        var id2 = store.allocate(); // same slot, new generation
        // Releasing the stale handle must not affect the current live session.
        store.release(id1);
        assertTrue(store.isActive(id2));
        assertEquals(1, store.activeCount());
    }

    @Test
    void sessionStore_release_withInvalidIndex_isSafeNoOp() {
        var store = new SessionStore(4);
        store.allocate();
        // Out-of-range handles must be a silent no-op (not throw).
        store.release(ConnectionId.of(-1, 1));
        store.release(ConnectionId.of(99, 1));
        assertEquals(1, store.activeCount());
    }

    @Test
    void sessionStore_forEachActive_withAllocationsAndReleasesInterleaved() {
        var store = new SessionStore(8);
        var ids = new ConnectionId[8];
        for (int i = 0; i < 8; i++) ids[i] = store.allocate();
        // Release every other slot.
        store.release(ids[1]);
        store.release(ids[3]);
        store.release(ids[5]);
        store.release(ids[7]);

        var seen = new java.util.ArrayList<ConnectionId>();
        store.forEachActive(seen::add);
        assertEquals(4, seen.size());
        assertTrue(seen.contains(ids[0]));
        assertTrue(seen.contains(ids[2]));
        assertTrue(seen.contains(ids[4]));
        assertTrue(seen.contains(ids[6]));

        // Re-allocate into freed slots — generation must have advanced.
        var reused = store.allocate();
        assertEquals(2, reused.generation());
        seen.clear();
        store.forEachActive(seen::add);
        assertEquals(5, seen.size());
        assertTrue(seen.contains(reused));
    }

    @Test
    void sessionStore_forEachActive_emptyStore_callsConsumerZeroTimes() {
        var store = new SessionStore(8);
        var seen = new java.util.ArrayList<ConnectionId>();
        store.forEachActive(seen::add);
        assertEquals(0, seen.size());
    }

    @Test
    void sessionStore_state_preservedIndependentlyOfFreeList() {
        // Regression: old implementation overloaded `states` to carry the
        // free-list pointer. Make sure freeing and re-allocating a different
        // slot does not corrupt another slot's state.
        var store = new SessionStore(4);
        var a = store.allocate();
        var b = store.allocate();
        store.setState(a, 111);
        store.setState(b, 222);
        store.release(b); // alters free list; must not touch `a`'s state
        assertEquals(111, store.getState(a));
        var c = store.allocate(); // reuses b's slot
        assertEquals(0, store.getState(c)); // fresh slot starts at 0
        assertEquals(111, store.getState(a)); // still intact
    }

    @Test
    void sessionStore_state_independentOfActiveBit() {
        // State is packed alongside the active flag in the new layout.
        // Verify that setting negative / sign-bit state values still works.
        var store = new SessionStore(4);
        var id = store.allocate();
        store.setState(id, -1);
        assertEquals(-1, store.getState(id));
        assertTrue(store.isActive(id));
        store.setState(id, Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, store.getState(id));
        assertTrue(store.isActive(id));
        store.setState(id, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, store.getState(id));
        assertTrue(store.isActive(id));
    }

    @Test
    void sessionStore_zeroCapacity_allocateThrows() {
        var store = new SessionStore(0);
        assertEquals(0, store.activeCount());
        assertThrows(IllegalStateException.class, store::allocate);
    }
}
