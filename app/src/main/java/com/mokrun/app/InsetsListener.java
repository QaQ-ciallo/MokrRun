package com.mokrun.app;

import android.view.View;
import android.view.WindowInsets;

/**
 * Top level class on purpose: d8 8.2 crashes on nested/anonymous classes
 * that carry an EnclosingMethod attribute.
 */
public class InsetsListener implements View.OnApplyWindowInsetsListener {

    private final boolean bottom;

    public InsetsListener() {
        this(false);
    }

    public InsetsListener(boolean bottom) {
        this.bottom = bottom;
    }

    @Override
    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
        if (bottom) {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
        } else {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
        }
        return insets;
    }
}
