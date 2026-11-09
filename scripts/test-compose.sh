#!/bin/sh
# Smoke tests for the local Postgres + pgvector setup (compose.yaml).
# Needs Docker with Compose v2; the runtime tests need the Docker daemon running.
#
#   sh scripts/test-compose.sh
#
# Runs under a throwaway Compose project on a free port, with fake credentials
# generated at runtime, so it never touches your dev database, its volume or
# your .env. Everything it creates is removed on exit.

set -u

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK_DIR=$(mktemp -d)
PROJECT="ask-my-docs-test-$$"

# Values from the caller's shell would override the env file; drop them.
unset POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_PORT

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

# Asks the OS for a free TCP port on localhost.
free_port() {
  python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

TEST_DB=amd_test
TEST_USER=amd_test
TEST_PASSWORD=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)
TEST_PORT=$(free_port)
# An empty port would fall back to compose.yaml's default, 5432: your dev database.
case "$TEST_PORT" in
  '' | *[!0-9]*)
    echo "ABORT: could not find a free port (python3 is needed): '$TEST_PORT'" >&2
    rm -rf "$WORK_DIR"
    exit 2
    ;;
esac

ENV_FILE="$WORK_DIR/test.env"
cat >"$ENV_FILE" <<EOF
POSTGRES_DB=$TEST_DB
POSTGRES_USER=$TEST_USER
POSTGRES_PASSWORD=$TEST_PASSWORD
POSTGRES_PORT=$TEST_PORT
EOF

# --env-file replaces the repo's .env, so a developer's real .env is never read.
compose() {
  docker compose --project-directory "$REPO_ROOT" -f "$REPO_ROOT/compose.yaml" \
    -p "$PROJECT" --env-file "$ENV_FILE" "$@"
}

