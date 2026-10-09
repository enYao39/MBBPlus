---
name: more-bubble-button-maintenance
description: 维护 MoreBubbleButton Android/Xposed 模块，尤其是 SystemUI Heads-up、Bubble、Evolution/LMO Freeform 和设置界面。
---

# MoreBubbleButton 维护技能

适用于后续 AI 接手本模块的修复、功能开发和验证任务。本项目由 AI Agent 全程编辑；目前只在 Evolution 17 实机上测试过。

## 必做步骤

1. 先阅读同目录的 `NOTES.md`，再检查模块仓库的 `git status`、最近提交和当前差异。
2. 只在本模块目录内工作，并遵守上级 `agents.md` 的边界约束。
3. 修改前确认现有未提交内容是否属于当前任务；不要覆盖用户已有改动。
4. 对代码改动使用 `apply_patch`，完成一个逻辑阶段后立即创建本地 commit；若用户要求版本更新，使用点号递增小版本并同步 versionCode。
5. 至少执行 `git diff --check` 和 Debug 构建；如果设备可用，再进行安装和短时日志验证。
6. 最终报告提交号、构建结果、设备验证是否完成，以及仍受设备权限限制的项目。

## Freeform 处理规则

- 先检查传统 Freeform feature、全局设置、Evolution DesktopMode 和 `lmo_freeform` Binder 服务。
- LMO Binder 的 PendingIntent 路径要求 system UID，普通 SystemUI UID 不能直接调用；不要仅因 Binder 服务存在就从 SystemUI 手动 transact。
- SystemUI 应先发送原始通知 PendingIntent，再查询新任务并通过 LMO Receiver 传递真实 `taskId` 将任务移动到 Freeform；只有任务查询不可用时才使用组件 Receiver/ActivityOptions 回退。
- 如果代码运行在真正的 system UID 进程，LMO PendingIntent 路径的参数顺序必须与 AIDL 一致：包名、占位 Activity、`userId=-100`、`taskId=-1`、PendingIntent、宽、高、densityDpi。
- 所有路径都必须最终支持 Bubble 和全屏回退。
- 不要把“调用返回成功”当成“窗口可用”；要继续检查窗口是否创建、目标任务是否被移除，以及是否出现后台启动拦截。

## Heads-up 和设置界面规则

- Bubble/Freeform 按钮在符合条件的非锁屏通知中始终保持显示。
- 横条只存在于 Heads-up popup，不要添加到普通通知或锁屏布局。
- Swipe 模式只额外叠加横条，不得隐藏或改变原生 Bubble/Freeform 按钮行为；下滑横条应与按钮使用同一打开和回退逻辑。
- 横条底部间距范围为 `-10dp..24dp`；修改设置范围时必须同步更新 `ModuleSettings`、Compose 设置页、`SettingsProvider` 和中英文资源。
- 中英文资源必须同步；英文采用 “Bubble” 和 “Freeform” 等短文案。
- 设置项文案过长时优先缩短摘要或使用单独说明，不要让选择框被长字符串撑大。

## 构建和测试

- 优先使用项目 Gradle Wrapper；项目当前要求 Android Studio JDK 21。
- 构建失败时区分源码错误、JDK/Gradle 缓存问题和网络问题，不要随意升级版本。
- 设备命令中的序列号始终使用 `<device-serial>` 占位符；不要把真实序列号写入源码、文档或 commit message。
- 日志只保留必要的 tag 和结论，通知内容、账号、应用数据和完整 dumpsys 不得进入仓库。

## AI Agent 工作方式

- 先读取 `NOTES.md`，再读取本文件；然后检查分支、工作区、最近提交和用户已有 diff。
- 把任务拆成可回退的小阶段，每个阶段单独提交，commit message 使用简洁英文。
- 不要凭设备序列号、个人绝对路径或历史日志推断环境；文档和命令使用 `<device-serial>` 等占位符。
- 仅在用户要求或任务明确需要时安装 APK、采集 logcat、重启 SystemUI 或执行需要 root 的操作。
- 发现行为依赖 Evolution 17 私有实现时，记录为“Evolution 17 已验证、其他环境未验证”，不要宣称通用 Android 兼容。

## 安全边界

- 不读取或提交 keystore、token、账号、通知正文或用户文件。
- 不执行清理整个工作区、重置用户改动或删除未知文件的命令。
- 需要 root、重启 SystemUI 或修改系统分区时，先说明设备权限限制；不要假设 adb shell 具有 root。
