#!/bin/sh
# Checks for the repo's technical docs: ADRs in docs/adr/, the architecture page,
# README links and every relative Markdown link. CI runs it (.github/workflows/docs.yml).
#
#   sh scripts/test-docs.sh
#
# Needs only POSIX sh, git, tar, grep, sed and awk. Each check runs against a root directory,
# so the self-checks at the end can break throwaway copies and confirm that the
# checks fail when they should. The repo itself is never modified.

set -u

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT INT TERM

passed=0
failed=0

pass() {
  passed=$((passed + 1))
  echo "PASS  $1"
}

fail() {
  failed=$((failed + 1))
  echo "FAIL  $1: $2"
}

VALID_STATUS='^(Proposed|Accepted|Deprecated|Superseded by [0-9]{4})$'

# The ADRs, sorted, one path per line: every .md in docs/adr/ except the index and
# the template, so a misnamed ADR (e.g. 05-foo.md) still gets checked and fails.
adr_files() {
  find "$1/docs/adr" -maxdepth 1 -type f -name '*.md' ! -name README.md ! -name 0000-template.md 2>/dev/null | sort
}

# Writes the ADR list to a temp file and prints its path, so callers can loop with
# `while IFS= read -r f; do ...; done <"$list"`: no word splitting on paths with
# spaces, and the loop runs in the current shell, so it can set rc.
adr_list() {
  l=$(mktemp "$WORK_DIR/adrs.XXXXXX")
  adr_files "$1" >"$l"
  echo "$l"
}

# Value of a "Field: value" line near the top of an ADR, e.g. "Status: Accepted".
adr_field() {
  sed -n "s/^$2: *//p" "$1" | head -1
}

# --- checks: each prints its problems and returns non-zero on failure ---------

check_template() {
  t="$1/docs/adr/0000-template.md"
  [ -f "$t" ] || { echo "docs/adr/0000-template.md is missing"; return 1; }
  rc=0
  for section in Context 'Options considered' Decision Consequences; do
    grep -qx "## $section" "$t" || { echo "template has no '## $section'"; rc=1; }
  done
  grep -qx 'Status: Proposed' "$t" || { echo "template has no 'Status: Proposed' line"; rc=1; }
  grep -qx 'Date: YYYY-MM-DD' "$t" || { echo "template has no 'Date: YYYY-MM-DD' line"; rc=1; }
  return $rc
}

check_numbering() {
  list=$(adr_list "$1")
  [ -s "$list" ] || { echo "no numbered ADRs in docs/adr/"; return 1; }
  rc=0
  expected=1
  while IFS= read -r f; do
    base=$(basename "$f")
    if ! echo "$base" | grep -Eq '^[0-9]{4}-[a-z0-9]+(-[a-z0-9]+)*\.md$'; then
      echo "$base: name is not NNNN-kebab-case-title.md"
      rc=1
      continue
    fi
    num=$(echo "$base" | cut -c1-4 | sed 's/^0*//')
    if [ "$num" -ne "$expected" ]; then
      echo "$base: expected number $(printf '%04d' "$expected") (gap or duplicate)"
      rc=1
    fi
    expected=$((num + 1))
  done <"$list"
  return $rc
}

check_titles() {
  rc=0
  list=$(adr_list "$1")
  while IFS= read -r f; do
    base=$(basename "$f")
    num=$(echo "$base" | cut -c1-4)
    first=$(grep -m1 '^# ' "$f")
    echo "$first" | grep -Eq "^# $num\. .+" || { echo "$base: first heading is '$first', expected '# $num. <title>'"; rc=1; }
  done <"$list"
  return $rc
}

check_date_and_status() {
  rc=0
  list=$(adr_list "$1")
  while IFS= read -r f; do
    base=$(basename "$f")
    date=$(adr_field "$f" Date)
    echo "$date" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' || { echo "$base: Date '$date' is not YYYY-MM-DD"; rc=1; }
    status=$(adr_field "$f" Status)
    echo "$status" | grep -Eq "$VALID_STATUS" || { echo "$base: Status '$status' is not one of Proposed, Accepted, Deprecated, Superseded by NNNN"; rc=1; }
  done <"$list"
  return $rc
}

