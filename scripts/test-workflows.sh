#!/bin/sh
# Static checks for the GitHub Actions workflows in .github/workflows/: what the
# build workflow runs and with which tool versions, and the hardening every
# workflow must keep (actions pinned to a commit SHA, read-only token, no
# persisted credentials). Also checks the Dependabot config
# (.github/dependabot.yml). CI runs it (.github/workflows/build.yml).
#
#   sh scripts/test-workflows.sh
#
# Needs only POSIX sh, grep, sed, awk and cp. These are text checks, not a YAML
# parser: the real check of a workflow is its run on GitHub. The self-checks at
# the end break throwaway copies to confirm the hardening checks fail when they
# should. The repo itself is never modified.

set -u

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT INT TERM

BUILD="$REPO_ROOT/.github/workflows/build.yml"
DEPENDABOT="$REPO_ROOT/.github/dependabot.yml"

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

# Prints a top-level block of a workflow: the "$2:" line and every indented or
# blank line after it, up to the next top-level key.
top_block() {
  awk -v key="$2:" '
    $0 == key || index($0, key " ") == 1 { inside = 1; print; next }
    inside && /^[^ #]/ { exit }
    inside { print }
  ' "$1" 2>/dev/null
}

# Prints the block of job "$2" (a two-space-indented key under jobs:).
job_block() {
  awk -v key="  $2:" '
    /^jobs:/ { in_jobs = 1; next }
    in_jobs && /^[^ #]/ { exit }
    in_jobs && $0 == key { inside = 1; print; next }
    inside && /^  [A-Za-z0-9_-]+:/ { exit }
    inside { print }
  ' "$1" 2>/dev/null
}

# Runs a check on a block of text: each remaining argument is a fixed string
# that must appear in it, outside comment lines. Prints the missing ones;
# non-zero when any is missing.
expect_lines() {
  text=$(printf '%s\n' "$1" | grep -v '^[[:space:]]*#')
  shift
  rc=0
  for want in "$@"; do
    printf '%s\n' "$text" | grep -qF -- "$want" || {
      echo "missing: $want"
      rc=1
    }
  done
  return $rc
}

# Runs a test: $1 is its name, the rest the command that checks it.
run_test() {
  name="$1"
  shift
  if out=$("$@" 2>&1); then
    pass "$name"
  else
    fail "$name" "$(printf '%s' "$out" | tr '\n' ';')"
  fi
}

# Writes the workflow list of root $1 to a temp file, one path per line, and
# prints its path, so callers loop with `while IFS= read -r f; do ...; done <"$list"`:
# no word splitting on paths with spaces, and the loop can set rc. Prints nothing
# and returns non-zero when there are no workflow files, so no check passes on an
# empty set.
workflow_list() {
  l=$(mktemp "$WORK_DIR/workflows.XXXXXX")
  find "$1/.github/workflows" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null | sort >"$l"
  [ -s "$l" ] || {
    echo "no workflow files in $1/.github/workflows" >&2
    return 1
  }
  echo "$l"
}

# --- hardening checks, on every workflow under root $1 ---------------------
# Comment lines are skipped. Quoted scalars are allowed; flow-style maps are
# checked for write grants but not otherwise parsed.

# Every "uses:" names a full 40-character commit SHA (local ./ actions excepted).
check_pins() {
  list=$(workflow_list "$1") || return 1
  rc=0
  while IFS= read -r f; do
    bad=$(grep -nE '^[[:space:]-]*uses:' "$f" |
      grep -vE "uses:[[:space:]]*[\"']?\./" |
      grep -vE "uses:[[:space:]]*[\"']?[^[:space:]@\"']+@[0-9a-f]{40}[\"']?([[:space:]]|\$)")
    [ -z "$bad" ] || {
      echo "$(basename "$f"): not pinned to a SHA: $bad"
      rc=1
    }
  done <"$list"
  return $rc
}

# Every workflow sets a top-level "permissions: contents: read" and grants no
# write anywhere: block style, flow style ({ contents: write }) or quoted.
check_permissions() {
  list=$(workflow_list "$1") || return 1
  rc=0
  while IFS= read -r f; do
    block=$(top_block "$f" permissions)
    printf '%s\n' "$block" | grep -qE '^[[:space:]]+contents:[[:space:]]*read([[:space:]]|$)' || {
      echo "$(basename "$f"): no top-level 'permissions: contents: read'"
      rc=1
    }
    writes=$(grep -vE '^[[:space:]]*#' "$f" | tr -d "\"'" |
      grep -E '(^|[[:space:]{,])[a-z-]+:[[:space:]]*write(-all)?([[:space:]},]|$)')
    [ -z "$writes" ] || {
      echo "$(basename "$f"): grants write permissions: $writes"
      rc=1
    }
  done <"$list"
  return $rc
}

# Every actions/checkout step sets "persist-credentials: false" in its with: block.
check_checkout_credentials() {
  list=$(workflow_list "$1") || return 1
  rc=0
  while IFS= read -r f; do
    bad=$(awk '
      function report() { if (open && !ok) print FILENAME ":" line }
      /^[[:space:]]*#/ { next }
      /uses:[[:space:]]*["\047]?actions\/checkout@/ {
        report(); open = 1; ok = 0; line = FNR
        match($0, /[^ -]/); indent = RSTART; next
      }
      open {
        match($0, /[^ ]/)
        if ($0 ~ /^[[:space:]]*$/) next
        if (RSTART < indent || $0 ~ /^[[:space:]]*- /) { report(); open = 0 }
        else if ($0 ~ /persist-credentials:[[:space:]]*false/) ok = 1
      }
      END { report() }
    ' "$f")
    [ -z "$bad" ] || {
      echo "checkout without persist-credentials: false at $bad"
      rc=1
    }
  done <"$list"
  return $rc
}

# --- build workflow checks --------------------------------------------------

check_build_exists() {
  [ -f "$BUILD" ] || {
    echo ".github/workflows/build.yml is missing"
    return 1
  }
}

check_triggers() {
  check_build_exists || return 1
  on=$(top_block "$BUILD" on)
  expect_lines "$on" '  push:' '  pull_request:' '  workflow_dispatch:' || return 1
  # Every push and every PR: no branch or path filter.
  ! printf '%s\n' "$on" | grep -nE 'branches|paths|tags' || {
    echo "trigger is filtered"
    return 1
  }
}

check_jobs() {
  check_build_exists || return 1
  rc=0
  for job in backend frontend compose workflows; do
    [ -n "$(job_block "$BUILD" "$job")" ] || {
      echo "no '$job' job"
      rc=1
    }
  done
  return $rc
}

check_backend_setup() {
  check_build_exists || return 1
  expect_lines "$(job_block "$BUILD" backend)" \
    'uses: actions/setup-java@' 'java-version-file: .sdkmanrc' 'cache: maven' \
    'cache-dependency-path: backend/pom.xml'
}

check_backend_verify() {
  check_build_exists || return 1
  expect_lines "$(job_block "$BUILD" backend)" 'working-directory: backend' './mvnw -B -ntp verify'
}

check_frontend_setup() {
  check_build_exists || return 1
  expect_lines "$(job_block "$BUILD" frontend)" \
    'uses: actions/setup-node@' 'node-version-file: frontend/.nvmrc' 'cache: npm' \
    'cache-dependency-path: frontend/package-lock.json'
}

check_frontend_commands() {
  check_build_exists || return 1
  block=$(job_block "$BUILD" frontend)
  expect_lines "$block" 'working-directory: frontend' 'run: npm ci' 'run: npm run build' \
    'run: npm run test:ci' || return 1
  # In this order: install, then build, then test.
  order=$(printf '%s\n' "$block" | grep -oE 'run: npm (ci|run build|run test:ci)' | tr '\n' ',')
  [ "$order" = "run: npm ci,run: npm run build,run: npm run test:ci," ] || {
    echo "steps out of order: $order"
    return 1
  }
}

check_test_ci_script() {
  grep -qE '"test:ci":[[:space:]]*"ng test --watch=false"' "$REPO_ROOT/frontend/package.json" || {
    echo 'frontend/package.json has no "test:ci": "ng test --watch=false"'
    return 1
  }
}

check_compose_job() {
  check_build_exists || return 1
  expect_lines "$(job_block "$BUILD" compose)" 'run: sh scripts/test-compose.sh'
}

check_workflows_job() {
  check_build_exists || return 1
  expect_lines "$(job_block "$BUILD" workflows)" 'run: sh scripts/test-workflows.sh'
}

# setup-java keys the Maven cache on the hash of backend/pom.xml in both workflows,
# and a saved key can't be overwritten. Only the backend job (full dependency set)
# may save it; code-style restores it read-only. $1: the code-style workflow.
check_code_style_cache_read_only() {
  expect_lines "$(job_block "${1:-$REPO_ROOT/.github/workflows/code-style.yml}" check)" \
    'cache: maven' 'cache-read-only: true'
}

check_backend_cache_writable() {
  check_build_exists || return 1
  ! job_block "$BUILD" backend | grep -v '^[[:space:]]*#' | tr -d "\"'" |
    grep -nE 'cache-read-only:[[:space:]]*true' || {
    echo "backend job must save the Maven cache"
    return 1
  }
}

# Superseded runs are cancelled on branches and PRs. On main each commit has its
# own group: GitHub keeps one pending run per group and replaces it even without
# cancel-in-progress, so a shared group would drop commits. $1: the build workflow.
check_cancel_off_main() {
  f=${1:-$BUILD}
  [ -f "$f" ] || {
    echo "$f is missing"
    return 1
  }
  expect_lines "$(top_block "$f" concurrency)" \
    "group: \${{ github.workflow }}-\${{ github.event_name }}-\${{ github.ref == 'refs/heads/main' && github.sha || github.ref }}" \
    'cancel-in-progress: true'
}

check_job_timeouts() {
  check_build_exists || return 1
  rc=0
  for job in backend frontend compose workflows; do
    job_block "$BUILD" "$job" | grep -qE '^    timeout-minutes:[[:space:]]*[0-9]+' || {
      echo "job '$job' has no timeout-minutes"
      rc=1
    }
  done
  return $rc
}

# --- Dependabot config checks -----------------------------------------------
# .github/dependabot.yml: which ecosystems are updated, from where, how often,
# grouped how, labelled how. The checks take the config file as $1 (default: the
# repo's) and look for manifests next to it (../ of .github/). Comments and quotes
# are stripped first; block lists and flow lists ([a, b]) both work.

# The update entries Dependabot must have, "<ecosystem> <directory>": exactly
# these, one each.
DEPENDABOT_ENTRIES='maven /backend
npm /frontend
github-actions /
docker-compose /'

# Prints config $1 without comments, quotes, trailing spaces or blank lines.
dependabot_clean() {
  [ -f "$1" ] || {
    echo "$1 is missing" >&2
    return 1
  }
  sed -e 's/^[[:space:]]*#.*$//' -e 's/[[:space:]]#.*$//' -e "s/[\"']//g" \
    -e 's/[[:space:]]*$//' "$1" | grep -v '^$'
}

# Prints one "<ecosystem> <directory>" line per update entry of config $1, in
# file order.
dependabot_entries() {
  [ -f "$1" ] || {
    echo "$1 is missing" >&2
    return 1
  }
  dependabot_clean "$1" | awk '
    function flush() { if (eco != "") print eco, dir; eco = ""; dir = "" }
    /^[^ ]/ { flush(); next }
    /^  - package-ecosystem:/ {
      flush(); eco = $0; sub(/^  - package-ecosystem:[[:space:]]*/, "", eco); next
    }
    /^    directory:/ { dir = $0; sub(/^    directory:[[:space:]]*/, "", dir) }
    END { flush() }
  '
}

# Prints update entry number $2 (from 1, in file order) of config $1, cleaned.
dependabot_entry_at() {
  dependabot_clean "$1" | awk -v want="$2" '
    /^[^ ]/ { inside = 0; next }
    /^  - package-ecosystem:/ { n++; inside = (n == want) }
    inside { print }
  '
}

# Prints the first update entry for ecosystem $2 of config $1, cleaned.
dependabot_entry() {
  at=$(dependabot_entries "$1" | awk -v eco="$2" '$1 == eco { print NR; exit }')
  [ -n "$at" ] && dependabot_entry_at "$1" "$at"
}

# Prints the values of the entry-level key $2 (four-space indent) in entry text
# $1, one per line: a scalar, a flow list or a block list (items indented at the
# key's level or deeper).
entry_values() {
  printf '%s\n' "$1" | awk -v key="    $2:" '
    index($0, key) == 1 {
      v = substr($0, length(key) + 1); gsub(/[][ ]/, "", v)
      if (v != "") { n = split(v, a, ","); for (i = 1; i <= n; i++) print a[i]; exit }
      inside = 1; next
    }
    inside && /^    [ ]*- / { v = $0; sub(/^ +- */, "", v); print v; next }
    inside { exit }
  '
}

# Prints one "<name>|<patterns>|<update-types>" line per group in entry text $1,
# lists comma-separated in file order. "|" rather than a tab: read with IFS=tab
# would merge an empty field into the next.
entry_groups() {
  printf '%s\n' "$1" | awk '
    function flush() { if (name != "") print name "|" pats "|" types; name = ""; pats = ""; types = "" }
    function add(k, v) {
      if (k == "patterns") pats = pats (pats == "" ? "" : ",") v
      else if (k == "update-types") types = types (types == "" ? "" : ",") v
    }
    /^    groups:/ { g = 1; next }
    g && /^    [^ ]/ { flush(); g = 0; next }
    g && /^      [^ ]/ { flush(); name = $0; sub(/^ +/, "", name); sub(/:.*/, "", name); next }
    g && /^        [^ -]/ {
      key = $0; sub(/^ +/, "", key); val = key; sub(/:.*/, "", key); sub(/^[^:]*:[[:space:]]*/, "", val)
      if (val != "") { gsub(/[][ ]/, "", val); n = split(val, a, ","); for (i = 1; i <= n; i++) add(key, a[i]) }
      next
    }
    g && /^ +- / { item = $0; sub(/^ +- */, "", item); add(key, item) }
    END { flush() }
  '
}

# Runs check function $2 on every update entry of config $1, one entry at a
# time: $2 gets the entry text and "<ecosystem> <directory>".
each_entry() {
  f=$1
  check=$2
  entries=$(dependabot_entries "$f") || return 1
  [ -n "$entries" ] || {
    echo "no update entries"
    return 1
  }
  count=$(printf '%s\n' "$entries" | wc -l | tr -d ' ')
  erc=0
  at=1
  while [ "$at" -le "$count" ]; do
    "$check" "$(dependabot_entry_at "$f" "$at")" "$(printf '%s\n' "$entries" | sed -n "${at}p")" || erc=1
    at=$((at + 1))
  done
  return $erc
}

check_dependabot_version() {
  dependabot_clean "${1:-$DEPENDABOT}" | grep -qE '^version:[[:space:]]*2$' || {
    echo "dependabot.yml has no 'version: 2'"
    return 1
  }
}

# Exactly the entries in DEPENDABOT_ENTRIES, once each, and every entry starts
# with package-ecosystem (the parsers rely on it).
check_dependabot_ecosystems() {
  f=${1:-$DEPENDABOT}
  entries=$(dependabot_entries "$f") || return 1
  out=$(
    printf '%s\n' "$DEPENDABOT_ENTRIES" | while IFS= read -r want; do
      printf '%s\n' "$entries" | grep -qxF -- "$want" || echo "missing entry: $want"
    done
    printf '%s\n' "$entries" | while IFS= read -r have; do
      [ -z "$have" ] || printf '%s\n' "$DEPENDABOT_ENTRIES" | grep -qxF -- "$have" ||
        echo "unexpected entry: $have"
    done
    printf '%s\n' "$entries" | sort | uniq -d | sed 's/^/duplicate entry: /'
    starts=$(dependabot_clean "$f" | grep -c '^  - ')
    [ "$starts" -eq "$(printf '%s\n' "$entries" | grep -c .)" ] ||
      echo "an update entry doesn't start with package-ecosystem"
  )
  [ -z "$out" ] || {
    echo "$out"
    return 1
  }
}

# Each entry's directory holds the files its ecosystem reads. $2: the repo root
# (default: the parent of the config's directory).
check_dependabot_manifests() {
  f=${1:-$DEPENDABOT}
  root=${2:-$(cd "$(dirname "$f")/.." && pwd)}
  entries=$(dependabot_entries "$f") || return 1
  [ -n "$entries" ] || {
    echo "no update entries"
    return 1
  }
  printf '%s\n' "$entries" | {
    rc=0
    while read -r eco dir; do
      d="$root${dir%/}"
      case $eco in
        maven) wants="pom.xml" ;;
        npm) wants="package.json package-lock.json" ;;
        github-actions) wants="workflows" ;;
        docker-compose) wants="compose.yaml" ;;
        *)
          echo "$eco in $dir: unknown ecosystem"
          rc=1
          continue
          ;;
      esac
      for w in $wants; do
        case $w in
          workflows)
            w=".github/workflows/*.yml"
            found=$(find "$d/.github/workflows" -maxdepth 1 -name '*.yml' 2>/dev/null | head -n 1)
            ;;
          *) found=$([ -f "$d/$w" ] && echo "$d/$w") ;;
        esac
        [ -n "$found" ] || {
          echo "$eco in $dir: no $w"
          rc=1
        }
      done
    done
    [ "$rc" -eq 0 ]
  }
}

