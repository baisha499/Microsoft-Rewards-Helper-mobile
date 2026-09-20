# 必应自动签到（Shizuku 版）

用 **Shizuku** 拿到 adb(shell) 权限，识别前台包名并自动完成签到：

> 点击左上角头像 → 点击 **Microsoft Rewards** → 在“签入”页点击 Day1~Day7 中**第一个还没变黄**的那个

也可以在 App 里点「开始执行每日签到」，会先拉起必应再执行上面三步。

## 工作原理

Shizuku 新版 API 把 `Shizuku#newProcess` 设为私有（官方要求改用 User Service），
所以本项目用 **Shizuku User Service**：由 Shizuku 以 root / shell(adb) 身份拉起的进程，
应用通过 Binder 拿到它的代理，在里面跑 `Runtime.exec("sh -c …")`。

| 环节 | 实现方式（全部走 shell 身份） |
| --- | --- |
| 前台包名识别 | `dumpsys activity activities` / `dumpsys window` |
| 启动目标 App | `cmd package resolve-activity` + `am start`，失败回退 `monkey` |
| 界面元素定位 | `uiautomator dump` → 抽取 `text` / `content-desc` / `bounds` |
| 点击 | `input tap x y`（点到命中节点的中心） |
| 判断 Day 卡片是否变黄 | `screencap` → 在 shell 进程里手写 PNG 解码 → 取卡片区域内的像素黄度打分 |

设计说明：

- 不用 AIDL（手写 `Binder.onTransact` + `Parcel`），避免本机 AIDL 编译环境的坑。
- dump 的 XML 太大（可能上 MB），直接跨 Binder 传会 `TransactionTooLarge`，
  所以先抽成 `text ␁ content-desc ␁ bounds` 的紧凑行再传回来。
- 不使用无障碍服务，也不需要 root（adb 模式的 Shizuku 即可，root 模式同理）。

## 权限

| 权限 | 用途 |
| --- | --- |
| Shizuku 授权 | 执行 shell（找节点、点击、启动 App、截图） |
| 通知（Android 13+） | 后台监听时的常驻通知 |
| 查看应用列表 `QUERY_ALL_PACKAGES` | 「选择」目标应用时列出系统已安装包 |
| 悬浮窗 `SYSTEM_ALERT_WINDOW` | 「选择」坐标时弹出取点覆盖层 |

## 使用步骤

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/zh-hans/download/)，让服务页显示「正在运行」。
2. 安装本 APK。
3. 打开 App → 点「申请 Shizuku 权限」→ 允许。状态区会显示「已安装 / 已连接 / 已授权」三行。
4. 右上角齿轮 → 「目标应用」里确认包名正确（点「选择」可从已安装包挑，默认过滤 bing/microsoft/msn）。
5. 验证环境：点「检测前台包名」能读出包名；点「扫描当前界面」能列出节点与坐标。
6. 点「开始执行每日签到」，看日志是否走到「✓ 复查 DayN … 已变黄」。
7. 需要无人值守：打开「后台监听前台包名…」（前台服务 + 常驻通知，默认每天只跑一次）。

## 配置（右上角齿轮，分组可折叠）

| 配置项 | 说明 |
| --- | --- |
| 目标包名 | 一行一个，默认 `com.microsoft.bing` |
| 第 1 步 · 左上角头像 | 关键词用 `|` 分隔，匹配节点 text 或 content-desc；默认还带了备选坐标 `9%,6%` |
| 第 2 步 · Microsoft Rewards | 同上 |
| 第 3 步 · 签入与 Day 卡片 | 判定词（默认 `签入\|签到\|Check in`）、Day 判定阈值（默认 40，越大越严格） |
| 备选坐标 | 支持 `990,2270` 绝对坐标，或 `9%,6%` 相对屏幕；**点旁边的「选择」可用悬浮窗取点** |
| 轮询间隔 | 监听模式下检测前台包名的间隔（秒） |

### 悬浮窗取点流程

点某个坐标框旁的「选择」→ 首次会让你开启「显示在其他应用上层」→ 再次点「选择」后：

1. 自动拉起目标包名对应的 App；
2. 顶部提示「请点击你想自定义的位置」，此时全屏透明层接管触摸；
3. 点一下屏幕 → 该位置出现**红点**，底部弹出「坐标 x , y ／ 确认 / 取消」；
4. 点**取消** → 红点消失，回到第 3 步继续等你点；
5. 点**确认** → 自动回到本 App、打开对应配置分组并填入坐标，你在设置里点「保存」即可。

## 暂停 / 继续

执行过程中随时可以切回本 App 点「暂停」按钮：当前正在做的那一步（比如等待按钮出现）会停下来等待；
点「继续」后从该步继续往下走。暂停期间不会计入步骤超时。

## 主题

- 亮色：纯白背景 + 纯黑文字/图标
- 暗色：灰色背景（`#2B2B2B` / 卡片 `#3A3A3A`）+ 纯白文字/图标

跟随系统深色模式自动切换（`values` / `values-night`）。

## 目录结构

```
app/src/main/java/com/tt/bingcheckin/
├── Config.kt               配置读写（含取点结果暂存）
├── Shell.kt                Shizuku 权限检查 + shell 调用入口
├── UserShell.kt            绑定 Shizuku User Service
├── UserShellProtocol.kt    手写 Binder IPC 约定
├── ShellUserService.kt     运行在 shell 身份进程里的 shell / dump / 截图取色
├── NodeSummary.kt          dump XML ↔ 节点摘要的转换
├── PngReader.kt            极简 PNG 解码（分析截图用）
├── ScreenAnalyzer.kt       区域取色与“是否变黄”打分
├── UiNode.kt               界面节点数据类
├── Device.kt               前台包名 / 分辨率 / 启动 / 点击 / dump / 取色
├── CheckInEngine.kt        签到流程（支持暂停）
├── MonitorService.kt       前台包轮询监听（前台服务）
├── CoordinatePickerService.kt 悬浮窗取点
├── AppList.kt              系统应用列表（含 QUERY_ALL_PACKAGES）
└── MainActivity.kt         界面 + 设置
```

## 编译

```bash
gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`（也可直接用 Android Studio 打开本目录）。

已在本机验证过的坑：

- 项目路径含中文 → `gradle.properties` 里已加 `android.overridePathCheck=true`。
- AGP 9.x 内置 Kotlin，不要再 apply `org.jetbrains.kotlin.android`，也没有 `kotlinOptions` DSL。
- 中文路径下 `compileDebugAidl` 会抛 `MalformedInputException`，所以本项目不依赖 AIDL。

## 已知限制

- `uiautomator dump` 在息屏、锁屏或某些 WebView 页面可能取不到节点。
- Day 卡片的“变黄”是靠像素黄度打分判断的，若颜色校正/深色模式导致误判，调整阈值即可。
- 除第 3 步的 Day 卡片外，其余步骤都靠关键词匹配，必应改版后请更新关键词或用备选坐标。
- 后台轮询会持续执行 `dumpsys`，建议把轮询间隔调大以降低耗电。
