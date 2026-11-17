#!/bin/bash
set -e
cd /d/dirs/AutoPianoAndroid
git rm --cached .github/workflows/build.yml -q
git commit -m "chore: 暂时移出 CI 工作流（待 token 补 workflow 权限后补推）" -q
git push -u origin main 2>&1 | tail -10