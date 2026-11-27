package com.vidbox.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subtitles. The stream servers' own subtitle menus are made for a mouse and are hidden with the
 * rest of their pages, so the app brings its own: files from OpenSubtitles (through the public
 * Stremio add-on, which needs no key and serves them as UTF-8), found by IMDb id.
 *
 * A film has many subtitle files, timed for different copies of it, and a file for another copy
 * is seconds or minutes out. So a downloaded file is never trusted on its name. The timing that
 * is known to be right is the stream's own: most servers carry subtitle tracks for the very file
 * they play (usually English, rarely Persian). Those are shown as they are, and they are the
 * yardstick for everything else: a downloaded file is used only if its lines start at the same
 * moments as the stream's own lines, up to one shift (and, for files made for 25 frames a second,
 * one stretch), which {@link #align} measures. A file that does not fit is skipped for the next.
 * Only when a server carries no track at all is a downloaded file shown unchecked, and it is
 * marked as such.
 */
final class VbSubs {
    private static final String API = "https://opensubtitles-v3.strem.io/subtitles/";
    static final String EN = "en", FA = "fa";
    /** No confident answer from {@link #align}. */
    static final int NO_MATCH = Integer.MIN_VALUE;

    private VbSubs() {}

    /** One line on screen: from {@code start} to {@code end}, in milliseconds. */
    static final class Cue {
        int start, end;
        String text;
    }

    /** One subtitle file on offer. */
    static final class File {
        String url, name;
        int score;
    }

    /** A file that has been read. {@code index} is its place in the list it came from. */
    static final class Track {
        List<Cue> cues;
        String name;
        int index, count;
        /** Time on the film = time in the file x {@code scale} + {@code shift} ms. */
        int shift;
        double scale = 1;
        /** The stream's own track. */
        boolean own;
        /** Its timing is known to be right: the stream's own, or measured against the stream's own. */
        boolean checked;
        /** Translated by machine from another track. */
        boolean machine;
    }

    /** A track read from the page's video element: {@code [[start,end,"text"],...]} in milliseconds. */
    static Track fromPage(JSONArray arr) {
        Track t = new Track();
        t.cues = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray c = arr.optJSONArray(i);
            if (c == null) continue;
            Cue cue = new Cue();
            cue.start = c.optInt(0);
            cue.end = c.optInt(1);
            cue.text = TAGS.matcher(c.optString(2)).replaceAll("").trim();
            if (cue.text.length() > 0 && cue.end > cue.start) t.cues.add(cue);
        }
        Collections.sort(t.cues, (a, b) -> a.start - b.start);
        t.name = "the stream's own";
        t.own = true;
        t.checked = true;
        return t.cues.size() < 5 ? null : t;
    }

    // ---------------------------------------------------------------- finding files

    /** The files for one film or episode in one language, most promising first. */
    static List<File> list(String imdb, boolean series, int season, int episode, String lang) throws Exception {
        String url = API + (series ? "series/" + imdb + ":" + season + ":" + episode : "movie/" + imdb) + ".json";
        JSONArray arr = new JSONObject(Vb.httpGetCached(url, 6 * 3600_000L)).optJSONArray("subtitles");
        List<File> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String l = o.optString("lang");
            boolean want = EN.equals(lang) ? "eng".equals(l) : ("per".equals(l) || "fas".equals(l) || "fa".equals(l));
            if (!want || o.optString("url").length() == 0) continue;
            File f = new File();
            f.url = o.getString("url");
            f.name = o.optString("movieReleaseName", o.optString("subtitleFileName", ""));
            if (f.name.length() == 0) f.name = o.optString("subtitleFileName", "");
            f.score = score((f.name + " " + o.optString("subtitleFileName") + " " + o.optString("releaseFormat")),
                    o.optInt("fpsMilli"));
            out.add(f);
        }
        // Stable: files the site lists first win a tie.
        Collections.sort(out, (a, b) -> b.score - a.score);
        return out;
    }

    private static final Pattern CINEMA = Pattern.compile("(^|[^a-z])(cam|camrip|hdcam|telesync|hdts|ts|tc|telecine|screener|scr)([^a-z]|$)");
    private static final Pattern PARTS = Pattern.compile("(^|[^a-z0-9])cd ?[12]([^0-9]|$)");
    private static final Pattern WEB = Pattern.compile("(^|[^a-z])(web|webrip|web-dl|webdl|amzn|nf|hmax|dsnp|atvp|hulu)([^a-z]|$)");
    private static final Pattern DISC = Pattern.compile("(^|[^a-z])(bluray|blu-ray|brrip|bdrip|bdremux|remux|hdrip)([^a-z]|$)");

    /** How likely a file is to be timed like the copy the servers stream (which is a web or disc copy). */
    private static int score(String name, int fpsMilli) {
        String n = name.toLowerCase(Locale.ROOT);
        int s = 0;
        if (CINEMA.matcher(n).find()) s -= 60;   // recorded in a cinema: different cut, different start
        if (PARTS.matcher(n).find()) s -= 50;    // half of a film split over two discs
        if (WEB.matcher(n).find()) s += 30;
        if (DISC.matcher(n).find()) s += 20;
        if (n.contains("1080p") || n.contains("720p") || n.contains("2160p")) s += 5;
        if (n.contains("hdtv")) s += 5;
        // The streams run at film speed; a file timed for 25 frames a second drifts further and further.
        if (fpsMilli >= 23900 && fpsMilli <= 24100) s += 10;
        else if (fpsMilli >= 24900 && fpsMilli <= 25100) s -= 15;
        return s;
    }

    // ---------------------------------------------------------------- reading a file

    private static final Pattern TIME = Pattern.compile(
            "(\\d+):(\\d\\d):(\\d\\d)[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d\\d):(\\d\\d)[,.](\\d{1,3})");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>|\\{\\\\[^}]*\\}");
    private static final Pattern CREDIT = Pattern.compile(
            "(?i)opensubtitles|osdb\\.link|subtitles? by|www\\.|\\.com|\\.ir\\b|t\\.me/|@[a-z0-9_]{4,}");

    /** The lines of an SRT (or WebVTT) file, in order. Lines that advertise the uploader are dropped. */
    static List<Cue> parse(String text) {
        List<Cue> out = new ArrayList<>();
        String[] lines = text.replace("\r", "").replace("﻿", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIME.matcher(lines[i]);
            if (!m.find()) continue;
            Cue c = new Cue();
            c.start = ms(m, 1);
            c.end = ms(m, 5);
            StringBuilder sb = new StringBuilder();
            while (i + 1 < lines.length && lines[i + 1].trim().length() > 0 && !TIME.matcher(lines[i + 1]).find()) {
                String l = TAGS.matcher(lines[++i]).replaceAll("").trim();
                if (l.length() == 0) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(l);
            }
            c.text = sb.toString();
            if (c.text.length() == 0 || c.end <= c.start || CREDIT.matcher(c.text).find()) continue;
            out.add(c);
        }
        Collections.sort(out, (a, b) -> a.start - b.start);
        return out;
    }

    private static int ms(Matcher m, int g) {
        String frac = (m.group(g + 3) + "00").substring(0, 3);
        return ((Integer.parseInt(m.group(g)) * 60 + Integer.parseInt(m.group(g + 1))) * 60
                + Integer.parseInt(m.group(g + 2))) * 1000 + Integer.parseInt(frac);
    }

    /**
     * Read the best file, starting at {@code from}.
     *
     * With {@code ref} (lines whose timing is right), a file is taken only if it lines up with
     * them; its shift and stretch are then set and it is {@code checked} when {@code refChecked}.
     * If {@code mustAlign} and no file lines up, the answer is null: better no subtitles than
     * wrong ones. Without {@code ref}, or when lining up is not required, the first file that fits
     * a film {@code durationMs} long is returned (its last line must fall in the film's last
     * stretch: a file for a longer cut runs past the end, half of a two-disc copy stops half-way).
     */
    static Track pick(List<File> files, int from, long durationMs, List<Cue> ref, boolean refChecked, boolean mustAlign) {
        Track fits = null, any = null;
        int n = files.size();
        for (int k = 0; k < Math.min(n, 10); k++) {
            int i = (from + k) % n;
            try {
                List<Cue> cues = parse(Vb.httpGetCached(files.get(i).url, 30L * 24 * 3600_000L));
                if (cues.size() < 20) continue;
                Track t = new Track();
                t.cues = cues;
                t.name = files.get(i).name;
                t.index = i;
                t.count = n;
                if (any == null) any = t;
                if (ref != null) {
                    double[] fit = align(ref, cues);
                    if (fit != null) {
                        t.shift = (int) fit[0];
                        t.scale = fit[1];
                        t.checked = refChecked;
                        return t;
                    }
                    if (mustAlign) continue;
                }
                long last = cues.get(cues.size() - 1).end;
                if (durationMs > 0 && (last > durationMs + 60_000 || last < durationMs * 0.70)) continue;
                if (ref == null) return t;
                if (fits == null) fits = t;
            } catch (Exception ignored) {
            }
        }
        if (mustAlign) return null;
        return fits != null ? fits : any;
    }

    // ---------------------------------------------------------------- lining two files up

    /** Film speed against the 25 frames a second of European television, both ways. */
    private static final double[] STRETCH = {1.0, 25.0 / 23.976, 23.976 / 25.0};

    /**
     * How {@code b} has to be moved to run with {@code a}: {@code {shift ms, stretch}}, meaning
     * time in a = time in b x stretch + shift; or null if they do not belong together.
     *
     * People speak at the same moments whatever the language, so two files for the same cut have
     * their lines starting together, up to one constant shift. Every pair of nearby lines votes
     * for the shift it would need; the right one collects far more votes than any other. It has
     * to be a clear winner (at least a third of the lines, and well ahead of the runner-up), so
     * a file for another cut, where no single shift works, is refused.
     */
    static double[] align(List<Cue> a, List<Cue> b) {
        if (a == null || b == null || a.size() < 30 || b.size() < 30) return null;
        double[] best = null;
        int bestVotes = 0;
        for (double k : STRETCH) {
            int[] r = vote(a, b, k);
            if (r != null && r[1] > bestVotes) {
                bestVotes = r[1];
                best = new double[]{r[0], k};
            }
        }
        return best;
    }

    /** {@code {shift, votes}} for one stretch, or null if there is no clear winner. */
    private static int[] vote(List<Cue> a, List<Cue> b, double k) {
        final int range = 180_000, bin = 200, bins = 2 * range / bin + 1;
        int[] votes = new int[bins];
        int step = Math.max(1, b.size() / 600), used = 0, lo = 0;
        for (int j = 0; j < b.size(); j += step) {
            int t = (int) (b.get(j).start * k);
            while (lo < a.size() && a.get(lo).start < t - range) lo++;
            for (int i = lo; i < a.size() && a.get(i).start <= t + range; i++) {
                votes[(a.get(i).start - t + range) / bin]++;
            }
            used++;
        }
        int best = 0;
        for (int i = 1; i < bins; i++) if (votes[i] > votes[best]) best = i;
        int top = votes[best] + (best > 0 ? votes[best - 1] : 0) + (best + 1 < bins ? votes[best + 1] : 0);
        int second = 0;
        for (int i = 0; i < bins; i++) {
            if (Math.abs(i - best) <= 3) continue;
            int v = votes[i] + (i > 0 ? votes[i - 1] : 0) + (i + 1 < bins ? votes[i + 1] : 0);
            if (v > second) second = v;
        }
        if (top < used / 3 || top < second * 1.5) return null;
        // Refine inside the winning bins: the middle of the shifts that voted there.
        List<Integer> diffs = new ArrayList<>();
        int centre = best * bin - range;
        lo = 0;
        for (int j = 0; j < b.size(); j += step) {
            int t = (int) (b.get(j).start * k);
            while (lo < a.size() && a.get(lo).start < t + centre - 2 * bin) lo++;
            for (int i = lo; i < a.size() && a.get(i).start <= t + centre + 2 * bin; i++) diffs.add(a.get(i).start - t);
        }
        if (diffs.isEmpty()) return new int[]{centre + bin / 2, top};
        Collections.sort(diffs);
        return new int[]{diffs.get(diffs.size() / 2), top};
    }

    private static final Pattern SOUND = Pattern.compile("^\\s*([\\[(][^\\])]*[\\])]\\s*)+$|^\\s*[♪♫].*[♪♫]\\s*$", Pattern.DOTALL);

    // ---------------------------------------------------------------- machine translation

    private static final String TRANSLATE = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=fa&dt=t";

    /**
     * Persian made by machine from {@code en}, line for line, so it carries {@code en}'s timing
     * exactly. For when no Persian file fits the copy being played: a plain translation in time
     * is worth more than a good one that is seconds out. Kept on disk, so a film is translated once.
     */
    static Track translate(Track en) throws Exception {
        List<Cue> src = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (Cue c : en.cues) {
            if (SOUND.matcher(c.text).matches()) continue;
            src.add(c);
            sig.append(c.text).append('|');
        }
        if (src.isEmpty()) return null;
        java.io.File file = Vb.cacheFile("vbtr_" + Vb.md5(sig.toString()));
        String[] fa = null;
        if (file.exists()) {
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                JSONArray saved = new JSONArray(new String(Vb.readAll(in), "UTF-8"));
                if (saved.length() == src.size()) {
                    fa = new String[saved.length()];
                    for (int i = 0; i < fa.length; i++) fa[i] = saved.getString(i);
                }
            } catch (Exception ignored) {
            }
        }
        if (fa == null) {
            fa = new String[src.size()];
            // Several lines a request, one line each; the service returns them one line each.
            int from = 0;
            while (from < src.size()) {
                int to = from, chars = 0;
                while (to < src.size() && to - from < 60 && chars + src.get(to).text.length() < 2800) chars += src.get(to++).text.length() + 1;
                if (to == from) to = from + 1;
                translate(src, from, to, fa);
                from = to;
                Thread.sleep(120);
            }
            JSONArray save = new JSONArray();
            for (String line : fa) save.put(line);
            try (java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                out.write(save.toString().getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
        }
        Track t = new Track();
        t.cues = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) {
            Cue c = new Cue();
            c.start = src.get(i).start;
            c.end = src.get(i).end;
            c.text = fa[i];
            if (c.text != null && c.text.length() > 0) t.cues.add(c);
        }
        t.name = "machine translation";
        t.shift = en.shift;
        t.scale = en.scale;
        t.checked = en.checked;
        t.machine = true;
        return t;
    }

    /** Translate lines [from, to). If the answer does not come back line for line, halve and try again. */
    private static void translate(List<Cue> src, int from, int to, String[] out) throws Exception {
        StringBuilder q = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) q.append('\n');
            q.append(src.get(i).text.replace('\n', ' '));
        }
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(TRANSLATE).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", Vb.UA);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        String body;
        try {
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(("q=" + java.net.URLEncoder.encode(q.toString(), "UTF-8")).getBytes("UTF-8"));
            }
            if (c.getResponseCode() != 200) throw new java.io.IOException("The translation service answered " + c.getResponseCode() + ".");
            try (java.io.InputStream in = c.getInputStream()) {
                body = new String(Vb.readAll(in), "UTF-8");
            }
        } finally {
            c.disconnect();
        }
        JSONArray parts = new JSONArray(body).getJSONArray(0);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) all.append(parts.getJSONArray(i).optString(0));
        String[] lines = all.toString().split("\n", -1);
        if (lines.length == to - from) {
            for (int i = from; i < to; i++) out[i] = lines[i - from].trim();
        } else if (to - from > 1) {
            int mid = (from + to) / 2;
            translate(src, from, mid, out);
            translate(src, mid, to, out);
        } else {
            out[from] = all.toString().replace('\n', ' ').trim();
        }
    }

    /**
     * The lines as a JavaScript array {@code [[start,end,"text"],...]} in film time. Lines that
     * only describe a sound ("[DOOR SLAMS]") are left out: they are for the hard of hearing.
     */
    static String js(Track t) {
        JSONArray arr = new JSONArray();
        if (t != null) {
            for (Cue c : t.cues) {
                if (SOUND.matcher(c.text).matches()) continue;
                arr.put(new JSONArray().put((int) (c.start * t.scale) + t.shift).put((int) (c.end * t.scale) + t.shift).put(c.text));
            }
        }
        return arr.toString();
    }
}