weekly() {
  printf '%s\n' "$1" | grep -qE '^      interval:[[:space:]]*weekly$' || {
    echo "$2: schedule is not weekly"
    return 1
  }
}

# Every group takes minor and patch updates and nothing else.
groups_minor_patch() {
  groups=$(entry_groups "$1")
  [ -n "$groups" ] || {
    echo "$2: no groups"
    return 1
  }
  printf '%s\n' "$groups" | {
    rc=0
    while IFS='|' read -r name pats types; do
      sorted=$(printf '%s\n' "$types" | tr ',' '\n' | sort | tr '\n' ',')
      [ "$sorted" = "minor,patch," ] || {
        echo "$2 group $name: update-types '$types', want minor and patch"
        rc=1
      }
    done
    [ "$rc" -eq 0 ]
  }
}

# The last group takes every remaining dependency: a dependency goes to the
# first group it matches, so a "*" group anywhere else would swallow the rest.
catch_all_last() {
  last=$(entry_groups "$1" | tail -n 1 | cut -d '|' -f 2)
  [ "$last" = "*" ] || {
    echo "$2: last group's patterns are '$last', want '*'"
    return 1
  }
  early=$(entry_groups "$1" | sed '$d' | awk -F '|' '("," $2 ",") ~ /,[*],/ { print $1 }')
  [ -z "$early" ] || {
    echo "$2: group '$early' lists '*' but isn't last"
    return 1
  }
}

