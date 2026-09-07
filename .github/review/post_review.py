#!/usr/bin/env python3
"""Post the reviewer's findings on a pull request as one review.

A `workflow_run` job does not know its pull request and cannot use the actions that assume one,
so the review is assembled and posted here by hand.

    python3 .github/review/post_review.py --pr 128 --commit "$SHA" \
        --diff build/review/diff.patch --findings build/review/findings.json

GitHub rejects the whole review if any one comment names a line that is not in the diff, so a
finding that does not anchor is carried into the summary instead of being dropped.
"""

import argparse
import json
import re
import subprocess
import sys

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
MAX_INLINE = 30


def anchorable_lines(diff):
    """Map each file in a unified diff to the new-file line numbers a comment may anchor to."""
    anchors = {}
    path = None
    line_no = 0
    # The hunk's own new-side length, counted down. Inside a hunk an added line reading "+++ b/x"
    # is content; outside one it is a file header, and the two are otherwise identical.
    remaining = 0
    for line in diff.splitlines():
        if remaining <= 0:
            if line.startswith("+++ "):
                name = line[4:].split("\t")[0]
                path = None if name == "/dev/null" else name[2:] if name[1:2] == "/" else name
            hunk = HUNK.match(line)
            if hunk:
                line_no = int(hunk.group(1))
                remaining = int(hunk.group(2) or 1)
            continue
        if not line:
            continue
        if line[0] in " +":
            if path is not None:
                anchors.setdefault(path, set()).add(line_no)
            line_no += 1
            remaining -= 1
    return anchors


def partition(findings, anchors):
    """Split findings into ones GitHub will accept inline and ones the summary has to carry."""
    inline, orphans = [], []
    for finding in findings:
        if not isinstance(finding, dict):
            continue
        path = str(finding.get("path", "")).lstrip("/")
        if path[:2] in ("a/", "b/"):
            path = path[2:]
        line = finding.get("line")
        body = str(finding.get("body", "")).strip()
        if not path or not body:
            continue
        entry = {"path": path, "line": line, "body": body}
        if isinstance(line, int) and line in anchors.get(path, ()):
            inline.append({"path": path, "line": line, "side": "RIGHT", "body": body})
        else:
            orphans.append(entry)
    # A wall of comments is not a review. Anything past the cap is still reported, in the summary.
    return inline[:MAX_INLINE], orphans + inline[MAX_INLINE:]


def render_body(summary, orphans):
    body = summary.strip() or "No findings."
    if orphans:
        body += "\n\n### Findings outside the diff\n"
        for orphan in orphans:
            where = f"`{orphan['path']}:{orphan['line']}`" if orphan.get("line") else f"`{orphan['path']}`"
            body += f"\n- {where} — {orphan['body']}\n"
    return body


def post(repo, pr, payload):
    subprocess.run(
        ["gh", "api", "--method", "POST", f"repos/{repo}/pulls/{pr}/reviews", "--input", "-"],
        input=json.dumps(payload),
        text=True,
        check=True,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=None)
    parser.add_argument("--pr", type=int, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--diff", required=True)
    parser.add_argument("--findings", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    with open(args.diff, encoding="utf-8", errors="replace") as handle:
        anchors = anchorable_lines(handle.read())
    with open(args.findings, encoding="utf-8") as handle:
        report = json.load(handle)
    if not isinstance(report, dict):
        sys.exit("The reviewer's findings are not a JSON object.")

    inline, orphans = partition(report.get("findings") or [], anchors)
    payload = {
        "commit_id": args.commit,
        "event": "COMMENT",
        "body": render_body(str(report.get("summary", "")), orphans),
        "comments": inline,
    }
    if args.dry_run:
        print(json.dumps(payload, indent=2))
        return

    repo = args.repo or subprocess.run(
        ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    post(repo, args.pr, payload)
    print(f"Posted {len(inline)} inline comment(s) and a summary on #{args.pr}.")


if __name__ == "__main__":
    main()
