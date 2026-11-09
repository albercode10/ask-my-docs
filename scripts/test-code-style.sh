#!/bin/sh
# Tests for the code style tooling: Spotless (backend) and ESLint + Prettier
# (frontend). Checks that clean code passes, that badly formatted or lint-failing
# code fails, and that one command fixes formatting. Needs Java 21 and the
# frontend's node_modules (npm ci in frontend/).
#
#   sh scripts/test-code-style.sh
#
# Every test runs on a throwaway copy of backend/ or frontend/ under a temp
# dir, so the repo's files are never touched. Maven uses your ~/.m2.

set -u

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

export NG_CLI_ANALYTICS=false

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

# Copies backend/ (without build output) to a temp dir and prints its path.
backend_copy() {
  dir=$(mktemp -d "$WORK_DIR/backend.XXXXXX")
  (cd "$REPO_ROOT/backend" && tar -cf - --exclude ./target .) | (cd "$dir" && tar -xf -)
  echo "$dir"
}

# Copies frontend/ (without node_modules and build output) to a temp dir,
# links the real node_modules, and prints its path.
frontend_copy() {
  dir=$(mktemp -d "$WORK_DIR/frontend.XXXXXX")
  (cd "$REPO_ROOT/frontend" && tar -cf - --exclude ./node_modules --exclude ./dist \
    --exclude ./.angular --exclude ./coverage .) | (cd "$dir" && tar -xf -)
  ln -s "$REPO_ROOT/frontend/node_modules" "$dir/node_modules"
  echo "$dir"
}

# Runs a Maven goal in a backend copy; output goes to $2, exit status is printed.
mvn_in() {
  (cd "$1" && ./mvnw -B -ntp $3) >"$2" 2>&1
  echo $?
}

# Runs an npm script in a frontend copy; output goes to $2, exit status is printed.
npm_in() {
  (cd "$1" && npm run --silent "$3") >"$2" 2>&1
  echo $?
}

JAVA_PKG=src/main/java/com/alber/backend

write_unformatted_java() {
  cat >"$1/$JAVA_PKG/Ugly.java" <<'EOF'
package com.alber.backend;
public class Ugly{public static int  add(int a,int b){return a+b;}}
EOF
}

write_unused_import_java() {
  cat >"$1/$JAVA_PKG/Unused.java" <<'EOF'
package com.alber.backend;

import java.util.List;

public class Unused {}
EOF
}

test_backend_check_passes_on_repo() {
  name=backend_check_passes_on_repo
  dir=$(backend_copy)
  status=$(mvn_in "$dir" "$WORK_DIR/$name.log" spotless:check)
  if [ "$status" -ne 0 ]; then
    fail "$name" "spotless:check exited $status (see below)"
    tail -20 "$WORK_DIR/$name.log"
  else
    pass "$name"
  fi
}

test_backend_check_fails_on_unformatted_java() {
  name=backend_check_fails_on_unformatted_java
  dir=$(backend_copy)
  write_unformatted_java "$dir"
  status=$(mvn_in "$dir" "$WORK_DIR/$name.log" spotless:check)
  if [ "$status" -eq 0 ]; then
    fail "$name" "spotless:check passed on badly formatted Java"
  elif ! grep -q "format violations" "$WORK_DIR/$name.log"; then
    fail "$name" "no Spotless format violation reported (exit $status)"
  elif ! grep -q "Ugly.java" "$WORK_DIR/$name.log"; then
    fail "$name" "violation doesn't name Ugly.java"
  else
    pass "$name"
  fi
}

test_backend_check_fails_on_unused_import() {
  name=backend_check_fails_on_unused_import
  dir=$(backend_copy)
  write_unused_import_java "$dir"
  status=$(mvn_in "$dir" "$WORK_DIR/$name.log" spotless:check)
  if [ "$status" -eq 0 ]; then
    fail "$name" "spotless:check passed with an unused import"
  elif ! grep -q "Unused.java" "$WORK_DIR/$name.log"; then
    fail "$name" "no Spotless violation for Unused.java (exit $status)"
  else
    pass "$name"
  fi
}

test_backend_verify_runs_spotless_check() {
  name=backend_verify_runs_spotless_check
  dir=$(backend_copy)
  write_unformatted_java "$dir"
  status=$(mvn_in "$dir" "$WORK_DIR/$name.log" "verify -DskipTests")
  if [ "$status" -eq 0 ]; then
    fail "$name" "verify passed on badly formatted Java"
  elif ! grep -q "format violations" "$WORK_DIR/$name.log"; then
    fail "$name" "verify failed, but not on a Spotless violation (exit $status)"
  else
    pass "$name"
  fi
}

