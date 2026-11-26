"""Merge an adapter into the original unquantized Qwen3.5 weights."""

import argparse
import json
from pathlib import Path

from model import BASE_MODEL, BASE_REVISION, load_base, tokenizer_for


def export(adapter, output):
    import torch
    from peft import PeftModel

    config = json.loads((adapter / "adapter_config.json").read_text(encoding="utf-8"))
    if config["base_model_name_or_path"] != BASE_MODEL:
        raise ValueError("El adaptador no corresponde al modelo base fijado")
    if output.exists():
        raise FileExistsError(output)

    base = load_base(torch.bfloat16, device_map={"": "cpu"})
    merged = PeftModel.from_pretrained(base, adapter, is_trainable=False).merge_and_unload(safe_merge=True)
    merged.save_pretrained(output, safe_serialization=True)
    tokenizer_for().save_pretrained(output)
    (output / "export-manifest.json").write_text(json.dumps({
        "base_model": BASE_MODEL, "base_revision": BASE_REVISION, "adapter": str(adapter),
    }, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("adapter", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export(args.adapter, args.output)
