"""Progressive GPU SFT using the official TRL completion-only recipe."""
import argparse
import json
import math
from pathlib import Path

import torch
from datasets import Dataset
from transformers import set_seed
from trl import SFTConfig, SFTTrainer
from base import MODEL, REV, adapter_config, load, save_adapter, snapshot
from corpus import HERE, digest, encode, messages, splits


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("output", type=Path)
    p.add_argument("--base-local", type=Path)
    p.add_argument("--resume", type=Path)
    p.add_argument("--pilot", action="store_true", help="Un ciclo sobre el corpus de ejemplo")
    args = p.parse_args()
    if not torch.cuda.is_available():
        p.error("Selecciona una GPU en el entorno de ejecución")
    bf16 = torch.cuda.is_bf16_supported(including_emulation=False)
    set_seed(42)
    base, tokenizer = load(snapshot(args.base_local), torch.bfloat16 if bf16 else torch.float16, "cuda")
    base.config.use_cache = False
    limit = 512
    sets = {}
    for split, records in splits().items():
        for r in records:
            encode(r, tokenizer, limit)
        sets[split] = Dataset.from_list([{"prompt": messages(r),
                                         "completion": [{"role": "assistant", "content": r["answer"]}],
                                         "chat_template_kwargs": {"enable_thinking": False}} for r in records])
    args.output.mkdir(parents=True, exist_ok=True)
    identity = {"base": MODEL, "revision": REV, "data_sha256": digest(HERE / "examples.jsonl"),
                "pilot": args.pilot, "seed": 42, "max_length": limit, "bf16": bf16}
    manifest = args.output / "run.json"
    if args.resume:
        if args.resume.resolve().parent != args.output.resolve() or json.loads(manifest.read_text()) != identity:
            p.error("El checkpoint y el directorio de esta ejecución deben coincidir")
    elif any(args.output.iterdir()):
        p.error("Usa un directorio nuevo o indica --resume")
    manifest.write_text(json.dumps(identity, indent=2) + "\n")
    config = SFTConfig(output_dir=str(args.output), learning_rate=1e-4, num_train_epochs=1,
                       max_steps=-1 if args.pilot else 2, per_device_train_batch_size=1,
                       per_device_eval_batch_size=1, gradient_accumulation_steps=2 if args.pilot else 1,
                       gradient_checkpointing=True, gradient_checkpointing_kwargs={"use_reentrant": False},
                       max_length=limit, completion_only_loss=True, packing=False, bf16=bf16, fp16=not bf16,
                       save_strategy="steps", save_steps=2 if args.pilot else 1, save_total_limit=3,
                       logging_steps=1, report_to="none", seed=42, data_seed=42)
    trainer = SFTTrainer(model=base, processing_class=tokenizer, peft_config=adapter_config(),
                         args=config, train_dataset=sets["train"], eval_dataset=sets["validation"])
    outcome = trainer.train(resume_from_checkpoint=str(args.resume) if args.resume else None)
    logs = trainer.state.log_history
    gradients = [x["grad_norm"] for x in logs if "grad_norm" in x]
    metrics = trainer.evaluate()
    if not gradients or not all(math.isfinite(g) for g in gradients) or not any(g > 0 for g in gradients):
        raise RuntimeError("Sin gradientes válidos")
    if not all(math.isfinite(x) for x in (metrics["eval_loss"], outcome.training_loss)):
        raise RuntimeError("Pérdida no finita")
    save_adapter(trainer.model, args.output / "adapter")
    (args.output / "metrics.json").write_text(json.dumps(
        {"training": outcome.metrics, "validation": metrics, "history": logs,
         "gpu": torch.cuda.get_device_name(), "peak_allocated_bytes": torch.cuda.max_memory_allocated()},
        indent=2) + "\n")


if __name__ == "__main__":
    main()
