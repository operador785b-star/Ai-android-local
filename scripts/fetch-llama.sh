#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=171527522610996f5f0acf12334e185156ca8f44
digest=00fe7d900aac2906edb7fee0a11c7255709f72c022ac1ba54a58984562eaf60c
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
