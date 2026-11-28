from pathlib import Path
from corpus import HERE, digest

MODEL = "LiquidAI/LFM3-1.7B"
REV = "1da06bec6a819da10b54c67e5c90972b46d81871"
TARGETS = ["q_proj", "k_proj", "v_proj", "o_proj", "in_proj", "out_proj", "w1", "w2", "w3"]


def snapshot(local=None):
    import json
    from huggingface_hub import snapshot_download
    hashes = json.loads((HERE / "original-files.json").read_text())
    path = Path(local) if local else Path(snapshot_download(MODEL, revision=REV, allow_patterns=list(hashes)))
    for name, value in hashes.items():
        if digest(path / name) != value:
            raise ValueError(f"Archivo original inesperado: {name}")
    return path


def load(path, dtype, device):
    from transformers import AutoModelForCausalLM, AutoTokenizer
    tokenizer = AutoTokenizer.from_pretrained(path, trust_remote_code=True, local_files_only=True)
    model = AutoModelForCausalLM.from_pretrained(
        path, trust_remote_code=True, local_files_only=True, dtype=dtype, device_map={"": device},
        use_safetensors=True, attn_implementation="eager",
    )
    if model.config.model_type != "lfm3" or tokenizer.eos_token_id != model.config.eos_token_id:
        raise ValueError("Arquitectura o tokenizer inesperados")
    return model, tokenizer


def adapter_config(smoke=False):
    from peft import LoraConfig
    return LoraConfig(task_type="CAUSAL_LM", r=4 if smoke else 16, lora_alpha=8 if smoke else 32,
                      target_modules=["w2"] if smoke else TARGETS, layers_to_transform=[23] if smoke else None,
                      lora_dropout=0 if smoke else 0.05, bias="none", revision=REV)


def save_adapter(model, path):
    model.peft_config["default"].base_model_name_or_path = MODEL
    model.peft_config["default"].revision = REV
    model.save_pretrained(path, safe_serialization=True, save_embedding_layers=False)
