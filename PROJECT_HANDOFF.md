# MarcoMovie 项目交接说明

## 1. 项目位置与仓库

- 本机项目目录：`/Users/lvzhaobing/workspace/marcomovie`
- 当前分支：`develop`
- 当前 HEAD 以 `git log -1` 为准；版本号唯一真值来源是 `android/edition-versions.json`。
- 个人仓库：`mine = git@github.com:geek-lv/MarcoMovie.git`
- 上游仓库：`origin = https://github.com/Pilipala9919/MarcoMovie.git`
- 0.5.26（手机 UI / 豆瓣片单 / 牛皮癣模糊全链路）已在 `3f41daf` 提交、经 `9cfd212` 合并后推送到 `mine/develop`，不是未提交补丁；不要 reset 或 clean。
- 0.5.27 起 TV 与手机端共用同一份 `adblur` 代码，模板只从手机扫码同步到电视（电视不提供采集入口）。

不要提交：

```text
android/src/main/assets/config.json
```

该文件包含本机订阅配置。各处 `.DS_Store` 也不要提交。

## 2. 项目基本情况

Android 部分主要采用 Java + 手写 Android View，不是 Compose，也没有现成的 DataBinding/RecyclerView 列表架构。

当前版本：

- Android TV：`0.5.27 / vCode 79`
- Android 手机版：`0.5.27 / vCode 79`
- minSdk 23，targetSdk 28

关键文件：

- `DoubanHot.java`：豆瓣榜单、分类、分页、每日缓存、卡片绑定及网格绘制。
- `MobileShell.java`：手机主页、搜索、直播、片库、设置及底部导航。
- `MainActivity.java`：页面栈、返回逻辑、窗口尺寸重排和全局图片代次。
- `ImageLoader.java`：海报缓存、异步加载、请求取消及图片回显。
- `HomeScreen.java`：TV 首页，顶部「剧集 / 电影 / 综艺」下拉 + 一行细分榜单 + 豆瓣海报网格；首页无线路切换按钮。
- `adblur/*`：TV / 手机共用的牛皮癣识别与 GL 模糊；`ConfigSyncData.java` + `SyncQrActivity` / `SyncScanActivity` 负责手机把模板（PNG 原字节 + .meta）扫码同步到电视。

## 3. 已完成并提交的历史工作

近期提交包括：

```text
9cfd212 merge: 保留本地 0.5.26 主线（远端 develop 为同源重复分叉）
3f41daf feat(mobile): UI 稳定性、豆瓣片单与视频牛皮癣模糊识别（0.5.17 → 0.5.26）
f368d88 fix(ci): pass API 23 callback and JVM fixture checks
6f844f6 fix(rebase): drop upstream hero-banner leftovers from mobile home
2cf2ace fix(tv): settings row double-touch on TV layout + poster load logging
90d1a1a feat(tv): replace upstream home waterfalls with two-level Douban category grid
```

已经完成过的主要功能：

- TV 首页原片源瀑布流替换为豆瓣网格。
- TV 豆瓣卡片支持遥控器 DPad 聚焦和确认。
- TV 设置项触摸双触问题修复。
- 手机首页删除旧 CMS 网格和「豆瓣热播」横向货架。
- MainActivity 重排风暴治理。
- CI 的 API 23 兼容问题修复，`f368d88` 已推送到个人 `main`。

## 4. 本轮正在做的工作

手机端目前加入：

1. 右上角「建安资源」替换为豆瓣「剧集 / 电影 / 综艺」入口。
2. 片单区只保留一层细分榜单。
3. 共 20 个豆瓣榜单的首批 20 条，按自然日预取和 SharedPreferences 持久缓存。
4. 同一榜单第一页请求合并，避免预取和用户点击重复请求。
5. 下拉请求期间保留旧片单。
6. 相同数据不重复绘制。
7. 尝试按豆瓣条目 ID 复用卡片，标题、评分等属性原位绑定。
8. 尝试让底部各标签页面常驻，通过 `VISIBLE/GONE` 切换，而不是每次 `removeAllViews()`。
9. 新增统一闪烁诊断日志，标签为 `MM_FLICKER`，覆盖：
   - Shell 重建和 `setContentView`
   - 页签切换
   - 下拉手势
   - `GRID_FULL_CLEAR` 及调用栈
   - 卡片实际创建次数
   - 图片绑定、缓存命中、取消和回显
   - MainActivity 尺寸重排

需要特别强调：用户反馈下拉刷新和总导航切换仍然闪烁，目前问题尚未验收解决。不能向接手人或用户宣称已经修好。

## 5. 最新构建与真机状态

最新带日志 APK 已构建成功：

```text
/Users/lvzhaobing/workspace/marcomovie/dist/MarcoMovie-0.5.17-android-mobile.apk
```

构建日志：

```text
/tmp/build-flicker-logs.log
```

最近一次完整构建和格式检查通过；此前 API 23 检查覆盖 318 个类并通过。

但是，最后一次日志诊断包安装时手机变成 `device offline`，所以带完整 `MM_FLICKER` 日志的版本尚未确认安装成功，也尚未抓到用户所说闪烁的复现日志。

测试手机：

```text
100.119.40.69:5555
```

下一步应首先恢复连接并安装日志包：

```bash
adb connect 100.119.40.69:5555
adb -s 100.119.40.69:5555 install -r \
  /Users/lvzhaobing/workspace/marcomovie/dist/MarcoMovie-0.5.17-android-mobile.apk
adb -s 100.119.40.69:5555 logcat -c
```

然后分别复现：

1. 首页顶部下拉刷新。
2. 主页 → 直播 → 片库 → 设置 → 主页。
3. 右上角豆瓣大类切换。
4. 豆瓣封面进入搜索再返回。

抓取日志：

```bash
adb -s 100.119.40.69:5555 logcat -d -v threadtime \
  MM_FLICKER:D AndroidRuntime:E Choreographer:I '*:S' \
  > /tmp/mm-flicker.log
```

重点按时间线检查：

- 闪烁时是否出现 `ACTIVITY_REFRESH` 或 `SHELL_RENDER`。
- 是否发生 `GRID_FULL_CLEAR`，调用栈来自哪里。
- 是否一次刷新创建了大量 `CARD_NEW`。
- 页签切换是否伴随 `IMAGES_CANCEL` 或图片代次变化。
- 下拉手势后是否发生尺寸重排。
- 是视图真正闪烁，还是图片重复置空/回显造成的视觉闪烁。

## 6. 构建环境

```bash
cd /Users/lvzhaobing/workspace/marcomovie

export MARCOMOVIE_JDK=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME="$MARCOMOVIE_JDK"
export MARCOMOVIE_BUILD_TOOLS="$HOME/Library/Android/sdk/build-tools/36.0.0"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export MARCOMOVIE_SKIP_CATPAW=1

python3 scripts/build_android.py
python3 android/tests/run_api23_callback_checks.py
```

格式检查：

```bash
python3 scripts/format_java.py --check
git diff --check
```

## 7. 当前交接结论

当前工作不是“已完成，只差提交”，而是“功能主体已写入并能编译，但闪烁仍未解决；最新日志包已构建，因手机离线尚未完成日志复现”。应先抓日志确认根因，再继续修改或整理提交。
