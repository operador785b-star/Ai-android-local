"""Run a bounded, pure arithmetic function from a model answer on fixed test vectors."""
import ast
import json
import re
import subprocess
import tempfile
from pathlib import Path


def function_passes(answer, spec):
    source = answer.strip()
    fence = re.fullmatch(r"\x60\x60\x60(?:python|py|kotlin|kt|javascript|js)?\s*\n(.*?)\n\x60\x60\x60", source, re.S)
    source = fence[1] if fence else source
    name, args = spec["name"], spec["args"]
    names = r"\s*,\s*".join(map(re.escape, args))
    if spec["language"] == "python":
        match = re.fullmatch(rf"def\s+{name}\(\s*{names}\s*\)\s*:\s+return\s+([^\n]+)", source)
    elif spec["language"] == "kotlin":
        params = r"\s*,\s*".join(a + r"\s*:\s*Int" for a in args)
        match = re.fullmatch(rf"fun\s+{name}\(\s*{params}\s*\)\s*(?::\s*Int\s*)?=\s*(.+)", source)
    else:
        match = re.fullmatch(rf"function\s+{name}\(\s*{names}\s*\)\s*\{{\s*return\s+([^;}}\n]+);?\s*\}};?", source)
    if not match or len(match[1]) > 100:
        return False
    expr = match[1].strip()
    if not re.fullmatch(r"[A-Za-z_0-9 ()+*%=!\-]+", expr):
        return False
    try:
        tree = ast.parse(expr, mode="eval")
    except (ValueError, SyntaxError):
        return False
    allowed = (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Compare, ast.Add, ast.Sub, ast.Mult,
               ast.Mod, ast.USub, ast.UAdd, ast.Eq, ast.NotEq, ast.Name, ast.Load, ast.Constant)
    for node in ast.walk(tree):
        if not isinstance(node, allowed):
            return False
        if isinstance(node, ast.Name) and node.id not in args:
            return False
        if isinstance(node, ast.Constant) and (type(node.value) is not int or abs(node.value) > 10000):
            return False
    try:
        tests = spec["tests"]
        if spec["language"] == "python":
            code = compile(tree, "<funcion>", "eval")
            values = [eval(code, {"__builtins__": {}}, dict(zip(args, xs, strict=True))) for xs, _ in tests]
            return all(type(v) is type(y) and v == y for v, (_, y) in zip(values, tests))
        calls = [f"{name}({', '.join(map(str, xs))})" for xs, _ in tests]
        if spec["language"] == "javascript":
            script = source + "\nconsole.log(JSON.stringify([" + ",".join(calls) + "]));"
            result = subprocess.run(["node", "-e", script], capture_output=True, text=True, timeout=8)
            return result.returncode == 0 and json.loads(result.stdout) == [y for _, y in tests]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Check.kt"
            path.write_text(source + "\nfun main() { " + "\n".join(f"println({c})" for c in calls) + " }")
            compiled = subprocess.run(["kotlinc", str(path), "-d", tmp], capture_output=True, timeout=40)
            if compiled.returncode:
                return False
            result = subprocess.run(["kotlin", "-cp", tmp, "CheckKt"], capture_output=True, text=True, timeout=10)
            return result.returncode == 0 and result.stdout.splitlines() == [str(y) for _, y in tests]
    except (ArithmeticError, ValueError, subprocess.TimeoutExpired):
        return False
