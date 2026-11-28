"""Freeze one completed checkpoint using only validation, before generating held-out answers."""
import argparse
import json
from pathlib import Path


def choose(run):
    candidates = sorted(run.glob("step-*/metrics.json"))
    if not candidates:
        raise ValueError("La ejecución no contiene checkpoints")
    scored = [(p.parent, json.loads(p.read_text())) for p in candidates]
    if any(m["identity"] != scored[0][1]["identity"] for _, m in scored):
        raise ValueError("Checkpoints de ejecuciones distintas")
    return min(scored, key=lambda x: (x[1]["validation_checkpoint"], len(x[1]["steps"])))[0]


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("run", type=Path)
    print(choose(cli.parse_args().run))
