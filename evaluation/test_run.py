import unittest

from run import json_scores


class JsonScoresTest(unittest.TestCase):
    expected = {"x": 7}

    def test_correct_numbers_in_markdown_fail_only_format(self):
        for answer in ('```json\n{"x":7}\n```', '{"x":7}\n```'):
            with self.subTest(answer=answer):
                self.assertEqual(json_scores(answer, self.expected), {"format": False, "values": True})

    def test_wrong_numbers_in_valid_json_fail_only_values(self):
        self.assertEqual(json_scores('{"x":6}', self.expected), {"format": True, "values": False})

    def test_rejects_wrong_keys_types_and_duplicate_keys(self):
        for answer in ('{"y":7}', '{"x":true}', '{"x":7.0}', '{"x":1,"x":7}'):
            with self.subTest(answer=answer):
                self.assertEqual(json_scores(answer, self.expected), {"format": False, "values": False})


if __name__ == "__main__":
    unittest.main()
