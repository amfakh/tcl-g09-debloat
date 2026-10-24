package dev.tcl.home;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
 * The options sheet of one smart device, opened by holding OK on its card: a panel on the right with what the
 * device has beyond on and off. The purifier has its mode, child lock and filter; the fountain its filter and
 * its clean count; the air conditioner its temperature, mode and fan. Every control paints itself from the
 * device's last read values and sends its change through {@link HomeActivity#smart}, which reads again, so what
 * the panel shows is what the device reported rather than what was pressed. Back closes it.
 */
final class DeviceOptions {
    private static final int TEXT = Color.rgb(241, 243, 244);
    private static final int DIM = Color.rgb(173, 176, 179);
    private static final int PALE = Color.rgb(207, 223, 237);
    private static final int DARK = Color.rgb(14, 14, 15);
    private static final int FAINT = Color.argb(20, 255, 255, 255);
    private static final int AMBER = Color.rgb(252, 229, 205);
    private static final int TRACK = Color.rgb(55, 62, 66);
    /** Width of the sheet's content, in 1080p pixels: the sheet is 720 wide with 48 of padding on each side. */
    private static final int CONTENT_WIDTH = 624;
    private static final long DISARM_MS = 4_000;

    /** The purifier's mode values, as the device reports them, and their names. */
    private static final String[] PURIFIER_MODES = {"S", "A", "1", "2"};
    private static final String[] PURIFIER_MODE_NAMES = {"Silent", "Auto", "1", "2"};
    private static final String[] AC_MODES = {"0", "1", "2", "3", "4"};
    private static final String[] AC_MODE_NAMES = {"Cool", "Heat", "Auto", "Fan", "Dry"};
    private static final String[] AC_FANS = {"0", "1", "2", "3"};
    private static final String[] AC_FAN_NAMES = {"Auto", "Low", "Mid", "High"};

    private final HomeActivity host;
    private final SmartHome home;
    private final List<Consumer<JSONObject>> updaters = new ArrayList<>();
    private Dialog dialog;
    private String action;

    /** A focusable rounded button: its view and the background that changes colour. */
    private static final class Btn {
        final FrameLayout view;
        final GradientDrawable bg;

        Btn(FrameLayout view, GradientDrawable bg) {
            this.view = view;
            this.bg = bg;
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
        JSONObject values = home.values(action);
        String kind = values.optString("_kind");

        LinearLayout content = host.column();
        content.setPadding(host.px(48), host.px(48), host.px(48), host.px(40));
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
            content.addView(meter("Filter left", v -> v.optInt("7") / 90.0, v -> v.optInt("7") + " of 90 days"));
            content.addView(resetButton("Reset filter", "5"));
            content.addView(section("Clean"));
            content.addView(meter("Clean in", v -> v.optInt("4") / 31.0, v -> v.optInt("4") + " of 31 days"));
            content.addView(resetButton("Reset clean", "6"));
        } else {
            content.addView(section("Power"));
            content.addView(toggle("Air conditioner", v -> v.optBoolean("power"), on -> ac("power")));
            content.addView(section("Temperature"));
            content.addView(stepper());
            content.addView(section("Mode"));
            content.addView(segmented(AC_MODE_NAMES, AC_MODES, v -> String.valueOf(v.optInt("mode")), mode -> ac("mode:" + mode)));
            content.addView(section("Fan"));
            content.addView(segmented(AC_FAN_NAMES, AC_FANS, v -> String.valueOf(v.optInt("fan")), fan -> ac("fan:" + fan)));
        }

        dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(Color.rgb(27, 28, 31));
        sheet.setCornerRadii(new float[] {host.px(24), host.px(24), 0, 0, 0, 0, host.px(24), host.px(24)});
        content.setBackground(sheet);
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.55f);
        w.setLayout(host.px(720), ViewGroup.LayoutParams.MATCH_PARENT);
        w.setGravity(Gravity.END);
        dialog.setOnDismissListener(d -> {
            dialog = null;
            if (opener.isAttachedToWindow()) {
                opener.requestFocus();
            }
        });
        dialog.show();
        refresh();
        View first = firstFocusable(content);
        if (first != null) {
            first.requestFocus();
        }
    }

    /** Paints every control again from the device's latest values; the sheet stays as it is when none is open. */
    void refresh() {
        if (dialog == null) {
            return;
        }
        JSONObject values = home.values(action);
        for (Consumer<JSONObject> paint : updaters) {
            paint.accept(values);
        }
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
        lp.topMargin = host.px(32);
        lp.bottomMargin = host.px(12);
        t.setLayoutParams(lp);
        return t;
    }

    private Btn button() {
        FrameLayout f = new FrameLayout(host);
        f.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(FAINT);
        bg.setCornerRadius(host.px(16));
        f.setBackground(bg);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(host.px(16));
        f.setForeground(ring);
        f.setOnFocusChangeListener((v, has) -> ring.setStroke(has ? host.px(4) : 0, TEXT));
        return new Btn(f, bg);
    }

    /** One button per value; the one the device reports is filled. */
    private View segmented(String[] names, String[] values, Function<JSONObject, String> selected, Consumer<String> pick) {
        LinearLayout row = new LinearLayout(host);
        Btn[] buttons = new Btn[values.length];
        TextView[] labels = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            Btn b = button();
            TextView t = host.text(26, TEXT, true);
            t.setText(names[i]);
            b.view.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            String value = values[i];
            b.view.setOnClickListener(v -> pick.accept(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, host.px(72), 1f);
            if (i > 0) {
                lp.leftMargin = host.px(12);
            }
            row.addView(b.view, lp);
            buttons[i] = b;
            labels[i] = t;
        }
        updaters.add(v -> {
            String chosen = selected.apply(v);
            for (int i = 0; i < values.length; i++) {
                boolean on = values[i].equals(chosen);
                buttons[i].bg.setColor(on ? PALE : FAINT);
                labels[i].setTextColor(on ? DARK : TEXT);
            }
        });
        return row;
    }

    /** A row with a name and an on/off switch. */
    private View toggle(String label, Function<JSONObject, Boolean> state, Consumer<Boolean> pick) {
        Btn b = button();
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(host.px(24), 0, host.px(24), 0);
        TextView name = host.text(26, TEXT, false);
        name.setText(label);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView word = host.text(22, DIM, true);
        row.addView(word);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(host.px(20));
        FrameLayout track = new FrameLayout(host);
        track.setBackground(trackBg);
        GradientDrawable knobBg = new GradientDrawable();
        knobBg.setShape(GradientDrawable.OVAL);
        View knob = new View(host);
        knob.setBackground(knobBg);
        track.addView(knob, new FrameLayout.LayoutParams(host.px(32), host.px(32), Gravity.CENTER_VERTICAL | Gravity.START));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(host.px(72), host.px(40));
        trackLp.leftMargin = host.px(16);
        row.addView(track, trackLp);
        b.view.addView(row, new FrameLayout.LayoutParams(-1, -1));
        boolean[] current = new boolean[1];
        b.view.setOnClickListener(v -> pick.accept(!current[0]));
        updaters.add(v -> {
            boolean on = state.apply(v);
            current[0] = on;
            word.setText(on ? "On" : "Off");
            word.setTextColor(on ? PALE : DIM);
            trackBg.setColor(on ? PALE : Color.rgb(74, 78, 83));
            knobBg.setColor(on ? DARK : TEXT);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) knob.getLayoutParams();
            lp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
            lp.setMargins(host.px(4), 0, host.px(4), 0);
            knob.setLayoutParams(lp);
        });
        b.view.setLayoutParams(new LinearLayout.LayoutParams(-1, host.px(72)));
        return b.view;
    }

    /** What is left, as a bar and in words; amber when it is nearly used up. */
    private View meter(String label, ToDoubleFunction<JSONObject> fraction, Function<JSONObject, String> text) {
        LinearLayout box = host.column();
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
        track.addView(fill, new FrameLayout.LayoutParams(host.px(2), -1));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, host.px(14));
        trackLp.topMargin = host.px(12);
        box.addView(track, trackLp);
        updaters.add(v -> {
            double f = Math.max(0, Math.min(1, fraction.applyAsDouble(v)));
            boolean low = f <= 0.15;
            value.setText(text.apply(v) + (low ? "  ·  replace soon" : ""));
            value.setTextColor(low ? AMBER : TEXT);
            fillBg.setColor(low ? AMBER : PALE);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            lp.width = Math.max(host.px(2), host.px((int) Math.round(CONTENT_WIDTH * f)));
            fill.setLayoutParams(lp);
        });
        return box;
    }

    /** A reset wipes a count that cannot be read back, so it takes a second press. */
    private View resetButton(String label, String dp) {
        Btn b = button();
        TextView t = host.text(24, TEXT, true);
        t.setText(label);
        b.view.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        boolean[] armed = new boolean[1];
        Runnable disarm = () -> {
            armed[0] = false;
            t.setText(label);
            t.setTextColor(TEXT);
            b.bg.setColor(FAINT);
        };
        b.view.setOnClickListener(v -> {
            if (armed[0]) {
                disarm.run();
                set(dp, true);
                return;
            }
            armed[0] = true;
            t.setText("Press OK again to reset");
            t.setTextColor(Color.rgb(23, 24, 27));
            b.bg.setColor(AMBER);
            b.view.postDelayed(disarm, DISARM_MS);
        });
        b.view.setOnFocusChangeListener((v, has) -> {
            if (!has && armed[0]) {
                disarm.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, host.px(64));
        lp.topMargin = host.px(16);
        b.view.setLayoutParams(lp);
        return b.view;
    }

    /** Minus, the temperature and plus; each press moves one degree within the air conditioner's limits. */
    private View stepper() {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Btn minus = stepButton("−");
        Btn plus = stepButton("+");
        TextView value = host.text(56, Color.WHITE, true);
        value.setGravity(Gravity.CENTER);
        row.addView(minus.view, new LinearLayout.LayoutParams(host.px(120), host.px(72)));
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(plus.view, new LinearLayout.LayoutParams(host.px(120), host.px(72)));
        minus.view.setOnClickListener(v -> ac("down"));
        plus.view.setOnClickListener(v -> ac("up"));
        updaters.add(v -> value.setText(v.optInt("temp") + "°C"));
        return row;
    }

    private Btn stepButton(String sign) {
        Btn b = button();
        TextView t = host.text(40, TEXT, true);
        t.setText(sign);
        b.view.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return b;
    }

    private static View firstFocusable(View view) {
        if (view.isFocusable()) {
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
