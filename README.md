# Microsoft Rewards Helper（必应自动签到 · Shizuku 版）

包名 `com.baisha.MicrosoftRewardsHelper`。通过 **Shizuku** 拿到 adb(shell) 权限，再配合本应用自带的无障碍服务，
在手机上自动完成 Microsoft Rewards（必应）的**每日签到、每日活动、自动搜索**三类积分任务，支持后台无人值守。

> 本项目由 AI 辅助编写。

## 功能一览

### 1. 每日签到（`开始执行每日签到`）

自动完成：

1. 拉起目标 App（默认 `com.microsoft.bing`），确认已处于前台；
2. 点击左上角用户头像；
3. 点击 **Microsoft Rewards**；
4. 在签入页找到 Day1~Day7 卡片，点击**第一个还没变黄**的那张，点击后复查一次是否变黄。

结束（无论成功与否）都会自动返回本应用。

### 2. 完成每日活动（`完成每日活动`）

1. 同样先进入目标 App → 点头像 → 点 Microsoft Rewards，到达奖励页；
2. 自动**上划两下**，然后等待「每日任务等待时间」（默认 5 秒）；
3. 截图取色，在所有含 `+10` 文案的节点里只保留**蓝底白字**的（`+10` 是白色文字、衬在蓝色按钮上：5×5 采样点里蓝底点 ≥ 2、白字点 ≥ 1），然后**随机挑一个**点击（页面上一般是 3 个卡片）；
4. 点击后等待「每日任务等待时间」（默认 5 秒），用全局手势返回奖励页，再找下一个，直到界面上再也找不到 `+10` 为止（安全上限 20 个）；
5. 找不到可点的蓝底白字 `+10` 按钮时提示「每日活动已完成」。

### 3. 自动搜索任务（`自动搜索任务`）

1. 次数可选：5 / 10 / 20 次，或自定义 1~200 次；
2. 点击必应首页写着「探索国内新鲜事」的搜索框；
3. 从内置词典 `assets/dictionary.txt`（5000+ 个词）随机抽取**不重复**的词；
4. 用无障碍 `ACTION_SET_TEXT` **逐字输入**（模仿真人打字节奏，每字间隔 80~220ms 随机），中文也能输入；
5. 点键盘上的「搜索」按钮（找不到就回退 `input keyevent 66`）→ 等待「搜索等待时间」（默认 5 秒）→ 点顶部搜索框 → 清空 → 搜下一个词；
6. 达到次数后返回本应用。

执行过程中可随时点「取消」，当前协程会被中断。

### 4. 后台监听（无人值守）

- 开关「后台监听前台包名，自动执行签到」：启动前台服务 + 常驻通知，按轮询间隔检测前台包名；
- 命中目标包名即自动跑一次签到；
- 开关「每天只执行一次」：同一天内只跑一次（记录在 SharedPreferences 的日期里）；
- 屏幕熄灭时跳过检测，Shizuku 未授权时通知里提示等待授权；
- 通知会实时显示当前前台包名和最近 4 条日志。

### 5. 释放 / 恢复自动化（主界面按钮）

需要临时自己用手机时，点「释放自动化（解绑 Shizuku）」：

- 立即解绑 Shizuku User Service（shell 进程退出），此后 `UserShell.get()` 一律返回 null，
  **所有 shell 调用（前台包名、启动、dump、点击、截图取色）都不会执行**，也不会有线程被阻塞；
- 三条任务流程的入口会直接返回并提示「自动化已释放」；后台监听仍在运行但每轮自动跳过，通知里显示「已释放自动化」；
- 主界面除监听开关外的功能按钮置灰，状态区显示「自动化：已释放（不执行任何 shell）」；
- 释放状态写进 SharedPreferences，进程重启后保持；
- 再点一次「恢复自动化（重新绑定 Shizuku）」，下次用到时会自动重新绑定并继续。

### 6. 悬浮窗取点（可视化配置坐标）

签到三步的备选坐标都不用手输：点坐标框旁的「选择」即可。

1. 取第 2 / 第 3 步坐标时，会**先自动跑完前面的步骤**进入对应界面；
2. 全屏透明覆盖层接管触摸，顶部提示「请点击你想自定义的位置」；
3. 点一下屏幕 → 该处出现红点，底部弹出坐标面板（同时显示百分比）；
4. 面板上有 ← → ↑ ↓ 四个按钮，每次微调 5px；
5. 点**取消**：红点消失，可重新点选；点**确认**：自动回到本应用、展开对应配置分组并填入坐标（结果也存进 prefs，后台起界面被系统拦截时下次打开仍会补填）。

### 7. 环境检测与诊断

