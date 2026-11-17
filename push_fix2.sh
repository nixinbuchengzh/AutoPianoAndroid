#!/bin/bash
set -e
cd /d/dirs/AutoPianoAndroid
git add -A
git commit -m "fix: 修复 Kotlin 编译错误（Keymaps 前缀、DoubleArray 赋值、coroutineContext 导入、lambda continue）" -q
git push origin main 2>&1 | tail -5