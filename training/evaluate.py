"""Compare the official checkpoint and a trained adapter on held-out text cases."""

import argparse
import json
from pathlib import Path

from data import TASKS, key, prompt_text, read_jsonl
from model import BASE_MODEL, BASE_REVISION, load_base, tokenizer_for


def evaluate(args):
    import torch
    from peft import PeftModel

    cases = read_jsonl(args.cases)
    if not cases:
        raise ValueError("El conjunto de evaluación está vacío")
    seen = set()
    for case in cases:
        if (
            not isinstance(case.get("id"), str) or not case["id"].strip() or case["id"] in seen
            or case.get("task") not in TASKS or case.get("kind") not in ("exact", "contains")
            or not isinstance(case.get("expected"), str) or not case["expected"].strip()
            or not isinstance(case.get("instruction"), str) or not case["instruction"].strip()
        ):
            raise ValueError(f"Caso inválido o repetido: {case}")
        seen.add(case["id"])
    if not torch.cuda.is_available():
        raise RuntimeError("Ejecuta esta comparación en la GPU de entrenamiento")
    tokenizer = tokenizer_for()
    base = load_base(torch.bfloat16, device_map={"": 0})

    def run(model):
        model.eval()
        outputs = []
        with torch.inference_mode():
            for case in cases:
                tokens = tokenizer(
                    prompt_text(tokenizer, case["instruction"]),
                    add_special_tokens=False, return_tensors="pt"
                ).to(model.device)
                generated = model.generate(
                    **tokens, max_new_tokens=args.max_new_tokens, do_sample=False,
                    temperature=None, top_p=None, top_k=None,
                    pad_token_id=tokenizer.pad_token_id,
                )[0, tokens.input_ids.shape[-1]:]
                answer = tokenizer.decode(generated, skip_special_tokens=True).strip()
                expected = key(case["expected"])
                passed = key(answer) == expected if case["kind"] == "exact" else expected in key(answer)
                outputs.append({**case, "answer": answer, "passed": passed})
        return {
            "cases": outputs,
            "by_task": {
                task: {
                    "passed": sum(r["passed"] for r in outputs if r["task"] == task),
                    "total": sum(r["task"] == task for r in outputs),
                }
                for task in sorted({r["task"] for r in outputs})
            },
        }

    before = run(base)
    after = run(PeftModel.from_pretrained(base, args.adapter, is_trainable=False))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump({
            "base_model": BASE_MODEL, "base_revision": BASE_REVISION, "adapter": str(args.adapter),
            "max_new_tokens": args.max_new_tokens, "base": before, "custom": after,
        }, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"base": before["by_task"], "custom": after["by_task"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-new-tokens", type=int, default=192)
    evaluate(parser.parse_args())
