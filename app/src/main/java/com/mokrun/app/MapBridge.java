package com.mokrun.app;

import android.webkit.JavascriptInterface;

/**
 * JS bridge for the map page. Top level class to avoid the d8 nested-class bug.
 */
public class MapBridge {

    private final MapActivity host;

    public MapBridge(MapActivity host) {
        this.host = host;
    }

    @JavascriptInterface
    public void saveRoute(String json) {
        host.onRouteJson(json);
    }

    @JavascriptInterface
    public void onStats(int points, double meters) {
        host.onStats(points, meters);
    }
}
