#!/bin/sh
# Tests for the gitleaks pre-commit hook (.githooks/pre-commit) and the
# .env / .env.example setup. Needs git and gitleaks on PATH.
#
#   sh scripts/test-gitleaks-hook.sh
#
# Hook tests run in throwaway repos under a temp dir, so this repo's
# history is never touched. The fake token is assembled at runtime so this
# file itself never contains anything gitleaks would flag.

set -u

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
HOOK="$REPO_ROOT/.githooks/pre-commit"
SCAN="$REPO_ROOT/scripts/gitleaks-scan-history.sh"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

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

# Creates an empty repo with the hook installed via core.hooksPath and prints its path.
new_repo() {
  repo=$(mktemp -d "$WORK_DIR/repo.XXXXXX")
  git -C "$repo" init -q
  git -C "$repo" config user.name "Hook Test"
  git -C "$repo" config user.email "hook-test@example.invalid"
  git -C "$repo" config commit.gpgsign false
  mkdir -p "$repo/.githooks"
  cp "$HOOK" "$repo/.githooks/pre-commit" 2>/dev/null && chmod +x "$repo/.githooks/pre-commit"
  git -C "$repo" config core.hooksPath .githooks
  echo "$repo"
}

has_commit() {
  git -C "$1" rev-parse -q --verify HEAD >/dev/null 2>&1
}

# A GitHub personal access token shape: prefix + 36 random alphanumerics.
fake_token() {
  printf '%s%s' "gh" "p_$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 36)"
}

test_blocks_commit_with_fake_github_token() {
  name=blocks_commit_with_fake_github_token
  repo=$(new_repo)
  token=$(fake_token)
  printf 'github.token=%s\n' "$token" >"$repo/config.properties"
  git -C "$repo" add config.properties
  if git -C "$repo" commit -q -m "add config" >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "commit succeeded"
  elif has_commit "$repo"; then
    fail "$name" "commit was created"
  else
    pass "$name"
  fi
}

test_redacts_secret_in_hook_output() {
  name=redacts_secret_in_hook_output
  repo=$(new_repo)
  token=$(fake_token)
  printf 'github.token=%s\n' "$token" >"$repo/config.properties"
  git -C "$repo" add config.properties
  git -C "$repo" commit -q -m "add config" >"$WORK_DIR/$name.log" 2>&1
  if grep -q "$token" "$WORK_DIR/$name.log"; then
    fail "$name" "token printed in clear"
  elif ! grep -q "REDACTED" "$WORK_DIR/$name.log"; then
    fail "$name" "no redacted finding in output"
  else
    pass "$name"
  fi
}

test_allows_commit_without_secrets() {
  name=allows_commit_without_secrets
  repo=$(new_repo)
  printf 'server.port=8080\n' >"$repo/config.properties"
  git -C "$repo" add config.properties
  if ! git -C "$repo" commit -q -m "add config" >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "commit failed: $(cat "$WORK_DIR/$name.log")"
  elif ! has_commit "$repo"; then
    fail "$name" "no commit created"
  else
    pass "$name"
  fi
}

test_blocks_commit_when_gitleaks_missing() {
  name=blocks_commit_when_gitleaks_missing
  minimal_path=/usr/bin:/bin
  if PATH=$minimal_path command -v gitleaks >/dev/null 2>&1; then
    echo "SKIP  $name: gitleaks is installed in $minimal_path"
    return
  fi
  repo=$(new_repo)
  printf 'server.port=8080\n' >"$repo/config.properties"
  git -C "$repo" add config.properties
  if PATH=$minimal_path git -C "$repo" commit -q -m "add config" >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "commit succeeded without gitleaks"
  elif has_commit "$repo"; then
    fail "$name" "commit was created"
  elif ! grep -q "brew install gitleaks" "$WORK_DIR/$name.log"; then
    fail "$name" "no install hint in output"
  else
    pass "$name"
  fi
}

test_env_example_has_no_secrets() {
  name=env_example_has_no_secrets
  if [ ! -f "$REPO_ROOT/.env.example" ]; then
    fail "$name" ".env.example is missing"
  elif ! gitleaks dir "$REPO_ROOT/.env.example" --no-banner --redact >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "gitleaks found something: $(cat "$WORK_DIR/$name.log")"
  else
    pass "$name"
  fi
}

