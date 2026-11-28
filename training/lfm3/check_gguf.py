"""Compare GGUF tokenizer tables with the installed official Q4, plus model and template metadata."""
import argparse
import json
import sys
from pathlib import Path
from corpus import ROOT, HERE, digest

sys.path.insert(0, str(ROOT / "vendor/llama.cpp/gguf-py"))
from gguf import GGUFReader, LlamaFileType


def check(model, official):
    if digest(official) != "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4":
        raise ValueError("La referencia debe ser el Q4_K_M oficial del catálogo")
    reader, ref = GGUFReader(model), GGUFReader(official)
    # Reads only the mapping tables and headers. mmap keeps weights on disk.
    for key in ("general.architecture", "general.file_type", "tokenizer.ggml.model", "tokenizer.ggml.pre",
                "tokenizer.ggml.tokens", "tokenizer.ggml.token_type", "tokenizer.ggml.merges",
                "tokenizer.ggml.bos_token_id", "tokenizer.ggml.eos_token_id", "tokenizer.chat_template"):
        lhs, rhs = reader.get_field(key), ref.get_field(key)
        if not lhs or not rhs or lhs.contents() != rhs.contents():
            raise ValueError(f"Metadato diferente: {key}")
    if reader.get_field("general.architecture").contents() != "lfm3":
        raise ValueError("Arquitectura incorrecta")
    if reader.get_field("general.file_type").contents() != LlamaFileType.MOSTLY_Q4_K_M:
        raise ValueError("La cuantización no es Q4_K_M")
    import hashlib
    template = reader.get_field("tokenizer.chat_template").contents().encode()
    if hashlib.sha256(template).hexdigest() != json.loads((HERE / "original-files.json").read_text())["chat_template.jinja"]:
        raise ValueError("Plantilla diferente de la revisión entrenable")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("gguf", type=Path)
    p.add_argument("official", type=Path)
    args = p.parse_args()
    check(args.gguf, args.official)
    print("GGUF LFM3 Q4_K_M, vocabulario y plantilla comprobados")