labels_dependencies() {
  entry_values "$1" labels | grep -qxF dependencies || {
    echo "$2: no 'dependencies' label"
    return 1
  }
}

limits_open_prs() {
  n=$(entry_values "$1" open-pull-requests-limit)
  case $n in
    [1-5]) ;;
    *)
      echo "$2: open-pull-requests-limit '$n', want 1-5"
      return 1
      ;;
  esac
}

# Prints the cooldown key $2 of entry text $1 (six-space indent), digits only.
cooldown_days() {
  printf '%s\n' "$1" | sed -n "s/^      $2:[[:space:]]*\([0-9][0-9]*\)\$/\1/p"
}

# At least a week between a release and its PR, for every ecosystem.
cooldown_week() {
  n=$(cooldown_days "$1" default-days)
  [ -n "$n" ] && [ "$n" -ge 7 ] && [ "$n" -le 90 ] || {
    echo "$2: cooldown default-days '$n', want 7-90"
    return 1
  }
}

# Majors wait longer where the ecosystem supports semver-*-days (maven, npm;
# not github-actions or docker-compose).
cooldown_major_longer() {
  case $2 in
    maven* | npm*) ;;
    *) return 0 ;;
  esac
  d=$(cooldown_days "$1" default-days)
  n=$(cooldown_days "$1" semver-major-days)
  [ -n "$n" ] && [ -n "$d" ] && [ "$n" -gt "$d" ] && [ "$n" -le 90 ] || {
    echo "$2: cooldown semver-major-days '$n', want more than default-days '$d', at most 90"
    return 1
  }
}

