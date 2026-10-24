package dev.tcl.home;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * The options sheet of one smart device, opened by holding OK on its card: a panel that slides in from the right
 * with what the device has beyond on and off. The purifier has its mode, child lock and filter; the fountain its
 * filter and its clean count; the air conditioner its temperature, mode and fan. Every control
 * paints itself from the device's last read values and sends its change through {@link HomeActivity#smart}, which
 * reads again, so what the sheet shows is what the device reported rather than what was pressed. Back closes it.
 *
 * <p>A focused control is filled white, like Google TV's own buttons; the value the device is in is tinted blue
 * with a blue outline, so the two never look alike.
 */
final class DeviceOptions {
    private static final int TEXT = Color.rgb(241, 243, 244);
    private static final int DIM = Color.rgb(173, 176, 179);
    private static final int PALE = Color.rgb(207, 223, 237);
    private static final int DARK = Color.rgb(14, 14, 15);
    private static final int FAINT = Color.argb(20, 255, 255, 255);
    private static final int CHOSEN = Color.argb(46, 207, 223, 237);
    private static final int AMBER = Color.rgb(252, 229, 205);
    private static final int TRACK = Color.rgb(55, 62, 66);
    private static final int SHEET_WIDTH = 720;
    /** Width of the sheet's content, in 1080p pixels: the sheet with 48 of padding on each side. */
    private static final int CONTENT_WIDTH = SHEET_WIDTH - 96;
    private static final long DISARM_MS = 4_000;
    private static final long SLIDE_MS = 240;
    private static final long FADE_MS = 150;

    /** The purifier's mode values, as the device reports them, and their names. */
    private static final String[] PURIFIER_MODES = {"S", "A", "1", "2"};
    private static final String[] PURIFIER_MODE_NAMES = {"Silent", "Auto", "1", "2"};
    private static final String[] AC_MODES = {"0", "1", "2", "3", "4"};
    private static final String[] AC_MODE_NAMES = {"Cool", "Heat", "Auto", "Fan", "Dry"};
    private static final String[] AC_FANS = {"0", "1", "2", "3"};
    private static final String[] AC_FAN_NAMES = {"Auto", "Low", "Mid", "High"};
    /** How long the fountain's filter and clean counts last, in days, as the BARDI app counts them. */
    private static final int FOUNTAIN_FILTER_DAYS = 30;
    private static final int FOUNTAIN_CLEAN_DAYS = 7;

    private final HomeActivity host;
    private final SmartHome home;
    private final List<Consumer<JSONObject>> updaters = new ArrayList<>();
    private Dialog dialog;
    private View sheet;
    private String action;
    /** The control that gets focus when the sheet opens, when it is not simply the first one. */
    private View firstFocus;

    /**
     * A focusable rounded button. It paints itself from three states: focused (white), chosen (the value the device
     * is in: blue tint and outline) and alert (an armed reset: amber), fading between colours.
     */
    private final class Btn {
        final FrameLayout view = new FrameLayout(host);
        final GradientDrawable bg = new GradientDrawable();
        final List<TextView> texts = new ArrayList<>();
        boolean chosen;
        boolean alert;
        Runnable onBlur;
        private int fill = FAINT;
        private ValueAnimator fade;

