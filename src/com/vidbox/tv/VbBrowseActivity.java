package com.vidbox.tv;

import android.app.Activity;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Android TV Leanback browse screen.
 *
 * Tab bar: HOME, MOVIES, TV SHOWS, GENRES, WATCHLIST, SEARCH.
 * HOME renders horizontal rows of cards with continuous scrolling and resume positions.
 * MOVIES, SHOWS, WATCHLIST, and SEARCH render a fast 6-column poster grid with pagination.
 */
public class VbBrowseActivity extends Activity {
    private static final int COLS = 6;
    private static final String TAB_HOME = "Home";
    private static final String TAB_MOVIES = "Movies";
    private static final String TAB_SHOWS = "TV Shows";
    private static final String TAB_GENRES = "Genres";
    private static final String TAB_WATCHLIST = "Watchlist";
    private static final String TAB_SEARCH = "Search";

    private final String[] TABS = {TAB_HOME, TAB_MOVIES, TAB_SHOWS, TAB_GENRES, TAB_WATCHLIST, TAB_SEARCH};
    private final List<TextView> tabViews = new ArrayList<>();
    private final List<Vb.Item> gridItems = new ArrayList<>();
    private final GridAdapter gridAdapter = new GridAdapter();

    private String currentTab = TAB_HOME;
    private int screenW, screenH, posterW, posterH, rowCardW, rowCardH;
    private int page = 1;
    private int currentGenreId = 0;
    private boolean loading = false;
    private int selectedIndex = -1;

    private FrameLayout rootLayout;
    private ImageView backdropView;
    private LinearLayout contentContainer;
    private LinearLayout tabRow;
    private ProgressBar loadingSpinner;

    // Home views
    private ScrollView homeScrollView;
    private LinearLayout homeRowsContainer;
    private boolean homeBuilt = false;

    // Grid views
    private FrameLayout gridContainer;
    private GridView gridView;
    private HorizontalScrollView genreScroll;
    private LinearLayout genreRow;

    // Search view
    private LinearLayout searchBoxRow;
    private EditText searchEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Vb.init(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(VbUi.BG));

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;

        int pad = VbUi.dp(this, 28);
        int gap = VbUi.dp(this, 14);

        // Responsive card sizes
        posterW = (screenW - 2 * pad - (COLS - 1) * gap) / COLS;
        posterH = (int) (posterW * 1.48f);
        rowCardW = (int) (posterW * 0.95f);
        rowCardH = (int) (rowCardW * 1.48f);

        rootLayout = new FrameLayout(this);
        setContentView(rootLayout);

        // 1. Dynamic blurred/dimmed backdrop in background
        backdropView = new ImageView(this);
        backdropView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdropView.setColorFilter(0xCC080C14, android.graphics.PorterDuff.Mode.SRC_ATOP);
        rootLayout.addView(backdropView);

        // 2. Main vertical container
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(pad, VbUi.dp(this, 18), pad, 0);
        rootLayout.addView(contentContainer);

        // 3. Top bar: Logo + Tab row + Search box
        buildTopBar();

        // 4. Loading Spinner
        loadingSpinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(VbUi.dp(this, 48), VbUi.dp(this, 48), Gravity.CENTER);
        loadingSpinner.setLayoutParams(spinLp);
        loadingSpinner.setVisibility(View.GONE);
        rootLayout.addView(loadingSpinner);

        // 5. Home ScrollView Container
        homeScrollView = new ScrollView(this);
        homeScrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        homeScrollView.setVerticalScrollBarEnabled(false);
        homeRowsContainer = new LinearLayout(this);
        homeRowsContainer.setOrientation(LinearLayout.VERTICAL);
        homeRowsContainer.setPadding(0, VbUi.dp(this, 8), 0, VbUi.dp(this, 30));
        homeScrollView.addView(homeRowsContainer);
        contentContainer.addView(homeScrollView);

        // 6. Grid Container (Movies, TV Shows, Genres, Watchlist, Search)
        gridContainer = new FrameLayout(this);
        gridContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gridContainer.setVisibility(View.GONE);