check_dependabot_weekly() { each_entry "${1:-$DEPENDABOT}" weekly; }
check_dependabot_groups() { each_entry "${1:-$DEPENDABOT}" groups_minor_patch; }
check_dependabot_catch_all() { each_entry "${1:-$DEPENDABOT}" catch_all_last; }
check_dependabot_labels() { each_entry "${1:-$DEPENDABOT}" labels_dependencies; }
check_dependabot_pr_limit() { each_entry "${1:-$DEPENDABOT}" limits_open_prs; }
check_dependabot_cooldown() { each_entry "${1:-$DEPENDABOT}" cooldown_week; }
check_dependabot_major_cooldown() { each_entry "${1:-$DEPENDABOT}" cooldown_major_longer; }

# Group $2 of entry text $1 lists every pattern in $3...
group_has() {
  entry=$1
  g=$2
  shift 2
  gpats=$(entry_groups "$entry" | awk -F '|' -v g="$g" '$1 == g { print $2 }')
  [ -n "$gpats" ] || {
    echo "no '$g' group"
    return 1
  }
  grc=0
  for p in "$@"; do
    printf '%s\n' "$gpats" | tr ',' '\n' | grep -qxF -- "$p" || {
      echo "group '$g' doesn't list $p"
      grc=1
    }
  done
  return $grc
}

