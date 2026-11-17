#!/bin/bash
set -e
cd /d/dirs/AutoPianoAndroid
git commit -m "feat: 初版 AutoPiano Android + GitHub Actions 自动构建"
git branch -M main
git log --oneline -1
git remote -v