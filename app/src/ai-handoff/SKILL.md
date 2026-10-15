---
name: more-bubble-button-maintenance
description: 维护 MoreBubbleButton Android/Xposed 模块，尤其是 SystemUI Heads-up、Bubble、Evolution/LMO Freeform 和设置界面。
---

# MoreBubbleButton 维护技能

适用于后续 AI 接手本模块的修复、功能开发和验证任务。本项目由 AI Agent 全程编辑；Evolution 17 是主要实机验证基线，Lunaris AOSP 3.12 已针对通知注入路径做过兼容性验证。

## 必做步骤

1. 先阅读同目录的 `NOTES.md`，再检查模块仓库的 `git status`、最近提交和当前差异。
2. 只在本模块目录内工作，并遵守上级 `agents.md` 的边界约束。
3. 修改前确认现有未提交内容是否属于当前任务；不要覆盖用户已有改动。
4. 对代码改动使用 `apply_patch`，完成一个逻辑阶段后立即创建本地 commit；每次创建交付相关 commit 时使用点号递增小版本并同步 versionCode。
5. 至少执行 `git diff --check` 和与任务匹配的构建；交付 APK 时执行 Release 构建。如果设备可用，再进行安装和短时日志验证。
6. 最终报告提交号、构建结果、设备验证是否完成，以及仍受设备权限限制的项目。

## Freeform 处理规则

- 先检查传统 Freeform feature、全局设置、Evolution DesktopMode 和 `lmo_freeform` Binder 服务。
- LMO Binder 的 PendingIntent 路径要求 system UID，普通 SystemUI UID 不能直接调用；不要仅因 Binder 服务存在就从 SystemUI 手动 transact。
- SystemUI 在发送原始通知 PendingIntent 前应记录 task 快照；之后按通知 user ID、包名和 task 变化关联本次 task，再通过 LMO Receiver 传递真实 `taskId`。不能仅凭包名选择旧 task。
- 若 user ID 不可读，不要默认成用户 0；在发送 PendingIntent 前退出该 task move 路径，保留尚未消费 PendingIntent 的 Bubble/全屏回退机会。
- PendingIntent 已发送后若 task 关联或移动失败，不要再次发送它。组件 Receiver 的 `taskId=-1` 恢复可能丢失通知上下文；明确记录这个限制，不要声称等价保留了通知 deep-link。
- App Bubble 查找必须验证包含 user ID 的精确 Bubble key，禁止仅按包名跨个人/工作资料复用。
- 如果代码运行在真正的 system UID 进程，LMO PendingIntent 路径的参数顺序必须与 AIDL 一致：包名、占位 Activity、`userId=-100`、`taskId=-1`、PendingIntent、宽、高、densityDpi。
- 对尚未发送通知 PendingIntent 的同步失败路径，保留 Bubble 和全屏回退。PendingIntent 已发送后的异步恢复是不同路径，不可通过重发造成重复启动；请根据代码和实机证据说明其限制。
- 不要把“调用返回成功”当成“窗口可用”；要继续检查窗口是否创建、目标任务是否被移除，以及是否出现后台启动拦截。
- `shouldShowBubbleButton` 必须兼容 Evolution 的无参变体和 Lunaris AOSP 3.12 的 `NotificationEntry` 参数变体。

## Heads-up 和设置界面规则

- Bubble/Freeform 按钮在符合条件的非锁屏通知中始终保持显示。
- 横条只存在于 Heads-up popup，不要添加到普通通知或锁屏布局。
- 通知 Bubble/Freeform 按钮与 Heads-up swipe handle 使用独立开关，可同时启用；关闭按钮时横条仍应按自身开关工作。
- Swipe handle 只额外叠加在 Heads-up popup，不得隐藏或改变 Bubble/Freeform 按钮行为；下滑横条应与按钮使用同一打开和回退逻辑。
- 对 auto-cancel 通知请求移除通知；对其他通知只请求收起 Heads-up popup，通知本身仍应留在列表。启动应等待 popup 退出信号，超过当前 10 秒等待上限仍无退出时取消待启动操作。
- `isHeadsUpState()` 轮询可直接观察 popup 状态；`setHeadsUpAnimatingAway(false)` 和 `onEntryAnimatingAwayEnded` 是动画生命周期回调，不能将它们描述为独立的状态确认。通知行 detach 本身不能触发待启动操作。
- 新设置键 `swipe_handle_enabled` 必须兼容旧 `popup_presentation=1` 的偏好迁移；设置 Provider 也要提供迁移后的有效值给 SystemUI。
- 横条底部间距范围为 `-10dp..24dp`；修改设置范围时必须同步更新 `ModuleSettings`、Compose 设置页、`SettingsProvider` 和中英文资源。
- 中英文资源必须同步；英文采用 “Bubble” 和 “Freeform” 等短文案。
- 设置项文案过长时优先缩短摘要或使用单独说明，不要让选择框被长字符串撑大。
- Button position 属于 Recents 下的 Bottom action bar 子设置；只在底部操作栏开启时展示，关闭时隐藏整组位置控件。

## Release 瘦身规则

- Release 保持 R8 minify 和 resource shrink；调试专用依赖不得进入正式依赖图。
- 设置图标优先使用 `material-icons-core` 或本地矢量资源，不要引入 `material-icons-extended`。
- 本地缺少正式 release keystore 时可以使用 Gradle 的 debug keystore 回退做验证，但不得把该 APK 当作正式签名发布包。

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
- 发现行为依赖 ROM 私有实现时，明确记录“Evolution 17 为主要验证基线；Lunaris AOSP 3.12 仅验证通知注入兼容路径；其他环境未验证”，不要宣称通用 Android 兼容。

## 安全边界

- 不读取或提交 keystore、token、账号、通知正文或用户文件。
- 不执行清理整个工作区、重置用户改动或删除未知文件的命令。
- 需要 root、重启 SystemUI 或修改系统分区时，先说明设备权限限制；不要假设 adb shell 具有 root。