- 「检测前台包名」：读取当前前台 App 包名；
- 「扫描当前界面」：列出当前界面有文本的节点及其中心坐标（最多 80 个）；
- 「诊断界面抓取（uiautomator）」：一键输出无障碍通道状态、无障碍节点数、`id`、SDK 版本、`uiautomator` 是否存在、dump 直出/落盘结果、最终解析节点数等，用于排查取不到界面的原因；
- 状态区实时显示「Shizuku 客户端是否安装 / 服务是否连接（API 版本）/ 是否已授权」和「无障碍是否已连接」，页面可见时每 2 秒刷新；
- 「重新连接 Shizuku」：Shizuku 只在 App 冷启动时投递 binder，拿不到时会先等待，超时后优雅重启本应用进程（`finishAffinity` + AlarmManager 拉起），不会弹「应用运行异常」。

### 8. 设置页（右上角齿轮）

- **签到配置**：目标应用 / 目标包名、三步关键词与备选坐标、Day 判定阈值、轮询间隔，分组可折叠；
- **目标应用选择**：点「选择」从已安装包里挑，默认过滤 bing / microsoft / msn / 必应；
- **权限管理**：应用列表、悬浮窗、通知三项的授予状态与一键申请；
- **数据管理**：显示缓存大小、「清理缓存」（只清 cache 目录，不动用户配置）；
- **备份 / 导入配置**：通过系统文件管理器把全部 SharedPreferences 导出为 JSON（`microsoft_rewards_helper_config_时间戳.json`），或从 JSON 恢复（导入后提示重启应用以完全生效）；
- **一键启用无障碍**：通过 Shizuku 授予 `WRITE_SECURE_SETTINGS` 后写入 `enabled_accessibility_services`，不用手动去系统设置里点；也可「打开系统无障碍设置」手动开启。

## 工作原理

Shizuku 新版 API 把 `Shizuku#newProcess` 设为私有（官方要求改用 User Service），
所以本项目用 **Shizuku User Service**：由 Shizuku 以 root / shell(adb) 身份拉起进程，
应用通过 Binder 拿到代理，在里面跑 `Runtime.exec("sh -c …")`。

界面读取与点击有**两条通道**：无障碍优先，失败回退 shell。

| 环节 | 实现方式 |
| --- | --- |
| 读界面 | 无障碍 `rootInActiveWindow` 遍历节点 → 失败回退 `uiautomator dump` → 抽取 `text ␁ content-desc ␁ bounds` |
| 点击 / 滑动 / 返回 | 无障碍手势 `dispatchGesture` → 失败回退 `input tap` / `input swipe` / `input keyevent 4` |
| 输入文字 | 无障碍 `ACTION_SET_TEXT`（中文也能输，逐字递增） |
| 前台包名 | `dumpsys activity activities` / `dumpsys window` |
| 屏幕状态 | `dumpsys power` |
| 分辨率 | `wm size`（用于把 `9%,6%` 这类相对坐标换算成绝对坐标） |
| 启动目标 App | `getLaunchIntentForPackage` → `cmd package resolve-activity` + `am start` → `monkey` |
| 判断 Day 卡片是否变黄 | `screencap` → 在 shell 进程里手写 PNG 解码 → 取卡片区域像素的黄度打分，与阈值比较 |

设计说明：

- 不用 AIDL（手写 `Binder.onTransact` + `Parcel`），避免本机 AIDL 编译环境的坑。
- dump 的 XML 太大（可能上 MB），跨 Binder 直接传会 `TransactionTooLarge`，所以先抽成紧凑行再回传。
- 不需要 root，adb 模式的 Shizuku 即可（root 模式同理）。

## 权限

| 权限 | 用途 |
| --- | --- |
| Shizuku 授权 | 执行 shell（前台包名、启动 App、截图取色、写入无障碍设置） |
| 无障碍服务 | 读界面、点击、滑动、输入文字（自动搜索必需） |
| 通知（Android 13+） | 后台监听 / 取点时的常驻前台服务通知 |
| `QUERY_ALL_PACKAGES` | 「选择」目标应用时列出系统已安装包 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗取点覆盖层 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 后台监听、取点两个前台服务 |

## 使用步骤

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/zh-hans/download/)，让服务页显示「正在运行」。
2. 安装本 APK 并打开。
3. 点「申请 Shizuku 权限」→ 允许；状态区显示客户端已安装 / 服务已连接 / 已授权。
4. 点「启用无障碍取界面（通过 Shizuku）」→ 状态区显示「服务已连接=true」。
   （无障碍未连接时，下方所有功能按钮会被禁用并在日志里提示。）
5. 右上角齿轮 → 「签到配置」里确认目标包名；点「选择」可从已安装包挑。
6. 验证环境：「检测前台包名」能读出包名；「扫描当前界面」能列出节点与坐标。
7. 点「开始执行每日签到」/「完成每日活动」/「自动搜索任务」，看日志是否走到成功那一步。
8. 需要无人值守：打开「后台监听前台包名，自动执行签到」。

