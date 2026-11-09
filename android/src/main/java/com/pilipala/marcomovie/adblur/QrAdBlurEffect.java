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
 * 视频「牛皮癣」广告（二维码 / 贴片水印）实时模糊特效。
 *
 * <p>作为 Media3 {@link GlEffect} 接入播放器的视频后处理链（{@code ExoPlayer.setVideoEffects}），
 * 逐帧在 GPU 上完成：
 * <ol>
 *   <li><b>检测</b>：把当前帧降采样后，用归一化 SAD 模板匹配在 GPU 上输出一张几 KB 的「分数图」，
 *       仅回读该分数图（而非整帧）到 CPU 做 argmin 求矩形框；每 {@code detectEveryN} 帧一次，
 *       其余帧复用上一帧矩形 —— 广告出现即模糊、消失即恢复清晰。</li>
 *   <li><b>模糊</b>：对检测到的矩形区域做两遍分离高斯（H→临时 FBO→V），矩形外透传原帧，
 *       边缘羽化过渡，全程在 GPU 上完成，不把视频帧拷回 CPU。</li>
 *   <li><b>特征图捕获</b>：{@link #requestCapture(CaptureListener)} 触发后，在下一帧把当前输入纹理
 *       渲染到离屏 FBO 并读回为 Bitmap（GL 朝向），交给 UI 做框选 —— 与检测同坐标系，匹配天然一致。</li>
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
    @Nullable
    private QrAdBlurShaderProgram program;
    @Nullable
    private CaptureListener captureListener;

    public QrAdBlurEffect(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 设置截图捕获回调（供 UI 框选特征图）。 */
    public void setCaptureListener(@Nullable CaptureListener l) {
        this.captureListener = l;
        if (program != null) program.setCaptureListener(l);
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
        private static final int MAX_TMPL = 8;   // 最多加载的特征图数
        private static final int CAP_MAX = 360;  // 截图捕获最大边长（供框选精度）

        // 全屏四边形（TRIANGLE_STRIP），aPosition 与 aTextureCoord 共用同一套坐标
        private static final float[] POS = {-1, -1, 1, -1, -1, 1, 1, 1};
        private static final float[] TEX = {0, 0, 1, 0, 0, 1, 1, 1};

        private static final String VERT = ""
                + "attribute vec4 aPosition;\n"
                + "attribute vec2 aTextureCoord;\n"
                + "varying vec2 vTexCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aPosition;\n"
                + "  vTexCoord = aTextureCoord;\n"
                + "}\n";

        // 直通拷贝（降采样 / 透传）
        private static final String COPY_FRAG = ""
                + "precision mediump float;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform sampler2D uTex;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(uTex, vTexCoord);\n"
                + "}\n";

        // 归一化 SAD 模板匹配：输出分数图（R 通道 = 平均绝对差 / 255，[0,1]，越低越像）
        private static final String MATCH_FRAG = ""
                + "precision mediump float;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform sampler2D uInput;\n"
                + "uniform sampler2D uTmpl;\n"
                + "uniform vec2 uFrameSize;\n"
                + "uniform vec2 uTmplSize;\n"
                + "uniform float uScoreScale;\n"
                + "const int MAXT = 64;\n"
                + "void main() {\n"
                + "  vec2 scorePx = vTexCoord * (uFrameSize - uTmplSize + 1.0);\n"
                + "  vec2 topLeft = scorePx / uFrameSize;\n"
                + "  vec2 texel = 1.0 / uFrameSize;\n"
                + "  float sad = 0.0;\n"
                + "  for (int j = 0; j < MAXT; j++) {\n"
                + "    if (j >= int(uTmplSize.y)) break;\n"
                + "    for (int i = 0; i < MAXT; i++) {\n"
                + "      if (i >= int(uTmplSize.x)) break;\n"
                + "      vec2 off = vec2(float(i), float(j)) * texel;\n"
                + "      vec3 f = texture2D(uInput, topLeft + off).rgb;\n"
                + "      vec2 tco = (vec2(float(i), float(j)) + 0.5) / uTmplSize;\n"
                + "      vec3 t = texture2D(uTmpl, tco).rgb;\n"
                + "      sad += abs(f.r - t.r) + abs(f.g - t.g) + abs(f.b - t.b);\n"
                + "    }\n"
                + "  }\n"
                + "  float norm = sad * uScoreScale / 255.0;\n"
                + "  gl_FragColor = vec4(norm, 0.0, 0.0, 1.0);\n"
                + "}\n";

        // 分离高斯（沿 uDir 方向 9 抽头），H 与 V 共用此 shader
        private static final String BLUR_FRAG = ""
                + "precision mediump float;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform sampler2D uTex;\n"
                + "uniform vec2 uDir;\n"
                + "void main() {\n"
                + "  float w0 = 0.227027, w1 = 0.1945946, w2 = 0.1216216, w3 = 0.054054, w4 = 0.016216;\n"
                + "  vec3 s = texture2D(uTex, vTexCoord).rgb * w0;\n"
                + "  s += texture2D(uTex, vTexCoord + uDir * 1.0).rgb * w1;\n"
                + "  s += texture2D(uTex, vTexCoord - uDir * 1.0).rgb * w1;\n"
                + "  s += texture2D(uTex, vTexCoord + uDir * 2.0).rgb * w2;\n"
                + "  s += texture2D(uTex, vTexCoord - uDir * 2.0).rgb * w2;\n"
                + "  s += texture2D(uTex, vTexCoord + uDir * 3.0).rgb * w3;\n"
                + "  s += texture2D(uTex, vTexCoord - uDir * 3.0).rgb * w3;\n"
                + "  s += texture2D(uTex, vTexCoord + uDir * 4.0).rgb * w4;\n"
                + "  s += texture2D(uTex, vTexCoord - uDir * 4.0).rgb * w4;\n"
                + "  gl_FragColor = vec4(s, 1.0);\n"
                + "}\n";

        // 合成：矩形内用模糊、矩形外透传清晰，边缘羽化
        private static final String MIX_FRAG = ""
                + "precision mediump float;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform sampler2D uSharp;\n"
                + "uniform sampler2D uBlurred;\n"
                + "uniform vec2 uFrameSize;\n"
                + "uniform vec4 uRect;\n"
                + "uniform float uFeather;\n"
                + "void main() {\n"
                + "  vec3 sharp = texture2D(uSharp, vTexCoord).rgb;\n"
                + "  vec3 blurred = texture2D(uBlurred, vTexCoord).rgb;\n"
                + "  float dx = min(vTexCoord.x - uRect.x, (uRect.x + uRect.z) - vTexCoord.x);\n"
                + "  float dy = min(vTexCoord.y - uRect.y, (uRect.y + uRect.w) - vTexCoord.y);\n"
                + "  float edge = min(dx, dy);\n"
                + "  float f = uFeather * min(uFrameSize.x, uFrameSize.y) + 1e-4;\n"
                + "  float blend = clamp(1.0 - smoothstep(0.0, f, edge), 0.0, 1.0);\n"
                + "  gl_FragColor = vec4(mix(sharp, blurred, blend), 1.0);\n"
                + "}\n";

        private final Context appContext;
        @Nullable
        private volatile CaptureListener captureListener;
        private volatile boolean captureRequested = false;

        // 程序
        private GlProgram copyProg;
        private GlProgram matchProg;
        private GlProgram blurProg;
        private GlProgram mixProg;

        // 模板纹理
        private final int[] tmplTex = new int[MAX_TMPL];
        private final int[] tmplW = new int[MAX_TMPL];
        private final int[] tmplH = new int[MAX_TMPL];
        private int tmplCount = 0;
        private boolean templatesReady = false;

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
        private String loadedSig = "";   // 已加载模板的文件签名，用于热重载
        @Nullable
        private float[] lastRect = null; // x,y,w,h 归一化(GL 空间)
        private int noMatch = 0;
        private static final int MAX_NO_MATCH = 3;

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

                loadTemplates();
                List<String> sigNames = AdBlurTemplates.list(appContext);
                loadedSig = sigNames.size() + ":" + TextUtils.join(",", sigNames);
                allocScaled(inputWidth, inputHeight);
                allocTemp(inputWidth, inputHeight);
                allocCapture(inputWidth, inputHeight);
                lastRect = null;
                noMatch = 0;
                frameIndex = 0;
                return new Size(inputWidth, inputHeight);
            } catch (GlUtil.GlException | java.io.IOException e) {
                throw new androidx.media3.common.VideoFrameProcessingException(e);
            }
        }

        private void setQuad(GlProgram p) throws GlUtil.GlException {
            p.use();
            p.setBufferAttribute("aPosition", POS, 2);
            p.setBufferAttribute("aTextureCoord", TEX, 2);
        }

        private void loadTemplates() throws GlUtil.GlException {
            templatesReady = false;
            tmplCount = 0;
            List<String> names = AdBlurTemplates.list(appContext);
            for (int i = 0; i < names.size() && tmplCount < MAX_TMPL; i++) {
                Bitmap b = AdBlurTemplates.load(appContext, names.get(i));
                if (b == null || b.isRecycled()) continue;
                tmplTex[tmplCount] = createTexture(b);
                tmplW[tmplCount] = b.getWidth();
                tmplH[tmplCount] = b.getHeight();
                b.recycle();
                tmplCount++;
            }
            templatesReady = tmplCount > 0;
        }

        /** 检测模板目录是否变化，变了则热重载 GPU 纹理（免重配置）。 */
        private void maybeReloadTemplates() throws GlUtil.GlException {
            List<String> names = AdBlurTemplates.list(appContext);
            String sig = names.size() + ":" + TextUtils.join(",", names);
            if (sig.equals(loadedSig)) return;
            for (int i = 0; i < tmplCount; i++) {
                if (tmplTex[i] != 0) deleteTexture(tmplTex[i]);
                tmplTex[i] = 0;
            }
            tmplCount = 0;
            loadTemplates();
            loadedSig = sig;
            if (tmplCount == 0) lastRect = null;
        }

        private void allocScaled(int inW, int inH) throws GlUtil.GlException {
            int scaleMax = Math.max(64, AdBlurConfig.getScaleMax());
            float s = (float) scaleMax / Math.max(inW, inH);
            scaledW = Math.max(16, Math.round(inW * s));
            scaledH = Math.max(16, Math.round(inH * s));
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

                // 2) 模板检测（开启且每隔 detectEveryN 帧）
                boolean enabled = AdBlurConfig.isEnabled();
                if (!enabled) {
                    lastRect = null;
                } else if (tmplCount > 0
                        && frameIndex % Math.max(1, AdBlurConfig.getDetectEveryN()) == 0) {
                    try {
                        detect(inputTexId);
                    } catch (Throwable t) {
                        // 检测偶发失败不应中断播放，保留上一次矩形
                    }
                }

                // 3) 模糊（开启且有矩形）或透传（关闭 / 无矩形）
                if (enabled && lastRect != null) {
                    // H 模糊: input -> tempA
                    focusFramebufferUsingCurrentContext(tempAFbo, outputW, outputH);
                    GLES20.glViewport(0, 0, outputW, outputH);
                    blurProg.use();
                    blurProg.setFloatsUniform("uDir",
                            new float[]{(float) AdBlurConfig.getBlurRadius() / outputW, 0f});
                    blurProg.setSamplerTexIdUniform("uTex", inputTexId, 0);
                    blurProg.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                    // V 模糊: tempA -> tempB
                    focusFramebufferUsingCurrentContext(tempBFbo, outputW, outputH);
                    blurProg.setFloatsUniform("uDir",
                            new float[]{0f, (float) AdBlurConfig.getBlurRadius() / outputH});
                    blurProg.setSamplerTexIdUniform("uTex", tempATex, 0);
                    blurProg.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                    // 合成: sharp + blurred -> outFbo
                    focusFramebufferUsingCurrentContext(outFbo, outputW, outputH);
                    mixProg.use();
                    mixProg.setSamplerTexIdUniform("uSharp", inputTexId, 0);
                    mixProg.setSamplerTexIdUniform("uBlurred", tempBTex, 1);
                    mixProg.setFloatsUniform("uFrameSize", new float[]{outputW, outputH});
                    mixProg.setFloatsUniform("uRect",
                            new float[]{lastRect[0], lastRect[1], lastRect[2], lastRect[3]});
                    mixProg.setFloatUniform("uFeather", AdBlurConfig.getFeather());
                    mixProg.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                } else {
                    focusFramebufferUsingCurrentContext(outFbo, outputW, outputH);
                    copyProg.use();
                    copyProg.setSamplerTexIdUniform("uTex", inputTexId, 0);
                    copyProg.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
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
            float bestSAD = Float.MAX_VALUE;
            int bestCx = -1, bestCy = -1, bestTw = 0, bestTh = 0;

            for (int i = 0; i < tmplCount; i++) {
                int tw = tmplW[i], th = tmplH[i];
                int sw = scaledW - tw + 1;
                int sh = scaledH - th + 1;
                if (sw <= 0 || sh <= 0) continue;
                ensureScoreFbo(sw, sh);
                focusFramebufferUsingCurrentContext(scoreFbo, sw, sh);
                GLES20.glViewport(0, 0, sw, sh);
                clearFocusedBuffers();
                matchProg.use();
                matchProg.setSamplerTexIdUniform("uInput", scaledTex, 0);
                matchProg.setSamplerTexIdUniform("uTmpl", tmplTex[i], 1);
                matchProg.setFloatsUniform("uFrameSize", new float[]{scaledW, scaledH});
                matchProg.setFloatsUniform("uTmplSize", new float[]{tw, th});
                matchProg.setFloatUniform("uScoreScale", 1f / (tw * th * 3f));
                matchProg.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                int n = sw * sh;
                if (scoreBuf == null || scoreBuf.capacity() < n * 4) {
                    scoreBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
                }
                scoreBuf.rewind();
                GLES20.glReadPixels(0, 0, sw, sh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, scoreBuf);

                float minV = Float.MAX_VALUE;
                int minX = 0, minY = 0;
                for (int p = 0; p < n; p++) {
                    int r = scoreBuf.get(p * 4) & 0xFF;
                    float v = r / 255f;
                    if (v < minV) {
                        minV = v;
                        minX = p % sw;
                        minY = p / sw;
                    }
                }
                if (minV < bestSAD) {
                    bestSAD = minV;
                    bestCx = minX;
                    bestCy = minY;
                    bestTw = tw;
                    bestTh = th;
                }
            }

            if (bestCx >= 0 && bestSAD < threshold) {
                float x = (float) bestCx / scaledW;
                float y = (float) bestCy / scaledH;
                float w = (float) bestTw / scaledW;
                float h = (float) bestTh / scaledH;
                if (lastRect == null) {
                    lastRect = new float[]{x, y, w, h};
                } else {
                    // 帧间 EMA 平滑，抑制抖动
                    final float k = 0.5f;
                    lastRect[0] += (x - lastRect[0]) * k;
                    lastRect[1] += (y - lastRect[1]) * k;
                    lastRect[2] += (w - lastRect[2]) * k;
                    lastRect[3] += (h - lastRect[3]) * k;
                }
                noMatch = 0;
            } else {
                noMatch++;
                if (noMatch > MAX_NO_MATCH) lastRect = null;
            }
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
            GLES20.glGetIntegv(GLES20.GL_FRAMEBUFFER_BINDING, f, 0);
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
            } catch (GlUtil.GlException e) {
                throw new androidx.media3.common.VideoFrameProcessingException(e);
            }
            super.release();
        }
    }
}
