#!/usr/bin/env python3
"""Exercise recurrent-cache restoration, chat isolation and cancellation with real LFM3 weights."""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from run import PROFILES, chat_prompt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("gguf", type=Path)
    parser.add_argument("build", type=Path)
    args = parser.parse_args()

    fixture = Path(__file__).with_name("performance-es.jsonl")
    cases = {row["id"]: row for row in map(json.loads, fixture.read_text(encoding="utf-8").splitlines())}
    first, next_turn = cases["memoria-1"], cases["memoria-2"]
    # A different assistant message forces the recurrent checkpoint path.
    next_turn["history"][0]["assistant"] = "Guardé el código de la excursión."
    with tempfile.TemporaryDirectory() as scratch:
        prompts = []
        for number, case in enumerate((first, next_turn), start=1):
            path = Path(scratch) / f"prompt-{number}"
            path.write_text(chat_prompt(case, PROFILES["lfm3"]), encoding="utf-8")
            prompts.append(str(path))
        build = args.build.resolve()
        result = subprocess.run([
            "java", f"-Djava.library.path={build}", "-cp", str(build),
            "com.ialocal.android.inference.NativeBridge", "--cache-check", str(args.gguf.resolve()), "2", *prompts,
        ], capture_output=True, encoding="utf-8", errors="replace", timeout=240)
        if result.returncode:
            raise RuntimeError(result.stderr[-5000:])
        print(result.stdout, end="")


if __name__ == "__main__":
    main()
