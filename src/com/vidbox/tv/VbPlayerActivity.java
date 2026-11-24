package com.vidbox.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen player.
 *
 * The stream servers are web pages with their own players, made for a mouse: left to themselves
 * they sit paused and muted waiting for a click, and open adverts. So the page is opened out of
 * sight in a WebView; once its video element has a film in it, the picture alone is shown
 * full-screen and that element is driven from here (play, pause, seek, speed). The controls on
 * screen are this app's own and the remote never reaches the page.
 *
 * A server whose page has no video within {@link #FIND_MS} is skipped for the next one, and how
 * each server did is remembered ({@link Vb#serverResult}), so the next film starts with the one
 * that is working best right now.
 *
 * Remote: Left/Right seek (faster when held), OK play/pause. Down opens the button row: previous
 * episode, back 10 s, play/pause, forward 10 s, next episode, speed, server. Up or Back leaves it.
 */
public class VbPlayerActivity extends Activity {
    private static final String TAG = "Vidbox";
    private static final long FIND_MS = 40_000, STALL_MS = 30_000;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    /** Advertising hosts the server pages load; refused so they cost no time. */
    private static final String[] BLOCKED = {
            "doubleclick.net", "googlesyndication.com", "google-analytics.com", "googletagmanager.com",
            "adsterra", "popads.net", "propellerads", "exoclick.com", "juicyads.com", "clickadu.com",
            "popcash.net", "hilltopads", "trafficjunky.com", "monetag.com", "adkeeper", "onclickads",
            "histats.com", "disqus.com", "facebook.net", "cloudflareinsights.com"};

    private Vb.Item item;
    private String imdbId, probe, probeFlags = "";
    private int season, episode;
    private int[] seasonNumbers, seasonCounts;

    // The server's page
    private WebView web;
    private FrameLayout webHolder;
    private View cover;
    private final List<Integer> order = new ArrayList<>(); // servers still to try, in order
    private int serverIdx = -1;
    private long findStarted;
    private boolean webPaused, webEnded;
    private long webPos, webDur, webBuffered;

    private boolean prepared, failed, started;
    private int generation, retries;
    private long resumeAt, lastPos, duration, seekTarget = -1, waitingSince, lastSave;

    private FrameLayout root;
    private View overlay;
    private TextView title, now, total, center, playBtn, speedBtn, serverBtn;
    private LinearLayout buttons;
    private float speed = 1f;
    private ProgressBar spinner;
    private Bar bar;

    private final Runnable hide = () -> {
        if (playing() && seekTarget < 0) {
            if (buttons.hasFocus()) root.requestFocus();
            overlay.setVisibility(View.INVISIBLE);
        }
    };
    private final Runnable commitSeek = this::commitSeek;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            Vb.MAIN.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Vb.init(this);
        try {
            item = Vb.Item.fromJson(new JSONObject(getIntent().getStringExtra("item_json")));
        } catch (Exception e) {
            finish();
            return;
        }
        imdbId = getIntent().getStringExtra("imdb_id");
        probe = getIntent().getStringExtra("probe");
        if (probe != null) {
            probeFlags = probe.replaceAll("[0-9]", "");
            probe = probe.replaceAll("[^0-9]", "");
        }
        season = getIntent().getIntExtra("season", 1);
        episode = getIntent().getIntExtra("episode", 1);
        seasonNumbers = getIntent().getIntArrayExtra("season_numbers");
        seasonCounts = getIntent().getIntArrayExtra("season_counts");
        if (b != null) {
            season = b.getInt("season", season);
            episode = b.getInt("episode", episode);
        } else if (getIntent().getBooleanExtra("startOver", false)) {
            Vb.clearPosition(key());
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        speed = Vb.prefs().getFloat("speed", 1f);

        root = new FrameLayout(this);
        root.setFocusable(true);
        root.setBackgroundColor(Color.BLACK);
        // The server's page, underneath everything: it has to be laid out and "visible" to run, but
        // it is never seen (the black cover is on top of it) and never gets the remote's keys.
        webHolder = new FrameLayout(this);
        webHolder.setDescendantFocusability(FrameLayout.FOCUS_BLOCK_DESCENDANTS);
        root.addView(webHolder, new FrameLayout.LayoutParams(-1, -1));
        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(VbUi.dp(this, 56), VbUi.dp(this, 56), Gravity.CENTER));

        center = VbUi.label(this, "", 20, Color.WHITE, true);
        center.setGravity(Gravity.CENTER);
        center.setPadding(VbUi.dp(this, 20), VbUi.dp(this, 10), VbUi.dp(this, 20), VbUi.dp(this, 10));
        center.setBackground(VbUi.round(0xD0000000, VbUi.dp(this, 12), 0, 0));
        center.setVisibility(View.GONE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        cl.topMargin = VbUi.dp(this, 90);
        root.addView(center, cl);

        // Controls: title on top, progress and buttons at the bottom, each on a flat dark band.
        FrameLayout ov = new FrameLayout(this);
        title = VbUi.label(this, "", 20, Color.WHITE, true);
        title.setSingleLine(true);
        title.setPadding(VbUi.dp(this, 40), VbUi.dp(this, 24), VbUi.dp(this, 40), VbUi.dp(this, 16));
        title.setBackgroundColor(0xB0000000);
        ov.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xB0000000);
        bottom.setPadding(VbUi.dp(this, 40), VbUi.dp(this, 14), VbUi.dp(this, 40), VbUi.dp(this, 24));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        now = VbUi.label(this, "0:00", 15, Color.WHITE, false);
        total = VbUi.label(this, "", 15, VbUi.DIM, false);
        bar = new Bar(this);
        line.addView(now, new LinearLayout.LayoutParams(VbUi.dp(this, 76), -2));
        line.addView(bar, new LinearLayout.LayoutParams(0, VbUi.dp(this, 22), 1f));
        total.setGravity(Gravity.END);
        line.addView(total, new LinearLayout.LayoutParams(VbUi.dp(this, 76), -2));
        bottom.addView(line);
        buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        boolean series = Vb.TYPE_TV.equals(item.type);
        if (series) button("Previous episode", v -> jump(-1));
        button("−10 s", v -> nudge(-1, 0));
        playBtn = button("Pause", v -> toggle());
        button("+10 s", v -> nudge(1, 0));
        if (series) button("Next episode", v -> jump(1));
        buttons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        speedBtn = button("", v -> nextSpeed());
        serverBtn = button("", v -> pickServer());
        bottom.addView(buttons, VbUi.lp(-1, -2, 0, VbUi.dp(this, 10), 0, 0));
        ov.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        overlay = ov;
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("season", season);
        out.putInt("episode", episode);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (item == null) return;
        begin();
        Vb.MAIN.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Vb.MAIN.removeCallbacks(tick);
        Vb.MAIN.removeCallbacks(hide);
        Vb.MAIN.removeCallbacks(commitSeek);
        save();
        generation++;
        prepared = false;
        closePage(); // also what stops the sound
    }

    private String key() {
        return Vb.playKey(item.type, item.id, season, episode);
    }

    private String heading() {
        return Vb.TYPE_TV.equals(item.type) ? item.title + "  ·  Season " + season + " Episode " + episode : item.title;
    }

    // ---------------------------------------------------------------- finding the stream

    /** Start this film or episode from scratch: the preferred server first, then the others in order. */
    private void begin() {
        order.clear();
        if (probe != null) order.add(Integer.parseInt(probe)); // testing one server: no fallback
        else order.addAll(Vb.serverOrder());
        retries = 0;
        failed = false;
        Vb.touchRecent(item, season, episode);
        long saved = Vb.position(key());
        resumeAt = Math.max(saved, 0);
        lastPos = resumeAt;
        title.setText(heading());
        nextServer(null);
    }

    /** Try the next server in the list, or give up if there is none left. */
    private void nextServer(String why) {
        if (why != null && serverIdx >= 0 && probe == null) Vb.serverResult(serverIdx, false, 0);
        if (why != null) Log.i(TAG, "server " + (serverIdx < 0 ? "?" : Vb.SERVERS.get(serverIdx).name) + " skipped: " + why);
        if (probe != null && why != null) Log.i(TAG, "PROBE " + probe + " FAIL " + why);
        if (order.isEmpty()) {
            failed = true;
            prepared = false;
            generation++;
            closePage();
            spinner.setVisibility(View.GONE);
            say("None of the servers has this title right now.\nOK to try again  ·  Back to leave");
            showControls();
            return;
        }
        serverIdx = order.remove(0);
        find();
    }

    /** Open the current server's page out of sight and wait for its player to ask for a stream. */
    private void find() {
        final int gen = ++generation;
        final Vb.Server server = Vb.SERVERS.get(serverIdx);
        prepared = false;
        started = false;
        seekTarget = -1;
        duration = 0;
        webBuffered = 0;
        webPaused = false;
        webEnded = false;
        webPos = webDur = 0;
        // Testing one server ("probe"): leave the page uncovered so a screenshot shows what it is doing.
        cover.setVisibility(probe != null && !probeFlags.contains("c") ? View.GONE : View.VISIBLE);
        waitingSince = 0;
        findStarted = System.currentTimeMillis();
        center.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        say("Finding the stream  ·  " + server.name);
        updateButtons();
        showControls();
        closePage();

        final String pageUrl = server.buildUrl(item.type, item.id, imdbId, season, episode);
        final String pageHost = Uri.parse(pageUrl).getHost();
        Log.i(TAG, "find: " + server.name + " " + pageUrl);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(Vb.UA);
        s.setSupportMultipleWindows(true); // so that pop-ups come to onCreateWindow, where they are refused
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message msg) {
                return false; // advertising pop-up
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // The page may only move within its own site; anything else is an advert taking over.
                if (!req.isForMainFrame()) return false;
                String h = req.getUrl().getHost();
                boolean same = h != null && pageHost != null && (h.equals(pageHost) || h.endsWith("." + root(pageHost)) || h.equals(root(pageHost)));
                if (!same) Log.i(TAG, "blocked navigation to " + h);
                return !same;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                // Not the main thread. Every request of the page and its frames passes here.
                Uri uri = req.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                for (String bad : BLOCKED) {
                    if (host.contains(bad)) {
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                    }
                }
                return null;
            }
        });
        webHolder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(pageUrl);

        // Many of these players wait for a click before they load anything.
        for (int k = 0; k < 5; k++) {
            Vb.MAIN.postDelayed(() -> {
                if (gen == generation && !prepared) tap();
            }, 4000 + k * 4000L);
        }
        Vb.MAIN.postDelayed(() -> {
            if (gen == generation && !prepared) nextServer("no video in " + FIND_MS / 1000 + " s");
        }, FIND_MS);
        pollPage(gen);
    }

    /** What the page's own video element is doing: "readyState,duration,position,paused,ended,width,buffered". */
    private static final String VIDEO_STATE = "(function(){var v=document.querySelector('video');"
            + "if(!v||!isFinite(v.duration))return '';"
            + "return [v.readyState,Math.floor(v.duration*1000),Math.floor(v.currentTime*1000),v.paused?1:0,v.ended?1:0,v.videoWidth,"
            + "v.buffered.length?Math.floor(v.buffered.end(v.buffered.length-1)*1000):0].join(',')})()";

    /** Ask the page every second, for as long as it is open, how its video is doing. */
    private void pollPage(final int gen) {
        if (gen != generation || web == null) return;
        web.evaluateJavascript(VIDEO_STATE, res -> {
            if (gen != generation || web == null) return;
            if (probe != null && !prepared) Log.i(TAG, "page video state: " + res);
            try {
                String[] p = res.replace("\"", "").split(",");
                if (p.length == 7) {
                    int ready = Integer.parseInt(p[0]);
                    webDur = Long.parseLong(p[1]);
                    webPos = Long.parseLong(p[2]);
                    webPaused = "1".equals(p[3]);
                    webEnded = "1".equals(p[4]);
                    webBuffered = Long.parseLong(p[6]);
                    // Longer than two minutes: a film, not an advert in front of it.
                    if (!prepared && ready >= 3 && webDur > 120_000 && Integer.parseInt(p[5]) > 0) showPage();
                    if (prepared) {
                        if (ready >= 3) {
                            if (waitingSince != 0) spinner.setVisibility(View.GONE);
                            waitingSince = 0;
                        } else if (waitingSince == 0) {
                            waitingSince = System.currentTimeMillis();
                            spinner.setVisibility(View.VISIBLE);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
        Vb.MAIN.postDelayed(() -> pollPage(gen), 1000);
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;" + code + "})()", null);
    }

    /** The page's own video has started: uncover the page and take over its video element. */
    private void showPage() {
        prepared = true;
        started = true;
        duration = webDur;
        long took = System.currentTimeMillis() - findStarted;
        Log.i(TAG, "playing after " + took + " ms on " + Vb.SERVERS.get(serverIdx).name);
        if (probe == null) Vb.serverResult(serverIdx, true, took);
        // Only the picture: the page's own buttons are for a mouse.
        web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;"
                + "var s=document.createElement('style');s.textContent='body *{visibility:hidden!important}video.vbx{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;"
                + "width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}';"
                + "document.head.appendChild(s);v.classList.add('vbx');v.controls=false;})()", null);
        if (resumeAt > 0 && resumeAt < duration - 5000) js("v.currentTime=" + resumeAt / 1000.0 + ";");
        js("v.muted=false;v.playbackRate=" + speed + ";v.play();");
        cover.setVisibility(View.GONE);
        spinner.setVisibility(View.GONE);
        center.setVisibility(View.GONE);
        updateButtons();
        showControls();
    }

    private boolean playing() {
        try {
            return prepared && !webPaused;
        } catch (Exception e) {
            return false;
        }
    }

    /** "embed.example.co" -> "example.co" (good enough to keep a site's own sub-domains together). */
    private static String root(String host) {
        String[] p = host.split("\\.");
        return p.length <= 2 ? host : p[p.length - 2] + "." + p[p.length - 1];
    }

    /** A touch in the middle of the hidden page, where the play button is. */
    private void tap() {
        if (web == null || web.getWidth() == 0) return;
        long t = SystemClock.uptimeMillis();
        float x = web.getWidth() / 2f, y = web.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private void closePage() {
        if (web == null) return;
        try {
            web.stopLoading();
            web.loadUrl("about:blank");
            webHolder.removeView(web);
            web.destroy();
        } catch (Exception ignored) {
        }
        web = null;
    }

    /** The video stopped: ask the same server again from where it was, then move on to the next. */
    private void playbackFailed(String why) {
        if (failed) return;
        Log.i(TAG, "playback failed (" + why + "), retry " + retries);
        save();
        prepared = false;
        resumeAt = lastPos;
        if (retries < 1) {
            retries++;
            find();
            return;
        }
        retries = 0;
        nextServer(why);
    }

    private void onCompletion() {
        if (!prepared || failed) return;
        webEnded = false;
        // A dropped connection can surface as an early "completion": treat that as a stall.
        if (duration > 0 && lastPos < duration - 20_000) {
            playbackFailed("ended early");
            return;
        }
        Vb.savePosition(key(), duration, duration);
        if (!jump(1)) finish();
    }

    private void save() {
        if (prepared && duration > 0 && item != null && lastPos > 0) Vb.savePosition(key(), lastPos, duration);
    }

    private void onTick() {
        long nowMs = System.currentTimeMillis();
        if (prepared) {
            try {
                long p = webPos;
                if (webDur > 0) duration = webDur;
                if (seekTarget < 0) {
                    if (p != lastPos && playing()) {
                        waitingSince = 0;
                        if (retries > 0 && p > resumeAt + 15_000) retries = 0;
                        if (probe != null && !probe.equals("done") && p > 3000) {
                            Log.i(TAG, "PROBE " + probe + " OK pos=" + p + " dur=" + duration);
                            probe = "done";
                        }
                    }
                    lastPos = p;
                }
            } catch (Exception ignored) {
            }
            long shownPos = seekTarget >= 0 ? seekTarget : lastPos;
            now.setText(Vb.time(shownPos));
            total.setText(duration > 0 ? Vb.time(duration) : "");
            bar.set(duration > 0 ? shownPos / (float) duration : 0, duration > 0 ? webBuffered / (float) duration : 0);
            if (nowMs - lastSave > 5000) {
                lastSave = nowMs;
                save();
            }
            if (webEnded) onCompletion();
        }
        if (!failed && prepared && waitingSince > 0 && nowMs - waitingSince > STALL_MS) {
            waitingSince = nowMs;
            playbackFailed("stalled");
        }
    }

    // ---------------------------------------------------------------- controls

    private TextView button(String label, View.OnClickListener click) {
        TextView t = VbUi.pill(this, label);
        t.setOnClickListener(click);
        buttons.addView(t, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
        return t;
    }

    private void updateButtons() {
        playBtn.setText(playing() || !prepared ? "Pause" : "Play");
        String s = speed == Math.round(speed) ? String.valueOf(Math.round(speed)) : String.valueOf(speed);
        speedBtn.setText("Speed " + s + "×");
        serverBtn.setText("Server: " + (serverIdx < 0 ? "…" : Vb.SERVERS.get(serverIdx).name));
    }

    private void applySpeed() {
        if (prepared) js("v.playbackRate=" + speed + ";");
    }

    private void nextSpeed() {
        int i = 2;
        for (int k = 0; k < SPEEDS.length; k++) if (Math.abs(SPEEDS[k] - speed) < 0.01f) i = k;
        speed = SPEEDS[(i + 1) % SPEEDS.length];
        Vb.prefs().edit().putFloat("speed", speed).apply();
        applySpeed();
        updateButtons();
        showControls();
    }

    private void say(String s) {
        center.setText(s);
        center.setVisibility(View.VISIBLE);
    }

    private void showControls() {
        overlay.setVisibility(View.VISIBLE);
        Vb.MAIN.removeCallbacks(hide);
        Vb.MAIN.postDelayed(hide, 4000);
    }

    private void toggle() {
        if (failed) {
            begin();
            return;
        }
        if (!prepared) return;
        if (playing()) {
            js("v.pause();");
            webPaused = true;
            save();
            say("Paused");
        } else {
            js("v.play();");
            webPaused = false;
            center.setVisibility(View.GONE);
        }
        updateButtons();
        showControls();
    }

    /** Accumulate D-pad presses into one seek; holding the key speeds it up. */
    private void nudge(int dir, int repeat) {
        if (!prepared || duration <= 0) return;
        long step = repeat < 8 ? 10_000 : repeat < 25 ? 30_000 : 60_000;
        if (seekTarget < 0) seekTarget = lastPos;
        seekTarget = Math.max(0, Math.min(duration - 3000, seekTarget + dir * step));
        long delta = seekTarget - lastPos;
        say((delta >= 0 ? "+" : "−") + Vb.time(Math.abs(delta)) + "   →   " + Vb.time(seekTarget));
        now.setText(Vb.time(seekTarget));
        bar.set(seekTarget / (float) duration, webBuffered / (float) duration);
        showControls();
        Vb.MAIN.removeCallbacks(commitSeek);
        Vb.MAIN.postDelayed(commitSeek, 450);
    }

    private void commitSeek() {
        if (!prepared || seekTarget < 0) return;
        js("v.currentTime=" + seekTarget / 1000.0 + ";");
        lastPos = webPos = seekTarget;
        seekTarget = -1;
        center.setVisibility(View.GONE);
        Vb.MAIN.postDelayed(hide, 4000);
    }

    private void pickServer() {
        String[] names = new String[Vb.SERVERS.size()];
        for (int i = 0; i < names.length; i++) names[i] = Vb.SERVERS.get(i).name;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Play from")
                .setSingleChoiceItems(names, Math.max(serverIdx, 0), (d, which) -> {
                    d.dismiss();
                    if (which == serverIdx && !failed) return;
                    save();
                    resumeAt = lastPos;
                    failed = false;
                    retries = 0;
                    // The chosen one first; if it has nothing, carry on through the rest.
                    order.clear();
                    for (int i : Vb.serverOrder()) if (i != which) order.add(i);
                    serverIdx = which;
                    find();
                })
                .show();
    }

    /** Go to the next (+1) or previous (-1) episode, across seasons. False if there is none. */
    private boolean jump(int by) {
        if (!Vb.TYPE_TV.equals(item.type) || seasonNumbers == null) return false;
        int si = -1;
        for (int i = 0; i < seasonNumbers.length; i++) if (seasonNumbers[i] == season) si = i;
        if (si < 0) return false;
        int s = season, e = episode + by;
        if (e < 1) {
            if (si == 0) return false;
            s = seasonNumbers[si - 1];
            e = Math.max(seasonCounts[si - 1], 1);
        } else if (e > seasonCounts[si]) {
            if (si + 1 >= seasonNumbers.length) return false;
            s = seasonNumbers[si + 1];
            e = 1;
        }
        save();
        season = s;
        episode = e;
        begin();
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Any key keeps the controls up a little longer.
        if (e.getAction() == KeyEvent.ACTION_DOWN && overlay != null && overlay.getVisibility() == View.VISIBLE) {
            Vb.MAIN.removeCallbacks(hide);
            Vb.MAIN.postDelayed(hide, 5000);
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (buttons != null && buttons.hasFocus()) {
            root.requestFocus(); // first Back leaves the button row, the next one leaves the player
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (buttons != null && buttons.hasFocus()) {
            // In the button row Left/Right move between buttons (default focus handling) and OK
            // presses the focused one; only Up and Down mean something here.
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                root.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Move along the row and stop at its ends, so focus can never drop out into seeking.
                View cur = buttons.getFocusedChild();
                View next = cur == null ? null
                        : cur.focusSearch(code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                if (next != null && next.getParent() == buttons) next.requestFocus();
                return true;
            }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (e.getRepeatCount() == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (prepared && !playing()) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (playing()) toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                nudge(-1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                nudge(1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showControls();
                playBtn.requestFocus();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                jump(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                jump(-1);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    /** Progress track: buffered part behind the played part. */
    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float played, loaded;

        Bar(Context c) {
            super(c);
        }

        void set(float p, float b) {
            played = Math.max(0, Math.min(1, p));
            loaded = Math.max(0, Math.min(1, b));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), t = h * 0.22f, cy = h / 2f;
            r.set(0, cy - t / 2, w, cy + t / 2);
            paint.setColor(0xFF3A3A42);
            c.drawRoundRect(r, t, t, paint);
            r.right = w * Math.max(loaded, played);
            paint.setColor(0xFF77777F);
            c.drawRoundRect(r, t, t, paint);
            r.right = w * played;
            paint.setColor(VbUi.ACCENT);
            c.drawRoundRect(r, t, t, paint);
            paint.setColor(Color.WHITE);
            c.drawCircle(Math.max(h / 3, Math.min(w - h / 3, w * played)), cy, h / 3, paint);
        }
    }
}
