package com.vidbox.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's main screen. "Home" is a page of rows (a title and a line of cards); the other tabs
 * are a poster grid that loads more as you go down.
 *
 * Remote: Up from the first row or the top of a grid reaches the tabs. Back goes to the tabs
 * first, then to Home, and only from the Home tab leaves the app.
 */
public class VbBrowseActivity extends Activity {
    private static final int COLS = 6;
    private static final String HOME = "Home", MOVIES = "Movies", SHOWS = "TV Shows", GENRES = "Genres",
            WATCHLIST = "Watchlist", SEARCH = "Search";

    private final List<Vb.Item> items = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private final Grid adapter = new Grid();
    private GridView grid;
    private TextView status;
    private EditText searchBox;
    private TextView version;
    private ImageView backdrop;
    private String backdropUrl;
    private int backdropGen;
    private HorizontalScrollView genreScroll;
    private LinearLayout genreRow, tabRow;

    private String mode = HOME;
    private String query = "";
    private String genreType = Vb.TYPE_MOVIE;
    private int genre = 0;
    private int page = 0, totalPages = 1, generation = 0, selected = -1;
    private boolean loading = false;
    private int posterW, posterH, screenH;

    // Home tab
    private ScrollView homeScroll;
    private LinearLayout homeRows;
    private Row continueRow;
    private boolean homeBuilt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Vb.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(VbUi.BG));

        int pad = VbUi.dp(this, 32);
        int gap = VbUi.dp(this, 14);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        posterW = (screenW - 2 * pad - (COLS - 1) * gap) / COLS;
        posterH = posterW * 3 / 2;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, VbUi.dp(this, 20), pad, 0);

        // --- top bar
        tabRow = new LinearLayout(this);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.addView(VbUi.label(this, "Watch", 20, VbUi.ACCENT, true), VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 24), 0));
        for (String name : new String[]{HOME, MOVIES, SHOWS, GENRES, WATCHLIST, SEARCH}) addTab(name);
        // Right end of the bar: the installed version, and an update's progress while one is coming in.
        tabRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        version = VbUi.label(this, "", 13, VbUi.DIM, false);
        tabRow.addView(version);
        root.addView(tabRow, VbUi.lp(-1, -2, 0, 0, 0, VbUi.dp(this, 12)));

        // --- search box (Search tab only)
        searchBox = new EditText(this);
        searchBox.setHint("Search films and series…");
        searchBox.setHintTextColor(VbUi.DIM);
        searchBox.setTextColor(VbUi.TEXT);
        searchBox.setTextSize(18);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchBox.setPadding(VbUi.dp(this, 16), VbUi.dp(this, 10), VbUi.dp(this, 16), VbUi.dp(this, 10));
        searchBox.setOnFocusChangeListener((v, f) -> styleSearchBox());
        styleSearchBox();
        searchBox.setOnEditorActionListener((v, action, ev) -> {
            if (ev != null && ev.getAction() != KeyEvent.ACTION_DOWN) return true;
            query = searchBox.getText().toString().trim();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
            reload();
            return true;
        });
        root.addView(searchBox, VbUi.lp(-1, -2, 0, 0, 0, VbUi.dp(this, 12)));

        // --- kind and genre chips (Genres tab only)
        genreScroll = new HorizontalScrollView(this);
        genreScroll.setHorizontalScrollBarEnabled(false);
        genreRow = new LinearLayout(this);
        genreScroll.addView(genreRow);
        root.addView(genreScroll, VbUi.lp(-1, -2, 0, 0, 0, VbUi.dp(this, 12)));

        // --- grid, status line and the Home rows share the rest of the screen
        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(COLS);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(VbUi.dp(this, 18));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        // Room around the cards so the focused one, drawn slightly larger, is not cut off.
        int room = VbUi.dp(this, 10);
        grid.setPadding(room, room, room, VbUi.dp(this, 40));
        posterW = (screenW - 2 * pad - 2 * room - (COLS - 1) * gap) / COLS;
        posterH = posterW * 3 / 2;
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> open(items.get(pos)));
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selected = pos;
                mark();
                if (grid.hasFocus() && pos >= 0 && pos < items.size()) showBackdrop(items.get(pos));
                if (pos >= items.size() - 2 * COLS) loadMore();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
                selected = -1;
                mark();
            }
        });
        grid.setOnFocusChangeListener((v, f) -> mark());
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int s) {}

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - COLS) loadMore();
            }
        });
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        status = VbUi.label(this, "", 18, VbUi.DIM, false);
        status.setGravity(Gravity.CENTER);
        body.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        homeScroll = new ScrollView(this);
        homeScroll.setVerticalScrollBarEnabled(false);
        homeRows = new LinearLayout(this);
        homeRows.setOrientation(LinearLayout.VERTICAL);
        homeRows.setClipChildren(false);
        homeRows.setPadding(0, 0, 0, screenH / 3);
        homeScroll.addView(homeRows, new FrameLayout.LayoutParams(-1, -2));
        body.addView(homeScroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Behind the page: the focused title's picture, as a soft wash of its colours. It is decoded
        // tiny and stretched over the screen, which blurs it for free, and darkened in the same
        // draw by a colour filter: one image, no blur effect and no extra translucent layer.
        FrameLayout page = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setColorFilter(0xB30B0B0D);
        backdrop.setAlpha(0f);
        page.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        page.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(page);

        select(HOME);
        tabs.get(0).requestFocus();
        VbUpdate.onStatus(this, getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        VbUpdate.check(this, version);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        VbUpdate.onStatus(this, i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only what can have changed while a title was open; the page and the focus stay as they were.
        if (HOME.equals(mode) && homeBuilt) fill(continueRow, Vb.getContinueWatching());
        if (WATCHLIST.equals(mode)) refreshWatchlist();
    }

    private void styleSearchBox() {
        boolean f = searchBox.isFocused();
        searchBox.setBackground(VbUi.round(VbUi.PANEL, VbUi.dp(this, 10), VbUi.dp(this, f ? 3 : 1),
                f ? Color.WHITE : 0xFF3A3A42));
    }

    // ---------------------------------------------------------------- tabs

    private void addTab(final String name) {
        TextView t = VbUi.pill(this, name);
        t.setTag(name);
        t.setOnClickListener(v -> select(name));
        tabRow.addView(t, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
        tabs.add(t);
    }

    private TextView tab(String name) {
        for (TextView t : tabs) if (name.equals(t.getTag())) return t;
        return tabs.get(0);
    }

    private void select(String name) {
        mode = name;
        for (TextView t : tabs) {
            t.setSelected(name.equals(t.getTag()));
            VbUi.restyle(t);
        }
        boolean isHome = HOME.equals(mode);
        VbUi.gone(homeScroll, !isHome);
        VbUi.gone(grid, isHome);
        VbUi.gone(searchBox, !SEARCH.equals(mode));
        VbUi.gone(genreScroll, !GENRES.equals(mode));
        if (GENRES.equals(mode) && genreRow.getChildCount() == 0) loadGenres();
        if (isHome) {
            generation++;
            items.clear();
            adapter.notifyDataSetChanged();
            status.setText("");
            buildHome();
            return;
        }
        reload();
    }

    @Override
    public void onBackPressed() {
        View tab = tab(mode);
        if (!tabRow.hasFocus()) {
            // From the page: back to the top and to this page's tab.
            if (HOME.equals(mode)) homeScroll.scrollTo(0, 0);
            else grid.setSelection(0);
            tab.requestFocus();
        } else if (!HOME.equals(mode)) {
            select(HOME);
            tab(HOME).requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- Home

    private static final class Row {
        LinearLayout box, line;
        HorizontalScrollView scroll;
        boolean wide;
    }

    private Row row(String title, boolean wide) {
        Row r = new Row();
        r.wide = wide;
        r.box = new LinearLayout(this);
        r.box.setOrientation(LinearLayout.VERTICAL);
        r.box.setClipChildren(false);
        TextView head = VbUi.label(this, title, 18, VbUi.TEXT, true);
        head.setPadding(0, VbUi.dp(this, 10), 0, VbUi.dp(this, 4));
        r.box.addView(head);
        r.scroll = new HorizontalScrollView(this);
        r.scroll.setHorizontalScrollBarEnabled(false);
        r.scroll.setClipToPadding(false);
        r.scroll.setClipChildren(false);
        r.line = new LinearLayout(this);
        // Room around the cards so the focused one, drawn slightly larger, is not cut off.
        r.line.setPadding(VbUi.dp(this, 8), VbUi.dp(this, 12), VbUi.dp(this, 8), VbUi.dp(this, 18));
        r.line.setClipToPadding(false);
        r.line.setClipChildren(false);
        r.scroll.addView(r.line);
        r.box.addView(r.scroll, new LinearLayout.LayoutParams(-1, -2));
        r.box.setVisibility(View.GONE);
        homeRows.addView(r.box, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void fill(final Row r, List<Vb.Item> list) {
        boolean hadFocus = r.line.hasFocus();
        int focused = hadFocus ? r.line.indexOfChild(r.line.getFocusedChild()) : -1;
        r.line.removeAllViews();
        int h = r.wide ? screenH * 20 / 100 : screenH * 28 / 100;
        int w = r.wide ? h * 16 / 9 : h * 2 / 3;
        int n = Math.min(list.size(), 20);
        for (int i = 0; i < n; i++) {
            final Vb.Item it = list.get(i);
            final View card = VbUi.card(this, h);
            String caption = null;
            if (r == continueRow && Vb.TYPE_TV.equals(it.type)) caption = "S" + it.season + " E" + it.episode + " · " + it.title;
            VbUi.bind(card, it, w, r.wide, caption);
            card.setFocusable(true);
            card.setOnClickListener(v -> open(it));
            card.setOnFocusChangeListener((v, has) -> {
                VbUi.focus(v, has);
                if (!has) return;
                showBackdrop(it);
                homeScroll.smoothScrollTo(0, Math.max(0, r.box.getTop()));
                // Keep the focused card clear of the screen's edges, where its ring would be cut.
                r.scroll.smoothScrollTo(v.getLeft() - (r.scroll.getWidth() - v.getWidth()) / 2, 0);
            });
            // Left and Right stay inside the row: at its ends they do nothing rather than jump to another row.
            final boolean first = i == 0, last = i == n - 1;
            card.setOnKeyListener((v, code, ev) -> (first && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    || (last && code == KeyEvent.KEYCODE_DPAD_RIGHT));
            r.line.addView(card, VbUi.lp(w, -2, 0, 0, VbUi.dp(this, 14), 0));
        }
        VbUi.gone(r.box, n == 0);
        if (hadFocus) {
            if (n > 0) r.line.getChildAt(Math.max(0, Math.min(focused, n - 1))).requestFocus();
            else tab(HOME).requestFocus();
        }
    }

    /** Put {@code it}'s picture behind the page, after a short pause so moving along a row stays light. */
    private void showBackdrop(Vb.Item it) {
        final String url = it == null ? null : VbUi.sized(it.getBackdropUrl(), 300);
        if (url == null ? backdropUrl == null : url.equals(backdropUrl)) return;
        backdropUrl = url;
        final int g = ++backdropGen;
        Vb.MAIN.postDelayed(() -> {
            if (g != backdropGen || isFinishing()) return;
            backdrop.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                if (g != backdropGen || url == null) return;
                VbUi.image(backdrop, url, 48);
                backdrop.animate().alpha(1f).setDuration(350).start();
            }).start();
        }, 250);
    }

    private void load(final Row r, final Vb.Job<Vb.Page> job) {
        Vb.async(job, (p, err) -> {
            if (isFinishing()) return;
            if (p != null) fill(r, p.items);
            homeLoaded(err);
        });
    }

    private int homePending, homeFailed;

    /** One Home row has answered. If none of them could be loaded, say so instead of an empty page. */
    private void homeLoaded(Exception err) {
        homePending--;
        if (err != null) homeFailed++;
        if (homePending > 0 || !HOME.equals(mode)) return;
        boolean nothing = homeFailed > 0 && visibleRows() == 0;
        status.setText(nothing ? "Could not reach the catalogue.\nCheck the connection, then press OK on Home." : "");
        if (nothing) homeBuilt = false;
    }

    private int visibleRows() {
        int n = 0;
        for (int i = 0; i < homeRows.getChildCount(); i++) if (homeRows.getChildAt(i).getVisibility() == View.VISIBLE) n++;
        return n;
    }

    private void buildHome() {
        if (homeBuilt) {
            fill(continueRow, Vb.getContinueWatching());
            return;
        }
        homeBuilt = true;
        homeRows.removeAllViews();
        status.setText("Loading…");
        continueRow = row("Continue watching", true);
        fill(continueRow, Vb.getContinueWatching());
        final String m = Vb.TYPE_MOVIE, t = Vb.TYPE_TV;
        homePending = 9;
        homeFailed = 0;
        load(row("Trending films", false), () -> Vb.list("/trending/movie/day", m, 1));
        load(row("Trending series", false), () -> Vb.list("/trending/tv/day", t, 1));
        load(row("In cinemas now", false), () -> Vb.list("/movie/now_playing", m, 1));
        load(row("Netflix", false), () -> Vb.list("/discover/tv?with_networks=213&sort_by=popularity.desc", t, 1));
        load(row("Prime Video", false), () -> Vb.list("/discover/tv?with_networks=1024&sort_by=popularity.desc", t, 1));
        load(row("Apple TV+", false), () -> Vb.list("/discover/tv?with_networks=2552&sort_by=popularity.desc", t, 1));
        load(row("Disney+", false), () -> Vb.list("/discover/tv?with_networks=2739&sort_by=popularity.desc", t, 1));
        load(row("Top rated films", false), () -> Vb.list("/movie/top_rated", m, 1));
        load(row("Top rated series", false), () -> Vb.list("/tv/top_rated", t, 1));
    }

    // ---------------------------------------------------------------- grid tabs

    private void loadGenres() {
        final String type = genreType;
        Vb.async(() -> Vb.genres(type), (list, err) -> {
            if (isFinishing() || !type.equals(genreType)) return;
            boolean hadFocus = genreRow.hasFocus();
            genreRow.removeAllViews();
            // First the kind, then that kind's genres.
            for (final String kind : new String[]{Vb.TYPE_MOVIE, Vb.TYPE_TV}) {
                TextView chip = VbUi.pill(this, Vb.TYPE_MOVIE.equals(kind) ? "Films" : "Series");
                chip.setSelected(kind.equals(genreType));
                VbUi.restyle(chip);
                chip.setOnClickListener(v -> {
                    if (kind.equals(genreType)) return;
                    genreType = kind;
                    genre = 0;
                    loadGenres();
                });
                genreRow.addView(chip, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
            }
            View gapView = new View(this);
            gapView.setBackgroundColor(0xFF34343C);
            genreRow.addView(gapView, VbUi.lp(VbUi.dp(this, 1), VbUi.dp(this, 22), VbUi.dp(this, 6), 0, VbUi.dp(this, 14), 0));
            if (list == null) {
                status.setText("Could not load the genres.\n" + reason(err));
                return;
            }
            for (final Vb.Genre g : list) {
                final TextView chip = VbUi.pill(this, g.name);
                chip.setTag(g.id);
                chip.setOnClickListener(v -> pickGenre(g.id));
                genreRow.addView(chip, VbUi.lp(-2, -2, 0, 0, VbUi.dp(this, 8), 0));
            }
            genreScroll.scrollTo(0, 0);
            if (hadFocus) genreRow.getChildAt(Vb.TYPE_MOVIE.equals(genreType) ? 0 : 1).requestFocus();
            if (!list.isEmpty()) pickGenre(list.get(0).id);
        });
    }

    private void pickGenre(int id) {
        genre = id;
        for (int i = 0; i < genreRow.getChildCount(); i++) {
            View c = genreRow.getChildAt(i);
            if (!(c.getTag() instanceof Integer)) continue;
            c.setSelected(((Integer) c.getTag()) == id);
            VbUi.restyle((TextView) c);
        }
        if (GENRES.equals(mode)) reload();
    }

    private void refreshWatchlist() {
        List<Vb.Item> now = Vb.getWatchlist();
        if (now.size() == items.size()) return;
        reload();
        if (grid.hasFocus() && items.isEmpty()) tab(WATCHLIST).requestFocus();
    }

    private void reload() {
        generation++;
        items.clear();
        adapter.notifyDataSetChanged();
        page = 0;
        totalPages = 1;
        loading = false;
        selected = -1;
        if (WATCHLIST.equals(mode)) {
            items.addAll(Vb.getWatchlist());
            adapter.notifyDataSetChanged();
            status.setText(items.isEmpty() ? "Nothing here yet.\nOpen a title and choose “Add to watchlist”." : "");
            return;
        }
        if (SEARCH.equals(mode) && query.length() == 0) {
            status.setText("Press OK on the box above, type a name, then press search.");
            return;
        }
        if (GENRES.equals(mode) && genre == 0) {
            status.setText("Loading…");
            return;
        }
        loadMore();
    }

    private void loadMore() {
        if (loading || page >= totalPages || WATCHLIST.equals(mode) || HOME.equals(mode)) return;
        if (SEARCH.equals(mode) && query.length() == 0) return;
        loading = true;
        final int gen = generation;
        final int next = page + 1;
        final String m = mode, q = query, gt = genreType;
        final int g = genre;
        if (items.isEmpty()) status.setText("Loading…");
        Vb.async(() -> SEARCH.equals(m) ? Vb.search(q, next)
                : GENRES.equals(m) ? Vb.popular(gt, next, g)
                : Vb.popular(MOVIES.equals(m) ? Vb.TYPE_MOVIE : Vb.TYPE_TV, next, 0), (p, err) -> {
            if (gen != generation || isFinishing()) return;
            loading = false;
            if (p == null) {
                if (items.isEmpty()) status.setText("Could not load this page.\n" + reason(err));
                else Toast.makeText(this, "Could not load more: " + reason(err), Toast.LENGTH_SHORT).show();
                return;
            }
            page = next;
            totalPages = Math.max(p.totalPages, 1);
            items.addAll(p.items);
            adapter.notifyDataSetChanged();
            status.setText(items.isEmpty() ? "Nothing found." : "");
        });
    }

    static String reason(Exception e) {
        if (e == null) return "";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) return "No connection.";
        if (e instanceof java.net.SocketTimeoutException) return "The server took too long to answer.";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void open(Vb.Item it) {
        Intent i = new Intent(this, VbDetailActivity.class);
        try {
            i.putExtra("item_json", it.toJson().toString());
        } catch (Exception e) {
            return;
        }
        startActivity(i);
    }

    /** Paint the focus ring on the selected card only (GridView keeps focus itself). */
    private void mark() {
        boolean focused = grid.hasFocus();
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            VbUi.focus(grid.getChildAt(i), focused && first + i == selected);
        }
    }

    private final class Grid extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int pos) {
            return items.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = VbUi.card(VbBrowseActivity.this, posterH);
            VbUi.bind(v, items.get(pos), posterW, false, null);
            v.setScaleX(1f);
            v.setScaleY(1f);
            VbUi.focus(v, grid.hasFocus() && pos == selected);
            return v;
        }
    }
}
