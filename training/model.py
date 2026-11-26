"""Model identity shared between training, evaluation and export."""

BASE_MODEL = "Qwen/Qwen3.5-2B"
BASE_REVISION = "15852e8c16360a2fea060d615a32b45270f8a8fc"


def tokenizer_for(source=BASE_MODEL, revision=BASE_REVISION):
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(source, revision=revision, trust_remote_code=False)
    if tokenizer.chat_template is None or tokenizer.eos_token_id is None:
        raise ValueError("Falta la plantilla de chat o el token de fin")
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    tokenizer.padding_side = "right"
    return tokenizer


def load_base(dtype, **options):
    from transformers import Qwen3_5ForConditionalGeneration

    return Qwen3_5ForConditionalGeneration.from_pretrained(
        BASE_MODEL, revision=BASE_REVISION, dtype=dtype, use_safetensors=True, **options
    )
