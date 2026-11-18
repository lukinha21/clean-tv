package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process. */
public class App extends Application {
    static String applicationId = "io.freemytv";

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        applicationId = base.getPackageName();
    }

    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Place.init(this);
    }
}