test_backend_apply_fixes_java() {
  name=backend_apply_fixes_java
  dir=$(backend_copy)
  write_unformatted_java "$dir"
  write_unused_import_java "$dir"
  mvn_in "$dir" "$WORK_DIR/$name.apply.log" spotless:apply >/dev/null
  status=$(mvn_in "$dir" "$WORK_DIR/$name.log" spotless:check)
  if [ "$status" -ne 0 ]; then
    fail "$name" "spotless:check still fails after spotless:apply (exit $status)"
  elif grep -q "import java.util.List" "$dir/$JAVA_PKG/Unused.java"; then
    fail "$name" "spotless:apply kept the unused import"
  else
    pass "$name"
  fi
}

test_frontend_format_check_passes_on_repo() {
  name=frontend_format_check_passes_on_repo
  dir=$(frontend_copy)
  status=$(npm_in "$dir" "$WORK_DIR/$name.log" format:check)
  if [ "$status" -ne 0 ]; then
    fail "$name" "format:check exited $status (see below)"
    tail -20 "$WORK_DIR/$name.log"
  else
    pass "$name"
  fi
}

test_frontend_lint_passes_on_repo() {
  name=frontend_lint_passes_on_repo
  dir=$(frontend_copy)
  status=$(npm_in "$dir" "$WORK_DIR/$name.log" lint)
  if [ "$status" -ne 0 ]; then
    fail "$name" "lint exited $status (see below)"
    tail -20 "$WORK_DIR/$name.log"
  else
    pass "$name"
  fi
}

test_frontend_format_check_fails_on_unformatted_ts() {
  name=frontend_format_check_fails_on_unformatted_ts
  dir=$(frontend_copy)
  printf 'export const  ugly = {a:1,b:"two"}\n' >"$dir/src/app/ugly.ts"
  status=$(npm_in "$dir" "$WORK_DIR/$name.log" format:check)
  if [ "$status" -eq 0 ]; then
    fail "$name" "format:check passed on badly formatted TypeScript"
  elif ! grep -q "src/app/ugly.ts" "$WORK_DIR/$name.log"; then
    fail "$name" "Prettier didn't report src/app/ugly.ts (exit $status)"
  else
    pass "$name"
  fi
}

test_frontend_format_fixes_ts() {
  name=frontend_format_fixes_ts
  dir=$(frontend_copy)
  printf 'export const  ugly = {a:1,b:"two"}\n' >"$dir/src/app/ugly.ts"
  npm_in "$dir" "$WORK_DIR/$name.format.log" format >/dev/null
  status=$(npm_in "$dir" "$WORK_DIR/$name.log" format:check)
  if [ "$status" -ne 0 ]; then
    fail "$name" "format:check still fails after format (exit $status)"
  elif ! grep -q "b: 'two'" "$dir/src/app/ugly.ts"; then
    fail "$name" "format didn't apply the project's Prettier config"
  else
    pass "$name"
  fi
}

test_frontend_lint_fails_on_selector_without_app_prefix() {
  name=frontend_lint_fails_on_selector_without_app_prefix
  dir=$(frontend_copy)
  cat >"$dir/src/app/bad.ts" <<'EOF'
import { Component } from '@angular/core';

@Component({
  selector: 'bad-widget',
  template: '<p>bad</p>',
})
export class BadWidget {}
EOF
  status=$(npm_in "$dir" "$WORK_DIR/$name.log" lint)
  if [ "$status" -eq 0 ]; then
    fail "$name" "lint passed on a selector without the app prefix"
  elif ! grep -q "@angular-eslint/component-selector" "$WORK_DIR/$name.log"; then
    fail "$name" "lint failed, but not on component-selector (exit $status)"
  else
    pass "$name"
  fi
}

test_eslint_config_has_no_prettier_conflicts() {
  name=eslint_config_has_no_prettier_conflicts
  dir=$(frontend_copy)
  # eslint-config-prettier's CLI lists enabled rules that conflict with Prettier.
  if (cd "$dir" && npx --no eslint-config-prettier src/main.ts src/app/app.html) \
    >"$WORK_DIR/$name.log" 2>&1; then
    pass "$name"
  else
    fail "$name" "conflicting rules or no eslint-config-prettier (see below)"
    tail -20 "$WORK_DIR/$name.log"
  fi
}

test_backend_check_passes_on_repo
test_backend_check_fails_on_unformatted_java
test_backend_check_fails_on_unused_import
test_backend_verify_runs_spotless_check
test_backend_apply_fixes_java
test_frontend_format_check_passes_on_repo
test_frontend_lint_passes_on_repo
test_frontend_format_check_fails_on_unformatted_ts
test_frontend_format_fixes_ts
test_frontend_lint_fails_on_selector_without_app_prefix
test_eslint_config_has_no_prettier_conflicts

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
