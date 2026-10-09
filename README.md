# MoreBubbleButton

MoreBubbleButton 是一个 Android/Xposed 模块；本项目 fork 后的全部修改均由 AICoding 完成，为 Pixel Launcher 最近任务界面和 SystemUI 通知中心添加 Bubble/Freeform 能力。

> Evolution 17 是当前主要实机验证基线；Lunaris AOSP 3.12 已针对通知注入路径做过兼容性验证。其他 ROM、Launcher、SystemUI 版本尚未验证，不能假设行为完全兼容。

当前开发分支为 `experimental`，版本号以 `app/build.gradle.kts` 为准；本文档记录的当前版本为 `1.7.11 (19)`。

## 功能

- 在 Pixel Launcher 多任务界面底部操作栏添加「🫧 消息气泡」按钮
- 在任务卡片菜单（点击 app 左上角图标）中添加「🫧 消息气泡」选项
- 点击后将当前选中的应用变为 app bubble
- 自动检测系统语言，提供中文和英文界面
- 在 SystemUI 通知中心为非锁屏、可打开的普通通知显示 Bubble/Freeform 按钮
- Bubble/Freeform 按钮支持两种打开方式：
  - Bubble：创建并展开 app bubble
  - Freeform：优先以系统自由窗口打开
- Freeform 不可用时自动回退到 Bubble，再回退到全屏启动
- 点击通知按钮后：
  - 优先跳转通知 `contentIntent` 对应界面，而不是只打开 app 首页
  - 自动收起通知中心
  - 对 `FLAG_AUTO_CANCEL` 通知执行移除，使其表现为已读/已处理
- Heads-up popup 支持横条模式：保留原生 Bubble/Freeform 按钮，并额外叠加可下滑横条
  - 横条只显示在 Heads-up popup，不显示在普通通知列表或锁屏界面
  - 下滑横条会打开当前通知对应的 Bubble/Freeform 目标
  - 横条长度、粗细和距通知底部的间距可调；间距范围为 `-10dp..24dp`
- 锁屏界面不显示额外的 Bubble/Freeform 按钮或横条
- 自动过滤无启动入口或特殊用户通知，避免 `android` / `user=-1` 等无效场景导致 SystemUI 崩溃
- 设置界面支持 X/Y 轴位置调节、滑杆旁的 `+` / `−` 精细调节按钮，以及横条尺寸设置

## 要求

- Android 16+ (API 36+)
- 已安装 LSPosed / KernelSU + Zygisk
- Pixel Launcher (NexusLauncher)
- SystemUI 通知气泡功能需要把作用域同时勾选到 `com.android.systemui`
- 当前主要实机验证环境：Evolution 17
- Lunaris AOSP 3.12：已验证通知注入兼容路径；其他功能和其他版本仍需单独测试

## 安装

