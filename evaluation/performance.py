#!/usr/bin/env python3
"""Compare cold and retained JNI sessions using fixed prompts and scripted histories."""
import argparse
import base64
import hashlib
import json
import platform
import subprocess
import tempfile
from pathlib import Path

from run import METRICS, PROFILES, chat_prompt, verify

EXTRA_METRICS = (
    "contexto_motor", "carga_modelo_ms", "creacion_contexto_ms", "tokenizacion_ms", "procesamiento_prompt_ms",
    "tokens_prompt_reutilizados", "tokens_prompt_evaluados", "modelo_reutilizado",
    "contexto_reutilizado", "rss_proceso_kib", "capacidades_arm64", "fragmentos_emitidos",
)


def measure(args, cases, mode, build, manifest):
    call = subprocess.run([
        "java", f"-Djava.library.path={build.resolve()}", "-cp", str(build.resolve()),
        "com.ialocal.android.inference.NativeBridge", "--series", str(args.gguf.resolve()),
        str(PROFILES[args.profile][0]), str(args.context), str(args.threads), str(args.batch), mode, str(manifest),
    ], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=1800)
    if call.returncode:
        raise RuntimeError(f"{mode} terminó con código {call.returncode}\n{call.stderr[-5000:]}")

    rows = [line.split("\t", 5)[1:] for line in call.stdout.splitlines() if line.startswith("IA_RESULT\t")]
    results = []
    for case, (code, raw, pieces, stream_us, encoded) in zip(cases, rows, strict=True):
        answer = base64.b64decode(encoded, validate=True).decode("utf-8", errors="replace")
        prefix = METRICS + EXTRA_METRICS[:1] if mode == "baseline" else METRICS + EXTRA_METRICS
        metrics = dict(zip(prefix, json.loads(raw), strict=True))
        fragments, stream_time = int(pieces), int(stream_us)
        metrics["emision_primero_a_ultimo_us"] = stream_time
        metrics["fragmentos_callback"] = fragments
        metrics["fragmentos_por_segundo"] = (
            round((fragments - 1) * 1_000_000 / stream_time, 3) if fragments > 1 and stream_time > 0 else None
        )
        if mode != "baseline" and int(code) in (0, 1):
            total = metrics["tokens_prompt_reutilizados"] + metrics["tokens_prompt_evaluados"]
            if total != metrics["tokens_entrada"]:
                raise ValueError(f"Conteo de tokens incoherente en {mode}: {case['id']}")
        results.append({"id": case["id"], "category": case["category"], "code": int(code),
                        "passed": int(code) in (0, 1) and verify(case, answer),
                        "answer": answer, "metrics": metrics})
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", choices=PROFILES)
    parser.add_argument("gguf", type=Path)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("optimized", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("performance-es.jsonl"))
    parser.add_argument("--threads", type=int, choices=(2, 3, 4), default=2)
    parser.add_argument("--context", type=int, choices=(1024, 2048, 4096), default=2048)
    parser.add_argument("--batch", type=int, choices=(32, 64, 128), default=64)
    parser.add_argument("--modes", nargs="+", choices=("baseline", "cold", "warm"), default=("baseline", "cold", "warm"))
    args = parser.parse_args()
    if args.batch != 64 and "baseline" in args.modes:
        parser.error("La versión de referencia solo permite lotes de 64 tokens.")

    with args.gguf.open("rb") as model:
        digest = hashlib.file_digest(model, "sha256").hexdigest()
    cases = [json.loads(line) for line in args.cases.read_text(encoding="utf-8").splitlines() if line]
    report = {"plataforma": platform.platform(), "profile": args.profile, "sha256": digest,
              "context": args.context, "threads": args.threads, "batch": args.batch, "seed": 1234,
              "suite_sha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(), "modes": {}}
    with tempfile.TemporaryDirectory() as scratch:
        directory = Path(scratch)
        manifest = directory / "prompts.tsv"
        with manifest.open("w", encoding="utf-8") as stream:
            for index, case in enumerate(cases):
                prompt = directory / f"prompt-{index}"
                prompt.write_text(chat_prompt(case, PROFILES[args.profile]), encoding="utf-8")
                stream.write(f"{case.get('conversation', index + 1)}\t{case['max_tokens']}\t{prompt}\n")
        for mode in args.modes:
            build = args.baseline if mode == "baseline" else args.optimized
            rows = measure(args, cases, mode, build, manifest)
            report["modes"][mode] = rows
            print(mode, sum(row["passed"] for row in rows), "/", len(rows), flush=True)
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
