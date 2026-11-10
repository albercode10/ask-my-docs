#!/bin/sh
# Static checks for the GitHub Actions workflows in .github/workflows/: what the
# build workflow runs and with which tool versions, and the hardening every
# workflow must keep (actions pinned to a commit SHA, read-only token, no
# persisted credentials). CI runs it (.github/workflows/build.yml).
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

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
