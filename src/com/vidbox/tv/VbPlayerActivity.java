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
 * episode, back 10 s, play/pause, forward 10 s, next episode, subtitles, speed, server. Up or Back
 * leaves it.
 *
 * Subtitles are the app's own ({@link VbSubs}), drawn inside the page over the picture and timed by
 * the page's own video clock.
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
    private TextView title, now, total, center, playBtn, speedBtn, serverBtn, subBtn;

    // Subtitles
    private static final String[] SUB_MODES = {"Off", "English", "فارسی", "English + فارسی"};
    private int subMode;            // index in SUB_MODES
    private int subOffset;          // ms; positive = the words come later
    private boolean subHasOwn;      // the server carries subtitle tracks of its own for this stream
    private List<VbSubs.File> subFilesEn, subFilesFa;
    private VbSubs.Track subEn, subFa;
    private String subKey;          // the film or episode the fields above belong to
    private boolean subLoading, subLifted;
    private android.app.Dialog subDialog;
    private TextView subInfo;
    private LinearLayout buttons;
    private float speed = 1f;
    private ProgressBar spinner;
    private Bar bar;

    private final Runnable hide = () -> {
        if (playing() && seekTarget < 0) {
            if (buttons.hasFocus()) root.requestFocus();
            overlay.setVisibility(View.INVISIBLE);
            liftSubs(false);
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
        subMode = Math.max(0, Math.min(SUB_MODES.length - 1, Vb.prefs().getInt("sub_mode", 2)));

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
        subBtn = button("", v -> subtitlePanel());
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
        if (subDialog != null) subDialog.dismiss();
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
        // Testing ("probe"): log what the subtitle box shows against the film's clock, to check timing.
        if (probe != null && prepared) {
            web.evaluateJavascript("(function(){var v=document.querySelector('video'),S=window.__vbx;if(!v||!S)return '';"
                    + "var k=S.box.children;return Math.round(v.currentTime*1000)+' | '+k[0].textContent.replace(/\\n/g,' / ')+' | '+k[1].textContent.replace(/\\n/g,' / ');})()",
                    r -> {
                        if (r != null && r.length() > 4) Log.i(TAG, "SUB " + r);
                    });
        }
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
        loadSubs();
    }

    // ---------------------------------------------------------------- subtitles

    /** Where this title's timing correction is kept: per film, or per season of a series. */
    private String subPrefKey() {
        return item.type + item.id + (Vb.TYPE_TV.equals(item.type) ? "_s" + season : "");
    }

    /**
     * The page's own English and Persian subtitle tracks, as {@code {n, en, fa}}: n tracks in all;
     * en / fa are the lines, or "wait" while the page is still fetching them, or null if it has
     * no such track. Asking wakes a track up ("hidden" loads it without the page drawing it).
     */
    private static final String PAGE_TRACKS = "(function(){var v=document.querySelector('video');if(!v)return '{}';"
            + "var pick=function(re){for(var i=0;i<v.textTracks.length;i++){var t=v.textTracks[i];"
            + "if(t.kind!=='subtitles'&&t.kind!=='captions')continue;if(re.test(t.language||'')||re.test(t.label||''))return t;}return null;};"
            + "var read=function(t){if(!t)return null;if(t.mode==='disabled')t.mode='hidden';if(!t.cues||t.cues.length<5)return 'wait';"
            + "var o=[];for(var j=0;j<t.cues.length;j++){var c=t.cues[j];o.push([Math.round(c.startTime*1000),Math.round(c.endTime*1000),c.text||'']);}return o;};"
            + "return JSON.stringify({n:v.textTracks.length,en:read(pick(/^en|english/i)),fa:read(pick(/^fa$|^fa-|^per|^fas|persian|farsi|\u0641\u0627\u0631\u0633/i))});})()";

    /** Work out this film's subtitles for the server now playing (once), then draw them. */
    private void loadSubs() {
        final String k = key() + "@" + serverIdx;
        if (k.equals(subKey)) {
            applySubs(); // the same page again
            return;
        }
        subKey = k;
        subEn = subFa = null;
        subFilesEn = subFilesFa = null;
        subHasOwn = false;
        subOffset = Vb.prefs().getInt("sub_off_" + subPrefKey(), 0);
        subLoading = true;
        updateButtons();
        readPageTracks(k, 0);
    }

    /** First the stream's own tracks: they are the ones whose timing is certain. */
    private void readPageTracks(final String k, final int tries) {
        if (!k.equals(subKey) || web == null) return;
        web.evaluateJavascript(PAGE_TRACKS, res -> {
            if (!k.equals(subKey) || isFinishing()) return;
            VbSubs.Track en = null, fa = null;
            boolean wait = false;
            int n = 0;
            try {
                JSONObject o = new JSONObject(new org.json.JSONArray("[" + res + "]").getString(0));
                n = o.optInt("n");
                Object e = o.opt("en"), f = o.opt("fa");
                if (e instanceof org.json.JSONArray) en = VbSubs.fromPage((org.json.JSONArray) e);
                if (f instanceof org.json.JSONArray) fa = VbSubs.fromPage((org.json.JSONArray) f);
                wait = "wait".equals(e) || "wait".equals(f);
            } catch (Exception ignored) {
            }
            // Players add their tracks a moment after the film starts, and fetch the lines on demand.
            if ((wait && tries < 20) || (n == 0 && tries < 8)) {
                Vb.MAIN.postDelayed(() -> readPageTracks(k, tries + 1), 1000);
                return;
            }
            Log.i(TAG, "subtitles: the stream has " + n + " tracks; English " + (en == null ? "no" : en.cues.size() + " lines")
                    + ", Persian " + (fa == null ? "no" : fa.cues.size() + " lines"));
            // What the stream carries can be shown at once; the rest follows when it has been found.
            subEn = en;
            subFa = fa;
            subHasOwn = en != null || fa != null;
            applySubs();
            readFiles(k, en, fa);
        });
    }

    /** Then downloaded files for whatever the stream does not carry, measured against what it does. */
    private void readFiles(final String k, final VbSubs.Track ownEn, final VbSubs.Track ownFa) {
        final boolean series = Vb.TYPE_TV.equals(item.type);
        final int se = season, ep = episode;
        final long dur = duration;
        final String pk = key();
        final int pickEn = Vb.prefs().getInt("sub_pick_" + pk + "_en", 0), pickFa = Vb.prefs().getInt("sub_pick_" + pk + "_fa", 0);
        Vb.async(() -> {
            Object[] out = new Object[4];
            String imdb = imdbId;
            if (imdb == null || imdb.length() == 0) imdb = Vb.detail(item.type, item.id).imdbId;
            if (imdb == null || imdb.length() == 0) return out;
            VbSubs.Track en = ownEn, fa = ownFa;
            if (en == null) {
                try {
                    List<VbSubs.File> files = VbSubs.list(imdb, series, se, ep, VbSubs.EN);
                    out[0] = files;
                    // No English track on the stream: an English file is right only if it fits the Persian track.
                    if (!files.isEmpty()) en = VbSubs.pick(files, pickEn, dur, ownFa == null ? null : ownFa.cues, true, ownFa != null);
                } catch (Exception e) {
                    Log.i(TAG, "subtitles (en): " + e);
                }
            }
            if (fa == null) {
                try {
                    List<VbSubs.File> files = VbSubs.list(imdb, series, se, ep, VbSubs.FA);
                    out[1] = files;
                    // Against the stream's English if there is one (then it must fit); else against the English file.
                    if (!files.isEmpty()) fa = VbSubs.pick(files, pickFa, dur, en == null ? null : en.cues, en != null && en.checked, ownEn != null);
                } catch (Exception e) {
                    Log.i(TAG, "subtitles (fa): " + e);
                }
            }
            // No Persian that fits: translate the English, which keeps its timing line for line.
            if (fa == null && en != null) {
                try {
                    fa = VbSubs.translate(en);
                } catch (Exception e) {
                    Log.i(TAG, "subtitles (translation): " + e);
                }
            }
            out[2] = en;
            out[3] = fa;
            return out;
        }, (out, err) -> {
            if (!k.equals(subKey) || isFinishing()) return;
            subLoading = false;
            subHasOwn = ownEn != null || ownFa != null;
            if (out != null) {
                @SuppressWarnings("unchecked") List<VbSubs.File> en = (List<VbSubs.File>) out[0];
                @SuppressWarnings("unchecked") List<VbSubs.File> fa = (List<VbSubs.File>) out[1];
                subFilesEn = en;
                subFilesFa = fa;
                subEn = (VbSubs.Track) out[2];
                subFa = (VbSubs.Track) out[3];
            } else {
                subEn = ownEn;
                subFa = ownFa;
            }
            Log.i(TAG, "subtitles: English " + describe(subEn, subFilesEn) + " | Persian " + describe(subFa, subFilesFa));
            applySubs();
        });
    }

    /** One line about a language's subtitles, for the panel and the log. */
    private String describe(VbSubs.Track t, List<VbSubs.File> files) {
        if (t == null) {
            if (files == null || files.isEmpty()) return "none found";
            return subHasOwn ? "none of the " + files.size() + " files fits this copy of the film" : "none usable";
        }
        if (t.own) return "the stream's own (in time)";
        if (t.machine) return "machine translation of the English" + (t.checked ? " (in time)" : " (timing not checked)");
        String which = "file " + (t.index + 1) + " of " + t.count;
        if (t.checked) {
            return which + ", fitted to the stream (" + String.format(java.util.Locale.US, "%+.1f s", t.shift / 1000f)
                    + (t.scale != 1 ? ", stretched" : "") + ")";
        }
        return which + ", timing not checked";
    }

    /**
     * Hand the lines to the page. They are drawn there, in a box over the picture, by a timer
     * that reads the video's own clock ten times a second, so they follow seeking, pausing and
     * speed without the app having to keep time.
     */
    private void applySubs() {
        updateButtons();
        refreshSubPanel();
        if (web == null || !prepared) return;
        boolean en = subMode == 1 || subMode == 3, fa = subMode == 2 || subMode == 3;
        // Asked for Persian but there is none (or the other way round): show the one that exists.
        if (fa && !en && subFa == null && subEn != null) en = true;
        if (en && !fa && subEn == null && subFa != null) fa = true;
        String a = VbSubs.js(en ? subEn : null), b = VbSubs.js(fa ? subFa : null);
        web.evaluateJavascript("(function(){var S=window.__vbx;if(!S){"
                + "var d=document.createElement('div');d.id='vbxsub';"
                + "d.style.cssText='visibility:visible!important;position:fixed!important;left:6vw;right:6vw;bottom:6vh;z-index:2147483647!important;"
                + "text-align:center;pointer-events:none;font-family:sans-serif;font-weight:700;color:#fff;font-size:4.6vh;line-height:1.35;"
                + "white-space:pre-line;text-shadow:0 0 5px #000,2px 2px 3px #000,-2px -2px 3px #000,2px -2px 3px #000,-2px 2px 3px #000;transition:bottom .15s';"
                + "var mk=function(){var e=document.createElement('div');e.dir='auto';e.style.cssText='visibility:visible!important';d.appendChild(e);return e;};"
                + "var ea=mk(),eb=mk();(document.body||document.documentElement).appendChild(d);"
                + "S=window.__vbx={A:[],B:[],off:0,box:d};"
                + "var at=function(c,t){var lo=0,hi=c.length-1;while(lo<=hi){var m=(lo+hi)>>1;if(c[m][1]<=t)lo=m+1;else if(c[m][0]>t)hi=m-1;else return c[m][2];}return '';};"
                + "setInterval(function(){var v=document.querySelector('video');if(!v)return;var t=v.currentTime*1000-S.off;"
                + "var x=at(S.A,t),y=at(S.B,t);if(ea.textContent!==x)ea.textContent=x;if(eb.textContent!==y)eb.textContent=y;},100);}"
                + "S.A=" + a + ";S.B=" + b + ";S.off=" + subOffset + ";S.box.style.bottom='" + (subLifted ? "27vh" : "6vh") + "';"
                // Two languages at once are four lines: a little smaller, so they cover less of the picture.
                + "S.box.style.fontSize='" + (en && fa ? "3.9vh" : "4.6vh") + "';})()", null);
    }

    /** Keep the words clear of the control band while it is up. */
    private void liftSubs(boolean up) {
        if (subLifted == up) return;
        subLifted = up;
        if (web != null && prepared) {
            web.evaluateJavascript("(function(){var S=window.__vbx;if(S)S.box.style.bottom='" + (up ? "27vh" : "6vh") + "';})()", null);
        }
    }

    private void setSubMode(int m) {
        subMode = m;
        Vb.prefs().edit().putInt("sub_mode", m).apply();
        applySubs();
    }

    private void moveSubs(int byMs) {
        subOffset += byMs;
        Vb.prefs().edit().putInt("sub_off_" + subPrefKey(), subOffset).apply();
        if (web != null && prepared) web.evaluateJavascript("(function(){var S=window.__vbx;if(S)S.off=" + subOffset + ";})()", null);
        refreshSubPanel();
    }

    /** Take the next file of a language. The stream's own track has no other file. */
    private void otherFile(final String lang) {
        final boolean en = VbSubs.EN.equals(lang);
        final List<VbSubs.File> files = en ? subFilesEn : subFilesFa;
        final VbSubs.Track cur = en ? subEn : subFa, other = en ? subFa : subEn;
        if (files == null || files.isEmpty() || subLoading || (cur != null && cur.own)) return;
        if (files.size() < 2 && cur != null && !cur.machine) return;
        final String k = subKey, pk = key();
        final int from = ((cur == null ? -1 : cur.index) + 1) % files.size();
        final long dur = duration;
        // It has to fit the other language whenever that one's timing is certain.
        final boolean must = other != null && other.checked;
        subLoading = true;
        refreshSubPanel();
        Vb.async(() -> VbSubs.pick(files, from, dur, other == null ? null : other.cues, must, must), (t, err) -> {
            if (!k.equals(subKey) || isFinishing()) return;
            subLoading = false;
            if (t != null) {
                if (en) subEn = t;
                else subFa = t;
                Vb.prefs().edit().putInt("sub_pick_" + pk + "_" + lang, t.index).apply();
            }
            applySubs();
        });
    }

    private String subTiming() {
        if (subOffset == 0) return "Timing: as in the file";
        return String.format(java.util.Locale.US, "Timing: words %.1f s %s", Math.abs(subOffset) / 1000f, subOffset > 0 ? "later" : "earlier");
    }

    private void refreshSubPanel() {
        if (subInfo == null || subDialog == null || !subDialog.isShowing()) return;
        StringBuilder sb = new StringBuilder();
        if (subLoading) sb.append("Looking for subtitles…");
        else {
            sb.append("English: ").append(describe(subEn, subFilesEn));
            sb.append("\nPersian: ").append(describe(subFa, subFilesFa));
            if (!subHasOwn) sb.append("\nThis server carries no subtitles of its own, so timing cannot be checked. Another server may.");
        }
        sb.append("\n").append(subTiming());
        subInfo.setText(sb.toString());
    }

    /** The subtitle panel: language, timing, another file. It sits in a corner so the film stays in view. */
    private void subtitlePanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = VbUi.dp(this, 18);
        col.setPadding(pad, pad, pad, pad);
        col.setBackground(VbUi.round(0xF0101014, VbUi.dp(this, 14), 0, 0));
        col.addView(VbUi.label(this, "Subtitles", 18, Color.WHITE, true));

        final List<TextView> modes = new ArrayList<>();
        LinearLayout modeRow = new LinearLayout(this);
        for (int i = 0; i < SUB_MODES.length; i++) {
            final int m = i;
            final TextView t = VbUi.pill(this, SUB_MODES[i]);
            t.setSelected(i == subMode);
            VbUi.restyle(t);
            t.setOnClickListener(v -> {
                setSubMode(m);
                for (int j = 0; j < modes.size(); j++) {
                    modes.get(j).setSelected(j == m);
                    VbUi.restyle(modes.get(j));
                }
            });
            modes.add(t);
            modeRow.addView(t, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
        }
        col.addView(modeRow, VbUi.lp(-2, -2, 0, VbUi.dp(this, 12), 0, 0));

        LinearLayout timeRow = new LinearLayout(this);
        TextView earlier = VbUi.pill(this, "Earlier  −0.5 s");
        earlier.setOnClickListener(v -> moveSubs(-500));
        TextView later = VbUi.pill(this, "Later  +0.5 s");
        later.setOnClickListener(v -> moveSubs(500));
        TextView reset = VbUi.pill(this, "Reset");
        reset.setOnClickListener(v -> moveSubs(-subOffset));
        timeRow.addView(earlier, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
        timeRow.addView(later, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
        timeRow.addView(reset);
        col.addView(timeRow, VbUi.lp(-2, -2, 0, VbUi.dp(this, 10), 0, 0));

        LinearLayout fileRow = new LinearLayout(this);
        TextView otherEn = VbUi.pill(this, "Another English file");
        otherEn.setOnClickListener(v -> otherFile(VbSubs.EN));
        TextView otherFa = VbUi.pill(this, "Another Persian file");
        otherFa.setOnClickListener(v -> otherFile(VbSubs.FA));
        fileRow.addView(otherEn, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
        fileRow.addView(otherFa);
        col.addView(fileRow, VbUi.lp(-2, -2, 0, VbUi.dp(this, 10), 0, 0));

        subInfo = VbUi.label(this, "", 14, VbUi.DIM, false);
        col.addView(subInfo, VbUi.lp(-2, -2, 0, VbUi.dp(this, 12), 0, 0));

        subDialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        subDialog.setContentView(col);
        android.view.Window w = subDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0f);
            w.setGravity(Gravity.TOP | Gravity.RIGHT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.x = VbUi.dp(this, 32);
            lp.y = VbUi.dp(this, 32);
            w.setAttributes(lp);
        }
        subDialog.setOnDismissListener(d -> {
            subInfo = null;
            showControls();
        });
        subDialog.show();
        refreshSubPanel();
        modes.get(subMode).requestFocus();
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
        String sub;
        if (subMode == 0) sub = "Off";
        else if (!prepared || (subLoading && subEn == null && subFa == null)) sub = "…";
        else if (subEn == null && subFa == null) sub = "none found";
        else {
            boolean en = subMode == 1 || subMode == 3, fa = subMode == 2 || subMode == 3;
            if (fa && subFa == null) sub = "English (no فارسی fits)";
            else if (en && subEn == null) sub = "فارسی (no English)";
            else sub = SUB_MODES[subMode];
            if (fa && subFa != null && subFa.machine) sub += " (machine)";
            boolean checked = (!en || subEn == null || subEn.checked) && (!fa || subFa == null || subFa.checked);
            if (!checked) sub += " · unchecked";
        }
        subBtn.setText("Subtitles: " + sub);
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
        liftSubs(true);
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
