package net.session;

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
}
