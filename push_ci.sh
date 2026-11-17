#!/bin/bash
set -e
cd /d/dirs/AutoPianoAndroid
git add .github/workflows/build.yml
git commit -m "ci: 添加 GitHub Actions 自动构建 APK 工作流" -q
git push origin main 2>&1 | tail -10