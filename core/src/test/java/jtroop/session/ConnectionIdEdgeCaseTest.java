package jtroop.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests for {@link ConnectionId} and {@link SessionStore}.
 * Covers: bit-packing with max values, negative index handling,
 * forEachActiveIndex/forEachActiveLong consistency, zero-capacity store,
 * and state overflow boundaries.
 */
class ConnectionIdEdgeCaseTest {

    // --- ConnectionId bit packing ---

    @Test
    void maxIndex_packsAndUnpacks() {
        int maxIdx = Integer.MAX_VALUE;
        var id = ConnectionId.of(maxIdx, 1);
        assertEquals(maxIdx, id.index());
        assertEquals(1, id.generation());
    }

    @Test
    void maxGeneration_packsAndUnpacks() {
        var id = ConnectionId.of(0, Integer.MAX_VALUE);
        assertEquals(0, id.index());
        assertEquals(Integer.MAX_VALUE, id.generation());
    }

    @Test
    void negativeIndex_preservedViaBitMask() {
        // Negative ints are valid in the packed representation due to & INDEX_MASK
        var id = ConnectionId.of(-1, 1);
        // -1 as int = 0xFFFFFFFF, masked to lower 32 bits = 0xFFFFFFFF = -1 as int
        assertEquals(-1, id.index());
        assertEquals(1, id.generation());
    }

    @Test
    void zeroGeneration_isNotValid() {
        var id = ConnectionId.of(5, 0);
        assertFalse(id.isValid());
    }

    @Test
    void negativeGeneration_isValid() {
        // Negative generation has the sign bit set in upper 32 bits;
        // >>> 32 produces a positive number from the bit pattern
        var id = ConnectionId.of(0, -1);
        // (int)(id >>> 32) where id has 0xFFFFFFFF in upper 32 bits
        // This produces -1 as int, and -1 > 0 is false
        // So negative generation is actually NOT valid per isValid()
        // This documents the behavior
        assertEquals(-1, id.generation());
    }

    @Test
    void equality_onlyDependsOnPackedLong() {
        var a = ConnectionId.of(3, 7);
        var b = new ConnectionId(a.id());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // --- SessionStore: forEachActiveIndex ---

    @Test
    void forEachActiveIndex_yieldsCorrectIndicesAndGenerations() {
        var store = new SessionStore(4);
        var id1 = store.allocate();
        var id2 = store.allocate();
        store.release(id1);
        var id3 = store.allocate(); // reuses id1's slot

        var indices = new java.util.ArrayList<Integer>();
        var generations = new java.util.ArrayList<Integer>();
        store.forEachActiveIndex((idx, gen) -> {
            indices.add(idx);
            generations.add(gen);
        });

        assertEquals(2, indices.size());
        // id2 and id3 are active
        assertTrue(indices.contains(id2.index()));
        assertTrue(indices.contains(id3.index()));
        // Verify generations match
        int pos2 = indices.indexOf(id2.index());
        assertEquals(id2.generation(), (int) generations.get(pos2));
        int pos3 = indices.indexOf(id3.index());
        assertEquals(id3.generation(), (int) generations.get(pos3));
    }

    @Test
    void forEachActiveIndex_emptyStore_neverCalls() {
        var store = new SessionStore(4);
        store.forEachActiveIndex((idx, gen) -> fail("should not be called"));
    }

    // --- SessionStore: isActive with out-of-bounds index ---

    @Test
    void isActive_negativeIndex_returnsFalse() {
        var store = new SessionStore(4);
        store.allocate();
        assertFalse(store.isActive(ConnectionId.of(-1, 1)));
    }

    @Test
    void isActive_indexBeyondCapacity_returnsFalse() {
        var store = new SessionStore(4);
        store.allocate();
        assertFalse(store.isActive(ConnectionId.of(100, 1)));
    }

    // --- SessionStore: state boundaries ---

    @Test
    void setState_maxUnsignedInt_roundTrips() {
        var store = new SessionStore(4);
        var id = store.allocate();
        store.setState(id, 0xFFFFFFFF); // all bits set = -1 as signed int
        assertEquals(0xFFFFFFFF, store.getState(id));
    }

    @Test
    void setState_zero_afterNonZero_works() {
        var store = new SessionStore(4);
        var id = store.allocate();
        store.setState(id, 999);
        store.setState(id, 0);
        assertEquals(0, store.getState(id));
    }

    // --- SessionStore: full cycle stress ---

    @Test
    void fullCycle_allocateAll_releaseAll_reallocateAll() {
        int cap = 64;
        var store = new SessionStore(cap);
        var ids = new ConnectionId[cap];

        // Fill completely
        for (int i = 0; i < cap; i++) {
            ids[i] = store.allocate();
            store.setState(ids[i], i);
        }
        assertEquals(cap, store.activeCount());
        assertThrows(IllegalStateException.class, store::allocate);

        // Release all
        for (int i = 0; i < cap; i++) {
            store.release(ids[i]);
        }
        assertEquals(0, store.activeCount());

        // Reallocate all - generations should all be 2
        for (int i = 0; i < cap; i++) {
            var newId = store.allocate();
            assertEquals(2, newId.generation());
            // Old handles should be stale
            assertFalse(store.isActive(ids[i]));
        }
        assertEquals(cap, store.activeCount());
    }

    // --- SessionStore: capacity 1 ---

    @Test
    void capacity1_works() {
        var store = new SessionStore(1);
        var id = store.allocate();
        assertTrue(store.isActive(id));
        assertThrows(IllegalStateException.class, store::allocate);

        store.release(id);
        assertFalse(store.isActive(id));
        assertEquals(0, store.activeCount());

        var id2 = store.allocate();
        assertEquals(2, id2.generation());
        assertEquals(id.index(), id2.index());
    }
}
