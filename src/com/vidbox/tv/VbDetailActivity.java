package com.vidbox.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * TV Detail screen for Movies and TV Shows.
 *
 * Shows full metadata, overview, genres, cast, play/resume button, watchlist button,
 * server switcher, season & episode horizontal selector (for TV series), and similar titles.
 */
public class VbDetailActivity extends Activity {
    private Vb.Item item;
    private Vb.Detail detail;
    private String selectedServerName = Vb.SERVERS.get(0).name;
    private int selectedSeasonNumber = 1;
    private int selectedEpisodeNumber = 1;

    private int screenW, screenH;
    private ImageView backdropView;
    private ScrollView mainScrollView;
    private LinearLayout contentBox;
    private ProgressBar loadingSpinner;

    private TextView playButton;
    private TextView watchlistButton;
    private TextView serverButton;

    // TV Season & Episodes
    private LinearLayout tvSeasonContainer;
    private LinearLayout seasonTabsRow;
    private LinearLayout episodeCardsRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Vb.init(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(VbUi.BG));

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;

        // Parse extras
        long id = getIntent().getLongExtra("id", 0);
        String type = getIntent().getStringExtra("type");
        String json = getIntent().getStringExtra("item_json");

        if (json != null) {
            try {
                item = Vb.Item.fromJson(new JSONObject(json));
            } catch (Exception ignored) {}
        }
        if (item == null && id > 0) {
            item = new Vb.Item();
            item.id = id;
            item.type = type != null ? type : Vb.TYPE_MOVIE;
        }

        if (item == null) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        setContentView(root);

        // 1. Fullscreen Backdrop
        backdropView = new ImageView(this);
        backdropView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdropView.setColorFilter(0xDD080C14, android.graphics.PorterDuff.Mode.SRC_ATOP);
        root.addView(backdropView);

        if (item.getBackdropUrl() != null) {
            VbUi.load(backdropView, item.getBackdropUrl(), screenW, screenH, 0);
        }

        // 2. Loading Spinner
        loadingSpinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(VbUi.dp(this, 50), VbUi.dp(this, 50), Gravity.CENTER);
        loadingSpinner.setLayoutParams(spinLp);
        root.addView(loadingSpinner);

        // 3. Scrollable Detail Content
        mainScrollView = new ScrollView(this);
        mainScrollView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mainScrollView.setVerticalScrollBarEnabled(false);
        mainScrollView.setClipToPadding(false);
        mainScrollView.setPadding(VbUi.dp(this, 44), VbUi.dp(this, 36), VbUi.dp(this, 44), VbUi.dp(this, 44));

        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        mainScrollView.addView(contentBox);
        root.addView(mainScrollView);

