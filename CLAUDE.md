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

## Public API style (from japes)

### Builder pattern for construction

```java
// Fluent builder → immutable runtime object
var world = World.builder()
    .addSystem(Physics.class)
    .addPlugin(H2PersistencePlugin.autoSync("jdbc:h2:./data"))
    .addResource(new Config(60))
    .chunkSize(1024)
    .build();
```

- `builder()` static factory, returns mutable builder
- Builder methods return `this` for chaining
- `build()` validates and constructs the immutable object
- Plugins bundle systems + resources: `addPlugin(Plugin)` where `Plugin` is `@FunctionalInterface void install(Builder)`

### Entity as value type

```java
// Entity is a record (value type) — no identity, no null-check issues
public record Entity(long id) {
    public int index()      { return (int)(id >>> 32); }
    public int generation() { return (int) id; }
    public static Entity of(int index, int generation) { ... }
}

var e = world.spawn(new Position(0, 0), new Velocity(1, 1));
world.setComponent(e, new Position(5, 5));
world.despawn(e);
```

- Entity is a packed long, not an object with identity
- Generation prevents use-after-free (stale handles detected)
- All mutation methods take Entity as first arg

### Components are records, mutations take records

```java
// Components: immutable records, no setters
record Position(float x, float y, float z) {}
record Health(int hp) {}

// Mutation: create new record, framework decomposes into SoA
world.setComponent(entity, new Position(x + dx, y + dy, z + dz));

// Read: framework reconstructs record from SoA (EA eliminates it if fields extracted)
var pos = world.getComponent(entity, Position.class);
float x = pos.x();  // scalar-replaced — 0 B/op
```

### Annotations for metadata, not behavior

```java
@Persistent          // included in save/load
@NetworkSync         // included in network sync
@ValueTracked        // equality check before marking changed
@SparseStorage       // (future) sparse set storage hint
```

- Annotations are `@Retention(RUNTIME)`, `@Target(TYPE)`
- Framework reads them once at registration, zero per-tick cost
- No annotation processing, no code generation at compile time — all runtime

### Systems are plain methods with annotated parameters

```java
class Physics {
    @System
    void integrate(@Read Velocity v, @Write Mut<Position> p) {
        var cur = p.get();
        p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy(), cur.z() + v.dz()));
    }
}
```

- `@Read` = read-only access, `@Write` = mutable via `Mut<T>` wrapper
- `Res<T>` / `ResMut<T>` for singleton resources
- `Commands` for deferred mutations (spawn/despawn/add/remove)
- `Entity` parameter for the current entity's ID
- Framework generates bytecode per system → monomorphic dispatch → EA works

### Deferred commands flush at stage boundaries

```java
@System void spawner(Commands cmds) {
    cmds.spawn(new Position(0, 0), new Health(100));
    cmds.despawn(entity);
    cmds.set(entity, new Health(50));
}
// Commands applied after all systems in the stage finish
```

- Commands stored in flat buffer (int[] ops, long[] ids, Object[] refs)
- Zero per-command allocation after warmup
- `cmds.applyTo(world)` for manual flush outside tick

### Read-only accessor for non-system code

```java
var accessor = world.accessor();
accessor.forEachPersistentEntity(entity -> { ... });
accessor.forEachPersistentEntityComponent((entity, comp) -> { ... });
var pos = accessor.getComponent(entity, Position.class);
```

- `WorldAccessor` is off the hot tick path — no EA impact
- Consumer-based iteration avoids intermediate list allocation
- List-returning convenience overloads for simple cases

### Naming conventions

| Pattern | Example | When |
|---|---|---|
| `getX` | `getComponent`, `getResource` | read that may fail |
| `hasX` | `hasComponent` | boolean check |
| `setX` | `setComponent`, `setResource` | overwrite existing |
| `addX` | `addSystem`, `addPlugin`, `addComponent` | append/register |
| `removeX` | `removeComponent` | delete |
| `spawn` / `despawn` | `spawn(Record...)`, `despawn(Entity)` | entity lifecycle |
| `forEachX` | `forEachPersistentEntity` | zero-alloc callback iteration |
| `xBuilder` | `spawnBuilder`, `bulkSpawnWithIdBuilder` | pre-resolved bulk path |
| `clear` | `world.clear()` | reset to empty state |
| `close` | `world.close()` | release resources (executor) |

### Visibility rules

- **Public**: user-facing API (spawn, despawn, tick, save, load, accessor, builder)
- **Package-private**: framework internals (CommandProcessor, *ById methods, entityAllocator)
- **No `protected`**: no inheritance in the API
- **`final` classes**: World, WorldBuilder, WorldAccessor, Commands, Entity — no subclassing

## Reference

- japes "One JIT to rule them all": `../ecs/site/docs/deep-dive/one-jit-to-rule-them-all.md`
- japes benchmark data: `../ecs/site/docs/data/benchmark-results.json`
- Verified: NBody 10k = 0 B/op, iterateWithWrite 10k = 0 B/op (JDK 26, stock)
