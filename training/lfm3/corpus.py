import hashlib
import json
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SYSTEM = ("Eres IA Local, un asistente útil. Responde en el idioma del usuario "
          "salvo que indique lo contrario. Respeta el formato exacto que pida. "
          "Usa bloques Markdown para código si el usuario no pidió texto sin formato. "
          "Si falta información, dilo con claridad.")


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def key(s):
    return " ".join(unicodedata.normalize("NFKC", s).casefold().split())


def splits():
    examples = rows(HERE / "examples.jsonl")
    held = rows(HERE / "holdout.jsonl")
    groups, seen = {}, set()
    legacy = {key(r["prompt"]) for path in (ROOT / "evaluation").glob("*.jsonl")
              for r in rows(path) if "prompt" in r}
    ids = [r["id"] for r in held]
    if len(ids) != len(set(ids)):
        raise ValueError("ID repetido")
    for row in examples + [dict(r, split="test") for r in held]:
        prompt, family, split = key(row["prompt"]), row["family"], row["split"]
        if not prompt or prompt in seen or prompt in legacy:
            raise ValueError(f"Prompt repetido o vacío: {prompt}")
        if groups.setdefault(family, split) != split:
            raise ValueError(f"Familia compartida entre particiones: {family}")
        if split not in {"train", "validation", "test"} or (split != "test" and not row["answer"].strip()):
            raise ValueError(row)
        seen.add(prompt)
    return {s: [r for r in examples if r["split"] == s] for s in ("train", "validation")}


def messages(row):
    return [{"role": "system", "content": SYSTEM}, {"role": "user", "content": row["prompt"]}]


def encode(row, tokenizer, limit):
    opts = dict(tokenize=False, enable_thinking=False)
    prompt = tokenizer.apply_chat_template(messages(row), add_generation_prompt=True, **opts)
    full = tokenizer.apply_chat_template(messages(row) + [{"role": "assistant", "content": row["answer"]}], **opts)
    suffix = tokenizer.eos_token + "\n"
    if not full.startswith(prompt) or not full.endswith(suffix):
        raise ValueError("La plantilla no coincide con los turnos de la app")
    prefix = tokenizer.encode(prompt, add_special_tokens=False)
    tokens = tokenizer.encode(full[:-1], add_special_tokens=False)
    if tokens[:len(prefix)] != prefix or tokens[-1] != tokenizer.eos_token_id or len(tokens) > limit:
        raise ValueError("Frontera de tokens, fin o longitud incorrectos")
    return {"input_ids": tokens, "labels": [-100] * len(prefix) + tokens[len(prefix):]}


if __name__ == "__main__":
    print({k: len(v) for k, v in splits().items()}, "test", len(rows(HERE / "holdout.jsonl")))
