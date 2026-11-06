#!/bin/sh
# Scans the full git history of the current repo for secrets. This is the scan
# CI runs (.github/workflows/gitleaks.yml); scripts/test-gitleaks-hook.sh tests it.
#
#   sh scripts/gitleaks-scan-history.sh [extra gitleaks flags]
#
# Set GITLEAKS to the gitleaks binary if it isn't on PATH.
#
# --log-opts mirrors gitleaks' own default (--full-history --all
# --diff-filter=tuxdb) and adds --diff-merges=first-parent, so content added
# inside a merge commit (conflict resolutions) is scanned too. Without it,
# git log shows no diff for merges.

exec "${GITLEAKS:-gitleaks}" git --redact --verbose --no-banner \
  --log-opts="--full-history --all --diff-filter=tuxdb --diff-merges=first-parent" \
  "$@"
