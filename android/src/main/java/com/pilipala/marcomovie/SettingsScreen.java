package com.pilipala.marcomovie;

import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.pilipala.marcomovie.adskip.AdSkipConfig;
import com.pilipala.marcomovie.adskip.AdSkipController;
import com.pilipala.marcomovie.safety.AdultSourceStats;
import com.pilipala.marcomovie.safety.ContentFilterConfig;
import com.pilipala.marcomovie.safety.ContentGate;
import java.io.*;

/** Remote-friendly settings. Each row changes a setting consumed by the app. */
final class SettingsScreen {
    final MainActivity a;

    SettingsScreen(MainActivity activity) {
        a = activity;
    }

    void page(String title, String note) {
        a.renderPage("", "");
        if (!a.isTvDevice()) {
            LinearLayout topNav = a.row();
            topNav.setGravity(Gravity.CENTER_VERTICAL);
            topNav.setPadding(0, 0, 0, a.dp(14));
            TextView backBtn = a.text("‹", 22, MainActivity.WHITE);
            backBtn.setGravity(Gravity.CENTER);
            backBtn.setBackground(
                    MobileShell.glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
            MobileDetailScreen.bindTouchFeedback(backBtn);
            backBtn.setOnClickListener(v -> a.onBackPressed());
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
            backLp.rightMargin = a.dp(10);
            topNav.addView(backBtn, backLp);
            TextView heading = a.text(title, 22, MainActivity.WHITE);
            heading.setTypeface(null, Typeface.BOLD);
            topNav.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            a.body.addView(topNav, new LinearLayout.LayoutParams(-1, -2));
        } else {
            a.body.addView(a.text(title, 28, MainActivity.WHITE));
            a.gap(a.body, 10);
        }
        if (!note.isEmpty()) {
            a.paragraph(note);
            a.gap(a.body, 6);
        }
    }

    void link(String title, String detail, String key, Runnable action) {
        boolean tv = a.isTvDevice();
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(20), a.dp(15), a.dp(20), a.dp(15));
        MobileShell.SettingIconView icon =
                new MobileShell.SettingIconView(
                        a, MobileShell.SettingIconView.kindForSetting(key, title));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);
        LinearLayout copy = a.vertical();
        copy.addView(a.text(title, 17, MainActivity.WHITE));
        TextView caption = a.text(detail, 12, MainActivity.MUTED);
        caption.setPadding(0, a.dp(4), 0, 0);
        if (!detail.isEmpty()) copy.addView(caption);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 23, MainActivity.MUTED));
        row.setTag(key);
        row.setId(View.generateViewId());
        row.setFocusable(tv);
        row.setFocusableInTouchMode(tv);
        row.setClickable(true);
        row.setBackground(a.bg(0xff222226, 14));
        row.setOnFocusChangeListener(
                (v, f) -> {
                    FocusMotion.style(
                            a,
                            row,
                            f,
                            0xff222226,
                            0xff37373e,
                            14,
                            MainActivity.WHITE,
                            MainActivity.WHITE,
                            true,
                            1.008f);
                });
        if (tv) {
            a.pressFeedback(row);
        } else {
            MobileShell.bindCardTouchFeedback(a, row, a.bg(0xff222226, 14), a.bg(0xff303038, 14));
        }
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = a.dp(14);
        a.body.addView(row, size);
    }

    void open(Runnable page) {
        a.open(new MainActivity.Screen(page), true);
    }

    void show() {
        page("设置", "");
        link("首页与壁纸", "", "settings-appearance", () -> open(this::appearance));
        link("播放与字幕", "", "settings-playback", () -> open(this::playback));
        link("搜索与数据", "", "settings-data", () -> open(this::data));
        link(
                "口播广告跳过",
                AdSkipConfig.isEnabled()
                        ? ("开启 · " + AdSkipConfig.getKeywordCount() + " 个关键词 · 跳 "
                                + AdSkipConfig.getSkipSeconds() + " 秒")
                        : "已关闭",
                "settings-adskip",
                () -> open(this::adSkip));
        link(
                "NSFW 内容过滤",
                ContentGate.locked()
                        ? "过滤开启"
                        : (ContentFilterConfig.unlocked() ? "临时解锁中" : "过滤关闭"),
                "settings-safety",
                () -> open(this::contentSafety));
        link("软件更新", "", "settings-updates", () -> open(() -> new UpdateScreen(a).show()));
    }

    // ------------------------------------------------------------ 口播广告跳过

    void adSkip() {
        page(
                "口播广告跳过",
                "识别烧录在音轨里的口播广告并自动快进。全程本地离线识别（sherpa-onnx），不上传任何音频；仅在「Media3」播放器下生效。");
        link(
                "口播关键词",
                AdSkipConfig.getKeywordCount() + " 个 · " + AdSkipConfig.getKeywordsRaw(),
                "adskip-keywords",
                this::editAdKeywords);
        link(
                "识别灵敏度",
                AdSkipConfig.getSensitivityLabel()
                        + " · "
                        + AdSkipConfig.SENSITIVITY_HINTS[AdSkipConfig.getSensitivity()],
                "adskip-sensitivity",
                this::pickAdSensitivity);
        link(
                "命中后跳过时长",
                AdSkipConfig.getSkipSeconds() == 0
                        ? "关闭"
                        : AdSkipConfig.getSkipSeconds() + " 秒",
                "adskip-duration",
                this::pickAdDuration);
        link(
                "引擎自检",
                "查看模型 / 关键词 / 探针 / 状态机",
                "adskip-diag",
                () ->
                        new AppDialog.Builder(a)
                                .setTitle("口播引擎自检")
                                .setMessage(
                                        AdSkipController.get().diagnostics()
                                                + "\n播放器："
                                                + (a.prefs.getInt(Prefs.PLAYER_ENGINE, 0) == 1
                                                        ? "系统播放器（探针不生效）"
                                                        : "Media3（探针生效）"))
                                .setPositiveButton("关闭", null)
                                .show());
        link(
                "恢复默认设置",
                AdSkipConfig.DEFAULT_KEYWORDS,
                "adskip-reset",
                () ->
                        confirm(
                                "恢复默认关键词与跳过时长？",
                                () -> {
                                    AdSkipConfig.setKeywordsRaw(AdSkipConfig.DEFAULT_KEYWORDS);
                                    AdSkipConfig.setSkipSeconds(
                                            AdSkipConfig.DEFAULT_SKIP_SECONDS);
                                    AdSkipConfig.setSensitivity(AdSkipConfig.DEFAULT_SENSITIVITY);
                                    AdSkipController.get().onConfigChanged(a);
                                    a.refresh();
                                }));
    }

    void editAdKeywords() {
        final EditText input = new EditText(a);
        input.setText(AdSkipConfig.getKeywordsRaw());
        input.setHint("多个关键词用逗号分隔，如：澳门新葡京,打开APP领取");
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        input.setTextSize(15);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP);
        input.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        input.setBackground(a.bg(0xff36363e, 16));
        new AppDialog.Builder(a)
                .setTitle("口播关键词")
                .setView(dialogBox(input, "清空关键词即关闭检测；修改后立即生效，无需重启。"))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (d, i) -> {
                            AdSkipConfig.setKeywordsRaw(input.getText().toString());
                            AdSkipController.get().onConfigChanged(a);
                            a.toast("已保存 " + AdSkipConfig.getKeywordCount() + " 个关键词");
                            a.refresh();
                        })
                .show();
    }

    void pickAdSensitivity() {
        String[] labels = new String[AdSkipConfig.SENSITIVITY_LABELS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = AdSkipConfig.SENSITIVITY_LABELS[i] + " · " + AdSkipConfig.SENSITIVITY_HINTS[i];
        }
        new AppDialog.Builder(a)
                .setTitle("识别灵敏度")
                .setMessage(
                        "口播之间没有停顿（例如「别再羡慕别人暴富」后面紧跟「澳门新葡京」）时，"
                                + "标准档会因为等不到词尾静音而漏检，这时选「灵敏」或「极敏」。\n"
                                + "档位越高越容易命中，也越可能把剧里台词误判成广告。")
                .setSingleChoiceItems(
                        labels,
                        AdSkipConfig.getSensitivity(),
                        (d, i) -> {
                            AdSkipConfig.setSensitivity(i);
                            AdSkipController.get().onConfigChanged(a);
                            d.dismiss();
                            a.refresh();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    void pickAdDuration() {
        final int[] values = {0, 10, 15, 20, 30, 45, 60, 90};
        String[] labels = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] == 0 ? "关闭检测" : values[i] + " 秒";
            if (values[i] == AdSkipConfig.getSkipSeconds()) checked = i;
        }
        final int chosen = checked;
        new AppDialog.Builder(a)
                .setTitle("命中后跳过时长")
                .setSingleChoiceItems(
                        labels,
                        chosen,
                        (d, i) -> {
                            AdSkipConfig.setSkipSeconds(values[i]);
                            AdSkipController.get().onConfigChanged(a);
                            d.dismiss();
                            a.refresh();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 内容安全

    void contentSafety() {
        page(
                "NSFW 内容过滤",
                "本地关键词 / 番号 / 来源画像过滤，不联网、不上传。判定为 NSFW 的条目、频道与整块来源结果在显示前就被丢弃。");
        if (ContentFilterConfig.hasPin())
            link(
                    "儿童锁",
                    ContentFilterConfig.unlocked()
                            ? ("已临时解锁 · 剩余 "
                                    + ContentFilterConfig.unlockMinutesLeft()
                                    + " 分钟 · 点击重新锁定")
                            : "已锁定 · 点击临时解锁",
                    "safety-pin-gate",
                    this::safetyPinGate);
        link(
                "内容过滤总开关",
                ContentFilterConfig.enabled() ? "开启" : "关闭",
                "safety-enabled",
                () ->
                        confirm(
                                ContentFilterConfig.enabled()
                                        ? "关闭内容过滤？"
                                        : "开启内容过滤？",
                                () -> {
                                    ContentFilterConfig.setEnabled(
                                            !ContentFilterConfig.enabled());
                                    a.refresh();
                                }));
        link(
                "额外过滤关键词",
                ContentFilterConfig.extraKeywordCount() + " 个",
                "safety-extra",
                this::editExtraKeywords);
        link(
                "设置 / 修改 PIN",
                ContentFilterConfig.hasPin() ? "已设置" : "未设置（无需 PIN 即可改设置）",
                "safety-pin",
                this::editPin);
        link(
                "已标记 NSFW 来源",
                ContentFilterConfig.adultCount() + " 个 · 点击查看 / 解除",
                "safety-marks",
                () -> open(this::adultMarks));
        link(
                "清除所有来源标记",
                "",
                "safety-clear-marks",
                () ->
                        confirm(
                                "清除全部 NSFW 来源标记？",
                                () -> {
                                    ContentFilterConfig.clearMarks();
                                    AdultSourceStats.clearAll();
                                    a.refresh();
                                }));
    }

    void editExtraKeywords() {
        final EditText input = new EditText(a);
        input.setText(ContentFilterConfig.extraKeywordsRaw());
        input.setHint("逗号分隔；这些词会追加到内置规则之外");
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        input.setTextSize(15);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setGravity(Gravity.TOP);
        input.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        input.setBackground(a.bg(0xff36363e, 16));
        new AppDialog.Builder(a)
                .setTitle("额外过滤关键词")
                .setView(dialogBox(input, "留空表示只用内置规则。"))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (d, i) -> {
                            ContentFilterConfig.setExtraKeywordsRaw(
                                    input.getText().toString());
                            a.toast(
                                    "已保存 "
                                            + ContentFilterConfig.extraKeywordCount()
                                            + " 个关键词");
                            a.refresh();
                        })
                .show();
    }

    void editPin() {
        final EditText input = pinInput("新 PIN（4–6 位数字）");
        AppDialog.Builder builder =
                new AppDialog.Builder(a)
                        .setTitle("儿童锁 PIN")
                        .setView(
                                dialogBox(
                                        input,
                                        "PIN 只在本机保存加盐摘要；临时解锁 30 分钟，重启应用自动恢复锁定。"))
                        .setNegativeButton("取消", null)
                        .setPositiveButton(
                                "保存",
                                (d, i) -> {
                                    if (!ContentFilterConfig.setPin(
                                            input.getText().toString())) {
                                        a.toast("PIN 必须是 4–6 位数字");
                                        return;
                                    }
                                    a.toast("PIN 已保存");
                                    a.refresh();
                                });
        if (ContentFilterConfig.hasPin())
            builder.setNeutralButton(
                    "清除 PIN",
                    (d, i) -> {
                        ContentFilterConfig.clearPin();
                        a.toast("PIN 已清除");
                        a.refresh();
                    });
        builder.show();
    }

    void safetyPinGate() {
        if (ContentFilterConfig.unlocked()) {
            ContentFilterConfig.lock();
            a.toast("已重新锁定");
            a.refresh();
            return;
        }
        final EditText input = pinInput("4–6 位数字 PIN");
        new AppDialog.Builder(a)
                .setTitle("临时解锁 30 分钟")
                .setView(dialogBox(input, "解锁期间不做任何过滤，重启应用自动恢复。"))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "解锁",
                        (d, i) -> {
                            if (!ContentFilterConfig.verifyPin(input.getText().toString())) {
                                a.toast("PIN 不正确");
                                return;
                            }
                            ContentFilterConfig.unlock();
                            a.toast("已临时解锁 30 分钟");
                            a.refresh();
                        })
                .show();
    }

    void adultMarks() {
        page("已标记 NSFW 来源", "这些来源的结果会被整块丢弃。点击可解除标记。");
        java.util.List<String> keys =
                new java.util.ArrayList<>(ContentFilterConfig.adultKeys());
        java.util.Collections.sort(keys);
        if (keys.isEmpty()) {
            a.paragraph("暂无标记。");
            return;
        }
        for (String key : keys) {
            final String k = key;
            String raw = ContentFilterConfig.markName(key);
            final String name = (raw == null || raw.isEmpty()) ? key : raw;
            link(
                    name + "（" + key + "）",
                    "原因：" + ContentFilterConfig.markReason(key),
                    "safety-mark-" + key,
                    () ->
                            confirm(
                                    "解除「" + name + "」的 NSFW 标记？",
                                    () -> {
                                        ContentFilterConfig.unmark(k);
                                        a.refresh();
                                    }));
        }
    }

    private EditText pinInput(String hint) {
        EditText input = new EditText(a);
        input.setHint(hint);
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        input.setTextSize(18);
        input.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        input.setBackground(a.bg(0xff36363e, 16));
        return input;
    }

    private LinearLayout dialogBox(View field, String note) {
        LinearLayout box = a.vertical();
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        box.addView(field);
        if (note != null && !note.isEmpty())
            box.addView(a.text(note, 12, MainActivity.MUTED));
        return box;
    }

    void decoders() {
        page("解码组件", "组件随应用安装，无需另外下载解码包。");
        a.paragraph(DecoderSupport.description());
    }

    void accountAction(String action) {
        if (a.config != null) {
            Cms.Site fallback = null;
            for (Cms.Site site : a.config.sites) {
                if (site.api != null && site.api.contains("MyDrive")) {
                    fallback = site;
                    break;
                }
                if (fallback == null && SourceController.isDedicatedConfigSite(site)) {
                    fallback = site;
                }
            }
            if (fallback == null) {
                for (Cms.Site site : a.config.sites) {
                    if (SourceController.isConfigOrNetdiskSite(site)) {
                        fallback = site;
                        break;
                    }
                }
            }
            if (fallback != null) {
                if (!a.available(fallback)) {
                    a.notice("请先在“搜索与数据”中启用来源扩展。");
                    return;
                }
                final Cms.Site targetSite = fallback;
                String api =
                        targetSite.api == null
                                ? ""
                                : targetSite.api.toLowerCase(java.util.Locale.ROOT);
                String key =
                        targetSite.key == null
                                ? ""
                                : targetSite.key.toLowerCase(java.util.Locale.ROOT);
                boolean isPanConfig =
                        api.contains("panconfig")
                                || key.contains("panconfig")
                                || key.contains("wexconfig");
                if (isPanConfig) {
                    if ("LoginShow".equals(action)) {
                        final String[] labels =
                                new String[] {
                                    "夸克网盘扫码登录",
                                    "UC网盘 Cookie 扫码登录",
                                    "UC网盘 TV Token 扫码登录",
                                    "百度网盘扫码登录",
                                    "115网盘扫码登录",
                                    "天翼网盘扫码登录",
                                    "天翼网盘账号密码登录",
                                    "移动云盘扫码登录",
                                    "光鸭网盘扫码登录",
                                    "迅雷云盘扫码登录",
                                    "哔哩哔哩扫码登录",
                                    "123网盘账号密码设置",
                                    "打开完整配置中心"
                                };
                        final String[] codes =
                                new String[] {
                                    "quark",
                                    "uccookie",
                                    "uctoken",
                                    "baidu",
                                    "pan115",
                                    "pan189",
                                    "pan189account",
                                    "pan139",
                                    "guangya",
                                    "thunder",
                                    "bili",
                                    "pan123manual",
                                    "__open_config__"
                                };
                        java.util.function.IntConsumer handleChoice =
                                idx -> {
                                    if (idx < 0 || idx >= codes.length) return;
                                    String code = codes[idx];
                                    if ("__open_config__".equals(code)) {
                                        a.prefs
                                                .edit()
                                                .putString(Prefs.SELECTED_SITE, targetSite.key)
                                                .apply();
                                        MobileShell.activeTab = 0;
                                        a.back.clear();
                                        a.open(new MainActivity.Screen(a::home), false);
                                    } else {
                                        a.sourceAction(targetSite, code);
                                    }
                                };
                        if (!a.isTvDevice()) {
                            MobileShell.showDrawerPicker(
                                    a,
                                    "网盘扫码与账号登录",
                                    "选择需要扫码或配置凭据的网盘服务",
                                    labels,
                                    -1,
                                    handleChoice::accept);
                        } else {
                            new AppDialog.Builder(a)
                                    .setTitle("网盘扫码与账号登录")
                                    .setItems(labels, (d, which) -> handleChoice.accept(which))
                                    .setNegativeButton("关闭", null)
                                    .show();
                        }
                        return;
                    }
                    if ("pushCkShow".equals(action)) {
                        a.sourceAction(targetSite, "web");
                        return;
                    }
                    if ("panSortShow".equals(action) || "panSourceSortShow".equals(action)) {
                        a.sourceAction(targetSite, "panorder");
                        return;
                    }
                }
                a.sourceAction(targetSite, action);
                return;
            }
        }
        a.notice("当前配置没有提供网盘账号入口，请先导入支持网盘的配置源。");
    }

    void appearance() {
        page("首页与壁纸", "界面按屏幕分辨率自动适配，海报保持 2:3 比例。");
        link(
                "界面大小",
                TvLayoutPolicy.percent(a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100))
                        + "% · 按钮、文字与图标一起调整",
                "setting-ui_scale_percent",
                () -> {
                    String[] scaleLabels = new String[] {"标准 · 自动适配", "放大 · 110%", "更大 · 120%"};
                    int[] scaleValues = new int[] {100, 110, 120};
                    int curScale =
                            a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 120
                                    ? 2
                                    : a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 110 ? 1 : 0;
                    if (!a.isTvDevice()) {
                        MobileShell.showDrawerPicker(
                                a,
                                "界面大小",
                                "标准会自动适配屏幕分辨率；选择后界面重新载入",
                                scaleLabels,
                                curScale,
                                n -> {
                                    a.prefs
                                            .edit()
                                            .putInt(Prefs.UI_SCALE_PERCENT, scaleValues[n])
                                            .commit();
                                    a.recreate();
                                });
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("界面大小")
                            .setMessage("标准会自动适配屏幕分辨率。观看距离较远时可放大；选择后界面重新载入。")
                            .setSingleChoiceItems(
                                    scaleLabels,
                                    curScale,
                                    (d, n) -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.UI_SCALE_PERCENT, scaleValues[n])
                                                .commit();
                                        a.recreate();
                                    })
                            .setNegativeButton("返回", null)
                            .show();
                });
        ints(
                "导航栏字体与按钮大小",
                Prefs.NAV_SCALE_PERCENT,
                new String[] {"紧凑 · 85%", "标准 · 100%", "偏大 · 115%", "大号 · 130%", "特大 · 140%"},
                new int[] {85, 100, 115, 130, 140},
                100);
        ints(
                "每行海报",
                Prefs.HOME_COLUMNS,
                new String[] {"最多 5 张 · 舒展", "最多 6 张", "最多 7 张", "最多 8 张"},
                new int[] {5, 6, 7, 8},
                5);
        link(
                "首页壁纸",
                a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)
                        ? "自定义壁纸"
                        : a.prefs.getBoolean(Prefs.HOME_AMBIENT, true) ? "海报配色柔光" : "纯深色",
                "settings-wallpaper",
                () -> open(this::wallpaper));
        toggle(
                "减少透明效果",
                Prefs.REDUCED_TRANSPARENCY,
                false,
                () -> com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root));
        toggle("开屏动画", Prefs.STARTUP_ANIMATION, true, null);
        toggle("海报配色柔光", Prefs.HOME_AMBIENT, true, null);
        toggle(
                "减少动态效果",
                Prefs.REDUCED,
                false,
                () -> a.reduced = a.prefs.getBoolean(Prefs.REDUCED, false));
    }

    void wallpaper() {
        page("首页壁纸", "壁纸只影响应用背景，不会更改电视桌面。图片以居中裁切显示，自动缩小以减少电视内存占用。");
        link("选择本地图片", "JPG、PNG、WebP", "wallpaper-file", () -> pick(44, "image/*", false));
        link("从图片地址导入", "输入你自己的 HTTP / HTTPS 图片地址", "wallpaper-url", this::wallpaperUrl);
        ints(
                "背景压暗",
                Prefs.WALLPAPER_DIM,
                new String[] {"轻 · 40%", "适中 · 60%", "深 · 80%"},
                new int[] {40, 60, 80},
                60);
        link(
                "恢复默认背景",
                "保留图片文件，随时可以重新启用",
                "wallpaper-reset",
                () -> {
                    a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, false).apply();
                    a.refresh();
                });
        if (new File(a.getFilesDir(), "wallpaper.jpg").exists())
            link(
                    "启用已保存的壁纸",
                    "使用上次导入的图片",
                    "wallpaper-enable",
                    () -> {
                        a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
                        a.refresh();
                    });
    }

    void playback() {
        page("播放与字幕", "作为之后播放的默认设置；播放界面中仍可随时调整。");
        ints(
                "画面输出",
                Prefs.VIDEO_OUTPUT,
                new String[] {"保真输出 · HDR 与流畅度优先", "兼容输出 · 支持应用内调色"},
                new int[] {0, 1},
                0);
        ints("播放器", Prefs.PLAYER_ENGINE, new String[] {"Media3", "系统播放器"}, new int[] {0, 1}, 0);
        ints(
                "解码方式",
                Prefs.DECODER_MODE,
                new String[] {"自动", "硬件解码", "软件解码优先"},
                new int[] {0, 1, 2},
                0);
        toggle("优先使用兼容音频解码", Prefs.SOFTWARE_AUDIO, false, null);
        toggle("优先选择 AAC 音轨", Prefs.PREFER_AAC, false, null);
        link("解码组件", "查看本机已加载的音频与视频扩展", "settings-decoders", () -> open(this::decoders));
        ints(
                "画面比例",
                Prefs.ASPECT,
                new String[] {"适应", "铺满", "16:9", "4:3", "裁切填充"},
                new int[] {0, 1, 2, 3, 4},
                0);
        ints(
                "网络缓冲",
                Prefs.BUFFER_SECONDS,
                new String[] {"15 秒 · 节省内存", "30 秒 · 均衡", "50 秒 · 网络波动"},
                new int[] {15, 30, 50},
                30);
        link(
                "默认速度",
                a.prefs.getFloat(Prefs.PLAY_SPEED, 1) + "×",
                "setting-play_speed",
                () -> {
                    String[] speedLabels =
                            new String[] {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"};
                    float[] speedVals = new float[] {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
                    float curSpeed = a.prefs.getFloat(Prefs.PLAY_SPEED, 1f);
                    int curIdx = 2;
                    for (int i = 0; i < speedVals.length; i++) {
                        if (Math.abs(curSpeed - speedVals[i]) < 0.05f) {
                            curIdx = i;
                            break;
                        }
                    }
                    if (!a.isTvDevice()) {
                        MobileShell.showDrawerPicker(
                                a,
                                "默认播放速度",
                                "作为之后进入播放时的初始倍速",
                                speedLabels,
                                curIdx,
                                i -> {
                                    a.prefs.edit().putFloat(Prefs.PLAY_SPEED, speedVals[i]).apply();
                                    a.refresh();
                                });
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("播放速度")
                            .setItems(
                                    speedLabels,
                                    (d, i) -> {
                                        a.prefs
                                                .edit()
                                                .putFloat(Prefs.PLAY_SPEED, speedVals[i])
                                                .apply();
                                        a.refresh();
                                    })
                            .show();
                });
        toggle("自动播放下一集", Prefs.AUTO_NEXT, true, null);
        ints("循环", Prefs.REPEAT_MODE, new String[] {"关闭", "单集循环", "列表循环"}, new int[] {0, 1, 2}, 0);
        int skipIntroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_INTRO, 0));
        link(
                "跳过片头（精准拉取节点）",
                skipIntroSec <= 0
                        ? "关闭（点击可手动拉取秒数）"
                        : PlaybackController.formatTime(skipIntroSec * 1000)
                                + " · "
                                + skipIntroSec
                                + " 秒",
                "setting-" + Prefs.SKIP_INTRO,
                () -> PlaybackControls.showSkipAdjuster(a, true, 0, 0, a::refresh));
        int skipOutroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_OUTRO, 0));
        link(
                "跳过片尾（精准拉取节点）",
                skipOutroSec <= 0
                        ? "关闭（点击可手动拉取秒数）"
                        : PlaybackController.formatTime(skipOutroSec * 1000)
                                + " · "
                                + skipOutroSec
                                + " 秒",
                "setting-" + Prefs.SKIP_OUTRO,
                () -> PlaybackControls.showSkipAdjuster(a, false, 0, 0, a::refresh));
        int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
        int subBottom = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
        int subStyle = Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
        link(
                a.isTvDevice() ? "字幕大小与位置（全屏预览调校）" : "字幕全屏调试（双指缩放 · 按住拖拽）",
                "字号 "
                        + subSize
                        + " · 底部高度 "
                        + subBottom
                        + "dp · "
                        + SubtitleOverlayView.STYLE_LABELS[subStyle],
                "setting-" + Prefs.SUBTITLE_SIZE,
                () -> PlaybackControls.showSubtitleAdjuster(a, null, a::refresh));
        ints(
                "字幕样式",
                Prefs.SUBTITLE_STYLE,
                SubtitleOverlayView.STYLE_LABELS,
                new int[] {0, 1, 2, 3, 4, 5, 6, 7},
                0);
        toggle("自动加载来源弹幕", Prefs.DANMAKU_DEFAULT, true, null);
        ints(
                "画面色彩",
                Prefs.PICTURE_EFFECT,
                new String[] {"原始", "明亮", "影院", "柔和"},
                new int[] {0, 1, 2, 3},
                0);
        ints(
                "音效",
                Prefs.AUDIO_EFFECT,
                new String[] {"原声", "影院", "人声清晰", "柔和低音"},
                new int[] {0, 1, 2, 3},
                0);
        a.paragraph(
                "兼容音频解码仅切换音频，不强制视频软解码；AAC 优先仅在媒体包含可用 AAC 音轨时生效，不会转换文件。以上两项用于 Media3。音效取决于电视提供的音频接口。系统播放器支持的字幕和网络格式少于 Media3；播放器中的字幕按钮可导入外部字幕。");
    }

    void data() {
        page("搜索与数据", "搜索默认识别首拼和全拼，并检索配置中全部可搜索来源。");
        ints(
                "同时搜索的来源数",
                Prefs.SEARCH_THREADS,
                new String[] {"2 个 · 轻量", "4 个 · 均衡", "6 个 · 快速"},
                new int[] {2, 4, 6},
                6);
        ints(
                "单个来源等待时间",
                Prefs.SEARCH_TIMEOUT,
                new String[] {"10 秒", "20 秒", "30 秒"},
                new int[] {10, 20, 30},
                20);
        link(
                "导入或更新配置源",
                a.isTvDevice()
                        ? "手机扫码、遥控输入或本地包 (.json/.js/.py/.zip)"
                        : "剪贴板粘贴、网址输入或本地包 (.json/.js/.py/.zip)",
                "settings-import",
                a::importDialog);
        link(
                "导入或管理电视直播源",
                "支持 M3U、TXT (#genre#)、JSON 及本地直播文件",
                "settings-live-import",
                () -> SourceController.showLiveImportDialog(a));
        if (a.config != null && a.config.depots.length() > 1) {
            link(
                    "切换影视仓线路",
                    "当前订阅包含 " + a.config.depots.length() + " 个子仓库，点击切换",
                    "settings-depots",
                    () -> SourcePicker.showWarehouses(a));
        }
        toggle("启用来源扩展", Prefs.EXPERIMENTAL_CSP, true, a::closeEngines);
        link(
                "网盘与账号",
                "网盘扫码登录、凭据与网盘顺序（由配置提供）",
                "settings-accounts",
                () -> AccountPanel.show(a, null));
        link(
                "备份配置与偏好",
                "导出配置与偏好，请妥善保管；不含插件单独保存的登录状态",
                "settings-backup",
                () -> pick(45, "application/json", true));
        link(
                "还原配置与偏好",
                "从 Marco Movie 备份文件还原",
                "settings-restore",
                () -> pick(46, "application/json", false));
        link("观看历史", "查看、继续播放或删除本机观看记录", "settings-history", () -> open(a::historyPage));
        link(
                "清除搜索记录",
                "清除最近搜索，保留网盘登录",
                "settings-clear-history",
                () ->
                        confirm(
                                "清除搜索记录？",
                                () -> {
                                    a.prefs.edit().remove(Prefs.GLOBAL_SEARCH_HISTORY).apply();
                                    a.refresh();
                                }));
        link(
                "重新加载首页图片与片单",
                "清除内存与磁盘中的首页缓存",
                "settings-clear-images",
                () -> {
                    a.images.evictAll();
                    a.homeData.clear();
                    try {
                        File snapDir = new File(a.getCacheDir(), "home_snap_v1");
                        File[] files = snapDir.listFiles();
                        if (files != null) for (File f : files) f.delete();
                    } catch (Throwable ignored) {
                    }
                    a.toast("已清除首页缓存");
                });
        link("运行日志", "查看本机最近异常与诊断记录", "settings-logs", this::diagnostics);
        link(
                "恢复播放与外观默认设置",
                "保留来源配置和网盘登录",
                "settings-reset",
                () ->
                        confirm(
                                "恢复默认设置？",
                                () -> {
                                    android.content.SharedPreferences.Editor edit = a.prefs.edit();
                                    for (String key : KEYS) edit.remove(key);
                                    edit.commit();
                                    a.reduced = false;
                                    a.recreate();
                                }));
        a.paragraph(
                "Marco Movie "
                        + UpdateManager.installedVersion(a)
                        + " · 作者 Pilipala · github.com/Pilipala9919/MarcoMovie。本产品仅为工具，不含任何影视资源；内容与配置请自行合法获取。播放能力取决于服务授权和设备支持。");
    }

    void confirm(String title, Runnable action) {
        new AppDialog.Builder(a)
                .setTitle(title)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, i) -> action.run())
                .show();
    }

    void diagnostics() {
        String text = AppLog.dump();
        new AppDialog.Builder(a)
                .setTitle("运行日志")
                .setMessage(text.isEmpty() ? "当前运行正常，暂无异常或告警记录。" : text)
                .setNeutralButton(
                        "清空记录",
                        (d, i) -> {
                            AppLog.clear();
                            a.toast("已清空运行日志");
                        })
                .setPositiveButton("关闭", null)
                .show();
    }

    void toggle(String title, String key, boolean defaultValue, Runnable after) {
        boolean tv = a.isTvDevice();
        boolean initial = a.prefs.getBoolean(key, defaultValue);
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(20), a.dp(15), a.dp(20), a.dp(15));
        MobileShell.SettingIconView icon =
                new MobileShell.SettingIconView(
                        a, MobileShell.SettingIconView.kindForSetting(key, title));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);
        LinearLayout copy = a.vertical();
        copy.addView(a.text(title, 17, MainActivity.WHITE));
        final TextView caption = a.text(initial ? "开启" : "关闭", 12, MainActivity.MUTED);
        caption.setPadding(0, a.dp(4), 0, 0);
        copy.addView(caption);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        final MobileShell.IosSwitchView toggleSwitch = new MobileShell.IosSwitchView(a, initial);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(a.dp(50), a.dp(30));
        switchLp.leftMargin = a.dp(12);
        row.addView(toggleSwitch, switchLp);

        row.setTag("setting-" + key);
        row.setId(View.generateViewId());
        row.setFocusable(tv);
        row.setFocusableInTouchMode(tv);
        row.setClickable(true);
        row.setBackground(a.bg(0xff222226, 14));
        row.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                a,
                                row,
                                f,
                                0xff222226,
                                0xff37373e,
                                14,
                                MainActivity.WHITE,
                                MainActivity.WHITE,
                                true,
                                1.008f));
        if (tv) {
            a.pressFeedback(row);
        } else {
            MobileShell.bindCardTouchFeedback(a, row, a.bg(0xff222226, 14), a.bg(0xff303038, 14));
        }
        row.setOnClickListener(
                v -> {
                    final boolean current = a.prefs.getBoolean(key, defaultValue);
                    final boolean nextVal = !current;
                    Runnable applyChange =
                            () -> {
                                a.prefs.edit().putBoolean(key, nextVal).apply();
                                toggleSwitch.setChecked(nextVal, !a.reduced);
                                caption.setText(nextVal ? "开启" : "关闭");
                                if (after != null) after.run();
                                if (key.equals(Prefs.WALLPAPER_ENABLED)) {
                                    a.refresh();
                                }
                            };
                    if (key.equals(Prefs.EXPERIMENTAL_CSP) && nextVal && !current) {
                        confirm("运行配置源提供的扩展？请仅使用你信任的配置。", applyChange);
                    } else {
                        applyChange.run();
                    }
                });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = a.dp(14);
        a.body.addView(row, size);
    }

    void ints(String title, String key, String[] labels, int[] values, int fallback) {
        int current = a.prefs.getInt(key, fallback), selected = 0;
        for (int i = 0; i < values.length; i++) if (current == values[i]) selected = i;
        final int choice = selected;
        link(
                title,
                labels[selected],
                "setting-" + key,
                () -> {
                    if (!a.isTvDevice()) {
                        MobileShell.showDrawerPicker(
                                a,
                                title,
                                "在下方抽屉中选择预设选项",
                                labels,
                                choice,
                                index -> {
                                    a.prefs.edit().putInt(key, values[index]).apply();
                                    a.refresh();
                                });
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle(title)
                            .setSingleChoiceItems(
                                    labels,
                                    choice,
                                    (dialog, index) -> {
                                        a.prefs.edit().putInt(key, values[index]).apply();
                                        dialog.dismiss();
                                        a.refresh();
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                });
    }

    void pick(int request, String mime, boolean create) {
        if (a.privateMode && (request == 45 || request == 46)) {
            a.toast("请先退出隐私空间，再导出或恢复公开备份");
            return;
        }
        try {
            Intent intent =
                    new Intent(
                            create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            if (create) intent.putExtra(Intent.EXTRA_TITLE, "MarcoMovie-backup.json");
            a.startActivityForResult(intent, request);
        } catch (Exception error) {
            a.notice("电视没有文件选择器。壁纸也可以通过图片地址导入；文件导入导出需要安装文件管理器。");
        }
    }

    void wallpaperUrl() {
        AddressInput input = new AddressInput(a, "https://…/wallpaper.jpg", "");
        new AppDialog.Builder(a)
                .setTitle("壁纸图片地址")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "导入",
                        (d, i) -> {
                            String address = input.value();
                            if (!Cms.isHttp(address)) {
                                a.notice("请输入 HTTP 或 HTTPS 图片地址。");
                                return;
                            }
                            a.worker.execute(
                                    () -> {
                                        try {
                                            java.net.HttpURLConnection connection =
                                                    (java.net.HttpURLConnection)
                                                            new java.net.URL(
                                                                            Cms.normalizeHttp(
                                                                                    address))
                                                                    .openConnection();
                                            connection.setConnectTimeout(15000);
                                            connection.setReadTimeout(15000);
                                            try (InputStream stream = connection.getInputStream()) {
                                                saveWallpaper(a, stream);
                                            } finally {
                                                connection.disconnect();
                                            }
                                            a.ui.post(
                                                    () -> {
                                                        a.refresh();
                                                        a.toast("壁纸已更新");
                                                    });
                                        } catch (Exception error) {
                                            a.ui.post(() -> a.notice("图片无法读取，请检查图片地址和格式。"));
                                        }
                                    });
                        })
                .show();
    }

    static void saveWallpaper(MainActivity a, InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int length;
        while ((length = stream.read(chunk)) != -1) {
            if (bytes.size() + length > 16 * 1024 * 1024) throw new IOException("Image too large");
            bytes.write(chunk, 0, length);
        }
        byte[] data = bytes.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1920
                || options.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (image == null) throw new IOException("Invalid image");
        File temporary = new File(a.getFilesDir(), "wallpaper.tmp");
        try {
            try (OutputStream output = new FileOutputStream(temporary)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    throw new IOException("Encoding failed");
            }
            if (!temporary.renameTo(new File(a.getFilesDir(), "wallpaper.jpg")))
                throw new IOException("Unable to save wallpaper");
        } finally {
            image.recycle();
            temporary.delete();
        }
        a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
    }

    static final String[] KEYS = Prefs.KEYS;

    static void result(MainActivity a, int request, Uri uri) {
        if (a.privateMode && (request == 45 || request == 46)) return;
        a.worker.execute(
                () -> {
                    try {
                        if (request == 44) {
                            try (InputStream input = a.getContentResolver().openInputStream(uri)) {
                                saveWallpaper(a, input);
                            }
                        } else if (request == 45) {
                            org.json.JSONObject backup =
                                    new org.json.JSONObject()
                                            .put("format", "MarcoMovie-1")
                                            .put("schema", "marco-movie-backup")
                                            .put("version", 1)
                                            .put("origin", a.configOrigin)
                                            .put(
                                                    "config",
                                                    a.config == null
                                                            ? org.json.JSONObject.NULL
                                                            : new org.json.JSONObject(
                                                                    a.config.raw));
                            org.json.JSONObject settings = new org.json.JSONObject();
                            for (String key : KEYS)
                                if (a.prefs.contains(key))
                                    settings.put(key, a.prefs.getAll().get(key));
                            for (String dataKey :
                                    new String[] {
                                        Prefs.SELECTED_SITE,
                                        Prefs.WATCH_HISTORY,
                                        Prefs.FAVORITES,
                                        Prefs.GLOBAL_SEARCH_HISTORY
                                    }) {
                                if (a.prefs.contains(dataKey)) {
                                    settings.put(dataKey, a.prefs.getString(dataKey, ""));
                                }
                            }
                            backup.put("settings", settings);
                            try (OutputStream output =
                                    a.getContentResolver().openOutputStream(uri, "wt")) {
                                output.write(backup.toString(2).getBytes("UTF-8"));
                            }
                        } else if (request == 46) {
                            org.json.JSONObject backup;
                            try (InputStream input = a.getContentResolver().openInputStream(uri)) {
                                backup = new org.json.JSONObject(Cms.read(input, Cms.MAX_JSON));
                            }
                            if (!Prefs.isBackupObject(
                                    backup.optString("format", null),
                                    backup.optString("schema", null),
                                    backup.optInt("version", 0)))
                                throw new IOException("Invalid backup");
                            final String origin = backup.optString("origin", "");
                            final String configJson;
                            if (!backup.has("config") || backup.isNull("config")) {
                                configJson = null;
                            } else {
                                configJson = Prefs.extractBackupConfigJson(backup.opt("config"));
                                if (configJson == null)
                                    throw new IOException("Invalid backup config");
                            }
                            final Cms.Config config =
                                    configJson == null ? null : Cms.parseConfig(configJson, origin);
                            final org.json.JSONObject settings = backup.optJSONObject("settings");
                            if (settings != null) {
                                for (String key :
                                        new String[] {
                                            Prefs.WATCH_HISTORY,
                                            Prefs.FAVORITES,
                                            Prefs.GLOBAL_SEARCH_HISTORY
                                        }) {
                                    if (!settings.has(key)) continue;
                                    org.json.JSONArray rows =
                                            new org.json.JSONArray(
                                                    String.valueOf(settings.get(key)));
                                    for (int i = 0; i < rows.length(); i++) {
                                        if (Prefs.GLOBAL_SEARCH_HISTORY.equals(key)) {
                                            if (!(rows.get(i) instanceof String))
                                                throw new IOException("Invalid search history");
                                        } else {
                                            org.json.JSONObject item = rows.getJSONObject(i);
                                            if (item.optString("id").isEmpty()
                                                    || item.optString("site").isEmpty())
                                                throw new IOException("Invalid bookmark");
                                        }
                                    }
                                }
                            }
                            a.ui.post(
                                    () ->
                                            new AppDialog.Builder(a)
                                                    .setTitle("还原备份？")
                                                    .setMessage("将替换本机来源配置与外观、播放偏好。网盘账号保持不变。")
                                                    .setNegativeButton("取消", null)
                                                    .setPositiveButton(
                                                            "还原",
                                                            (d, i) -> {
                                                                try {
                                                                    int oldScale =
                                                                            a.prefs.getInt(
                                                                                    Prefs
                                                                                            .UI_SCALE_PERCENT,
                                                                                    100);
                                                                    restoreSettings(a, settings);
                                                                    if (config != null)
                                                                        a.saveConfig(
                                                                                config, origin);
                                                                    else a.refresh();
                                                                    if (oldScale
                                                                            != a.prefs.getInt(
                                                                                    Prefs
                                                                                            .UI_SCALE_PERCENT,
                                                                                    100))
                                                                        a.recreate();
                                                                } catch (Exception error) {
                                                                    AppLog.w(
                                                                            "Settings",
                                                                            "restoreSettings failed",
                                                                            error);
                                                                    a.notice("备份无法还原。");
                                                                }
                                                            })
                                                    .show());
                            return;
                        }
                        a.ui.post(
                                () -> {
                                    a.refresh();
                                    a.toast(request == 45 ? "备份已保存" : "壁纸已更新");
                                });
                    } catch (Exception error) {
                        AppLog.w("Settings", "file result " + request + " failed", error);
                        a.ui.post(() -> a.notice("文件无法处理。请检查文件格式与可用存储空间。"));
                    }
                });
    }

    static void restoreSettings(MainActivity a, org.json.JSONObject settings) {
        if (settings == null) return;
        android.content.SharedPreferences.Editor edit = a.prefs.edit();
        for (String key : KEYS) {
            Object clean = Prefs.sanitizeSetting(key, settings.opt(key));
            if (clean instanceof Boolean) edit.putBoolean(key, (Boolean) clean);
            else if (clean instanceof Float) edit.putFloat(key, (Float) clean);
            else if (clean instanceof Integer) edit.putInt(key, (Integer) clean);
        }
        for (String dataKey :
                new String[] {
                    Prefs.SELECTED_SITE,
                    Prefs.WATCH_HISTORY,
                    Prefs.FAVORITES,
                    Prefs.GLOBAL_SEARCH_HISTORY
                }) {
            if (settings.has(dataKey)) {
                String cleanData = Prefs.sanitizeUserData(dataKey, settings.opt(dataKey));
                if (cleanData != null) edit.putString(dataKey, cleanData);
            }
        }
        edit.apply();
        a.reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
    }
}
