package com.pilipala.marcomovie;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pilipala.marcomovie.adblur.AdBlurController;
import com.pilipala.marcomovie.adblur.AdBlurTemplates;

/**
 * 用户从播放画面截取的画面进入此处，做矩形框选并保存为特征图。
 *
 * <p>锁定横屏：源画面来自横屏播放器，框选交互直接基于横屏模式。
 * 画面来源：播放器右侧菜单「采集」（VideoOutput.snapshotFullFrame 抓显示帧，
 * 由调用方翻转成 GL 朝向后写入 {@link #pending}）。
 *
 * <p>截图以「竖直翻转」后的可视态展示（GL 朝向＝行 0 在底部，直接显示会上下颠倒），
 * 框选矩形在显示坐标系下产生；保存前再翻转回 GL 坐标系，使模板与逐帧检测同坐标系。
 */
public class TemplateCaptureActivity extends Activity {

    /** 由播放器菜单「采集」等流程写入的 GL 朝向截图。 */
    public static volatile Bitmap pending = null;

    private RectSelectView rsv;
    private Bitmap glBitmap;
    /** 视频实际内容区域（GL 系归一化 {cx,cy,cw,ch}），随模板一起写入 .meta。 */
    private float[] contentRect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 框选交互直接基于横屏模式（源画面为横屏播放器画面）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        glBitmap = pending;
        if (glBitmap == null || glBitmap.isRecycled()) {
            finish();
            return;
        }
        // 实测内容区域：播放器可能 fit 留黑边 / 拉伸填满 / 裁剪填满，
        // 不能按固定宽高比猜，必须按当前这一帧的实际非黑区域算。
        contentRect = detectContentRect(glBitmap);
        // 显示用：竖直翻转（行 0 → 顶部），让用户看到正立的画面
        Bitmap display = flipVertical(glBitmap);

        FrameLayout root = new FrameLayout(this);
        rsv = new RectSelectView(this, display);
        root.addView(rsv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(16), dp(16), dp(16));
        bar.setBackgroundColor(0xcc111418);

        TextView title = new TextView(this);
        title.setText("框选牛皮癣区域");
        title.setTextColor(0xffffffff);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> finish());

        Button save = new Button(this);
        save.setText("保存特征图");
        save.setOnClickListener(v -> doSave());

        bar.addView(title);
        bar.addView(cancel);
        bar.addView(save);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, -2);
        barLp.gravity = Gravity.TOP;
        root.addView(bar, barLp);

        setContentView(root);
    }

    private void doSave() {
        Rect r = rsv.getRectInBitmap();
        if (r == null) {
            toast("请先在画面上拖拽框选广告区域");
            return;
        }
        // 显示坐标系 → GL 坐标系（竖直翻转）：左右不变，上下镜像
        int gw = glBitmap.getWidth();
        int gh = glBitmap.getHeight();
        int glLeft = r.left;
        int glTop = gh - r.bottom;
        int glW = r.width();
        int glH = r.height();
        if (glLeft < 0) glLeft = 0;
        if (glTop < 0) glTop = 0;
        if (glLeft + glW > gw) glW = gw - glLeft;
        if (glTop + glH > gh) glH = gh - glTop;
        if (glW < 6 || glH < 6) {
            toast("框选区域太小");
            return;
        }
        Bitmap crop = Bitmap.createBitmap(glBitmap, glLeft, glTop, glW, glH);
        // 标定：记录裁剪区域在整帧中的归一化矩形（GL 系，y 从画面底部向上）。
        // glBitmap 行 0 = 画面底部 = GL 纹理 v=0，故 glTop/gh 直接就是 GL 系 y。
        // 检测侧据此把搜索限制在 ROI 内，并按当前帧尺度生成同比例模板纹理。
        AdBlurTemplates.Meta meta =
                new AdBlurTemplates.Meta(
                        glW,
                        glH,
                        (float) glLeft / gw,
                        (float) glTop / gh,
                        (float) glW / gw,
                        (float) glH / gh,
                        contentRect);
        String name = AdBlurTemplates.save(this, crop, "tpl", meta);
        if (crop != null) crop.recycle();
        if (name != null) {
            AdBlurController.get(this).notifyTemplatesChanged();
            toast("已保存特征图：" + name);
        } else {
            toast("保存失败");
        }
        finish();
    }

    /**
     * 从截图四边检测近黑信箱边，返回真实视频内容区（GL 系：y 从画面底部算）。
     * 拉伸/裁剪填满时没有黑边，自然退化为整屏；两侧不对称或黑边过宽时视为暗场误检，回退整屏。
     */
    private static float[] detectContentRect(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        float[] full = new float[] {0f, 0f, 1f, 1f};
        if (w < 64 || h < 64) return full;
        int stepY = Math.max(1, h / 24), stepX = Math.max(1, w / 24);
        int left = 0, right = w - 1;
        int maxX = w / 4;
        while (left < maxX && darkColumn(b, left, h, stepY)) left++;
        while (right > w - 1 - maxX && darkColumn(b, right, h, stepY)) right--;
        int lw = left, rw = w - 1 - right;
        if (Math.abs(lw - rw) > Math.max(8, w / 50) || lw + rw > w * 0.32f) {
            left = 0;
            right = w - 1;
        }
        int bottom = 0, top = h - 1;
        int maxY = h / 4;
        while (bottom < maxY && darkRow(b, bottom, w, stepX)) bottom++;
        while (top > h - 1 - maxY && darkRow(b, top, w, stepX)) top--;
        int bh = bottom, th = h - 1 - top;
        if (Math.abs(bh - th) > Math.max(8, h / 50) || bh + th > h * 0.32f) {
            bottom = 0;
            top = h - 1;
        }
        return new float[] {
            (float) left / w,
            (float) bottom / h,
            (float) (right - left + 1) / w,
            (float) (top - bottom + 1) / h
        };
    }

    /** 列采样平均亮度低于阈值视为信箱黑边（视频黑电平约 16）。 */
    private static boolean darkColumn(Bitmap b, int x, int h, int step) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < h; y += step) {
            int c = b.getPixel(x, y);
            sum += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
            n += 3;
        }
        return n > 0 && sum / n < 26;
    }

    private static boolean darkRow(Bitmap b, int y, int w, int step) {
        long sum = 0;
        int n = 0;
        for (int x = 0; x < w; x += step) {
            int c = b.getPixel(x, y);
            sum += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
            n += 3;
        }
        return n > 0 && sum / n < 26;
    }

    private static Bitmap flipVertical(Bitmap src) {
        Matrix m = new Matrix();
        m.postScale(1, -1, src.getWidth() / 2f, src.getHeight() / 2f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    @Override
    protected void onDestroy() {
        // 消费完即清空，避免被后续无关帧误用；显示位图由 rsv 持有，交给 GC
        pending = null;
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