        Btn() {
            view.setFocusable(true);
            bg.setCornerRadius(host.px(16));
            bg.setColor(fill);
            view.setBackground(bg);
            view.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(FADE_MS).start();
                if (!has && onBlur != null) {
                    onBlur.run();
                }
                paint();
            });
        }

        TextView label(float size, boolean bold, String text) {
            TextView t = host.text(size, TEXT, bold);
            t.setText(text);
            texts.add(t);
            return t;
        }

        void paint() {
            boolean focused = view.isFocused();
            int to = alert ? AMBER : focused ? TEXT : chosen ? CHOSEN : FAINT;
            if (fade != null) {
                fade.cancel();
            }
            fade = ValueAnimator.ofArgb(fill, to);
            fade.setDuration(FADE_MS);
            fade.addUpdateListener(a -> {
                fill = (int) a.getAnimatedValue();
                bg.setColor(fill);
            });
            fade.start();
            bg.setStroke(chosen && !focused && !alert ? host.px(2) : 0, PALE);
            int ink = alert || focused ? DARK : chosen ? PALE : TEXT;
            for (TextView t : texts) {
                t.setTextColor(ink);
            }
        }
    }

    /**
     * @param host the activity, for the views' context, sizes and the smart thread
     * @param home where the devices' values come from
     */
    DeviceOptions(HomeActivity host, SmartHome home) {
        this.host = host;
        this.home = home;
    }

    /**
     * Opens the sheet for a device.
     *
     * @param action the device's card action, as {@link SmartHome#values} understands it
     * @param title the device's name
     * @param icon the device's icon kind
     * @param opener the card that had focus, which gets it back when the sheet closes
     */
    void show(String action, String title, int icon, View opener) {
        this.action = action;
        updaters.clear();
        firstFocus = null;
        String kind = home.values(action).optString("_kind");

        LinearLayout content = host.column();
        content.setPadding(host.px(48), host.px(48), host.px(48), host.px(40));
        // A focused control grows by 4% and reaches into the padding; nothing may cut it off.
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.addView(header(title, icon));
        if (kind.equals("purifier")) {
            content.addView(section("Mode"));
            content.addView(segmented(PURIFIER_MODE_NAMES, PURIFIER_MODES, v -> v.optString("4"), mode -> set("4", mode)));
            content.addView(section("Child lock"));
            content.addView(toggle("Lock the buttons", v -> v.optBoolean("7"), on -> set("7", on)));
            content.addView(section("Filter"));
            content.addView(meter("Filter left", v -> v.optInt("5") / 100.0, v -> v.optInt("5") + "%"));
            content.addView(resetButton("Reset filter", "11"));
        } else if (kind.equals("fountain")) {
            content.addView(section("Filter"));
            content.addView(meter("Filter left", v -> v.optInt("7") / (double) FOUNTAIN_FILTER_DAYS,
                v -> v.optInt("7") + " of " + FOUNTAIN_FILTER_DAYS + " days"));
            content.addView(resetButton("Reset filter", "5"));
            content.addView(section("Clean"));
            content.addView(meter("Clean in", v -> v.optInt("4") / (double) FOUNTAIN_CLEAN_DAYS,
                v -> v.optInt("4") + " of " + FOUNTAIN_CLEAN_DAYS + " days"));
            content.addView(resetButton("Reset clean", "6"));
        } else {
            content.addView(section("Temperature"));
            content.addView(temperature());
            content.addView(section("Mode"));
            content.addView(segmented(AC_MODE_NAMES, AC_MODES, v -> String.valueOf(v.optInt("mode")), mode -> ac("mode:" + mode)));
            content.addView(section("Fan"));
            content.addView(segmented(AC_FAN_NAMES, AC_FANS, v -> String.valueOf(v.optInt("fan")), fan -> ac("fan:" + fan)));
        }

        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Color.rgb(27, 28, 31));
        sheetBg.setCornerRadii(new float[] {host.px(24), host.px(24), 0, 0, 0, 0, host.px(24), host.px(24)});
        content.setBackground(sheetBg);
        sheet = content;

        dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.55f);
        w.setLayout(host.px(SHEET_WIDTH), ViewGroup.LayoutParams.MATCH_PARENT);
        w.setGravity(Gravity.END);
        w.setWindowAnimations(0);
        // Back slides the sheet out first; the dialog would otherwise vanish at once.
        dialog.setOnKeyListener((d, code, event) -> {
            if (code != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                close();
            }
            return true;
        });
        dialog.setOnDismissListener(d -> {
            dialog = null;
            if (opener.isAttachedToWindow()) {
                opener.requestFocus();
            }
        });
        content.setTranslationX(host.px(SHEET_WIDTH));
        dialog.show();
        refresh();
        content.animate().translationX(0).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
        View first = firstFocus != null ? firstFocus : firstFocusable(content);
        if (first != null) {
            first.requestFocus();
        }
    }

    /** Paints every control again from the device's latest values; nothing happens when no sheet is open. */
    void refresh() {
        if (dialog == null) {
            return;
        }
        JSONObject values = home.values(action);
        for (Consumer<JSONObject> paint : updaters) {
            paint.accept(values);
        }
    }

    private void close() {
        if (dialog == null || sheet == null) {
            return;
        }
        Dialog closing = dialog;
        sheet.animate().translationX(host.px(SHEET_WIDTH)).setDuration(SLIDE_MS * 3 / 4)
            .setInterpolator(new DecelerateInterpolator()).withEndAction(closing::dismiss).start();
    }

    // ---- sending ------------------------------------------------------------------------------------

    private void set(String dp, Object value) {
        int index = Integer.parseInt(action.split(":")[1]);
        host.smart(() -> home.setLocal(index, dp, value));
    }

    private void ac(String what) {
        host.smart(() -> home.sendAc(what));
    }

    // ---- building blocks ----------------------------------------------------------------------------

    private View header(String title, int icon) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setShape(GradientDrawable.OVAL);
        chipBg.setColor(Color.argb(41, 207, 223, 237));
        FrameLayout chip = new FrameLayout(host);
        chip.setBackground(chipBg);
        Icon art = new Icon(host, icon);
        art.setColor(PALE);
        chip.addView(art, new FrameLayout.LayoutParams(host.px(34), host.px(34), Gravity.CENTER));
        row.addView(chip, new LinearLayout.LayoutParams(host.px(64), host.px(64)));
        TextView name = host.text(34, Color.WHITE, true);
        name.setText(title);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
        nameLp.leftMargin = host.px(20);
        row.addView(name, nameLp);
        return row;
    }

    private View section(String label) {
        TextView t = host.text(20, DIM, false);
        t.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = host.px(28);
        lp.bottomMargin = host.px(12);
        t.setLayoutParams(lp);
        return t;
    }

    /** One button per value; the one the device is in is chosen. */
    private View segmented(String[] names, String[] values, Function<JSONObject, String> selected, Consumer<String> pick) {
        LinearLayout row = new LinearLayout(host);
        row.setClipChildren(false);
        Btn[] buttons = new Btn[values.length];
        for (int i = 0; i < values.length; i++) {
            Btn b = new Btn();
            b.view.addView(b.label(names[i].length() > 6 ? 22 : 26, true, names[i]),
                new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            String value = values[i];
            b.view.setOnClickListener(v -> pick.accept(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, host.px(72), 1f);
            if (i > 0) {
                lp.leftMargin = host.px(12);
            }
            row.addView(b.view, lp);
            buttons[i] = b;
        }
        updaters.add(v -> {
            String chosen = selected.apply(v);
            for (int i = 0; i < values.length; i++) {
                buttons[i].chosen = values[i].equals(chosen);
                buttons[i].paint();
            }
        });
        return row;
    }

    /** A row with a name and an on/off switch. */
    private View toggle(String label, Function<JSONObject, Boolean> state, Consumer<Boolean> pick) {
        Btn b = new Btn();
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(host.px(24), 0, host.px(24), 0);
        row.addView(b.label(26, false, label), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView word = b.label(22, true, "");
        row.addView(word);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(host.px(20));
        FrameLayout track = new FrameLayout(host);
        track.setBackground(trackBg);
        GradientDrawable knobBg = new GradientDrawable();
        knobBg.setShape(GradientDrawable.OVAL);
        View knob = new View(host);
        knob.setBackground(knobBg);
        FrameLayout.LayoutParams knobLp = new FrameLayout.LayoutParams(host.px(32), host.px(32), Gravity.CENTER_VERTICAL | Gravity.START);
        knobLp.leftMargin = host.px(4);
        track.addView(knob, knobLp);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(host.px(72), host.px(40));
        trackLp.leftMargin = host.px(16);
        row.addView(track, trackLp);
        b.view.addView(row, new FrameLayout.LayoutParams(-1, -1));
        boolean[] current = new boolean[1];
        b.view.setOnClickListener(v -> pick.accept(!current[0]));
        updaters.add(v -> {
            boolean on = state.apply(v);
            boolean changed = on != current[0];
            current[0] = on;
            word.setText(on ? "On" : "Off");
            trackBg.setColor(on ? PALE : Color.rgb(74, 78, 83));
            knobBg.setColor(on ? DARK : TEXT);
            // The knob slides to its side.
            float to = on ? host.px(72 - 32 - 8) : 0;
            if (changed) {
                knob.animate().translationX(to).setDuration(FADE_MS).start();
            } else {
                knob.setTranslationX(to);
            }
            b.paint();
        });
        b.view.setLayoutParams(new LinearLayout.LayoutParams(-1, host.px(72)));
        return b.view;
    }

    /** What is left, as a bar and in words; amber when it is nearly used up. The bar grows to its new length. */
    private View meter(String label, ToDoubleFunction<JSONObject> fraction, Function<JSONObject, String> text) {
        LinearLayout box = host.column();
        box.setClipChildren(false);
        LinearLayout head = new LinearLayout(host);
        TextView name = host.text(20, DIM, false);
        name.setText(label);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = host.text(26, TEXT, true);
        head.addView(value);
        box.addView(head);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(TRACK);
        trackBg.setCornerRadius(host.px(7));
        FrameLayout track = new FrameLayout(host);
        track.setBackground(trackBg);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(host.px(7));
        View fill = new View(host);
        fill.setBackground(fillBg);
        fill.setPivotX(0);
        track.addView(fill, new FrameLayout.LayoutParams(host.px(CONTENT_WIDTH), -1));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, host.px(14));
        trackLp.topMargin = host.px(12);
        box.addView(track, trackLp);
        fill.setScaleX(0);
        updaters.add(v -> {
            double f = Math.max(0, Math.min(1, fraction.applyAsDouble(v)));
            boolean low = f <= 0.15;
            value.setText(text.apply(v) + (low ? "  ·  replace soon" : ""));
            value.setTextColor(low ? AMBER : TEXT);
            fillBg.setColor(low ? AMBER : PALE);
            fill.animate().scaleX((float) Math.max(0.01, f)).setDuration(SLIDE_MS * 2)
                .setInterpolator(new DecelerateInterpolator()).start();
        });
        return box;
    }

    /** A reset wipes a count that cannot be read back, so it takes a second press. */
    private View resetButton(String label, String dp) {
        Btn b = new Btn();
        TextView t = b.label(24, true, label);
        b.view.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        Runnable disarm = () -> {
            b.alert = false;
            t.setText(label);
            b.paint();
        };
        b.onBlur = () -> {
            if (b.alert) {
                disarm.run();
            }
        };
        b.view.setOnClickListener(v -> {
            if (b.alert) {
                disarm.run();
                set(dp, true);
                return;
            }
            b.alert = true;
            t.setText("Press OK again to reset");
            b.paint();
            b.view.postDelayed(() -> {
                if (b.alert) {
                    disarm.run();
                }
            }, DISARM_MS);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, host.px(64));
        lp.topMargin = host.px(16);
        b.view.setLayoutParams(lp);
        return b.view;
    }

    /**
     * Minus, the temperature and plus. Minus and plus move one degree; OK on the temperature opens every degree
     * the air conditioner takes, to pick one directly.
     */
    private View temperature() {
        LinearLayout box = host.column();
        box.setClipChildren(false);
        LinearLayout row = new LinearLayout(host);
        row.setClipChildren(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Btn minus = stepButton("−");
        Btn value = new Btn();
        TextView number = value.label(44, true, "");
        value.view.addView(number, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        Btn plus = stepButton("+");
        row.addView(minus.view, new LinearLayout.LayoutParams(host.px(120), host.px(80)));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(0, host.px(80), 1f);
        valueLp.leftMargin = host.px(12);
        valueLp.rightMargin = host.px(12);
        row.addView(value.view, valueLp);
        row.addView(plus.view, new LinearLayout.LayoutParams(host.px(120), host.px(80)));
        box.addView(row);
        // Opening on minus would make the first OK send a command; the number only opens the choice.
        firstFocus = value.view;

        LinearLayout grid = host.column();
        grid.setClipChildren(false);
        grid.setVisibility(View.GONE);
        List<Btn> degrees = new ArrayList<>();
        JSONObject limits = home.values(action);
        int min = limits.optInt("min", 16);
        int max = limits.optInt("max", 30);
        int perRow = (max - min + 2) / 2;
        LinearLayout line = null;
        for (int t = min; t <= max; t++) {
            if ((t - min) % perRow == 0) {
                line = new LinearLayout(host);
                line.setClipChildren(false);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, host.px(60));
                lineLp.topMargin = host.px(12);
                grid.addView(line, lineLp);
            }
            Btn chip = new Btn();
            chip.view.addView(chip.label(22, true, String.valueOf(t)), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            int degree = t;
            chip.view.setOnClickListener(v -> {
                ac("temp:" + degree);
                grid.setVisibility(View.GONE);
                value.view.requestFocus();
            });
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, -1, 1f);
            if ((t - min) % perRow > 0) {
                chipLp.leftMargin = host.px(8);
            }
            line.addView(chip.view, chipLp);
            chip.view.setTag(degree);
            degrees.add(chip);
        }
        // The last line is as wide as the first, so its degrees line up under the ones above.
        for (int pad = (max - min + 1) % perRow == 0 ? 0 : perRow - (max - min + 1) % perRow; pad > 0; pad--) {
            View spacer = new View(host);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, -1, 1f);
            spacerLp.leftMargin = host.px(8);
            line.addView(spacer, spacerLp);
        }
        box.addView(grid);

        minus.view.setOnClickListener(v -> ac("down"));
        plus.view.setOnClickListener(v -> ac("up"));
        value.view.setOnClickListener(v -> {
            boolean open = grid.getVisibility() != View.VISIBLE;
            grid.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) {
                grid.setAlpha(0f);
                grid.animate().alpha(1f).setDuration(SLIDE_MS).start();
                for (Btn chip : degrees) {
                    if (chip.chosen) {
                        chip.view.requestFocus();
                    }
                }
            }
        });
        updaters.add(v -> {
            int temp = v.optInt("temp");
            number.setText(temp + "°C");
            for (Btn chip : degrees) {
                chip.chosen = (int) chip.view.getTag() == temp;
                chip.paint();
            }
            minus.paint();
            value.paint();
            plus.paint();
        });
        return box;
    }

    private Btn stepButton(String sign) {
        Btn b = new Btn();
        b.view.addView(b.label(40, true, sign), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return b;
    }

    private static View firstFocusable(View view) {
        if (view.isFocusable() && view.getVisibility() == View.VISIBLE) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** What a control sends back through the smart thread. */
    interface Send {
        void run() throws IOException;
    }
}
