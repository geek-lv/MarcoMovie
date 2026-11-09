package com.pilipala.marcomovie;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 矩形框选编辑器：在截图上拖拽出矩形区域（特征图），支持四角手柄缩放与整体拖拽移动。
 * 矩形坐标保存在「显示 Bitmap」像素空间；调用方负责转换为 GL 朝向。
 */
public class RectSelectView extends View {

    private Bitmap bmp;
    private final Paint framePaint = new Paint();
    private final Paint handlePaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final RectF rect = new RectF();
    private boolean hasRect = false;

    private float ox, oy, scale; // bitmap -> view 的布局映射
    private static final float HANDLE_DP = 20f;
    private float handlePx;

    private int dragMode = 0; // 0 无 / 1 绘制 / 2 移动 / 3~6 四角
    private float lastX, lastY;

    public RectSelectView(Context context, Bitmap bmp) {
        super(context);
        this.bmp = bmp;
        framePaint.setColor(Color.WHITE);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3);
        handlePaint.setColor(0xff4f9dff);
        handlePaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(0x78000000);
        handlePx = HANDLE_DP * context.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setBitmap(Bitmap b) {
        this.bmp = b;
        invalidate();
    }

    private void computeLayout() {
        if (bmp == null || bmp.getWidth() == 0) return;
        scale = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        if (scale <= 0) scale = 1;
        ox = (getWidth() - bmp.getWidth() * scale) / 2f;
        oy = (getHeight() - bmp.getHeight() * scale) / 2f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeLayout();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bmp == null) return;
        computeLayout();
        RectF dst = new RectF(ox, oy, ox + bmp.getWidth() * scale, oy + bmp.getHeight() * scale);
        canvas.drawBitmap(bmp, null, dst, null);
        if (hasRect) {
            RectF dr = bmpRectToView(rect);
            // 矩形外变暗（上下左右四块）
            canvas.drawRect(ox, oy, ox + bmp.getWidth() * scale, dr.top, dimPaint);
            canvas.drawRect(ox, dr.bottom, ox + bmp.getWidth() * scale, oy + bmp.getHeight() * scale, dimPaint);
            canvas.drawRect(ox, dr.top, dr.left, dr.bottom, dimPaint);
            canvas.drawRect(dr.right, dr.top, ox + bmp.getWidth() * scale, dr.bottom, dimPaint);
            canvas.drawRect(dr, framePaint);
            drawHandle(canvas, dr.left, dr.top);
            drawHandle(canvas, dr.right, dr.top);
            drawHandle(canvas, dr.left, dr.bottom);
            drawHandle(canvas, dr.right, dr.bottom);
        }
    }

    private void drawHandle(Canvas c, float x, float y) {
        c.drawCircle(x, y, handlePx / 2f, handlePaint);
    }

    private RectF bmpRectToView(RectF r) {
        return new RectF(ox + r.left * scale, oy + r.top * scale,
                ox + r.right * scale, oy + r.bottom * scale);
    }

    private RectF viewToBmp(float x, float y) {
        return new RectF((x - ox) / scale, (y - oy) / scale, 0, 0);
    }

    private int hitHandle(float x, float y) {
        if (!hasRect) return 0;
        RectF dr = bmpRectToView(rect);
        float[][] corners = {{dr.left, dr.top}, {dr.right, dr.top}, {dr.left, dr.bottom}, {dr.right, dr.bottom}};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(x - corners[i][0]) <= handlePx && Math.abs(y - corners[i][1]) <= handlePx) {
                return i + 3; // 3,4,5,6
            }
        }
        return 0;
    }

    private void clampRect() {
        float w = bmp == null ? 1 : bmp.getWidth();
        float h = bmp == null ? 1 : bmp.getHeight();
        rect.left = Math.max(0, Math.min(rect.left, w));
        rect.top = Math.max(0, Math.min(rect.top, h));
        rect.right = Math.max(0, Math.min(rect.right, w));
        rect.bottom = Math.max(0, Math.min(rect.bottom, h));
        if (rect.left > rect.right) {
            float t = rect.left; rect.left = rect.right; rect.right = t;
        }
        if (rect.top > rect.bottom) {
            float t = rect.top; rect.top = rect.bottom; rect.bottom = t;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        computeLayout();
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int h = hitHandle(x, y);
                if (h != 0) {
                    dragMode = h;
                } else {
                    RectF dr = bmpRectToView(rect);
                    if (hasRect && x >= dr.left - handlePx && x <= dr.right + handlePx
                            && y >= dr.top - handlePx && y <= dr.bottom + handlePx) {
                        dragMode = 2; // move
                    } else {
                        dragMode = 1; // draw new
                        RectF b = viewToBmp(x, y);
                        rect.set(b.left, b.top, b.left, b.top);
                        hasRect = true;
                    }
                }
                lastX = x; lastY = y;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = (x - lastX) / scale;
                float dy = (y - lastY) / scale;
                if (dragMode == 1) {
                    RectF b = viewToBmp(x, y);
                    rect.right = b.left; rect.bottom = b.top;
                } else if (dragMode == 2) {
                    rect.left += dx; rect.right += dx; rect.top += dy; rect.bottom += dy;
                } else if (dragMode >= 3) {
                    RectF b = viewToBmp(x, y);
                    float bx = b.left, by = b.top;
                    int corner = dragMode - 3;
                    // 0=TL,1=TR,2=BL,3=BR
                    if (corner == 0) { rect.left = bx; rect.top = by; }
                    else if (corner == 1) { rect.right = bx; rect.top = by; }
                    else if (corner == 2) { rect.left = bx; rect.bottom = by; }
                    else { rect.right = bx; rect.bottom = by; }
                }
                clampRect();
                lastX = x; lastY = y;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = 0;
                invalidate();
                return true;
            default:
                return false;
        }
    }

    /** 返回显示 Bitmap 像素空间下的矩形；未框选或过小返回 null。 */
    public android.graphics.Rect getRectInBitmap() {
        if (!hasRect) return null;
        if (rect.width() < 6 || rect.height() < 6) return null;
        return new android.graphics.Rect(
                Math.round(rect.left), Math.round(rect.top),
                Math.round(rect.right), Math.round(rect.bottom));
    }
}
