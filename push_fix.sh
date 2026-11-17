#!/bin/bash
set -e
cd /d/dirs/AutoPianoAndroid
git add .github/workflows/build.yml
git commit -m "ci: 改为使用官方 commandline-tools 自行安装 Android SDK，规避 setup-android 失败" -q
git push origin main 2>&1 | tail -10