1. 下载 [最新 Release](https://github.com/TYOPXN360/MoreBubbleButton/releases) APK
2. 在 LSPosed 中安装并启用模块
3. 设置作用域：
   - `com.google.android.apps.nexuslauncher`
   - `com.android.launcher3`（如设备使用该包名）
   - `com.android.systemui`（通知中心气泡按钮必需）
4. 强制停止 Pixel Launcher / SystemUI，或重启设备

## 使用

### 方式一：最近任务底部操作栏

进入多任务界面 → 点击底部「🫧 消息气泡」按钮。

### 方式二：任务卡片菜单

进入多任务界面 → 点击任务卡片左上角 app 图标 → 点击「🫧 消息气泡」。

### 方式三：通知中心 Bubble/Freeform 按钮

下拉通知中心 → 对支持打开的普通通知点击 Bubble/Freeform 图标 → 自动打开对应通知界面。

### 方式四：Heads-up 横条

在设置中选择“下滑横条” → 新通知以 Heads-up popup 出现时，原生按钮和横条都会显示 → 下滑横条打开当前通知。

### 方式五：设置界面

打开 MoreBubbleButton 应用 → 调整开关和按钮位置。

## 设置项

| 设置 | 说明 | 默认值 |
|------|------|--------|
| 任务卡片菜单 | 在菜单中显示消息气泡选项 | 开 |
| 底部操作栏 | 在底部显示消息气泡按钮 | 开 |
| 通知 Bubble 按钮 | 在非锁屏通知中显示 Bubble/Freeform 按钮 | 开 |
| Heads-up 操作方式 | Bubble 按钮 / 下滑横条；下滑横条模式会在按钮之外增加横条 | Bubble 按钮 |
| 横条长度 | Heads-up 横条长度 | 56dp |
| 横条粗细 | Heads-up 横条粗细 | 7dp |
| 横条距底部 | 横条相对通知底部的位置，支持 `-10dp..24dp` | 3dp |
| 按钮位置 | 跟随原按钮 / 第二行 | 跟随原按钮 |
| X 轴 | 水平位置，支持滑杆和 `+` / `−` 精调 | 50% |
| Y 轴 | 垂直位置，支持滑杆和 `+` / `−` 精调 | 50% |
| 重启启动器 + 系统界面 | 通过 root 重启 Pixel Launcher / SystemUI 使设置生效 | - |

## 技术实现

基于 [libxposed API 102](https://github.com/libxposed/api)，Hook Pixel Launcher 与 SystemUI。当前实现以 Evolution 17 的类结构和运行行为为主要适配目标，并对 Lunaris AOSP 3.12 的通知 hook 签名保留兼容路径。

### Pixel Launcher

| Hook 目标 | 方法 | 作用 |
|-----------|------|------|
| `OverviewActionsView` | `onFinishInflate` | 注入底部操作栏按钮 |
| `OverviewActionsView` | `onClick` | 处理按钮点击 |
| `TaskMenuView` | `addMenuOptions` | 注入任务卡片菜单项 |

最近任务气泡触发通过 `SystemUiProxy.showAppBubble()` 调用 WMShell Bubble 服务。

### SystemUI

| Hook 目标 | 作用 |
|-----------|------|
| `NotificationContentView.shouldShowBubbleButton` | 为符合条件的非锁屏通知显示 Bubble/Freeform 按钮；兼容无参与 `NotificationEntry` 参数版本，swipe 模式不再隐藏按钮 |
| `BubblesManager.onUserChangedBubble` / `expandStackAndSelectBubble` | 拦截通知气泡点击，改走稳定的 app bubble 路径 |
| `BubbleController.expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, null)` | 使用通知 `contentIntent` 创建并展开 app bubble |
| `BubbleCoordinator.removeNotification` | 在气泡成功展开后移除 auto-cancel 通知 |

## 构建

```bash
cd MoreBubbleButton

# 设备调试版本
./gradlew :app:assembleDebug --offline --no-daemon

# 发布/交付版本：启用 R8 与资源压缩
./gradlew :app:assembleRelease --no-daemon
```

项目使用 Gradle Wrapper、Android Gradle Plugin 和 Kotlin Compose；当前构建要求 JDK 21，Android Studio 自带 JDK 即可。首次构建若本地缓存不完整，可以去掉 `--offline`，但这会需要网络下载依赖。

APK 输出：

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

安装调试 APK（仅在明确需要设备验证时执行）：

```bash
adb devices
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

安装后在 LSPosed 中启用模块并勾选 Launcher 和 SystemUI 作用域；修改 Hook 逻辑后通常需要重新加载 SystemUI/Launcher。没有 root 时不要假设 Agent 可以自动重启系统进程。

## Release

Release 构建启用 R8 minify 与 resource shrink，并移除调试工具依赖；当前仅保留
`material-icons-core` 等实际使用的轻量依赖。若本地配置了正式 release keystore，构建会优先使用它；
没有正式 keystore 时仅为方便本地验证回退到 debug keystore，该 APK 不应作为正式发布签名包分发。
Release APK 通常显著小于 Debug APK，交付前应优先检查 `app/build/outputs/apk/release/app-release.apk`。

## AI 声明与验证边界

本项目 fork 后的全部修改（包括代码、重构、问题排查、文档和构建修改）均由 aicode 完成；人工仅提供需求、设备环境和实际体验反馈。Evolution 17 是主要验证基线，Lunaris AOSP 3.12 只对当前通知注入兼容路径做过验证，任何其他设备或未覆盖功能都必须重新测试。

## 让其他 AI Agent 接手

建议在项目根目录启动 Agent，并把以下内容作为任务上下文：

```text
请维护 MoreBubbleButton。先阅读 app/src/ai-handoff/NOTES.md 和
app/src/ai-handoff/SKILL.md，再检查 git status、当前分支和未提交差异。
保留用户已有修改；只在本模块目录内工作；完成后执行 git diff --check，
构建并报告测试结果。设备验证只使用用户明确提供的设备，不要把设备序列号、
个人路径、通知正文、日志或密钥写入仓库。
```

Agent 接手时应遵循以下顺序：

1. 阅读 `app/src/ai-handoff/NOTES.md` 与 `app/src/ai-handoff/SKILL.md`。
2. 检查当前分支、工作区差异和最近提交，不覆盖未提交的用户修改。
3. 修改代码时使用 `apply_patch`，每个独立阶段创建一个简洁的本地 commit。
4. 至少执行 `git diff --check` 和与任务匹配的构建；需要交付 APK 时执行 Release 构建，安装或重启 SystemUI 前先确认用户要求及设备权限。
5. 只保留脱敏后的结论，不提交 APK、设备 dump、完整 logcat、反编译产物或个人信息。

更详细的模块约束、Freeform 适配说明和日志验证方法见 `app/src/ai-handoff/NOTES.md`；可复用的维护流程见 `app/src/ai-handoff/SKILL.md`。

## License

Apache License 2.0
