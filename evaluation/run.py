#!/usr/bin/env python3
"""Evaluate app JNI inference with real GGUF weights. Generated code is parsed, never run."""
import argparse
import ast
import csv
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

SYSTEM = ("Eres IA Local, un asistente útil. Responde en el idioma del usuario "
          "salvo que indique lo contrario. Respeta el formato exacto que pida. "
          "Usa bloques Markdown para código si el usuario no pidió texto sin formato. "
          "Si falta información, dilo con claridad.")
PROFILES = {"qwen35": (1, "<think>\n\n</think>\n\n"), "lfm3": (2, "<think></think>"),
            "nanbeige": (3, "<think>\n\n</think>\n\n")}
METRICS = ("tokens_entrada", "tokens_salida", "total_ms", "primer_fragmento_ms",
           "decodificacion_ms", "pico_rss_proceso_kib", "codigo_motor")


def chat_prompt(case, profile):
    def turn(role, content):
        return f"<|im_start|>{role}\n{content}<|im_end|>\n"

    prompt = turn("system", SYSTEM)
    history_prefix = "" if profile[0] == 1 else profile[1]
    for exchange in case.get("history", []):
        prompt += turn("user", exchange["user"])
        prompt += turn("assistant", history_prefix + exchange["assistant"])
    return prompt + turn("user", case["prompt"]) + f"<|im_start|>assistant\n{profile[1]}"


def verify(case, answer):
    text, expected = answer.strip(), case["expect"]
    kind = case["check"]
    if kind == "exact":
        return text.casefold() == expected.casefold()
    if kind == "literal":
        return text == expected
    if kind == "regex":
        return re.search(expected, text) is not None
    try:
        if kind == "json":
            return json.dumps(json.loads(text), sort_keys=True) == json.dumps(expected, sort_keys=True)
        if kind == "csv":
            return list(csv.reader(text.splitlines(), strict=True)) == expected
        if kind == "markdown":
            lines = text.splitlines()
            if len(lines) != len(expected) + 1 or not all("|" in line for line in lines):
                return False
            rows = [[cell.strip() for cell in line.strip().strip("|").split("|")] for line in lines]
            return (len(rows[1]) == len(expected[0])
                    and all(re.fullmatch(r":?-{3,}:?", cell) for cell in rows[1])
                    and [rows[0], *rows[2:]] == expected)
        if kind == "python":
            fenced = re.fullmatch(r"```(?:python|py)?\s*\n(.*?)\n```", text, re.DOTALL)
            tree = ast.parse(fenced[1] if fenced else text)
            fn = next((node for node in tree.body if isinstance(node, ast.FunctionDef)
                       and node.name == expected["function"]), None)
            if fn is None:
                return False
            nodes = list(ast.walk(fn))
            return (not expected["loop"] or any(isinstance(node, (ast.While, ast.For)) for node in nodes)) and not any(
                isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == fn.name for node in nodes
            )
    except (ValueError, csv.Error, SyntaxError):
        return False
    raise ValueError(f"Check desconocido: {kind}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", choices=PROFILES)
    parser.add_argument("gguf", type=Path)
    parser.add_argument("build", type=Path, help="Directorio CMake con libialocal.so y NativeBridge.class")
    parser.add_argument("report", type=Path)
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("es-local.jsonl"))
    parser.add_argument("--threads", type=int, choices=range(1, 5), default=2)
    args = parser.parse_args()
    profile = PROFILES[args.profile]
    with args.gguf.open("rb") as weights:
        digest = hashlib.file_digest(weights, "sha256").hexdigest()
    cases = [json.loads(line) for line in args.cases.read_text().splitlines() if line]
    results = []
    with tempfile.TemporaryDirectory() as temp:
        prompt_file = Path(temp) / "prompt"
        for case in cases:
            prompt_file.write_text(chat_prompt(case, profile), encoding="utf-8")
            error = None
            metrics = {}
            try:
                call = subprocess.run([
                    "java", f"-Djava.library.path={args.build.resolve()}", "-cp", str(args.build.resolve()),
                    "com.ialocal.android.inference.NativeBridge", "--eval", str(args.gguf.resolve()),
                    str(profile[0]), str(prompt_file), str(case["max_tokens"]), str(args.threads)
                ], capture_output=True, encoding="utf-8", errors="replace", timeout=180)
                samples = re.search(r"^IA_METRICS=(\[.+\])$", call.stderr, re.MULTILINE)
                if samples:
                    metrics = dict(zip(METRICS, json.loads(samples[1]), strict=True))
                    count, elapsed = metrics["tokens_salida"], metrics["decodificacion_ms"]
                    metrics["tokens_por_segundo"] = round((count - 1) * 1000 / elapsed, 2) if count > 1 and elapsed else None
                answer = call.stdout
                if call.returncode:
                    error = f"Proceso terminó con código {call.returncode}"
            except subprocess.TimeoutExpired:
                answer, error = "", "Tiempo de espera agotado"
            passed = not error and verify(case, answer)
            results.append({"id": case["id"], "category": case["category"], "passed": passed,
                            "answer": answer, "metrics": metrics, "error": error})
            print(case["id"], "OK" if passed else "FALLO", flush=True)
    args.report.write_text(json.dumps({
        "profile": args.profile, "sha256": digest, "seed": 1234, "context": 2048, "threads": args.threads,
        "suite_sha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(),
        "passed": sum(result["passed"] for result in results), "total": len(results), "results": results,
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
