#!/usr/bin/env python3
"""Check that benchmark prompts do not appear literally in training data."""
import argparse
import json
import unicodedata
from pathlib import Path


def prompts(path, key):
    return {
        " ".join(unicodedata.normalize("NFKC", json.loads(line)[key]).casefold().split())
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("training", type=Path)
    cli.add_argument("evaluation", type=Path)
    args = cli.parse_args()
    overlap = prompts(args.training, "instruction") & prompts(args.evaluation, "prompt")
    if overlap:
        raise SystemExit("Hay prompts repetidos entre entrenamiento y evaluación.")
    print("Conjuntos sin coincidencias literales tras normalizar.")
