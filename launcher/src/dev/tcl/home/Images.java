package dev.tcl.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads card artwork off the main thread (https URLs from YouTube and the like, or content:// URIs),
 * scaled down to card size and kept in a memory cache sized from the app's heap.
 */
final class Images {
    // Card width on a 1080p panel; decoding wider only costs memory.
    private static final int TARGET_WIDTH = 320;
    private static final int TIMEOUT_MS = 8000;

    private final Context context;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    // Large images (the hero) get their own thread so they do not wait behind a screen of cards.
    private final ExecutorService large = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    // Views waiting per image being fetched, so rebuilt rows do not fetch the same image twice. Main thread only.
    private final Map<String, List<ImageView>> waiting = new HashMap<>();
    // What to run once a view's image is set, instead of fading it in. Main thread only.
    private final Map<ImageView, Runnable> onShownFor = new HashMap<>();
    // An eighth of the heap holds every card of the Home rows, so rebuilding them fetches nothing again.
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * @param context used to open content:// URIs
     */
    Images(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Shows the image at uri in view once loaded. A view reused for another uri meanwhile is left alone.
     * Call on the main thread.
     *
     * @param uri image location, or null for none
     * @param view the target
     */
    void load(String uri, ImageView view) {
        load(uri, view, TARGET_WIDTH);
    }

    /**
     * Same as {@link #load(String, ImageView)}, decoded to about the given width (the hero needs more
     * pixels than a card).
     *
     * @param uri image location, or null for none
     * @param view the target
     * @param width wanted width in pixels
     */
    void load(String uri, ImageView view, int width) {
        load(uri, view, width, null);
    }

    /**
     * Same as {@link #load(String, ImageView, int)}. Without onShown, an image that had to be fetched
     * fades in; one from the cache shows at once. With onShown, nothing fades: onShown runs once the
     * image is in the view, unless the view was given another uri meanwhile.
     *
     * @param uri image location, or null for none
     * @param view the target
     * @param width wanted width in pixels
     * @param onShown run on the main thread once the image is set, or null
     */
    void load(String uri, ImageView view, int width, Runnable onShown) {
        view.setTag(uri);
        if (onShown != null) {
            onShownFor.put(view, onShown);
        } else {
            onShownFor.remove(view);
        }
        if (uri == null) {
            return;
        }
        String key = width + "|" + uri;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            Runnable shown = onShownFor.remove(view);
            if (shown != null) {
                shown.run();
            }
            return;
        }
        List<ImageView> views = waiting.get(key);
        if (views != null) {
            views.add(view);
            return;
        }
        views = new ArrayList<>();
        views.add(view);
        waiting.put(key, views);
        (width > TARGET_WIDTH ? large : pool).execute(() -> {
            Bitmap bmp = decode(uri, width);
            main.post(() -> {
                List<ImageView> targets = waiting.remove(key);
                if (bmp == null) {
                    return;
                }
                cache.put(key, bmp);
                for (ImageView v : targets) {
                    if (uri.equals(v.getTag())) {
                        v.setImageBitmap(bmp);
                        Runnable shown = onShownFor.remove(v);
                        if (shown != null) {
                            shown.run();
                        } else {
                            v.setAlpha(0f);
                            v.animate().alpha(1f).setDuration(200).start();
                        }
                    }
                }
            });
        });
    }

    /**
     * @param uri image location
     * @param width wanted width in pixels
     * @return the image scaled to about that width, or null when it cannot be read
     */
    private Bitmap decode(String uri, int width) {
        try {
            byte[] data = read(uri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= width) {
                opts.inSampleSize *= 2;
            }
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * @param uri http(s) or content URI
     * @return the raw bytes
     * @throws Exception when the image cannot be fetched
     */
    private byte[] read(String uri) throws Exception {
        InputStream in;
        HttpURLConnection http = null;
        if (uri.startsWith("http")) {
            http = (HttpURLConnection) new URL(uri).openConnection();
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            in = http.getInputStream();
        } else {
            in = context.getContentResolver().openInputStream(Uri.parse(uri));
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (http != null) {
                http.disconnect();
            }
        }
    }
}
