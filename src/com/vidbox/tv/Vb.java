package com.vidbox.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core Data layer for Vidbox TV.
 *
 * Interfaces with TMDB and Vidbox streaming providers:
 * - Catalogue: Trending, Now Playing, Platform Originals (Netflix, Amazon, Apple, Disney), Top Rated
 * - Detail: Synopsis, runtime, rating, genres, cast, season/episode listings, recommendations
 * - Streaming: Server resolution across VidLink (Echo), Max, 4K, Vidfast, V2, Vidpro, Premium,
 *   Nxsha, Vidspark, 2Embed, Cinemaos, and Vidbox direct sessions.
 * - History & Watchlist: Local persistence via SharedPreferences with resume positions.
 */
public final class Vb {
    public static final String SITE = "https://vidbox.wtf";
    public static final String TMDB_API = "https://api.themoviedb.org/3";
    public static final String TMDB_KEY = "ef311eb0b9b07b9c73e9fb0a732cc150";
    public static final String IMG_POSTER = "https://image.tmdb.org/t/p/w500";
    public static final String IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280";
    public static final String IMG_STILL = "https://image.tmdb.org/t/p/w300";

    public static final String UA = "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    public static final String TYPE_MOVIE = "movie";
    public static final String TYPE_TV = "tv";

    private static final long LIST_TTL = 15 * 60_000L;    // 15 minutes
    private static final long DETAIL_TTL = 60 * 60_000L;  // 1 hour
    private static final long GENRE_TTL = 24 * 60 * 60_000L; // 24 hours

    public static final ExecutorService IO = Executors.newFixedThreadPool(4);
    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context app;
    private static SharedPreferences prefs;
    private static final ConcurrentHashMap<String, Object[]> memCache = new ConcurrentHashMap<>();

    private Vb() {}

    public static void init(Context c) {
        if (app == null) {
            app = c.getApplicationContext();
            prefs = app.getSharedPreferences("vidbox_data", Context.MODE_PRIVATE);
        }
    }

    // ---------------------------------------------------------------- models

    public static final class Item {
        public long id;
        public String type; // "movie" or "tv"
        public String title;
        public String overview;
        public String poster;
        public String backdrop;
        public double rating;
        public String year = "";
        public int season = 1;
        public int episode = 1;
        public long resumePosition = 0;
        public long duration = 0;

        public String getPosterUrl() {
            if (poster == null || poster.isEmpty()) return null;
            if (poster.startsWith("http")) return poster;
            return IMG_POSTER + poster;
        }

        public String getBackdropUrl() {
            if (backdrop == null || backdrop.isEmpty()) return getPosterUrl();
            if (backdrop.startsWith("http")) return backdrop;
            return IMG_BACKDROP + backdrop;
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("type", type)
                    .put("title", title)
                    .put("overview", overview)
                    .put("poster", poster)
                    .put("backdrop", backdrop)
                    .put("rating", rating)
                    .put("year", year)
                    .put("season", season)
                    .put("episode", episode)
                    .put("resumePosition", resumePosition)
                    .put("duration", duration);
        }

        public static Item fromJson(JSONObject o) {
            if (o == null) return null;
            Item it = new Item();
            it.id = o.optLong("id");
            it.type = o.optString("type", TYPE_MOVIE);
            it.title = o.optString("title", o.optString("name", "Untitled"));
            it.overview = o.optString("overview", "");
            it.poster = o.optString("poster_path", o.optString("poster", ""));
            it.backdrop = o.optString("backdrop_path", o.optString("backdrop", ""));
            it.rating = o.optDouble("vote_average", o.optDouble("rating", 0.0));
            String date = o.optString("release_date", o.optString("first_air_date", o.optString("year", "")));
            it.year = date.length() >= 4 ? date.substring(0, 4) : date;
            it.season = o.optInt("season", 1);
            it.episode = o.optInt("episode", 1);
            it.resumePosition = o.optLong("resumePosition", 0);
            it.duration = o.optLong("duration", 0);
            return it;
        }
    }

