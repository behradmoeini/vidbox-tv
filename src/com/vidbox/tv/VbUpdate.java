package com.vidbox.tv;

import android.app.Activity;
import android.app.AlertDialog;
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
 * Self-update mechanism for Vidbox TV.
 * Checks for a new release manifest, prompts the user, downloads the APK,
 * and hands it to the system package installer.
 */
public final class VbUpdate {
    public static String MANIFEST_URL = "https://raw.githubusercontent.com/behradmoeini/vidbox-tv-releases/main/version.json";
    private static final String STATUS = "com.vidbox.tv.INSTALL_STATUS";
    private static final long EVERY_MS = 10 * 60_000L;
    private static final long SNOOZE_MS = 60 * 60_000L;
    private static long lastCheck, snoozedUntil;
    private static AlertDialog prompt;

    private VbUpdate() {}

    public static void check(final Activity a) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < EVERY_MS || now < snoozedUntil) return;
        if (prompt != null && prompt.isShowing()) return;
        lastCheck = now;

        final long myCode;
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            if ("dev".equals(pi.versionName)) return; // skip for local dev builds
            myCode = pi.versionCode;
        } catch (Exception e) {
            return;
        }

        Vb.IO.execute(() -> {
            try {
                String json = Vb.httpGetCached(MANIFEST_URL, 60_000L);
                JSONObject m = new JSONObject(json);
                final String url = m.optString("apk_url");
                long remoteCode = m.optLong("version_code", 0);
                String remoteName = m.optString("version_name", "latest");

                if (remoteCode > myCode && !url.isEmpty()) {
                    Vb.MAIN.post(() -> {
                        if (a.isFinishing() || a.isDestroyed()) return;
                        if (prompt != null && prompt.isShowing()) return;
                        prompt = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Update Available")
                                .setMessage("A new version of Vidbox TV (" + remoteName + ") is available. Install now?")
                                .setPositiveButton("Update", (d, w) -> download(a, url))
                                .setNegativeButton("Later", (d, w) -> snoozedUntil = System.currentTimeMillis() + SNOOZE_MS)
                                .show();
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private static void download(final Activity a, final String url) {
        final AlertDialog progress = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Downloading Update")
                .setMessage("0%")
                .setCancelable(false)
                .show();

        Vb.IO.execute(() -> {
            try {
                File dir = new File(a.getCacheDir(), "update");
                dir.mkdirs();
                File out = new File(dir, "update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(30_000);
                c.setRequestProperty("User-Agent", Vb.UA);
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    long total = c.getContentLength();
                    long done = 0;
                    int lastPct = -1;
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            Vb.MAIN.post(() -> progress.setMessage(pct < 0 ? "..." : pct + "%"));
                        }
                    }
                } finally {
                    c.disconnect();
                }

                install(a, out);
                Vb.MAIN.post(() -> {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                Vb.MAIN.post(() -> {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                    Toast.makeText(a, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void install(Activity a, File apk) throws Exception {
        PackageInstaller installer = a.getPackageManager().getPackageInstaller();
        int id = installer.createSession(
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
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
            String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(a, "Update failed" + (why == null ? "" : ": " + why), Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