## 配置说明（设置 → 签到配置，分组可折叠）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| 目标包名 | `com.microsoft.bing` | 一行一个（支持换行/逗号/分号/空格分隔） |
| 第 1 步 · 左上角头像 | 关键词留空，坐标 `112,192` | 该步直接用坐标点击，不做关键词匹配（留关键词则先按关键词找） |
| 第 2 步 · Microsoft Rewards | `Microsoft Rewards\|Rewards\|微软奖励\|奖励` | 匹配节点 text 或 content-desc |
| 第 3 步 · 签入页判定词 | `签入\|签到\|Check in\|Check-in` | 用于确认已进入签入页 |
| Day 判定阈值 | 22（范围 1~200，越大越严格） | 黄度打分 ≥ 阈值即认为该 Day 已签过 |
| 备选坐标 | 空 | 支持 `990,2270` 绝对坐标或 `9%,6%` 相对屏幕；点旁边「选择」可用悬浮窗取点 |
| 轮询间隔 | 3 秒（范围 1~60） | 后台监听时检测前台包名的间隔 |
| 搜索等待时间 | 5 秒（范围 1~120） | 自动搜索里每次点搜索按钮后的等待时间 |
| 每日任务等待时间 | 5 秒（范围 1~120） | 每日活动：上划后的等待时间，以及点击 +10 后的等待时间 |

关键词匹配规则：完全匹配优先 → 按关键词顺序 → 再按位置（第 1 步限定屏幕上方 35%，
并且会排除含「已签入 / 已签到 / 已打卡 / 已领取 / 明日再来 / 已经连续」等已完成字样的节点）。
Day 卡片只认带 `Day N` 或 `第N天` 字样的节点，同一天取面积最大的那个，避免把「搜索 1 次」这类任务框误判成 Day 卡片。
关键词 6 秒内没命中且配了备选坐标时，会直接用备选坐标点击；没配坐标则最长等 20 秒。

## 主题

- 亮色：纯白背景 + 纯黑文字/图标
- 暗色：灰色背景（`#2B2B2B` / 卡片 `#3A3A3A`）+ 纯白文字/图标

跟随系统深色模式自动切换（`values` / `values-night`）。

## 目录结构

```
app/src/main/java/com/baisha/MicrosoftRewardsHelper/
├── MainActivity.kt             主界面：状态、三类任务按钮、日志、监听开关
├── SettingsActivity.kt         设置首页：导航、权限、缓存、备份/导入
├── StepConfigActivity.kt       签到配置页（分组折叠 + 悬浮窗取点入口）
├── BingAccessibilityService.kt 无障碍服务：取节点、点击、滑动、输入、IME 搜索
├── A11y.kt                     无障碍通道的开关与调用封装（含通过 Shizuku 自动开启）
├── CheckInEngine.kt            签到 / 每日活动 / 自动搜索三条流程
├── Config.kt                   配置读写、备份导入导出、缓存统计清理
├── Device.kt                   前台包名 / 屏幕状态 / 分辨率 / 启动 / 点击 / 取色
├── MonitorService.kt           前台包轮询监听（前台服务 + 通知）
├── CoordinatePickerService.kt  悬浮窗取点（红点 + 微调 + 确认/取消）
├── Shell.kt                    Shizuku 权限检查 + shell 调用入口
├── UserShell.kt                绑定 Shizuku User Service
├── UserShellProtocol.kt        手写 Binder IPC 约定
├── ShellUserService.kt         运行在 shell 身份进程里的 shell / dump / 截图取色
├── NodeSummary.kt              dump XML ↔ 节点摘要的转换
├── ScreenAnalyzer.kt           截图与“是否变黄”打分
├── PngReader.kt                极简 PNG 解码（分析截图用）
├── RectSpec.kt                 区域列表的序列化
├── UiNode.kt                   界面节点数据类
└── AppList.kt                  系统应用列表（含 QUERY_ALL_PACKAGES）
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

- 无障碍服务必须处于「已连接」状态，否则所有功能按钮禁用；部分 ROM 写入 `enabled_accessibility_services` 后仍需手动到系统设置里打开一次。
- `uiautomator dump` 在息屏、锁屏或某些 WebView 页面可能取不到节点（此时依赖无障碍通道）。
- Day 卡片的「变黄」靠像素黄度打分判断，颜色校正 / 深色模式导致误判时调整阈值即可。
- 每日活动的「蓝底白字 +10 按钮」靠截图取色判断：节点矩形外扩 4px 后取 5×5 采样点，统计偏蓝的采样点（`ScreenAnalyzer.BLUE_MIN`，蓝度 ≥ 25）和接近纯白的采样点（`ScreenAnalyzer.WHITE_MIN`），要求蓝底点 ≥ 2 且白字点 ≥ 1；截图取色失败会退化为在所有 `+10` 节点里随机挑一个。
- 除第 3 步的 Day 卡片外，其余步骤都靠关键词匹配；必应改版后请更新关键词或用备选坐标。
- 自动搜索依赖内置词典与「探索国内新鲜事」这个搜索框提示语，必应改版后可能需更新 `CheckInEngine.SEARCH_BOX_HINT` 或替换词典。
- 后台轮询会持续执行 `dumpsys`，建议把轮询间隔调大以降低耗电。
