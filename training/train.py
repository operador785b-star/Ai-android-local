import argparse
import hashlib
import json
from pathlib import Path

from data import clean, key, prompt_text, read_jsonl
from model import BASE_MODEL, BASE_REVISION, load_base, tokenizer_for


def encode_split(rows, tokenizer, max_length, name):
    samples = []
    skipped = 0
    for row in rows:
        prefix = tokenizer.encode(prompt_text(tokenizer, row["instruction"]), add_special_tokens=False)
        response = tokenizer.encode(row["response"], add_special_tokens=False) + [tokenizer.eos_token_id]
        if len(prefix) + len(response) > max_length:
            skipped += 1
            continue
        samples.append({
            "input_ids": prefix + response,
            "attention_mask": [1] * (len(prefix) + len(response)),
            "labels": [-100] * len(prefix) + response,
        })
    print(f"{name}: {len(samples)} ejemplos, {skipped} fuera del límite de longitud")
    if not samples:
        raise ValueError(f"{name} no tiene ejemplos dentro del límite de longitud")
    return samples


def train(args):
    import torch
    from peft import LoraConfig, TaskType, get_peft_model, prepare_model_for_kbit_training
    from transformers import BitsAndBytesConfig, DataCollatorForTokenClassification, Trainer, TrainingArguments
    from transformers import set_seed

    if not torch.cuda.is_available() or not torch.cuda.is_bf16_supported():
        raise RuntimeError("Se requiere una GPU NVIDIA con soporte BF16, como L4 o A100")
    if args.output.exists():
        raise FileExistsError(args.output)
    if args.epochs <= 0 or args.max_length < 64:
        raise ValueError("Usa épocas positivas y al menos 64 tokens por ejemplo")
    set_seed(args.seed)
    tokenizer = tokenizer_for()
    train_rows = clean(read_jsonl(args.train))
    validation_rows = clean(read_jsonl(args.validation))
    if {key(r["instruction"]) for r in train_rows} & {key(r["instruction"]) for r in validation_rows}:
        raise ValueError("Hay instrucciones repetidas entre entrenamiento y validación")
    train_data = encode_split(train_rows, tokenizer, args.max_length, "Entrenamiento")
    validation_data = encode_split(validation_rows, tokenizer, args.max_length, "Validación")

    options = {}
    if args.mode == "qlora":
        options["device_map"] = {"": 0}
        options["quantization_config"] = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_use_double_quant=True,
            bnb_4bit_compute_dtype=torch.bfloat16,
        )
    base = load_base(torch.bfloat16, **options)
    base.config.use_cache = False
    base.config.text_config.use_cache = False
    if args.mode == "qlora":
        base = prepare_model_for_kbit_training(base, use_gradient_checkpointing=False)
    adapter = get_peft_model(base, LoraConfig(
        task_type=TaskType.CAUSAL_LM,
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        target_modules=r".*language_model\.layers\.\d+\.(?:self_attn\.(?:q_proj|k_proj|v_proj|o_proj)|"
                       r"mlp\.(?:gate_proj|up_proj|down_proj))$",
        bias="none",
    ))
    adapter.print_trainable_parameters()
    run = Trainer(
        model=adapter,
        processing_class=tokenizer,
        args=TrainingArguments(
            output_dir=str(args.output / "checkpoints"),
            bf16=True,
            seed=args.seed,
            data_seed=args.seed,
            num_train_epochs=args.epochs,
            learning_rate=1e-4,
            per_device_train_batch_size=1,
            per_device_eval_batch_size=1,
            gradient_accumulation_steps=8,
            gradient_checkpointing=True,
            gradient_checkpointing_kwargs={"use_reentrant": False},
            eval_strategy="epoch",
            save_strategy="epoch",
            save_total_limit=1,
            prediction_loss_only=True,
            logging_steps=10,
            report_to="none",
        ),
        train_dataset=train_data,
        eval_dataset=validation_data,
        data_collator=DataCollatorForTokenClassification(tokenizer, pad_to_multiple_of=8),
    )
    metrics = run.train().metrics
    metrics.update(run.evaluate())
    adapter.save_pretrained(args.output / "adapter", safe_serialization=True)
    tokenizer.save_pretrained(args.output / "adapter")
    manifest = {
        "base_model": BASE_MODEL, "base_revision": BASE_REVISION, "mode": args.mode,
        "seed": args.seed, "max_length": args.max_length, "epochs": args.epochs, "metrics": metrics,
        "examples": {"train": len(train_data), "validation": len(validation_data)},
        "data_sha256": {
            "train": hashlib.sha256(args.train.read_bytes()).hexdigest(),
            "validation": hashlib.sha256(args.validation.read_bytes()).hexdigest(),
        },
    }
    (args.output / "training-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", type=Path, required=True)
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=["lora", "qlora"], default="qlora")
    parser.add_argument("--max-length", type=int, default=1024)
    parser.add_argument("--epochs", type=float, default=2)
    parser.add_argument("--seed", type=int, default=42)
    train(parser.parse_args())
