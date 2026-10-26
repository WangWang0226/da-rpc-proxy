#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
"$project_dir/gradlew" --no-daemon clean installDist
echo "Built $project_dir/build/install/besu-rpc-proxy"
