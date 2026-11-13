package com.pilipala.marcomovie;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.pilipala.marcomovie.adblur.AdBlurTemplates;
import java.util.*;

/** Independent TV playback controls. Every visible action is wired to playback state. */
final class PlaybackControls {
    final MainActivity a;
    final Cms.Site site;
    final Cms.Video video;
    final Cms.Episode resolved;
    final LinearLayout dockOverlay;
    final SeekBar timeline;
    final SubtitleOverlayView subtitle;
    final VideoOutput surface;
    final PlaybackEffects effects = new PlaybackEffects();
    final DanmakuView danmaku;
    boolean danmakuLoaded;
    boolean currentMobileLandscape;
    final Map<String, Button> buttons = new LinkedHashMap<>();
    Dialog activeDialog;
    View menuAnchor;
    String lastSubtitle = "";
    boolean dialogOpen, transitioning;
    boolean touchSeeking, sliderSeeking;
    long lastSeekTimeMs;
    int lastSeekTargetMs = -1;
    int pendingResumeMs;
    int videoWidth, videoHeight;

    boolean isUserSeeking() {
        return touchSeeking || sliderSeeking;
    }

    void recordUserSeek(int targetMs) {
        lastSeekTimeMs = android.os.SystemClock.uptimeMillis();
        lastSeekTargetMs = Math.max(0, targetMs);
        timeline.setProgress(lastSeekTargetMs);
    }

    int displayPosition(int actualPositionMs) {
        if (lastSeekTargetMs >= 0
                && android.os.SystemClock.uptimeMillis() - lastSeekTimeMs < 3500
                && Math.abs(actualPositionMs - lastSeekTargetMs) > 2500) {
            return lastSeekTargetMs;
        }
        return actualPositionMs;
    }

    PlaybackControls(
            MainActivity host,
            LinearLayout overlay,
            LinearLayout progress,
            VideoOutput texture,
            Cms.Site s,
            Cms.Video v,
            Cms.Episode ep) {
        a = host;
        site = s;
        video = v;
        resolved = ep;
        dockOverlay = overlay;
        surface = texture;
        danmaku =
                new DanmakuView(
                        a,
                        new DanmakuView.Clock() {
                            public int position() {
                                return a.player == null ? 0 : a.player.getCurrentPosition();
                            }

                            public boolean playing() {
                                return a.player != null && a.player.isPlaying();
                            }
                        });
        a.playbackLayer.addView(danmaku, 1, new FrameLayout.LayoutParams(-1, -1));
        danmaku.enabled(false);
        subtitle = new SubtitleOverlayView(a);
        subtitle.setUserDefaultStyle();
        subtitle.setPadding(a.dp(40), 0, a.dp(40), a.dp(12));
        FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM);
        caption.bottomMargin = a.dp(168);
        a.playbackLayer.addView(subtitle, 1, caption);
        applySubtitleStyle();
        timeline = new SeekBar(a);
        timeline.setTag("timeline");
        timeline.setBackgroundColor(Color.TRANSPARENT);
        timeline.setFocusable(true);
        timeline.setOnFocusChangeListener(
                (focusedView, f) ->
                        FocusMotion.style(
                                a,
                                timeline,
                                f,
                                0,
                                0x16ffffff,
                                16,
                                MainActivity.WHITE,
                                MainActivity.WHITE,
                                false,
                                1));
        UiCompat.tintSeekBar(timeline, 0xfff5f5f7, Color.WHITE);
        if (!a.isTvDevice()) {
            timeline.setPadding(a.dp(8), 0, a.dp(8), 0);
        }
        timeline.setKeyProgressIncrement(15000);
        progress.addView(timeline, new LinearLayout.LayoutParams(-1, a.dp(32)));
        timeline.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {
                        sliderSeeking = true;
                        a.finishSeek(false);
                        a.scheduleHide();
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        boolean wasDragging = sliderSeeking;
                        sliderSeeking = false;
                        if (wasDragging && a.prepared && a.player != null) {
                            int target = b.getProgress();
                            recordUserSeek(target);
                            a.player.seekTo(target);
                            if (a.playClock != null && a.player.getDuration() > 0) {
                                a.playClock.setText(
                                        PlaybackController.formatTime(target)
                                                + " / "
                                                + PlaybackController.formatTime(
                                                        a.player.getDuration()));
                            }
                        }
                        a.scheduleHide();
                    }

