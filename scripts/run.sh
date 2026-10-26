#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ ! -x "$project_dir/build/install/besu-rpc-proxy/bin/besu-rpc-proxy" ]; then
  "$project_dir/scripts/build.sh"
fi

exec "$project_dir/build/install/besu-rpc-proxy/bin/besu-rpc-proxy"
