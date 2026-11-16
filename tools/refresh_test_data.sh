#!/usr/bin/env bash
set -euo pipefail
exec python3 "$(dirname "$0")/refresh_test_data.py"
