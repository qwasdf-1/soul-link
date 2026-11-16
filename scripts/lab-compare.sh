#!/usr/bin/env bash
# Paired build-against-build cost: this checkout (candidate) against another git ref (baseline).
#
#   scripts/lab-compare.sh <baseline-ref> [gradle args...]
#
# Builds the baseline in a temporary git worktree with the same Gradle arguments, takes the Soul Link
# jar its prepareIntegrationServer installed, and runs the mc-lab measurement
# (testing/tests/test_90_lab_build_comparison.py) against this checkout's install. The report lands in
# build/integration/reports/lab/build-comparison.json; an interrupted run resumes when rerun with the
# same baseline. Environment: TERRA_RASA_PATH (default ../terra-rasa), MC_LAB_BLOCKS (default 3).
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: scripts/lab-compare.sh <baseline-ref> [gradle args...]" >&2
    exit 2
fi
ref=$1
shift

root=$(git rev-parse --show-toplevel)
terra_rasa=$(cd "${TERRA_RASA_PATH:-$root/../terra-rasa}" && pwd)
sha=$(git -C "$root" rev-parse --verify "$ref^{commit}")
candidate=$(git -C "$root" rev-parse HEAD)
if [ -n "$(git -C "$root" status --porcelain --untracked-files=no)" ]; then
    candidate="$candidate+dirty"
fi

worktree="$root/build/lab/baseline-worktree"
remove_worktree() {
    git -C "$root" worktree remove --force "$worktree" 2>/dev/null || rm -rf "$worktree"
    git -C "$root" worktree prune
}
if [ -e "$worktree" ]; then
    remove_worktree
fi
mkdir -p "$root/build/lab"
git -C "$root" worktree add --detach "$worktree" "$sha"
trap remove_worktree EXIT

echo "Building the baseline ($ref, $sha)"
(cd "$worktree" && ./gradlew prepareIntegrationServer -PterraRasaPath="$terra_rasa" "$@")

shopt -s nullglob
jars=("$worktree"/build/integration/server/mods/soullink-*.jar)
if [ ${#jars[@]} -ne 1 ]; then
    echo "expected one Soul Link jar in the baseline install, found ${#jars[@]}" >&2
    exit 1
fi
baseline_dir="$root/build/lab/baseline-${sha:0:12}"
rm -rf "$baseline_dir"
mkdir -p "$baseline_dir"
cp "${jars[0]}" "$baseline_dir/"
baseline_jar="$baseline_dir/$(basename "${jars[0]}")"
remove_worktree
trap - EXIT

echo "Measuring candidate $candidate against baseline $sha"
MC_LAB_BASELINE_JAR="$baseline_jar" \
MC_LAB_BASELINE_REF="$ref $sha" \
MC_LAB_CANDIDATE_REF="$candidate" \
    "$root/gradlew" -p "$root" integrationTest -PterraRasaPath="$terra_rasa" \
    -Ppytest.args="--lab -m lab -s" "$@"
