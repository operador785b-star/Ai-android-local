import argparse
import json
from collections import Counter
from pathlib import Path
from corpus import HERE, digest, rows


def compare(reference, candidate):
    cases = rows(HERE / "holdout.jsonl")
    expected = [(r["id"], r["category"]) for r in cases]
    for report in (reference, candidate):
        meta = report.get("identity", report)
        if meta["suite_sha256"] != digest(HERE / "holdout.jsonl"):
            raise ValueError("Bancos distintos")
        if [(r["id"], r["category"]) for r in report["results"]] != expected:
            raise ValueError("Banco incompleto o desordenado")
    if "identity" in reference and "identity" in candidate:
        keys = ["revision", "backend", "sampling", "dtype", "device", "threads", "system"]
        if any(reference["identity"][k] != candidate["identity"][k] for k in keys):
            raise ValueError("Parámetros de Transformers distintos")
    elif "identity" not in reference and "identity" not in candidate:
        keys = ["profile", "seed", "context", "threads", "json_object", "system_sha256"]
        if any(reference[k] != candidate[k] for k in keys):
            raise ValueError("Parámetros de JNI distintos")
    else:
        raise ValueError("Compara pares del mismo motor; informa por separado el impacto de cuantizar")
    result = {}
    for label, report in (("base", reference), ("candidate", candidate)):
        counts = Counter(r["category"] for r in report["results"] if r["passed"] and not r.get("error"))
        result[label] = {cat: counts[cat] for _, cat in expected}
    deltas = [b - result["base"][c] for c, b in result["candidate"].items()]
    result["advances_to_manual_review"] = min(deltas) >= 0 and sum(deltas) > 0
    result["regressions"] = [x["id"] for x, y in zip(reference["results"], candidate["results"])
                             if x["passed"] and not y["passed"]]
    return result


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("reference", type=Path)
    cli.add_argument("candidate", type=Path)
    args = cli.parse_args()
    print(json.dumps(compare(*(json.loads(p.read_text()) for p in (args.reference, args.candidate))),
                     ensure_ascii=False, indent=2))
