package pt.xsoulp.domotica;

import android.app.Application;

public final class App extends Application {
    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    static String text(int resourceId) {
        return instance.getString(resourceId);
    }
}
