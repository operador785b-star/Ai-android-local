import sys
import unittest
from fractions import Fraction
from corpus import HERE, ROOT, rows, splits

sys.path.insert(0, str(ROOT / "evaluation"))
from functions_es import function_passes
from run import verify


class SpanishBench(unittest.TestCase):
    def test_splits_and_math_oracles(self):
        sets = splits()
        self.assertGreaterEqual(len(sets["train"]), 20)
        self.assertGreaterEqual(len(sets["validation"]), 6)
        cases = rows(HERE / "holdout.jsonl")
        math = [136-58-39, 84*3//4+11, 8*Fraction("7.50")/3, 45*38//15, 54*60//90,
                (43-7)//2, 8, (23-3)//5]
        self.assertEqual(8**2-9, 55)
        self.assertEqual([int(r["expect"]) for r in cases if r["category"] == "matematicas"], math)
        train = {r["family"]: r["answer"] for r in sets["train"]}
        self.assertIn(str(350-125), train["saldo"])
        self.assertEqual(len({r["id"] for r in cases}), len(cases))

    def test_format_rejects_extra_prose(self):
        cases = rows(HERE / "holdout.jsonl")
        case = next(c for c in cases if c["check"] == "csv")
        self.assertTrue(verify(case, 'producto,nota\npan,"blanco, integral"'))
        self.assertFalse(verify(case, 'producto,nota\npan,blanco, integral'))
        case = next(c for c in cases if c["check"] == "json")
        self.assertTrue(verify(case, '{"colores": ["rojo","gris"]}'))
        self.assertFalse(verify(case, 'Aquí está: {"colores":["rojo","gris"]}'))

    def test_programs(self):
        gold = ["def es_impar(n):\n    return n % 2 != 0",
                "fun cuadrado(x: Int): Int = x * x",
                "function diferencia(a, b) { return a - b; }",
                "def segundos(h, m):\n    return (h * 60 + m) * 60"]
        cases = [c for c in rows(HERE / "holdout.jsonl") if c["check"] == "function"]
        for case, source in zip(cases, gold, strict=True):
            with self.subTest(case=case["id"]):
                self.assertTrue(function_passes(source, case["expect"]))
                self.assertFalse(function_passes(source.replace("*", "+").replace("!=", "==").replace("a - b", "a + b"), case["expect"]))
        self.assertFalse(function_passes("def es_impar(n):\n    return print(n)", cases[0]["expect"]))


if __name__ == "__main__":
    unittest.main()
