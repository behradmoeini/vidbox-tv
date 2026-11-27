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
                            String aired = sObj.isNull("air_date") ? "" : sObj.optString("air_date", "");
                            if (s.episodeCount == 0 || aired.isEmpty()) continue; // announced, not broadcast
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

    // ---------------------------------------------------------------- background work

    public interface Job<T> {
        T run() throws Exception;
    }

    public interface Done<T> {
        /** On the main thread: the result, or {@code null} and the error. */
        void done(T result, Exception error);
    }

    public static <T> void async(final Job<T> job, final Done<T> done) {
        IO.execute(() -> {
            T out = null;
            Exception err = null;
            try {
                out = job.run();
            } catch (Exception e) {
                err = e;
            }
            final T o = out;
            final Exception e = err;
            MAIN.post(() -> done.done(o, e));
        });
    }

    // ---------------------------------------------------------------- catalogue (TMDB)

    public static final class Page {
        public final List<Item> items = new ArrayList<>();
        public int totalPages = 1;
    }

    private static String api(String path) {
        return TMDB_API + path + (path.contains("?") ? "&" : "?") + "api_key=" + TMDB_KEY;
    }

    /** One page of a TMDB list, e.g. {@code /trending/movie/day} or {@code /discover/tv?with_genres=18}. */
    public static Page list(String path, String type, int page) throws Exception {
        JSONObject obj = new JSONObject(httpGetCached(api(path) + "&page=" + page, LIST_TTL));
        Page out = new Page();
        out.totalPages = Math.min(obj.optInt("total_pages", 1), 500); // TMDB serves no page past 500
        JSONArray results = obj.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String t = type != null ? type : o.optString("media_type", "");
            if (!TYPE_MOVIE.equals(t) && !TYPE_TV.equals(t)) continue; // search also returns people
            Item it = Item.fromJson(o);
            it.type = t;
            if (it.poster != null && !it.poster.isEmpty()) out.items.add(it);
        }
        return out;
    }

    public static Page popular(String type, int page, int genreId) throws Exception {
        return list("/discover/" + type + "?sort_by=popularity.desc" + (genreId > 0 ? "&with_genres=" + genreId : ""),
                type, page);
    }

    public static Page search(String query, int page) throws Exception {
        return list("/search/multi?query=" + URLEncoder.encode(query, "UTF-8"), null, page);
    }

    public static Detail detail(String type, long id) throws Exception {
        String url = api("/" + type + "/" + id + "?append_to_response=external_ids,credits,recommendations,similar");
        return Detail.fromJson(new JSONObject(httpGetCached(url, DETAIL_TTL)), type);
    }

    /** The episodes of one season that have already been broadcast. */
    public static List<Episode> episodes(long tvId, int seasonNumber) throws Exception {
        JSONObject obj = new JSONObject(httpGetCached(api("/tv/" + tvId + "/season/" + seasonNumber), DETAIL_TTL));
        JSONArray arr = obj.optJSONArray("episodes");
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        List<Episode> list = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Episode ep = new Episode();
            ep.id = o.optLong("id");
            ep.seasonNumber = o.optInt("season_number", seasonNumber);
            ep.episodeNumber = o.optInt("episode_number", i + 1);
            ep.title = o.optString("name", "Episode " + ep.episodeNumber);
            ep.overview = o.optString("overview", "");
            ep.stillPath = o.optString("still_path", "");
            ep.airDate = o.isNull("air_date") ? "" : o.optString("air_date", "");
            if (ep.airDate.isEmpty() || ep.airDate.compareTo(today) > 0) continue;
            list.add(ep);
        }
        return list;
    }

    public static List<Genre> genres(String type) throws Exception {
        JSONObject obj = new JSONObject(httpGetCached(api("/genre/" + type + "/list"), GENRE_TTL));
        JSONArray arr = obj.optJSONArray("genres");
        List<Genre> list = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject g = arr.optJSONObject(i);
            if (g != null) list.add(new Genre(g.optInt("id"), g.optString("name")));
        }
        return list;
    }

    // ---------------------------------------------------------------- watch progress

    public static SharedPreferences prefs() {
        return prefs;
    }

    /** The key one film or one episode keeps its position under. */
    public static String playKey(String type, long id, int season, int episode) {
        return TYPE_TV.equals(type) ? "t" + id + "_" + season + "_" + episode : "m" + id;
    }

    /** Milliseconds watched; 0 if never started; -1 if watched to the end. */
    public static long position(String key) {
        return prefs == null ? 0 : prefs.getLong("pos_" + key, 0);
    }

    public static long duration(String key) {
        return prefs == null ? 0 : prefs.getLong("dur_" + key, 0);
    }

    public static void savePosition(String key, long pos, long dur) {
        if (prefs == null || dur <= 0) return;
        boolean finished = pos >= dur - Math.max(60_000, dur / 20); // the credits count as the end
        prefs.edit().putLong("pos_" + key, finished ? -1 : Math.max(pos, 1)).putLong("dur_" + key, dur).apply();
    }

    public static void clearPosition(String key) {
        if (prefs != null) prefs.edit().remove("pos_" + key).apply();
    }

    /** Season and episode last played of a series, or null. */
    public static int[] lastEpisode(long tvId) {
        String s = prefs == null ? null : prefs.getString("last_" + tvId, null);
        if (s == null) return null;
        try {
            String[] p = s.split("_");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (Exception e) {
            return null;
        }
    }

    /** Put a title at the front of "Continue watching" (and remember which episode it was). */
    public static synchronized void touchRecent(Item item, int season, int episode) {
        if (item == null || prefs == null) return;
        try {
            item.season = season;
            item.episode = episode;
            String key = "prog_" + item.type + "_" + item.id;
            JSONArray old = new JSONArray(prefs.getString("recent_keys", "[]"));
            JSONArray updated = new JSONArray();
            updated.put(key);
            for (int i = 0; i < old.length() && updated.length() < 20; i++) {
                if (!key.equals(old.optString(i))) updated.put(old.optString(i));
            }
            SharedPreferences.Editor e = prefs.edit().putString(key, item.toJson().toString())
                    .putString("recent_keys", updated.toString());
            if (TYPE_TV.equals(item.type)) e.putString("last_" + item.id, season + "_" + episode);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    /** Titles in progress, most recent first; a film watched to the end drops out. */
    public static synchronized List<Item> getContinueWatching() {
        List<Item> list = new ArrayList<>();
        if (prefs == null) return list;
        try {
            JSONArray arr = new JSONArray(prefs.getString("recent_keys", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String json = prefs.getString(arr.optString(i), null);
                if (json == null) continue;
                Item it = Item.fromJson(new JSONObject(json));
                String key = playKey(it.type, it.id, it.season, it.episode);
                long pos = position(key);
                if (pos == -1 && TYPE_MOVIE.equals(it.type)) continue;
                it.resumePosition = Math.max(pos, 0);
                it.duration = duration(key);
                list.add(it);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    // ---------------------------------------------------------------- watchlist

    public static synchronized boolean isInWatchlist(long id, String type) {
        return prefs != null && prefs.contains("watch_" + type + "_" + id);
    }

    public static synchronized void toggleWatchlist(Item item) {
        if (item == null || prefs == null) return;
        String key = "watch_" + item.type + "_" + item.id;
        try {
            if (prefs.contains(key)) prefs.edit().remove(key).apply();
            else prefs.edit().putString(key, item.toJson().put("added", System.currentTimeMillis()).toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** The watchlist, newest addition first. */
    public static synchronized List<Item> getWatchlist() {
        List<Item> list = new ArrayList<>();
        if (prefs == null) return list;
        final Map<Item, Long> added = new HashMap<>();
        try {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (!entry.getKey().startsWith("watch_")) continue;
                JSONObject o = new JSONObject((String) entry.getValue());
                Item it = Item.fromJson(o);
                added.put(it, o.optLong("added", 0));
                list.add(it);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(list, (a, b) -> Long.compare(added.get(b), added.get(a)));
        return list;
    }

    // ---------------------------------------------------------------- stream server choice

    /** Index in {@link #SERVERS} of the server the viewer pinned, or -1 for "automatic". */
    public static int pinnedServer() {
        String name = prefs == null ? null : prefs.getString("server_pin", null);
        for (int i = 0; i < SERVERS.size(); i++) if (SERVERS.get(i).name.equals(name)) return i;
        return -1;
    }

    public static void pinServer(int index) {
        if (prefs == null) return;
        if (index < 0) prefs.edit().remove("server_pin").apply();
        else prefs.edit().putString("server_pin", SERVERS.get(index).name).apply();
    }

    private static final long FAIL_MEMORY_MS = 6 * 60 * 60_000L;

    /** Record how a server did: it played after {@code startMs}, or it gave nothing. */
    public static void serverResult(int index, boolean ok, long startMs) {
        if (prefs == null) return;
        String n = SERVERS.get(index).name;
        SharedPreferences.Editor e = prefs.edit();
        if (ok) {
            long old = prefs.getLong("srv_ms_" + n, 0);
            e.putLong("srv_ms_" + n, old == 0 ? startMs : (old + startMs) / 2).putInt("srv_fail_" + n, 0);
        } else {
            e.putInt("srv_fail_" + n, recentFails(n) + 1).putLong("srv_failat_" + n, System.currentTimeMillis());
        }
        e.apply();
    }

    /** Failures in a row, forgotten after six hours so a server that was down gets another chance. */
    private static int recentFails(String name) {
        if (System.currentTimeMillis() - prefs.getLong("srv_failat_" + name, 0) > FAIL_MEMORY_MS) return 0;
        return prefs.getInt("srv_fail_" + name, 0);
    }

    /**
     * The servers in the order to try them now: the pinned one first if there is one, then those
     * that have not been failing, quickest to start first; ones never tried keep their list order
     * after the proven ones.
     */
    public static List<Integer> serverOrder() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < SERVERS.size(); i++) out.add(i);
        if (prefs == null) return out;
        final int pin = pinnedServer();
        final long[] rank = new long[out.size()];
        for (int i = 0; i < rank.length; i++) {
            String n = SERVERS.get(i).name;
            long ms = prefs.getLong("srv_ms_" + n, 0);
            rank[i] = i == pin ? -1 : recentFails(n) * 1_000_000L + (ms > 0 ? Math.min(ms, 90_000) : 100_000 + i);
        }
        Collections.sort(out, (a, b) -> Long.compare(rank[a], rank[b]));
        return out;
    }

    /** "1:02:03" or "2:03". */
    public static String time(long ms) {
        long s = Math.max(ms, 0) / 1000;
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- http helpers

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
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new java.io.IOException("The catalogue answered with error " + code + ".");
        }
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

    /** A file in the app's cache folder. */
    public static File cacheFile(String name) {
        return new File(app.getCacheDir(), name);
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
