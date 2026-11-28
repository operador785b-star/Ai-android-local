#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 4 ]]; then
    echo "Uso: android.sh MERGED GGUF_OFICIAL BUILD DIRECTORIO_NUEVO" >&2
    exit 2
fi
merged=$(realpath "$1")
original=$(realpath "$2")
build=$(realpath -m "$3")
out=$(realpath -m "$4")
cd "$(dirname "$0")/../.."
test -s "$merged/export-manifest.json"
test ! -e "$out"
mkdir -p "$build" "$(dirname "$out")"
stage=$(mktemp -d "$(dirname "$out")/.lfm3-export.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
scripts/fetch-llama.sh
llama=vendor/llama.cpp
cmake -S "$llama" -B "$build/tools" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF \
    -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build "$build/tools" --target llama-quantize -j 2
python "$llama/convert_hf_to_gguf.py" "$merged" --outtype bf16 --outfile "$stage/merged-bf16.gguf"
model=IA-Local-LFM3-1.7B-Q4_K_M.gguf
"$build/tools/bin/llama-quantize" "$stage/merged-bf16.gguf" "$stage/$model" Q4_K_M
rm "$stage/merged-bf16.gguf"
python training/lfm3/check_gguf.py "$stage/$model" "$original"
cmake -S app/src/main/cpp -B "$build/jni" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build/jni" -j 2
javac -d "$build/jni" scripts/NativeBridge.java
for variant in original candidate; do
    file="$original"
    if [[ "$variant" == candidate ]]; then file="$stage/$model"; fi
    python evaluation/run.py lfm3 "$file" "$build/jni" "$stage/$variant.json" --cases training/lfm3/holdout.jsonl
done
python training/lfm3/compare.py "$stage/original.json" "$stage/candidate.json" > "$stage/comparison.json"
cp "$merged"/{LICENSE,NOTICE.txt,export-manifest.json} "$stage/"
cp "$llama/.revision" "$stage/llama-revision.txt"
(cd "$stage" && sha256sum "$model" > SHA256SUMS)
mv -T "$stage" "$out"
