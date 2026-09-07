#!/usr/bin/env python3
"""Pull the reviewer's findings object out of a `claude -p --output-format json` envelope.

    python3 .github/review/extract_findings.py build/review/raw.json > findings.json

The prompt asks for one bare JSON object, but a model that wraps it in a fence or a sentence has
still done the review, so the object is found rather than demanded.
"""

import json
import sys


def _objects(text):
    """Every brace-balanced span in `text`, in order, ignoring braces inside strings.

    Prose either side of the object is expected, and prose contains braces — `if (x) { y; }` in a
    sentence is a balanced span that is not JSON — so candidates are yielded rather than the first
    one being taken on faith.
    """
    depth = 0
    start = None
    in_string = False
    escaped = False
    for index, char in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            if depth == 0:
                start = index
            depth += 1
        elif char == "}":
            # A stray closing brace would otherwise drive the depth negative, after which no
            # opening brace can ever balance and the real object is never seen.
            if depth == 0:
                continue
            depth -= 1
            if depth == 0 and start is not None:
                yield text[start : index + 1]


def extract(envelope_text):
    try:
        envelope = json.loads(envelope_text)
    except json.JSONDecodeError as error:
        raise ValueError(f"The session did not print a JSON envelope: {error}") from error
    if envelope.get("is_error"):
        raise ValueError(f"The review session failed: {envelope.get('result')!r}")
    result = envelope.get("result")
    if not isinstance(result, str):
        raise ValueError("The review session printed no result.")

    for candidate in _objects(result):
        try:
            report = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        report.setdefault("summary", "")
        report.setdefault("findings", [])
        return report
    raise ValueError(f"No findings object in the session's answer: {result[:500]!r}")


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as handle:
        json.dump(extract(handle.read()), sys.stdout, indent=2)
        sys.stdout.write("\n")
