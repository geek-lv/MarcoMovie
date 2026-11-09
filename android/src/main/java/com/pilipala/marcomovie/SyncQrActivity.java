package com.pilipala.marcomovie;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Locale;

/**
 * 电视端「同步码」页面：开启一个 {@link ConfigSyncServer}，把地址编码成二维码展示；
 * 手机扫码后把本地配置推送过来，本机整体应用并热重载。
 */
public class SyncQrActivity extends Activity {

    private ConfigSyncServer server;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xff101013);
        root.setPadding(dp(28), dp(36), dp(28), dp(36));

        TextView title = new TextView(this);
        title.setText("手机扫码同步配置到电视");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        root.addView(title);
        root.addView(gap(dp(14)));

        try {
            String ip = PhoneConfigDialog.lanAddress();
            server =
                    new ConfigSyncServer(
                            json -> ui.post(() -> applyConfig(json)));
            String address = server.address(ip);

            int size = dp(220);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(makeQr(address, size));
            root.addView(qr, new LinearLayout.LayoutParams(size, size));

            TextView url = new TextView(this);
            url.setText(address);
            url.setTextColor(0xffbfbfc7);
            url.setTextSize(12);
            url.setGravity(android.view.Gravity.CENTER);
            url.setPadding(0, dp(10), 0, 0);
            root.addView(url);

            TextView note = new TextView(this);
            note.setText("保持本页面打开，用手机「设置 → 视频牛皮癣模糊 → 同步到电视」扫码并推送。");
            note.setTextColor(0xffbfbfc7);
            note.setTextSize(14);
            note.setGravity(android.view.Gravity.CENTER);
            note.setPadding(0, dp(16), 0, 0);
            root.addView(note);

            TextView exp = new TextView(this);
            exp.setText("二维码有效 5 分钟 · 关闭后停止接收");
            exp.setTextColor(0xff8a8a92);
            exp.setTextSize(12);
            exp.setGravity(android.view.Gravity.CENTER);
            exp.setPadding(0, dp(10), 0, 0);
            root.addView(exp);
        } catch (Exception e) {
            TextView err = new TextView(this);
            err.setText("无法开启局域网同步：" + e.getMessage());
            err.setTextColor(Color.WHITE);
            err.setTextSize(15);
            err.setGravity(android.view.Gravity.CENTER);
            root.addView(err);
        }

        setContentView(root);
    }

    private void applyConfig(String json) {
        try {
            int n = ConfigSyncData.apply(this, json);
            toast("已接收并应用 " + n + " 项配置");
            AdBlurController.get(this).notifyTemplatesChanged();
            AdSkipController.get().onConfigChanged(this);
        } catch (Exception e) {
            toast("同步失败：" + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.close();
        super.onDestroy();
    }

    private Bitmap makeQr(String text, int size) throws Exception {
        BitMatrix matrix =
                new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                px[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }

    private android.view.View gap(int h) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }
}