cleanup() {
  if [ -f "$REPO_ROOT/compose.yaml" ] && docker info >/dev/null 2>&1; then
    compose down -v --remove-orphans >/dev/null 2>&1
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT
# dash (sh on Ubuntu) skips the EXIT trap when killed by a signal; exiting from
# the signal trap runs it.
trap 'exit 130' INT
trap 'exit 143' TERM

psql_app() {
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$TEST_USER" -d "$TEST_DB" -tAc "$1"
}

# --- Config: no daemon needed ------------------------------------------------

test_config_fails_without_postgres_password() {
  name=config_fails_without_postgres_password
  grep -v '^POSTGRES_PASSWORD=' "$ENV_FILE" >"$WORK_DIR/no-password.env"
  if docker compose --project-directory "$REPO_ROOT" -f "$REPO_ROOT/compose.yaml" \
    -p "$PROJECT" --env-file "$WORK_DIR/no-password.env" config >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "config succeeded without POSTGRES_PASSWORD"
  elif ! grep -q "POSTGRES_PASSWORD" "$WORK_DIR/$name.log"; then
    fail "$name" "error does not name POSTGRES_PASSWORD: $(cat "$WORK_DIR/$name.log")"
  else
    pass "$name"
  fi
}

test_config_pins_pgvector_image_tag() {
  name=config_pins_pgvector_image_tag
  if ! compose config >"$WORK_DIR/config.yaml" 2>&1; then
    fail "$name" "compose config failed: $(cat "$WORK_DIR/config.yaml")"
  elif grep -Eq 'image: pgvector/pgvector:[0-9]+\.[0-9]+\.[0-9]+-pg18(-[a-z]+)?@sha256:[0-9a-f]{64}$' "$WORK_DIR/config.yaml"; then
    pass "$name"
  else
    fail "$name" "image is not pinned as pgvector/pgvector:<x.y.z>-pg18[-<debian>]@sha256:<digest>"
  fi
}

# pg_isready over the unix socket also answers for the image's temporary init
# server, which listens on no TCP address; over TCP only the real server answers.
test_config_healthcheck_probes_over_tcp() {
  name=config_healthcheck_probes_over_tcp
  if ! compose config >"$WORK_DIR/config.yaml" 2>&1; then
    fail "$name" "compose config failed: $(cat "$WORK_DIR/config.yaml")"
  elif grep -q 'pg_isready -h 127.0.0.1 ' "$WORK_DIR/config.yaml"; then
    pass "$name"
  else
    fail "$name" "healthcheck does not run pg_isready -h 127.0.0.1"
  fi
}

test_env_example_fails_config_until_password_set() {
  name=env_example_fails_config_until_password_set
  if docker compose --project-directory "$REPO_ROOT" -f "$REPO_ROOT/compose.yaml" \
    -p "$PROJECT" --env-file "$REPO_ROOT/.env.example" config >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "config accepted .env.example as-is (placeholder password works)"
  elif ! grep -q "POSTGRES_PASSWORD" "$WORK_DIR/$name.log"; then
    fail "$name" "error does not name POSTGRES_PASSWORD: $(cat "$WORK_DIR/$name.log")"
  else
    pass "$name"
  fi
}

test_env_example_with_password_passes_config() {
  name=env_example_with_password_passes_config
  sed "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$TEST_PASSWORD/" "$REPO_ROOT/.env.example" \
    >"$WORK_DIR/example.env"
  if docker compose --project-directory "$REPO_ROOT" -f "$REPO_ROOT/compose.yaml" \
    -p "$PROJECT" --env-file "$WORK_DIR/example.env" config >"$WORK_DIR/$name.log" 2>&1; then
    pass "$name"
  else
    fail "$name" ".env.example with a password does not satisfy compose.yaml: $(cat "$WORK_DIR/$name.log")"
  fi
}

test_config_binds_postgres_to_localhost_only() {
  name=config_binds_postgres_to_localhost_only
  if ! compose config >"$WORK_DIR/config.yaml" 2>&1; then
    fail "$name" "compose config failed: $(cat "$WORK_DIR/config.yaml")"
  elif ! grep -q 'host_ip: 127.0.0.1' "$WORK_DIR/config.yaml"; then
    fail "$name" "published port is not bound to 127.0.0.1"
  elif ! grep -q "published: \"$TEST_PORT\"" "$WORK_DIR/config.yaml"; then
    fail "$name" "POSTGRES_PORT is not used as the host port"
  else
    pass "$name"
  fi
}

test_config_mounts_named_volume_at_pg18_data_root() {
  name=config_mounts_named_volume_at_pg18_data_root
  if ! compose config >"$WORK_DIR/config.yaml" 2>&1; then
    fail "$name" "compose config failed: $(cat "$WORK_DIR/config.yaml")"
  elif ! grep -A3 'type: volume' "$WORK_DIR/config.yaml" | grep -q 'target: /var/lib/postgresql$'; then
    fail "$name" "no named volume at /var/lib/postgresql (the PG 18 data root)"
  else
    pass "$name"
  fi
}

# --- Runtime: Docker daemon needed --------------------------------------------

test_up_wait_reports_postgres_healthy() {
  name=up_wait_reports_postgres_healthy
  if ! compose up -d --wait --wait-timeout 120 >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "up --wait failed: $(tail -5 "$WORK_DIR/$name.log")"
  elif [ "$(psql_app 'SHOW server_version_num' 2>/dev/null | cut -c1-2)" != "18" ]; then
    fail "$name" "server is not Postgres 18"
  else
    pass "$name"
  fi
}

test_vector_extension_installed_in_app_database() {
  name=vector_extension_installed_in_app_database
  version=$(psql_app "SELECT extversion FROM pg_extension WHERE extname = 'vector'" 2>&1)
  if [ -n "$version" ] && echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    pass "$name"
  else
    fail "$name" "vector is not installed in $TEST_DB: '$version'"
  fi
}

test_vector_column_supports_distance_query() {
  name=vector_column_supports_distance_query
  result=$(psql_app "CREATE TEMP TABLE items (embedding vector(3));
    INSERT INTO items VALUES ('[1,2,3]'), ('[4,5,6]');
    SELECT embedding FROM items ORDER BY embedding <-> '[3,1,2]' LIMIT 1;" 2>&1)
  if [ "$(echo "$result" | tail -1)" = "[1,2,3]" ]; then
    pass "$name"
  else
    fail "$name" "unexpected nearest neighbour: '$result'"
  fi
}

test_data_persists_after_down_and_up() {
  name=data_persists_after_down_and_up
  if ! psql_app "CREATE TABLE persist_check (v text); INSERT INTO persist_check VALUES ('kept');" >"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "could not write: $(cat "$WORK_DIR/$name.log")"
    return
  fi
  compose down >>"$WORK_DIR/$name.log" 2>&1
  if ! compose up -d --wait --wait-timeout 120 >>"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "restart failed: $(tail -5 "$WORK_DIR/$name.log")"
  elif [ "$(psql_app 'SELECT v FROM persist_check' 2>&1)" = "kept" ]; then
    pass "$name"
  else
    fail "$name" "row lost after down and up"
  fi
}

test_down_v_deletes_data_and_reruns_init() {
  name=down_v_deletes_data_and_reruns_init
  compose down -v >"$WORK_DIR/$name.log" 2>&1
  if ! compose up -d --wait --wait-timeout 120 >>"$WORK_DIR/$name.log" 2>&1; then
    fail "$name" "restart failed: $(tail -5 "$WORK_DIR/$name.log")"
    return
  fi
  tables=$(psql_app "SELECT count(*) FROM pg_tables WHERE tablename = 'persist_check'" 2>&1)
  vector=$(psql_app "SELECT count(*) FROM pg_extension WHERE extname = 'vector'" 2>&1)
  if [ "$tables" != "0" ]; then
    fail "$name" "data survived down -v (persist_check count: '$tables')"
  elif [ "$vector" != "1" ]; then
    fail "$name" "init script did not rerun on the fresh volume (vector count: '$vector')"
  else
    pass "$name"
  fi
}

test_config_fails_without_postgres_password
test_config_pins_pgvector_image_tag
test_config_binds_postgres_to_localhost_only
test_config_mounts_named_volume_at_pg18_data_root
test_config_healthcheck_probes_over_tcp
test_env_example_fails_config_until_password_set
test_env_example_with_password_passes_config

if docker info >/dev/null 2>&1; then
  test_up_wait_reports_postgres_healthy
  test_vector_extension_installed_in_app_database
  test_vector_column_supports_distance_query
  test_data_persists_after_down_and_up
  test_down_v_deletes_data_and_reruns_init
else
  for t in up_wait_reports_postgres_healthy vector_extension_installed_in_app_database \
    vector_column_supports_distance_query data_persists_after_down_and_up \
    down_v_deletes_data_and_reruns_init; do
    fail "$t" "Docker daemon is not running (start Docker Desktop or OrbStack)"
  done
fi

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
