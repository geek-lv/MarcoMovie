package com.pilipala.marcomovie;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机端「同步到电视」：用相机扫描电视上的同步二维码，把本地配置快照 POST 过去。
 *
 * <p>若相机不可用 / 未授权，提供手动粘贴电视地址的回退入口，二者最终都走 {@link #startSync(String)}。
 */
public class SyncScanActivity extends Activity
        implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int REQ_CAM = 2001;

    private Camera camera;
    private SurfaceView preview;
    private TextView status;
    private boolean scanning = false;
    private boolean syncing = false;
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);

        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(16), dp(16), dp(16), dp(16));
        bar.setBackgroundColor(0xcc111418);
        bar.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("扫描电视上的同步二维码");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title);

        status = new TextView(this);
        status.setText("将电视二维码置于取景框内");
        status.setTextColor(0xffbfbfc7);
        status.setTextSize(14);
        status.setGravity(android.view.Gravity.CENTER);
        status.setPadding(0, dp(8), 0, 0);
        bar.addView(status);

        // 手动回退：无法扫码时粘贴电视地址
        bar.addView(gap(dp(14)));
        TextView mh = new TextView(this);
        mh.setText("无法扫码？手动粘贴电视地址：");
        mh.setTextColor(0xffbfbfc7);
        mh.setTextSize(13);
        bar.addView(mh);
        EditText addr = new EditText(this);
        addr.setHint("http://192.168.x.x:port/sync/...");
        addr.setTextColor(Color.WHITE);
        addr.setHintTextColor(0xff8a8a92);
        addr.setTextSize(14);
        addr.setPadding(dp(12), dp(10), dp(12), dp(10));
        addr.setBackgroundColor(0xff292930);
        bar.addView(addr);
        Button send = new Button(this);
        send.setText("推送配置到电视");
        send.setOnClickListener(
                v -> {
                    String a = addr.getText().toString().trim();
                    if (!a.isEmpty()) startSync(a);
                });
        bar.addView(send);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> finish());
        bar.addView(cancel);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2);
        blp.gravity = android.view.Gravity.BOTTOM;
        root.addView(bar, blp);

        setContentView(root);
    }

    // ---------------------------------------------------------------- 相机

    private boolean hasCamPermission() {
        return checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCam() {
        requestPermissions(new String[] {android.Manifest.permission.CAMERA}, REQ_CAM);
    }

    @Override
    public void onRequestPermissionsResult(
            int req, String[] perms, int[] res) {
        if (req == REQ_CAM
                && perms.length > 0
                && res.length > 0
                && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (preview.getHolder() != null) openCamera();
        } else {
            showManual("未授予相机权限，请手动粘贴电视地址");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        if (hasCamPermission()) openCamera();
        else requestCam();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        releaseCamera();
    }

    private void openCamera() {
        if (camera != null) return;
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = chooseSize(p.getSupportedPreviewSizes(), 640, 480);
            if (best != null) p.setPreviewSize(best.width, best.height);
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(preview.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();
            scanning = true;
        } catch (Exception e) {
            scanning = false;
            releaseCamera();
            showManual("无法打开相机：" + e.getMessage());
        }
    }

    private void releaseCamera() {
        scanning = false;
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    private static Camera.Size chooseSize(List<Camera.Size> sizes, int tw, int th) {
        if (sizes == null) return null;
        Camera.Size best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (Camera.Size s : sizes) {
            int diff = Math.abs(s.width - tw) + Math.abs(s.height - th);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (!scanning || syncing || data == null) return;
        Camera.Size sz = cam.getParameters().getPreviewSize();
        if (sz == null) return;
        try {
            LuminanceSource src =
                    new PlanarYUVLuminanceSource(
                            data, sz.width, sz.height, 0, 0, sz.width, sz.height, false);
            if (decode(src) != null) return;
            // 尝试四个旋转方向（相机可能横置 / 旋转）
            LuminanceSource r = src;
            for (int i = 0; i < 3; i++) {
                r = r.rotateCounterClockwise();
                if (decode(r) != null) return;
            }
        } catch (Exception ignored) {
        }
    }

    private String decode(LuminanceSource src) {
        try {
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(
                    DecodeHintType.POSSIBLE_FORMATS,
                    Collections.singletonList(BarcodeFormat.QR_CODE));
            com.google.zxing.Result res = reader.decode(bb, hints);
            if (res != null) {
                String text = res.getText();
                onCode(text);
                return text;
            }
        } catch (com.google.zxing.NotFoundException ignored) {
            // 本帧未识别，继续
        } catch (Exception ignored) {
        }
        return null;
    }

    private void onCode(String text) {
        scanning = false;
        releaseCamera();
        startSync(text);
    }

    private void showManual(String msg) {
        ui.post(
                () -> {
                    if (status != null) status.setText(msg);
                });
    }

    // ---------------------------------------------------------------- 同步推送

    private void startSync(String address) {
        if (syncing) return;
        syncing = true;
        ui.post(() -> setStatus("正在打包本地配置并推送…"));
        new Thread(
                        () -> {
                            try {
                                JSONObject payload = ConfigSyncData.build(this);
                                byte[] body = payload.toString().getBytes("UTF-8");
                                URL u = new URL(address);
                                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                                conn.setRequestMethod("POST");
                                conn.setConnectTimeout(10000);
                                conn.setReadTimeout(30000);
                                conn.setDoOutput(true);
                                conn.setRequestProperty(
                                        "Content-Type", "application/json; charset=utf-8");
                                conn.setRequestProperty(
                                        "Content-Length", String.valueOf(body.length));
                                conn.setFixedLengthStreamingMode(body.length);
                                try (OutputStream os = conn.getOutputStream()) {
                                    os.write(body);
                                }
                                int code = conn.getResponseCode();
                                String resp =
                                        readStream(
                                                (code >= 200 && code < 400)
                                                        ? conn.getInputStream()
                                                        : conn.getErrorStream());
                                conn.disconnect();
                                final boolean ok = code == 200;
                                final String msg = resp == null ? "" : resp;
                                ui.post(
                                        () -> {
                                            syncing = false;
                                            Toast.makeText(
                                                            this,
                                                            (ok
                                                                            ? "已同步到电视："
                                                                            : "同步失败("
                                                                                    + code
                                                                                    + ")：")
                                                                    + msg,
                                                            Toast.LENGTH_LONG)
                                                    .show();
                                            if (ok) finish();
                                        });
                            } catch (Exception e) {
                                ui.post(
                                        () -> {
                                            syncing = false;
                                            Toast.makeText(
                                                            this,
                                                            "同步失败：" + e.getMessage(),
                                                            Toast.LENGTH_LONG)
                                                    .show();
                                        });
                            }
                        })
                .start();
    }

    private void setStatus(final String s) {
        ui.post(
                () -> {
                    if (status != null) status.setText(s);
                });
    }

    private static String readStream(InputStream in) {
        if (in == null) return "";
        try (ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        super.onDestroy();
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
