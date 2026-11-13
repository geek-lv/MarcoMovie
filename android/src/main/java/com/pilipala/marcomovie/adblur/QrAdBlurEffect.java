package com.pilipala.marcomovie.adblur;

import static androidx.media3.common.util.GlUtil.clearFocusedBuffers;
import static androidx.media3.common.util.GlUtil.createFboForTexture;
import static androidx.media3.common.util.GlUtil.createTexture;
import static androidx.media3.common.util.GlUtil.deleteFbo;
import static androidx.media3.common.util.GlUtil.deleteTexture;
import static androidx.media3.common.util.GlUtil.focusFramebufferUsingCurrentContext;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频「牛皮癣」广告（贴片水印）实时模糊特效。
 *
 * <p>作为 Media3 {@link GlEffect} 接入播放器的视频后处理链（{@code ExoPlayer.setVideoEffects}）：
 * <ol>
 *   <li><b>标定</b>：用户框选时把归一化矩形写入同目录 {@code .meta}。检测时只在矩形 ±ROI 邻域内
 *       搜索，定位误差为 0、搜索空间缩小约 95%。模板纹理按当前检测帧尺度动态生成，
 *       与帧共用同一个 {@code detectScale}，彻底消除两套缩放基准失配。</li>
 *   <li><b>检测</b>：降采样帧在 GPU 上算梯度域 ZNCC（抗亮度/半透明/背景变化），
 *       输出一张仅几 KB 的「失配度图」，只回读该图到 CPU 做 argmin；默认 500ms（2Hz）一次，
 *       跨帧投票锁定，其余帧复用上一帧矩形。</li>
 *   <li><b>模糊</b>：矩形内两遍分离高斯（H→临时 FBO→V），矩形外透传，边缘羽化；
 *       每帧都画，检测只更新矩形，因此低频检测不会造成闪烁。</li>
 *   <li><b>特征图捕获</b>：{@link #requestCapture(CaptureListener)} 触发后把当前帧读回 GL 朝向
 *       Bitmap 交给 UI 框选 —— 与检测同坐标系。</li>
 * </ol>
 *
 * <p>注：HDR 帧经此特效会走 8-bit 合成，可能存在轻微色偏；开启后 HDR 透传降级，属已知权衡。
 */
@UnstableApi
public final class QrAdBlurEffect implements GlEffect {

    /** 捕获回调：在下一帧把当前画面（GL 朝向 Bitmap）交给调用方做框选。 */
    public interface CaptureListener {
        /** @param glBitmap 行序为 GL 朝向（行 0 = 画面底部）；显示需竖直翻转，保存为模板则保持原样。 */
        void onCaptured(Bitmap glBitmap);
    }

    private final Context appContext;
    @Nullable private QrAdBlurShaderProgram program;
    @Nullable private CaptureListener captureListener;

    public QrAdBlurEffect(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 设置截图捕获回调（供 UI 框选特征图）。 */
    public void setCaptureListener(@Nullable CaptureListener l) {
        this.captureListener = l;
        if (program != null) program.setCaptureListener(l);
    }

    /** 已处理的视频帧计数（供播放器侧看门狗判断特效管线是否在产出帧）。 */
    public long processedFrames() {
        QrAdBlurShaderProgram p = program;
        return p == null ? -1 : p.producedFrames();
    }

    /** 请求在下一帧捕获当前播放画面（GL 朝向 Bitmap），结果经 {@link CaptureListener} 回传。 */
    public void requestCapture() {
        if (program != null) program.requestCapture();
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
            throws androidx.media3.common.VideoFrameProcessingException {
        program = new QrAdBlurShaderProgram(appContext, captureListener);
        return program;
    }

    /** 内部 GPU 程序：BaseGlShaderProgram 负责输出纹理池、FBO 绑定与输入/输出帧流转。 */
    @UnstableApi
    private static final class QrAdBlurShaderProgram extends BaseGlShaderProgram {
        private static final int MAX_TMPL = 8; // 最多加载的特征图数
        private static final int CAP_MAX = 360; // 截图捕获最大边长（供框选精度）
        private static final int MAXT = 64; // 与 MATCH_FRAG 的循环上界一致
        private static final float ROI_MARGIN = 0.03f; // 标定矩形外扩（占整帧比例）
        private static final int MISS_TO_RELEASE = 2; // 连续 2 次低置信度即释放（1s @500ms）
        private static final int ARM_STREAK = 2; // 连续 2 次高置信度才启用模糊

        // 全屏四边形（TRIANGLE_STRIP），aPosition 与 aTextureCoord 共用同一套坐标
        private static final float[] POS = {-1, -1, 1, -1, -1, 1, 1, 1};
        private static final float[] TEX = {0, 0, 1, 0, 0, 1, 1, 1};

        private static final String VERT =
                ""
                        + "attribute vec4 aPosition;\n"
                        + "attribute vec2 aTextureCoord;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "  gl_Position = aPosition;\n"
                        + "  vTexCoord = aTextureCoord;\n"
                        + "}\n";

        // 直通拷贝（降采样 / 透传）
        private static final String COPY_FRAG =
                ""
                        + "precision mediump float;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "uniform sampler2D uTex;\n"
                        + "void main() {\n"
                        + "  gl_FragColor = texture2D(uTex, vTexCoord);\n"
                        + "}\n";

        // 梯度域加权 ZNCC 模板匹配：输出失配度 (1-NCC)/2（R 通道，[0,1]，越低越像）。
        // uTmpl.R = 模板水平梯度，uTmpl.G = 前景权重（文字边缘高、背景低）。
        // 广告是固定文字叠在变化背景上：只看模板文字边缘做 ZNCC，
        // 背景变化就不会把相关系数拉下来；保留 0.2 权重地板以免样本过少失真。
        // uSearchOrigin / uScoreSize 把滑窗限制在标定 ROI 内，分数图只覆盖 ROI。
        private static final String MATCH_FRAG =
                ""
                        + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                        + "precision highp float;\n"
                        + "#else\n"
                        + "precision mediump float;\n"
                        + "#endif\n"
                        + "varying vec2 vTexCoord;\n"
                        + "uniform sampler2D uInput;\n"
                        + "uniform sampler2D uTmpl;\n"
                        + "uniform vec2 uFrameSize;\n"
                        + "uniform vec2 uScoreSize;\n"
                        + "uniform vec2 uSearchOrigin;\n"
                        + "uniform vec2 uTmplSize;\n"
                        + "const int MAXT = 64;\n"
                        + "const vec3 LUMA = vec3(0.299, 0.587, 0.114);\n"
                        + "float lum(vec2 uv) {\n"
                        + "  return dot(texture2D(uInput, uv).rgb, LUMA);\n"
                        + "}\n"
                        + "float frameFeat(vec2 uv, vec2 dx) {\n"
                        + "  return abs(lum(uv + dx) - lum(uv));\n"
                        + "}\n"
                        + "void main() {\n"
                        + "  vec2 scorePx = min(floor(vTexCoord * uScoreSize), uScoreSize - 1.0);\n"
                        + "  vec2 base = (uSearchOrigin + scorePx + 0.5) / uFrameSize;\n"
                        + "  vec2 texel = 1.0 / uFrameSize;\n"
                        + "  vec2 dx = vec2(texel.x, 0.0);\n"
                        + "  float sumW = 0.0, sumF = 0.0, sumT = 0.0;\n"
                        + "  float sumFF = 0.0, sumTT = 0.0, sumFT = 0.0;\n"
                        + "  for (int j = 0; j < MAXT; j++) {\n"
                        + "    if (j >= int(uTmplSize.y)) break;\n"
                        + "    for (int i = 0; i < MAXT; i++) {\n"
                        + "      if (i >= int(uTmplSize.x)) break;\n"
                        + "      vec2 ij = vec2(float(i), float(j));\n"
                        + "      vec2 fuv = base + ij * texel;\n"
                        + "      vec2 tuv = (ij + 0.5) / uTmplSize;\n"
                        + "      vec4 ts = texture2D(uTmpl, tuv);\n"
                        + "      float t = ts.r;\n"
                        + "      float w = 0.2 + 0.8 * ts.g;\n"
                        + "      float f = frameFeat(fuv, dx);\n"
                        + "      sumW += w;\n"
                        + "      sumF += w * f; sumT += w * t;\n"
                        + "      sumFF += w * f * f; sumTT += w * t * t; sumFT += w * f * t;\n"
                        + "    }\n"
                        + "  }\n"
                        + "  float invW = 1.0 / max(sumW, 1e-4);\n"
                        + "  float meanF = sumF * invW, meanT = sumT * invW;\n"
                        + "  float varF = max(sumFF * invW - meanF * meanF, 0.0);\n"
                        + "  float varT = max(sumTT * invW - meanT * meanT, 0.0);\n"
                        + "  float cov = sumFT * invW - meanF * meanT;\n"
                        + "  float ncc = clamp(cov / (sqrt(varF * varT) + 1e-5), -1.0, 1.0);\n"
                        + "  gl_FragColor = vec4((1.0 - ncc) * 0.5, 0.0, 0.0, 1.0);\n"
                        + "}\n";

        // 分离高斯（沿 uDir 方向 9 抽头），H 与 V 共用此 shader
        // 双线性加权 5 次采样等效 9-tap 高斯。采样步长由 CPU 端限制在数像素级，
        // 多轮不同尺度迭代叠加出大半径；避免「一步 18px」那种欠采样造成的条纹 / 网格。
        private static final String BLUR_FRAG =
                ""
                        + "precision mediump float;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "uniform sampler2D uTex;\n"
                        + "uniform vec2 uDir;\n"
                        + "void main() {\n"
                        + "  vec3 s = texture2D(uTex, vTexCoord).rgb * 0.2270270270;\n"
                        + "  s += texture2D(uTex, vTexCoord + uDir * 1.3846153846).rgb * 0.3162162162;\n"
                        + "  s += texture2D(uTex, vTexCoord - uDir * 1.3846153846).rgb * 0.3162162162;\n"
                        + "  s += texture2D(uTex, vTexCoord + uDir * 3.2307692308).rgb * 0.0702702703;\n"
                        + "  s += texture2D(uTex, vTexCoord - uDir * 3.2307692308).rgb * 0.0702702703;\n"
                        + "  gl_FragColor = vec4(s, 1.0);\n"
                        + "}\n";

        // 合成：模糊矩形按 uPad 四边外扩，羽化带再向外扩 uFeatherPx（等像素宽度）。
        // 矩形内及边界 blend=1 全模糊，只有矩形外才渐变到清晰 —— 不会再把四边吃掉一圈。
        private static final String MIX_FRAG =
                ""
                        + "precision mediump float;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "uniform sampler2D uSharp;\n"
                        + "uniform sampler2D uBlurred;\n"
                        + "uniform vec4 uRect;\n"
                        + "uniform vec2 uPad;\n"
                        + "uniform vec2 uFeatherPx;\n"
                        + "uniform vec2 uBlock;\n"
                        + "uniform float uStyle;\n"
                        + "void main() {\n"
                        + "  vec3 sharp = texture2D(uSharp, vTexCoord).rgb;\n"
                        + "  vec3 blurred = texture2D(uBlurred, vTexCoord).rgb;\n"
                        + "  vec2 uvM = (floor(vTexCoord / uBlock) + 0.5) * uBlock;\n"
                        + "  vec3 mosaic = texture2D(uSharp, uvM).rgb;\n"
                        + "  vec3 eff = mix(blurred, mosaic, uStyle);\n"
                        + "  float x0 = uRect.x - uPad.x;\n"
                        + "  float x1 = uRect.x + uRect.z + uPad.x;\n"
                        + "  float y0 = uRect.y - uPad.y;\n"
                        + "  float y1 = uRect.y + uRect.w + uPad.y;\n"
                        + "  float dx = min(vTexCoord.x - x0, x1 - vTexCoord.x) / uFeatherPx.x;\n"
                        + "  float dy = min(vTexCoord.y - y0, y1 - vTexCoord.y) / uFeatherPx.y;\n"
                        + "  float edge = min(dx, dy);\n"
                        + "  float blend = smoothstep(-1.0, 0.0, edge);\n"
                        + "  gl_FragColor = vec4(mix(sharp, eff, blend), 1.0);\n"
                        + "}\n";

        private final Context appContext;
        @Nullable private volatile CaptureListener captureListener;
        private volatile boolean captureRequested = false;

        // 程序
        private GlProgram copyProg;
        private GlProgram matchProg;
        private GlProgram blurProg;
        private GlProgram mixProg;

        // 模板纹理（按当前 detectScale 动态生成的梯度特征图）
        private final int[] tmplTex = new int[MAX_TMPL];
        /** 同模板的竖直翻转特征图：兼容「屏幕采集帧(显示朝向) vs 视频纹理(GL 朝向)」Y 轴约定差异。 */
        private final int[] tmplTexFlip = new int[MAX_TMPL];
        private final int[] tmplW = new int[MAX_TMPL];
        private final int[] tmplH = new int[MAX_TMPL];
        private final boolean[] tmplHasRect = new boolean[MAX_TMPL];
        private final float[] tmplRx = new float[MAX_TMPL];
        private final float[] tmplRy = new float[MAX_TMPL];
        private final float[] tmplRw = new float[MAX_TMPL];
        private final float[] tmplRh = new float[MAX_TMPL];
        private int tmplCount = 0;
        private boolean templatesReady = false;
        /** 模板与检测帧共用的缩放比例，由 configure() 按标定矩形与 MAXT 共同决定。 */
        private float detectScale = 1f;

        // 帧尺寸 / 离屏缓冲
        private int outputW;
        private int outputH;
        private int scaledW;
        private int scaledH;
        private int scaledTex;
        private int scaledFbo;
        private int scoreW;
        private int scoreH;
        private int scoreTex;
        private int scoreFbo;
        private int tempATex;
        private int tempAFbo;
        private int tempBTex;
        private int tempBFbo;
        private int capMax = CAP_MAX;
        private int capW;
        private int capH;
        private int capTex;
        private int capFbo;

        // 检测 / 平滑状态
        private int frameIndex = 0;
        private String loadedSig = ""; // 已加载模板的文件签名，用于热重载
        @Nullable private float[] lastRect = null; // x,y,w,h 归一化(GL 空间)
        private long lastDetectMs = 0L; // 2Hz 时间节流
        private int hitStreak = 0;
        private int missStreak = 0;
        private int highStreak = 0; // 连续高置信度命中次数
        private int lowStreak = 0; // 连续低置信度次数
        private int lastCx = -1, lastCy = -1; // 上次最佳位置，用于帧间稳定性判据
        private int detectCount = 0; // 诊断日志节流

        private ByteBuffer scoreBuf = null;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        QrAdBlurShaderProgram(Context appContext, @Nullable CaptureListener listener) {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.appContext = appContext.getApplicationContext();
            this.captureListener = listener;
        }

        void setCaptureListener(@Nullable CaptureListener l) {
            this.captureListener = l;
        }

        void requestCapture() {
            this.captureRequested = true;
        }

        @Override
        public Size configure(int inputWidth, int inputHeight)
                throws androidx.media3.common.VideoFrameProcessingException {
            try {
                this.outputW = inputWidth;
                this.outputH = inputHeight;
                copyProg = new GlProgram(VERT, COPY_FRAG);
                matchProg = new GlProgram(VERT, MATCH_FRAG);
                blurProg = new GlProgram(VERT, BLUR_FRAG);
                mixProg = new GlProgram(VERT, MIX_FRAG);
                setQuad(copyProg);
                setQuad(matchProg);
                setQuad(blurProg);
                setQuad(mixProg);

                // 先按标定信息定出全局 detectScale，再分配降采样缓冲并生成模板纹理：
                // 模板与帧共用同一比例，且模板长边必不超过 MAXT。
                float s =
                        (float) Math.max(64, AdBlurConfig.getScaleMax())
                                / Math.max(inputWidth, inputHeight);
                List<String> names = AdBlurTemplates.list(appContext);
                for (String n : names) {
                    AdBlurTemplates.Meta m = AdBlurTemplates.readMeta(appContext, n);
                    if (m == null) continue;
                    float targetW = Math.max(2f, m.w * inputWidth);
                    float targetH = Math.max(2f, m.h * inputHeight);
                    s = Math.min(s, (float) MAXT / Math.max(targetW, targetH));
                }
                detectScale = Math.max(1f / 4096f, s);
                allocScaled(inputWidth, inputHeight);
                loadTemplates();
                loadedSig = names.size() + ":" + TextUtils.join(",", names);
                allocTemp(inputWidth, inputHeight);
                allocCapture(inputWidth, inputHeight);
                lastRect = null;
                hitStreak = 0;
                missStreak = 0;
                highStreak = 0;
                lowStreak = 0;
                lastCx = -1;
                lastCy = -1;
                detectCount = 0;
                lastDetectMs = 0L;
                frameIndex = 0;
                return new Size(inputWidth, inputHeight);
            } catch (GlUtil.GlException e) {
                throw new androidx.media3.common.VideoFrameProcessingException(e);
            }
        }

        private void setQuad(GlProgram p) throws GlUtil.GlException {
            p.use();
            p.setBufferAttribute("aPosition", POS, 2);
            p.setBufferAttribute("aTextureCoord", TEX, 2);
        }

        /** orient: bit0=模板竖直翻转, bit1=ROI 竖直镜像。t/T + r/R 表示组合。 */
        private static String orientName(int o) {
            return "" + ((o & 1) != 0 ? 'T' : 't') + ((o & 2) != 0 ? 'R' : 'r');
        }

        private void loadTemplates() throws GlUtil.GlException {
            templatesReady = false;
            for (int i = 0; i < tmplCount; i++) {
                if (tmplTex[i] != 0) deleteTexture(tmplTex[i]);
                if (tmplTexFlip[i] != 0) deleteTexture(tmplTexFlip[i]);
                tmplTex[i] = 0;
                tmplTexFlip[i] = 0;
            }
            tmplCount = 0;
            StringBuilder detail = new StringBuilder();
            List<String> names = AdBlurTemplates.list(appContext);
            for (int i = 0; i < names.size() && tmplCount < MAX_TMPL; i++) {
                String name = names.get(i);
                Bitmap src = AdBlurTemplates.load(appContext, name);
                if (src == null || src.isRecycled()) continue;
                AdBlurTemplates.Meta meta = AdBlurTemplates.readMeta(appContext, name);
                int tw, th;
                // 换算后的矩形一律是「视频帧坐标系」：检测帧与模糊矩形都基于纯视频帧，
                // 不含播放器 fit 黑边，也不管当前片源是拉伸填满还是裁剪填满。
                float rx = 0f, ry = 0f, rw = 1f, rh = 1f;
                String convTag = "";
                if (meta != null) {
                    float[] c = meta.content;
                    if (c == null) {
                        // v1 旧模板：cropW/w、cropH/h 可反推采集截图尺寸，
                        // 再按视频帧宽高比推算 fit 内容区，保证不用重采也能对齐。
                        float shotW = meta.w > 0f ? meta.cropW / meta.w : outputW;
                        float shotH = meta.h > 0f ? meta.cropH / meta.h : outputH;
                        float va = (float) outputW / outputH;
                        float sa = shotW / shotH;
                        if (va < sa) {
                            float dispW = shotH * va;
                            c =
                                    new float[] {
                                        (shotW - dispW) / (2f * shotW), 0f, dispW / shotW, 1f
                                    };
                        } else {
                            float dispH = shotW / va;
                            c =
                                    new float[] {
                                        0f, (shotH - dispH) / (2f * shotH), 1f, dispH / shotH
                                    };
                        }
                        convTag = "fit";
                    } else {
                        convTag = "measured";
                    }
                    rx = clamp01((meta.x - c[0]) / c[2]);
                    ry = clamp01((meta.y - c[1]) / c[3]);
                    rw = clamp01(meta.w / c[2]);
                    rh = clamp01(meta.h / c[3]);
                    if (rx + rw > 1f) rw = 1f - rx;
                    if (ry + rh > 1f) rh = 1f - ry;
                    // 归一化矩形 × 当前帧尺寸 × 同一 detectScale => 尺度永远对齐
                    tw = clamp(Math.round(rw * outputW * detectScale), 2, MAXT);
                    th = clamp(Math.round(rh * outputH * detectScale), 2, MAXT);
                } else {
                    // 老模板 / 相册导入：无标定信息，保持存储尺寸并退回全帧搜索
                    tw = clamp(src.getWidth(), 2, MAXT);
                    th = clamp(src.getHeight(), 2, MAXT);
                }
                Bitmap featN = buildFeatureBitmap(src, tw, th, false);
                Bitmap featF = buildFeatureBitmap(src, tw, th, true);
                int storedW = src.getWidth(), storedH = src.getHeight();
                int idx = tmplCount;
                src.recycle();
                tmplTex[idx] = createTexture(featN);
                tmplTexFlip[idx] = createTexture(featF);
                featN.recycle();
                featF.recycle();
                tmplW[idx] = tw;
                tmplH[idx] = th;
                tmplHasRect[idx] = meta != null;
                if (meta != null) {
                    tmplRx[idx] = rx;
                    tmplRy[idx] = ry;
                    tmplRw[idx] = rw;
                    tmplRh[idx] = rh;
                }
                if (detail.length() > 0) detail.append(' ');
                detail.append(name)
                        .append(meta != null ? ":meta" : ":NOMETA")
                        .append('(')
                        .append(storedW)
                        .append('x')
                        .append(storedH)
                        .append("->")
                        .append(tw)
                        .append('x')
                        .append(th);
                if (meta != null) {
                    detail.append("@")
                            .append(String.format("%.3f,%.3f,%.3f,%.3f", meta.x, meta.y, meta.w, meta.h))
                            .append('>')
                            .append(convTag)
                            .append(String.format(":%.3f,%.3f,%.3f,%.3f", rx, ry, rw, rh));
                }
                detail.append(')');
                tmplCount++;
            }
            templatesReady = tmplCount > 0;
            android.util.Log.i(
                    "AdBlur",
                    "templates loaded: count="
                            + tmplCount
                            + " scale="
                            + String.format("%.5f", detectScale)
                            + " frame="
                            + scaledW
                            + "x"
                            + scaledH
                            + " detail=["
                            + detail
                            + "]");
        }

        /**
         * 缩放到当前检测尺度，并预计算水平梯度特征（三通道同值，防 ARGB→RGBA 映射差异）。
         * flipY=true 时行序倒转，用于覆盖「模板相对检测帧上下镜像」这一路假设。
         */
        private static Bitmap buildFeatureBitmap(Bitmap src, int tw, int th, boolean flipY) {
            Bitmap scaled =
                    (src.getWidth() == tw && src.getHeight() == th)
                            ? src
                            : Bitmap.createScaledBitmap(src, tw, th, true);
            int[] px = new int[tw * th];
            scaled.getPixels(px, 0, tw, 0, 0, tw, th);
            int[] out = new int[tw * th];
            for (int y = 0; y < th; y++) {
                int srcRow = (flipY ? th - 1 - y : y) * tw;
                int dstRow = y * tw;
                for (int x = 0; x < tw; x++) {
                    int si = srcRow + x;
                    int lc = luma(px[si]);
                    int lr = (x + 1 < tw) ? luma(px[si + 1]) : lc;
                    int g = Math.min(255, Math.abs(lr - lc));
                    // R = 梯度，G = 前景权重（软阈值，文字边缘高），B 不用。
                    int wq = (int) (255f * Math.min(1f, g / 64f));
                    out[dstRow + x] = 0xff000000 | (g << 16) | (wq << 8);
                }
            }
            Bitmap feat = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            feat.setPixels(out, 0, tw, 0, 0, tw, th);
            if (scaled != src) scaled.recycle();
            return feat;
        }

        private static int luma(int argb) {
            int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
            return (r * 77 + g * 150 + b * 29) >> 8;
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private static float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        /** 检测模板目录是否变化，变了则热重载 GPU 纹理（免重配置）。 */
        private void maybeReloadTemplates() throws GlUtil.GlException {
            List<String> names = AdBlurTemplates.list(appContext);
            String sig = names.size() + ":" + TextUtils.join(",", names);
            if (sig.equals(loadedSig)) return;
            for (int i = 0; i < tmplCount; i++) {
                if (tmplTex[i] != 0) deleteTexture(tmplTex[i]);
                if (tmplTexFlip[i] != 0) deleteTexture(tmplTexFlip[i]);
                tmplTex[i] = 0;
                tmplTexFlip[i] = 0;
            }
            tmplCount = 0;
            loadTemplates();
            loadedSig = sig;
            if (tmplCount == 0) lastRect = null;
            highStreak = 0;
            lowStreak = 0;
            lastCx = -1;
            lastCy = -1;
            android.util.Log.i(
                    "AdBlur", "templates reloaded: count=" + tmplCount + " names=[" + sig + "]");
        }

        private void allocScaled(int inW, int inH) throws GlUtil.GlException {
            scaledW = Math.max(16, Math.round(inW * detectScale));
            scaledH = Math.max(16, Math.round(inH * detectScale));
            scaledTex = createTexture(scaledW, scaledH, false);
            scaledFbo = createFboForTexture(scaledTex);
        }

        private void allocTemp(int w, int h) throws GlUtil.GlException {
            tempATex = createTexture(w, h, false);
            tempAFbo = createFboForTexture(tempATex);
            tempBTex = createTexture(w, h, false);
            tempBFbo = createFboForTexture(tempBTex);
        }

        private void allocCapture(int inW, int inH) throws GlUtil.GlException {
            float s = (float) capMax / Math.max(inW, inH);
            capW = Math.max(16, Math.round(inW * s));
            capH = Math.max(16, Math.round(inH * s));
            capTex = createTexture(capW, capH, false);
            capFbo = createFboForTexture(capTex);
        }

        private void ensureScoreFbo(int w, int h) throws GlUtil.GlException {
            if (scoreTex != 0 && scoreW == w && scoreH == h) return;
            if (scoreTex != 0) {
                deleteFbo(scoreFbo);
                deleteTexture(scoreTex);
            }
            scoreW = w;
            scoreH = h;
            scoreTex = createTexture(w, h, false);
            scoreFbo = createFboForTexture(scoreTex);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws androidx.media3.common.VideoFrameProcessingException {
            try {
                frameIndex++;
                producedFrames.incrementAndGet();

                // 基类在调用 drawFrame 前已把输出 FBO 绑定为当前帧缓冲（并已 clear）；
                // 中间的检测 / 截图步骤会切换绑定点，故先记下真正的输出 FBO，最终合成再切回。
                int outFbo = currentFbo();

                // 0) 周期性热重载模板（增删特征图免重配置生效）
                if (frameIndex % 30 == 0) {
                    try {
                        maybeReloadTemplates();
                    } catch (Throwable t) {
                        // 重载失败不致命
                    }
                }

                // 1) 截图捕获（下一帧）
                if (captureRequested && captureListener != null) {
                    captureRequested = false;
                    Bitmap bmp = readFrameToBitmap(inputTexId, capW, capH);
                    if (bmp != null) {
                        final Bitmap fb = bmp;
                        final CaptureListener l = captureListener;
                        mainHandler.post(() -> l.onCaptured(fb));
                    }
                }

                // 2) 模板检测：按真实时间 500ms 节流（2Hz），不受片源帧率影响
                boolean enabled = AdBlurConfig.isEnabled();
                if (!enabled) {
                    lastRect = null;
                    hitStreak = 0;
                    missStreak = 0;
                    highStreak = 0;
                    lowStreak = 0;
                    lastCx = -1;
                    lastCy = -1;
                } else if (tmplCount > 0) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastDetectMs >= AdBlurConfig.getDetectIntervalMs()) {
                        lastDetectMs = now;
                        try {
                            detect(inputTexId);
                        } catch (Throwable t) {
                            // 检测偶发失败不中断播放，保留上一次矩形
                            android.util.Log.w("AdBlur", "detect failed", t);
                        }
                    }
                }

                // 3) 模糊（开启且有矩形）或透传（关闭 / 无矩形）
                if (enabled && lastRect != null) {
                    final int style = AdBlurConfig.getBlurStyle();
                    final boolean mosaic = style == AdBlurConfig.STYLE_MOSAIC;
                    if (!mosaic) {
                        // 柔和高斯：3 轮分离高斯，步长逐轮加倍。等效半径靠迭代累积，
                        // 单轮步长始终保持在数像素内，因此不会出现条纹 / 网格状重影。
                        float k =
                                Math.max(0.6f,
                                        Math.min(2f, (float) AdBlurConfig.getBlurRadius() / 18f));
                        float[] steps = {2f * k, 4f * k, 8f * k};
                        int srcTex = inputTexId;
                        blurProg.use();
                        for (float step : steps) {
                            // H: src -> tempA
                            focusFramebufferUsingCurrentContext(tempAFbo, outputW, outputH);
                            GLES20.glViewport(0, 0, outputW, outputH);
                            blurProg.setSamplerTexIdUniform("uTex", srcTex, 0);
                            blurProg.setFloatsUniform("uDir", new float[] {step / outputW, 0f});
                            blurProg.bindAttributesAndUniforms();
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                            // V: tempA -> tempB
                            focusFramebufferUsingCurrentContext(tempBFbo, outputW, outputH);
                            blurProg.setSamplerTexIdUniform("uTex", tempATex, 0);
                            blurProg.setFloatsUniform("uDir", new float[] {0f, step / outputH});
                            blurProg.bindAttributesAndUniforms();
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                            srcTex = tempBTex;
                        }
                    }

                    // 合成: sharp + effect -> outFbo
                    // 马赛克样式会跳过上面的模糊 pass，故此处必须显式复位视口，
                    // 否则沿用上一次检测 FBO 的小视口，只会画到左上角（表现为黑屏）。
                    focusFramebufferUsingCurrentContext(outFbo, outputW, outputH);
                    GLES20.glViewport(0, 0, outputW, outputH);
                    mixProg.use();
                    mixProg.setSamplerTexIdUniform("uSharp", inputTexId, 0);
                    mixProg.setSamplerTexIdUniform(
                            "uBlurred", mosaic ? inputTexId : tempBTex, 1);
                    float[] r = lastRect;
                    float shortSide = Math.min(outputW, outputH);
                    // 羽化宽度按短边比例定义，换算成各轴归一化值 => x/y 实际像素宽度一致
                    float fNorm = Math.max(AdBlurConfig.getFeather(), 1f / shortSide);
                    mixProg.setFloatsUniform(
                            "uFeatherPx",
                            new float[] {
                                fNorm * shortSide / outputW, fNorm * shortSide / outputH
                            });
                    // 广告框四边外扩：按模板框自身尺寸自适应，至少 1 像素，最多 2% 画幅
                    float padX = Math.min(0.02f, Math.max(r[2] * 0.06f, 1f / outputW));
                    float padY = Math.min(0.02f, Math.max(r[3] * 0.10f, 1f / outputH));
                    mixProg.setFloatsUniform("uPad", new float[] {padX, padY});
                    mixProg.setFloatsUniform(
                            "uBlock", new float[] {14f / outputW, 14f / outputH});
                    mixProg.setFloatUniform("uStyle", mosaic ? 1f : 0f);
                    mixProg.setFloatsUniform(
                            "uRect", new float[] {r[0], r[1], r[2], r[3]});
                    mixProg.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                } else {
                    focusFramebufferUsingCurrentContext(outFbo, outputW, outputH);
                    // 关键：透传前必须复位视口。检测（scaled/score FBO）与截图路径都会把
                    // glViewport 改成小尺寸；若此处不复位，透传只会把画面画进左上角小块，
                    // 其余全黑（表现为「有声音但黑屏」）。
                    GLES20.glViewport(0, 0, outputW, outputH);
                    copyProg.use();
                    copyProg.setSamplerTexIdUniform("uTex", inputTexId, 0);
                    copyProg.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                }

                // 健康探针（约每 3 秒）：输入有内容但输出持续全黑（HDR / 数据空间异常片源在
                // 部分设备上会让管线静默输出黑画面）→ 自动停用特效并热卸载，保证播放可见。
                if (frameIndex % 90 == 0) {
                    try {
                        probeOutputHealth(outFbo, inputTexId);
                    } catch (Throwable ignore) {
                    }
                }
            } catch (GlUtil.GlException e) {
                throw new androidx.media3.common.VideoFrameProcessingException(e);
            }
        }

        private void detect(int inputTexId) throws GlUtil.GlException {
            // 降采样当前帧 -> scaledTex
            focusFramebufferUsingCurrentContext(scaledFbo, scaledW, scaledH);
            GLES20.glViewport(0, 0, scaledW, scaledH);
            clearFocusedBuffers();
            copyProg.use();
            copyProg.setSamplerTexIdUniform("uTex", inputTexId, 0);
            copyProg.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            float threshold = AdBlurConfig.getMatchThreshold();
            float bestScore = Float.MAX_VALUE, bestMean = 0.5f;
            int bestCx = -1, bestCy = -1, bestTw = 0, bestTh = 0;
            int bestTmpl = -1, bestOrient = -1;
            detectCount++;
            StringBuilder dbg = (detectCount % 20 == 0) ? new StringBuilder() : null;

            // 模板内容与 ROI 位置是两个独立的 Y 朝向自由度：采集帧与视频纹理的约定、
            // meta 归一化 y 的约定可能各自相反。跑满 4 组合才能排除所有镜像假设。
            for (int i = 0; i < tmplCount; i++) {
                // 无 .meta 的老模板没有标定位置，只能全帧搜索；它会把框抢到画面任意
                // 位置，是「误识别到别处」的直接来源。保留加载与诊断，但不参与命中竞争。
                if (!tmplHasRect[i]) continue;
                int tw = tmplW[i], th = tmplH[i];
                for (int orient = 0; orient < 4; orient++) {
                    boolean flipTmpl = (orient & 1) != 0;
                    boolean flipRoi = (orient & 2) != 0;
                    // 无 .meta 老模板走全帧搜索，ROI 镜像是同一件事，跳过重复计算
                    if (!tmplHasRect[i] && flipRoi) continue;
                    int texId = flipTmpl ? tmplTexFlip[i] : tmplTex[i];
                    if (texId == 0) continue;
                    float ry = flipRoi ? (1f - tmplRy[i] - tmplRh[i]) : tmplRy[i];
                    int roiX, roiY, roiW, roiH;
                    if (tmplHasRect[i]) {
                        // 标定 ROI：归一化矩形外扩 ROI_MARGIN，映射回降采样帧像素（GL 系）
                        roiX = (int) Math.floor(clamp01(tmplRx[i] - ROI_MARGIN) * scaledW);
                        roiY = (int) Math.floor(clamp01(ry - ROI_MARGIN) * scaledH);
                        roiW =
                                (int) Math.ceil(clamp01(tmplRx[i] + tmplRw[i] + ROI_MARGIN) * scaledW)
                                        - roiX;
                        roiH =
                                (int) Math.ceil(clamp01(ry + tmplRh[i] + ROI_MARGIN) * scaledH)
                                        - roiY;
                        roiW = Math.min(roiW, scaledW - roiX);
                        roiH = Math.min(roiH, scaledH - roiY);
                    } else {
                        roiX = 0;
                        roiY = 0;
                        roiW = scaledW;
                        roiH = scaledH;
                    }
                    int sw = roiW - tw + 1, sh = roiH - th + 1;
                    if (sw <= 0 || sh <= 0) {
                        // 片源裁切/黑边导致 ROI 容不下模板时，放宽到全帧兜底
                        roiX = 0;
                        roiY = 0;
                        roiW = scaledW;
                        roiH = scaledH;
                        sw = scaledW - tw + 1;
                        sh = scaledH - th + 1;
                        if (sw <= 0 || sh <= 0) continue;
                    }
                    ensureScoreFbo(sw, sh);
                    focusFramebufferUsingCurrentContext(scoreFbo, sw, sh);
                    GLES20.glViewport(0, 0, sw, sh);
                    clearFocusedBuffers();
                    matchProg.use();
                    matchProg.setSamplerTexIdUniform("uInput", scaledTex, 0);
                    matchProg.setSamplerTexIdUniform("uTmpl", texId, 1);
                    matchProg.setFloatsUniform("uFrameSize", new float[] {scaledW, scaledH});
                    matchProg.setFloatsUniform("uScoreSize", new float[] {sw, sh});
                    matchProg.setFloatsUniform("uSearchOrigin", new float[] {roiX, roiY});
                    matchProg.setFloatsUniform("uTmplSize", new float[] {tw, th});
                    matchProg.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                    int n = sw * sh;
                    if (scoreBuf == null || scoreBuf.capacity() < n * 4) {
                        scoreBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
                    }
                    scoreBuf.rewind();
                    GLES20.glReadPixels(
                            0, 0, sw, sh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, scoreBuf);

                    float minV = Float.MAX_VALUE, sumV = 0f;
                    int minX = 0, minY = 0;
                    for (int p = 0; p < n; p++) {
                        int r = scoreBuf.get(p * 4) & 0xFF;
                        float v = r / 255f;
                        sumV += v;
                        if (v < minV) {
                            minV = v;
                            minX = p % sw;
                            minY = p / sw;
                        }
                    }
                    float meanV = n > 0 ? sumV / n : 0.5f;
                    if (dbg != null) {
                        dbg.append(' ')
                                .append(i)
                                .append(orientName(orient))
                                .append(':')
                                .append(String.format("%.3f", minV))
                                .append('@')
                                .append(roiX)
                                .append(',')
                                .append(roiY)
                                .append(' ')
                                .append(sw)
                                .append('x')
                                .append(sh);
                    }
                    if (minV < bestScore) {
                        bestScore = minV;
                        bestMean = meanV;
                        bestCx = roiX + minX;
                        bestCy = roiY + minY;
                        bestTw = tw;
                        bestTh = th;
                        bestTmpl = i;
                        bestOrient = orient;
                    }
                }
            }
            if (dbg != null) android.util.Log.i("AdBlur", "diag" + dbg);

            // 置信度闸门：分数够好 + 峰值显著 + 落在标定位置 + 帧间稳定，四者同时成立
            // 才允许打码。宁可不糊，也不能在没有广告的画面上糊一块。
            int level = AdBlurConfig.getMinConfidence();
            float need = level / 100f;
            float gapNeed = level >= 65 ? 0.14f : (level >= 50 ? 0.11f : 0.08f);
            float conf = 0f, gap = 0f, posConf = 0f, stabConf = 1f;
            boolean valid = bestCx >= 0 && bestTmpl >= 0 && tmplHasRect[bestTmpl];
            if (valid) {
                gap = bestMean - bestScore;
                float scoreConf = clamp01((threshold + 0.10f - bestScore) / 0.12f);
                float gapConf = clamp01(gap / gapNeed);
                boolean flipRoi = (bestOrient & 2) != 0;
                float bRy =
                        flipRoi
                                ? (1f - tmplRy[bestTmpl] - tmplRh[bestTmpl])
                                : tmplRy[bestTmpl];
                float cx = (bestCx + bestTw * 0.5f) / scaledW;
                float cy = (bestCy + bestTh * 0.5f) / scaledH;
                float ex =
                        Math.abs(cx - (tmplRx[bestTmpl] + tmplRw[bestTmpl] * 0.5f))
                                / Math.max(tmplRw[bestTmpl], 0.02f);
                float ey =
                        Math.abs(cy - (bRy + tmplRh[bestTmpl] * 0.5f))
                                / Math.max(tmplRh[bestTmpl], 0.02f);
                posConf = clamp01(1f - Math.max(ex, ey) / 0.6f);
                if (lastCx >= 0) {
                    float dx = Math.abs(bestCx - lastCx) / (float) Math.max(1, bestTw);
                    float dy = Math.abs(bestCy - lastCy) / (float) Math.max(1, bestTh);
                    stabConf = clamp01(1f - Math.max(dx, dy) / 0.5f);
                }
                lastCx = bestCx;
                lastCy = bestCy;
                conf = 0.25f * scoreConf + 0.35f * gapConf + 0.25f * posConf + 0.15f * stabConf;
            }
            boolean ok = valid && posConf >= 0.5f && conf >= need;
            // 命中框跳变：立即释放，不让 EMA 把模糊框慢慢拖到别的画面元素上
            if (ok && lastRect != null) {
                float ncx = (bestCx + bestTw * 0.5f) / scaledW;
                float ncy = (bestCy + bestTh * 0.5f) / scaledH;
                float ocx = lastRect[0] + lastRect[2] * 0.5f;
                float ocy = lastRect[1] + lastRect[3] * 0.5f;
                float jx = Math.abs(ncx - ocx) / Math.max(lastRect[2], 0.02f);
                float jy = Math.abs(ncy - ocy) / Math.max(lastRect[3], 0.02f);
                if (Math.max(jx, jy) > 0.75f) {
                    lastRect = null;
                    highStreak = 0;
                    android.util.Log.i("AdBlur", "release: position jump");
                }
            }
            if (ok) {
                lowStreak = 0;
                highStreak++;
                hitStreak++;
                missStreak = 0;
                // 连续 ARM_STREAK 次高置信度才真正启用 / 更新模糊框
                if (highStreak >= ARM_STREAK) {
                    float x = (float) bestCx / scaledW;
                    float y = (float) bestCy / scaledH;
                    float w = (float) bestTw / scaledW;
                    float h = (float) bestTh / scaledH;
                    if (lastRect == null) {
                        lastRect = new float[] {x, y, w, h};
                    } else {
                        final float k = 0.5f; // 帧间 EMA 平滑，抑制抖动
                        lastRect[0] += (x - lastRect[0]) * k;
                        lastRect[1] += (y - lastRect[1]) * k;
                        lastRect[2] += (w - lastRect[2]) * k;
                        lastRect[3] += (h - lastRect[3]) * k;
                    }
                }
                if (hitStreak <= 3 || hitStreak % 20 == 0) {
                    android.util.Log.i(
                            "AdBlur",
                            "MATCH conf="
                                    + String.format("%.3f", conf)
                                    + " need="
                                    + String.format("%.2f", need)
                                    + " gap="
                                    + String.format("%.3f", gap)
                                    + " pos="
                                    + String.format("%.2f", posConf)
                                    + " stab="
                                    + String.format("%.2f", stabConf)
                                    + " score="
                                    + String.format("%.3f", bestScore)
                                    + " rect=["
                                    + bestCx
                                    + ","
                                    + bestCy
                                    + " "
                                    + bestTw
                                    + "x"
                                    + bestTh
                                    + "]"
                                    + " armed="
                                    + (highStreak >= ARM_STREAK)
                                    + " tmpl="
                                    + bestTmpl
                                    + " "
                                    + orientName(bestOrient));
                }
            } else {
                highStreak = 0;
                lowStreak++;
                hitStreak = 0;
                missStreak++;
                // 低置信度不再续命：连续 MISS_TO_RELEASE 次（1s @500ms）立即关掉模糊
                if (lowStreak >= MISS_TO_RELEASE && lastRect != null) {
                    lastRect = null;
                    android.util.Log.i(
                            "AdBlur",
                            "release: low confidence conf="
                                    + String.format("%.3f", conf)
                                    + " need="
                                    + String.format("%.2f", need));
                }
                if (missStreak == 1 || missStreak % 10 == 1) {
                    android.util.Log.i(
                            "AdBlur",
                            "no match: miss="
                                    + missStreak
                                    + " conf="
                                    + String.format("%.3f", conf)
                                    + " need="
                                    + String.format("%.2f", need)
                                    + " score="
                                    + (bestScore == Float.MAX_VALUE
                                            ? "-1"
                                            : String.format("%.3f", bestScore))
                                    + " gap="
                                    + String.format("%.3f", gap)
                                    + " pos="
                                    + String.format("%.2f", posConf)
                                    + " stab="
                                    + String.format("%.2f", stabConf)
                                    + " tmpl="
                                    + bestTmpl
                                    + " tmplCount="
                                    + tmplCount);
                }
            }
        }

        /** 连续「输入有内容但输出全黑」的探测次数；≥3 判定管线不可用。 */
        private int blackStreak;

        /** 已处理帧计数：供播放器侧看门狗判断管线是否在产出帧（停摆时不再触发任何回调）。 */
        private final java.util.concurrent.atomic.AtomicLong producedFrames =
                new java.util.concurrent.atomic.AtomicLong();

        long producedFrames() {
            return producedFrames.get();
        }

        /**
         * 输出健康探针：对比输入（缩到 32×18）与输出中心横条的亮度。
         * 输入有内容而输出持续全黑 → 判定特效管线在此片源上静默失效，
         * 自动停用特效并触发热卸载（播放回到普通输出路径，画面恢复可见）。
         */
        private void probeOutputHealth(int outFbo, int inputTexId) throws GlUtil.GlException {
            Bitmap in = readFrameToBitmap(inputTexId, 32, 18);
            float inLum = averageLuma(in);
            in.recycle();
            int w = Math.max(1, Math.min(outputW, 48));
            ByteBuffer buf = ByteBuffer.allocateDirect(w * 4).order(ByteOrder.nativeOrder());
            focusFramebufferUsingCurrentContext(outFbo, outputW, outputH);
            GLES20.glReadPixels(
                    Math.max(0, (outputW - w) / 2),
                    Math.max(0, outputH / 2),
                    w,
                    1,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    buf);
            float outLum = averageLuma(buf, w);
            if (inLum > 10f && outLum < 4f) blackStreak++;
            else blackStreak = 0;
            if (blackStreak >= 3) {
                blackStreak = 0;
                android.util.Log.w("MM_FLICKER", "AdBlur 输出全黑且输入有内容，自动停用特效并热卸载");
                mainHandler.post(
                        () -> {
                            AdBlurController controller = AdBlurController.get(appContext);
                            controller.suspendEffects();
                            controller.notifyTemplatesChanged();
                        });
            }
        }

        private static float averageLuma(Bitmap bmp) {
            if (bmp == null || bmp.isRecycled()) return 0f;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w <= 0 || h <= 0) return 0f;
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            long sum = 0;
            for (int p : px) {
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                sum += (r * 54 + g * 183 + b * 19) >> 8;
            }
            return (float) sum / px.length;
        }

        private static float averageLuma(ByteBuffer buf, int count) {
            if (buf == null || count <= 0) return 0f;
            buf.rewind();
            long sum = 0;
            for (int i = 0; i < count; i++) {
                int r = buf.get() & 0xff, g = buf.get() & 0xff, b = buf.get() & 0xff;
                buf.get(); // alpha
                sum += (r * 54 + g * 183 + b * 19) >> 8;
            }
            return (float) sum / count;
        }

        private Bitmap readFrameToBitmap(int inputTexId, int w, int h) throws GlUtil.GlException {
            focusFramebufferUsingCurrentContext(capFbo, w, h);
            GLES20.glViewport(0, 0, w, h);
            clearFocusedBuffers();
            copyProg.use();
            copyProg.setSamplerTexIdUniform("uTex", inputTexId, 0);
            copyProg.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf); // GL 朝向：行 0 = 画面底部
            return bmp;
        }

        private static int currentFbo() {
            int[] f = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, f, 0);
            return f[0];
        }

        @Override
        public void release() throws androidx.media3.common.VideoFrameProcessingException {
            try {
                if (copyProg != null) copyProg.delete();
                if (matchProg != null) matchProg.delete();
                if (blurProg != null) blurProg.delete();
                if (mixProg != null) mixProg.delete();
                for (int i = 0; i < tmplCount; i++) {
                    if (tmplTex[i] != 0) deleteTexture(tmplTex[i]);
                    if (tmplTexFlip[i] != 0) deleteTexture(tmplTexFlip[i]);
                }
                if (scaledTex != 0) {
                    deleteFbo(scaledFbo);
                    deleteTexture(scaledTex);
                }
                if (scoreTex != 0) {
                    deleteFbo(scoreFbo);
                    deleteTexture(scoreTex);
                }
                if (tempATex != 0) {
                    deleteFbo(tempAFbo);
                    deleteTexture(tempATex);
                }
                if (tempBTex != 0) {
                    deleteFbo(tempBFbo);
                    deleteTexture(tempBTex);
                }
                if (capTex != 0) {
                    deleteFbo(capFbo);
                    deleteTexture(capTex);
                }
                tmplCount = 0;
                templatesReady = false;
                lastRect = null;
                hitStreak = 0;
                missStreak = 0;
                lastDetectMs = 0L;
            } catch (GlUtil.GlException e) {
                throw new androidx.media3.common.VideoFrameProcessingException(e);
            }
            super.release();
        }
    }
}
