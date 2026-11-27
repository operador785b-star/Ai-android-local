#!/usr/bin/env bash
# Convert locally supplied checkpoint files and run the offline export acceptance suite.
set -euo pipefail
if [[ $# -ne 5 ]]; then
    printf 'Uso: %s PERFIL CHECKPOINT LLAMA_QUANTIZE BUILD_JNI DIRECTORIO_SALIDA\n' "$0" >&2
    exit 2
fi
profile=$1
checkpoint=$(realpath "$2")
quantize=$(realpath "$3")
host=$(realpath "$4")
output=$(realpath -m "$5")
case "$profile" in
    lfm3) expected=Lfm3ForCausalLM ;;
    nanbeige) expected=NanbeigeForCausalLM ;;
    *) printf 'Usa lfm3 o nanbeige.\n' >&2; exit 2 ;;
esac
cd "$(dirname "$0")/.."
test -f "$checkpoint/config.json"
test -x "$quantize"
test -f "$host/libialocal.so"
test ! -e "$output"
python3 - "$checkpoint/config.json" "$expected" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    architectures = json.load(f)["architectures"]
if sys.argv[2] not in architectures:
    raise SystemExit("La arquitectura local no coincide con el perfil elegido.")
PY
scripts/fetch-llama.sh
mkdir -p "$output"
staging=$(mktemp -d "$output/work.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
unshare -Urn -- python3 vendor/llama.cpp/convert_hf_to_gguf.py "$checkpoint" --outtype f16 --outfile "$staging/base-f16.gguf"
unshare -Urn -- "$quantize" "$staging/base-f16.gguf" "$output/model-q4_k_m.gguf" Q4_K_M
unshare -Urn -- python3 evaluation/run.py "$profile" "$output/model-q4_k_m.gguf" "$host" "$output/evaluacion.json"
