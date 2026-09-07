#!/usr/bin/env bash
# #18's PR review agent: a normal Claude Code session with a different opening prompt, run over a
# pull request's own tree so it loads `CLAUDE.md` and the ADRs the way any session here does.
#
#   ANTHROPIC_API_KEY=... .github/review/review.sh 128
#
# Writes $OUT/diff.patch and $OUT/findings.json; posting them is post_review.py's job. The session
# is `--restricted` with three read-only tools, so a pull request cannot talk it into running a
# command, reaching the network, or reading the key it is holding.
set -euo pipefail

PR=${1:?usage: review.sh <pr-number> [out-dir]}
OUT=${2:-build/review}
MODEL=${REVIEW_MODEL:-opus}
# The commit CI went green on. The Environment gate can park this job for hours, and the branch
# may move while it waits, so the caller pins the commit rather than the script re-resolving a
# head that no longer matches the run that admitted it.
HEAD_SHA=${REVIEW_HEAD_SHA:-}
# 400 KB of patch is already more than a reviewer can hold. Past that the tail is dropped and the
# session is told so, rather than the run failing on a green-but-enormous branch.
MAX_DIFF_BYTES=${MAX_DIFF_BYTES:-400000}

cd "$(dirname "$0")/../.."
REPO_ROOT=$(pwd)
PROMPT="$REPO_ROOT/.github/review/prompt.md"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

read -r BASE_SHA RESOLVED_HEAD < <(gh pr view "$PR" --json baseRefOid,headRefOid \
  --jq '[.baseRefOid, .headRefOid] | @tsv')
HEAD_SHA=${HEAD_SHA:-$RESOLVED_HEAD}
echo "$HEAD_SHA" > "$OUT/head.sha"

# `pull/N/head` rather than the branch name: it exists for forks too, and it cannot be a ref the
# pull request author chose.
git fetch --no-tags --quiet origin "pull/$PR/head"
for sha in "$BASE_SHA" "$HEAD_SHA"; do
  git cat-file -e "$sha^{commit}" 2>/dev/null || git fetch --no-tags --quiet origin "$sha"
done
git diff --merge-base "$BASE_SHA" "$HEAD_SHA" > "$OUT/diff.patch"

if [ ! -s "$OUT/diff.patch" ]; then
  echo "#$PR changes nothing against its base." >&2
  printf '{"summary": "This pull request has an empty diff against its base.", "findings": []}\n' \
    > "$OUT/findings.json"
  exit 0
fi

# The pull request's tree, checked out beside the workspace rather than over it: the prompt and
# these scripts have to stay the versions on `main` that the workflow was approved with.
TREE=$(mktemp -d)
trap 'git worktree remove --force "$TREE" 2>/dev/null || true; rm -rf "$TREE"' EXIT
git worktree add --quiet --detach "$TREE" "$HEAD_SHA"

{
  cat "$PROMPT"
  echo
  echo "## The diff"
  echo
  echo '```diff'
  head -c "$MAX_DIFF_BYTES" "$OUT/diff.patch"
  echo '```'
  if [ "$(wc -c < "$OUT/diff.patch")" -gt "$MAX_DIFF_BYTES" ]; then
    echo
    echo "The diff was truncated at $MAX_DIFF_BYTES bytes. Say so in your summary."
  fi
} > "$OUT/prompt.txt"

echo "Reviewing #$PR ($BASE_SHA..$HEAD_SHA) with $MODEL ..." >&2
# On stdin, not in argv: Linux caps a single argument at 128 KiB whatever ARG_MAX says, and the
# prompt carries the patch.
(cd "$TREE" && claude -p \
  --model "$MODEL" \
  --restricted \
  --strict-mcp-config \
  --tools Read,Grep,Glob \
  --output-format json < "$OUT/prompt.txt") > "$OUT/raw.json"

python3 "$REPO_ROOT/.github/review/extract_findings.py" "$OUT/raw.json" > "$OUT/findings.json"
echo "Findings in $OUT/findings.json" >&2
