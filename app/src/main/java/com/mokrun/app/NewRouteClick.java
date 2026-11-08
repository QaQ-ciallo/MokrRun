package com.mokrun.app;

import android.content.Context;
import android.content.Intent;
import android.view.View;

public class NewRouteClick implements View.OnClickListener {

    @Override
    public void onClick(View v) {
        Context ctx = v.getContext();
        ctx.startActivity(new Intent(ctx, MapActivity.class));
    }
}
