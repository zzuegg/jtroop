#!/usr/bin/env python3
"""
Diff two JMH JSON result files and exit non-zero if a regression exceeds the
configured envelope. Intended for CI gating.

Usage:
    scripts/jmh-diff.py <baseline.json> <candidate.json> \
        [--alloc-pct 5] [--throughput-pct 10]

The baseline is typically a committed golden file under benchmark/baselines/.
The candidate is the output of a fresh benchmark run
(benchmark/net/build/results/jmh/results.json).

Exits 0 if every benchmark in the candidate is within the envelope, 1 if any
metric regresses, 2 on a format / matching error (e.g. the candidate is missing
a benchmark that was in the baseline).
"""
import argparse
import json
import sys


def load(path):
    """Return a dict keyed by (benchmark, sorted-params) so parameterised
    benchmarks with the same method name but different @Param values stay
    distinct."""
    with open(path) as f:
        out = {}
        for r in json.load(f):
            params = r.get("params") or {}
            key = (r["benchmark"], tuple(sorted(params.items())))
            out[key] = r
        return out


def key_to_str(key):
    name, params = key
    if not params:
        return name
    return name + "[" + ",".join(f"{k}={v}" for k, v in params) + "]"


def primary_score(record):
    return record["primaryMetric"]["score"]


def alloc_norm(record):
    sec = record.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
    if sec is None:
        return None
    return sec["score"]


def check(baseline, candidate, alloc_envelope, tp_envelope):
    regressions = []
    for key, base in baseline.items():
        if key not in candidate:
            print(f"MISSING in candidate: {key_to_str(key)}", file=sys.stderr)
            return 2
        cand = candidate[key]
        name = key_to_str(key)

        # Throughput: higher is better for "thrpt" mode; lower for "avgt".
        base_score = primary_score(base)
        cand_score = primary_score(cand)
        mode = base["mode"]
        if mode == "thrpt":
            delta_pct = (cand_score - base_score) / base_score * 100.0
            # Negative delta_pct = throughput dropped = regression.
            if delta_pct < -tp_envelope:
                regressions.append((name, "throughput", base_score, cand_score, delta_pct))
        else:
            # avgt / sample: lower is better.
            delta_pct = (cand_score - base_score) / base_score * 100.0
            if delta_pct > tp_envelope:
                regressions.append((name, "latency", base_score, cand_score, delta_pct))

        base_alloc = alloc_norm(base)
        cand_alloc = alloc_norm(cand)
        if base_alloc is not None and cand_alloc is not None:
            # Allocation: lower is better. Regression if candidate exceeds
            # baseline by more than the envelope. Avoid division by zero for
            # near-zero baselines by comparing absolute delta if baseline < 1.
            if base_alloc < 1.0:
                if cand_alloc - base_alloc > 1.0:
                    regressions.append(
                        (name, "alloc (B/op)", base_alloc, cand_alloc, cand_alloc - base_alloc)
                    )
            else:
                alloc_delta_pct = (cand_alloc - base_alloc) / base_alloc * 100.0
                if alloc_delta_pct > alloc_envelope:
                    regressions.append(
                        (name, "alloc (B/op)", base_alloc, cand_alloc, alloc_delta_pct)
                    )
    return regressions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--alloc-pct", type=float, default=5.0,
                        help="allocation (B/op) regression threshold, percent")
    parser.add_argument("--throughput-pct", type=float, default=10.0,
                        help="throughput drop threshold, percent")
    args = parser.parse_args()

    baseline = load(args.baseline)
    candidate = load(args.candidate)

    result = check(baseline, candidate, args.alloc_pct, args.throughput_pct)
    if result == 2:
        return 2
    regressions = result

    if not regressions:
        print(f"OK — {len(baseline)} benchmarks within envelope "
              f"(alloc ±{args.alloc_pct}%, throughput ±{args.throughput_pct}%).")
        return 0

    print(f"REGRESSION in {len(regressions)} metric(s):")
    for name, metric, base, cand, delta in regressions:
        print(f"  {name}")
        print(f"    {metric}: baseline={base:.4f} candidate={cand:.4f} "
              f"delta={delta:+.2f}%")
    return 1


if __name__ == "__main__":
    sys.exit(main())