# Spring (Boot parent, Spring AI BOM) and Angular (framework, CLI, builder,
# angular-eslint, TypeScript) each move as one PR.
check_dependabot_named_groups() {
  f=${1:-$DEPENDABOT}
  nrc=0
  group_has "$(dependabot_entry "$f" maven)" spring 'org.springframework*' || nrc=1
  group_has "$(dependabot_entry "$f" npm)" angular '@angular/*' '@angular-devkit/*' \
    '@schematics/*' angular-eslint typescript || nrc=1
  return $nrc
}

# --- self-checks: the hardening checks must fail on broken copies -----------

# Copies the workflows to a fresh root and prints its path.
copy_root() {
  dir=$(mktemp -d "$WORK_DIR/root.XXXXXX")
  mkdir -p "$dir/.github"
  cp -R "$REPO_ROOT/.github/workflows" "$dir/.github/"
  echo "$dir"
}

# Replaces the file $1 with the output of sed script $2 applied to it.
sed_in_place() {
  sed "$2" "$1" >"$1.tmp" && mv "$1.tmp" "$1"
}

expect_fail() {
  if "$@" >/dev/null 2>&1; then
    echo "check passed on a broken copy"
    return 1
  fi
}

self_pin_check_fails_on_tag_ref() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" 's#actions/checkout@[0-9a-f]*#actions/checkout@v7#'
  expect_fail check_pins "$dir"
}

