#!/usr/bin/env python3
import math, argparse

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--tps", type=float, required=True, help="transactions per second")
    p.add_argument("--entries", type=float, default=4, help="avg entries per tx")
    args = p.parse_args()

    T, E = args.tps, args.entries
    row_ops = T * (3 + 2*E)

    partitions = max(128, int(math.ceil(T / 50.0)))
    # Round partitions to a nice power-of-two-ish multiple of 32
    def round_up(n):
        for m in [128, 160, 192, 224, 256, 320, 384, 512, 768, 1024]:
            if n <= m: return m
        return int(math.ceil(n / 256.0) * 256)
    partitions = round_up(partitions)

    projection_workers = min(partitions, int(math.ceil(T / 200.0)))
    api_replicas = max(3, int(math.ceil(T / 200.0)))

    print("=== Capacity Recommendations (first-pass) ===")
    print(f"TPS: {T:.0f}")
    print(f"Avg entries/tx: {E:.1f}")
    print(f"Estimated Postgres row ops/sec: {row_ops:,.0f}")
    print(f"Kafka partitions (ledger.transactions.v1): {partitions}")
    print(f"Projection worker replicas (start): {projection_workers}")
    print(f"API replicas (start): {api_replicas}")
    print("\nValidate and adjust using k6 and production telemetry.")

if __name__ == "__main__":
    main()
