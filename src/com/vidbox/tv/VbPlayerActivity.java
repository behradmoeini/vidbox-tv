package com.vidbox.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
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
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fullscreen hardware-accelerated streaming player for Android TV.
 *
 * Renders Vidbox server embeds (Echo, Max, 4K, Vidfast, V2, Vidpro, Premium, Nxsha, etc.)
 * in a high-performance TV-optimized WebView with remote D-pad controls, ad-blocking,
 * auto next-episode, and watch progress tracking.
 */
public class VbPlayerActivity extends Activity {
    private Vb.Item item;
    private String type;
    private long tmdbId;
    private String imdbId;
    private int season = 1;
    private int episode = 1;
    private String title = "";
    private int currentServerIdx = 0;
    private long resumePosition = 0;

    private FrameLayout rootLayout;
    private WebView webView;
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private View customView;

    private ProgressBar loadingSpinner;
    private TextView seekOsd;
    private LinearLayout controlHud;
    private TextView hudTitle;
    private TextView hudServerBtn;
    private TextView hudPrevBtn;
    private TextView hudNextBtn;
    private TextView hudReloadBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideHudRunnable = this::hideHud;
    private final Runnable hideSeekOsdRunnable = () -> seekOsd.setVisibility(View.GONE);
    private boolean isHudVisible = false;

    // Periodic progress saver
    private final Runnable progressCheckRunnable = new Runnable() {
        @Override
        public void run() {
            queryProgress();
            handler.postDelayed(this, 5000);
        }
    };