                    public void onProgressChanged(SeekBar b, int position, boolean user) {
                        if (user && a.prepared && a.player != null) {
                            if (sliderSeeking) {
                                if (a.playClock != null && a.player.getDuration() > 0) {
                                    a.playClock.setText(
                                            PlaybackController.formatTime(position)
                                                    + " / "
                                                    + PlaybackController.formatTime(
                                                            a.player.getDuration()));
                                }
                            } else {
                                recordUserSeek(position);
                                a.player.seekTo(position);
                            }
                            a.scheduleHide();
                        }
                    }
                });
        boolean mobileLandscape = !a.isTvDevice() && !a.isPortrait();
        currentMobileLandscape = mobileLandscape;
        LinearLayout row = a.row();
        boolean live = "live".equals(site.key);
        if (a.isTvDevice()) {
            add(
                    row,
                    live ? "下台" : "下一集",
                    "next-episode",
                    () -> {
                        if (live) a.liveStep(1);
                        else if (!step(1)) showBubble("无下集", new String[] {"知道了"}, -1, i -> {}, "");
                    });
            add(
                    row,
                    live ? "频道" : "选集",
                    "episodes",
                    () -> {
                        if (live) a.chooseLiveChannel();
                        else chooseEpisodes();
                    });
            add(
                    row,
                    live ? "线路" : "换源",
                    "lines",
                    () -> {
                        if (live) a.chooseLiveStream();
                        else chooseLine();
                    });
            add(row, "倍速", "speed", this::speed);
            add(row, "音轨", "audio-tracks", () -> tracks(1));
            add(row, "解码", "decoder", this::decoderMenu);
            // 电视遥控器无法框选截图：模板只在手机上采集，经设置页同步码推送过来
            if (!a.isTvDevice()) {
                add(row, "采集", "adblur-capture-tool", this::captureAdTemplate);
            }
            add(row, "片头", "skip-intro-tool", () -> skip(true));
            add(row, "片尾", "skip-outro-tool", () -> skip(false));
            add(row, "更多", "more", this::more);
            // Keep aspect/subtitles action references in map for programmatic callers
            registerHiddenAction("aspect", this::aspect);
            registerHiddenAction("subtitles", () -> tracks(3));
        } else {
            if (!live) {
                add(
                        row,
                        "上集",
                        "prev-episode",
                        () -> {
                            if (!step(-1))
                                showBubble("已是第一集", new String[] {"知道了"}, -1, i -> {}, "");
                        });
            }
            add(
                    row,
                    live ? "下台" : "下集",
                    "next-episode",
                    () -> {
                        if (live) a.liveStep(1);
                        else if (!step(1)) showBubble("无下集", new String[] {"知道了"}, -1, i -> {}, "");
                    });
            add(
                    row,
                    live ? "频道" : "选集",
                    "episodes",
                    () -> {
                        if (live) a.chooseLiveChannel();
                        else chooseEpisodes();
                    });
            add(
                    row,
                    "换源",
                    "lines",
                    () -> {
                        if (live) a.chooseLiveStream();
                        else chooseLine();
                    });
            add(row, "1.0×", "speed", this::speed);
            add(row, "画面", "aspect", this::aspect);
            add(row, "字幕", "subtitles", () -> tracks(3));
            add(row, "全屏", "fullscreen-tool", a::togglePlayerOrientation);
            add(row, "音轨", "audio-tracks", () -> tracks(1));
            add(row, "解码", "decoder", this::decoderMenu);
            add(row, "弹幕", "danmaku-tool", this::danmaku);
            // 电视遥控器无法框选截图：模板只在手机上采集，经设置页同步码推送过来
            if (!a.isTvDevice()) {
                add(row, "采集", "adblur-capture-tool", this::captureAdTemplate);
            }
            add(row, "片头", "skip-intro-tool", () -> skip(true));
            add(row, "片尾", "skip-outro-tool", () -> skip(false));
            add(row, "循环", "repeat-tool", this::repeat);
            add(
                    row,
                    "重播",
                    "restart-tool",
                    () -> {
                        if (a.player != null && a.prepared) {
                            a.player.seekTo(0);
                            a.player.start();
                            a.playing = true;
                        } else reload(false);
                    });
            add(row, "更多", "more", this::more);
        }
        timeline.setId(UiCompat.generateViewId());
        List<Button> all = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Button) all.add((Button) child);
        }
        for (Button b : all) b.setId(UiCompat.generateViewId());
        for (Button b : all) b.setNextFocusUpId(timeline.getId());
        timeline.setNextFocusDownId(all.get(0).getId());
        all.get(0).setNextFocusLeftId(all.get(all.size() - 1).getId());
        all.get(all.size() - 1).setNextFocusRightId(all.get(0).getId());
        all.get(0)
                .setOnKeyListener(
                        (view, key, event) -> {
                            if (key != KeyEvent.KEYCODE_DPAD_LEFT) return false;
                            if (event.getAction() == KeyEvent.ACTION_DOWN)
                                all.get(all.size() - 1).requestFocus();
                            return true;
                        });
        all.get(all.size() - 1)
                .setOnKeyListener(
                        (view, key, event) -> {
                            if (key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                            if (event.getAction() == KeyEvent.ACTION_DOWN)
                                all.get(0).requestFocus();
                            return true;
                        });
        rebuildToolDock(mobileLandscape);
        updateLabels();
    }

    void relayout(boolean mobileLandscape) {
        if (mobileLandscape != currentMobileLandscape || dockOverlay.getChildCount() == 0) {
            currentMobileLandscape = mobileLandscape;
            rebuildToolDock(mobileLandscape);
        }
        Button fsTool = buttons.get("fullscreen-tool");
        if (fsTool != null) {
            fsTool.setText(mobileLandscape ? "竖屏" : "全屏");
        }
    }

    private void rebuildToolDock(boolean mobileLandscape) {
        dockOverlay.removeAllViews();
        boolean tv = a.isTvDevice();
        Button fsTool = buttons.get("fullscreen-tool");
        if (fsTool != null) {
            fsTool.setText(mobileLandscape ? "竖屏" : "全屏");
        }
        if (!tv) {
            LinearLayout col = a.vertical();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setClipChildren(false);
            col.setClipToPadding(false);
            col.setPadding(a.dp(1), a.dp(2), a.dp(1), a.dp(2));
            for (Button b : buttons.values()) {
                if (b.getVisibility() == View.GONE) continue;
                if (b.getParent() instanceof ViewGroup) {
                    ((ViewGroup) b.getParent()).removeView(b);
                }
                b.setSingleLine(true);
                b.setTextSize(11f);
                b.setMinHeight(0);
                b.setMinimumHeight(0);
                int padH = a.dp(2);
                b.setPadding(padH, 0, padH, 0);
                int w = a.dp(mobileLandscape ? 40 : 44);
                int h = a.dp(mobileLandscape ? 30 : 33);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(w, h);
                size.setMargins(a.dp(1), a.dp(2), a.dp(1), a.dp(2));
                col.addView(b, size);
            }
            ScrollView vScroller =
                    new ScrollView(a) {
                        @Override
                        public boolean onInterceptTouchEvent(MotionEvent ev) {
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            return super.onInterceptTouchEvent(ev);
                        }

                        @Override
                        public boolean onTouchEvent(MotionEvent ev) {
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            return super.onTouchEvent(ev);
                        }
                    };
            vScroller.setVerticalScrollBarEnabled(false);
            vScroller.setFillViewport(true);
            vScroller.setClipChildren(true);
            vScroller.setClipToPadding(true);
            vScroller.addView(col, new ScrollView.LayoutParams(-1, -2));
            dockOverlay.addView(vScroller, new LinearLayout.LayoutParams(-1, -1));
            return;
        }
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(a.dp(1), a.dp(1), a.dp(1), a.dp(1));
        for (Button b : buttons.values()) {
            if (b.getVisibility() == View.GONE) continue;
            if (b.getParent() instanceof ViewGroup) {
                ((ViewGroup) b.getParent()).removeView(b);
            }
            b.setSingleLine(true);
            int padH = a.dp(4);
            b.setPadding(padH, 0, padH, 0);
            int w = a.dp(58);
            int h = a.dp(42);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(w, h);
            size.setMargins(a.dp(3), a.dp(4), a.dp(3), a.dp(4));
            row.addView(b, size);
        }
        HorizontalScrollView scroller =
                new HorizontalScrollView(a) {
                    @Override
                    protected void dispatchDraw(Canvas canvas) {
                        int save = canvas.save();
                        canvas.clipRect(getScrollX(), 0, getScrollX() + getWidth(), getHeight());
                        super.dispatchDraw(canvas);
                        canvas.restoreToCount(save);
                    }
                };
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        scroller.setClipChildren(true);
        scroller.setClipToPadding(true);
        scroller.addView(row);
        dockOverlay.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
    }

    void registerHiddenAction(String key, Runnable action) {
        Button b =
                a.button(
                        key,
                        key,
                        () -> {
                            menuAnchor = buttons.get("more");
                            action.run();
                        });
        b.setVisibility(View.GONE);
        buttons.put(key, b);
    }

    Button add(LinearLayout row, String title, String key, Runnable action) {
        Button b =
                a.button(
                        title,
                        key,
                        () -> {
                            menuAnchor = buttons.get(key);
                            action.run();
                        });
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setTextSize(a.isTvDevice() ? 12.5f : 13f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(a.dp(42));
        b.setMinimumHeight(a.dp(42));
        b.setBackground(a.bg(0x16ffffff, 19));
        int padH = a.dp(a.isTvDevice() ? 4 : 6);
        b.setPadding(padH, 0, padH, 0);
        b.setOnFocusChangeListener(
                (view, focused) -> {
                    FocusMotion.style(
                            a,
                            b,
                            focused,
                            0x16ffffff,
                            MainActivity.WHITE,
                            19,
                            MainActivity.WHITE,
                            MainActivity.DARK,
                            false,
                            1.025f);
                    b.setPadding(padH, 0, padH, 0);
                });
        int btnWidth = a.dp(a.isTvDevice() ? 58 : 68);
        int marginH = a.dp(a.isTvDevice() ? 3 : 4);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(btnWidth, a.dp(42));
        size.setMargins(marginH, a.dp(4), marginH, a.dp(4));
        row.addView(b, size);
        buttons.put(key, b);
        return b;
    }

    int lineIndex() {
        return a.activeLine == null ? -1 : video.lines.indexOf(a.activeLine);
    }

    int episodeIndex() {
        return a.activeLine == null ? -1 : a.activeLine.episodes.indexOf(a.activeEpisode);
    }

    void choose(String title, String[] labels, int selected, PlayerBubble.IntAction action) {
        showBubble(title, labels, selected, action, "");
    }

    void message(String text) {
        showBubble("播放提示", new String[] {"完成"}, -1, i -> {}, text);
    }

    void showBubble(
            String title,
            String[] labels,
            int selected,
            PlayerBubble.IntAction action,
            String message) {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        if (menuAnchor == null || !UiCompat.isAttached(menuAnchor))
            menuAnchor = buttons.get("more");
        dialogOpen = true;
        if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
        final Dialog[] holder = new Dialog[1];
        holder[0] =
                PlayerBubble.show(
                        a,
                        menuAnchor,
                        title,
                        labels,
                        selected,
                        action,
                        () -> {
                            if (activeDialog == holder[0]) {
                                activeDialog = null;
                                dialogOpen = false;
                                a.scheduleHide();
                            }
                        },
                        message);
        activeDialog = holder[0];
    }

    static final int EPISODE_GROUP_SIZE = 50;

    void chooseEpisodes() {
        int l = lineIndex();
        if (l < 0) {
            message("此内容没有可切换的剧集。");
            return;
        }
        Cms.Line line = video.lines.get(l);
        int total = line.episodes.size();
        int current = episodeIndex();
        if (total <= EPISODE_GROUP_SIZE) {
            String[] labels = new String[total];
            for (int i = 0; i < total; i++) labels[i] = line.episodes.get(i).name;
            choose(
                    line.name + " · 选集",
                    labels,
                    current,
                    i -> a.changeEpisode(site, video, line, line.episodes.get(i), 0));
            return;
        }
        int page = EpisodeSelection.pageFor(Math.max(0, current), total, false);
        chooseEpisodePage(line, page);
    }

    void chooseEpisodePage(Cms.Line line, int page) {
        int total = line.episodes.size();
        int groups = Math.max(1, (total + EPISODE_GROUP_SIZE - 1) / EPISODE_GROUP_SIZE);
        int safePage = Math.max(0, Math.min(page, groups - 1));
        int start = safePage * EPISODE_GROUP_SIZE;
        int end = Math.min(total, start + EPISODE_GROUP_SIZE);
        int count = end - start;
        String[] labels = new String[count + 1];
        labels[0] = "切换选集分组（当前 " + (start + 1) + "-" + end + " / 共 " + total + " 集）";
        int current = episodeIndex();
        int selected = 0;
        for (int i = 0; i < count; i++) {
            int epIndex = start + i;
            labels[i + 1] = line.episodes.get(epIndex).name;
            if (epIndex == current) selected = i + 1;
        }
        choose(
                line.name + " · 选集 (" + (start + 1) + "-" + end + ")",
                labels,
                selected,
                i -> {
                    if (i == 0) {
                        chooseEpisodeGroupList(line, safePage);
                    } else {
                        int target = start + (i - 1);
                        if (target >= 0 && target < total) {
                            a.changeEpisode(site, video, line, line.episodes.get(target), 0);
                        }
                    }
                });
    }

    void chooseEpisodeGroupList(Cms.Line line, int currentGroup) {
        int total = line.episodes.size();
        int groups = Math.max(1, (total + EPISODE_GROUP_SIZE - 1) / EPISODE_GROUP_SIZE);
        String[] groupLabels = new String[groups];
        for (int g = 0; g < groups; g++) {
            int gStart = g * EPISODE_GROUP_SIZE + 1;
            int gEnd = Math.min(total, (g + 1) * EPISODE_GROUP_SIZE);
            groupLabels[g] = "第 " + gStart + " - " + gEnd + " 集";
        }
        choose(line.name + " · 选择分组", groupLabels, currentGroup, g -> chooseEpisodePage(line, g));
    }

    void chooseLine() {
        org.json.JSONArray qualities =
                resolved != null && resolved.media != null
                        ? resolved.media.optJSONArray("qualities")
                        : null;
        int qCount = qualities != null && qualities.length() > 1 ? qualities.length() : 0;
        boolean canCrossSearch =
                video != null
                        && video.title != null
                        && !video.title.trim().isEmpty()
                        && !"live".equals(site.key);
        if (video.lines.isEmpty() && qCount == 0 && !canCrossSearch) {
            message("此内容只有当前地址。");
            return;
        }
        List<String> items = new ArrayList<>();
        int currentQualityIdx =
                resolved != null && resolved.media != null
                        ? resolved.media.optInt("_qualityIdx", 0)
                        : 0;
        for (int q = 0; q < qCount; q++) {
            org.json.JSONObject qObj = qualities.optJSONObject(q);
            String qName = qObj != null ? qObj.optString("name", "画质 " + (q + 1)) : "画质 " + (q + 1);
            items.add("画质 · " + qName + (q == currentQualityIdx ? "（当前）" : ""));
        }
        for (int i = 0; i < video.lines.size(); i++) {
            items.add((qCount > 0 ? "线路 · " : "") + video.lines.get(i).name);
        }
        final int crossSearchIndex = canCrossSearch ? items.size() : -1;
        if (canCrossSearch) {
            items.add("搜索同名其他片源 · " + video.title);
        }
        int selected = qCount > 0 ? currentQualityIdx : lineIndex();
        final int finalQCount = qCount;
        final org.json.JSONArray finalQualities = qualities;
        choose(
                qCount > 0 ? "选择画质或播放线路" : "换源 · 选择线路或片源",
                items.toArray(new String[0]),
                selected,
                i -> {
                    if (crossSearchIndex >= 0 && i == crossSearchIndex) {
                        a.leavePlayback();
                        a.crossSearch(Cms.searchTitle(video));
                        return;
                    }
                    if (i < finalQCount && finalQualities != null) {
                        org.json.JSONObject qObj = finalQualities.optJSONObject(i);
                        if (qObj != null) {
                            String nextUrl = qObj.optString("url", "");
                            if (!nextUrl.isEmpty()) {
                                try {
                                    resolved.media.put("_qualityIdx", i);
                                } catch (Exception ignored) {
                                }
                                resolved.url = LocalProxy.rewrite(nextUrl);
                                a.resumeAt =
                                        a.prepared && a.player != null
                                                ? a.player.getCurrentPosition()
                                                : 0;
                                a.playerPage(site, video, resolved);
                            }
                        }
                        return;
                    }
                    int lineIdx = i - finalQCount;
                    if (lineIdx >= 0 && lineIdx < video.lines.size()) {
                        Cms.Line line = video.lines.get(lineIdx);
                        if (!line.episodes.isEmpty()) {
                            int n = Math.min(Math.max(0, episodeIndex()), line.episodes.size() - 1);
                            a.changeEpisode(site, video, line, line.episodes.get(n), 0);
                        }
                    }
                });
    }

    boolean step(int direction) {
        int i = episodeIndex(), l = lineIndex();
        if (l < 0 || i < 0) return false;
        Cms.Line line = video.lines.get(l);
        int next = i + direction;
        if (a.prefs.getInt(Prefs.REPEAT_MODE, 0) == 2)
            next = (next + line.episodes.size()) % line.episodes.size();
        if (next < 0 || next >= line.episodes.size()) return false;
        transitioning = true;
        a.changeEpisode(site, video, line, line.episodes.get(next), 0);
        return true;
    }

    void reload(boolean preserve) {
        int position =
                preserve && a.prepared && a.player != null ? a.player.getCurrentPosition() : 0;
        if (a.activeLine != null && a.activeEpisode != null)
            a.changeEpisode(site, video, a.activeLine, a.activeEpisode, position);
        else {
            a.resumeAt = position;
            a.playerPage(site, video, resolved);
        }
    }

    void speed() {
        if (a.player == null) return;
        float[] speeds = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = {"0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "1.75×", "2.0×"};
        int current = 2;
        for (int i = 0; i < speeds.length; i++) if (speeds[i] == a.player.getSpeed()) current = i;
        choose(
                "播放速度",
                labels,
                current,
                i -> {
                    if (a.player != null) a.player.setSpeed(speeds[i]);
                    a.prefs.edit().putFloat(Prefs.PLAY_SPEED, speeds[i]).apply();
                    updateLabels();
                });
    }

    void aspect() {
        String[] labels = {"适应画面", "铺满屏幕", "16:9", "4:3", "裁切填充"};
        choose(
                "画面比例",
                labels,
                a.prefs.getInt(Prefs.ASPECT, 0),
                i -> {
                    a.prefs.edit().putInt(Prefs.ASPECT, i).apply();
                    resize(videoWidth, videoHeight);
                    updateLabels();
                });
    }

    void engine() {
        int cur = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
        int selected = cur == 2 ? 1 : (cur == 1 ? 2 : 0);
        final int[] vals = {0, 2, 1};
        choose(
                "选择播放器内核",
                new String[] {
                    "Exo 播放器 (Media3 · 默认)", "IJK 播放器 (FFmpeg · 兼容)", "系统播放器 (Android Native)"
                },
                selected,
                i -> {
                    if (i >= 0 && i < vals.length) {
                        a.prefs.edit().putInt(Prefs.PLAYER_ENGINE, vals[i]).apply();
                        updateLabels();
                        reload(true);
                    }
                });
    }

    void output() {
        choose(
                "画面输出",
                new String[] {"保真输出 · HDR 与流畅度优先", "兼容输出 · 支持应用内调色"},
                a.prefs.getInt(Prefs.VIDEO_OUTPUT, 0),
                i -> {
                    a.prefs.edit().putInt(Prefs.VIDEO_OUTPUT, i).apply();
                    reload(true);
                });
    }

    void decoder() {
        choose(
                "选择解码方式",
                new String[] {"自动选择（默认）", "设备硬件解码", "软件解码优先（高分辨率可能较慢）"},
                a.prefs.getInt(Prefs.DECODER_MODE, 0),
                i -> {
                    a.prefs.edit().putInt(Prefs.DECODER_MODE, i).apply();
                    updateLabels();
                    reload(true);
                });
    }

    void decoderMenu() {
        int decMode = a.prefs.getInt(Prefs.DECODER_MODE, 0);
        int engMode = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
        int outMode = a.prefs.getInt(Prefs.VIDEO_OUTPUT, 0);
        String engLabel = engMode == 2 ? "IJK" : (engMode == 1 ? "系统" : "Exo");
        String[] items = {
            (engMode == 0 ? "✓ " : "") + "播放器内核 · Exo (Media3)",
            (engMode == 2 ? "✓ " : "") + "播放器内核 · IJK (FFmpeg)",
            (engMode == 1 ? "✓ " : "") + "播放器内核 · 系统播放器",
            (decMode == 0 ? "✓ " : "") + "解码方式 · 自动解码（默认）",
            (decMode == 1 ? "✓ " : "") + "解码方式 · 设备硬件解码 (HW)",
            (decMode == 2 ? "✓ " : "") + "解码方式 · 软件解码优先 (SW)",
            "画面输出 · " + (outMode == 0 ? "保真输出 (HDR)" : "兼容输出 (调色)")
        };
        int curIdx = engMode == 2 ? 1 : (engMode == 1 ? 2 : 0);
        choose(
                "播放器与解码 (" + engLabel + ")",
                items,
                curIdx,
                i -> {
                    if (i == 0 || i == 1 || i == 2) {
                        int nextEng = i == 1 ? 2 : (i == 2 ? 1 : 0);
                        a.prefs.edit().putInt(Prefs.PLAYER_ENGINE, nextEng).apply();
                        updateLabels();
                        reload(true);
                    } else if (i >= 3 && i <= 5) {
                        a.prefs.edit().putInt(Prefs.DECODER_MODE, i - 3).apply();
                        updateLabels();
                        reload(true);
                    } else if (i == 6) {
                        output();
                    }
                });
    }

    /**
     * 采集当前画面为「牛皮癣模糊」特征图。
     *
     * <p>交互直接基于横屏模式：暂停播放 → 抓取当前显示帧（VideoOutput.snapshotFullFrame，
     * 不经 GL 特效管线，无 HDR / 数据空间问题，且无模板时也可用）→ 竖直翻转成 GL 朝向 →
     * 进入框选编辑页（横屏）。
     */
    void captureAdTemplate() {
        if (a.isTvDevice()) {
            showBubble(
                    "电视端请在手机上框选模板，再用「设置 → 视频牛皮癣模糊 → 电视同步码」扫码接收",
                    new String[] {"知道了"},
                    -1,
                    i -> {},
                    "");
            return;
        }
        if (a.player == null || !a.prepared) {
            showBubble("请先播放视频", new String[] {"知道了"}, -1, i -> {}, "");
            return;
        }
        if (AdBlurTemplates.count(a) >= 8) {
            showBubble("特征图已达上限（8 个）", new String[] {"知道了"}, -1, i -> {}, "");
            return;
        }
        if (surface == null) {
            showBubble("画面尚未就绪", new String[] {"知道了"}, -1, i -> {}, "");
            return;
        }
        // 暂停播放：框选与截帧都基于静止画面
        if (a.player != null) {
            a.player.pause();
            a.playing = false;
        }
        surface.snapshotFullFrame(
                bmp -> {
                    if (bmp != null) {
                        openCaptureEditor(bmp);
                        return;
                    }
                    // 回退：软渲染 / 特殊合成下 PixelCopy 取不到显示帧时，从媒体源按当前位置取帧
                    captureFromSource();
                });
    }

    /** 进入框选编辑：显示朝向 → GL 朝向（模板与逐帧检测同坐标系；左右不变，上下镜像）。 */
    private void openCaptureEditor(Bitmap displayFrame) {
        Bitmap gl = flipVertical(displayFrame);
        displayFrame.recycle();
        TemplateCaptureActivity.pending = gl;
        a.startActivity(new Intent(a, TemplateCaptureActivity.class));
    }

    /**
     * 回退抓帧：{@code MediaMetadataRetriever} 直接对媒体源按当前播放位置解码出帧，
     * 不依赖 GPU 合成（模拟器软渲染、特殊 Surface 合成场景也能用）。
     */
    private void captureFromSource() {
        final String url =
                resolved == null
                        ? ""
                        : resolved.media
                                .optString("url", resolved.url == null ? "" : resolved.url)
                                .trim();
        if (url.isEmpty()) {
            showBubble("画面截取失败，请重试", new String[] {"知道了"}, -1, i -> {}, "");
            return;
        }
        final long posMs = a.player == null ? 0 : Math.max(0, a.player.getCurrentPosition());
        final java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        if (resolved != null && resolved.headers != null) headers.putAll(resolved.headers);
        a.worker.execute(
                () -> {
                    Bitmap frame = null;
                    android.media.MediaMetadataRetriever retriever =
                            new android.media.MediaMetadataRetriever();
                    try {
                        if (headers.isEmpty()) retriever.setDataSource(url);
                        else retriever.setDataSource(url, headers);
                        frame =
                                retriever.getFrameAtTime(
                                        posMs * 1000L,
                                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } catch (Throwable error) {
                        AppLog.w("AdBlurCapture", "retriever frame failed url=" + url, error);
                        frame = null;
                    } finally {
                        try {
                            retriever.release();
                        } catch (Throwable ignore) {
                        }
                    }
                    final Bitmap grabbed = frame;
                    a.ui.post(
                            () -> {
                                if (grabbed == null) {
                                    showBubble("画面截取失败，请重试", new String[] {"知道了"}, -1, i -> {}, "");
                                    return;
                                }
                                openCaptureEditor(grabbed);
                            });
                });
    }

    private static Bitmap flipVertical(Bitmap src) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(1f, -1f, src.getWidth() / 2f, src.getHeight() / 2f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    void danmaku() {
        choose(
                "弹幕",
                new String[] {"开启 / 重新加载来源弹幕", "关闭弹幕", "导入本地弹幕 XML / JSON"},
                -1,
                i -> {
                    if (i == 1) {
                        danmaku.enabled(false);
                        return;
                    }
                    if (i == 2) {
                        a.pickDanmaku();
                        return;
                    }
                    String address = resolved.media.optString("danmaku");
                    if (address.isEmpty()) {
                        String recentDm = CspEngine.consumeRecentPluginDanmaku(120000L);
                        if (!recentDm.isEmpty()) {
                            address = LocalProxy.rewrite(recentDm);
                            try {
                                resolved.media.put("danmaku", address);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (address.isEmpty()) {
                        message("当前来源没有提供弹幕，可导入本地 XML / JSON。");
                        return;
                    }
                    danmaku.enabled(true);
                    danmaku.load(
                            address,
                            resolved.headers,
                            new DanmakuView.Listener() {
                                public void loaded(int count) {
                                    a.toast("已加载 " + count + " 条弹幕");
                                }

                                public void error(String message) {
                                    message(message);
                                }
                            });
                });
    }

    void soundEffect() {
        choose(
                "音效",
                new String[] {"原声", "影院", "人声清晰", "柔和低音"},
                a.prefs.getInt(Prefs.AUDIO_EFFECT, 0),
                i -> {
                    try {
                        if (a.player == null) throw new IllegalStateException();
                        effects.audio(a.player.getAudioSessionId(), i);
                        a.prefs.edit().putInt(Prefs.AUDIO_EFFECT, i).apply();
                    } catch (Exception error) {
                        message("这台设备当前音轨未提供可用音效接口。");
                    }
                });
    }

    void pictureEffect() {
        choose(
                "画面色彩",
                new String[] {"原始画面", "明亮", "影院", "柔和"},
                a.prefs.getInt(Prefs.PICTURE_EFFECT, 0),
                i -> {
                    if (i > 0 && !surface.supportsPicture()) {
                        message("当前使用保真输出，保留原始色彩。需要应用内调色时，可在“播放器与解码”选择兼容输出；HDR 内容建议保持保真输出。");
                        return;
                    }
                    PlaybackEffects.picture(surface, i);
                    a.prefs.edit().putInt(Prefs.PICTURE_EFFECT, i).apply();
                });
    }

    void close() {
        surface.setFrameAdvancing(false);
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        effects.close();
        danmaku.close();
    }

    static final int[] SUBTITLE_SIZES = {28, 35, 45, 55, 65};
    static final String[] SUBTITLE_SIZE_LABELS = {"极小", "小", "标准", "大", "超大"};
    static final int[] SUBTITLE_BOTTOMS = {8, 24, 48, 76, 110, 148};
    static final String[] SUBTITLE_BOTTOM_LABELS = {"贴底", "标准", "略高", "中下", "偏高", "较高"};

    void applySubtitleStyle() {
        int size = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
        int bottomDp = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
        int stylePreset = Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
        int weight = Prefs.clampSubtitleWeight(a.prefs.getInt(Prefs.SUBTITLE_WEIGHT, 50));
        int offsetX = Prefs.clampSubtitleOffsetX(a.prefs.getInt(Prefs.SUBTITLE_OFFSET_X, 0));
        subtitle.applyStylePreset(stylePreset, weight);
        subtitle.setSubtitleSizeValue(size);
        subtitle.setSubtitleBottomDp(bottomDp);
        subtitle.setHorizontalOffsetPercent(offsetX);
        visible(a.controlsVisible);
    }

    String subtitleSizeLabel() {
        int size = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
        for (int i = 0; i < SUBTITLE_SIZES.length; i++) {
            if (SUBTITLE_SIZES[i] == size) return SUBTITLE_SIZE_LABELS[i];
        }
        return size + "";
    }

    String subtitleBottomLabel() {
        int bottomDp = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
        for (int i = 0; i < SUBTITLE_BOTTOMS.length; i++) {
            if (SUBTITLE_BOTTOMS[i] == bottomDp) return SUBTITLE_BOTTOM_LABELS[i];
        }
        return bottomDp + "dp";
    }

    String subtitleStyleLabel() {
        int style = Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
        return SubtitleOverlayView.STYLE_LABELS[style];
    }

    void subtitleSizeDialog() {
        showSubtitleAdjuster(a, subtitle, this::applySubtitleStyle);
    }

    void subtitleBottomDialog() {
        showSubtitleAdjuster(a, subtitle, this::applySubtitleStyle);
    }

    static void showSubtitleStyleDrawer(
            MainActivity a, int selectedIndex, PlayerBubble.IntAction onSelect) {
        if (!a.isTvDevice()) {
            MobileShell.showDrawerPicker(
                    a,
                    "选择字幕颜色与样式",
                    "选择后实时套用至全屏字幕预览与后续播放",
                    SubtitleOverlayView.STYLE_LABELS,
                    selectedIndex,
                    onSelect);
            return;
        }
        final Dialog drawer = new Dialog(a);
        drawer.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (drawer.getWindow() != null) {
            drawer.getWindow()
                    .setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            drawer.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            drawer.getWindow()
                    .setLayout(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT);
        }
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0x55000000);
        root.setOnClickListener(v -> drawer.dismiss());

        LinearLayout sheet = a.vertical();
        sheet.setPadding(a.dp(22), a.dp(24), a.dp(22), a.dp(24));
        android.graphics.drawable.GradientDrawable bg = a.bg(0xf2151d1a, 24);
        bg.setStroke(a.dp(1), 0x38ffffff);
        sheet.setBackground(bg);
        sheet.setClickable(true);

        TextView head = a.text("字幕颜色与样式（抽屉菜单）", 18, MainActivity.WHITE);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        sheet.addView(head);
        a.gap(sheet, 4);
        sheet.addView(a.text("上下移动实时预览，按确认键完成选择", 12, MainActivity.MUTED));
        a.gap(sheet, 14);

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = a.vertical();
        Button initialFocus = null;
        for (int i = 0; i < SubtitleOverlayView.STYLE_LABELS.length; i++) {
            final int idx = i;
            String label =
                    (i == selectedIndex ? "✓  " : "    ") + SubtitleOverlayView.STYLE_LABELS[i];
            Button item =
                    a.button(
                            label,
                            "sub-style-" + i,
                            () -> {
                                onSelect.accept(idx);
                                drawer.dismiss();
                            });
            item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            item.setTextSize(14);
            item.setMinHeight(a.dp(44));
            item.setMinimumHeight(a.dp(44));
            item.setTextColor(SubtitleOverlayView.STYLE_COLORS[i]);
            item.setOnFocusChangeListener(
                    (v, focused) -> {
                        FocusMotion.style(
                                a,
                                item,
                                focused,
                                0xff202a26,
                                MainActivity.WHITE,
                                14,
                                SubtitleOverlayView.STYLE_COLORS[idx],
                                MainActivity.DARK,
                                false,
                                1.02f);
                        if (focused) {
                            onSelect.accept(idx);
                        }
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(44));
            lp.bottomMargin = a.dp(8);
            list.addView(item, lp);
            if (i == selectedIndex) initialFocus = item;
        }
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout.LayoutParams sheetLp =
                new FrameLayout.LayoutParams(
                        a.dp(360),
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        sheetLp.setMargins(0, a.dp(20), a.dp(20), a.dp(20));
        root.addView(sheet, sheetLp);
        drawer.setContentView(root, new ViewGroup.LayoutParams(a.windowWidth(), a.windowHeight()));
        drawer.show();
        if (drawer.getWindow() != null) {
            drawer.getWindow()
                    .setLayout(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT);
        }
        if (initialFocus != null) initialFocus.requestFocus();
    }

    static void showSubtitleAdjuster(
            MainActivity a, SubtitleOverlayView liveSub, Runnable onChanged) {
        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow()
                    .setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dlg.getWindow()
                    .setLayout(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT);
        }
        if (liveSub != null) {
            liveSub.setVisibility(View.INVISIBLE);
            if (a.controlsVisible) a.showControls(false);
        }

        FrameLayout fullRoot = new FrameLayout(a);
        if (liveSub == null) {
            fullRoot.setBackgroundColor(0xee080e0c);
        } else {
            fullRoot.setBackgroundColor(0x28000000);
        }

        final SubtitleOverlayView previewCanvas = new SubtitleOverlayView(a);
        previewCanvas.setPreviewActive(true);
        previewCanvas.setShowAxisGuides(true);
        fullRoot.addView(previewCanvas, new FrameLayout.LayoutParams(-1, -1));

        final SeekBar[] sizeBarRef = new SeekBar[1];
        final SeekBar[] weightBarRef = new SeekBar[1];
        final TextView[] sizeValRef = new TextView[1];
        final TextView[] weightValRef = new TextView[1];
        final TextView[] posValRef = new TextView[1];
        final TextView[] styleBtnTextRef = new TextView[1];
        final View[] initialTvFocusRef = new View[1];
        final boolean[] internalSync = {false};
        final int savedScrollY = a.scroll != null ? a.scroll.getScrollY() : 0;
        final String savedFocusTag =
                a.getCurrentFocus() != null && a.getCurrentFocus().getTag() instanceof String
                        ? (String) a.getCurrentFocus().getTag()
                        : (a.current != null ? a.current.focus : null);
        final int savedDescendantFocus =
                a.scroll != null
                        ? a.scroll.getDescendantFocusability()
                        : ViewGroup.FOCUS_AFTER_DESCENDANTS;
        if (a.scroll != null && liveSub == null) {
            a.scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }

        final Runnable syncState =
                () -> {
                    int sz = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
                    int bt = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
                    int st = Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
                    int wt = Prefs.clampSubtitleWeight(a.prefs.getInt(Prefs.SUBTITLE_WEIGHT, 50));
                    int ox = Prefs.clampSubtitleOffsetX(a.prefs.getInt(Prefs.SUBTITLE_OFFSET_X, 0));

                    previewCanvas.applyStylePreset(st, wt);
                    previewCanvas.setSubtitleSizeValue(sz);
                    previewCanvas.setSubtitleBottomDp(bt);
                    previewCanvas.setHorizontalOffsetPercent(ox);

                    internalSync[0] = true;
                    if (sizeBarRef[0] != null) sizeBarRef[0].setProgress(sz - 25);
                    if (weightBarRef[0] != null) weightBarRef[0].setProgress(wt);
                    internalSync[0] = false;

                    if (sizeValRef[0] != null) sizeValRef[0].setText(sz + "");
                    if (weightValRef[0] != null) weightValRef[0].setText(wt + "");
                    if (posValRef[0] != null) {
                        posValRef[0].setText(
                                "高度 "
                                        + bt
                                        + "dp · "
                                        + (Math.abs(ox) <= 2
                                                ? "中轴线居中 ✓"
                                                : ("水平 " + (ox > 0 ? "+" : "") + ox)));
                    }
                    if (styleBtnTextRef[0] != null) {
                        styleBtnTextRef[0].setText(
                                "字幕颜色与样式  ·  " + SubtitleOverlayView.STYLE_LABELS[st] + "  ▸");
                        styleBtnTextRef[0].setTextColor(SubtitleOverlayView.STYLE_COLORS[st]);
                    }
                    if (liveSub != null && onChanged != null) onChanged.run();
                };

        final Runnable resetDefaults =
                () -> {
                    a.prefs
                            .edit()
                            .putInt(Prefs.SUBTITLE_SIZE, 45)
                            .putInt(Prefs.SUBTITLE_BOTTOM, 24)
                            .putInt(Prefs.SUBTITLE_WEIGHT, 50)
                            .putInt(Prefs.SUBTITLE_OFFSET_X, 0)
                            .putInt(Prefs.SUBTITLE_STYLE, 0)
                            .apply();
                    syncState.run();
                };

        if (!a.isTvDevice()) {
            // =================================================================
            // MOBILE FULL-SCREEN INTERACTIVE SUBTITLE DEBUGGER
            // - Two-finger pinch-to-zoom for font size (25..65)
            // - One-finger press & drag for 2D position with center-axis snap
            // - Center-axis guide lines (中轴线指引)
            // - Dedicated sliders & drawer picker for color/style
            // =================================================================
            final float[] pinchSize = {
                Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45))
            };
            final boolean[] isScaling = {false};
            final float[] downXY = new float[2];
            final int[] startPos = new int[2];

            final ScaleGestureDetector scaleDetector =
                    new ScaleGestureDetector(
                            a,
                            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                @Override
                                public boolean onScaleBegin(ScaleGestureDetector detector) {
                                    isScaling[0] = true;
                                    pinchSize[0] =
                                            Prefs.clampSubtitleSize(
                                                    a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
                                    return true;
                                }

                                @Override
                                public boolean onScale(ScaleGestureDetector detector) {
                                    pinchSize[0] =
                                            Math.max(
                                                    25f,
                                                    Math.min(
                                                            65f,
                                                            pinchSize[0]
                                                                    * detector.getScaleFactor()));
                                    int nextSz = Prefs.clampSubtitleSize(Math.round(pinchSize[0]));
                                    a.prefs.edit().putInt(Prefs.SUBTITLE_SIZE, nextSz).apply();
                                    syncState.run();
                                    return true;
                                }

                                @Override
                                public void onScaleEnd(ScaleGestureDetector detector) {
                                    isScaling[0] = false;
                                }
                            });

            previewCanvas.setOnTouchListener(
                    (v, event) -> {
                        scaleDetector.onTouchEvent(event);
                        int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN) {
                            downXY[0] = event.getRawX();
                            downXY[1] = event.getRawY();
                            startPos[0] =
                                    Prefs.clampSubtitleOffsetX(
                                            a.prefs.getInt(Prefs.SUBTITLE_OFFSET_X, 0));
                            startPos[1] =
                                    Prefs.clampSubtitleBottom(
                                            a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
                            previewCanvas.setShowAxisGuides(true);
                            return true;
                        }
                        if (action == MotionEvent.ACTION_POINTER_DOWN) {
                            isScaling[0] = true;
                            return true;
                        }
                        if (action == MotionEvent.ACTION_MOVE) {
                            if (isScaling[0] || event.getPointerCount() > 1) return true;
                            float dx = event.getRawX() - downXY[0];
                            float dy = event.getRawY() - downXY[1];
                            float maxShiftX = Math.max(a.dp(80), previewCanvas.getWidth() * 0.30f);
                            int nextOx =
                                    Prefs.clampSubtitleOffsetX(
                                            startPos[0] + Math.round((dx / maxShiftX) * 100f));
                            if (Math.abs(nextOx) <= 4) {
                                nextOx = 0; // Magnetic snap to vertical center axis (中轴线吸附)
                            }
                            int nextBt =
                                    Prefs.clampSubtitleBottom(
                                            startPos[1]
                                                    - Math.round(dy / Math.max(1f, a.dp(2.2f))));
                            if (Math.abs(nextBt - 24) <= 2) {
                                nextBt = 24;
                            }
                            a.prefs
                                    .edit()
                                    .putInt(Prefs.SUBTITLE_OFFSET_X, nextOx)
                                    .putInt(Prefs.SUBTITLE_BOTTOM, nextBt)
                                    .apply();
                            syncState.run();
                            return true;
                        }
                        return true;
                    });

            // Top floating HUD bar
            LinearLayout topBar = a.row();
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            android.graphics.drawable.GradientDrawable topBg = a.bg(0xdd141c19, 20);
            topBg.setStroke(a.dp(1), 0x33ffffff);
            topBar.setBackground(topBg);

            LinearLayout titleCol = a.vertical();
            TextView t1 = a.text("全屏字幕触控调试", 14.5f, MainActivity.WHITE);
            t1.setTypeface(null, android.graphics.Typeface.BOLD);
            titleCol.addView(t1);
            TextView t2 = a.text("单指按住拖拽位置（中轴线自动吸附） · 双指捏合缩放字号", 11.5f, 0xff9ca3af);
            titleCol.addView(t2);
            topBar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView resetBtn = a.text("重置居中", 12, 0xffe5e7eb);
            resetBtn.setGravity(Gravity.CENTER);
            resetBtn.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
            resetBtn.setBackground(a.bg(0x28ffffff, 999));
            MobileDetailScreen.bindTouchFeedback(resetBtn);
            resetBtn.setOnClickListener(v -> resetDefaults.run());
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, -2);
            resetLp.rightMargin = a.dp(8);
            topBar.addView(resetBtn, resetLp);

            TextView doneBtn = a.text("完成", 12.5f, Color.WHITE);
            doneBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            doneBtn.setGravity(Gravity.CENTER);
            doneBtn.setPadding(a.dp(15), a.dp(7), a.dp(15), a.dp(7));
            doneBtn.setBackground(a.bg(0xff1f7a54, 999));
            MobileDetailScreen.bindTouchFeedback(doneBtn);
            doneBtn.setOnClickListener(v -> dlg.dismiss());
            topBar.addView(doneBtn, new LinearLayout.LayoutParams(-2, -2));

            FrameLayout.LayoutParams topLp =
                    new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            int topInset = a.statusBarHeight() + a.dp(8);
            topLp.setMargins(a.dp(14), topInset, a.dp(14), 0);
            fullRoot.addView(topBar, topLp);

            // Compact floating control card at top-below-HUD (so bottom subtitle area stays clear)
            LinearLayout ctrlCard = a.vertical();
            ctrlCard.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
            android.graphics.drawable.GradientDrawable ctrlBg = a.bg(0xdc141c19, 20);
            ctrlBg.setStroke(a.dp(1), 0x2effffff);
            ctrlCard.setBackground(ctrlBg);

            // Size slider row
            LinearLayout szRow = a.row();
            szRow.setGravity(Gravity.CENTER_VERTICAL);
            szRow.addView(a.text("字幕大小", 12.5f, MainActivity.WHITE));
            SeekBar szBar = new SeekBar(a);
            szBar.setMax(40);
            UiCompat.tintSeekBar(szBar, 0xff34d399, Color.WHITE);
            sizeBarRef[0] = szBar;
            szRow.addView(szBar, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView szVal = a.text("45", 12.5f, 0xff34d399);
            szVal.setGravity(Gravity.RIGHT);
            sizeValRef[0] = szVal;
            szRow.addView(szVal, new LinearLayout.LayoutParams(a.dp(32), -2));
            ctrlCard.addView(szRow, new LinearLayout.LayoutParams(-1, -2));

            a.gap(ctrlCard, 6);

            // Weight slider row
            LinearLayout wtRow = a.row();
            wtRow.setGravity(Gravity.CENTER_VERTICAL);
            wtRow.addView(a.text("字体粗细", 12.5f, MainActivity.WHITE));
            SeekBar wtBar = new SeekBar(a);
            wtBar.setMax(100);
            UiCompat.tintSeekBar(wtBar, 0xff5ac8fa, Color.WHITE);
            weightBarRef[0] = wtBar;
            wtRow.addView(wtBar, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView wtVal = a.text("50", 12.5f, 0xff5ac8fa);
            wtVal.setGravity(Gravity.RIGHT);
            weightValRef[0] = wtVal;
            wtRow.addView(wtVal, new LinearLayout.LayoutParams(a.dp(32), -2));
            ctrlCard.addView(wtRow, new LinearLayout.LayoutParams(-1, -2));

            a.gap(ctrlCard, 8);

            // Drawer button for Subtitle Color & Style
            TextView styleDrawerBtn = a.text("", 13, Color.WHITE);
            styleDrawerBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            styleDrawerBtn.setGravity(Gravity.CENTER);
            styleDrawerBtn.setPadding(a.dp(14), a.dp(9), a.dp(14), a.dp(9));
            styleDrawerBtn.setBackground(a.bg(0x24ffffff, 14));
            MobileDetailScreen.bindTouchFeedback(styleDrawerBtn);
            styleBtnTextRef[0] = styleDrawerBtn;
            styleDrawerBtn.setOnClickListener(
                    v -> {
                        int curSt =
                                Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
                        showSubtitleStyleDrawer(
                                a,
                                curSt,
                                idx -> {
                                    a.prefs.edit().putInt(Prefs.SUBTITLE_STYLE, idx).apply();
                                    syncState.run();
                                });
                    });
            ctrlCard.addView(styleDrawerBtn, new LinearLayout.LayoutParams(-1, -2));

            szBar.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                            if (!fromUser || internalSync[0]) return;
                            a.prefs.edit().putInt(Prefs.SUBTITLE_SIZE, 25 + p).apply();
                            syncState.run();
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar b) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar b) {}
                    });
            wtBar.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                            if (!fromUser || internalSync[0]) return;
                            a.prefs.edit().putInt(Prefs.SUBTITLE_WEIGHT, p).apply();
                            syncState.run();
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar b) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar b) {}
                    });

            FrameLayout.LayoutParams ctrlLp =
                    new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            ctrlLp.setMargins(a.dp(14), topInset + a.dp(62), a.dp(14), 0);
            fullRoot.addView(ctrlCard, ctrlLp);
        } else {
            // =================================================================
            // TV FULL-SCREEN SUBTITLE PREVIEW & ADJUSTER
            // - D-Pad 2D position adjustment with center-axis guide lines
            // - Dedicated SeekBar slider for Subtitle Size (字幕大小)
            // - Dedicated SeekBar slider for Subtitle Weight (字体粗细)
            // - Slide-in Drawer Menu for Subtitle Color & Style (字幕颜色与样式)
            // =================================================================
            LinearLayout tvPanel = a.vertical();
            tvPanel.setPadding(a.dp(22), a.dp(16), a.dp(22), a.dp(16));
            android.graphics.drawable.GradientDrawable bg = a.bg(0xe4141c19, 22);
            bg.setStroke(a.dp(1), 0x40ffffff);
            tvPanel.setBackground(bg);

            LinearLayout headerRow = a.row();
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = a.text("全屏字幕实时预览与调节", 17, MainActivity.WHITE);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            headerRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView posStatus = a.text("", 12.5f, 0xff34d399);
            posValRef[0] = posStatus;
            headerRow.addView(posStatus);
            tvPanel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
            a.gap(tvPanel, 10);

            // 1. D-Pad Position Control Row (Interactive 2D D-Pad Mode + Quick Step Buttons)
            final boolean[] posDpadActive = {false};
            LinearLayout posRow = a.row();
            posRow.setGravity(Gravity.CENTER_VERTICAL);
            final Button posDpadBtn = a.button("十字方向键调位置（点击进入全向微调）", "tv-sub-pos-mode", () -> {});
            posDpadBtn.setId(View.generateViewId());
            posDpadBtn.setNextFocusUpId(posDpadBtn.getId());
            posDpadBtn.setNextFocusLeftId(posDpadBtn.getId());
            posDpadBtn.setTextSize(13f);
            posDpadBtn.setMinHeight(a.dp(40));
            posDpadBtn.setMinimumHeight(a.dp(40));
            initialTvFocusRef[0] = posDpadBtn;

            final Runnable updatePosModeUi =
                    () -> {
                        boolean active = posDpadActive[0];
                        posDpadBtn.setText(
                                active ? "● 正在用 ▲▼◀▶ 调节位置（按确认键或返回键退出位置模式）" : "十字方向键调位置（点击进入全向微调）");
                        int selfId = posDpadBtn.getId();
                        posDpadBtn.setNextFocusUpId(selfId);
                        posDpadBtn.setNextFocusLeftId(selfId);
                        posDpadBtn.setNextFocusDownId(active ? selfId : View.NO_ID);
                        posDpadBtn.setNextFocusRightId(active ? selfId : View.NO_ID);
                    };

            posDpadBtn.setOnClickListener(
                    v -> {
                        posDpadActive[0] = !posDpadActive[0];
                        updatePosModeUi.run();
                        posDpadBtn.requestFocus();
                    });

            final View.OnKeyListener posModeKeyHandler =
                    (v, keyCode, event) -> {
                        if (!posDpadActive[0]) {
                            return false;
                        }
                        int action = event.getAction();
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            if (action == KeyEvent.ACTION_DOWN) {
                                int bt =
                                        Prefs.clampSubtitleBottom(
                                                a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24) + 4);
                                a.prefs.edit().putInt(Prefs.SUBTITLE_BOTTOM, bt).apply();
                                syncState.run();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (action == KeyEvent.ACTION_DOWN) {
                                int bt =
                                        Prefs.clampSubtitleBottom(
                                                a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24) - 4);
                                a.prefs.edit().putInt(Prefs.SUBTITLE_BOTTOM, bt).apply();
                                syncState.run();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            if (action == KeyEvent.ACTION_DOWN) {
                                int ox =
                                        Prefs.clampSubtitleOffsetX(
                                                a.prefs.getInt(Prefs.SUBTITLE_OFFSET_X, 0) - 4);
                                if (Math.abs(ox) <= 2) ox = 0;
                                a.prefs.edit().putInt(Prefs.SUBTITLE_OFFSET_X, ox).apply();
                                syncState.run();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (action == KeyEvent.ACTION_DOWN) {
                                int ox =
                                        Prefs.clampSubtitleOffsetX(
                                                a.prefs.getInt(Prefs.SUBTITLE_OFFSET_X, 0) + 4);
                                if (Math.abs(ox) <= 2) ox = 0;
                                a.prefs.edit().putInt(Prefs.SUBTITLE_OFFSET_X, ox).apply();
                                syncState.run();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_BACK
                                || keyCode == KeyEvent.KEYCODE_ESCAPE
                                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                                || keyCode == KeyEvent.KEYCODE_ENTER
                                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                            if (action == KeyEvent.ACTION_UP) {
                                posDpadActive[0] = false;
                                updatePosModeUi.run();
                                posDpadBtn.requestFocus();
                            }
                            return true;
                        }
                        return true;
                    };

            posDpadBtn.setOnKeyListener(posModeKeyHandler);
            dlg.setOnKeyListener(
                    (d, keyCode, event) -> posModeKeyHandler.onKey(posDpadBtn, keyCode, event));
            LinearLayout.LayoutParams posBtnLp = new LinearLayout.LayoutParams(0, a.dp(40), 1f);
            posBtnLp.rightMargin = a.dp(8);
            posRow.addView(posDpadBtn, posBtnLp);

            Button upBtn =
                    a.button(
                            "▲ 上移",
                            "tv-sub-up",
                            () -> {
                                int bt =
                                        Prefs.clampSubtitleBottom(
                                                a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24) + 6);
                                a.prefs.edit().putInt(Prefs.SUBTITLE_BOTTOM, bt).apply();
                                syncState.run();
                            });
            upBtn.setId(View.generateViewId());
            upBtn.setNextFocusUpId(upBtn.getId());
            upBtn.setTextSize(12.5f);
            upBtn.setMinHeight(a.dp(40));
            upBtn.setMinimumHeight(a.dp(40));
            LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(-2, a.dp(40));
            upLp.rightMargin = a.dp(6);
            posRow.addView(upBtn, upLp);

            Button downBtn =
                    a.button(
                            "▼ 下移",
                            "tv-sub-down",
                            () -> {
                                int bt =
                                        Prefs.clampSubtitleBottom(
                                                a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24) - 6);
                                a.prefs.edit().putInt(Prefs.SUBTITLE_BOTTOM, bt).apply();
                                syncState.run();
                            });
            downBtn.setId(View.generateViewId());
            downBtn.setNextFocusUpId(downBtn.getId());
            downBtn.setNextFocusRightId(downBtn.getId());
            downBtn.setTextSize(12.5f);
            downBtn.setMinHeight(a.dp(40));
            downBtn.setMinimumHeight(a.dp(40));
            posRow.addView(downBtn, new LinearLayout.LayoutParams(-2, a.dp(40)));
            tvPanel.addView(posRow, new LinearLayout.LayoutParams(-1, -2));

            a.gap(tvPanel, 8);

            // 2. Dedicated SeekBar Slider for Subtitle Size (字幕大小)
            LinearLayout sizeRow = a.row();
            sizeRow.setGravity(Gravity.CENTER_VERTICAL);
            sizeRow.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
            sizeRow.setBackground(a.bg(0x1fffffff, 12));
            TextView sizeLbl = a.text("字幕大小（◀ ▶ 滑动）", 13.5f, MainActivity.WHITE);
            sizeRow.addView(sizeLbl, new LinearLayout.LayoutParams(a.dp(150), -2));
            final SeekBar tvSizeBar = new SeekBar(a);
            tvSizeBar.setMax(40);
            tvSizeBar.setKeyProgressIncrement(2);
            tvSizeBar.setFocusable(true);
            UiCompat.tintSeekBar(tvSizeBar, 0xff34d399, Color.WHITE);
            tvSizeBar.setOnFocusChangeListener(
                    (v, f) ->
                            FocusMotion.style(
                                    a,
                                    sizeRow,
                                    f,
                                    0x1fffffff,
                                    0x3834d399,
                                    12,
                                    MainActivity.WHITE,
                                    MainActivity.WHITE,
                                    false,
                                    1.01f));
            sizeBarRef[0] = tvSizeBar;
            sizeRow.addView(tvSizeBar, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView tvSizeVal = a.text("45", 13.5f, 0xff34d399);
            tvSizeVal.setGravity(Gravity.RIGHT);
            sizeValRef[0] = tvSizeVal;
            sizeRow.addView(tvSizeVal, new LinearLayout.LayoutParams(a.dp(40), -2));
            tvPanel.addView(sizeRow, new LinearLayout.LayoutParams(-1, -2));

            a.gap(tvPanel, 8);

            // 3. Dedicated SeekBar Slider for Subtitle Weight / Thickness (字体粗细)
            LinearLayout weightRow = a.row();
            weightRow.setGravity(Gravity.CENTER_VERTICAL);
            weightRow.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
            weightRow.setBackground(a.bg(0x1fffffff, 12));
            TextView weightLbl = a.text("字体粗细（◀ ▶ 滑动）", 13.5f, MainActivity.WHITE);
            weightRow.addView(weightLbl, new LinearLayout.LayoutParams(a.dp(150), -2));
            final SeekBar tvWeightBar = new SeekBar(a);
            tvWeightBar.setMax(100);
            tvWeightBar.setKeyProgressIncrement(5);
            tvWeightBar.setFocusable(true);
            UiCompat.tintSeekBar(tvWeightBar, 0xff5ac8fa, Color.WHITE);
            tvWeightBar.setOnFocusChangeListener(
                    (v, f) ->
                            FocusMotion.style(
                                    a,
                                    weightRow,
                                    f,
                                    0x1fffffff,
                                    0x385ac8fa,
                                    12,
                                    MainActivity.WHITE,
                                    MainActivity.WHITE,
                                    false,
                                    1.01f));
            weightBarRef[0] = tvWeightBar;
            weightRow.addView(tvWeightBar, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView tvWeightVal = a.text("50", 13.5f, 0xff5ac8fa);
            tvWeightVal.setGravity(Gravity.RIGHT);
            weightValRef[0] = tvWeightVal;
            weightRow.addView(tvWeightVal, new LinearLayout.LayoutParams(a.dp(40), -2));
            tvPanel.addView(weightRow, new LinearLayout.LayoutParams(-1, -2));

            tvSizeBar.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                            if (internalSync[0]) return;
                            a.prefs.edit().putInt(Prefs.SUBTITLE_SIZE, 25 + p).apply();
                            syncState.run();
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar b) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar b) {}
                    });
            tvWeightBar.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                            if (internalSync[0]) return;
                            a.prefs.edit().putInt(Prefs.SUBTITLE_WEIGHT, p).apply();
                            syncState.run();
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar b) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar b) {}
                    });

            a.gap(tvPanel, 8);

            // 4. Drawer Menu Button for Subtitle Color & Style + Reset & Done Buttons
            LinearLayout bottomRow = a.row();
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            Button styleDrawerBtn =
                    a.button(
                            "字幕颜色与样式",
                            "tv-sub-style-drawer",
                            () -> {
                                int curSt =
                                        Prefs.clampSubtitleStyle(
                                                a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
                                showSubtitleStyleDrawer(
                                        a,
                                        curSt,
                                        idx -> {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.SUBTITLE_STYLE, idx)
                                                    .apply();
                                            syncState.run();
                                        });
                            });
            styleDrawerBtn.setId(View.generateViewId());
            styleDrawerBtn.setNextFocusDownId(styleDrawerBtn.getId());
            styleDrawerBtn.setNextFocusLeftId(styleDrawerBtn.getId());
            styleDrawerBtn.setTextSize(13f);
            styleDrawerBtn.setMinHeight(a.dp(42));
            styleDrawerBtn.setMinimumHeight(a.dp(42));
            styleBtnTextRef[0] = styleDrawerBtn;
            LinearLayout.LayoutParams styleLp = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
            styleLp.rightMargin = a.dp(8);
            bottomRow.addView(styleDrawerBtn, styleLp);

            Button resetBtn = a.button("恢复默认", "tv-sub-reset", resetDefaults);
            resetBtn.setId(View.generateViewId());
            resetBtn.setNextFocusDownId(resetBtn.getId());
            resetBtn.setTextSize(13f);
            resetBtn.setMinHeight(a.dp(42));
            resetBtn.setMinimumHeight(a.dp(42));
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, a.dp(42));
            resetLp.rightMargin = a.dp(8);
            bottomRow.addView(resetBtn, resetLp);

            Button doneBtn = a.button("完成", "tv-sub-done", dlg::dismiss);
            doneBtn.setId(View.generateViewId());
            doneBtn.setNextFocusDownId(doneBtn.getId());
            doneBtn.setNextFocusRightId(doneBtn.getId());
            doneBtn.setTextSize(13f);
            doneBtn.setMinHeight(a.dp(42));
            doneBtn.setMinimumHeight(a.dp(42));
            bottomRow.addView(doneBtn, new LinearLayout.LayoutParams(-2, a.dp(42)));
            tvPanel.addView(bottomRow, new LinearLayout.LayoutParams(-1, -2));

            FrameLayout.LayoutParams panelLp =
                    new FrameLayout.LayoutParams(
                            Math.min(a.windowWidth() - a.dp(48), a.dp(660)),
                            -2,
                            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            panelLp.topMargin = a.dp(24);
            fullRoot.addView(tvPanel, panelLp);
        }

        syncState.run();

        dlg.setOnDismissListener(
                d -> {
                    if (a.scroll != null && liveSub == null) {
                        a.scroll.setDescendantFocusability(savedDescendantFocus);
                        a.scroll.scrollTo(0, savedScrollY);
                    }
                    if (a.current != null && liveSub == null) {
                        if (savedFocusTag != null) a.current.focus = savedFocusTag;
                        a.current.scroll = savedScrollY;
                    }
                    if (liveSub != null) {
                        liveSub.setPreviewActive(false);
                        liveSub.setVisibility(View.VISIBLE);
                    }
                    if (onChanged != null) onChanged.run();
                });
        dlg.setContentView(fullRoot, new ViewGroup.LayoutParams(a.windowWidth(), a.windowHeight()));
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow()
                    .setLayout(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT);
        }
        if (initialTvFocusRef[0] != null) {
            initialTvFocusRef[0].requestFocus();
            initialTvFocusRef[0].post(initialTvFocusRef[0]::requestFocus);
        }
    }

    void tracks(int type) {
        if (type == 3) {
            List<String> tracks =
                    (a.player != null && a.prepared)
                            ? a.player.trackLabels(3)
                            : Collections.emptyList();
            List<String> labels = new ArrayList<>();
            labels.add(
                    "方向键直调字幕（大小 "
                            + subtitleSizeLabel()
                            + " · 位置 "
                            + subtitleBottomLabel()
                            + " · "
                            + subtitleStyleLabel()
                            + "）");
            labels.add("字幕样式 · " + subtitleStyleLabel());
            labels.add("关闭字幕");
            labels.addAll(tracks);
            labels.add("导入字幕文件…");
            choose(
                    "字幕与显示设置",
                    labels.toArray(new String[0]),
                    -1,
                    i -> {
                        if (i == 0) {
                            showSubtitleAdjuster(a, subtitle, this::applySubtitleStyle);
                        } else if (i == 1) {
                            int cur =
                                    Prefs.clampSubtitleStyle(
                                            a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
                            choose(
                                    "字幕样式（默认透明底描边）",
                                    SubtitleOverlayView.STYLE_LABELS,
                                    cur,
                                    sIdx -> {
                                        a.prefs.edit().putInt(Prefs.SUBTITLE_STYLE, sIdx).apply();
                                        applySubtitleStyle();
                                    });
                        } else if (i == labels.size() - 1) {
                            a.pickSubtitle();
                        } else if (a.player != null && a.prepared) {
                            a.player.selectTrack(3, i == 2 ? -2 : i - 3);
                        }
                    });
            return;
        }
        if (a.player == null || !a.prepared) {
            message("请等待视频开始播放。");
            return;
        }
        List<String> tracks = a.player.trackLabels(type);
        List<String> labels = new ArrayList<>();
        labels.add("自动选择默认音轨");
        labels.addAll(tracks);
        final int trackOptionCount = labels.size();
        boolean swAudio = a.prefs.getBoolean(Prefs.SOFTWARE_AUDIO, false);
        labels.add("兼容音频解码：" + (swAudio ? "已开启" : "关闭"));
        labels.add("音效模式 · 设置");
        choose(
                "音轨与音频",
                labels.toArray(new String[0]),
                0,
                i -> {
                    if (i < trackOptionCount) {
                        if (a.player != null) a.player.selectTrack(type, i == 0 ? -1 : i - 1);
                    } else if (i == trackOptionCount) {
                        a.prefs.edit().putBoolean(Prefs.SOFTWARE_AUDIO, !swAudio).apply();
                        reload(true);
                    } else {
                        soundEffect();
                    }
                });
    }

    void skip(boolean intro) {
        int curPos = a.player != null && a.prepared ? a.player.getCurrentPosition() : 0;
        int curDur = a.player != null && a.prepared ? a.player.getDuration() : 0;
        showSkipAdjuster(a, intro, curPos, curDur, this::updateLabels);
    }

    static void showSkipAdjuster(
            MainActivity a,
            boolean intro,
            int currentPlayerPosMs,
            int currentPlayerDurMs,
            Runnable onChanged) {
        final String key = intro ? Prefs.SKIP_INTRO : Prefs.SKIP_OUTRO;
        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow()
                    .setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(22), a.dp(18), a.dp(22), a.dp(18));
        android.graphics.drawable.GradientDrawable bg = a.bg(0xf2161e1b, 22);
        bg.setStroke(a.dp(1), 0x38ffffff);
        card.setBackground(bg);

        TextView title = a.text(intro ? "手动精准设置跳过片头" : "手动精准设置跳过片尾", 17, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);
        a.gap(card, 6);

        TextView sub =
                a.text("拖动滑块或使用左右方向键精准定位到秒（00:00 - 03:00），也可一键抓取当前播放进度", 12, MainActivity.MUTED);
        card.addView(sub);
        a.gap(card, 14);

        final TextView valueLabel = a.text("", 18, 0xff2ad47a);
        valueLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(valueLabel);
        a.gap(card, 10);

        final SeekBar bar = new SeekBar(a);
        bar.setMax(180);
        bar.setKeyProgressIncrement(1);
        UiCompat.tintSeekBar(bar, 0xff2ad47a, Color.WHITE);
        int initialSec = Prefs.clampSkipSeconds(a.prefs.getInt(key, 0));
        bar.setProgress(initialSec);
        card.addView(bar, new LinearLayout.LayoutParams(-1, a.dp(36)));
        a.gap(card, 12);

        final PlayerBubble.IntAction applySec =
                sec -> {
                    int clamped = Prefs.clampSkipSeconds(sec);
                    a.prefs.edit().putInt(key, clamped).apply();
                    if (bar.getProgress() != clamped) bar.setProgress(clamped);
                    valueLabel.setText(
                            clamped <= 0
                                    ? "当前：已关闭（00:00）"
                                    : "当前："
                                            + PlaybackController.formatTime(clamped * 1000)
                                            + "（"
                                            + clamped
                                            + " 秒）");
                };
        applySec.accept(initialSec);
        dlg.setOnDismissListener(
                d -> {
                    if (onChanged != null) onChanged.run();
                });

        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar s) {}

                    public void onStopTrackingTouch(SeekBar s) {}

                    public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                        if (fromUser) applySec.accept(progress);
                    }
                });

        if (currentPlayerPosMs > 0) {
            int candidateSec =
                    intro
                            ? (currentPlayerPosMs / 1000)
                            : (currentPlayerDurMs > currentPlayerPosMs
                                    ? (currentPlayerDurMs - currentPlayerPosMs) / 1000
                                    : (currentPlayerPosMs / 1000));
            final int captureSec = Prefs.clampSkipSeconds(Math.max(1, candidateSec));
            String captureText =
                    (intro ? "设为当前播放进度 · " : "设为当前剩余时长 · ")
                            + PlaybackController.formatTime(captureSec * 1000)
                            + " ("
                            + captureSec
                            + "秒)";
            TextView captureBtn = a.text(captureText, 13, MainActivity.WHITE);
            captureBtn.setGravity(Gravity.CENTER);
            captureBtn.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            captureBtn.setBackground(a.bg(0xff1e5d41, 14));
            captureBtn.setFocusable(true);
            captureBtn.setOnClickListener(v -> applySec.accept(captureSec));
            card.addView(captureBtn, new LinearLayout.LayoutParams(-1, -2));
            a.gap(card, 10);
        }

        LinearLayout stepRow = a.row();
        stepRow.setGravity(Gravity.CENTER);
        String[] stepTitles = {"关闭", "-10秒", "-5秒", "-1秒", "+1秒", "+5秒", "+10秒", "完成"};
        int[] deltas = {0, -10, -5, -1, 1, 5, 10, 999};
        for (int i = 0; i < stepTitles.length; i++) {
            final int d = deltas[i];
            TextView b = a.text(stepTitles[i], 12, MainActivity.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
            b.setFocusable(true);
            b.setBackground(a.bg(d == 999 ? 0xff1f6f4a : 0x24ffffff, 12));
            b.setOnClickListener(
                    v -> {
                        if (d == 999) {
                            dlg.dismiss();
                        } else if (d == 0) {
                            applySec.accept(0);
                        } else {
                            applySec.accept(a.prefs.getInt(key, 0) + d);
                        }
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = a.dp(6);
            stepRow.addView(b, lp);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(stepRow);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout wrapper = new FrameLayout(a);
        FrameLayout.LayoutParams wlp =
                new FrameLayout.LayoutParams(
                        Math.min(a.windowWidth() - a.dp(32), a.dp(540)), -2, Gravity.CENTER);
        wrapper.addView(card, wlp);
        dlg.setContentView(wrapper);
        dlg.show();
        bar.requestFocus();
    }

    void repeat() {
        choose(
                "循环播放",
                new String[] {"关闭", "单集循环", "列表循环"},
                a.prefs.getInt(Prefs.REPEAT_MODE, 0),
                i -> {
                    a.prefs.edit().putInt(Prefs.REPEAT_MODE, i).apply();
                    if (a.player != null)
                        a.player.setRepeatOne(i == 1 && a.prefs.getInt(Prefs.SKIP_OUTRO, 0) == 0);
                    updateLabels();
                });
    }

    void more() {
        if ("live".equals(site.key)) {
            String[] live = {"上一频道", "选择频道", "播放线路", "播放器与解码", "画面色彩", "播放信息", "返回频道列表"};
            choose(
                    "直播设置",
                    live,
                    -1,
                    i -> {
                        switch (i) {
                            case 0:
                                a.liveStep(-1);
                                break;
                            case 1:
                                a.chooseLiveChannel();
                                break;
                            case 2:
                                a.chooseLiveStream();
                                break;
                            case 3:
                                choose(
                                        "播放器与解码",
                                        new String[] {"切换播放器", "切换解码方式", "画面输出"},
                                        -1,
                                        n -> {
                                            if (n == 0) engine();
                                            else if (n == 1) decoder();
                                            else output();
                                        });
                                break;
                            case 4:
                                pictureEffect();
                                break;
                            case 5:
                                message(
                                        a.player == null
                                                ? "播放器尚未准备好。"
                                                : a.player.playbackInfo()
                                                        + "\n"
                                                        + surface.outputName());
                                break;
                            case 6:
                                a.leavePlayback();
                                break;
                        }
                    });
            return;
        }
        String[] labels = {
            "画面比例",
            "字幕与显示",
            "刷新播放",
            "从头重播",
            "上一集",
            "跳过片头 / 片尾",
            "弹幕",
            "音效",
            "画面色彩",
            "循环播放",
            "自动播放下集：" + (a.prefs.getBoolean(Prefs.AUTO_NEXT, true) ? "开启" : "关闭"),
            "播放信息",
            "返回详情"
        };
        choose(
                "更多",
                labels,
                -1,
                i -> {
                    switch (i) {
                        case 0:
                            aspect();
                            break;
                        case 1:
                            tracks(3);
                            break;
                        case 2:
                            reload(true);
                            break;
                        case 3:
                            pendingResumeMs = 0;
                            if (a.playingBookmark != null) a.playingBookmark.position = 0;
                            if (a.player != null && a.prepared) {
                                recordUserSeek(0);
                                a.player.seekTo(0);
                                a.player.start();
                                a.playing = true;
                            } else {
                                a.resumeAt = -1;
                                reload(false);
                            }
                            break;
                        case 4:
                            if (!step(-1)) message("已经是第一集。");
                            break;
                        case 5:
                            choose(
                                    "自动跳过",
                                    new String[] {
                                        "片头 · " + skipLabel(a.prefs.getInt(Prefs.SKIP_INTRO, 0)),
                                        "片尾 · " + skipLabel(a.prefs.getInt(Prefs.SKIP_OUTRO, 0))
                                    },
                                    -1,
                                    n -> skip(n == 0));
                            break;
                        case 6:
                            danmaku();
                            break;
                        case 7:
                            soundEffect();
                            break;
                        case 8:
                            pictureEffect();
                            break;
                        case 9:
                            repeat();
                            break;
                        case 10:
                            a.prefs
                                    .edit()
                                    .putBoolean(
                                            Prefs.AUTO_NEXT,
                                            !a.prefs.getBoolean(Prefs.AUTO_NEXT, true))
                                    .apply();
                            break;
                        case 11:
                            message(
                                    a.player == null
                                            ? "播放器尚未准备好。"
                                            : a.player.playbackInfo()
                                                    + "\n"
                                                    + surface.outputName());
                            break;
                        case 12:
                            a.leavePlayback();
                            break;
                    }
                });
    }

    void updateLabels() {
        float rate = a.prefs.getFloat(Prefs.PLAY_SPEED, 1f);
        Button speedBtn = buttons.get("speed");
        if (speedBtn != null) {
            if (a.isTvDevice()) {
                speedBtn.setText(Math.abs(rate - 1f) < 0.01f ? "倍速" : rate + "×");
            } else {
                speedBtn.setText(rate + "×");
            }
        }
        Button introBtn = buttons.get("skip-intro-tool");
        if (introBtn != null) {
            int s = a.prefs.getInt(Prefs.SKIP_INTRO, 0);
            introBtn.setText(s > 0 ? (a.isTvDevice() ? "片头" + s + "s" : s + "s") : "片头");
        }
        Button outroBtn = buttons.get("skip-outro-tool");
        if (outroBtn != null) {
            int s = a.prefs.getInt(Prefs.SKIP_OUTRO, 0);
            outroBtn.setText(s > 0 ? (a.isTvDevice() ? "片尾" + s + "s" : s + "s") : "片尾");
        }
        Button decBtn = buttons.get("decoder");
        if (decBtn != null) {
            int eng = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
            decBtn.setText(eng == 2 ? "IJK" : (eng == 1 ? "系统" : "Exo"));
        }
    }

    String skipLabel(int seconds) {
        return seconds == 0 ? "关闭" : a.time(seconds * 1000);
    }

    void applyExternalDanmaku(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return;
        String candidate = rawUrl.trim();
        if (candidate.startsWith("[") || candidate.startsWith("{")) {
            try {
                org.json.JSONObject wrapper = new org.json.JSONObject().put("danmaku", candidate);
                String extracted = MediaOptions.extractDanmakuUrl(wrapper);
                if (!extracted.isEmpty()) candidate = extracted;
            } catch (Exception ignored) {
            }
        }
        String rewritten = LocalProxy.rewrite(candidate);
        if (rewritten == null || rewritten.isEmpty()) return;
        String previous = resolved.media.optString("danmaku");
        try {
            resolved.media.put("danmaku", rewritten);
        } catch (Exception ignored) {
        }
        if (a.prefs.getBoolean(Prefs.DANMAKU_DEFAULT, true)
                && (!danmakuLoaded || !rewritten.equals(previous))) {
            danmakuLoaded = true;
            danmaku.enabled(true);
            danmaku.load(
                    rewritten,
                    resolved.headers,
                    new DanmakuView.Listener() {
                        public void loaded(int count) {
                            if (count > 0) a.toast("已加载 " + count + " 条弹幕");
                        }

                        public void error(String message) {}
                    });
        }
    }

    void prepared() {
        if (a.player == null) return;
        if (resolved.media.optString("danmaku").isEmpty()) {
            String recentDm = CspEngine.consumeRecentPluginDanmaku(25000L);
            if (!recentDm.isEmpty()) {
                applyExternalDanmaku(recentDm);
            }
        }
        if (a.prefs.getBoolean(Prefs.DANMAKU_DEFAULT, true)
                && !danmakuLoaded
                && !resolved.media.optString("danmaku").isEmpty()) {
            danmakuLoaded = true;
            danmaku.enabled(true);
            danmaku.load(
                    resolved.media.optString("danmaku"),
                    resolved.headers,
                    new DanmakuView.Listener() {
                        public void loaded(int count) {
                            if (count > 0) a.toast("已加载 " + count + " 条弹幕");
                        }

                        public void error(String message) {}
                    });
        }
        PlaybackEffects.picture(surface, a.prefs.getInt(Prefs.PICTURE_EFFECT, 0));
        try {
            effects.audio(a.player.getAudioSessionId(), a.prefs.getInt(Prefs.AUDIO_EFFECT, 0));
        } catch (Exception ignored) {
        }
        int duration = a.player.getDuration();
        if (duration <= 0 && a.playingBookmark != null && a.playingBookmark.duration > 0) {
            duration = a.playingBookmark.duration;
        }
        timeline.setEnabled(duration > 0);
        timeline.setMax(duration > 0 ? duration : 100);
        if (duration <= 0) timeline.setProgress(0);
        timeline.setKeyProgressIncrement(15000);
        boolean explicitRestart = a.resumeAt < 0;
        boolean historyResume = a.resumeAt > 0;
        int seek =
                ("live".equals(site.key) || explicitRestart)
                        ? 0
                        : historyResume ? a.resumeAt : a.prefs.getInt(Prefs.SKIP_INTRO, 0) * 1000;
        a.resumeAt = 0;
        if (seek > 0) {
            if (duration > 0) {
                if (seek < duration - 1000) {
                    pendingResumeMs = 0;
                    recordUserSeek(seek);
                    a.player.seekTo(seek);
                    if (historyResume) {
                        a.toast("已为您续播至 " + PlaybackController.formatTime(seek));
                    }
                }
            } else {
                pendingResumeMs = seek;
                if (historyResume) {
                    a.toast("已为您续播至 " + PlaybackController.formatTime(seek));
                }
            }
        }
        a.player.setSpeed(a.prefs.getFloat(Prefs.PLAY_SPEED, 1));
        a.player.setRepeatOne(
                a.prefs.getInt(Prefs.REPEAT_MODE, 0) == 1
                        && a.prefs.getInt(Prefs.SKIP_OUTRO, 0) == 0);
    }

    void tick() {
        if ("live".equals(site.key)) return;
        if (a.player == null || !a.prepared || isUserSeeking()) return;
        int duration = a.player.getDuration();
        if (duration <= 0 && a.playingBookmark != null && a.playingBookmark.duration > 0) {
            duration = a.playingBookmark.duration;
        }
        if (pendingResumeMs > 0 && duration > 0) {
            int target = pendingResumeMs;
            pendingResumeMs = 0;
            if (target < duration - 1000
                    && a.player.getCurrentPosition() < Math.min(target - 2000, 5000)) {
                recordUserSeek(target);
                a.player.seekTo(target);
            }
        }
        int position = displayPosition(a.player.getCurrentPosition());
        if (duration > 0) {
            timeline.setEnabled(true);
            timeline.setMax(Math.max(duration, position));
            timeline.setProgress(Math.max(0, Math.min(position, timeline.getMax())));
        } else {
            timeline.setEnabled(false);
            timeline.setMax(100);
            timeline.setProgress(0);
        }
        int tail = a.prefs.getInt(Prefs.SKIP_OUTRO, 0) * 1000;
        if (!transitioning
                && a.playing
                && duration > 0
                && tail > 0
                && tail < duration
                && position >= duration - tail) {
            if (a.prefs.getInt(Prefs.REPEAT_MODE, 0) == 1)
                a.player.seekTo(a.prefs.getInt(Prefs.SKIP_INTRO, 0) * 1000);
            else if (!step(1)) {
                transitioning = true;
                a.player.pause();
                a.playing = false;
                a.playStatus.setText("已跳过片尾");
                a.showControls(true);
            }
        }
    }

    void completed() {
        if (!transitioning
                && (a.prefs.getBoolean(Prefs.AUTO_NEXT, true)
                        || a.prefs.getInt(Prefs.REPEAT_MODE, 0) == 2)
                && step(1)) return;
        a.showControls(true);
        a.playStatus.setText("播放结束 · 按确认重播");
    }

    void resize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        if (a.playerChrome != null) a.playerChrome.resolution(width, height);
        if (width <= 0 || height <= 0 || a.playbackLayer.getWidth() == 0) return;
        int w = a.playbackLayer.getWidth(),
                h = a.playbackLayer.getHeight(),
                mode = a.prefs.getInt(Prefs.ASPECT, 0);
        float ratio = mode == 2 ? 16f / 9 : mode == 3 ? 4f / 3 : (float) width / height;
        if (mode != 1) {
            if (mode == 4) {
                if ((float) w / h > ratio) h = Math.round(w / ratio);
                else w = Math.round(h * ratio);
            } else if ((float) w / h > ratio) w = Math.round(h * ratio);
            else h = Math.round(w / ratio);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    void visible(boolean visible) {
        int bottomDp = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
        int basePx = a.dp(bottomDp);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) subtitle.getLayoutParams();
        if (lp.bottomMargin != basePx) {
            lp.bottomMargin = basePx;
            subtitle.setLayoutParams(lp);
        }
        if (subtitle.isPreviewActive()) {
            subtitle.setTranslationY(0f);
            return;
        }
        boolean progressOnly = a.playerChrome != null && a.playerChrome.progressOnlyMode;
        int targetPx =
                visible
                        ? a.dp(
                                progressOnly
                                        ? Math.max(82, bottomDp + 16)
                                        : Math.max(168, bottomDp + 24))
                        : basePx;
        subtitle.setTranslationY(-(targetPx - basePx));
    }
}
