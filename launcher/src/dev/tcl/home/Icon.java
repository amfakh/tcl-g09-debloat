package dev.tcl.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Line icons for the top bar, drawn in code so the launcher needs no image files: search, picture
 * (ambient mode), gear (settings) and, under the clock, the sky and a raindrop for the chance of rain.
 * Drawn on a 24x24 grid, scaled to the view.
 */
final class Icon extends View {
    static final int SEARCH = 0;
    static final int PICTURE = 1;
    static final int GEAR = 2;
    static final int SUN = 3;
    static final int MOON = 4;
    static final int PARTLY = 5;
    static final int CLOUD = 6;
    static final int FOG = 7;
    static final int RAIN = 8;
    static final int STORM = 9;
    static final int PARTLY_NIGHT = 10;
    static final int DROP = 11;

    private int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * @param context the context
     * @param kind SEARCH, PICTURE, GEAR or a weather kind
     */
    Icon(Context context, int kind) {
        super(context);
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * @param kind the icon to draw from now on
     */
    void setKind(int kind) {
        this.kind = kind;
        invalidate();
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
        } else if (kind == SUN) {
            sun(canvas, 12, 12, 4, 7.5f);
        } else if (kind == MOON) {
            canvas.drawPath(moon(12, 12, 7.5f), paint);
        } else if (kind == PARTLY || kind == PARTLY_NIGHT) {
            // The sun or moon sits behind the cloud: none of it is drawn inside the cloud's outline.
            Path cloud = cloud(2, 3);
            canvas.save();
            canvas.clipOutPath(cloud);
            if (kind == PARTLY) {
                sun(canvas, 8.5f, 8.5f, 3, 5.5f);
            } else {
                canvas.drawPath(moon(8.5f, 8.5f, 5.5f), paint);
            }
            canvas.restore();
            canvas.drawPath(cloud, paint);
        } else if (kind == CLOUD) {
            canvas.drawPath(cloud(0, 0), paint);
        } else if (kind == FOG) {
            canvas.drawLine(4, 8, 20, 8, paint);
            canvas.drawLine(6, 12, 18, 12, paint);
            canvas.drawLine(4, 16, 20, 16, paint);
        } else if (kind == RAIN) {
            canvas.drawPath(cloud(0, -3), paint);
            for (int i = 0; i < 3; i++) {
                canvas.drawLine(8.5f + 3.5f * i, 18, 7.5f + 3.5f * i, 21, paint);
            }
        } else if (kind == DROP) {
            Path drop = new Path();
            drop.moveTo(12, 3.5f);
            drop.cubicTo(12, 3.5f, 5.5f, 11, 5.5f, 14.5f);
            drop.cubicTo(5.5f, 18.2f, 8.4f, 21, 12, 21);
            drop.cubicTo(15.6f, 21, 18.5f, 18.2f, 18.5f, 14.5f);
            drop.cubicTo(18.5f, 11, 12, 3.5f, 12, 3.5f);
            drop.close();
            canvas.drawPath(drop, paint);
        } else if (kind == STORM) {
            canvas.drawPath(cloud(0, -3), paint);
            Path bolt = new Path();
            bolt.moveTo(13, 15);
            bolt.lineTo(10.5f, 19);
            bolt.lineTo(13.5f, 19);
            bolt.lineTo(11, 23);
            canvas.drawPath(bolt, paint);
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

    /**
     * A sun: a disc and eight rays.
     *
     * @param canvas the canvas, in grid units
     * @param cx center x
     * @param cy center y
     * @param r disc radius
     * @param rays outer end of the rays
     */
    private void sun(Canvas canvas, float cx, float cy, float r, float rays) {
        canvas.drawCircle(cx, cy, r, paint);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            canvas.drawLine(cx + (r + 1.5f) * (float) Math.cos(a), cy + (r + 1.5f) * (float) Math.sin(a),
                cx + rays * (float) Math.cos(a), cy + rays * (float) Math.sin(a), paint);
        }
    }

    /**
     * A crescent moon.
     *
     * @param cx center x
     * @param cy center y
     * @param r radius
     * @return the crescent's outline, in grid units
     */
    private static Path moon(float cx, float cy, float r) {
        Path moon = new Path();
        moon.addCircle(cx, cy, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + 0.6f * r, cy - 0.47f * r, 0.8f * r, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
        return moon;
    }

    /**
     * A cloud of three lobes on a flat base, filling most of the grid at offset 0.
     *
     * @param dx horizontal offset
     * @param dy vertical offset
     * @return the cloud's outline, in grid units
     */
    private static Path cloud(float dx, float dy) {
        Path p = new Path();
        p.moveTo(7.5f + dx, 18 + dy);
        p.lineTo(17 + dx, 18 + dy);
        p.arcTo(new RectF(13.5f + dx, 11 + dy, 20.5f + dx, 18 + dy), 90, -180);
        p.arcTo(new RectF(8 + dx, 6 + dy, 16 + dx, 14 + dy), 20, -200);
        p.arcTo(new RectF(3.5f + dx, 10 + dy, 11.5f + dx, 18 + dy), 270, -180);
        p.close();
        return p;
    }
}
