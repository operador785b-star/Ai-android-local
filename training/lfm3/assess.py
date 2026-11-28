"""Generate independent Spanish answers from original BF16 and/or a PEFT checkpoint."""
import argparse
import json
import os
import resource
import sys
import time
from pathlib import Path

import torch
from peft import PeftModel
from base import MODEL, REV, load, snapshot
from corpus import HERE, ROOT, SYSTEM, digest, messages, rows
sys.path.insert(0, str(ROOT / "evaluation"))
from run import verify


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("report", type=Path)
    cli.add_argument("--base-local", type=Path)
    cli.add_argument("--adapter", type=Path)
    cli.add_argument("--device", choices=["cuda", "cpu"], default="cpu")
    args = cli.parse_args()
    torch.set_num_threads(2)
    path = snapshot(args.base_local)
    model, tokenizer = load(path, torch.bfloat16 if args.device == "cpu" or torch.cuda.is_bf16_supported()
                            else torch.float16, args.device)
    adapter_hash = None
    if args.adapter:
        cfg = json.loads((args.adapter / "adapter_config.json").read_text())
        if (cfg["base_model_name_or_path"], cfg["revision"]) != (MODEL, REV):
            raise ValueError("El adaptador no corresponde al modelo original fijado")
        model = PeftModel.from_pretrained(model, args.adapter)
        adapter_hash = digest(args.adapter / "adapter_model.safetensors")
    model.eval()
    casefile = HERE / "holdout.jsonl"
    cases = rows(casefile)
    identity = {"revision": REV, "adapter_sha256": adapter_hash, "suite_sha256": digest(casefile),
                "backend": "transformers", "sampling": "greedy", "device": args.device,
                "dtype": str(model.dtype), "threads": 2, "system": SYSTEM}
    results = []
    if args.report.exists():
        prior = json.loads(args.report.read_text())
        if prior["identity"] != identity or [r["id"] for r in prior["results"]] != [c["id"] for c in cases[:len(prior["results"])]]:
            raise ValueError("El informe parcial no corresponde a la misma ejecución")
        results = prior["results"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    for case in cases[len(results):]:
        prompt = tokenizer.apply_chat_template(messages(case), add_generation_prompt=True,
                                               enable_thinking=False, tokenize=False)
        inputs = tokenizer(prompt, add_special_tokens=False, return_tensors="pt").to(args.device)
        start = time.monotonic()
        with torch.inference_mode():
            generated = model.generate(**inputs, do_sample=False, max_new_tokens=case["max_tokens"],
                                       repetition_penalty=1.0, use_cache=True)
        out = generated[0, inputs.input_ids.shape[-1]:].tolist()
        seconds = time.monotonic() - start
        text = tokenizer.decode(out, skip_special_tokens=True)
        results.append({"id": case["id"], "category": case["category"], "passed": verify(case, text),
                        "answer": text, "seconds": seconds, "output_tokens": len(out),
                        "process_peak_rss_kib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss})
        tmp = args.report.with_suffix(".tmp")
        tmp.write_text(json.dumps({"identity": identity, "results": results, "total": len(results),
                                   "passed": sum(r["passed"] for r in results)}, ensure_ascii=False, indent=2) + "\n")
        os.replace(tmp, args.report)
        print(case["id"], results[-1]["passed"], flush=True)


if __name__ == "__main__":
    main()
