import json
import unittest

from extract_findings import extract

REPORT = {"summary": "nothing found", "findings": [{"path": "a.java", "line": 3, "body": "b"}]}


def envelope(result, **extra):
    return json.dumps({"type": "result", "is_error": False, "result": result, **extra})


class Extract(unittest.TestCase):
    def test_a_bare_json_object(self):
        self.assertEqual(extract(envelope(json.dumps(REPORT))), REPORT)

    def test_a_fenced_json_object(self):
        fenced = "```json\n" + json.dumps(REPORT) + "\n```"
        self.assertEqual(extract(envelope(fenced)), REPORT)

    def test_prose_either_side_of_the_object(self):
        chatty = "Here is my review:\n" + json.dumps(REPORT) + "\nHope that helps."
        self.assertEqual(extract(envelope(chatty)), REPORT)

    def test_a_brace_inside_a_string_does_not_end_the_object(self):
        report = {"summary": "the body said }", "findings": []}
        self.assertEqual(extract(envelope(json.dumps(report))), report)

    def test_missing_findings_normalises_to_empty(self):
        self.assertEqual(extract(envelope('{"summary": "clear"}'))["findings"], [])

    def test_a_brace_pair_in_the_preamble_does_not_win(self):
        chatty = "First I ran `if (x) { y(); }` in my head.\n" + json.dumps(REPORT)
        self.assertEqual(extract(envelope(chatty)), REPORT)

    def test_a_stray_closing_brace_before_the_object_does_not_blind_the_scan(self):
        self.assertEqual(extract(envelope("} oops\n" + json.dumps(REPORT))), REPORT)

    def test_an_errored_session_raises(self):
        with self.assertRaises(ValueError):
            extract(envelope("whatever", is_error=True))

    def test_a_result_with_no_object_raises(self):
        with self.assertRaises(ValueError):
            extract(envelope("I could not review this."))

    def test_an_envelope_that_is_not_json_raises(self):
        with self.assertRaises(ValueError):
            extract("not json at all")


if __name__ == "__main__":
    unittest.main()