    public static final class Detail {
        public Item item;
        public String imdbId;
        public String tagline;
        public int runtime; // in minutes
        public int seasonsCount;
        public int episodesCount;
        public final List<String> genres = new ArrayList<>();
        public final List<String> cast = new ArrayList<>();
        public final List<Season> seasons = new ArrayList<>();
        public final List<Item> similar = new ArrayList<>();

        public static Detail fromJson(JSONObject o, String type) {
            if (o == null) return null;
            Detail d = new Detail();
            d.item = Item.fromJson(o);
            d.item.type = type;
            d.tagline = o.optString("tagline", "");
            d.runtime = o.optInt("runtime", 0);
            if (d.runtime == 0) {
                JSONArray runtimes = o.optJSONArray("episode_run_time");
                if (runtimes != null && runtimes.length() > 0) {
                    d.runtime = runtimes.optInt(0, 45);
                }
            }

            JSONObject ext = o.optJSONObject("external_ids");
            if (ext != null) {
                d.imdbId = ext.optString("imdb_id", null);
            }
            if (d.imdbId == null) {
                d.imdbId = o.optString("imdb_id", null);
            }

            JSONArray gArr = o.optJSONArray("genres");
            if (gArr != null) {
                for (int i = 0; i < gArr.length(); i++) {
                    JSONObject g = gArr.optJSONObject(i);
                    if (g != null) d.genres.add(g.optString("name"));
                }
            }

            JSONObject credits = o.optJSONObject("credits");
            if (credits != null) {
                JSONArray cArr = credits.optJSONArray("cast");
                if (cArr != null) {
                    for (int i = 0; i < Math.min(10, cArr.length()); i++) {
                        JSONObject c = cArr.optJSONObject(i);
                        if (c != null) d.cast.add(c.optString("name"));
                    }
                }
            }

            d.seasonsCount = o.optInt("number_of_seasons", 0);
            d.episodesCount = o.optInt("number_of_episodes", 0);

            JSONArray sArr = o.optJSONArray("seasons");
            if (sArr != null) {
                for (int i = 0; i < sArr.length(); i++) {
                    JSONObject sObj = sArr.optJSONObject(i);
                    if (sObj != null) {
                        int sNum = sObj.optInt("season_number");
                        if (sNum > 0) { // skip season 0 specials for cleaner TV browse
                            Season s = new Season();
                            s.seasonNumber = sNum;
                            s.name = sObj.optString("name", "Season " + sNum);
                            s.episodeCount = sObj.optInt("episode_count", 0);
                            d.seasons.add(s);
                        }
                    }
                }
            }

            JSONObject sim = o.optJSONObject("recommendations");
            if (sim == null) sim = o.optJSONObject("similar");
            if (sim != null) {
                JSONArray simArr = sim.optJSONArray("results");
                if (simArr != null) {
                    for (int i = 0; i < Math.min(12, simArr.length()); i++) {
                        JSONObject itObj = simArr.optJSONObject(i);
                        if (itObj != null) {
                            Item it = Item.fromJson(itObj);
                            it.type = type;
                            d.similar.add(it);
                        }
                    }
                }
            }
            return d;
        }
    }

    public static final class Season {
        public int seasonNumber;
        public String name;
        public int episodeCount;
        public final List<Episode> episodes = new ArrayList<>();
    }

    public static final class Episode {
        public long id;
        public int seasonNumber;
        public int episodeNumber;
        public String title;
        public String overview;
        public String stillPath;
        public String airDate;

        public String getStillUrl() {
            if (stillPath == null || stillPath.isEmpty()) return null;
            if (stillPath.startsWith("http")) return stillPath;
            return IMG_STILL + stillPath;
        }
    }

    public static final class Genre {
        public int id;
        public String name;