self_permissions_check_fails_when_missing() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" '/^permissions:/,/^  contents:/d'
  expect_fail check_permissions "$dir"
}

self_permissions_check_fails_on_write() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" 's/^  contents: read/  contents: write/'
  expect_fail check_permissions "$dir"
}

self_credentials_check_fails_when_missing() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" '/persist-credentials: false/d'
  expect_fail check_checkout_credentials "$dir"
}

self_cancel_check_ignores_commented_line() {
  dir=$(copy_root)
  f="$dir/.github/workflows/build.yml"
  sed_in_place "$f" 's#^  group: \(.*\)$#  \# group: \1\n  group: ${{ github.workflow }}-${{ github.ref }}#'
  grep -q '^  # group:' "$f" || {
    echo "could not comment out the group line"
    return 1
  }
  expect_fail check_cancel_off_main "$f"
}

self_cache_read_only_check_ignores_commented_line() {
  dir=$(copy_root)
  f="$dir/.github/workflows/code-style.yml"
  sed_in_place "$f" 's#^          cache-read-only: true#          \# cache-read-only: true#'
  grep -q '# cache-read-only: true' "$f" || {
    echo "could not comment out cache-read-only"
    return 1
  }
  expect_fail check_code_style_cache_read_only "$f"
}

self_checks_fail_when_no_workflows() {
  dir=$(mktemp -d "$WORK_DIR/empty.XXXXXX")
  mkdir -p "$dir/.github/workflows"
  rc=0
  for check in check_pins check_permissions check_checkout_credentials; do
    expect_fail "$check" "$dir" || {
      echo "$check passed with no workflows"
      rc=1
    }
  done
  return $rc
}

self_pin_check_fails_under_path_with_spaces() {
  dir="$WORK_DIR/path with spaces"
  mkdir -p "$dir/.github"
  cp -R "$REPO_ROOT/.github/workflows" "$dir/.github/"
  check_pins "$dir" >/dev/null 2>&1 || {
    echo "pin check fails on the intact copy"
    return 1
  }
  sed_in_place "$dir/.github/workflows/docs.yml" 's#actions/checkout@[0-9a-f]*#actions/checkout@v7#'
  expect_fail check_pins "$dir"
}

self_pin_check_accepts_quoted_sha() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" 's#uses: \(actions/checkout@[0-9a-f]*\)#uses: "\1"#'
  grep -q 'uses: "actions/checkout@' "$dir/.github/workflows/docs.yml" || {
    echo "could not quote the uses: value"
    return 1
  }
  check_pins "$dir"
}

self_credentials_check_ignores_commented_checkout() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" 's#^      - name: Test docs#      \# - uses: actions/checkout@v4\n&#'
  grep -q '# - uses: actions/checkout@v4' "$dir/.github/workflows/docs.yml" || {
    echo "could not add the commented checkout"
    return 1
  }
  check_checkout_credentials "$dir"
}

self_permissions_check_fails_on_flow_style_write() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" 's#^    runs-on: ubuntu-latest#&\n    permissions: { contents: write }#'
  grep -q 'permissions: { contents: write }' "$dir/.github/workflows/docs.yml" || {
    echo "could not add the job-level permissions"
    return 1
  }
  expect_fail check_permissions "$dir"
}