test_env_file_is_gitignored() {
  name=env_file_is_gitignored
  if git -C "$REPO_ROOT" check-ignore -q .env && ! git -C "$REPO_ROOT" check-ignore -q .env.example; then
    pass "$name"
  else
    fail "$name" ".env must be ignored and .env.example must not be"
  fi
}

test_block_message_points_to_gitleaks_allow() {
  name=block_message_points_to_gitleaks_allow
  repo=$(new_repo)
  printf 'github.token=%s\n' "$(fake_token)" >"$repo/config.properties"
  git -C "$repo" add config.properties
  git -C "$repo" commit -q -m "add config" >"$WORK_DIR/$name.log" 2>&1
  if ! grep -q "possible secret" "$WORK_DIR/$name.log"; then
    fail "$name" "no leak message in output"
  elif ! grep -q "gitleaks:allow" "$WORK_DIR/$name.log"; then
    fail "$name" "message does not mention gitleaks:allow"
  elif grep -q -- "--no-verify" "$WORK_DIR/$name.log"; then
    fail "$name" "message suggests --no-verify, which CI would still flag"
  elif grep -q "gitleaksignore" "$WORK_DIR/$name.log"; then
    fail "$name" "message suggests .gitleaksignore fingerprints, which silence the whole line for good"
  else
    pass "$name"
  fi
}

test_reports_gitleaks_error_separately() {
  name=reports_gitleaks_error_separately
  repo=$(new_repo)
  printf 'this is [not valid toml\n' >"$repo/.gitleaks.toml"
  printf 'server.port=8080\n' >"$repo/config.properties"
  git -C "$repo" add config.properties
  if git -C "$repo" commit -q -m "add config" >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "commit succeeded although gitleaks failed"
  elif has_commit "$repo"; then
    fail "$name" "commit was created"
  elif grep -q "possible secret" "$WORK_DIR/$name.log"; then
    fail "$name" "a gitleaks error was reported as a secret"
  elif ! grep -q "gitleaks failed" "$WORK_DIR/$name.log"; then
    fail "$name" "no 'gitleaks failed' message in output"
  else
    pass "$name"
  fi
}

# Runs the CI scan (scripts/gitleaks-scan-history.sh) in a repo; prints its exit code.
# --exit-code 99 tells a leak (99) apart from a gitleaks failure (1).
run_history_scan() {
  (cd "$1" && sh "$SCAN" --exit-code 99 >"$2" 2>&1)
  echo $?
}

# The CI workflow scans a full clone. It must see secrets that only reach the
# default branch through a merge's second parent.
test_full_history_scan_finds_secret_in_merged_side_branch() {
  name=full_history_scan_finds_secret_in_merged_side_branch
  repo=$(new_repo)
  git -C "$repo" config --unset core.hooksPath # simulate a clone without the hook
  printf 'server.port=8080\n' >"$repo/config.properties"
  git -C "$repo" add config.properties
  git -C "$repo" commit -q -m "base"
  git -C "$repo" checkout -q -b side
  printf 'github.token=%s\n' "$(fake_token)" >"$repo/secret.properties"
  git -C "$repo" add secret.properties
  git -C "$repo" commit -q -m "leak on side branch"
  git -C "$repo" rm -q secret.properties
  git -C "$repo" commit -q -m "remove it again"
  git -C "$repo" checkout -q -
  printf 'server.port=9090\n' >"$repo/config.properties"
  git -C "$repo" commit -q -am "main work"
  git -C "$repo" merge -q --no-ff -m "merge side" side
  git -C "$repo" branch -q -D side
  status=$(run_history_scan "$repo" "$WORK_DIR/$name.log")
  if [ "$status" -eq 99 ]; then
    pass "$name"
  else
    fail "$name" "expected exit 99 (leak found), got $status"
  fi
}

