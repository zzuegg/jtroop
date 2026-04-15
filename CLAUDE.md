# Net — Allocation-Free Design Guide

This project targets zero per-message heap allocation on JDK 26+. Every design decision must be evaluated against C2's escape analysis (EA) capabilities.

## Core principle

**If the JIT can prove an object never escapes the method that created it, it eliminates the allocation entirely.** The object's fields become CPU registers or stack slots. No heap, no GC, no cache miss.

An object escapes if:
- Stored in a heap field (`this.x = obj`)
- Stored in an array (`array[i] = obj` — aastore requires heap reference)
- Passed to a non-inlined method
- Returned from a non-inlined method

## The 10 rules

### 1. Primitive arrays enable EA; Object[] defeats it
`Object[]` requires `aastore` which forces heap allocation. Primitive arrays (`float[]`, `int[]`, `byte[]`) use `fastore`/`iastore`/`bastore` which only need primitive values. Store data in struct-of-arrays (SoA) layout with one primitive array per field.

### 2. Fresh allocation per iteration enables EA; reuse prevents it
A fresh object created inside a loop body, used, and discarded within one iteration is scalar-replaced. A reused object stored as a heap field costs real memory writes and EA cannot eliminate it. Counter-intuitively: **more allocation = faster execution**.

### 3. The JIT must inline the entire call chain
EA works across inlined methods. If any link in the call chain is not inlined (too large, megamorphic dispatch, interface call), the object must be heap-allocated at that boundary. Keep hot paths short and monomorphic.

### 4. `invokeinterface` blocks inlining; `invokevirtual` from monomorphic sites enables it
Interface dispatch is megamorphic by default. Use concrete types or generated classes that the JIT sees as monomorphic call sites. Hidden classes via `MethodHandles.Lookup.defineClass()` produce monomorphic `invokevirtual`.

### 5. Uniform field widths enable scalar replacement
C2's EA struggles with mixed primitive types in the same object. Store all fields as the same width (e.g., all `long`) with bit-preserving widening (`Float.floatToRawIntBits`, `Double.doubleToRawLongBits`). Narrow at boundaries.

### 6. Avoid phi merges on allocation-bearing fields
If a field holds either value A or value B depending on a branch, C2 creates a phi node and can't eliminate either allocation. Split into separate fields: `current` (set once in constructor) and `pending` (set by mutation). No phi merge → EA works.

### 7. Cache lambdas, use index loops, return raw collections
Per-tick allocation sources: `this::method` lambda captures, `for-each` iterator objects, `Collections.unmodifiableList()` wrappers, `String.substring()`, `Set.of()`. Eliminate all of them on hot paths.

### 8. Pre-warm branch profiles for mid-range entity counts
C1's `MinInlineFrequencyRatio` refuses to inline methods on cold branches (400-2500 iterations). Seed the branch profile by invoking the method ~10K times with zero-valued data during initialization.

### 9. Graph-level EA handles large methods; BCEA 150-byte limit is a red herring
C2's bytecode-level EA (BCEA) has a 150-byte limit, but graph-level EA runs independently and handles methods of 500+ bytes. Don't split methods to satisfy BCEA — split for inlining and readability.

### 10. No 3-field inline limit exists
Tested 1-8 fields of the same type — all achieve 0 B/op. The perceived limit is about type mix in wrapper interactions, not field count.

## Flat-buffer patterns

### Command buffers
Parallel `int[] ops`, `long[] ids`, `Object[] refs` arrays instead of `List<Command>` objects. Arrays reused across cycles via `reset()` (clear cursors, null refs for GC). After warmup: zero allocation during command queueing.

### Message encoding
Read/write primitives directly from/to typed arrays. No intermediate Record/POJO creation. Use `DataInput`/`DataOutput` for wire format, but decode directly into backing arrays:

```java
// Zero-alloc decode: stream → primitive arrays
void decodeDirect(DataInput in, Object[] fieldArrays, int slot) {
    ((float[]) fieldArrays[0])[slot] = in.readFloat();
    ((float[]) fieldArrays[1])[slot] = in.readFloat();
    ((int[])   fieldArrays[2])[slot] = in.readInt();
}
```

### Bulk operations
Pre-resolve metadata once, cache references, reuse across iterations:
```java
var handle = prepareOnce(componentTypes);  // resolve once
handle.ensureCapacity(count);              // pre-allocate
for (...) {
    int slot = handle.allocateSlot(id);    // no HashSet, no ArchetypeId
    decodeDirect(in, handle.arrays(0), slot);  // no Record
    handle.markDone(slot);
}
```

## What to profile

Use JMH with `-prof gc` for `gc.alloc.rate.norm` (B/op). Target: **0 B/op** for hot paths.

Verify with:
- `gc.count = 0` across all forks (no GC triggered)
- Constant `gc.alloc.rate` (MB/sec) regardless of entity/message count = JMH noise, not real allocation
- B/op that scales linearly with count = real allocation to fix

## Known JIT limits

| Issue | Cause | Workaround |
|---|---|---|
| `World.setComponent` allocates Record | `invokeinterface` on `ComponentStorage<?>[]` blocks EA | Generate per-type decomposition or use SoA direct write |
| Mixed-type fields residual | Mut wrapper interaction with 4+ different primitive types | Override `set()` with `decompose$()` helper splitting bytecode under MaxInlineSize |
| Mid-range entity count EA failure | C1 branch profiling under-inlines on cold write paths | Pre-warm with 10K zero-valued invocations |
| `Record.equals()` blocks EA for @ValueTracked | Virtual dispatch | Primitive field comparison via `if_icmpne`/`fcmpl`/`dcmpl`/`lcmp` |

## JDK requirements

- JDK 26+ with `--enable-preview`
- `java.lang.classfile` API for hidden class generation
- `MethodHandles.Lookup.defineClass()` for monomorphic dispatch
- No `Unsafe`, no Valhalla required for zero-allocation

## Reference

- japes "One JIT to rule them all": `../ecs/site/docs/deep-dive/one-jit-to-rule-them-all.md`
- japes benchmark data: `../ecs/site/docs/data/benchmark-results.json`
- Verified: NBody 10k = 0 B/op, iterateWithWrite 10k = 0 B/op (JDK 26, stock)
