#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=b97ebdc98f6053604a19d861c08d8087601b96e0
digest=29e57a3273f25575e40859d6d7d10c9bea0b72f9e25dc19827e34cb05098239a
destination=vendor/llama.cpp
if [[ -d "$destination" ]]; then
    if [[ -f "$destination/include/llama.h" && -f "$destination/.revision" ]] &&
       [[ "$(cat "$destination/.revision")" = "$revision" ]]; then
        exit 0
    fi
    printf 'Revisa %s antes de cambiar de revisión.\n' "$destination" >&2
    exit 1
fi
mkdir -p vendor
staging=$(mktemp -d vendor/.llama.XXXXXX)
trap 'rm -rf -- "$staging"' EXIT
archive="$staging/source.tar.gz"
if [[ $# -eq 1 ]]; then
    cp -- "$1" "$archive"
else
    curl --fail --location --silent --show-error --retry 3 \
        "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/$revision" -o "$archive"
fi
printf '%s  %s\n' "$digest" "$archive" | sha256sum --check
mkdir "$staging/source"
tar -xzf "$archive" --strip-components=1 -C "$staging/source"
printf '%s\n' "$revision" > "$staging/source/.revision"
mv -T "$staging/source" "$destination"
