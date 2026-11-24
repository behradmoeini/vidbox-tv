package com.vidbox.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * UI styling, colors, animation, and fast two-tier image loader for Android TV.
 */
public final class VbUi {
    // Brand Colors
    public static final int BG = 0xFF080C14;
    public static final int BG_SURFACE = 0xFF0F172A;
    public static final int CARD_BG = 0xFF141E33;
    public static final int CARD_FOCUSED = 0xFF1E293B;
    public static final int ACCENT = 0xFF38BDF8;      // Sky blue
    public static final int ACCENT_GRAD = 0xFF2563EB; // Deep blue
    public static final int ACCENT_RED = 0xFFEF4444;  // Red badge
    public static final int TEXT_WHITE = 0xFFF8FAFC;
    public static final int TEXT_MUTED = 0xFF94A3B8;
    public static final int STAR_YELLOW = 0xFFFBBF24;

    private static final int MEM_CACHE_SIZE = (int) (Runtime.getRuntime().maxMemory() / 8);
    private static final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(MEM_CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private VbUi() {}

    public static int dp(Context c, int dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable roundRect(int bgColor, int cornerRadius, int strokeWidth, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(bgColor);
        gd.setCornerRadius(cornerRadius);
        if (strokeWidth > 0 && strokeColor != 0) {
            gd.setStroke(strokeWidth, strokeColor);
        }
        return gd;
    }

    public static GradientDrawable gradientRect(int startColor, int endColor, GradientDrawable.Orientation orient, int cornerRadius) {
        GradientDrawable gd = new GradientDrawable(orient, new int[]{startColor, endColor});
        gd.setCornerRadius(cornerRadius);
        return gd;
    }

    /**
     * Smooth scale animation for Android TV D-pad focus.
     */
    public static void animateFocus(View v, boolean focused, float maxScale) {
        float start = v.getScaleX();
        float target = focused ? maxScale : 1.0f;
        if (Math.abs(start - target) < 0.01f) {
            v.setScaleX(target);
            v.setScaleY(target);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(start, target);
        anim.setDuration(150);
        anim.addUpdateListener(a -> {
            float s = (Float) a.getAnimatedValue();
            v.setScaleX(s);
            v.setScaleY(s);
        });
        anim.start();
    }

    /**
     * Load image asynchronously into ImageView with memory & disk cache, downsampling, and rounded corners.
     */
    public static void load(ImageView iv, String url, int targetW, int targetH, int cornerRadius) {
        if (iv == null) return;
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(null);
            iv.setTag(null);
            return;
        }

        iv.setTag(url);
        Bitmap cached = memoryCache.get(url + "_" + cornerRadius);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }

        // Placeholder dark background
        iv.setImageDrawable(new ColorDrawable(CARD_BG));

        Vb.IO.execute(() -> {
            Bitmap bmp = fetchAndDecode(iv.getContext(), url, targetW, targetH, cornerRadius);
            if (bmp != null) {
                memoryCache.put(url + "_" + cornerRadius, bmp);
                Vb.MAIN.post(() -> {
                    if (url.equals(iv.getTag())) {
                        iv.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private static Bitmap fetchAndDecode(Context c, String urlStr, int reqW, int reqH, int radius) {
        String key = Vb.md5(urlStr);
        File cacheDir = c != null ? c.getCacheDir() : null;
        File diskFile = cacheDir != null ? new File(cacheDir, "img_" + key) : null;

        if (diskFile != null && diskFile.exists() && diskFile.length() > 0) {
            Bitmap b = decodeSampled(diskFile.getAbsolutePath(), reqW, reqH);
            if (b != null) return radius > 0 ? getRoundedCornerBitmap(b, radius) : b;
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", Vb.UA);
            try (InputStream in = conn.getInputStream()) {
                byte[] data = Vb.readAll(in);
                if (diskFile != null) {
                    try (OutputStream out = new FileOutputStream(diskFile)) {
                        out.write(data);
                    }
                }
                Bitmap b = decodeSampledFromBytes(data, reqW, reqH);
                if (b != null) return radius > 0 ? getRoundedCornerBitmap(b, radius) : b;
            } finally {
                conn.disconnect();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Bitmap decodeSampled(String path, int reqW, int reqH) {
        if (reqW <= 0 || reqH <= 0) return BitmapFactory.decodeFile(path);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }

    private static Bitmap decodeSampledFromBytes(byte[] data, int reqW, int reqH) {
        if (reqW <= 0 || reqH <= 0) return BitmapFactory.decodeByteArray(data, 0, data.length);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static int calculateInSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        final int height = opts.outHeight;
        final int width = opts.outWidth;
        int inSampleSize = 1;
        if (height > reqH || width > reqW) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqH && (halfWidth / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static Bitmap getRoundedCornerBitmap(Bitmap bitmap, int pixels) {
        if (bitmap == null) return null;
        try {
            Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            final Paint paint = new Paint();
            final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            final RectF rectF = new RectF(rect);
            paint.setAntiAlias(true);
            canvas.drawARGB(0, 0, 0, 0);
            paint.setColor(0xff424242);
            canvas.drawRoundRect(rectF, (float) pixels, (float) pixels, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(bitmap, rect, rect, paint);
            return output;
        } catch (Throwable t) {
            return bitmap;
        }
    }
}
