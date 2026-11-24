package com.vidbox.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The look every screen shares, and a small cached image loader.
 *
 * Focus is shown one way everywhere: a button turns white with dark text; a poster card gets a
 * white ring, grows a little, and its title turns white (and scrolls if it is too long to fit).
 * Everything is flat colour: no blur and no stacked translucent layers, which the TV's graphics
 * chip is too weak for.
 */
final class VbUi {
    static final int BG = 0xFF0B0B0D;
    static final int PANEL = 0xFF1A1A1F;
    static final int ACCENT = 0xFFFFB224;
    static final int TEXT = 0xFFF5F5F4;
    static final int DIM = 0xFF9A9AA3;

    private VbUi() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    static GradientDrawable round(int fill, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static void gone(View v, boolean gone) {
        v.setVisibility(gone ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- buttons

    /**
     * A D-pad button: white when focused, accent-tinted when {@code setSelected(true)}.
     * Call {@link #restyle(TextView)} after changing the selected state.
     */
    static TextView pill(Context c, String s) {
        final TextView t = label(c, s, 15, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        t.setFocusable(true);
        t.setFocusableInTouchMode(false);
        t.setClickable(true);
        t.setSingleLine(true);
        t.setOnFocusChangeListener((v, f) -> restyle(t));
        restyle(t);
        return t;
    }

    static void restyle(TextView t) {
        float r = dp(t.getContext(), 20);
        if (t.isFocused()) {
            t.setBackground(round(Color.WHITE, r, 0, 0));
            t.setTextColor(BG);
        } else if (t.isSelected()) {
            t.setBackground(round(0xFF33260A, r, dp(t.getContext(), 1), ACCENT));
            t.setTextColor(ACCENT);
        } else {
            t.setBackground(round(0xFF1F1F25, r, 0, 0));
            t.setTextColor(TEXT);
        }
    }

    // ---------------------------------------------------------------- poster cards

    /** The views of one poster card; kept in the card's tag. */
    static final class Card {
        FrameLayout frame;
        ImageView image;
        TextView title, tag;
        View progress;
    }

    /** A card: picture (with a "TV" tag and a resume bar), one-line title underneath. */
    static View card(Context c, int imageH) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        Card h = new Card();
        h.frame = new FrameLayout(c);
        h.frame.setBackground(round(PANEL, dp(c, 8), 0, 0));
        h.frame.setClipToOutline(true);
        h.image = new ImageView(c);
        h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.frame.addView(h.image, new FrameLayout.LayoutParams(-1, -1));
        h.tag = label(c, "TV", 11, BG, true);
        h.tag.setBackground(round(ACCENT, dp(c, 4), 0, 0));
        h.tag.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 2));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        tl.setMargins(dp(c, 6), dp(c, 6), 0, 0);
        h.frame.addView(h.tag, tl);
        h.progress = new View(c);
        h.progress.setBackgroundColor(ACCENT);
        h.frame.addView(h.progress, new FrameLayout.LayoutParams(0, dp(c, 4), Gravity.BOTTOM | Gravity.LEFT));
        card.addView(h.frame, new LinearLayout.LayoutParams(-1, imageH));
        h.title = label(c, "", 13, DIM, false);
        h.title.setSingleLine(true);
        h.title.setEllipsize(TextUtils.TruncateAt.END);
        h.title.setMarqueeRepeatLimit(-1);
        h.title.setHorizontalFadingEdgeEnabled(false);
        h.title.setPadding(dp(c, 2), dp(c, 6), dp(c, 2), 0);
        card.addView(h.title, new LinearLayout.LayoutParams(-1, -2));
        card.setTag(h);
        return card;
    }

    /** Fill a card. {@code wide} shows the landscape still instead of the poster. */
    static void bind(View card, Vb.Item it, int imageW, boolean wide, String caption) {
        Card h = (Card) card.getTag();
        h.title.setText(caption != null ? caption : it.title);
        gone(h.tag, wide || !Vb.TYPE_TV.equals(it.type));
        String url = wide ? it.getBackdropUrl() : it.getPosterUrl();
        image(h.image, sized(url, wide ? 300 : 185), imageW);
        int w = 0;
        if (it.resumePosition > 0 && it.duration > 0) {
            w = (int) Math.max(dp(card.getContext(), 6), imageW * it.resumePosition / it.duration);
        }
        h.progress.getLayoutParams().width = w;
        h.progress.requestLayout();
    }

    /** The one focus indication for cards. */
    static void focus(View card, boolean on) {
        Card h = (Card) card.getTag();
        Context c = card.getContext();
        h.frame.setForeground(on ? round(Color.TRANSPARENT, dp(c, 8), dp(c, 3), Color.WHITE) : null);
        h.title.setTextColor(on ? Color.WHITE : DIM);
        marquee(h.title, on);
        float s = on ? 1.06f : 1f;
        if (card.getScaleX() != s) card.animate().scaleX(s).scaleY(s).setDuration(120).start();
    }

    /** Scroll a one-line title that does not fit (while {@code on}); otherwise cut it with an ellipsis. */
    static void marquee(TextView t, boolean on) {
        CharSequence s = t.getText();
        int room = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
        boolean scroll = on && room > 0 && t.getPaint().measureText(s, 0, s.length()) > room;
        // A title that fits keeps the ellipsis mode, so it can never move.
        TextUtils.TruncateAt mode = scroll ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END;
        if (t.getEllipsize() != mode) t.setEllipsize(mode);
        t.setSelected(scroll); // the platform marquee runs only on a selected or focused view
    }

    // ---------------------------------------------------------------- images

    /** TMDB serves each picture at several widths; ask for one near what is drawn. */
    static String sized(String url, int width) {
        if (url == null) return null;
        return url.replaceFirst("/t/p/w\\d+/", "/t/p/w" + width + "/");
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 6)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Load {@code url} into {@code view}, downsampled to about {@code widthPx}. Safe for recycled views. */
    static void image(final ImageView view, final String url, final int widthPx) {
        if (url == null) {
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        if (url.equals(view.getTag()) && view.getDrawable() != null) return;
        view.setTag(url);
        final String key = url + "@" + widthPx;
        Bitmap hit = MEM.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        final Context app = view.getContext().getApplicationContext();
        POOL.execute(() -> {
            try {
                File dir = new File(app.getCacheDir(), "vb_img");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, Vb.md5(url));
                if (!f.exists() || f.length() == 0) {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10_000);
                    c.setReadTimeout(20_000);
                    c.setRequestProperty("User-Agent", Vb.UA);
                    byte[] data;
                    try {
                        data = Vb.readAll(c.getInputStream());
                    } finally {
                        c.disconnect();
                    }
                    File tmp = new File(dir, f.getName() + ".tmp");
                    try (FileOutputStream os = new FileOutputStream(tmp)) {
                        os.write(data);
                    }
                    tmp.renameTo(f);
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
                if (bmp == null) {
                    f.delete();
                    return;
                }
                MEM.put(key, bmp);
                Vb.MAIN.post(() -> {
                    if (url.equals(view.getTag())) view.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
            }
        });
    }
}