        LinearLayout gridContent = new LinearLayout(this);
        gridContent.setOrientation(LinearLayout.VERTICAL);

        // Genre filter row
        genreScroll = new HorizontalScrollView(this);
        genreScroll.setHorizontalScrollBarEnabled(false);
        genreScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        genreScroll.setPadding(0, 0, 0, VbUi.dp(this, 10));
        genreRow = new LinearLayout(this);
        genreRow.setOrientation(LinearLayout.HORIZONTAL);
        genreScroll.addView(genreRow);
        gridContent.addView(genreScroll);

        // Search box row
        searchBoxRow = new LinearLayout(this);
        searchBoxRow.setOrientation(LinearLayout.HORIZONTAL);
        searchBoxRow.setGravity(Gravity.CENTER_VERTICAL);
        searchBoxRow.setPadding(0, 0, 0, VbUi.dp(this, 12));
        searchBoxRow.setVisibility(View.GONE);

        searchEditText = new EditText(this);
        searchEditText.setHint("Search movies, TV shows, actors...");
        searchEditText.setHintTextColor(VbUi.TEXT_MUTED);
        searchEditText.setTextColor(VbUi.TEXT_WHITE);
        searchEditText.setTextSize(16);
        searchEditText.setSingleLine(true);
        searchEditText.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchEditText.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 8), VbUi.dp(this, 1), 0x3338BDF8));
        searchEditText.setPadding(VbUi.dp(this, 16), VbUi.dp(this, 10), VbUi.dp(this, 16), VbUi.dp(this, 10));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(VbUi.dp(this, 400), ViewGroup.LayoutParams.WRAP_CONTENT);
        searchEditText.setLayoutParams(searchLp);

        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = searchEditText.getText().toString().trim();
                if (!q.isEmpty()) {
                    performSearch(q);
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
        searchBoxRow.addView(searchEditText);
        gridContent.addView(searchBoxRow);

        // GridView
        gridView = new GridView(this);
        gridView.setNumColumns(COLS);
        gridView.setHorizontalSpacing(gap);
        gridView.setVerticalSpacing(gap);
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setClipToPadding(false);
        gridView.setPadding(0, 0, 0, VbUi.dp(this, 30));
        gridView.setAdapter(gridAdapter);
        gridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (!loading && totalItemCount > 0 && (firstVisibleItem + visibleItemCount >= totalItemCount - 6)) {
                    loadNextPage();
                }
            }
        });

        gridContent.addView(gridView);
        gridContainer.addView(gridContent);
        contentContainer.addView(gridContainer);

        // Start on HOME tab
        selectTab(TAB_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        VbUpdate.check(this);
        if (TAB_HOME.equals(currentTab)) {
            loadHome();
        } else if (TAB_WATCHLIST.equals(currentTab)) {
            loadWatchlist();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        VbUpdate.onStatus(this, intent);
    }

    // ---------------------------------------------------------------- Top Bar & Tabs

    private void buildTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, VbUi.dp(this, 14));

        // Brand Title
        TextView logo = new TextView(this);
        logo.setText("VIDBOX");
        logo.setTextColor(VbUi.ACCENT);
        logo.setTextSize(22);
        logo.setTypeface(null, android.graphics.Typeface.BOLD);
        logo.setPadding(0, 0, VbUi.dp(this, 30), 0);
        topBar.addView(logo);

        // Tab bar
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);

        for (String tabName : TABS) {
            TextView tv = new TextView(this);
            tv.setText(tabName);
            tv.setTextSize(15);
            tv.setTextColor(VbUi.TEXT_MUTED);
            tv.setFocusable(true);
            tv.setFocusableInTouchMode(true);
            tv.setPadding(VbUi.dp(this, 14), VbUi.dp(this, 6), VbUi.dp(this, 14), VbUi.dp(this, 6));

            tv.setOnFocusChangeListener((v, hasFocus) -> {
                updateTabStyle(tv, hasFocus, tabName.equals(currentTab));
                if (hasFocus) {
                    VbUi.animateFocus(v, true, 1.06f);
                } else {
                    VbUi.animateFocus(v, false, 1.0f);
                }
            });

            tv.setOnClickListener(v -> selectTab(tabName));
            tv.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        selectTab(tabName);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (TAB_HOME.equals(currentTab) && homeRowsContainer.getChildCount() > 0) {
                            View firstRow = homeRowsContainer.getChildAt(0);
                            firstRow.requestFocus();
                            return true;
                        } else if (!TAB_HOME.equals(currentTab) && gridView.getChildCount() > 0) {
                            gridView.requestFocus();
                            return true;
                        }
                    }
                }
                return false;
            });

            tabViews.add(tv);
            tabRow.addView(tv);
        }
        topBar.addView(tabRow);
        contentContainer.addView(topBar);
    }

    private void updateTabStyle(TextView tv, boolean hasFocus, boolean isSelected) {
        if (hasFocus) {
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 16), VbUi.dp(this, 2), VbUi.ACCENT));
        } else if (isSelected) {
            tv.setTextColor(VbUi.ACCENT);
            tv.setBackground(VbUi.roundRect(0x2238BDF8, VbUi.dp(this, 16), 0, 0));
        } else {
            tv.setTextColor(VbUi.TEXT_MUTED);
            tv.setBackground(null);
        }
    }

    private void selectTab(String tabName) {
        currentTab = tabName;
        for (int i = 0; i < TABS.length; i++) {
            TextView tv = tabViews.get(i);
            updateTabStyle(tv, tv.hasFocus(), TABS[i].equals(currentTab));
        }

        if (TAB_HOME.equals(tabName)) {
            homeScrollView.setVisibility(View.VISIBLE);
            gridContainer.setVisibility(View.GONE);
            loadHome();
        } else {
            homeScrollView.setVisibility(View.GONE);
            gridContainer.setVisibility(View.VISIBLE);
            page = 1;
            gridItems.clear();
            gridAdapter.notifyDataSetChanged();

            if (TAB_MOVIES.equals(tabName)) {
                genreScroll.setVisibility(View.VISIBLE);
                searchBoxRow.setVisibility(View.GONE);
                loadGenres(Vb.TYPE_MOVIE);
                loadMovies(1, 0);
            } else if (TAB_SHOWS.equals(tabName)) {
                genreScroll.setVisibility(View.VISIBLE);
                searchBoxRow.setVisibility(View.GONE);
                loadGenres(Vb.TYPE_TV);
                loadShows(1, 0);
            } else if (TAB_GENRES.equals(tabName)) {
                genreScroll.setVisibility(View.VISIBLE);
                searchBoxRow.setVisibility(View.GONE);
                loadGenres(Vb.TYPE_MOVIE);
            } else if (TAB_WATCHLIST.equals(tabName)) {
                genreScroll.setVisibility(View.GONE);
                searchBoxRow.setVisibility(View.GONE);
                loadWatchlist();
            } else if (TAB_SEARCH.equals(tabName)) {
                genreScroll.setVisibility(View.GONE);
                searchBoxRow.setVisibility(View.VISIBLE);
                searchEditText.requestFocus();
            }
        }
    }

    // ---------------------------------------------------------------- Home Rows

    private void loadHome() {
        setLoading(true);
        Vb.getHomeRows(new Vb.Callback<List<Vb.HomeRow>>() {
            @Override
            public void onSuccess(List<Vb.HomeRow> rows) {
                setLoading(false);
                homeRowsContainer.removeAllViews();
                for (Vb.HomeRow row : rows) {
                    if (row.items != null && !row.items.isEmpty()) {
                        buildHomeRow(row);
                    }
                }
                homeBuilt = true;
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
                Toast.makeText(VbBrowseActivity.this, "Network error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void buildHomeRow(Vb.HomeRow row) {
        LinearLayout rowBox = new LinearLayout(this);
        rowBox.setOrientation(LinearLayout.VERTICAL);
        rowBox.setPadding(0, 0, 0, VbUi.dp(this, 18));

        // Row title
        TextView titleView = new TextView(this);
        titleView.setText(row.title);
        titleView.setTextColor(VbUi.TEXT_WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(0, 0, 0, VbUi.dp(this, 8));
        rowBox.addView(titleView);

        // Horizontal scroll
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setClipToPadding(false);

        LinearLayout cardRow = new LinearLayout(this);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);

        for (Vb.Item item : row.items) {
            View card = createCardView(item, rowCardW, rowCardH, true);
            cardRow.addView(card);
        }
        hScroll.addView(cardRow);
        rowBox.addView(hScroll);
        homeRowsContainer.addView(rowBox);
    }

    // ---------------------------------------------------------------- Card View

    private View createCardView(Vb.Item item, int width, int height, boolean inRow) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        if (inRow) {
            lp.setMargins(0, 0, VbUi.dp(this, 12), 0);
        }
        card.setLayoutParams(lp);
        card.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 10), 0, 0));

        // Poster image
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(iv);
        VbUi.load(iv, item.getPosterUrl(), width, height, VbUi.dp(this, 10));

        // Bottom gradient overlay for title
        View gradient = new View(this);
        FrameLayout.LayoutParams gradLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, VbUi.dp(this, 60), Gravity.BOTTOM);
        gradient.setLayoutParams(gradLp);
        gradient.setBackground(VbUi.gradientRect(0x00000000, 0xEE090D16, GradientDrawable.Orientation.TOP_BOTTOM, VbUi.dp(this, 10)));
        card.addView(gradient);

        // Title & metadata text
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        infoBox.setLayoutParams(infoLp);
        infoBox.setPadding(VbUi.dp(this, 8), 0, VbUi.dp(this, 8), VbUi.dp(this, 6));

        TextView titleView = new TextView(this);
        titleView.setText(item.title);
        titleView.setTextColor(VbUi.TEXT_WHITE);
        titleView.setTextSize(13);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        infoBox.addView(titleView);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        if (item.rating > 0) {
            TextView ratingView = new TextView(this);
            ratingView.setText(String.format("★ %.1f", item.rating));
            ratingView.setTextColor(VbUi.STAR_YELLOW);
            ratingView.setTextSize(11);
            ratingView.setPadding(0, 0, VbUi.dp(this, 6), 0);
            metaRow.addView(ratingView);
        }

        if (item.year != null && !item.year.isEmpty()) {
            TextView yearView = new TextView(this);
            yearView.setText(item.year);
            yearView.setTextColor(VbUi.TEXT_MUTED);
            yearView.setTextSize(11);
            metaRow.addView(yearView);
        }
        infoBox.addView(metaRow);
        card.addView(infoBox);

        // Resume progress bar (if watched before)
        if (item.resumePosition > 0 && item.duration > 0) {
            View progBg = new View(this);
            FrameLayout.LayoutParams progBgLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, VbUi.dp(this, 4), Gravity.BOTTOM);
            progBg.setLayoutParams(progBgLp);
            progBg.setBackgroundColor(0x88000000);
            card.addView(progBg);

            View progBar = new View(this);
            int barW = (int) (width * Math.min(1.0f, (float) item.resumePosition / item.duration));
            FrameLayout.LayoutParams progLp = new FrameLayout.LayoutParams(barW, VbUi.dp(this, 4), Gravity.BOTTOM);
            progBar.setLayoutParams(progLp);
            progBar.setBackgroundColor(VbUi.ACCENT);
            card.addView(progBar);
        }

        // Focus listener
        card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                card.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 10), VbUi.dp(this, 3), VbUi.ACCENT));
                VbUi.animateFocus(card, true, 1.08f);
                // Update background backdrop
                String backdropUrl = item.getBackdropUrl();
                if (backdropUrl != null) {
                    VbUi.load(backdropView, backdropUrl, screenW, screenH, 0);
                }
                titleView.setSelected(true); // enables marquee if text is long
            } else {
                card.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 10), 0, 0));
                VbUi.animateFocus(card, false, 1.0f);
                titleView.setSelected(false);
            }
        });

        // Click listener -> Open Detail Screen
        card.setOnClickListener(v -> openDetail(item));

        return card;
    }

    private void openDetail(Vb.Item item) {
        if (item == null) return;
        try {
            Intent intent = new Intent(this, VbDetailActivity.class);
            intent.putExtra("item_json", item.toJson().toString());
            intent.putExtra("id", item.id);
            intent.putExtra("type", item.type);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Error opening title: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- Grid View & Pagination

    private void loadMovies(int p, int genreId) {
        setLoading(true);
        Vb.getMovies(p, genreId, new Vb.Callback<List<Vb.Item>>() {
            @Override
            public void onSuccess(List<Vb.Item> items) {
                setLoading(false);
                if (p == 1) gridItems.clear();
                gridItems.addAll(items);
                gridAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
            }
        });
    }

    private void loadShows(int p, int genreId) {
        setLoading(true);
        Vb.getShows(p, genreId, new Vb.Callback<List<Vb.Item>>() {
            @Override
            public void onSuccess(List<Vb.Item> items) {
                setLoading(false);
                if (p == 1) gridItems.clear();
                gridItems.addAll(items);
                gridAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
            }
        });
    }

    private void loadWatchlist() {
        gridItems.clear();
        gridItems.addAll(Vb.getWatchlist());
        gridAdapter.notifyDataSetChanged();
    }

    private void performSearch(String q) {
        setLoading(true);
        Vb.search(q, 1, new Vb.Callback<List<Vb.Item>>() {
            @Override
            public void onSuccess(List<Vb.Item> items) {
                setLoading(false);
                gridItems.clear();
                gridItems.addAll(items);
                gridAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
                Toast.makeText(VbBrowseActivity.this, "Search error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadGenres(String type) {
        Vb.getGenres(type, new Vb.Callback<List<Vb.Genre>>() {
            @Override
            public void onSuccess(List<Vb.Genre> genres) {
                genreRow.removeAllViews();
                // Add "All" option
                addGenreChip("All", 0, type);
                for (Vb.Genre g : genres) {
                    addGenreChip(g.name, g.id, type);
                }
            }

            @Override
            public void onError(Exception e) {}
        });
    }

    private void addGenreChip(String name, int id, String type) {
        TextView chip = new TextView(this);
        chip.setText(name);
        chip.setTextColor(id == currentGenreId ? VbUi.ACCENT : VbUi.TEXT_MUTED);
        chip.setTextSize(14);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setPadding(VbUi.dp(this, 12), VbUi.dp(this, 5), VbUi.dp(this, 12), VbUi.dp(this, 5));
        chip.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 14), 0, 0));

        chip.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                chip.setTextColor(0xFFFFFFFF);
                chip.setBackground(VbUi.roundRect(VbUi.CARD_FOCUSED, VbUi.dp(this, 14), VbUi.dp(this, 2), VbUi.ACCENT));
                VbUi.animateFocus(v, true, 1.08f);
            } else {
                chip.setTextColor(id == currentGenreId ? VbUi.ACCENT : VbUi.TEXT_MUTED);
                chip.setBackground(VbUi.roundRect(VbUi.CARD_BG, VbUi.dp(this, 14), 0, 0));
                VbUi.animateFocus(v, false, 1.0f);
            }
        });

        chip.setOnClickListener(v -> {
            currentGenreId = id;
            page = 1;
            if (Vb.TYPE_TV.equals(type)) {
                loadShows(1, id);
            } else {
                loadMovies(1, id);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, VbUi.dp(this, 8), 0);
        chip.setLayoutParams(lp);
        genreRow.addView(chip);
    }

    private void loadNextPage() {
        page++;
        if (TAB_MOVIES.equals(currentTab)) {
            loadMovies(page, currentGenreId);
        } else if (TAB_SHOWS.equals(currentTab)) {
            loadShows(page, currentGenreId);
        }
    }

    private void setLoading(boolean l) {
        this.loading = l;
        loadingSpinner.setVisibility(l ? View.VISIBLE : View.GONE);
    }

    private final class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return gridItems.size();
        }

        @Override
        public Object getItem(int position) {
            return gridItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return gridItems.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Vb.Item item = gridItems.get(position);
            return createCardView(item, posterW, posterH, false);
        }
    }

    // ---------------------------------------------------------------- Remote Navigation

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!TAB_HOME.equals(currentTab)) {
                selectTab(TAB_HOME);
                tabViews.get(0).requestFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
