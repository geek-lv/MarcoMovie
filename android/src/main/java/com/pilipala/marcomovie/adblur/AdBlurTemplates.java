package com.pilipala.marcomovie.adblur;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户运行时框选保存的「特征图（牛皮癣样板）」管理。
 *
 * <p>图片以 PNG 存于应用私有目录 {@code files/adblur_templates/}，同名 {@code .meta} 保存采集时的
 * 标定信息（裁剪像素尺寸 + 归一化矩形）。
 *
 * <p><b>存储保留原始裁剪分辨率</b>（仅用 {@link #STORE_MAX_DIM} 限制 PNG 体积），匹配纹理由
 * {@code QrAdBlurEffect.configure()} 按当前检测帧尺度动态生成 —— 不再在保存时一次性压到 64px，
 * 那会把文字笔画混叠成横向色带，且造成模板与检测帧尺度基准不一致。
 *
 * <p>保存的 Bitmap 必须是「GL 朝向」（与视频帧纹理同坐标系，见 {@code QrAdBlurEffect} 的截图
 * 捕获），归一化矩形同样使用 GL 系：x 从左、y 从画面底部向上，取值 [0,1]。
 */
public class AdBlurTemplates {

    private static final String DIR = "adblur_templates";

    /** 存储最长边上限：仅控制 PNG 体积，不是匹配纹理尺寸。 */
    public static final int STORE_MAX_DIM = 512;

    /** @deprecated 旧语义（GPU 匹配上限）已废弃；保留仅为兼容外部引用。 */
    @Deprecated public static final int MAX_DIM = STORE_MAX_DIM;

    private static final long MAX_BYTES = 4 * 1024 * 1024; // 单文件上限，防滥用

    /**
     * 采集标定信息：裁剪像素尺寸 + GL 系归一化矩形 + 视频内容区域。
     *
     * <p>{@link #content} 是「视频实际内容在采集截图里」的归一化矩形 {cx, cy, cw, ch}（GL 系）。
     * 播放器可能 fit 留黑边、拉伸填满或裁剪填满，只有按实测内容区换算，模板 ROI 与模糊框
     * 才能落在视频帧坐标系里。null 表示旧格式（v1），检测侧会按尺寸比推算兼容。
     */
    public static final class Meta {
        public final int cropW, cropH;
        public final float x, y, w, h;
        @Nullable public final float[] content;

        public Meta(int cropW, int cropH, float x, float y, float w, float h) {
            this(cropW, cropH, x, y, w, h, null);
        }

        public Meta(
                int cropW,
                int cropH,
                float x,
                float y,
                float w,
                float h,
                @Nullable float[] content) {
            this.cropW = cropW;
            this.cropH = cropH;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.content =
                    (content != null && content.length == 4) ? content.clone() : null;
        }

        /** 单行文本，空格分隔，Float.toString 避免 Locale 小数点差异。 */
        public String encode() {
            StringBuilder s = new StringBuilder();
            s.append(content == null ? "1 " : "2 ")
                    .append(cropW)
                    .append(' ')
                    .append(cropH)
                    .append(' ')
                    .append(Float.toString(x))
                    .append(' ')
                    .append(Float.toString(y))
                    .append(' ')
                    .append(Float.toString(w))
                    .append(' ')
                    .append(Float.toString(h));
            if (content != null) {
                for (float v : content) s.append(' ').append(Float.toString(v));
            }
            return s.toString();
        }

        @Nullable
        public static Meta decode(String raw) {
            if (raw == null) return null;
            String[] p = raw.trim().split("\\s+");
            if (p.length < 7) return null;
            boolean v2 = "2".equals(p[0]);
            if (!v2 && !"1".equals(p[0])) return null;
            try {
                int cw = Integer.parseInt(p[1]);
                int ch = Integer.parseInt(p[2]);
                float x = Float.parseFloat(p[3]);
                float y = Float.parseFloat(p[4]);
                float w = Float.parseFloat(p[5]);
                float h = Float.parseFloat(p[6]);
                if (cw < 2 || ch < 2 || w <= 0 || h <= 0) return null;
                if (w > 1.001f || h > 1.001f) return null;
                if (x < -0.001f || y < -0.001f || x + w > 1.001f || y + h > 1.001f) return null;
                float[] content = null;
                if (v2) {
                    if (p.length < 11) return null;
                    content =
                            new float[] {
                                Float.parseFloat(p[7]),
                                Float.parseFloat(p[8]),
                                Float.parseFloat(p[9]),
                                Float.parseFloat(p[10])
                            };
                    if (content[2] <= 0.05f || content[3] <= 0.05f) return null;
                    if (content[2] > 1.001f || content[3] > 1.001f) return null;
                }
                return new Meta(cw, ch, x, y, w, h, content);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

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

    /** 保存特征图（crop 须为 GL 朝向）。无标定信息时走全帧搜索兼容路径。 */
    @Nullable
    public static String save(Context c, Bitmap crop, @Nullable String label) {
        return save(c, crop, label, null);
    }

    /**
     * 保存特征图并附带标定信息。返回文件名，失败返回 null。
     *
     * @param meta 采集时的归一化矩形；为 null 时（相册/旧同步导入）不写 .meta，
     *             检测侧自动退回全帧搜索。
     */
    @Nullable
    public static String save(Context c, Bitmap crop, @Nullable String label, @Nullable Meta meta) {
        if (crop == null || crop.isRecycled()) return null;
        File d = dirFile(c);
        float scale =
                Math.min(1f, (float) STORE_MAX_DIM / Math.max(crop.getWidth(), crop.getHeight()));
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
            if (meta != null) writeMeta(c, name, meta.encode());
            return f.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取同名 .meta；不存在或格式非法返回 null（=> 全帧搜索兼容路径）。 */
    @Nullable
    public static Meta readMeta(Context c, String pngName) {
        File f = metaFile(c, pngName);
        if (!f.exists() || f.length() > 4096) return null;
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            return Meta.decode(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** 原样写入 .meta 文本（供配置同步还原）。 */
    public static void writeMeta(Context c, String pngName, String raw) {
        if (pngName == null || raw == null) return;
        try (FileOutputStream os = new FileOutputStream(metaFile(c, pngName))) {
            os.write(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
            // 元数据写入失败不致命，退化为全帧搜索
        }
    }

    private static File metaFile(Context c, String pngName) {
        String base =
                pngName.endsWith(".png")
                        ? pngName.substring(0, pngName.length() - 4)
                        : pngName;
        return new File(dir(c), sanitize(base) + ".meta");
    }

    /**
     * 文件名白名单。<b>必须允许 '.'</b>：list() 返回的是完整 {@code tpl_xxx.png}，
     * 早期版本把点号替换成下划线，导致 load()/delete() 永远找不到磁盘文件，
     * GPU 侧模板数恒为 0、检测循环从不执行。
     */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 64; i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)
                    || ch == '_'
                    || ch == '-'
                    || ch == '.'
                    || (ch >= '一' && ch <= '鿿')) {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString().replace("..", "_"); // 防路径穿越
        return out.isEmpty() ? "tpl" : out;
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

    /**
     * 清理 0.5.19 之前遗留的旧模板：当时保存时会把图压到最长边 64px 且没有 .meta，
     * 既无法定位、又会在全帧乱匹配（误识别到别的画面元素）。
     *
     * <p>只删「无 .meta 且最长边 ≤ 64」的文件；新采集模板带 .meta，
     * 相册导入的图最长边 > 64，两者都保留。幂等，每次进程启动调用一次。
     */
    public static int purgeLegacy(Context c) {
        int removed = 0;
        for (String name : list(c)) {
            if (readMeta(c, name) != null) continue;
            File f = new File(dir(c), sanitize(name));
            if (!f.exists()) continue;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            if (opt.outWidth <= 0 || opt.outHeight <= 0) continue;
            if (Math.max(opt.outWidth, opt.outHeight) > 64) continue;
            if (f.delete()) {
                removed++;
                android.util.Log.i(
                        "AdBlur",
                        "purged legacy template: "
                                + name
                                + " "
                                + opt.outWidth
                                + "x"
                                + opt.outHeight);
            }
        }
        return removed;
    }

    /** 解码指定模板为 GL 朝向 Bitmap（不翻转）。不存在返回 null。 */
    @Nullable
    public static Bitmap load(Context c, String name) {
        File f = new File(dir(c), sanitize(name));
        if (!f.exists() || f.length() > MAX_BYTES) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    public static void delete(Context c, String name) {
        String n = sanitize(name);
        File f = new File(dir(c), n);
        if (f.exists()) f.delete();
        File m = metaFile(c, n);
        if (m.exists()) m.delete();
    }

    public static void clear(Context c) {
        File d = dir(c);
        if (d.exists()) {
            File[] files = d.listFiles();
            // 同时清掉 .meta（listFiles 返回目录下全部文件）
            if (files != null) for (File f : files) f.delete();
        }
    }
}
