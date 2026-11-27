package com.vidbox.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One film or series: poster, facts, overview, the buttons (play or resume, start over, watchlist,
 * server), and underneath either the seasons and episodes of a series or more films like this one.
 *
 * Exported, so a launcher can open a title directly: extras {@code id} (TMDB id) and {@code type}
 * ("movie" or "tv"); {@code play=true} starts playing at once.
 */
public class VbDetailActivity extends Activity {
    private Vb.Item item;
    private Vb.Detail detail;
    private boolean isShow;
    private final List<Vb.Episode> shown = new ArrayList<>();
    private final Rows rows = new Rows();
    private int season = -1, seasonGen;

    private ImageView poster;
    private TextView title, info, overview, play, restart, watch, server;
    private LinearLayout col, seasonRow, similarLine;
    private ListView list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Vb.init(this);
        try {
            String json = getIntent().getStringExtra("item_json");
            if (json != null) item = Vb.Item.fromJson(new JSONObject(json));
        } catch (Exception ignored) {
        }
        if (item == null) {
            long id = getIntent().getLongExtra("id", 0);
            if (id <= 0) {
                finish();
                return;
            }
            item = new Vb.Item();
            item.id = id;
            item.type = Vb.TYPE_TV.equals(getIntent().getStringExtra("type")) ? Vb.TYPE_TV : Vb.TYPE_MOVIE;
            item.title = "";
        }
        isShow = Vb.TYPE_TV.equals(item.type);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(VbUi.BG));

        int pad = VbUi.dp(this, 40);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(pad, pad, pad, VbUi.dp(this, 20));
        root.setClipChildren(false);

        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(VbUi.round(VbUi.PANEL, VbUi.dp(this, 10), 0, 0));
        poster.setClipToOutline(true);
        int pw = VbUi.dp(this, 200);
        root.addView(poster, VbUi.lp(pw, pw * 3 / 2, 0, 0, VbUi.dp(this, 32), 0));
        VbUi.image(poster, VbUi.sized(item.getPosterUrl(), 342), pw);

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        root.addView(col, new LinearLayout.LayoutParams(0, -1, 1f));

        title = VbUi.label(this, item.title, 30, VbUi.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        info = VbUi.label(this, facts(), 14, VbUi.ACCENT, false);
        info.setSingleLine(true);
        info.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(info, VbUi.lp(-1, -2, 0, VbUi.dp(this, 4), 0, VbUi.dp(this, 10)));

        overview = VbUi.label(this, item.overview == null ? "" : item.overview, 15, VbUi.DIM, false);
        overview.setMaxLines(isShow ? 3 : 4);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.15f);
        col.addView(overview, VbUi.lp(-1, -2, 0, 0, 0, VbUi.dp(this, 14)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        play = VbUi.pill(this, "▶  Play");
        play.setTextSize(17);
        play.setOnClickListener(v -> playPrimary(false));
        buttons.addView(play, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 10), 0));
        restart = VbUi.pill(this, "Start over");
        restart.setOnClickListener(v -> playPrimary(true));
        buttons.addView(restart, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 10), 0));
        watch = VbUi.pill(this, "");
        watch.setOnClickListener(v -> {
            Vb.toggleWatchlist(item);
            refreshButtons();
        });
        buttons.addView(watch, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 10), 0));
        server = VbUi.pill(this, "");
        server.setOnClickListener(v -> pickServer());
        buttons.addView(server);
        col.addView(buttons, VbUi.lp(-1, -2, 0, 0, 0, VbUi.dp(this, 14)));

        if (isShow) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            seasonRow = new LinearLayout(this);
            hs.addView(seasonRow);
            // The column does not clip its children (so focused cards can grow past its edge), which
            // also let this row, once scrolled, spill out to the left over the poster. A frame of its
            // own keeps the row inside the column: seasons that scroll off are cut at the column's edge.
            FrameLayout seasonClip = new FrameLayout(this);
            seasonClip.addView(hs, new FrameLayout.LayoutParams(-1, -2));
            col.addView(seasonClip, VbUi.lp(-1, -2, 0, 0, 0, VbUi.dp(this, 8)));

            list = new ListView(this);
            list.setDivider(null);
            list.setVerticalScrollBarEnabled(false);
            // The ring is drawn only while the list has focus, so two things never look focused at once.
            final android.graphics.drawable.Drawable ring = VbUi.round(0xFF26262C, VbUi.dp(this, 8), VbUi.dp(this, 3), 0xFFFFFFFF);
            final android.graphics.drawable.Drawable none = new ColorDrawable(0);
            list.setSelector(none);
            list.setOnFocusChangeListener((v, f) -> list.setSelector(f ? ring : none));
            list.setAdapter(rows);
            list.setOnItemClickListener((p, v, pos, id) -> playEpisode(shown.get(pos), false));
            // Same as the season row: without a frame of its own, an episode that is scrolled half off
            // the top is drawn above the list, over the seasons.
            FrameLayout listClip = new FrameLayout(this);
            listClip.addView(list, new FrameLayout.LayoutParams(-1, -1));
            col.addView(listClip, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            // Films: a row of similar films fills the space a series uses for its episodes.
            TextView head = VbUi.label(this, "More like this", 18, VbUi.TEXT, true);
            head.setTag("similar");
            col.addView(head, VbUi.lp(-2, -2, 0, VbUi.dp(this, 6), 0, 0));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipToPadding(false);
            hs.setClipChildren(false);
            similarLine = new LinearLayout(this);
            similarLine.setPadding(VbUi.dp(this, 8), VbUi.dp(this, 12), VbUi.dp(this, 8), VbUi.dp(this, 12));
            similarLine.setClipToPadding(false);
            similarLine.setClipChildren(false);
            hs.addView(similarLine);
            col.addView(hs, new LinearLayout.LayoutParams(-1, -2));
            VbUi.gone(head, true);
        }
        setContentView(root);
        refreshButtons();
        play.requestFocus();

        Vb.async(() -> Vb.detail(item.type, item.id), (d, err) -> {
            if (isFinishing()) return;
            if (d == null) {
                info.setText("Could not load the details: " + VbBrowseActivity.reason(err));
                return;
            }
            onDetail(d);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (item == null) return;
        refreshButtons();
        if (isShow && detail != null && !detail.seasons.isEmpty()) {
            int[] cur = primaryEpisode();
            if (cur[0] != season) showSeason(cur[0]);
            else rows.notifyDataSetChanged();
        }
    }

    private void onDetail(Vb.Detail d) {
        detail = d;
        // The list an item came from has less than the full record; keep what identifies it.
        d.item.type = item.type;
        item = d.item;
        title.setText(item.title);
        overview.setText(item.overview);
        info.setText(facts());
        VbUi.image(poster, VbUi.sized(item.getPosterUrl(), 342), VbUi.dp(this, 200));
        refreshButtons();
        if (isShow) {
            if (d.seasons.isEmpty()) info.setText(facts() + "  ·  no episodes listed yet");
            else buildSeasons();
        } else {
            buildSimilar();
        }
        if (getIntent().getBooleanExtra("play", false)) {
            getIntent().removeExtra("play");
            if (!isShow) playPrimary(false);
        }
    }

    /** "Series · 2019 · ★ 8.4 · 3 seasons · Drama, Crime" */
    private String facts() {
        List<String> f = new ArrayList<>();
        f.add(isShow ? "Series" : "Film");
        if (item.year != null && !item.year.isEmpty()) f.add(item.year);
        if (item.rating > 0) f.add(String.format(Locale.US, "★ %.1f", item.rating));
        if (detail != null) {
            if (isShow && detail.seasonsCount > 0) f.add(detail.seasonsCount + (detail.seasonsCount == 1 ? " season" : " seasons"));
            if (!isShow && detail.runtime > 0) {
                f.add(detail.runtime >= 60 ? detail.runtime / 60 + " h " + detail.runtime % 60 + " min" : detail.runtime + " min");
            }
            if (!detail.genres.isEmpty()) f.add(TextUtils.join(", ", detail.genres));
        }
        return TextUtils.join("  ·  ", f);
    }

    // ---------------------------------------------------------------- buttons

    /** Season and episode Play should start: the one last watched, or the one after it if that was finished. */
    private int[] primaryEpisode() {
        int[] last = Vb.lastEpisode(item.id);
        int first = detail == null || detail.seasons.isEmpty() ? 1 : detail.seasons.get(0).seasonNumber;
        if (last == null) return new int[]{first, 1};
        if (Vb.position(Vb.playKey(item.type, item.id, last[0], last[1])) != -1 || detail == null) return last;
        int[] next = next(last[0], last[1]);
        return next == null ? last : next;
    }

    /** The episode after this one, crossing into the next season; null after the last. */
    private int[] next(int s, int e) {
        for (int i = 0; i < detail.seasons.size(); i++) {
            Vb.Season sn = detail.seasons.get(i);
            if (sn.seasonNumber != s) continue;
            if (e < sn.episodeCount) return new int[]{s, e + 1};
            if (i + 1 < detail.seasons.size()) return new int[]{detail.seasons.get(i + 1).seasonNumber, 1};
        }
        return null;
    }

    private void refreshButtons() {
        watch.setText(Vb.isInWatchlist(item.id, item.type) ? "✓  In watchlist" : "+  Add to watchlist");
        int pin = Vb.pinnedServer();
        server.setText("Server: " + (pin < 0 ? "Automatic" : Vb.SERVERS.get(pin).name));
        if (!isShow) {
            long pos = Vb.position(Vb.playKey(item.type, item.id, 0, 0));
            play.setText(pos > 0 ? "▶  Resume from " + Vb.time(pos) : "▶  Play");
            VbUi.gone(restart, pos <= 0);
            if (pos <= 0 && restart.isFocused()) play.requestFocus();
            return;
        }
        VbUi.gone(restart, true);
        int[] e = primaryEpisode();
        long pos = Vb.position(Vb.playKey(item.type, item.id, e[0], e[1]));
        play.setText((pos > 0 ? "▶  Resume " : "▶  Play ") + "S" + e[0] + " E" + e[1]);
    }

    private void pickServer() {
        String[] names = new String[Vb.SERVERS.size() + 1];
        names[0] = "Automatic (the one working best now)";
        for (int i = 1; i < names.length; i++) names[i] = Vb.SERVERS.get(i - 1).name;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Play from")
                .setSingleChoiceItems(names, Vb.pinnedServer() + 1, (d, which) -> {
                    d.dismiss();
                    Vb.pinServer(which - 1);
                    refreshButtons();
                })
                .show();
    }

    // ---------------------------------------------------------------- series

    private void buildSeasons() {
        seasonRow.removeAllViews();
        for (final Vb.Season s : detail.seasons) {
            TextView chip = VbUi.pill(this, "Season " + s.seasonNumber);
            chip.setTag(s.seasonNumber);
            chip.setOnClickListener(v -> showSeason(s.seasonNumber));
            seasonRow.addView(chip, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
        }
        VbUi.gone(seasonRow, detail.seasons.size() < 2);
        showSeason(primaryEpisode()[0]);
    }

    private void showSeason(final int s) {
        season = s;
        final int gen = ++seasonGen;
        for (int i = 0; i < seasonRow.getChildCount(); i++) {
            TextView c = (TextView) seasonRow.getChildAt(i);
            c.setSelected(((Integer) c.getTag()) == s);
            VbUi.restyle(c);
        }
        shown.clear();
        rows.notifyDataSetChanged();
        Vb.async(() -> Vb.episodes(item.id, s), (eps, err) -> {
            if (isFinishing() || gen != seasonGen) return;
            if (eps == null) {
                info.setText("Could not load the episodes: " + VbBrowseActivity.reason(err));
                return;
            }
            info.setText(facts());
            shown.addAll(eps);
            rows.notifyDataSetChanged();
            // The season's real length: TMDB's count includes episodes not broadcast yet.
            for (Vb.Season sn : detail.seasons) if (sn.seasonNumber == s && !eps.isEmpty()) sn.episodeCount = eps.get(eps.size() - 1).episodeNumber;
            int[] cur = primaryEpisode();
            for (int i = 0; i < shown.size(); i++) {
                if (cur[0] == s && shown.get(i).episodeNumber == cur[1]) list.setSelection(i);
            }
            if (getIntent().getBooleanExtra("play", false)) {
                getIntent().removeExtra("play");
                playPrimary(false);
            }
        });
    }

    // ---------------------------------------------------------------- films

    private void buildSimilar() {
        similarLine.removeAllViews();
        // What is left under the buttons decides the card size, so the row never runs off the screen.
        col.post(() -> {
            View head = col.findViewWithTag("similar");
            int h = Math.min(VbUi.dp(this, 170), col.getHeight() - head.getTop() - VbUi.dp(this, 90));
            if (h < VbUi.dp(this, 90) || detail.similar.isEmpty()) return;
            int w = h * 2 / 3;
            for (final Vb.Item it : detail.similar) {
                View card = VbUi.card(this, h);
                VbUi.bind(card, it, w, false, null);
                card.setFocusable(true);
                card.setOnFocusChangeListener(VbUi::focus);
                card.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(this, VbDetailActivity.class).putExtra("item_json", it.toJson().toString()));
                    } catch (Exception ignored) {
                    }
                });
                similarLine.addView(card, VbUi.lp(w, -2, 0, 0, VbUi.dp(this, 14), 0));
            }
            VbUi.gone(head, false);
        });
    }

    // ---------------------------------------------------------------- playing

    private void playPrimary(boolean startOver) {
        if (!isShow) {
            start(0, 0, startOver);
        } else if (detail != null && !detail.seasons.isEmpty()) {
            int[] e = primaryEpisode();
            start(e[0], e[1], startOver);
        }
    }

    private void playEpisode(Vb.Episode e, boolean startOver) {
        start(e.seasonNumber, e.episodeNumber, startOver);
    }

    private void start(int s, int e, boolean startOver) {
        Intent i = new Intent(this, VbPlayerActivity.class);
        try {
            i.putExtra("item_json", item.toJson().toString());
        } catch (Exception ex) {
            return;
        }
        i.putExtra("imdb_id", detail == null ? null : detail.imdbId);
        i.putExtra("season", s);
        i.putExtra("episode", e);
        i.putExtra("startOver", startOver);
        if (isShow && detail != null) {
            int n = detail.seasons.size();
            int[] numbers = new int[n], counts = new int[n];
            for (int k = 0; k < n; k++) {
                numbers[k] = detail.seasons.get(k).seasonNumber;
                counts[k] = detail.seasons.get(k).episodeCount;
            }
            i.putExtra("season_numbers", numbers);
            i.putExtra("season_counts", counts);
        }
        if (getIntent().hasExtra("probe")) i.putExtra("probe", getIntent().getStringExtra("probe"));
        startActivity(i);
    }

    private final class Rows extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int pos) {
            return shown.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            VbDetailActivity c = VbDetailActivity.this;
            LinearLayout row = (LinearLayout) v;
            if (row == null) {
                row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(VbUi.dp(c, 14), VbUi.dp(c, 10), VbUi.dp(c, 14), VbUi.dp(c, 10));
                TextView num = VbUi.label(c, "", 16, VbUi.ACCENT, true);
                row.addView(num, new LinearLayout.LayoutParams(VbUi.dp(c, 110), -2));
                TextView name = VbUi.label(c, "", 16, VbUi.TEXT, false);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView state = VbUi.label(c, "", 14, VbUi.DIM, false);
                state.setPadding(VbUi.dp(c, 12), 0, 0, 0);
                row.addView(state);
            }
            Vb.Episode e = shown.get(pos);
            ((TextView) row.getChildAt(0)).setText("Episode " + e.episodeNumber);
            ((TextView) row.getChildAt(1)).setText(e.title);
            long p = Vb.position(Vb.playKey(item.type, item.id, e.seasonNumber, e.episodeNumber));
            ((TextView) row.getChildAt(2)).setText(p == -1 ? "✓ watched" : p > 0 ? "resume from " + Vb.time(p) : "");
            return row;
        }
    }
}