    // Known ad & popunder hosts to block on TV
    private static final Set<String> BLOCKED_HOSTS = new HashSet<>(Arrays.asList(
            "adsterra.com", "popads.net", "propellerads.com", "doubleclick.net",
            "googleads.g.doubleclick.net", "exoclick.com", "bet365", "1xbet",
            "adkeeper.co.uk", "juicyads.com", "clickadu.com", "popcash.net",
            "hilltopads.com", "trafficjunky.com", "monetag.com"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Vb.init(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // Read extras
        tmdbId = getIntent().getLongExtra("id", 0);
        type = getIntent().getStringExtra("type");
        title = getIntent().getStringExtra("title");
        imdbId = getIntent().getStringExtra("imdb_id");
        season = getIntent().getIntExtra("season", 1);
        episode = getIntent().getIntExtra("episode", 1);
        resumePosition = getIntent().getLongExtra("resume_pos", 0);
        String serverName = getIntent().getStringExtra("server_name");

        String itemJson = getIntent().getStringExtra("item_json");
        if (itemJson != null) {
            try {
                item = Vb.Item.fromJson(new JSONObject(itemJson));
            } catch (Exception ignored) {}
        }
        if (item == null) {
            item = new Vb.Item();
            item.id = tmdbId;
            item.type = type != null ? type : Vb.TYPE_MOVIE;
            item.title = title != null ? title : "Title";
        }

        // Find server index
        if (serverName != null) {
            for (int i = 0; i < Vb.SERVERS.size(); i++) {
                if (Vb.SERVERS.get(i).name.equals(serverName)) {
                    currentServerIdx = i;
                    break;
                }
            }
        }

        buildPlayerView();
        loadCurrentStream();
        handler.postDelayed(progressCheckRunnable, 5000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
    }

    // ---------------------------------------------------------------- Player View Setup

    private void buildPlayerView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        setContentView(rootLayout);

        // 1. Hardware Accelerated TV WebView
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.BLACK);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(Vb.UA);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // ChromeClient for fullscreen video
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                customViewContainer.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                customViewContainer.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                webView.setVisibility(View.VISIBLE);
                customViewContainer.setVisibility(View.GONE);
                customViewContainer.removeView(customView);
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customView = null;
                customViewCallback = null;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                return true;
            }
        });

        // WebViewClient with ad-blocking and styling injection
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loadingSpinner.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadingSpinner.setVisibility(View.GONE);
                injectTvOptimizationStyles();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                String host = uri.getHost();
                if (host != null) {
                    String h = host.toLowerCase();
                    for (String blocked : BLOCKED_HOSTS) {
                        if (h.contains(blocked)) {
                            // Block ad request
                            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                        }
                    }
                }
                return super.shouldInterceptRequest(view, req);
            }
        });

        rootLayout.addView(webView);

        // 2. Custom View Container (for native fullscreen HTML5 video tag)
        customViewContainer = new FrameLayout(this);
        customViewContainer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        customViewContainer.setBackgroundColor(Color.BLACK);
        customViewContainer.setVisibility(View.GONE);
        rootLayout.addView(customViewContainer);

        // 3. Loading Spinner
        loadingSpinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(VbUi.dp(this, 54), VbUi.dp(this, 54), Gravity.CENTER);
        loadingSpinner.setLayoutParams(spinLp);
        rootLayout.addView(loadingSpinner);

        // 4. Seek OSD Feedback overlay
        seekOsd = new TextView(this);
        seekOsd.setTextColor(VbUi.TEXT_WHITE);
        seekOsd.setTextSize(22);
        seekOsd.setTypeface(null, android.graphics.Typeface.BOLD);
        seekOsd.setPadding(VbUi.dp(this, 24), VbUi.dp(this, 12), VbUi.dp(this, 24), VbUi.dp(this, 12));
        seekOsd.setBackground(VbUi.roundRect(0xCC090D16, VbUi.dp(this, 12), 0, 0));
        seekOsd.setVisibility(View.GONE);
        FrameLayout.LayoutParams seekLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        seekOsd.setLayoutParams(seekLp);
        rootLayout.addView(seekOsd);

        // 5. On-Screen Display Control HUD (Down arrow brings this up)
        buildControlHud();
    }

    private void buildControlHud() {
        controlHud = new LinearLayout(this);
        controlHud.setOrientation(LinearLayout.VERTICAL);
        controlHud.setPadding(VbUi.dp(this, 32), VbUi.dp(this, 16), VbUi.dp(this, 32), VbUi.dp(this, 20));
        controlHud.setBackground(VbUi.gradientRect(0x00000000, 0xF0090D16, android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, 0));
        controlHud.setVisibility(View.GONE);

        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        controlHud.setLayoutParams(hudLp);

        // Title line
        hudTitle = new TextView(this);
        updateHudTitle();
        hudTitle.setTextColor(VbUi.TEXT_WHITE);
        hudTitle.setTextSize(18);
        hudTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        hudTitle.setPadding(0, 0, 0, VbUi.dp(this, 10));
        controlHud.addView(hudTitle);

        // Button row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        // Server switcher button
        hudServerBtn = createHudButton("Server: " + Vb.SERVERS.get(currentServerIdx).name + " ▾");
        hudServerBtn.setOnClickListener(v -> showServerPicker());
        btnRow.addView(hudServerBtn);

        // If TV show: Previous Episode & Next Episode buttons
        if (Vb.TYPE_TV.equals(type)) {
            hudPrevBtn = createHudButton("⏮ Prev Ep");
            hudPrevBtn.setOnClickListener(v -> {
                if (episode > 1) {
                    episode--;
                    updateHudTitle();
                    loadCurrentStream();
                } else {
                    Toast.makeText(this, "First episode", Toast.LENGTH_SHORT).show();
                }
            });
            btnRow.addView(hudPrevBtn);

            hudNextBtn = createHudButton("Next Ep ⏭");
            hudNextBtn.setOnClickListener(v -> {
                episode++;
                updateHudTitle();
                loadCurrentStream();
            });
            btnRow.addView(hudNextBtn);
        }

        // Reload Stream button
        hudReloadBtn = createHudButton("↻ Reload");
        hudReloadBtn.setOnClickListener(v -> loadCurrentStream());
        btnRow.addView(hudReloadBtn);

        controlHud.addView(btnRow);
        rootLayout.addView(controlHud);
    }

    private TextView createHudButton(String label) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(VbUi.TEXT_WHITE);
        btn.setTextSize(14);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setPadding(VbUi.dp(this, 16), VbUi.dp(this, 8), VbUi.dp(this, 16), VbUi.dp(this, 8));
        btn.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 8), VbUi.dp(this, 1), 0x33FFFFFF));

        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                btn.setTextColor(0xFFFFFFFF);
                btn.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 8), VbUi.dp(this, 2), VbUi.ACCENT));
                VbUi.animateFocus(v, true, 1.06f);
            } else {
                btn.setTextColor(VbUi.TEXT_WHITE);
                btn.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 8), VbUi.dp(this, 1), 0x33FFFFFF));
                VbUi.animateFocus(v, false, 1.0f);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, VbUi.dp(this, 12), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void updateHudTitle() {
        if (hudTitle == null) return;
        if (Vb.TYPE_TV.equals(type)) {
            hudTitle.setText(String.format("%s — Season %d, Episode %d", title, season, episode));
        } else {
            hudTitle.setText(title);
        }
    }

    private void showHud() {
        isHudVisible = true;
        controlHud.setVisibility(View.VISIBLE);
        hudServerBtn.requestFocus();
        handler.removeCallbacks(hideHudRunnable);
        handler.postDelayed(hideHudRunnable, 5000);
    }

    private void hideHud() {
        isHudVisible = false;
        controlHud.setVisibility(View.GONE);
        webView.requestFocus();
    }

    private void showServerPicker() {
        String[] serverNames = new String[Vb.SERVERS.size()];
        for (int i = 0; i < Vb.SERVERS.size(); i++) {
            serverNames[i] = Vb.SERVERS.get(i).name;
        }

        new AlertDialog.Builder(this)
                .setTitle("Switch Streaming Server")
                .setSingleChoiceItems(serverNames, currentServerIdx, (dialog, which) -> {
                    currentServerIdx = which;
                    hudServerBtn.setText("Server: " + Vb.SERVERS.get(currentServerIdx).name + " ▾");
                    loadCurrentStream();
                    dialog.dismiss();
                    hideHud();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- Stream Loading

    private void loadCurrentStream() {
        Vb.Server server = Vb.SERVERS.get(currentServerIdx);
        String streamUrl = server.buildUrl(type, tmdbId, imdbId, season, episode);

        loadingSpinner.setVisibility(View.VISIBLE);
        webView.loadUrl(streamUrl);

        Toast.makeText(this, "Playing via " + server.name, Toast.LENGTH_SHORT).show();
    }

    private void injectTvOptimizationStyles() {
        // Inject CSS to ensure video fits screen and unwanted ad overlays are hidden
        String js = "javascript:(function() {" +
                "  var style = document.createElement('style');" +
                "  style.innerHTML = 'video { width: 100vw !important; height: 100vh !important; object-fit: contain !important; } " +
                "  body, html { background: #000 !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; } " +
                "  .ad-banner, .pop-under, [id*=\"banner\"], [class*=\"ad-\"] { display: none !important; }';" +
                "  document.head.appendChild(style);" +
                "  var v = document.querySelector('video');" +
                "  if (v) { v.play().catch(function(){}); }" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    // ---------------------------------------------------------------- TV Remote Control

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (isHudVisible) {
                return super.onKeyDown(keyCode, event);
            }
            togglePlayPause();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!isHudVisible) {
                seekRelative(-10);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!isHudVisible) {
                seekRelative(10);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!isHudVisible) {
                showHud();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (isHudVisible) {
                hideHud();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isHudVisible) {
                hideHud();
                return true;
            }
            // Save progress and exit
            queryProgress();
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void togglePlayPause() {
        String js = "javascript:(function() {" +
                "  var v = document.querySelector('video');" +
                "  if (v) { if (v.paused) { v.play(); return 'PLAYING'; } else { v.pause(); return 'PAUSED'; } }" +
                "  return 'NO_VIDEO';" +
                "})()";
        webView.evaluateJavascript(js, res -> {
            if (res != null && res.contains("PAUSED")) {
                showSeekFeedback("⏸ Paused");
            } else if (res != null && res.contains("PLAYING")) {
                showSeekFeedback("▶ Playing");
            }
        });
    }

    private void seekRelative(int seconds) {
        String js = "javascript:(function() {" +
                "  var v = document.querySelector('video');" +
                "  if (v) { v.currentTime = Math.max(0, v.currentTime + (" + seconds + ")); return v.currentTime; }" +
                "  return -1;" +
                "})()";
        webView.evaluateJavascript(js, res -> {
            if (seconds > 0) {
                showSeekFeedback("⏩ +" + seconds + "s");
            } else {
                showSeekFeedback("⏪ " + seconds + "s");
            }
        });
    }

    private void showSeekFeedback(String text) {
        seekOsd.setText(text);
        seekOsd.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekOsdRunnable);
        handler.postDelayed(hideSeekOsdRunnable, 1500);
    }

    private void queryProgress() {
        String js = "(function() {" +
                "  var v = document.querySelector('video');" +
                "  if (v && v.duration > 0) {" +
                "    return JSON.stringify({pos: Math.floor(v.currentTime * 1000), dur: Math.floor(v.duration * 1000)});" +
                "  }" +
                "  return null;" +
                "})()";
        webView.evaluateJavascript(js, val -> {
            if (val != null && !val.equals("null")) {
                try {
                    // Strips potential quotes around returned JSON string
                    String clean = val;
                    if (clean.startsWith("\"") && clean.endsWith("\"")) {
                        clean = clean.substring(1, clean.length() - 1).replace("\\\"", "\"");
                    }
                    JSONObject obj = new JSONObject(clean);
                    long pos = obj.optLong("pos", 0);
                    long dur = obj.optLong("dur", 0);
                    if (pos > 0 && dur > 0) {
                        Vb.saveProgress(item, season, episode, pos, dur);
                    }
                } catch (Exception ignored) {}
            }
        });
    }
}
