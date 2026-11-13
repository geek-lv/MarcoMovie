#!/usr/bin/env python3
"""MarcoMovie 闪烁复现自动化脚本 - redroid 上运行"""
import subprocess
import time
import sys
import os

ADB = ["adb", "-s", "100.92.158.48:5555"]
LOG_FILE = "/tmp/mm-flicker-redroid.log"
SCREENSHOT_DIR = "/tmp/redroid-shots"

# 关键坐标（从 UI dump 获取）
COORD = {
    "home_tab": (255, 1691),
    "live_tab": (445, 1691),
    "library_tab": (634, 1691),
    "settings_tab": (824, 1691),
    "douban_major": (706, 205),
    "card_1": (167, 1206),
    "card_2": (540, 1206),
    "card_3": (913, 1206),
}

def run(cmd, timeout=10):
    """运行 adb 命令"""
    r = subprocess.run(ADB + cmd, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout.strip(), r.stderr.strip()

def tap(x, y):
    run(["shell", "input", "tap", str(x), str(y)])
    print(f"  → tap ({x},{y})")

def swipe(x1, y1, x2, y2, dur=300):
    run(["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(dur)])
    print(f"  → swipe ({x1},{y1})→({x2},{y2}) dur={dur}ms")

def keyevent(code):
    run(["shell", "input", "keyevent", str(code)])
    print(f"  → keyevent {code}")

def screenshot(name):
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    path = f"{SCREENSHOT_DIR}/{name}.png"
    # screencap outputs binary PNG, must NOT use text=True
    r = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True, timeout=10)
    with open(path, "wb") as f:
        f.write(r.stdout)
    print(f"  → screenshot saved: {path} ({len(r.stdout)} bytes)")

def sleep(sec):
    time.sleep(sec)
    print(f"  → sleep {sec}s")

# ════════════════════════════════════════════
# 主流程
# ════════════════════════════════════════════

print("=" * 60)
print("MarcoMovie 闪烁复现测试 - redroid")
print("=" * 60)

# 1. 重启 App 到首页
print("\n[准备] 强制停止 App 并重新启动")
run(["shell", "am", "force-stop", "com.pilipala.marcomovie"])
sleep(1)
run(["shell", "am", "start", "-n", "com.pilipala.marcomovie/.MainActivity"])
sleep(4)
screenshot("00-app-started")

# 2. 清空 logcat
print("\n[准备] 清空 logcat")
run(["logcat", "-c"])
print("  → logcat cleared")

# 3. 启动后台日志捕获
print("\n[准备] 启动后台日志捕获")
log_proc = subprocess.Popen(
    ADB + ["logcat", "-v", "threadtime", "MM_FLICKER:D", "Choreographer:I", "AndroidRuntime:E", "*:S"],
    stdout=open(LOG_FILE, "w"),
    stderr=subprocess.DEVNULL,
)
print(f"  → logcat PID={log_proc.pid}, output → {LOG_FILE}")
sleep(2)

# ════════════════════════════════════════════
# 场景 ①：首页顶部下拉刷新
# ════════════════════════════════════════════
print("\n" + "=" * 60)
print("场景 ①：首页顶部下拉刷新")
print("=" * 60)
# 先滚动到顶部
run(["shell", "input", "keyevent", "192"])  # KEYCODE_MOVE_HOME
sleep(1)
# 下拉
swipe(540, 100, 540, 450, 500)
sleep(2)
screenshot("01-pull-refresh-start")
sleep(5)  # 等待刷新完成
screenshot("01-pull-refresh-done")
sleep(2)

# ════════════════════════════════════════════
# 场景 ②：主页 → 直播 → 片库 → 设置 → 主页
# ════════════════════════════════════════════
print("\n" + "=" * 60)
print("场景 ②：页签切换 主页→直播→片库→设置→主页")
print("=" * 60)
tap(*COORD["live_tab"])
sleep(2)
screenshot("02-tab-live")
tap(*COORD["library_tab"])
sleep(2)
screenshot("02-tab-library")
tap(*COORD["settings_tab"])
sleep(2)
screenshot("02-tab-settings")
tap(*COORD["home_tab"])
sleep(3)
screenshot("02-tab-home-back")
sleep(2)

# ════════════════════════════════════════════
# 场景 ③：右上角豆瓣大类切换
# ════════════════════════════════════════════
print("\n" + "=" * 60)
print("场景 ③：豆瓣大类切换 剧集→电影→综艺→剧集")
print("=" * 60)
tap(*COORD["douban_major"])
sleep(2)
screenshot("03-drawer-opened")
# 抽屉里选"电影" - 需要先看抽屉的 UI
# 假设抽屉在中间偏下，选项垂直排列
# 先截图看看
# tap 电影 (大概在中间位置)
tap(540, 900)
sleep(3)
screenshot("03-switched-to-movie")
sleep(2)
# 再点大类按钮切到综艺
tap(*COORD["douban_major"])
sleep(2)
screenshot("03-drawer-opened-2")
tap(540, 1000)
sleep(3)
screenshot("03-switched-to-variety")
sleep(2)
# 切回剧集
tap(*COORD["douban_major"])
sleep(2)
tap(540, 800)
sleep(3)
screenshot("03-switched-back-to-drama")
sleep(2)

# ════════════════════════════════════════════
# 场景 ④：豆瓣封面进入搜索再返回
# ════════════════════════════════════════════
print("\n" + "=" * 60)
print("场景 ④：点击豆瓣封面 → 搜索 → 返回")
print("=" * 60)
tap(*COORD["card_1"])
sleep(3)
screenshot("04-search-opened")
sleep(2)
keyevent(4)  # BACK
sleep(3)
screenshot("04-back-to-home")
sleep(2)

# ════════════════════════════════════════════
# 停止日志捕获
# ════════════════════════════════════════════
print("\n[完成] 停止日志捕获")
log_proc.terminate()
log_proc.wait(timeout=5)

# 也 dump 一次完整日志
print("[完成] 导出完整日志")
r = subprocess.run(
    ADB + ["logcat", "-d", "-v", "threadtime", "MM_FLICKER:D", "Choreographer:I", "AndroidRuntime:E", "*:S"],
    capture_output=True, text=True, timeout=15
)
with open(LOG_FILE + ".full", "w") as f:
    f.write(r.stdout)

print(f"\n日志已保存:")
print(f"  → {LOG_FILE} (后台捕获)")
print(f"  → {LOG_FILE}.full (完整 dump)")
print(f"  → {SCREENSHOT_DIR}/ (截图)")

# 统计
with open(LOG_FILE, "r") as f:
    lines = f.readlines()
flicker_count = sum(1 for l in lines if "MM_FLICKER" in l)
choreo_count = sum(1 for l in lines if "Choreographer" in l)
skipped_frames = sum(1 for l in lines if "Skipped" in l)

print(f"\n日志统计:")
print(f"  总行数: {len(lines)}")
print(f"  MM_FLICKER 日志: {flicker_count}")
print(f"  Choreographer 日志: {choreo_count}")
print(f"  Skip frames 警告: {skipped_frames}")
print(f"  截图数: {len(os.listdir(SCREENSHOT_DIR))}")
print("\n" + "=" * 60)
print("测试完成！")
print("=" * 60)
