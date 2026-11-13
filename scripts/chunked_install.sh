#!/usr/bin/env bash
# 分块安装 APK 到通过 NAS 中继连接的设备（手机 WiFi 不稳，大文件直传必断）
# 用法: chunked_install.sh <adb_serial> <apk_path>
set -u
SERIAL="$1"
APK="$2"
ADB="adb -s $SERIAL"

NAME=$(basename "$APK")
REMOTE_DIR=/data/local/tmp

connect() {
  # 直连序列号（Tailscale IP 或中继地址都直接用序列号本身连接）
  adb connect "$SERIAL" >/dev/null 2>&1
}

# 1. 重置连接
adb disconnect >/dev/null 2>&1
adb connect "$SERIAL" >/dev/null 2>&1
sleep 1

# 2. 分块推送（块名带 APK 哈希前缀：不同构建绝不与残留旧块混淆；同构建可断点续传）
APK_HASH=$(md5 -q "$APK" | cut -c1-8)
DIR=$(mktemp -d)
split -b 4m "$APK" "$DIR/chunk-"
OK=0; TOTAL=0
for chunk in "$DIR"/chunk-*; do
  cname=$(basename "$chunk"); TOTAL=$((TOTAL+1))
  REMOTE_SZ=$($ADB shell stat -c%s "$REMOTE_DIR/mm-$APK_HASH-$cname" 2>/dev/null | tr -d '\r')
  LOCAL_SZ=$(stat -f%z "$chunk")
  if [ -n "$REMOTE_SZ" ] && [ "$REMOTE_SZ" = "$LOCAL_SZ" ]; then
    echo "$cname SKIP(resume)"
    OK=$((OK+1)); continue
  fi
  for attempt in 1 2 3 4 5 6; do
    if $ADB push "$chunk" "$REMOTE_DIR/mm-$APK_HASH-$cname" 2>&1 | grep -qE "pushed|bytes"; then
      OK=$((OK+1)); break
    fi
    sleep 2; connect
  done
done
echo "PUSHED=$OK/$TOTAL"
[ "$OK" -ne "$TOTAL" ] && { echo "INSTALL_ABORTED (chunks incomplete)"; rm -rf "$DIR"; exit 1; }

# 3. 设备端拼接 + 校验大小
LOCAL_SIZE=$(stat -f%z "$APK")
$ADB shell "cat $REMOTE_DIR/mm-$APK_HASH-chunk-* > $REMOTE_DIR/$NAME" >/dev/null 2>&1
REMOTE_SIZE=$($ADB shell stat -c%s "$REMOTE_DIR/$NAME" 2>/dev/null | tr -d '\r')
echo "SIZE local=$LOCAL_SIZE remote=$REMOTE_SIZE"
if [ "$LOCAL_SIZE" != "$REMOTE_SIZE" ]; then
  echo "INSTALL_ABORTED (size mismatch)"
  rm -rf "$DIR"; exit 1
fi

# 4. pm install
$ADB shell pm install -r "$REMOTE_DIR/$NAME"
RC=$?

# 5. 清理设备临时文件（含历史构建的旧块）
$ADB shell "rm -f $REMOTE_DIR/mm-* $REMOTE_DIR/$NAME" >/dev/null 2>&1
rm -rf "$DIR"

# 6. 验证版本
$ADB shell dumpsys package com.pilipala.marcomovie 2>/dev/null | grep -E "versionName|versionCode" | head -2
[ $RC -eq 0 ] && echo "INSTALL_OK" || echo "INSTALL_FAILED"
exit $RC
