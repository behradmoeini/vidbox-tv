package com.vidbox.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs when an update has replaced the app (which closed it): opens the app again. */
public class VbUpdated extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        try {
            c.startActivity(new Intent(c, VbBrowseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // The system may refuse a start from the background; the viewer then opens it from the home screen.
        }
    }
}