        // Fetch complete detail
        loadDetail();
    }

    private void loadDetail() {
        loadingSpinner.setVisibility(View.VISIBLE);
        Vb.getDetail(item.type, item.id, new Vb.Callback<Vb.Detail>() {
            @Override
            public void onSuccess(Vb.Detail d) {
                loadingSpinner.setVisibility(View.GONE);
                detail = d;
                renderDetail();
            }

            @Override
            public void onError(Exception e) {
                loadingSpinner.setVisibility(View.GONE);
                Toast.makeText(VbDetailActivity.this, "Failed to load details: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void renderDetail() {
        contentBox.removeAllViews();

        // 1. Title
        TextView titleView = new TextView(this);
        titleView.setText(detail.item.title);
        titleView.setTextColor(VbUi.TEXT_WHITE);
        titleView.setTextSize(30);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        contentBox.addView(titleView);

        // Tagline (if any)
        if (detail.tagline != null && !detail.tagline.isEmpty()) {
            TextView tagView = new TextView(this);
            tagView.setText("\"" + detail.tagline + "\"");
            tagView.setTextColor(VbUi.TEXT_MUTED);
            tagView.setTextSize(14);
            tagView.setTypeface(null, android.graphics.Typeface.ITALIC);
            tagView.setPadding(0, VbUi.dp(this, 3), 0, 0);
            contentBox.addView(tagView);
        }

        // 2. Metadata badges (Year, Rating, Runtime/Seasons, HD badge, Genres)
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, VbUi.dp(this, 12), 0, VbUi.dp(this, 16));

        if (detail.item.year != null && !detail.item.year.isEmpty()) {
            addMetaBadge(metaRow, detail.item.year, VbUi.TEXT_WHITE, null);
        }

        if (detail.item.rating > 0) {
            addMetaBadge(metaRow, String.format("★ %.1f", detail.item.rating), VbUi.STAR_YELLOW, null);
        }

        if (Vb.TYPE_MOVIE.equals(detail.item.type)) {
            if (detail.runtime > 0) {
                int hours = detail.runtime / 60;
                int mins = detail.runtime % 60;
                String rtStr = hours > 0 ? (hours + "h " + mins + "m") : (mins + "m");
                addMetaBadge(metaRow, rtStr, VbUi.TEXT_MUTED, null);
            }
        } else {
            if (detail.seasonsCount > 0) {
                addMetaBadge(metaRow, detail.seasonsCount + (detail.seasonsCount == 1 ? " Season" : " Seasons"), VbUi.TEXT_MUTED, null);
            }
        }

        // HD badge
        addMetaBadge(metaRow, "HD", VbUi.ACCENT, VbUi.roundRect(0x2238BDF8, VbUi.dp(this, 4), VbUi.dp(this, 1), VbUi.ACCENT));

        // Genre badges
        for (String g : detail.genres) {
            addMetaBadge(metaRow, g, VbUi.TEXT_MUTED, VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 4), 0, 0));
        }
        contentBox.addView(metaRow);

        // 3. Action Buttons Row: [Play] [Watchlist] [Server]
        buildActionButtons();

        // 4. Overview paragraph
        if (detail.item.overview != null && !detail.item.overview.isEmpty()) {
            TextView overviewTitle = new TextView(this);
            overviewTitle.setText("Synopsis");
            overviewTitle.setTextColor(VbUi.TEXT_WHITE);
            overviewTitle.setTextSize(16);
            overviewTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            overviewTitle.setPadding(0, VbUi.dp(this, 18), 0, VbUi.dp(this, 6));
            contentBox.addView(overviewTitle);

            TextView overviewView = new TextView(this);
            overviewView.setText(detail.item.overview);
            overviewView.setTextColor(0xFFCBD5E1);
            overviewView.setTextSize(14);
            overviewView.setLineSpacing(VbUi.dp(this, 4), 1.0f);
            overviewView.setMaxWidth(VbUi.dp(this, 800));
            contentBox.addView(overviewView);
        }

        // 5. Cast Row
        if (!detail.cast.isEmpty()) {
            TextView castTitle = new TextView(this);
            castTitle.setText("Starring: " + TextUtils.join(", ", detail.cast));
            castTitle.setTextColor(VbUi.TEXT_MUTED);
            castTitle.setTextSize(13);
            castTitle.setPadding(0, VbUi.dp(this, 12), 0, 0);
            contentBox.addView(castTitle);
        }

        // 6. For TV Shows: Seasons & Episodes lists
        if (Vb.TYPE_TV.equals(detail.item.type) && !detail.seasons.isEmpty()) {
            buildTvEpisodesSection();
        }

        // 7. Similar Titles Row
        if (!detail.similar.isEmpty()) {
            buildSimilarSection();
        }

        // Request initial focus on Play Button
        if (playButton != null) {
            playButton.post(() -> playButton.requestFocus());
        }
    }

    private void addMetaBadge(LinearLayout row, String text, int textColor, GradientDrawable bg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(VbUi.dp(this, 8), VbUi.dp(this, 3), VbUi.dp(this, 8), VbUi.dp(this, 3));
        if (bg != null) tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, VbUi.dp(this, 8), 0);
        tv.setLayoutParams(lp);
        row.addView(tv);
    }

    // ---------------------------------------------------------------- Action Buttons

    private void buildActionButtons() {
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, VbUi.dp(this, 6), 0, VbUi.dp(this, 12));

        // 1. Play Button
        playButton = new TextView(this);
        updatePlayButtonText();
        playButton.setTextColor(0xFF0F172A);
        playButton.setTextSize(15);
        playButton.setTypeface(null, android.graphics.Typeface.BOLD);
        playButton.setFocusable(true);
        playButton.setFocusableInTouchMode(true);
        playButton.setPadding(VbUi.dp(this, 26), VbUi.dp(this, 12), VbUi.dp(this, 26), VbUi.dp(this, 12));
        playButton.setBackground(VbUi.roundRect(VbUi.ACCENT, VbUi.dp(this, 10), 0, 0));

        playButton.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                playButton.setBackground(VbUi.roundRect(0xFFFFFFFF, VbUi.dp(this, 10), VbUi.dp(this, 3), VbUi.ACCENT));
                VbUi.animateFocus(v, true, 1.08f);
            } else {
                playButton.setBackground(VbUi.roundRect(VbUi.ACCENT, VbUi.dp(this, 10), 0, 0));
                VbUi.animateFocus(v, false, 1.0f);
            }
        });

        playButton.setOnClickListener(v -> startPlayback(selectedSeasonNumber, selectedEpisodeNumber));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playLp.setMargins(0, 0, VbUi.dp(this, 14), 0);
        playButton.setLayoutParams(playLp);
        btnRow.addView(playButton);

        // 2. Watchlist Button
        watchlistButton = new TextView(this);
        updateWatchlistButtonText();
        watchlistButton.setTextSize(14);
        watchlistButton.setFocusable(true);
        watchlistButton.setFocusableInTouchMode(true);
        watchlistButton.setPadding(VbUi.dp(this, 18), VbUi.dp(this, 12), VbUi.dp(this, 18), VbUi.dp(this, 12));
        watchlistButton.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 10), VbUi.dp(this, 1), 0x33FFFFFF));

        watchlistButton.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                watchlistButton.setTextColor(0xFFFFFFFF);
                watchlistButton.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 10), VbUi.dp(this, 2), VbUi.ACCENT));
                VbUi.animateFocus(v, true, 1.06f);
            } else {
                updateWatchlistButtonText();
                VbUi.animateFocus(v, false, 1.0f);
            }
        });

        watchlistButton.setOnClickListener(v -> {
            Vb.toggleWatchlist(detail.item);
            updateWatchlistButtonText();
        });
        LinearLayout.LayoutParams watchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        watchLp.setMargins(0, 0, VbUi.dp(this, 14), 0);
        watchlistButton.setLayoutParams(watchLp);
        btnRow.addView(watchlistButton);

        // 3. Server Selector Button
        serverButton = new TextView(this);
        serverButton.setText("Server: " + selectedServerName + " ▾");
        serverButton.setTextColor(VbUi.TEXT_WHITE);
        serverButton.setTextSize(14);
        serverButton.setFocusable(true);
        serverButton.setFocusableInTouchMode(true);
        serverButton.setPadding(VbUi.dp(this, 18), VbUi.dp(this, 12), VbUi.dp(this, 18), VbUi.dp(this, 12));
        serverButton.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 10), VbUi.dp(this, 1), 0x33FFFFFF));

        serverButton.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                serverButton.setTextColor(0xFFFFFFFF);
                serverButton.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 10), VbUi.dp(this, 2), VbUi.ACCENT));
                VbUi.animateFocus(v, true, 1.06f);
            } else {
                serverButton.setTextColor(VbUi.TEXT_WHITE);
                serverButton.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 10), VbUi.dp(this, 1), 0x33FFFFFF));
                VbUi.animateFocus(v, false, 1.0f);
            }
        });

        serverButton.setOnClickListener(v -> showServerPicker());
        btnRow.addView(serverButton);

        contentBox.addView(btnRow);
    }

    private void updatePlayButtonText() {
        if (playButton == null || detail == null) return;
        if (detail.item.resumePosition > 0 && detail.item.duration > 0) {
            long mins = detail.item.resumePosition / 60_000L;
            playButton.setText(String.format("▶ Resume (%d min)", mins));
        } else if (Vb.TYPE_TV.equals(detail.item.type)) {
            playButton.setText(String.format("▶ Play S%d:E%d", selectedSeasonNumber, selectedEpisodeNumber));
        } else {
            playButton.setText("▶ Play Movie");
        }
    }

    private void updateWatchlistButtonText() {
        if (watchlistButton == null || detail == null) return;
        boolean inList = Vb.isInWatchlist(detail.item.id, detail.item.type);
        if (inList) {
            watchlistButton.setText("✓ In Watchlist");
            watchlistButton.setTextColor(VbUi.ACCENT);
        } else {
            watchlistButton.setText("+ Add to Watchlist");
            watchlistButton.setTextColor(VbUi.TEXT_WHITE);
        }
    }

    private void showServerPicker() {
        String[] serverNames = new String[Vb.SERVERS.size()];
        int currentIdx = 0;
        for (int i = 0; i < Vb.SERVERS.size(); i++) {
            serverNames[i] = Vb.SERVERS.get(i).name;
            if (serverNames[i].equals(selectedServerName)) currentIdx = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Streaming Server")
                .setSingleChoiceItems(serverNames, currentIdx, (dialog, which) -> {
                    selectedServerName = serverNames[which];
                    serverButton.setText("Server: " + selectedServerName + " ▾");
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- TV Seasons & Episodes

    private void buildTvEpisodesSection() {
        tvSeasonContainer = new LinearLayout(this);
        tvSeasonContainer.setOrientation(LinearLayout.VERTICAL);
        tvSeasonContainer.setPadding(0, VbUi.dp(this, 24), 0, 0);

        TextView secTitle = new TextView(this);
        secTitle.setText("Episodes");
        secTitle.setTextColor(VbUi.TEXT_WHITE);
        secTitle.setTextSize(18);
        secTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        secTitle.setPadding(0, 0, 0, VbUi.dp(this, 12));
        tvSeasonContainer.addView(secTitle);

        // Season tabs horizontal scroll
        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonTabsRow = new LinearLayout(this);
        seasonTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        seasonScroll.addView(seasonTabsRow);
        tvSeasonContainer.addView(seasonScroll);

        // Render Season pills
        for (Vb.Season s : detail.seasons) {
            TextView sTab = new TextView(this);
            sTab.setText(s.name);
            sTab.setTextSize(14);
            sTab.setFocusable(true);
            sTab.setFocusableInTouchMode(true);
            sTab.setPadding(VbUi.dp(this, 16), VbUi.dp(this, 8), VbUi.dp(this, 16), VbUi.dp(this, 8));
            updateSeasonTabStyle(sTab, s.seasonNumber == selectedSeasonNumber);

            sTab.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    sTab.setTextColor(0xFFFFFFFF);
                    sTab.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 16), VbUi.dp(this, 2), VbUi.ACCENT));
                    VbUi.animateFocus(v, true, 1.06f);
                } else {
                    updateSeasonTabStyle(sTab, s.seasonNumber == selectedSeasonNumber);
                    VbUi.animateFocus(v, false, 1.0f);
                }
            });

            sTab.setOnClickListener(v -> {
                selectedSeasonNumber = s.seasonNumber;
                for (int i = 0; i < seasonTabsRow.getChildCount(); i++) {
                    View child = seasonTabsRow.getChildAt(i);
                    if (child instanceof TextView) {
                        updateSeasonTabStyle((TextView) child, detail.seasons.get(i).seasonNumber == selectedSeasonNumber);
                    }
                }
                loadEpisodesForSeason(selectedSeasonNumber);
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, VbUi.dp(this, 10), 0);
            sTab.setLayoutParams(lp);
            seasonTabsRow.addView(sTab);
        }

        // Horizontal scroll for episode cards
        HorizontalScrollView epScroll = new HorizontalScrollView(this);
        epScroll.setHorizontalScrollBarEnabled(false);
        epScroll.setPadding(0, VbUi.dp(this, 14), 0, 0);

        episodeCardsRow = new LinearLayout(this);
        episodeCardsRow.setOrientation(LinearLayout.HORIZONTAL);
        epScroll.addView(episodeCardsRow);
        tvSeasonContainer.addView(epScroll);

        contentBox.addView(tvSeasonContainer);

        // Load season 1 episodes
        loadEpisodesForSeason(selectedSeasonNumber);
    }

    private void updateSeasonTabStyle(TextView tv, boolean isSelected) {
        if (isSelected) {
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackground(VbUi.roundRect(VbUi.ACCENT_GRAD, VbUi.dp(this, 16), 0, 0));
        } else {
            tv.setTextColor(VbUi.TEXT_MUTED);
            tv.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 16), 0, 0));
        }
    }

    private void loadEpisodesForSeason(int sNum) {
        episodeCardsRow.removeAllViews();
        TextView loadingView = new TextView(this);
        loadingView.setText("Loading episodes...");
        loadingView.setTextColor(VbUi.TEXT_MUTED);
        episodeCardsRow.addView(loadingView);

        Vb.getSeasonEpisodes(detail.item.id, sNum, new Vb.Callback<List<Vb.Episode>>() {
            @Override
            public void onSuccess(List<Vb.Episode> episodes) {
                episodeCardsRow.removeAllViews();
                for (Vb.Episode ep : episodes) {
                    episodeCardsRow.addView(createEpisodeCard(ep));
                }
            }

            @Override
            public void onError(Exception e) {
                episodeCardsRow.removeAllViews();
                TextView errView = new TextView(VbDetailActivity.this);
                errView.setText("Failed to load episodes: " + e.getMessage());
                errView.setTextColor(VbUi.ACCENT_RED);
                episodeCardsRow.addView(errView);
            }
        });
    }

    private View createEpisodeCard(Vb.Episode ep) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);

        int cardW = VbUi.dp(this, 220);
        int thumbH = VbUi.dp(this, 124);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, VbUi.dp(this, 14), 0);
        card.setLayoutParams(lp);
        card.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 8), 0, 0));

        // Thumbnail image
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setLayoutParams(new LinearLayout.LayoutParams(cardW, thumbH));
        card.addView(iv);
        VbUi.load(iv, ep.getStillUrl(), cardW, thumbH, VbUi.dp(this, 8));

        // Episode info
        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setPadding(VbUi.dp(this, 8), VbUi.dp(this, 8), VbUi.dp(this, 8), VbUi.dp(this, 8));

        TextView numView = new TextView(this);
        numView.setText("Episode " + ep.episodeNumber);
        numView.setTextColor(VbUi.ACCENT);
        numView.setTextSize(12);
        numView.setTypeface(null, android.graphics.Typeface.BOLD);
        textContainer.addView(numView);

        TextView titleView = new TextView(this);
        titleView.setText(ep.title);
        titleView.setTextColor(VbUi.TEXT_WHITE);
        titleView.setTextSize(13);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        textContainer.addView(titleView);

        card.addView(textContainer);

        card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                card.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 8), VbUi.dp(this, 2), VbUi.ACCENT));
                VbUi.animateFocus(card, true, 1.06f);
            } else {
                card.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 8), 0, 0));
                VbUi.animateFocus(card, false, 1.0f);
            }
        });

        card.setOnClickListener(v -> {
            selectedSeasonNumber = ep.seasonNumber;
            selectedEpisodeNumber = ep.episodeNumber;
            updatePlayButtonText();
            startPlayback(ep.seasonNumber, ep.episodeNumber);
        });

        return card;
    }

    // ---------------------------------------------------------------- Similar Titles Section

    private void buildSimilarSection() {
        LinearLayout simContainer = new LinearLayout(this);
        simContainer.setOrientation(LinearLayout.VERTICAL);
        simContainer.setPadding(0, VbUi.dp(this, 28), 0, 0);

        TextView secTitle = new TextView(this);
        secTitle.setText("More Like This");
        secTitle.setTextColor(VbUi.TEXT_WHITE);
        secTitle.setTextSize(18);
        secTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        secTitle.setPadding(0, 0, 0, VbUi.dp(this, 12));
        simContainer.addView(secTitle);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int cardW = VbUi.dp(this, 130);
        int cardH = (int) (cardW * 1.48f);

        for (Vb.Item it : detail.similar) {
            FrameLayout c = new FrameLayout(this);
            c.setFocusable(true);
            c.setFocusableInTouchMode(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            lp.setMargins(0, 0, VbUi.dp(this, 10), 0);
            c.setLayoutParams(lp);
            c.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 8), 0, 0));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            c.addView(iv);
            VbUi.load(iv, it.getPosterUrl(), cardW, cardH, VbUi.dp(this, 8));

            c.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    c.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 8), VbUi.dp(this, 2), VbUi.ACCENT));
                    VbUi.animateFocus(c, true, 1.08f);
                } else {
                    c.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 8), 0, 0));
                    VbUi.animateFocus(c, false, 1.0f);
                }
            });

            c.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(this, VbDetailActivity.class);
                    intent.putExtra("item_json", it.toJson().toString());
                    intent.putExtra("id", it.id);
                    intent.putExtra("type", it.type);
                    startActivity(intent);
                } catch (Exception ignored) {}
            });
            row.addView(c);
        }
        scroll.addView(row);
        simContainer.addView(scroll);
        contentBox.addView(simContainer);
    }

    // ---------------------------------------------------------------- Launch Player

    private void startPlayback(int season, int episode) {
        try {
            Intent intent = new Intent(this, VbPlayerActivity.class);
            intent.putExtra("item_json", detail.item.toJson().toString());
            intent.putExtra("id", detail.item.id);
            intent.putExtra("type", detail.item.type);
            intent.putExtra("title", detail.item.title);
            intent.putExtra("imdb_id", detail.imdbId);
            intent.putExtra("season", season);
            intent.putExtra("episode", episode);
            intent.putExtra("server_name", selectedServerName);
            intent.putExtra("resume_pos", detail.item.resumePosition);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Error starting playback: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
