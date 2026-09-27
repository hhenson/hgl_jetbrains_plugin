#!/usr/bin/env bash
# Copies the HGL example modules that the parser tests must accept without
# error elements from the sibling hgraph_spec and hgraph checkouts.
# Usage: tools/refresh_test_data.sh [path-to-hgraph_spec] [path-to-hgraph]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
spec="${1:-$here/../hgraph_spec}"
hgraph="${2:-$here/../hgraph}"
out="$here/src/test/testData/parser"

copy_tree() {  # copy_tree <source dir> <destination dir>
  local src="$1" dst="$2"
  rm -rf "$dst"; mkdir -p "$dst"
  find "$src" -name '*.hgl' | sort | while read -r f; do
    rel="${f#"$src"/}"
    cp "$f" "$dst/$(echo "$rel" | tr '/' '_')"
  done
}

# hgraph_spec/language/examples: the implemented forms of the language guide.
# language/stdlib/examples is excluded: the enum, switch and str() forms
# there are provisional and not yet parsed by any implementation.
copy_tree "$spec/language/examples" "$out/spec"
# hgraph/language: the C++ compiler's own examples and the compiled stdlib.
copy_tree "$hgraph/language/examples" "$out/hgraph"
copy_tree "$hgraph/language/stdlib/hgl" "$out/stdlib"

{
  echo "# Parser test data sources"
  echo
  echo "Refreshed by tools/refresh_test_data.sh on $(date -u +%Y-%m-%d)."
  echo
  echo "- spec/: hgraph_spec language/examples at $(git -C "$spec" rev-parse --short HEAD)"
  echo "- hgraph/: hgraph language/examples at $(git -C "$hgraph" rev-parse --short HEAD)"
  echo "- stdlib/: hgraph language/stdlib/hgl at $(git -C "$hgraph" rev-parse --short HEAD)"
  echo "- golden/: hand-picked modules with checked-in PSI dumps (HglParserTest)"
} > "$out/SOURCES.md"
echo "copied: $(find "$out" -name '*.hgl' | wc -l | tr -d ' ') files"
