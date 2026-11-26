#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
    printf 'Uso: %s RUTA_MODELO_GGUF DIRECTORIO_DE_COMPILACIÓN\n' "$0" >&2
    exit 2
fi
model=$(realpath "$1")
mkdir -p "$2"
build=$(realpath "$2")
cd "$(dirname "$0")/.."
scripts/fetch-llama.sh
"${CMAKE:-cmake}" -S app/src/main/cpp -B "$build" -DCMAKE_BUILD_TYPE=Release
"${CMAKE:-cmake}" --build "$build" -j "${BUILD_JOBS:-2}"
javac -d "$build" scripts/NativeBridge.java
java -Djava.library.path="$build" -cp "$build" \
    com.ialocal.android.inference.NativeBridge "$model"