        public Genre(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    // ---------------------------------------------------------------- servers / providers

    public static final class Server {
        public final String name;
        public final String countryCode;
        public final String movieUrlTemplate;
        public final String tvUrlTemplate;
        public final boolean requiresImdb;

        public Server(String name, String countryCode, String movieUrlTemplate, String tvUrlTemplate, boolean requiresImdb) {
            this.name = name;
            this.countryCode = countryCode;
            this.movieUrlTemplate = movieUrlTemplate;
            this.tvUrlTemplate = tvUrlTemplate;
            this.requiresImdb = requiresImdb;
        }

        public String buildUrl(String type, long tmdbId, String imdbId, int season, int episode) {
            String id = (requiresImdb && imdbId != null && !imdbId.isEmpty()) ? imdbId : String.valueOf(tmdbId);
            if (TYPE_MOVIE.equals(type)) {
                return movieUrlTemplate.replace("{id}", id);
            } else {
                return tvUrlTemplate.replace("{id}", id)
                        .replace("{season}", String.valueOf(season))
                        .replace("{episode}", String.valueOf(episode));
            }
        }
    }

    public static final List<Server> SERVERS = Collections.unmodifiableList(Arrays.asList(
            new Server("Echo (VidLink)", "US",
                    "https://vidlink.pro/movie/{id}?primaryColor=38BDF8&secondaryColor=131B2E&iconColor=38BDF8&title=false&poster=true&autoplay=true",
                    "https://vidlink.pro/tv/{id}/{season}/{episode}?primaryColor=38BDF8&secondaryColor=131B2E&iconColor=38BDF8&title=false&poster=true&autoplay=true",
                    false),
            new Server("Max (YTHD)", "US",
                    "https://ythd.org/embed/{id}",
                    "https://ythd.org/embed/{id}/{season}-{episode}",
                    false),
            new Server("4K (Vidrift)", "GB",
                    "https://embed.vidrift.net/embed/movie/{id}?autoplay=true",
                    "https://embed.vidrift.net/embed/tv/{id}/{season}/{episode}?autoplay=true",
                    false),
            new Server("Vidfast", "GB",
                    "https://vidfast.vc/movie/{id}?autoplay=true",
                    "https://vidfast.vc/tv/{id}/{season}/{episode}?autoplay=true",
                    false),
            new Server("V2 (VAPlayer)", "RU",
                    "https://vaplayer.ru/embed/movie/{id}?autoplay=true",
                    "https://vaplayer.ru/embed/tv/{id}/{season}/{episode}?autoplay=true",
                    false),
            new Server("Vidpro (Vixsrc)", "GB",
                    "https://vixsrc.to/movie/{id}",
                    "https://vixsrc.to/tv/{id}/{season}/{episode}",
                    false),
            new Server("Premium (Vidy)", "US",
                    "https://www.vidy.st/movie/{id}?color=38BDF8&autoplay=true",
                    "https://www.vidy.st/tv/{id}/{season}/{episode}?color=38BDF8&nextEpisode=true&autoplayNextEpisode=true&autoplay=true",
                    false),
            new Server("Nxsha", "US",
                    "https://nxsha.space/embed/movie/{id}?lang=en&autoplay=true&sub=en",
                    "https://nxsha.space/embed/tv/{id}/{season}/{episode}?lang=en&autoplay=true&sub=en",
                    false),
            new Server("Vidspark", "AU",
                    "https://vidnest.pro/movie/{id}?autoplay=true",
                    "https://vidnest.pro/tv/{id}/{season}/{episode}?autoplay=true",
                    false),
            new Server("2Embed", "AU",
                    "https://2embed.stream/embed/movie/{id}",
                    "https://www.2embed.stream/embed/tv/{id}/{season}/{episode}",
                    false),
            new Server("Cinemaos", "US",
                    "https://cinemaos.tech/player/{id}",
                    "https://cinemaos.tech/player/{id}/{season}/{episode}",
                    false),
            new Server("Primesrc", "AU",
                    "https://primesrc.me/embed/movie?imdb={id}",
                    "https://primesrc.me/embed/tv?tmdb={id}&season={season}&episode={episode}",
                    true)
    ));

    // ---------------------------------------------------------------- API & catalog

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public static final class HomeRow {
        public final String title;
        public final List<Item> items;

        public HomeRow(String title, List<Item> items) {
            this.title = title;
            this.items = items;
        }
    }

    /**
     * Loads all Home screen rows: Continue Watching, Trending Movies, Trending TV,
     * Now Playing, Netflix Originals, Amazon Prime, Apple TV+, Disney+, Top Rated.
     */
    public static void getHomeRows(Callback<List<HomeRow>> cb) {
        IO.execute(() -> {
            try {
                List<HomeRow> rows = new ArrayList<>();

                // 1. Continue Watching
                List<Item> continueItems = getContinueWatching();
                if (!continueItems.isEmpty()) {
                    rows.add(new HomeRow("Continue Watching", continueItems));
                }

                // 2. Trending Movies
                rows.add(new HomeRow("Trending Movies",
                        fetchList(TMDB_API + "/trending/movie/day?api_key=" + TMDB_KEY, TYPE_MOVIE)));

                // 3. Trending TV Shows
                rows.add(new HomeRow("Trending TV Shows",
                        fetchList(TMDB_API + "/trending/tv/day?api_key=" + TMDB_KEY, TYPE_TV)));

                // 4. Now Playing
                rows.add(new HomeRow("Now Playing in Theaters",
                        fetchList(TMDB_API + "/movie/now_playing?api_key=" + TMDB_KEY, TYPE_MOVIE)));

                // 5. Netflix Originals (Network ID 213)
                rows.add(new HomeRow("Netflix Originals",
                        fetchList(TMDB_API + "/discover/tv?api_key=" + TMDB_KEY + "&with_networks=213&sort_by=popularity.desc", TYPE_TV)));

                // 6. Amazon Prime (Network ID 1024)
                rows.add(new HomeRow("Amazon Prime Shows",
                        fetchList(TMDB_API + "/discover/tv?api_key=" + TMDB_KEY + "&with_networks=1024&sort_by=popularity.desc", TYPE_TV)));

                // 7. Apple TV+ (Network ID 2552)
                rows.add(new HomeRow("Apple TV+ Shows",
                        fetchList(TMDB_API + "/discover/tv?api_key=" + TMDB_KEY + "&with_networks=2552&sort_by=popularity.desc", TYPE_TV)));

                // 8. Disney+ (Network ID 2739)
                rows.add(new HomeRow("Disney+ Shows",
                        fetchList(TMDB_API + "/discover/tv?api_key=" + TMDB_KEY + "&with_networks=2739&sort_by=popularity.desc", TYPE_TV)));

                // 9. Top Rated Movies
                rows.add(new HomeRow("Top Rated Movies",
                        fetchList(TMDB_API + "/movie/top_rated?api_key=" + TMDB_KEY, TYPE_MOVIE)));

                // 10. Top Rated TV Shows
                rows.add(new HomeRow("Top Rated TV Shows",
                        fetchList(TMDB_API + "/tv/top_rated?api_key=" + TMDB_KEY, TYPE_TV)));

                MAIN.post(() -> cb.onSuccess(rows));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    /**
     * Browse Movies with pagination and optional genre filter.
     */
    public static void getMovies(int page, int genreId, Callback<List<Item>> cb) {
        IO.execute(() -> {
            try {
                String url = TMDB_API + "/discover/movie?api_key=" + TMDB_KEY + "&page=" + page + "&sort_by=popularity.desc";
                if (genreId > 0) url += "&with_genres=" + genreId;
                List<Item> items = fetchList(url, TYPE_MOVIE);
                MAIN.post(() -> cb.onSuccess(items));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    /**
     * Browse TV Shows with pagination and optional genre filter.
     */
    public static void getShows(int page, int genreId, Callback<List<Item>> cb) {
        IO.execute(() -> {
            try {
                String url = TMDB_API + "/discover/tv?api_key=" + TMDB_KEY + "&page=" + page + "&sort_by=popularity.desc";
                if (genreId > 0) url += "&with_genres=" + genreId;
                List<Item> items = fetchList(url, TYPE_TV);
                MAIN.post(() -> cb.onSuccess(items));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    /**
     * Search movies and TV shows across TMDB.
     */
    public static void search(String query, int page, Callback<List<Item>> cb) {
        IO.execute(() -> {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                String url = TMDB_API + "/search/multi?api_key=" + TMDB_KEY + "&query=" + encoded + "&page=" + page;
                String json = httpGetCached(url, LIST_TTL);
                JSONObject obj = new JSONObject(json);
                JSONArray results = obj.optJSONArray("results");
                List<Item> list = new ArrayList<>();
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject it = results.optJSONObject(i);
                        if (it != null) {
                            String mediaType = it.optString("media_type", "");
                            if (TYPE_MOVIE.equals(mediaType) || TYPE_TV.equals(mediaType)) {
                                Item item = Item.fromJson(it);
                                item.type = mediaType;
                                if (item.poster != null && !item.poster.isEmpty()) {
                                    list.add(item);
                                }
                            }
                        }
                    }
                }
                MAIN.post(() -> cb.onSuccess(list));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    /**
     * Fetch complete detail for a movie or TV show.
     */
    public static void getDetail(String type, long id, Callback<Detail> cb) {
        IO.execute(() -> {
            try {
                String url = TMDB_API + "/" + type + "/" + id + "?api_key=" + TMDB_KEY
                        + "&append_to_response=external_ids,credits,recommendations,similar";
                String json = httpGetCached(url, DETAIL_TTL);
                Detail detail = Detail.fromJson(new JSONObject(json), type);
                MAIN.post(() -> cb.onSuccess(detail));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    /**
     * Fetch episodes for a specific season of a TV show.
     */
    public static void getSeasonEpisodes(long tvId, int seasonNumber, Callback<List<Episode>> cb) {
        IO.execute(() -> {
            try {
                String url = TMDB_API + "/tv/" + tvId + "/season/" + seasonNumber + "?api_key=" + TMDB_KEY;
                String json = httpGetCached(url, DETAIL_TTL);
                JSONObject obj = new JSONObject(json);
                JSONArray epArr = obj.optJSONArray("episodes");
                List<Episode> list = new ArrayList<>();
                if (epArr != null) {
                    for (int i = 0; i < epArr.length(); i++) {
                        JSONObject eObj = epArr.optJSONObject(i);
                        if (eObj != null) {
                            Episode ep = new Episode();
                            ep.id = eObj.optLong("id");
                            ep.seasonNumber = eObj.optInt("season_number", seasonNumber);
                            ep.episodeNumber = eObj.optInt("episode_number", i + 1);
                            ep.title = eObj.optString("name", "Episode " + ep.episodeNumber);
                            ep.overview = eObj.optString("overview", "");
                            ep.stillPath = eObj.optString("still_path", "");
                            ep.airDate = eObj.optString("air_date", "");
                            list.add(ep);
                        }
                    }
                }
                MAIN.post(() -> cb.onSuccess(list));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    /**
     * Fetch genre list.
     */
    public static void getGenres(String type, Callback<List<Genre>> cb) {
        IO.execute(() -> {
            try {
                String url = TMDB_API + "/genre/" + type + "/list?api_key=" + TMDB_KEY;
                String json = httpGetCached(url, GENRE_TTL);
                JSONObject obj = new JSONObject(json);
                JSONArray gArr = obj.optJSONArray("genres");
                List<Genre> list = new ArrayList<>();
                if (gArr != null) {
                    for (int i = 0; i < gArr.length(); i++) {
                        JSONObject g = gArr.optJSONObject(i);
                        if (g != null) {
                            list.add(new Genre(g.optInt("id"), g.optString("name")));
                        }
                    }
                }
                MAIN.post(() -> cb.onSuccess(list));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    // ---------------------------------------------------------------- history & watchlist

    public static synchronized void saveProgress(Item item, int season, int episode, long position, long duration) {
        if (item == null || prefs == null) return;
        try {
            item.season = season;
            item.episode = episode;
            item.resumePosition = position;
            item.duration = duration;

            String key = "prog_" + item.type + "_" + item.id;
            prefs.edit().putString(key, item.toJson().toString()).apply();

            // Update recent list
            String recentStr = prefs.getString("recent_keys", "[]");
            JSONArray arr = new JSONArray(recentStr);
            JSONArray updated = new JSONArray();
            updated.put(key);
            for (int i = 0; i < arr.length(); i++) {
                String k = arr.optString(i);
                if (!k.equals(key) && updated.length() < 20) {
                    updated.put(k);
                }
            }
            prefs.edit().putString("recent_keys", updated.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized List<Item> getContinueWatching() {
        List<Item> list = new ArrayList<>();
        if (prefs == null) return list;
        try {
            String recentStr = prefs.getString("recent_keys", "[]");
            JSONArray arr = new JSONArray(recentStr);
            for (int i = 0; i < arr.length(); i++) {
                String key = arr.optString(i);
                String json = prefs.getString(key, null);
                if (json != null) {
                    Item it = Item.fromJson(new JSONObject(json));
                    if (it != null) list.add(it);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static synchronized boolean isInWatchlist(long id, String type) {
        if (prefs == null) return false;
        return prefs.contains("watch_" + type + "_" + id);
    }

    public static synchronized void toggleWatchlist(Item item) {
        if (item == null || prefs == null) return;
        String key = "watch_" + item.type + "_" + item.id;
        try {
            if (prefs.contains(key)) {
                prefs.edit().remove(key).apply();
            } else {
                prefs.edit().putString(key, item.toJson().toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    public static synchronized List<Item> getWatchlist() {
        List<Item> list = new ArrayList<>();
        if (prefs == null) return list;
        try {
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (entry.getKey().startsWith("watch_")) {
                    String json = (String) entry.getValue();
                    Item it = Item.fromJson(new JSONObject(json));
                    if (it != null) list.add(it);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    // ---------------------------------------------------------------- http helpers

    private static List<Item> fetchList(String url, String type) throws Exception {
        String json = httpGetCached(url, LIST_TTL);
        JSONObject obj = new JSONObject(json);
        JSONArray results = obj.optJSONArray("results");
        List<Item> list = new ArrayList<>();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject it = results.optJSONObject(i);
                if (it != null) {
                    Item item = Item.fromJson(it);
                    item.type = type;
                    if (item.poster != null && !item.poster.isEmpty()) {
                        list.add(item);
                    }
                }
            }
        }
        return list;
    }

    public static String httpGetCached(String urlStr, long ttl) throws Exception {
        String key = md5(urlStr);
        Object[] hit = memCache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && (now - (Long) hit[0]) < ttl) {
            return (String) hit[1];
        }

        // Check disk cache
        if (app != null) {
            File disk = new File(app.getCacheDir(), "vb_" + key);
            if (disk.exists() && (now - disk.lastModified()) < ttl) {
                try (InputStream in = new FileInputStream(disk)) {
                    String cached = new String(readAll(in), "UTF-8");
                    memCache.put(key, new Object[]{now, cached});
                    return cached;
                } catch (Exception ignored) {}
            }
        }

        // Fetch fresh
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        try (InputStream in = c.getInputStream()) {
            String res = new String(readAll(in), "UTF-8");
            memCache.put(key, new Object[]{now, res});
            if (app != null) {
                try (OutputStream out = new FileOutputStream(new File(app.getCacheDir(), "vb_" + key))) {
                    out.write(res.getBytes("UTF-8"));
                } catch (Exception ignored) {}
            }
            return res;
        } finally {
            c.disconnect();
        }
    }

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    public static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
