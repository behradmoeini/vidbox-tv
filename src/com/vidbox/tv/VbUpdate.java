package com.vidbox.tv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update, with nothing for the viewer to do. Each time the main screen comes to the front the
 * app reads the small manifest CI publishes ({@code version.json} on main); when it names a higher
 * versionCode the APK is downloaded and installed straight away.
 *
 * Android installs it without asking when this app is its own installer of record and holds
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION (Android 12+). The first copy is sideloaded with
 * {@code adb install -i com.vidbox.tv}, so that holds from the start; where it does not, the system
 * shows its own confirmation once, and after that update this app is the installer of record.
 * Replacing the app closes it, so the viewer is told and {@link VbUpdated} opens it again.
 */
public final class VbUpdate {
    public static String MANIFEST_URL = "https://raw.githubusercontent.com/behradmoeini/vidbox-tv/main/version.json";
    private static final String STATUS = "com.vidbox.tv.INSTALL_STATUS";
    private static final long EVERY_MS = 3 * 60_000L;    // at most one manifest read per 3 minutes
    private static final long SNOOZE_MS = 60 * 60_000L;  // after a failed download or install
    private static long lastCheck, snoozedUntil;
    private static boolean busy;

    private VbUpdate() {}

    public static void check(final Activity a) {
        // Called every time the main screen comes to the front. The app's process usually outlives
        // the screen (Back does not kill it), so checking once per process would almost never run.
        long now = System.currentTimeMillis();
        if (busy || now - lastCheck < EVERY_MS || now < snoozedUntil) return;
        lastCheck = now;
        final long mine;
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            if ("dev".equals(pi.versionName)) return; // local build: a release could not replace it anyway
            mine = pi.versionCode;
        } catch (Exception e) {
            return;
        }
        busy = true;
        Vb.async(() -> {
            JSONObject m = new JSONObject(Vb.httpGetCached(MANIFEST_URL, 60_000L));
            String url = m.optString("apk_url");
            if (m.optLong("version_code", 0) <= mine || url.isEmpty()) return null;
            final String name = m.optString("version_name", "");
            Vb.MAIN.post(() -> Toast.makeText(a, "Getting Vidbox " + name + "…", Toast.LENGTH_LONG).show());
            return download(a, url);
        }, (apk, err) -> {
            busy = false;
            if (err != null) {
                snoozedUntil = System.currentTimeMillis() + SNOOZE_MS;
                return;
            }
            // Only while the main screen is still in front: never in the middle of a film.
            if (apk == null || a.isFinishing() || a.isDestroyed() || !a.hasWindowFocus()) {
                if (apk != null) lastCheck = 0; // ask again the next time the main screen opens
                return;
            }
            try {
                Toast.makeText(a, "Updating Vidbox. It closes for a moment and opens again.", Toast.LENGTH_LONG).show();
                install(a, apk);
            } catch (Exception e) {
                snoozedUntil = System.currentTimeMillis() + SNOOZE_MS;
                Toast.makeText(a, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static File download(Activity a, String url) throws Exception {
        File dir = new File(a.getCacheDir(), "update");
        dir.mkdirs();
        File out = new File(dir, "update.apk");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", Vb.UA);
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static void install(Activity a, File apk) throws Exception {
        PackageInstaller installer = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session s = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            }
            Intent back = new Intent(a, a.getClass()).setAction(STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getActivity(a, id, back, flags).getIntentSender());
        }
    }

    public static boolean onStatus(Activity a, Intent i) {
        if (i == null || !STATUS.equals(i.getAction())) return false;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    a.startActivity(confirm);
                } catch (Exception e) {
                    Toast.makeText(a, "Could not launch installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            snoozedUntil = System.currentTimeMillis() + SNOOZE_MS;
            String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(a, "Update failed" + (why == null ? "" : ": " + why), Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
