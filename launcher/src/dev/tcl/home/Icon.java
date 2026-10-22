package dev.tcl.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Line icons for the top bar, drawn in code so the launcher needs no image files: search, picture
 * (ambient mode) and gear (settings). Drawn on a 24x24 grid, scaled to the view.
 */
final class Icon extends View {
    static final int SEARCH = 0;
    static final int PICTURE = 1;
    static final int GEAR = 2;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * @param context the context
     * @param kind SEARCH, PICTURE or GEAR
     */
    Icon(Context context, int kind) {
        super(context);
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * @param color stroke color
     */
    void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /**
     * Draws the icon centered in the view.
     *
     * @param canvas the canvas
     */
    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        canvas.save();
        canvas.translate((getWidth() - 24 * s) / 2, (getHeight() - 24 * s) / 2);
        canvas.scale(s, s);
        paint.setStrokeWidth(2f);
        if (kind == SEARCH) {
            canvas.drawCircle(11, 11, 6.5f, paint);
            canvas.drawLine(16, 16, 20, 20, paint);
        } else if (kind == PICTURE) {
            canvas.drawRoundRect(new RectF(3.5f, 5, 20.5f, 19), 2.5f, 2.5f, paint);
            Path hills = new Path();
            hills.moveTo(6.5f, 16);
            hills.lineTo(10.5f, 11.5f);
            hills.lineTo(13.5f, 14.5f);
            hills.lineTo(15.5f, 12.5f);
            hills.lineTo(17.5f, 16);
            canvas.drawPath(hills, paint);
            canvas.drawCircle(15.5f, 9, 1.2f, paint);
        } else {
            canvas.drawCircle(12, 12, 3, paint);
            canvas.drawCircle(12, 12, 7, paint);
            for (int i = 0; i < 6; i++) {
                double a = Math.PI / 3 * i;
                canvas.drawLine(12 + 7 * (float) Math.cos(a), 12 + 7 * (float) Math.sin(a),
                    12 + 9.5f * (float) Math.cos(a), 12 + 9.5f * (float) Math.sin(a), paint);
            }
        }
        canvas.restore();
    }
}
