package com.pilipala.marcomovie;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.pilipala.marcomovie.adblur.AdBlurConfig;
import com.pilipala.marcomovie.adblur.AdBlurTemplates;
import com.pilipala.marcomovie.adskip.AdSkipConfig;
import com.pilipala.marcomovie.safety.ContentFilterConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置快照：手机把本地设置打包成 JSON（含特征图），电视接收后整体写入。
 *
 * <p>同步范围（手机 → 电视）：
 * <ul>
 *   <li>adblur：牛皮癣模糊开关与各参数；</li>
 *   <li>adskip：口播广告关键词 / 灵敏度 / 跳过时长；</li>
 *   <li>safety：NSFW 过滤开关与额外关键词（不含 PIN，PIN 属设备级密钥，不跨设备同步）；</li>
 *   <li>prefs：通用界面 / 播放设置（仅 {@link Prefs#KEYS} 白名单内的键，带类型）；</li>
 *   <li>templates：用户框选的特征图（PNG 转 base64，先清空电视旧模板再写入）。</li>
 * </ul>
 *
 * <p>不含：影视源 / 站点选择 / 历史记录 / 账号类数据。
 */
final class ConfigSyncData {

    static final int VERSION = 1;

    private ConfigSyncData() {}

    /** 从当前设备（手机）构建完整快照。 */
    static JSONObject build(Context ctx) throws Exception {
        ctx = ctx.getApplicationContext();
        JSONObject root = new JSONObject();
        root.put("v", VERSION);

        JSONObject adblur = new JSONObject();
        adblur.put("enabled", AdBlurConfig.isEnabled());
        adblur.put("blur_radius", AdBlurConfig.getBlurRadius());
        adblur.put("blur_style", AdBlurConfig.getBlurStyle());
        adblur.put("min_confidence", AdBlurConfig.getMinConfidence());
        adblur.put("detect_every_n", AdBlurConfig.getDetectEveryN()); // 兼容旧电视
        adblur.put("detect_interval_ms", AdBlurConfig.getDetectIntervalMs());
        adblur.put("match_threshold", AdBlurConfig.getMatchThreshold());
        adblur.put("scale_max", AdBlurConfig.getScaleMax());
        adblur.put("feather", AdBlurConfig.getFeather());
        root.put("adblur", adblur);

        JSONObject adskip = new JSONObject();
        adskip.put("skip_seconds", AdSkipConfig.getSkipSeconds());
        adskip.put("sensitivity", AdSkipConfig.getSensitivity());
        adskip.put("keywords_raw", AdSkipConfig.getKeywordsRaw());
        root.put("adskip", adskip);

        JSONObject safety = new JSONObject();
        safety.put("enabled", ContentFilterConfig.enabled());
        safety.put("extra_keywords", ContentFilterConfig.getExtraKeywordsRaw());
        root.put("safety", safety);

        // 通用设置：仅白名单内、且带明确类型的键
        JSONObject prefs = new JSONObject();
        SharedPreferences sp = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        for (String key : Prefs.KEYS) {
            Object v = all.get(key);
            if (v == null) continue;
            JSONObject item = new JSONObject();
            if (v instanceof Boolean) {
                item.put("t", "b");
                item.put("v", (Boolean) v);
            } else if (v instanceof Integer) {
                item.put("t", "i");
                item.put("v", (Integer) v);
            } else if (v instanceof Long) {
                item.put("t", "l");
                item.put("v", (Long) v);
            } else if (v instanceof Float) {
                item.put("t", "f");
                item.put("v", (double) (float) (Float) v);
            } else if (v instanceof String) {
                item.put("t", "s");
                item.put("v", (String) v);
            } else {
                continue;
            }
            prefs.put(key, item);
        }
        root.put("prefs", prefs);

        // 特征图：PNG 读原字节再 base64（避免重复解码 / 方向问题）
        JSONArray tpls = new JSONArray();
        List<String> names = AdBlurTemplates.list(ctx);
        for (String name : names) {
            File f = new File(AdBlurTemplates.dirFile(ctx), name);
            if (!f.exists()) continue;
            byte[] bytes = readAll(f);
            JSONObject t = new JSONObject();
            t.put("name", name);
            t.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            AdBlurTemplates.Meta meta = AdBlurTemplates.readMeta(ctx, name);
            if (meta != null) t.put("meta", meta.encode());
            tpls.put(t);
        }
        root.put("templates", tpls);
        return root;
    }

    /** 把快照应用到当前设备（电视）。返回实际写入的条数。 */
    static int apply(Context ctx, String json) throws Exception {
        ctx = ctx.getApplicationContext();
        JSONObject root = new JSONObject(json);
        int count = 0;

        if (root.has("adblur")) {
            JSONObject a = root.getJSONObject("adblur");
            if (a.has("enabled")) AdBlurConfig.setEnabled(a.getBoolean("enabled"));
            if (a.has("blur_radius")) AdBlurConfig.setBlurRadius(a.getInt("blur_radius"));
            if (a.has("blur_style")) AdBlurConfig.setBlurStyle(a.getInt("blur_style"));
            if (a.has("min_confidence"))
                AdBlurConfig.setMinConfidence(a.getInt("min_confidence"));
            if (a.has("detect_interval_ms")) {
                AdBlurConfig.setDetectIntervalMs(a.getInt("detect_interval_ms"));
            } else if (a.has("detect_every_n")) {
                // 旧快照按帧数存储，按 30fps 近似换算成 ms
                AdBlurConfig.setDetectIntervalMs(
                        Math.max(100, a.getInt("detect_every_n") * 1000 / 30));
            }
            if (a.has("match_threshold"))
                AdBlurConfig.setMatchThreshold((float) a.getDouble("match_threshold"));
            if (a.has("scale_max")) AdBlurConfig.setScaleMax(a.getInt("scale_max"));
            if (a.has("feather")) AdBlurConfig.setFeather((float) a.getDouble("feather"));
            count++;
        }
        if (root.has("adskip")) {
            JSONObject a = root.getJSONObject("adskip");
            if (a.has("skip_seconds")) AdSkipConfig.setSkipSeconds(a.getInt("skip_seconds"));
            if (a.has("sensitivity")) AdSkipConfig.setSensitivity(a.getInt("sensitivity"));
            if (a.has("keywords_raw")) AdSkipConfig.setKeywordsRaw(a.getString("keywords_raw"));
            count++;
        }
        if (root.has("safety")) {
            JSONObject a = root.getJSONObject("safety");
            if (a.has("enabled")) ContentFilterConfig.setEnabled(a.getBoolean("enabled"));
            if (a.has("extra_keywords"))
                ContentFilterConfig.setExtraKeywordsRaw(a.getString("extra_keywords"));
            count++;
        }
        if (root.has("prefs")) {
            JSONObject p = root.getJSONObject("prefs");
            SharedPreferences sp = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            Set<String> allowed = new HashSet<>(Arrays.asList(Prefs.KEYS));
            Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (!allowed.contains(key)) continue;
                JSONObject item = p.getJSONObject(key);
                String t = item.optString("t", "");
                switch (t) {
                    case "b":
                        ed.putBoolean(key, item.getBoolean("v"));
                        break;
                    case "i":
                        ed.putInt(key, item.getInt("v"));
                        break;
                    case "l":
                        ed.putLong(key, item.getLong("v"));
                        break;
                    case "f":
                        ed.putFloat(key, (float) item.getDouble("v"));
                        break;
                    case "s":
                        ed.putString(key, item.getString("v"));
                        break;
                    default:
                        continue;
                }
                count++;
            }
            ed.apply();
        }
        if (root.has("templates")) {
            JSONArray tpls = root.getJSONArray("templates");
            AdBlurTemplates.clear(ctx);
            for (int i = 0; i < tpls.length(); i++) {
                JSONObject t = tpls.getJSONObject(i);
                String name = t.optString("name", "tpl");
                if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
                String data = t.optString("data", "");
                if (data.isEmpty()) continue;
                byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) continue;
                String saved = AdBlurTemplates.save(ctx, bmp, name);
                if (saved != null) {
                    String meta = t.optString("meta", "");
                    if (!meta.isEmpty()) AdBlurTemplates.writeMeta(ctx, saved, meta);
                }
                bmp.recycle();
                count++;
            }
        }
        return count;
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
