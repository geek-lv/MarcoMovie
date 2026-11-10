package com.pilipala.marcomovie.adblur;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户运行时框选保存的「特征图（牛皮癣样板）」管理。
 *
 * <p>图片以 PNG 存于应用私有目录 {@code files/adblur_templates/}，文件名做注入防护。
 * 模板会降采样到最长边 {@link #MAX_DIM} 以内（保持比例），以控制 GPU 匹配开销。
 *
 * <p>注意：保存的 Bitmap 必须是「GL 朝向」（与视频帧纹理同坐标系，见 {@code QrAdBlurEffect}
 * 的截图捕获），这样检测时的 SAD 匹配才与画面一致。
 */
public class AdBlurTemplates {

    private static final String DIR = "adblur_templates";

    /** 模板最长边上限（降采样），也是 GPU 匹配内层循环的硬上限。 */
    public static final int MAX_DIM = 64;

    private static final long MAX_BYTES = 4 * 1024 * 1024; // 单文件上限，防滥用

    private static File dir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), DIR);
    }

    public static File dirFile(Context c) {
        File d = dir(c);
        if (!d.exists() && !d.mkdirs()) {
            // mkdirs 失败时目录可能已存在（并发），下一行 exists 兜底
        }
        return d;
    }

    /**
     * 保存特征图（crop 须为 GL 朝向的 Bitmap）。返回文件名，失败返回 null。
     */
    @Nullable
    public static String save(Context c, Bitmap crop, @Nullable String label) {
        if (crop == null || crop.isRecycled()) return null;
        File d = dirFile(c);
        float scale = Math.min(1f, (float) MAX_DIM / Math.max(crop.getWidth(), crop.getHeight()));
        Bitmap t = crop;
        if (scale < 1f) {
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            t = Bitmap.createBitmap(crop, 0, 0, crop.getWidth(), crop.getHeight(), m, true);
        }
        String name =
                (label == null || label.isEmpty() ? "tpl" : sanitize(label))
                        + "_"
                        + (System.currentTimeMillis() % 1000000)
                        + ".png";
        File f = new File(d, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            t.compress(Bitmap.CompressFormat.PNG, 100, os);
            if (t != crop) t.recycle();
            return f.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitize(String s) {
        // 仅保留字母数字、中文、下划线/连字符，防路径穿越与特殊字符
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 24; i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)
                    || ch == '_'
                    || ch == '-'
                    || (ch >= '一' && ch <= '鿿')) {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "tpl" : sb.toString();
    }

    /** 列出全部特征图文件名（按文件名排序，稳定）。 */
    public static List<String> list(Context c) {
        File d = dir(c);
        List<String> out = new ArrayList<>();
        if (d.exists()) {
            String[] fs =
                    d.list(
                            new FilenameFilter() {
                                @Override
                                public boolean accept(File dir, String n) {
                                    return n.endsWith(".png");
                                }
                            });
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (String n : fs) out.add(n);
            }
        }
        return out;
    }

    public static int count(Context c) {
        return list(c).size();
    }

    /** 解码指定模板为 GL 朝向 Bitmap（不翻转）。不存在返回 null。 */
    @Nullable
    public static Bitmap load(Context c, String name) {
        File f = new File(dir(c), sanitize(name));
        if (!f.exists() || f.length() > MAX_BYTES) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    public static void delete(Context c, String name) {
        File f = new File(dir(c), sanitize(name));
        if (f.exists()) f.delete();
    }

    public static void clear(Context c) {
        File d = dir(c);
        if (d.exists()) {
            File[] files = d.listFiles();
            if (files != null) for (File f : files) f.delete();
        }
    }
}
