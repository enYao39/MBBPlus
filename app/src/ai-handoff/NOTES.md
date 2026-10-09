# MoreBubbleButton 接手说明

这是一份给后续维护者或其他 AI 使用的项目说明。它只记录代码结构、设计约束和可复用的验证方法，不包含设备序列号、用户目录、通知正文、完整日志或任何密钥。

## 项目边界

- 当前模块是独立 Git 仓库，工作范围限定在本模块目录内。
- 修改前先阅读上级目录的 `agents.md`；不要修改该规则文件。
- 不要把构建产物、设备导出数据、原始 logcat 或个人路径提交到仓库。
- 每完成一个可独立回退的阶段就创建一次本地 commit，commit message 使用简洁英文描述。

## 代码入口

- `app/src/main/java/com/floatwindow/morebubblebutton/MoreBubbleHookModule.java`
  - SystemUI：通知 Heads-up、Bubble 按钮、横条手势、通知启动和降级逻辑。
  - Launcher：最近任务相关的 Bubble/Freeform 行为。
- `app/src/main/java/com/floatwindow/morebubblebutton/ModuleSettings.java`
  - 设置键、默认值和读写方法。
- `app/src/main/java/com/floatwindow/morebubblebutton/SettingsProvider.java`
  - 设置页与远端/模块设置数据的桥接。
- `app/src/main/java/com/floatwindow/morebubblebutton/ui/SettingsScreen.kt`
  - Compose 设置界面。
- `app/src/main/res/values*/strings.xml`
  - 英文、简体中文和其他语言资源；涉及用户可见文本时同步更新。

## 当前行为约定

1. 打开方式可选 Bubble 或 Freeform。
2. Heads-up 中的原生 Bubble 按钮和底部横条互斥；横条只添加到 Heads-up popup。
3. Freeform 启动失败时依次回退到 Bubble，再回退到全屏 PendingIntent。
4. 文案统一使用 “Bubble”，不要重新引入 “Message Bubble”。
5. 没有 root 时不能把“手动重启 SystemUI”当作模块功能；只能提示用户使用设备支持的重载方式。

## Evolution/LMO Freeform 适配

Evolution 衍生系统可能不暴露传统的 `FEATURE_FREEFORM_WINDOW_MANAGEMENT` 或 `enable_freeform_support`，但仍提供 LMO Freeform 服务。当前实现按公开 LMOFreeform 源码适配：

- Binder 服务名：`lmo_freeform`
- AIDL 描述符：`com.libremobileos.freeform.ILMOFreeformUIService`
- `startAppInFreeform` 是第一个 Binder 方法，事务号为 `1`。
- 传递通知原始 `PendingIntent` 时，`userId` 必须使用 LMO 的特殊值 `-100`，`taskId` 使用 `-1`，这样服务才会走 PendingIntent 分支并保留通知 deep-link/extras。
- 该 Binder 接口要求 system UID；SystemUI 是独立的应用 UID，不能直接调用。SystemUI 路径应先发送原始 PendingIntent 创建真实任务，再通过导出的 Receiver 传递真实 `taskId`，让 LMO 将任务移动到 Freeform 显示。
- 组件 Receiver 的 `taskId=-1` 路径只作为最后兼容回退；它按组件重新启动 Activity，可能丢失通知上下文，不应作为首选。

公开参考仓库：
[ProjectInfinity-X/packages_apps_LMOFreeform](https://github.com/ProjectInfinity-X/packages_apps_LMOFreeform)

如果系统侧 AIDL 发生变化，优先重新检查上述公开源码和目标 ROM 的反编译结果，再修改手动 Parcel 编码，不要凭猜测调整事务号或参数顺序。

## 建议验证流程

```sh
# 在模块仓库根目录执行
./gradlew :app:assembleDebug

# 仅在用户明确要求安装测试时执行
adb devices
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk

# 按需抓取短时、脱敏后的相关日志；不要提交原始日志文件
adb -s <device-serial> logcat -c
adb -s <device-serial> logcat -v brief \
  -s MoreBubbleModule:V LMOFreeform:V ActivityTaskManager:W
```

重点观察：

- `Freeform supported by lmo_freeform Binder service`
- `launched notification PendingIntent via LMO Binder`
- LMO 服务是否创建窗口后立即出现 `onTaskRemoved`
- 是否仍有 `Background activity launch blocked`

如果 APK 已更新但日志没有新 Hook，先确认 SystemUI 已重新加载模块；部分设备不允许 adb shell 在无 root 情况下重启持久化的 SystemUI，此时应让用户手动重启或重新启动设备后再测。

## 提交和隐私要求

- 先 `git diff --check`，再构建，再提交。
- 一个提交只完成一个逻辑阶段，例如“Freeform Binder launch fix”或“Add AI handoff notes”。
- 提交前检查 `git status --short`，不要提交 APK、Gradle 缓存、反编译产物、设备 dump、通知正文和包含个人路径的文件。
- 日志、截图和测试报告如需留存，必须先去除序列号、账号、应用通知内容、文件路径和时间线中不必要的个人信息。