check_sections() {
  rc=0
  list=$(adr_list "$1")
  while IFS= read -r f; do
    base=$(basename "$f")
    for section in Context Decision Consequences; do
      grep -qx "## $section" "$f" || { echo "$base: no '## $section' section"; rc=1; }
    done
  done <"$list"
  return $rc
}

# Each ADR has exactly one index row whose first cell is "[NNNN](its file)", and
# that row's last cell is the ADR's Status. Rows that only mention it don't count.
check_index() {
  index="$1/docs/adr/README.md"
  [ -f "$index" ] || { echo "docs/adr/README.md is missing"; return 1; }
  rc=0
  list=$(adr_list "$1")
  while IFS= read -r f; do
    base=$(basename "$f")
    num=$(echo "$base" | cut -c1-4)
    status=$(adr_field "$f" Status)
    rows=$(awk -F'|' -v cell="[$num]($base)" '
      { first = $2; gsub(/^[ \t]+|[ \t]+$/, "", first) }
      first == cell { last = $(NF - 1); gsub(/^[ \t]+|[ \t]+$/, "", last); print last }
    ' "$index")
    count=$(printf '%s' "$rows" | grep -c '^')
    if [ "$count" -ne 1 ]; then
      echo "index has $count rows for [$num]($base), expected 1"
      rc=1
    elif [ "$rows" != "$status" ]; then
      echo "index row for $base says '$rows', the ADR says '$status'"
      rc=1
    fi
  done <"$list"
  return $rc
}

check_architecture() {
  a="$1/docs/architecture.md"
  [ -f "$a" ] || { echo "docs/architecture.md is missing"; return 1; }
  n=$(grep -c '^```mermaid$' "$a")
  [ "$n" -ge 2 ] || { echo "expected at least 2 mermaid diagrams (components, RAG flow), found $n"; return 1; }
}

check_readme_links() {
  r="$1/README.md"
  rc=0
  grep -q '](docs/architecture.md)' "$r" || { echo "README doesn't link docs/architecture.md"; rc=1; }
  grep -Eq '\]\(docs/adr/?(README\.md)?\)' "$r" || { echo "README doesn't link docs/adr/"; rc=1; }
  grep -q 'localhost:8080/swagger-ui.html' "$r" || { echo "README doesn't mention the Swagger UI URL"; rc=1; }
  return $rc
}

# Prints every link target in a Markdown file, one per line: inline links (with
# titles, <...> targets and balanced parentheses), reference definitions, and HTML
# href/src. Skips fenced code blocks and inline code spans.
md_links() {
  awk '
    /^[[:space:]]*```/ { fence = !fence; next }
    fence { next }
    {
      line = $0
      gsub(/`[^`]*`/, "", line)

      # Reference definition: [label]: target "title"
      if (line ~ /^ ? ? ?\[/) {
        p = index(line, "]:")
        if (p > 0 && index(line, "]") == p) {
          rest = substr(line, p + 2)
          sub(/^[ \t]+/, "", rest)
          if (substr(rest, 1, 1) == "<") print substr(rest, 2, index(rest, ">") - 2)
          else { sub(/[ \t].*$/, "", rest); if (rest != "") print rest }
          next
        }
      }

      tmp = line
      while (match(tmp, /(href|src)="[^"]*"/)) {
        m = substr(tmp, RSTART, RLENGTH)
        sub(/^(href|src)="/, "", m)
        sub(/"$/, "", m)
        print m
        tmp = substr(tmp, RSTART + RLENGTH)
      }

      tmp = line
      while ((i = index(tmp, "](")) > 0) {
        tmp = substr(tmp, i + 2)
        if (substr(tmp, 1, 1) == "<") {
          e = index(tmp, ">")
          if (e == 0) break
          print substr(tmp, 2, e - 2)
          tmp = substr(tmp, e + 1)
          continue
        }
        depth = 0
        out = ""
        n = length(tmp)
        for (k = 1; k <= n; k++) {
          c = substr(tmp, k, 1)
          if (c == "(") depth++
          else if (c == ")") { if (depth == 0) break; depth-- }
          out = out c
        }
        tmp = substr(tmp, k + 1)
        sub(/[ \t].*$/, "", out)
        if (out != "") print out
      }
    }
  ' "$1"
}

# Prints the anchors a Markdown file offers: GitHub heading slugs (lowercase,
# punctuation dropped, spaces to hyphens, -1/-2 for duplicates) and HTML id/name.
# Non-ASCII letters are dropped, where GitHub keeps them: avoid linking to
# headings that contain them.
md_anchors() {
  awk '
    /^[[:space:]]*```/ { fence = !fence; next }
    fence { next }
    /^#+ / {
      h = $0
      sub(/^#+ +/, "", h)
      sub(/ +#+ *$/, "", h)
      h = tolower(h)
      gsub(/[^a-z0-9 _-]/, "", h)
      gsub(/ /, "-", h)
      if (h in seen) { seen[h]++; print h "-" seen[h] } else { seen[h] = 0; print h }
    }
    {
      tmp = $0
      while (match(tmp, /(id|name)="[^"]*"/)) {
        m = substr(tmp, RSTART, RLENGTH)
        sub(/^(id|name)="/, "", m)
        sub(/"$/, "", m)
        print m
        tmp = substr(tmp, RSTART + RLENGTH)
      }
    }
  ' "$1"
}

# Checks one link target found in $2 (relative to root $1): the file exists and,
# for a Markdown target, the #anchor matches one of its headings.
check_link() {
  l_root=$1
  l_rel=$2
  l_t=$3
  case "$l_t" in
    *://* | mailto:* | tel:*) return 0 ;;
  esac
  l_anchor=""
  case "$l_t" in
    *'#'*) l_anchor=${l_t#*#} ;;
  esac
  l_path=${l_t%%#*}
  l_path=${l_path%%\?*}
  l_path=$(printf '%s' "$l_path" | sed 's/%20/ /g')
  if [ -z "$l_path" ]; then
    l_file="$l_root/$l_rel"
  else
    case "$l_path" in
      /*) l_file="$l_root$l_path" ;;
      *) l_file="$l_root/$(dirname "$l_rel")/$l_path" ;;
    esac
  fi
  [ -e "$l_file" ] || { echo "$l_rel: broken link '$l_t'"; return 1; }
  if [ -n "$l_anchor" ] && [ -f "$l_file" ]; then
    case "$l_file" in
      *.md)
        md_anchors "$l_file" | grep -qxF "$l_anchor" \
          || { echo "$l_rel: no heading for the anchor in '$l_t'"; return 1; }
        ;;
    esac
  fi
  return 0
}

# Every relative link in README.md, CLAUDE.md and docs/**/*.md points at a file
# that exists, and at a heading that exists when it has a #anchor into Markdown.
# URLs and mailto links aren't checked.
check_relative_links() {
  root="$1"
  rc=0
  mds=$(mktemp "$WORK_DIR/mds.XXXXXX")
  (
    cd "$root" || exit
    for f in README.md CLAUDE.md; do [ -f "$f" ] && echo "$f"; done
    find docs -type f -name '*.md' 2>/dev/null | sort
  ) >"$mds"
  while IFS= read -r rel; do
    links=$(mktemp "$WORK_DIR/links.XXXXXX")
    md_links "$root/$rel" >"$links"
    while IFS= read -r target; do
      check_link "$root" "$rel" "$target" || rc=1
    done <"$links"
  done <"$mds"
  return $rc
}

check_pom_metadata() {
  pom="$1/backend/pom.xml"
  rc=0
  grep -Eq '^	<name>[^<]+</name>' "$pom" || { echo "backend/pom.xml has an empty <name>"; rc=1; }
  grep -Eq '^	<description>[^<]+</description>' "$pom" || { echo "backend/pom.xml has an empty <description>"; rc=1; }
  return $rc
}

# --- tests on the repo ----------------------------------------------------------

run_check() {
  name=$1
  shift
  if out=$("$@" "$REPO_ROOT" 2>&1); then
    pass "$name"
  else
    fail "$name" "$(echo "$out" | head -5 | tr '\n' ';')"
  fi
}

run_check adr_template_has_required_sections check_template
run_check adr_files_are_numbered_without_gaps_or_duplicates check_numbering
run_check each_adr_title_matches_its_number check_titles
run_check each_adr_has_date_and_valid_status check_date_and_status
run_check each_adr_has_context_decision_consequences check_sections
run_check adr_index_lists_every_adr check_index
run_check architecture_has_mermaid_diagrams check_architecture
run_check readme_links_architecture_adrs_and_swagger_ui check_readme_links
run_check relative_markdown_links_resolve check_relative_links
run_check pom_has_name_and_description check_pom_metadata

# --- self-checks: the checks fail on broken copies ------------------------------

# A copy of the repo's files to break: tracked and untracked, minus gitignored
# ones (build output, node_modules, .env), so every link target is there too.
docs_copy() {
  dir=$(mktemp -d "$WORK_DIR/copy.XXXXXX")
  (cd "$REPO_ROOT" && git ls-files --cached --others --exclude-standard | while IFS= read -r f; do
    [ -f "$f" ] && printf '%s\n' "$f"
  done | tar -cf - -T -) | (cd "$dir" && tar -xf -)
  echo "$dir"
}

expect_failure() {
  name=$1
  check=$2
  dir=$3
  if $check "$dir" >/dev/null 2>&1; then
    fail "$name" "$check passed on a broken copy"
  else
    pass "$name"
  fi
}

# Only meaningful once there are ADRs to break.
if [ -n "$(adr_files "$REPO_ROOT")" ]; then
  dir=$(docs_copy)
  last=$(adr_files "$dir" | tail -1)
  num=$(basename "$last" | cut -c1-4 | sed 's/^0*//')
  gap=$(printf '%04d' $((num + 2)))
  sed "s/^# [0-9]\{4\}\./# $gap./" "$last" >"$dir/docs/adr/$gap-gap-in-numbering.md"
  expect_failure numbering_check_fails_on_a_gap check_numbering "$dir"

  dir=$(docs_copy)
  cp "$(adr_files "$dir" | head -1)" "$dir/docs/adr/05-new-decision.md"
  if out=$(check_numbering "$dir" 2>&1); then
    fail numbering_check_fails_on_a_misnamed_adr "check_numbering passed with docs/adr/05-new-decision.md"
  elif ! echo "$out" | grep -q '05-new-decision.md'; then
    fail numbering_check_fails_on_a_misnamed_adr "failed without naming 05-new-decision.md: $(echo "$out" | head -1)"
  else
    pass numbering_check_fails_on_a_misnamed_adr
  fi

  dir=$(docs_copy)
  first=$(adr_files "$dir" | head -1)
  sed '/^## Consequences$/d' "$first" >"$first.tmp" && mv "$first.tmp" "$first"
  expect_failure sections_check_fails_without_consequences check_sections "$dir"

  dir=$(docs_copy)
  first=$(adr_files "$dir" | head -1)
  sed 's/^Status: .*/Status: Maybe/' "$first" >"$first.tmp" && mv "$first.tmp" "$first"
  expect_failure status_check_fails_on_an_unknown_status check_date_and_status "$dir"
else
  fail adr_self_checks "no ADRs to break yet"
fi

dir=$(docs_copy)
printf '\nSee [nowhere](docs/does-not-exist.md).\n' >>"$dir/README.md"
expect_failure link_check_fails_on_a_broken_link check_relative_links "$dir"

dir=$(docs_copy)
printf '\n```sh\n[not a link](nowhere.md)\n```\n' >>"$dir/README.md"
if out=$(check_relative_links "$dir" 2>&1); then
  pass link_check_ignores_code_blocks
elif echo "$out" | grep -q nowhere.md; then
  fail link_check_ignores_code_blocks "flagged a link inside a fenced code block"
else
  fail link_check_ignores_code_blocks "repo links are broken: $(echo "$out" | head -1)"
fi

dir=$(docs_copy)
sed '/^Status: Proposed$/d' "$dir/docs/adr/0000-template.md" >"$dir/t.tmp" && mv "$dir/t.tmp" "$dir/docs/adr/0000-template.md"
expect_failure template_check_fails_without_its_status_line check_template "$dir"

# A later ADR's row mentions 0001 and shows 0001's status, while 0001's own row disagrees.
dir=$(docs_copy)
first=$(adr_files "$dir" | head -1)
first_base=$(basename "$first")
first_status=$(adr_field "$first" Status)
sed "/^| \[0001\]/s/| $first_status |\$/| Deprecated |/" "$dir/docs/adr/README.md" >"$dir/i.tmp" && mv "$dir/i.tmp" "$dir/docs/adr/README.md"
printf '| [0099](%s) | Mentions %s | %s |\n' "$first_base" "$first_base" "$first_status" >>"$dir/docs/adr/README.md"
expect_failure index_check_compares_the_adrs_own_row check_index "$dir"

dir=$(docs_copy)
printf '\nSee [setup](README.md#no-such-heading).\n' >>"$dir/CLAUDE.md"
expect_failure link_check_fails_on_a_broken_anchor check_relative_links "$dir"

dir=$(docs_copy)
printf '\nSee [here](#no-such-heading-here).\n' >>"$dir/CLAUDE.md"
expect_failure link_check_fails_on_a_broken_same_file_anchor check_relative_links "$dir"

dir=$(docs_copy)
printf '\nSee [the spec][spec].\n\n[spec]: docs/missing-spec.md\n' >>"$dir/README.md"
expect_failure link_check_fails_on_a_broken_reference_definition check_relative_links "$dir"

dir=$(docs_copy)
printf '\n<a href="docs/missing.html">x</a> <img src="docs/missing.png" alt="x">\n' >>"$dir/README.md"
expect_failure link_check_fails_on_a_broken_html_link check_relative_links "$dir"

# Valid forms that a naive parser misreads: parentheses and spaces in targets, titles,
# root-relative paths, query strings, and anchors that exist.
dir=$(docs_copy)
mkdir -p "$dir/docs/odd"
: >"$dir/docs/odd/file_(v2).md"
: >"$dir/docs/odd/my file.md"
cat >>"$dir/README.md" <<'EOF'

[parens](docs/odd/file_(v2).md) [spaces](<docs/odd/my file.md>) [title](docs/architecture.md "Architecture")
[root](/docs/architecture.md) [query](docs/architecture.md?plain=1) [anchor](docs/architecture.md#rag-flow)
[self](#documentation) [encoded](docs/odd/my%20file.md)
EOF
if out=$(check_relative_links "$dir" 2>&1); then
  pass link_check_accepts_valid_link_forms
else
  fail link_check_accepts_valid_link_forms "$(echo "$out" | head -3 | tr '\n' ';')"
fi

dir=$(docs_copy)
printf '\nUse `[x](nowhere.md)` in inline code.\n' >>"$dir/README.md"
if out=$(check_relative_links "$dir" 2>&1); then
  pass link_check_ignores_inline_code
else
  fail link_check_ignores_inline_code "$(echo "$out" | head -1)"
fi

# Every check passes on an unmodified copy whose path contains a space.
spaced="$WORK_DIR/path with space"
mkdir -p "$spaced"
mv "$(docs_copy)" "$spaced/repo"
problems=""
for check in check_template check_numbering check_titles check_date_and_status check_sections \
  check_index check_architecture check_readme_links check_relative_links check_pom_metadata; do
  out=$($check "$spaced/repo" 2>&1) || problems="$problems $check: $(echo "$out" | head -1);"
done
if [ -z "$problems" ]; then
  pass checks_pass_under_a_path_with_spaces
else
  fail checks_pass_under_a_path_with_spaces "$problems"
fi

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
