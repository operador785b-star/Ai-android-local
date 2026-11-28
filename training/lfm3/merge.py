"""Merge the evaluated adapter into a clean copy of the pinned BF16 base."""
import argparse
import json
import os
import shutil
import tempfile
from pathlib import Path

import torch
from peft import PeftModel
from base import MODEL, REV, load, snapshot
from compare import compare
from corpus import digest


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("adapter", type=Path)
    cli.add_argument("reference_report", type=Path)
    cli.add_argument("candidate_report", type=Path)
    cli.add_argument("output", type=Path)
    cli.add_argument("--base-local", type=Path)
    args = cli.parse_args()
    ref, test = [json.loads(p.read_text()) for p in (args.reference_report, args.candidate_report)]
    signature = digest(args.adapter / "adapter_model.safetensors")
    cfg = json.loads((args.adapter / "adapter_config.json").read_text())
    if (cfg["base_model_name_or_path"], cfg["revision"]) != (MODEL, REV):
        raise ValueError("Base del adaptador incorrecta")
    if ref["identity"]["adapter_sha256"] is not None or test["identity"]["adapter_sha256"] != signature:
        raise ValueError("El informe no corresponde a este adaptador y su base original")
    verdict = compare(ref, test)
    if not verdict["advances_to_manual_review"]:
        raise ValueError("El candidato no superó la comparación independiente")
    if args.output.exists():
        raise FileExistsError(args.output)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    path = snapshot(args.base_local)
    base, _ = load(path, torch.bfloat16, "cpu")
    merged = PeftModel.from_pretrained(base, args.adapter).merge_and_unload(safe_merge=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as temp:
        stage = Path(temp) / "merged"
        merged.save_pretrained(stage, safe_serialization=True)
        for name in ["tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "chat_template.jinja",
                     "generation_config.json", "modeling_lfm3.py", "configuration_lfm3.py", "lfm3_conv.py", "LICENSE"]:
            shutil.copy2(path / name, stage / name)
        (stage / "NOTICE.txt").write_text("Derived from LiquidAI/LFM3-1.7B under the LFM Open License v1.0. "
                                        "IA Local Android modified the model weights by supervised LoRA fine-tuning.\n")
        (stage / "export-manifest.json").write_text(json.dumps(
            {"base": MODEL, "revision": REV, "adapter_sha256": signature, "reference_report": digest(args.reference_report),
             "candidate_report": digest(args.candidate_report), "comparison": verdict}, indent=2) + "\n")
        os.rename(stage, args.output)


if __name__ == "__main__":
    main()
