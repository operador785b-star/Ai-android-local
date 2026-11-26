"""Preparation and normalization for the Qwen3.5 training data."""

import argparse
import hashlib
import json
import random
import re
import unicodedata
from pathlib import Path

TASKS = {"codigo", "matematicas", "razonamiento", "conversacion"}
CONTROL = re.compile(r"<\|[^|<>\n]{1,80}\|>|</?think>", re.IGNORECASE)
SYSTEM = (
    "Eres IA Local, un asistente útil. Responde en el idioma del usuario "
    "salvo que indique lo contrario. Si escribes código, usa bloques Markdown con lenguaje. "
    "Si falta información, dilo con claridad."
)


def read_jsonl(path):
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError("se esperaba un objeto JSON")
            rows.append(row)
        except ValueError as error:
            raise ValueError(f"{path}:{number}: {error}") from error
    return rows


def write_jsonl(path, rows):
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def key(text):
    return " ".join(unicodedata.normalize("NFKC", text).casefold().split())


def clean(rows):
    unique = {}
    for number, row in enumerate(rows, 1):
        task = row.get("task")
        if not isinstance(task, str) or task not in TASKS:
            raise ValueError(f"Fila {number}: task debe ser uno de {sorted(TASKS)}")
        record = {"task": task}
        for field in ("instruction", "response"):
            text = row.get(field)
            if not isinstance(text, str) or not text.strip() or CONTROL.search(text) or "\0" in text:
                raise ValueError(f"Fila {number}: {field} debe contener texto sin marcadores de control")
            record[field] = text.strip()
        identity = key(record["instruction"])
        previous = unique.setdefault(identity, record)
        if previous["response"] != record["response"] or previous["task"] != task:
            raise ValueError(f"Fila {number}: la misma instrucción tiene anotaciones distintas")
    return [unique[k] for k in sorted(unique)]


def prepare(source, output, seed, validation_fraction):
    rows = clean(read_jsonl(source))
    if len(rows) < 2:
        raise ValueError("Se requieren al menos dos ejemplos únicos")
    if not 0 < validation_fraction < 1:
        raise ValueError("La fracción de validación debe estar entre 0 y 1")
    random.Random(seed).shuffle(rows)
    count = max(1, min(len(rows) - 1, round(len(rows) * validation_fraction)))
    output.mkdir(parents=True, exist_ok=False)
    write_jsonl(output / "train.jsonl", rows[count:])
    write_jsonl(output / "validation.jsonl", rows[:count])
    metadata = {
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "seed": seed,
        "validation_fraction": validation_fraction,
        "train": len(rows) - count,
        "validation": count,
    }
    (output / "data-manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False))


def prompt_text(tokenizer, instruction):
    return tokenizer.apply_chat_template(
        [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": instruction},
        ],
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=False,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--validation-fraction", type=float, default=0.15)
    args = parser.parse_args()
    prepare(args.source, args.output, args.seed, args.validation_fraction)
