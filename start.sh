#!/usr/bin/env bash
# 牵响一键启动器 — 双击即玩，不弹终端
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p logs

# 后台启动，所有输出写入日志，用户看不到终端
nohup ./gradlew runClient > logs/launcher.log 2>&1 &
