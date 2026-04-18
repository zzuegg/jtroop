# JMH regression baselines

Committed golden benchmark results used for regression gating. Each file is a
JMH JSON result set produced by

```sh
gradle :benchmark:net:jmh -Pjmh.include='<regex>' -Pjmh.warmup=1s -Pjmh.timeOnIteration=2s
cp benchmark/net/build/results/jmh/results.json benchmark/baselines/<name>.json
```

## Current baselines

| File | Date | Coverage | Profile |
|---|---|---|---|
| `net-game-2026-04-18.json` | 2026-04-18 | `NetGameBenchmark.positionUpdate`, `NetGameBenchmark.chatMessage` | 1 s warmup × 5, 2 s iteration × 5, fork=1, `-prof gc` |

## How to diff vs a local run

After running the same benchmark on a change, compare `gc.alloc.rate.norm`
and `Score` fields in the two JSON files. A regression is a `gc.alloc.rate.norm`
delta greater than the noise envelope (~±5 %) or a throughput drop greater
than 10 %.

A purpose-built diff script belongs in `scripts/jmh-diff.py` for CI
integration — out of scope for this commit.

## When to refresh

Regenerate the baseline when:

* a deliberate perf change lands that is meant to move the numbers;
* JDK version, hardware, or JVM flags change in a way that affects the
  absolute numbers (CI-visible);
* a measurement methodology change (warmup count, iteration duration,
  profiler set) is made in `benchmark/net/build.gradle.kts`.

Include the reason in the commit message alongside the regenerated JSON.