# A secret added while making the merge itself (conflict resolution, evil merge)
# shows up in no parent commit, only in the merge commit's own diff.
test_full_history_scan_finds_secret_added_in_merge_commit() {
  name=full_history_scan_finds_secret_added_in_merge_commit
  repo=$(new_repo)
  git -C "$repo" config --unset core.hooksPath
  printf 'server.port=8080\n' >"$repo/config.properties"
  git -C "$repo" add config.properties
  git -C "$repo" commit -q -m "base"
  git -C "$repo" checkout -q -b side
  printf 'side=1\n' >"$repo/side.properties"
  git -C "$repo" add side.properties
  git -C "$repo" commit -q -m "side work"
  git -C "$repo" checkout -q -
  printf 'server.port=9090\n' >"$repo/config.properties"
  git -C "$repo" commit -q -am "main work"
  git -C "$repo" merge -q --no-ff --no-commit side >/dev/null 2>&1
  printf 'github.token=%s\n' "$(fake_token)" >"$repo/secret.properties"
  git -C "$repo" add secret.properties
  git -C "$repo" commit -q --no-verify -m "merge side"
  status=$(run_history_scan "$repo" "$WORK_DIR/$name.log")
  if [ "$status" -eq 99 ]; then
    pass "$name"
  else
    fail "$name" "expected exit 99 (leak found), got $status"
  fi
}

test_full_history_scan_passes_clean_history() {
  name=full_history_scan_passes_clean_history
  repo=$(new_repo)
  git -C "$repo" config --unset core.hooksPath
  printf 'server.port=8080\n' >"$repo/config.properties"
  git -C "$repo" add config.properties
  git -C "$repo" commit -q -m "base"
  git -C "$repo" checkout -q -b side
  printf 'side=1\n' >"$repo/side.properties"
  git -C "$repo" add side.properties
  git -C "$repo" commit -q -m "side work"
  git -C "$repo" checkout -q -
  git -C "$repo" merge -q --no-ff -m "merge side" side
  status=$(run_history_scan "$repo" "$WORK_DIR/$name.log")
  if [ "$status" -eq 0 ]; then
    pass "$name"
  else
    fail "$name" "expected exit 0, got $status: $(cat "$WORK_DIR/$name.log")"
  fi
}

test_hook_allows_line_marked_gitleaks_allow() {
  name=hook_allows_line_marked_gitleaks_allow
  repo=$(new_repo)
  printf 'github.token=%s # gitleaks:allow\n' "$(fake_token)" >"$repo/config.properties"
  git -C "$repo" add config.properties
  if ! git -C "$repo" commit -q -m "add config" >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "commit blocked: $(cat "$WORK_DIR/$name.log")"
  elif ! has_commit "$repo"; then
    fail "$name" "no commit created"
  else
    pass "$name"
  fi
}

test_full_history_scan_honours_gitleaks_allow() {
  name=full_history_scan_honours_gitleaks_allow
  repo=$(new_repo)
  git -C "$repo" config --unset core.hooksPath
  printf 'github.token=%s # gitleaks:allow\n' "$(fake_token)" >"$repo/config.properties"
  git -C "$repo" add config.properties
  git -C "$repo" commit -q -m "add config"
  status=$(run_history_scan "$repo" "$WORK_DIR/$name.log")
  if [ "$status" -eq 0 ]; then
    pass "$name"
  else
    fail "$name" "expected exit 0, got $status: $(cat "$WORK_DIR/$name.log")"
  fi
}

test_full_history_scan_redacts_secret() {
  name=full_history_scan_redacts_secret
  repo=$(new_repo)
  git -C "$repo" config --unset core.hooksPath
  token=$(fake_token)
  printf 'github.token=%s\n' "$token" >"$repo/config.properties"
  git -C "$repo" add config.properties
  git -C "$repo" commit -q -m "add config"
  status=$(run_history_scan "$repo" "$WORK_DIR/$name.log")
  if [ "$status" -ne 99 ]; then
    fail "$name" "expected exit 99 (leak found), got $status"
  elif grep -q "$token" "$WORK_DIR/$name.log"; then
    fail "$name" "token printed in clear"
  elif ! grep -q "REDACTED" "$WORK_DIR/$name.log"; then
    fail "$name" "no redacted finding in output"
  else
    pass "$name"
  fi
}

test_blocks_commit_with_fake_github_token
test_redacts_secret_in_hook_output
test_block_message_points_to_gitleaks_allow
test_reports_gitleaks_error_separately
test_full_history_scan_finds_secret_in_merged_side_branch
test_full_history_scan_finds_secret_added_in_merge_commit
test_full_history_scan_passes_clean_history
test_hook_allows_line_marked_gitleaks_allow
test_full_history_scan_honours_gitleaks_allow
test_full_history_scan_redacts_secret
test_allows_commit_without_secrets
test_blocks_commit_when_gitleaks_missing
test_env_example_has_no_secrets
test_env_file_is_gitignored

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
