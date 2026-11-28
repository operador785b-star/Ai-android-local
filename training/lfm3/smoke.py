"""Real CPU/BF16 or CUDA smoke SFT on one projection of the final LFM3 layer."""
import argparse
import json
import math
import os
import random
import tempfile
from pathlib import Path

import torch
from peft import PeftModel, get_peft_model

from base import MODEL, REV, adapter_config, load, save_adapter, snapshot
from corpus import HERE, digest, encode, splits


def loss_on(model, row, device):
    batch = {k: torch.tensor([v], device=device) for k, v in row.items()}
    return model(**batch, use_cache=False).loss.float()


def validation(model, data, device):
    model.eval()
    with torch.no_grad():
        return sum(loss_on(model, row, device).item() for row in data) / len(data)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--base-local", type=Path)
    cli.add_argument("--output", type=Path, required=True)
    cli.add_argument("--steps", type=int, default=2)
    cli.add_argument("--resume", type=Path)
    cli.add_argument("--checkpoint-every", type=int, default=1)
    cli.add_argument("--device", choices=["cpu", "cuda"], default="cpu")
    args = cli.parse_args()
    if args.checkpoint_every < 1 or args.steps <= 0 or args.steps > 100 or (args.device == "cuda" and not torch.cuda.is_bf16_supported()):
        cli.error("Se requieren entre 1 y 100 pasos y soporte BF16")
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.manual_seed(42)
    path = snapshot(args.base_local)
    base, tokenizer = load(path, torch.bfloat16, args.device)
    sets = {k: [encode(row, tokenizer, 256) for row in rows] for k, rows in splits().items()}
    order = list(range(len(sets["train"])))
    random.Random(42).shuffle(order)
    identity = {"base": MODEL, "revision": REV, "data_sha256": digest(HERE / "examples.jsonl"),
                "target": "model.layers.23.feed_forward.w2", "rank": 4, "seed": 42, "device": args.device}
    if args.resume:
        prior = json.loads((args.resume / "metrics.json").read_text())
        if prior["identity"] != identity or digest(args.resume / "adapter_model.safetensors") != prior["adapter_sha256"]:
            raise ValueError("Checkpoint incompatible")
        model = PeftModel.from_pretrained(base, args.resume, is_trainable=True)
        logs = prior["steps"]
        initial = prior["validation_original"]
        restored = validation(model, sets["validation"], args.device)
        if not math.isclose(restored, prior["validation_checkpoint"], abs_tol=1e-5):
            raise RuntimeError("La pérdida del adaptador restaurado no coincide")
    else:
        initial = validation(base, sets["validation"], args.device)
        model = get_peft_model(base, adapter_config(smoke=True))
        logs, restored = [], None
    if args.steps <= len(logs):
        cli.error("--steps es el número total de actualizaciones, incluidas las restauradas")
    params = [p for p in model.parameters() if p.requires_grad]
    optim = torch.optim.AdamW(params, lr=2e-4)
    if args.resume:
        optim.load_state_dict(torch.load(args.resume / "optimizer.pt", map_location=args.device, weights_only=True))
    for i in range(len(logs), args.steps):
        dst = args.output / f"step-{i + 1:03d}"
        if dst.exists():
            raise FileExistsError(dst)
        model.train()
        optim.zero_grad(set_to_none=True)
        loss = loss_on(model, sets["train"][order[i % len(order)]], args.device)
        loss.backward()
        grad = torch.nn.utils.clip_grad_norm_(params, 1.0, error_if_nonfinite=True).item()
        if not math.isfinite(loss.item()) or grad <= 0:
            raise RuntimeError("La pérdida o el gradiente no permiten una actualización")
        optim.step()
        logs.append({"step": i + 1, "sample": order[i % len(order)], "loss": loss.item(), "grad_norm": grad})
        if (i + 1) % args.checkpoint_every and i + 1 != args.steps:
            continue
        score = validation(model, sets["validation"], args.device)
        with tempfile.TemporaryDirectory(dir=args.output) as tmp:
            staging = Path(tmp) / "checkpoint"
            save_adapter(model, staging)
            torch.save(optim.state_dict(), staging / "optimizer.pt")
            report = {"identity": identity, "steps": logs, "validation_original": initial,
                      "validation_resume": restored, "validation_checkpoint": score,
                      "trainable_parameters": sum(p.numel() for p in params),
                      "adapter_sha256": digest(staging / "adapter_model.safetensors")}
            (staging / "metrics.json").write_text(json.dumps(report, indent=2) + "\n")
            os.rename(staging, dst)
        print(json.dumps(logs[-1]), f"validación {score:.6f}", flush=True)


if __name__ == "__main__":
    main()
