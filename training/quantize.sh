#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 3 ]]; then
    printf 'Uso: %s MODELO_FUSIONADO DIRECTORIO_DE_SALIDA DIRECTORIO_DE_COMPILACIÓN\n' "$0" >&2
    exit 2
fi
merged=$(realpath "$1")
destination=$(realpath -m "$2")
build=$(realpath -m "$3")
cd "$(dirname "$0")/.."
test -f "$merged/export-manifest.json"
test ! -e "$destination"
mkdir -p "$(dirname "$destination")"
staging=$(mktemp -d "$(dirname "$destination")/.quantize.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
scripts/fetch-llama.sh
llama=vendor/llama.cpp
cmake -S "$llama" -B "$build" -DCMAKE_BUILD_TYPE=Release -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_OPENSSL=OFF -DGGML_NATIVE=OFF
cmake --build "$build" --target llama-quantize -j "${BUILD_JOBS:-4}"
python "$llama/convert_hf_to_gguf.py" "$merged" --outtype f16 --outfile "$staging/model-f16.gguf"
"$build/bin/llama-quantize" "$staging/model-f16.gguf" "$staging/IA-Local-Qwen3.5-2B-Q4_K_M.gguf" Q4_K_M
rm "$staging/model-f16.gguf"
cp "$merged/export-manifest.json" "$staging/"
cp app/src/main/res/raw/qwen_license.txt "$staging/LICENSE-QWEN.txt"
cp "$llama/.revision" "$staging/llama-revision.txt"
(cd "$staging" && sha256sum IA-Local-Qwen3.5-2B-Q4_K_M.gguf > SHA256SUMS)
mv -T "$staging" "$destination"