self_permissions_check_fails_on_quoted_write() {
  dir=$(copy_root)
  sed_in_place "$dir/.github/workflows/docs.yml" 's#^    runs-on: ubuntu-latest#&\n    permissions:\n      contents: "write"#'
  grep -q '      contents: "write"' "$dir/.github/workflows/docs.yml" || {
    echo "could not add the job-level permissions"
    return 1
  }
  expect_fail check_permissions "$dir"
}

# Prints dependabot.yml with the first occurrence of fixed string $2 replaced by
# $3, inside the entry for ecosystem $1 only.
in_entry() {
  awk -v eco="$1" -v from="$2" -v to="$3" '
    /^  - package-ecosystem:/ { inside = ($0 ~ ("package-ecosystem: " eco "$")) }
    inside && !done && (i = index($0, from)) > 0 {
      $0 = substr($0, 1, i - 1) to substr($0, i + length(from)); done = 1
    }
    { print }
  ' "$DEPENDABOT"
}

# Prints dependabot.yml without the entry for ecosystem $1.
without_entry() {
  awk -v eco="$1" '
    /^[^ #]/ { skip = 0 }
    /^  - package-ecosystem:/ { skip = ($0 ~ ("package-ecosystem: " eco "$")) }
    !skip { print }
  ' "$DEPENDABOT"
}

# Prints dependabot.yml with a second, bare maven entry for /backend.
with_duplicate_maven() {
  cat "$DEPENDABOT"
  printf '  - package-ecosystem: maven\n    directory: /backend\n'
}

# Prints dependabot.yml with "major" added after the first "- patch" item.
with_major_update_type() {
  awk '!done && /^[[:space:]]+- patch$/ { print; sub(/patch/, "major"); print; done = 1; next } { print }' \
    "$DEPENDABOT"
}

# YAML also allows a block list at its key's own indent ("labels:" then
# "    - dependencies"); the labels check must read it, not fail on it.
self_labels_check_reads_list_at_key_indent() {
  copy=$(mktemp "$WORK_DIR/dependabot.XXXXXX")
  dependabot_clean "$DEPENDABOT" >/dev/null || return 1
  # Outdents every label item, so a parser that read only the first would fail.
  awk '/^    labels:$/ { l = 1; print; next } l && /^      - / { sub(/^      /, "    ") } l && !/^ +- / { l = 0 } { print }' \
    "$DEPENDABOT" >"$copy"
  grep -q '^    - backend$' "$copy" || {
    echo "could not outdent the second label"
    return 1
  }
  grep -q '^    - dependencies$' "$copy" || {
    echo "could not outdent the labels list"
    return 1
  }
  check_dependabot_labels "$copy"
}

# Writes the output of command $2... to a copy of dependabot.yml and expects
# check $1 to fail on it (with the repo root for the manifest check). The copy
# must differ from the original, so a breakage that no longer applies is caught.
break_dependabot() {
  check=$1
  shift
  dependabot_clean "$DEPENDABOT" >/dev/null || return 1
  copy=$(mktemp "$WORK_DIR/dependabot.XXXXXX")
  "$@" >"$copy" || return 1
  ! cmp -s "$DEPENDABOT" "$copy" || {
    echo "breakage changed nothing: $*"
    return 1
  }
  expect_fail "$check" "$copy" "$REPO_ROOT"
}

run_test build_runs_on_push_pull_request_and_dispatch check_triggers
run_test build_has_backend_frontend_compose_workflows_jobs check_jobs
run_test backend_job_reads_java_from_sdkmanrc_and_caches_maven check_backend_setup
run_test backend_job_runs_mvnw_verify check_backend_verify
run_test frontend_job_reads_node_from_nvmrc_and_caches_npm check_frontend_setup
run_test frontend_job_runs_npm_ci_build_and_test_ci check_frontend_commands
run_test frontend_test_ci_script_disables_watch check_test_ci_script
run_test compose_job_runs_test_compose_script check_compose_job
run_test workflows_job_runs_test_workflows_script check_workflows_job
run_test code_style_maven_cache_is_read_only check_code_style_cache_read_only
run_test backend_maven_cache_is_writable check_backend_cache_writable
run_test build_cancels_superseded_runs_only_off_main check_cancel_off_main
run_test every_build_job_has_timeout check_job_timeouts
run_test dependabot_config_is_version_2 check_dependabot_version
run_test dependabot_covers_maven_npm_actions_and_compose check_dependabot_ecosystems
run_test dependabot_directories_hold_their_manifests check_dependabot_manifests
run_test every_dependabot_ecosystem_runs_weekly check_dependabot_weekly
run_test every_dependabot_group_takes_minor_and_patch_only check_dependabot_groups
run_test spring_and_angular_have_their_own_groups check_dependabot_named_groups
run_test every_dependabot_ecosystem_has_catch_all_group check_dependabot_catch_all
run_test every_dependabot_ecosystem_labels_dependencies check_dependabot_labels
run_test every_dependabot_ecosystem_limits_open_prs check_dependabot_pr_limit
run_test every_dependabot_ecosystem_has_cooldown check_dependabot_cooldown
run_test maven_and_npm_majors_wait_longer check_dependabot_major_cooldown
run_test all_actions_pinned_to_commit_sha check_pins "$REPO_ROOT"
run_test all_workflows_have_read_only_permissions check_permissions "$REPO_ROOT"
run_test all_checkouts_disable_persisted_credentials check_checkout_credentials "$REPO_ROOT"
run_test pin_check_fails_on_tag_ref self_pin_check_fails_on_tag_ref
run_test permissions_check_fails_when_missing self_permissions_check_fails_when_missing
run_test permissions_check_fails_on_write self_permissions_check_fails_on_write
run_test credentials_check_fails_when_missing self_credentials_check_fails_when_missing
run_test cancel_check_ignores_commented_line self_cancel_check_ignores_commented_line
run_test cache_read_only_check_ignores_commented_line self_cache_read_only_check_ignores_commented_line
run_test checks_fail_when_no_workflows self_checks_fail_when_no_workflows
run_test pin_check_fails_under_path_with_spaces self_pin_check_fails_under_path_with_spaces
run_test pin_check_accepts_quoted_sha self_pin_check_accepts_quoted_sha
run_test credentials_check_ignores_commented_checkout self_credentials_check_ignores_commented_checkout
run_test permissions_check_fails_on_flow_style_write self_permissions_check_fails_on_flow_style_write
run_test permissions_check_fails_on_quoted_write self_permissions_check_fails_on_quoted_write
run_test dependabot_version_check_fails_on_version_1 \
  break_dependabot check_dependabot_version sed 's/^version: 2$/version: 1/' "$DEPENDABOT"
run_test ecosystem_check_fails_when_one_is_missing \
  break_dependabot check_dependabot_ecosystems without_entry docker-compose
run_test ecosystem_check_fails_on_duplicate_entry \
  break_dependabot check_dependabot_ecosystems with_duplicate_maven
run_test manifest_check_fails_on_wrong_actions_directory \
  break_dependabot check_dependabot_manifests in_entry github-actions 'directory: /' 'directory: /backend'
run_test catch_all_check_fails_on_star_in_earlier_group \
  break_dependabot check_dependabot_catch_all in_entry maven '- "org.springframework*"' '- "*"'
run_test manifest_check_fails_on_wrong_directory \
  break_dependabot check_dependabot_manifests in_entry maven 'directory: /backend' 'directory: /frontend'
run_test weekly_check_fails_when_one_entry_is_daily \
  break_dependabot check_dependabot_weekly in_entry docker-compose 'interval: weekly' 'interval: daily'
run_test group_check_fails_on_major \
  break_dependabot check_dependabot_groups with_major_update_type
run_test named_group_check_fails_without_spring_pattern \
  break_dependabot check_dependabot_named_groups in_entry maven '"org.springframework*"' '"org.example*"'
run_test named_group_check_fails_without_typescript_in_angular \
  break_dependabot check_dependabot_named_groups in_entry npm '"typescript"' '"tslib"'
run_test catch_all_check_fails_when_last_group_is_narrow \
  break_dependabot check_dependabot_catch_all in_entry github-actions '"*"' '"actions/*"'
run_test labels_check_reads_list_at_key_indent self_labels_check_reads_list_at_key_indent
run_test labels_check_fails_without_dependencies_label \
  break_dependabot check_dependabot_labels in_entry docker-compose '- dependencies' '- deps'
run_test pr_limit_check_fails_on_zero \
  break_dependabot check_dependabot_pr_limit in_entry npm 'open-pull-requests-limit: 3' 'open-pull-requests-limit: 0'
run_test cooldown_check_fails_under_a_week \
  break_dependabot check_dependabot_cooldown in_entry github-actions 'default-days: 7' 'default-days: 3'
run_test major_cooldown_check_fails_without_major_days \
  break_dependabot check_dependabot_major_cooldown in_entry npm 'semver-major-days' 'semver-minor-days'

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
