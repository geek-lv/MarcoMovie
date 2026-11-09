package com.pilipala.marcomovie;

import android.app.Activity;
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
 * 用户从播放画面截取的「GL 朝向」Bitmap 进入此处，做矩形框选并保存为特征图。
 *
 * <p>截图以「竖直翻转」后的可视态展示（GL 朝向＝行 0 在底部，直接显示会上下颠倒），
 * 框选矩形在显示坐标系下产生；保存前再翻转回 GL 坐标系，使模板与逐帧检测同坐标系。
 */
public class TemplateCaptureActivity extends Activity {

    /** 由 {@code SettingsScreen.startCapture()} 在捕获回调里写入的 GL 朝向截图。 */
    public static volatile Bitmap pending = null;

    private RectSelectView rsv;
    private Bitmap glBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        glBitmap = pending;
        if (glBitmap == null || glBitmap.isRecycled()) {
            finish();
            return;
        }
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
        String name = AdBlurTemplates.save(this, crop, "tpl");
        if (crop != null) crop.recycle();
        if (name != null) {
            AdBlurController.get(this).notifyTemplatesChanged();
            toast("已保存特征图：" + name);
        } else {
            toast("保存失败");
        }
        finish();
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
