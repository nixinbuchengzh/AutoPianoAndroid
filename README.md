# AutoPianoAndroid (纯 Kotlin 安卓版)

由 PC 版 `engine.py` + `overlay.py` 完整迁移至纯 Kotlin + Android 原生无障碍多指触控，支持 **原神（3排 21键 / 2排 14键）** 与 **光遇（3×5 15键）**。

## 目录结构
- `engine/KeymapPreset.kt`：原神 3×7 / 2×7 与光遇 3×5 键位预设。
- `engine/JianpuParser.kt` & `engine/MidiParser.kt`：纯 Kotlin 简谱与 MIDI 解析/自动移调/八度优化/伴奏让位。
- `engine/EventBuilder.kt` & `engine/PianoPlayer.kt`：拍点对齐智能长音续奏、多指和弦打包调度。
- `service/AutoPianoAccessibilityService.kt`：Android 8.0+ 无障碍 `dispatchGesture` 多指点击注入。
- `service/CalibrationOverlay.kt`：全屏拖拽式网格坐标校准器（拖动左上/右下角圆环对准游戏首尾琴键即可）。
- `service/OverlayService.kt`：不抢焦点的悬浮播放控制窗（支持 `±10s` 快进退、倍速、循环、长音补奏、只弹旋律）。

## 编译与使用步骤
1. 用 **Android Studio** 打开 `AutoPianoAndroid` 根目录，等待 Gradle 同步完成（需 `compileSdk 34` / `minSdk 26`）。
2. 连接手机或点击 `Build -> Build APK(s)` 生成安装包并安装。
3. 打开 App，依次开启 **悬浮窗权限** 与 **无障碍服务（自动弹琴）**。
4. 点击 **打开悬浮控制面板**，切到原神或光遇并打开琴界面，点击悬浮窗 **校准点位** 拖动左上/右下圆环对准首尾琴键后保存。
5. 选择 `.txt` 简谱或 `.mid` 文件即可开始弹奏。
