package com.dpi.switcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        StateManager state = new StateManager(newBase);
        int pd = state.getPhysicalDensity();
        if (pd > 0) {
            android.content.res.Configuration newConfig = new android.content.res.Configuration(newBase.getResources().getConfiguration());
            newConfig.densityDpi = pd;
            newConfig.fontScale = 1.0f;
            super.attachBaseContext(newBase.createConfigurationContext(newConfig));
        } else {
            super.attachBaseContext(newBase);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        RelativeLayout root = new RelativeLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#050508"));
        

        LinearLayout layout = new LinearLayout(this);
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        layout.setLayoutParams(layoutParams);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_app); 
        logo.setClipToOutline(true);
        logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(120), dp(120));
        layout.addView(logo, logoParams);
        
        TextView appName = new TextView(this);
        appName.setText(getString(R.string.app_name));
        appName.setTextColor(Color.WHITE);
        appName.setTextSize(28f);
        appName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        appName.setPadding(0, dp(16), 0, 0);
        appName.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(appName);
        
        TextView devText = new TextView(this);
        devText.setText("DEVELOPED BY UNIQUE APPS");
        devText.setTextColor(Color.parseColor("#BB86FC"));
        devText.setTextSize(14f);
        devText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        devText.setPadding(0, dp(8), 0, 0);
        devText.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(devText);
        
        root.addView(layout);
        setContentView(root);
        
        // Intro Animations
        layout.setAlpha(0f);
        layout.setTranslationY(dp(30));
        layout.setScaleX(0.9f);
        layout.setScaleY(0.9f);
        layout.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .setInterpolator(new DecelerateInterpolator())
            .start();
        
        // Delay 2.5 seconds then open MainActivity
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 2500); 
    }
    
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
