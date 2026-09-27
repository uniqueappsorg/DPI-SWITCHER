package com.dpi.switcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String TAG = "DpiSwitcher/Main";

    private StateManager stateManager;

    // Root structure
    private android.widget.FrameLayout rootFrame;    // full-screen frame
    private LinearLayout contentArea;               // scrollable content above nav bar
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout rootLayout;                // inner vertical layout inside scroll
    private LinearLayout bottomNav;                 // fixed bottom nav bar

    // Tab state
    private int currentTab = 0; // 0=Home, 1=Presets, 2=About
    private Button[] navBtns = new Button[3];

    // UI references
    private TextView dpiValueDisplay;
    private View iconOverlay;
    private DpiController.DeviceInfo cachedInfo;

    private final Shizuku.OnRequestPermissionResultListener shizukuListener = (requestCode, grantResult) -> {
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stateManager.setShizukuGranted(true);
            loadDeviceInfo();
        } else {
            showInAppToast("Shizuku permission denied", true);
        }
    };

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

    private android.os.CountDownTimer safetyTimer;
    private android.app.Dialog safetyDialog;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            String stackTrace = android.util.Log.getStackTraceString(e);
            getSharedPreferences("DpiSwitcherPrefs", Context.MODE_PRIVATE).edit().putString("crash_log", stackTrace).commit();
            System.exit(1);
        });

        stateManager = new StateManager(this);

        String lastCrash = getSharedPreferences("DpiSwitcherPrefs", Context.MODE_PRIVATE).getString("crash_log", null);
        if (lastCrash != null) {
            getSharedPreferences("DpiSwitcherPrefs", Context.MODE_PRIVATE).edit().remove("crash_log").commit();
            new AlertDialog.Builder(this)
                .setTitle("Previous Crash Log")
                .setMessage(lastCrash)
                .setPositiveButton("OK", null)
                .show();
        }

        try { Shizuku.addRequestPermissionResultListener(shizukuListener); } catch (Throwable ignored) {}

        // Full-screen FrameLayout
        rootFrame = new android.widget.FrameLayout(this);
        rootFrame.setBackgroundColor(getColor(R.color.bg_dark));

        // Inner content layout (scrollable area)
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(20), dp(44), dp(20), dp(8));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(rootLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        swipeRefresh = new SwipeRefreshLayout(this);
        swipeRefresh.addView(scrollView);
        swipeRefresh.setOnRefreshListener(this::refreshCurrentDpi);

        // Place swipeRefresh above nav bar
        android.widget.FrameLayout.LayoutParams contentLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.bottomMargin = dp(64 + 48); // default fallback
        rootFrame.addView(swipeRefresh, contentLp);

        // Bottom Nav Bar
        bottomNav = buildBottomNav();
        android.widget.FrameLayout.LayoutParams navLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        rootFrame.addView(bottomNav, navLp);

        setContentView(rootFrame);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        rootFrame.setOnApplyWindowInsetsListener((v, insets) -> {
            int navHeight = insets.getSystemWindowInsetBottom();
            int statusHeight = insets.getSystemWindowInsetTop();

            rootLayout.setPadding(dp(20), statusHeight + dp(16), dp(20), dp(8));

            int totalBottomMargin = navHeight + dp(16);
            contentLp.bottomMargin = dp(64) + totalBottomMargin;
            swipeRefresh.setLayoutParams(contentLp);

            bottomNav.setPadding(dp(20), 0, dp(20), totalBottomMargin);
            return insets;
        });

        checkRootAndInitialize();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener); } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (iconOverlay != null && iconOverlay.getParent() != null) {
            closeIconSettingsOverlay();
            return;
        }
        if (currentTab != 0) {
            switchTab(0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrentDpi();
    }

    private void refreshCurrentDpi() {
        if (stateManager.isRootGranted() || stateManager.isShizukuGranted()) {
            DpiController.getCurrentDpi(dpi -> {
                if (dpiValueDisplay != null) {
                    dpiValueDisplay.setText(dpi > 0 ? String.valueOf(dpi) : "ERR");
                    if (dpi > 0) {
                        stateManager.setCurrentDpi(dpi);
                        if (stateManager.getOriginalDpi() == 0) stateManager.setOriginalDpi(dpi);
                    }
                }
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            });
        } else {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BOTTOM NAV
    // ─────────────────────────────────────────────────────────────────────────

    private LinearLayout buildBottomNav() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.BOTTOM);
        outer.setPadding(dp(20), 0, dp(20), dp(28)); // overridden by insets

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        android.graphics.drawable.GradientDrawable navBg = new android.graphics.drawable.GradientDrawable();
        navBg.setColor(Color.parseColor("#E00B0A18")); // deep dark
        navBg.setCornerRadius(dp(28));
        navBg.setStroke(dp(1), Color.parseColor("#28A855F7")); // faint purple outline on bar
        nav.setBackground(navBg);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setElevation(dp(10));
        nav.setPadding(dp(6), dp(2), dp(6), dp(2));

        String[] labels = {"Home", "Presets", "About"};
        String[] emojis = {"⚡", "🎛️", "ℹ️"};

        for (int i = 0; i < 3; i++) {
            final int tab = i;
            LinearLayout tabBtn = new LinearLayout(this);
            tabBtn.setOrientation(LinearLayout.VERTICAL);
            tabBtn.setGravity(Gravity.CENTER);
            tabBtn.setPadding(dp(6), dp(6), dp(6), dp(6));

            // Emoji icon
            TextView icon = new TextView(this);
            icon.setText(emojis[i]);
            icon.setTextSize(18f);
            icon.setGravity(Gravity.CENTER);
            icon.setAlpha(i == 0 ? 1.0f : 0.4f);
            tabBtn.addView(icon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Label
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(i == 0 ? getColor(R.color.accent_purple) : Color.parseColor("#55557A"));
            label.setTextSize(9f);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(2), 0, 0);
            tabBtn.addView(label);

            // Active tab gets outline + tint; inactive gets nothing
            if (i == 0) {
                android.graphics.drawable.GradientDrawable activeBg = new android.graphics.drawable.GradientDrawable();
                activeBg.setColor(Color.parseColor("#18A855F7"));
                activeBg.setCornerRadius(dp(18));
                activeBg.setStroke(dp(1), Color.parseColor("#66A855F7"));
                tabBtn.setBackground(activeBg);
            }

            // Store refs: [icon, label]
            tabBtn.setTag(new View[]{icon, label});
            tabBtn.setOnClickListener(v -> switchTab(tab));

            nav.addView(tabBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            navBtns[i] = new Button(this);
            navBtns[i].setTag(tabBtn);
        }

        outer.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private android.graphics.drawable.Drawable createNavBg() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#0D0D0D"));
        return bg;
    }

    private void switchTab(int tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        updateNavHighlight();
        if (cachedInfo != null) {
            renderCurrentTab();
        }
    }

    private void updateNavHighlight() {
        for (int i = 0; i < 3; i++) {
            if (navBtns[i] == null) continue;
            LinearLayout tabBtn = (LinearLayout) navBtns[i].getTag();
            if (tabBtn == null) continue;
            View[] views = (View[]) tabBtn.getTag();
            if (views == null || views.length < 2) continue;

            TextView icon  = (TextView) views[0];
            TextView label = (TextView) views[1];

            boolean active = (i == currentTab);

            // Icon opacity
            icon.setAlpha(active ? 1.0f : 0.4f);

            // Label color
            label.setTextColor(active ? getColor(R.color.accent_purple) : Color.parseColor("#55557A"));

            // Active = outline + tint; Inactive = no background
            if (active) {
                android.graphics.drawable.GradientDrawable activeBg = new android.graphics.drawable.GradientDrawable();
                activeBg.setColor(Color.parseColor("#18A855F7"));
                activeBg.setCornerRadius(dp(18));
                activeBg.setStroke(dp(1), Color.parseColor("#66A855F7"));
                tabBtn.setBackground(activeBg);
            } else {
                tabBtn.setBackground(null);
            }
        }
    }

    private void renderCurrentTab() {
        rootLayout.removeAllViews();
        dpiValueDisplay = null;

        switch (currentTab) {
            case 0: showHomeTab(); break;
            case 1: showPresetsTab(); break;
            case 2: showAboutTab(); break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PERMISSION / INIT
    // ─────────────────────────────────────────────────────────────────────────

    private void checkRootAndInitialize() {
        rootLayout.removeAllViews();
        addTitle("DPI Switcher");

        TextView checking = new TextView(this);
        checking.setText("Checking permissions...");
        checking.setTextColor(getColor(R.color.text_secondary));
        checking.setTextSize(14f);
        checking.setPadding(0, dp(12), 0, 0);
        rootLayout.addView(checking);

        boolean hasShizuku = false;
        try {
            hasShizuku = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}

        if (stateManager.isRootGranted() || hasShizuku) {
            if (hasShizuku) stateManager.setShizukuGranted(true);
            loadDeviceInfo();
        } else {
            showPermissionUI();
        }
    }

    private void loadDeviceInfo() {
        // Request root if not Shizuku
        boolean hasShizuku = false;
        try {
            hasShizuku = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}

        if (!hasShizuku) {
            RootManager.requestRoot(granted -> {
                if (granted) {
                    stateManager.setRootGranted(true);
                    addQuickTileIfNeeded();
                    DpiController.getDeviceInfo(info -> {
                        if (info != null && info.physicalDensity > 0) {
                            if (stateManager.getPhysicalDensity() != info.physicalDensity) {
                                stateManager.setPhysicalDensity(info.physicalDensity);
                                recreate();
                                return;
                            }
                        }
                        cachedInfo = info;
                        currentTab = 0;
                        updateNavHighlight();
                        showMainUI(info);
                    });
                } else {
                    showPermissionUI();
                }
            });
        } else {
            addQuickTileIfNeeded();
            DpiController.getDeviceInfo(info -> {
                if (info != null && info.physicalDensity > 0) {
                    if (stateManager.getPhysicalDensity() != info.physicalDensity) {
                        stateManager.setPhysicalDensity(info.physicalDensity);
                        recreate();
                        return;
                    }
                }
                cachedInfo = info;
                currentTab = 0;
                updateNavHighlight();
                showMainUI(info);
            });
        }
    }

    private void addQuickTileIfNeeded() {
        String tileName = "com.dpi.switcher/.DpiTileService";
        // Use 'settings get secure sysui_qs_tiles' — gives clean comma-separated list, no headers
        RootManager.exec("settings get secure sysui_qs_tiles", result -> {
            if (result == null || result.stdout == null) return;
            String current = result.stdout.trim();
            // "null" means the key doesn't exist yet
            if (current.equalsIgnoreCase("null")) current = "";
            if (!current.contains(tileName)) {
                String newTiles = current.isEmpty() ? tileName : current + "," + tileName;
                RootManager.exec("settings put secure sysui_qs_tiles \"" + newTiles + "\"", r -> {});
            }
        });
    }

    private void showPermissionUI() {
        if (bottomNav != null) bottomNav.setVisibility(android.view.View.GONE);
        rootLayout.removeAllViews();
        addTitle("DPI Switcher");

        TextView desc = new TextView(this);
        desc.setText("This app requires system access to change display density safely. Please grant one of the following permissions:\n\n1. ROOT ACCESS: Recommended for advanced users with Magisk or KernelSU.\n\n2. SHIZUKU: Perfect for unrooted devices. Start the Shizuku app using Wireless Debugging, then click below to grant access.\n\n3. DISABLE BATTERY OPTIMIZATION: Required for the Quick Settings tile and safety timer to run flawlessly in the background.");
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setTextSize(14f);
        desc.setLineSpacing(0, 1.2f);
        desc.setPadding(0, dp(16), 0, dp(24));
        rootLayout.addView(desc);

        Button grantBtn = new Button(this);
        grantBtn.setText("Grant Root (Magisk/KSU)");
        grantBtn.setBackgroundResource(R.drawable.btn_primary);
        grantBtn.setTextColor(Color.WHITE);
        grantBtn.setOnClickListener(v -> {
            grantBtn.setText("Waiting...");
            grantBtn.setEnabled(false);
            RootManager.requestRoot(granted -> {
                if (granted) {
                    stateManager.setRootGranted(true);
                    loadDeviceInfo();
                } else {
                    grantBtn.setText("Grant Root (Magisk/KSU)");
                    grantBtn.setEnabled(true);
                    showInAppToast("Root access denied", true);
                }
            });
        });
        rootLayout.addView(grantBtn);

        Button shizukuBtn = new Button(this);
        shizukuBtn.setText("Grant Shizuku");
        shizukuBtn.setBackgroundResource(R.drawable.btn_secondary);
        shizukuBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams shizLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shizLp.topMargin = dp(16);
        shizukuBtn.setOnClickListener(v -> {
            try {
                if (!Shizuku.pingBinder()) {
                    showInAppToast("Shizuku app is not running!", true);
                    return;
                }
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    stateManager.setShizukuGranted(true);
                    loadDeviceInfo();
                } else {
                    Shizuku.requestPermission(1001);
                }
            } catch (Throwable e) {
                showInAppToast("Shizuku API not available", true);
            }
        });
        rootLayout.addView(shizukuBtn, shizLp);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Button batteryBtn = new Button(this);
                batteryBtn.setText("Disable Battery Optimization");
                batteryBtn.setBackgroundResource(R.drawable.btn_secondary);
                batteryBtn.setTextColor(getColor(R.color.error));
                batteryBtn.setOnClickListener(v -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        showInAppToast("Failed to open settings", true);
                    }
                });
                LinearLayout.LayoutParams batParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                batParams.topMargin = dp(16);
                rootLayout.addView(batteryBtn, batParams);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN UI dispatcher
    // ─────────────────────────────────────────────────────────────────────────

    private void showMainUI(DpiController.DeviceInfo info) {
        if (bottomNav != null) bottomNav.setVisibility(android.view.View.VISIBLE);
        cachedInfo = info;
        renderCurrentTab();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 1: HOME
    // ─────────────────────────────────────────────────────────────────────────

    private void showHomeTab() {
        addTitle("DPI Switcher");

        DpiController.DeviceInfo info = cachedInfo;

        boolean isShizuku = false;
        try {
            isShizuku = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}

        final boolean isShizukuFinal = isShizuku;

        // ── CURRENT DPI CARD ─────────────────────────────────────────
        LinearLayout dpiCard = new LinearLayout(this);
        dpiCard.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable dpiBg = new android.graphics.drawable.GradientDrawable();
        dpiBg.setColor(Color.parseColor("#12FFFFFF"));
        dpiBg.setCornerRadius(dp(20));
        dpiBg.setStroke(dp(1), Color.parseColor("#22FFFFFF"));
        dpiCard.setBackground(dpiBg);
        dpiCard.setPadding(dp(24), dp(24), dp(24), dp(20));

        LinearLayout dpiTopRow = new LinearLayout(this);
        dpiTopRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout dpiLeft = new LinearLayout(this);
        dpiLeft.setOrientation(LinearLayout.VERTICAL);

        TextView currentLabel = new TextView(this);
        currentLabel.setText("CURRENT DPI");
        currentLabel.setTextColor(getColor(R.color.text_secondary));
        currentLabel.setTextSize(11f);
        currentLabel.setLetterSpacing(0.15f);
        dpiLeft.addView(currentLabel);

        dpiValueDisplay = new TextView(this);
        dpiValueDisplay.setText("...");
        dpiValueDisplay.setTextColor(getColor(R.color.accent_purple));
        dpiValueDisplay.setTextSize(56f);
        dpiValueDisplay.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dpiLeft.addView(dpiValueDisplay);

        dpiTopRow.addView(dpiLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Right: Root Indicator
        LinearLayout rootIndicator = new LinearLayout(this);
        rootIndicator.setOrientation(LinearLayout.HORIZONTAL);
        rootIndicator.setGravity(Gravity.CENTER);
        rootIndicator.setPadding(dp(10), dp(6), dp(12), dp(6));
        android.graphics.drawable.GradientDrawable rootBg = new android.graphics.drawable.GradientDrawable();
        rootBg.setCornerRadius(dp(14));
        rootBg.setColor((isShizukuFinal || stateManager.isRootGranted()) ? Color.parseColor("#1544FF88") : Color.parseColor("#15FF5555"));
        rootBg.setStroke(dp(1), (isShizukuFinal || stateManager.isRootGranted()) ? Color.parseColor("#4444FF88") : Color.parseColor("#44FF5555"));
        rootIndicator.setBackground(rootBg);

        ImageView rootIcon = new ImageView(this);
        rootIcon.setImageResource(isShizukuFinal ? android.R.drawable.ic_menu_manage : android.R.drawable.ic_secure);
        rootIcon.setColorFilter((isShizukuFinal || stateManager.isRootGranted()) ? Color.parseColor("#44FF88") : Color.parseColor("#FF5555"));
        LinearLayout.LayoutParams riLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        riLp.rightMargin = dp(6);
        rootIndicator.addView(rootIcon, riLp);

        TextView rootStatus = new TextView(this);
        rootStatus.setText(isShizukuFinal ? "Shizuku" : (stateManager.isRootGranted() ? "Root Access" : "No Access"));
        rootStatus.setTextColor((isShizukuFinal || stateManager.isRootGranted()) ? Color.parseColor("#44FF88") : Color.parseColor("#FF5555"));
        rootStatus.setTextSize(11f);
        rootStatus.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        rootIndicator.addView(rootStatus);

        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootLp.gravity = Gravity.TOP;
        dpiTopRow.addView(rootIndicator, rootLp);

        dpiCard.addView(dpiTopRow);

        // Reset Button Horizontal
        LinearLayout resetBtn = new LinearLayout(this);
        resetBtn.setOrientation(LinearLayout.HORIZONTAL);
        resetBtn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable resetBg = new android.graphics.drawable.GradientDrawable();
        resetBg.setCornerRadius(dp(12));
        resetBg.setColor(Color.parseColor("#1AFF3333"));
        resetBg.setStroke(dp(1), Color.parseColor("#33FF3333"));
        resetBtn.setBackground(resetBg);
        resetBtn.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams rstLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rstLp.topMargin = dp(16);

        ImageView resetIcon = new ImageView(this);
        resetIcon.setImageResource(android.R.drawable.ic_popup_sync);
        resetIcon.setColorFilter(getColor(R.color.error));
        LinearLayout.LayoutParams ricLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        ricLp.rightMargin = dp(8);
        resetBtn.addView(resetIcon, ricLp);

        TextView resetText = new TextView(this);
        resetText.setText("Reset to Default");
        resetText.setTextColor(getColor(R.color.error));
        resetText.setTextSize(13f);
        resetText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        resetBtn.addView(resetText);

        resetBtn.setOnClickListener(v -> {
            dpiValueDisplay.setText("...");
            DpiController.resetDpi(result -> {
                if (result.success) {
                    dpiValueDisplay.setText(String.valueOf(result.dpi));
                    stateManager.setCurrentDpi(result.dpi);
                    showInAppToast("Reset successful", false);
                } else {
                    dpiValueDisplay.setText("ERR");
                    showInAppToast(result.error, false);
                }
            });
        });
        dpiCard.addView(resetBtn, rstLp);

        LinearLayout.LayoutParams dpiCardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dpiCardLp.bottomMargin = dp(14);
        rootLayout.addView(dpiCard, dpiCardLp);

        refreshCurrentDpi();

        // ── DEVICE SPEC CARD ─────────────────────────────────────────
        if (info != null) {
            float ar = Math.max(info.width, info.height) / (float) Math.min(info.width, info.height);
            String arStr = String.format("%.2f:1", ar);
            
            float rr = 60.0f;
            try {
                android.view.WindowManager wm = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    rr = wm.getDefaultDisplay().getRefreshRate();
                }
            } catch (Exception ignored) {}
            String rrStr = String.format("%.0f Hz", rr);

            LinearLayout specCard = new LinearLayout(this);
            specCard.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable specBg = new android.graphics.drawable.GradientDrawable();
            specBg.setColor(Color.parseColor("#0EFFFFFF"));
            specBg.setCornerRadius(dp(20));
            specBg.setStroke(dp(1), Color.parseColor("#1AFFFFFF"));
            specCard.setBackground(specBg);
            specCard.setPadding(dp(24), dp(20), dp(24), dp(20));

            TextView specHeader = new TextView(this);
            specHeader.setText("DEVICE INFORMATION");
            specHeader.setTextColor(getColor(R.color.text_secondary));
            specHeader.setTextSize(10f);
            specHeader.setLetterSpacing(0.15f);
            specHeader.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            shLp.bottomMargin = dp(16);
            specCard.addView(specHeader, shLp);

            // Row 1: Model | Resolution
            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r1Lp.bottomMargin = dp(16);

            LinearLayout modelCol = makeSpecCol("Device Model", info.model, Color.WHITE, Gravity.START);
            row1.addView(modelCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout resCol = makeSpecCol("Resolution", info.width + " × " + info.height, Color.WHITE, Gravity.END);
            row1.addView(resCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            specCard.addView(row1, r1Lp);

            // Divider
            View hDiv = new View(this);
            hDiv.setBackgroundColor(Color.parseColor("#15FFFFFF"));
            LinearLayout.LayoutParams hdLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            hdLp.bottomMargin = dp(16);
            specCard.addView(hDiv, hdLp);

            // Row 2: Default SW | Physical DPI | Max SW
            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r2Lp.bottomMargin = dp(16);

            LinearLayout defCol = makeSpecCol("Default SW", String.valueOf(info.defaultSw), getColor(R.color.accent_purple), Gravity.START);
            row2.addView(defCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String physStr = info.physicalDensity > 0 ? String.valueOf(info.physicalDensity) : "—";
            LinearLayout physCol = makeSpecColCenter("Physical DPI", physStr, Color.parseColor("#DDDDDD"));
            row2.addView(physCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout maxCol = makeSpecCol("Max Safe SW", String.valueOf(info.maxSw), Color.parseColor("#BB66FF"), Gravity.END);
            row2.addView(maxCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            specCard.addView(row2, r2Lp);

            // Row 3: Aspect Ratio | Refresh Rate
            LinearLayout row3 = new LinearLayout(this);
            row3.setOrientation(LinearLayout.HORIZONTAL);
            
            LinearLayout arCol = makeSpecCol("Aspect Ratio", arStr, Color.WHITE, Gravity.START);
            row3.addView(arCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout rrCol = makeSpecCol("Refresh Rate", rrStr, Color.parseColor("#44FF88"), Gravity.END);
            row3.addView(rrCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            specCard.addView(row3);

            LinearLayout.LayoutParams specCardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            specCardLp.bottomMargin = dp(16);
            rootLayout.addView(specCard, specCardLp);
        }

        // ── SW INFO CARD ─────────────────────────────────────────
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        android.graphics.drawable.GradientDrawable infoBg = new android.graphics.drawable.GradientDrawable();
        infoBg.setColor(Color.parseColor("#0DFFFFFF"));
        infoBg.setCornerRadius(dp(18));
        infoBg.setStroke(dp(1), Color.parseColor("#2FA855F7")); // purple outline
        infoCard.setBackground(infoBg);

        // Card header row
        LinearLayout infoHeader = new LinearLayout(this);
        infoHeader.setOrientation(LinearLayout.HORIZONTAL);
        infoHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ihLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ihLp.bottomMargin = dp(12);

        ImageView bulb = new ImageView(this);
        bulb.setImageResource(android.R.drawable.ic_dialog_info);
        bulb.setColorFilter(getColor(R.color.accent_purple));
        LinearLayout.LayoutParams bulbLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        bulbLp.rightMargin = dp(8);
        infoHeader.addView(bulb, bulbLp);

        TextView infoTitle = new TextView(this);
        infoTitle.setText("What is SmallestWidth (SW)?");
        infoTitle.setTextColor(Color.WHITE);
        infoTitle.setTextSize(13f);
        infoTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        infoHeader.addView(infoTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        infoCard.addView(infoHeader, ihLp);

        // Thin divider
        View infoDivider = new View(this);
        infoDivider.setBackgroundColor(Color.parseColor("#18A855F7"));
        LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        idLp.bottomMargin = dp(12);
        infoCard.addView(infoDivider, idLp);

        // Detail rows
        String defSwStr = (info != null) ? info.defaultSw + " dp" : "— dp";
        String maxSwStr = (info != null) ? info.maxSw + " dp" : "— dp";
        String[][] details = {
            {"📐", "SmallestWidth (SW)", "The minimum screen width in dp your device reports to apps. Lower density = higher SW."},
            {"📲", "Higher SW = More Space", "Tablet-like UI with bigger cards, wider columns, and more items per row."},
            {"🏠", "Your Default SW", "Factory default for your device is " + defSwStr + ". Going below this may break layouts."},
            {"🛡️", "Safe Range", "Recommended range: " + defSwStr + " → " + maxSwStr + ". Beyond max may crash some apps."},
            {"⚡", "Quick Switching", "Use the Quick Tile to cycle between your saved presets without opening the app."}
        };

        for (int di = 0; di < details.length; di++) {
            String[] d = details[di];
            LinearLayout detailRow = new LinearLayout(this);
            detailRow.setOrientation(LinearLayout.HORIZONTAL);
            detailRow.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            drLp.bottomMargin = (di < details.length - 1) ? dp(10) : 0;

            TextView detEmoji = new TextView(this);
            detEmoji.setText(d[0]);
            detEmoji.setTextSize(15f);
            LinearLayout.LayoutParams deLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            deLp.rightMargin = dp(10);
            deLp.topMargin = dp(1);
            detailRow.addView(detEmoji, deLp);

            LinearLayout detTextCol = new LinearLayout(this);
            detTextCol.setOrientation(LinearLayout.VERTICAL);

            TextView detTitle = new TextView(this);
            detTitle.setText(d[1]);
            detTitle.setTextColor(Color.WHITE);
            detTitle.setTextSize(12f);
            detTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            detTextCol.addView(detTitle);

            TextView detDesc = new TextView(this);
            detDesc.setText(d[2]);
            detDesc.setTextColor(getColor(R.color.text_secondary));
            detDesc.setTextSize(11f);
            detDesc.setLineSpacing(0, 1.25f);
            detTextCol.addView(detDesc);

            detailRow.addView(detTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            infoCard.addView(detailRow, drLp);
        }

        LinearLayout.LayoutParams infoCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoCardLp.bottomMargin = dp(8);
        rootLayout.addView(infoCard, infoCardLp);

        // Spacer
        android.widget.Space spacer = new android.widget.Space(this);
        rootLayout.addView(spacer, new LinearLayout.LayoutParams(0, dp(32), 1f));
    }

    private LinearLayout makeSpecCol(String label, String value, int valueColor, int gravity) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(gravity == Gravity.END ? Gravity.END : Gravity.START);

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(getColor(R.color.text_secondary));
        lv.setTextSize(10f);
        lv.setGravity(gravity);
        col.addView(lv);

        TextView vv = new TextView(this);
        vv.setText(value);
        vv.setTextColor(valueColor);
        vv.setTextSize(16f);
        vv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        vv.setGravity(gravity);
        col.addView(vv);
        return col;
    }

    private LinearLayout makeSpecColCenter(String label, String value, int valueColor) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(getColor(R.color.text_secondary));
        lv.setTextSize(10f);
        lv.setGravity(Gravity.CENTER);
        col.addView(lv);

        TextView vv = new TextView(this);
        vv.setText(value);
        vv.setTextColor(valueColor);
        vv.setTextSize(16f);
        vv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        vv.setGravity(Gravity.CENTER);
        col.addView(vv);
        return col;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 2: PRESETS
    // ─────────────────────────────────────────────────────────────────────────

    private void showPresetsTab() {
        addTitle("Presets");
        DpiController.DeviceInfo info = cachedInfo;
        if (info == null) return;

        // Safe Presets section
        LinearLayout presetsHeader = new LinearLayout(this);
        presetsHeader.setOrientation(LinearLayout.HORIZONTAL);
        presetsHeader.setPadding(0, 0, 0, dp(12));
        presetsHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView presetsLabel = new TextView(this);
        presetsLabel.setText("Safe Presets");
        presetsLabel.setTextColor(getColor(R.color.text_primary));
        presetsLabel.setTextSize(16f);
        presetsLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        presetsHeader.addView(presetsLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton refreshBtn = new ImageButton(this);
        refreshBtn.setImageResource(android.R.drawable.ic_popup_sync);
        refreshBtn.setBackgroundColor(Color.TRANSPARENT);
        refreshBtn.setColorFilter(Color.WHITE);
        refreshBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        presetsHeader.addView(refreshBtn);

        ImageButton iconSettingsBtn = new ImageButton(this);
        iconSettingsBtn.setImageResource(android.R.drawable.ic_menu_preferences);
        iconSettingsBtn.setBackgroundColor(Color.TRANSPARENT);
        iconSettingsBtn.setColorFilter(Color.WHITE);
        iconSettingsBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        iconSettingsBtn.setOnClickListener(v -> showIconSettingsOverlay());
        presetsHeader.addView(iconSettingsBtn);

        rootLayout.addView(presetsHeader);

        // Wrap grid in a card container so it never overflows
        LinearLayout gridCard = new LinearLayout(this);
        gridCard.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable gridCardBg = new android.graphics.drawable.GradientDrawable();
        gridCardBg.setColor(Color.parseColor("#0EFFFFFF"));
        gridCardBg.setCornerRadius(dp(16));
        gridCardBg.setStroke(dp(1), Color.parseColor("#15FFFFFF"));
        gridCard.setBackground(gridCardBg);
        gridCard.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams gridCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridCardLp.bottomMargin = dp(8);
        rootLayout.addView(gridCard, gridCardLp);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridCard.addView(grid, gridLp);

        Runnable populateGrid = () -> {
            grid.removeAllViews();
            for (int preset : info.dynamicPresets) {
                Button btn = new Button(this);
                btn.setText(String.valueOf(preset));
                btn.setBackgroundResource(R.drawable.btn_secondary);
                btn.setTextColor(Color.WHITE);
                btn.setTextSize(13f);
                btn.setPadding(0, dp(10), 0, dp(10));

                GridLayout.LayoutParams p = new GridLayout.LayoutParams();
                p.width = 0;
                p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                btn.setLayoutParams(p);

                btn.setOnClickListener(v -> applyDpiSafe(preset));
                grid.addView(btn);
            }
        };
        populateGrid.run();

        refreshBtn.setOnClickListener(v -> {
            info.shuffleDynamicPresets();
            populateGrid.run();
        });

        // Quick Tile Presets
        TextView tileLabel = new TextView(this);
        tileLabel.setText("Quick Tile Presets");
        tileLabel.setTextColor(getColor(R.color.text_primary));
        tileLabel.setTextSize(16f);
        tileLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tileLabel.setPadding(0, dp(24), 0, dp(8));
        rootLayout.addView(tileLabel);

        LinearLayout tileRow = new LinearLayout(this);
        tileRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText presetsInput = new EditText(this);
        presetsInput.setText(stateManager.getCustomPresetsString());
        presetsInput.setHint("e.g. 392, 480, 600");
        presetsInput.setHintTextColor(getColor(R.color.text_secondary));
        presetsInput.setTextColor(Color.WHITE);
        presetsInput.setBackgroundResource(R.drawable.bg_glass);
        presetsInput.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams tileInputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tileInputLp.rightMargin = dp(12);
        tileRow.addView(presetsInput, tileInputLp);

        Button saveBtn = new Button(this);
        saveBtn.setText("Save");
        saveBtn.setBackgroundResource(R.drawable.btn_primary);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setMinimumWidth(0);
        saveBtn.setMinHeight(0);
        saveBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        saveBtn.setOnClickListener(v -> {
            stateManager.setCustomPresetsString(presetsInput.getText().toString());
            showInAppToast("Tile presets saved!", false);
        });
        tileRow.addView(saveBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootLayout.addView(tileRow);

        // Custom DPI
        TextView customLabel = new TextView(this);
        customLabel.setText("Custom DPI");
        customLabel.setTextColor(getColor(R.color.text_primary));
        customLabel.setTextSize(16f);
        customLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        customLabel.setPadding(0, dp(20), 0, dp(8));
        rootLayout.addView(customLabel);

        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText customInput = new EditText(this);
        customInput.setHint("Enter value (e.g. 500)");
        customInput.setHintTextColor(getColor(R.color.text_secondary));
        customInput.setTextColor(Color.WHITE);
        customInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        customInput.setBackgroundResource(R.drawable.bg_glass);
        customInput.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams custInputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        custInputLp.rightMargin = dp(12);
        customRow.addView(customInput, custInputLp);

        Button applyBtn = new Button(this);
        applyBtn.setText("Apply");
        applyBtn.setBackgroundResource(R.drawable.btn_primary);
        applyBtn.setTextColor(Color.WHITE);
        applyBtn.setMinimumWidth(0);
        applyBtn.setMinHeight(0);
        applyBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        applyBtn.setOnClickListener(v -> {
            try {
                int val = Integer.parseInt(customInput.getText().toString());
                applyDpiSafe(val);
            } catch (Exception e) {
                showInAppToast("Invalid number", true);
            }
        });
        customRow.addView(applyBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootLayout.addView(customRow);

        // Spacer
        android.widget.Space spacer = new android.widget.Space(this);
        rootLayout.addView(spacer, new LinearLayout.LayoutParams(0, dp(32), 1f));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 3: ABOUT
    // ─────────────────────────────────────────────────────────────────────────

    private void showAboutTab() {
        // No addTitle here — About has its own centered header style

        // Logo
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_app);
        logo.setClipToOutline(true);
        logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(100), dp(100));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logoLp.topMargin = dp(12);
        logoLp.bottomMargin = dp(14);
        rootLayout.addView(logo, logoLp);

        TextView appName = new TextView(this);
        appName.setText("DPI Switcher");
        appName.setTextColor(Color.WHITE);
        appName.setTextSize(26f);
        appName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        appName.setGravity(Gravity.CENTER);
        rootLayout.addView(appName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView version = new TextView(this);
        version.setText("Version 1.0");
        version.setTextColor(getColor(R.color.text_secondary));
        version.setTextSize(12f);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(4), 0, dp(20));
        rootLayout.addView(version, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Divider
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#20FFFFFF"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.bottomMargin = dp(16);
        rootLayout.addView(div, divLp);

        // Info card
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        android.graphics.drawable.GradientDrawable icBg = new android.graphics.drawable.GradientDrawable();
        icBg.setColor(Color.parseColor("#12FFFFFF"));
        icBg.setCornerRadius(dp(16));
        icBg.setStroke(dp(1), Color.parseColor("#2FA855F7")); // purple outline
        infoCard.setBackground(icBg);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icLp.bottomMargin = dp(16);
        rootLayout.addView(infoCard, icLp);

        String[][] infos = {
            {"📱", "What it does", "Change your Android display density (DPI/SmallestWidth) on the fly without reboot, safely."},
            {"🛡️", "Safety First", "Built-in safety checks prevent your screen from getting stuck at an unusable DPI."},
            {"⚡", "Quick Tile", "Add a Quick Tile to your notification shade for instant DPI switching."},
            {"🔑", "Root Access", "Works with Magisk, KernelSU, or any root solution that grants shell access."},
            {"🔌", "Shizuku Support", "No root? Use Shizuku via ADB Wireless Debugging for the same experience."}
        };

        for (String[] row : infos) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(14);

            TextView icon = new TextView(this);
            icon.setText(row[0]);
            icon.setTextSize(20f);
            icon.setPadding(0, 0, dp(12), 0);
            rowLayout.addView(icon);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView rowTitle = new TextView(this);
            rowTitle.setText(row[1]);
            rowTitle.setTextColor(Color.WHITE);
            rowTitle.setTextSize(13f);
            rowTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textCol.addView(rowTitle);

            TextView rowDesc = new TextView(this);
            rowDesc.setText(row[2]);
            rowDesc.setTextColor(getColor(R.color.text_secondary));
            rowDesc.setTextSize(12f);
            rowDesc.setLineSpacing(0, 1.2f);
            textCol.addView(rowDesc);

            rowLayout.addView(textCol);
            infoCard.addView(rowLayout, rowLp);
        }

        // GitHub Card
        LinearLayout githubCard = new LinearLayout(this);
        githubCard.setOrientation(LinearLayout.HORIZONTAL);
        githubCard.setGravity(Gravity.CENTER_VERTICAL);
        githubCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable ghBg = new android.graphics.drawable.GradientDrawable();
        ghBg.setColor(Color.parseColor("#1AFFFFFF"));
        ghBg.setCornerRadius(dp(16));
        ghBg.setStroke(dp(1), Color.parseColor("#44FFFFFF"));
        githubCard.setBackground(ghBg);

        TextView ghIcon = new TextView(this);
        ghIcon.setText("🐙"); 
        ghIcon.setTextSize(24f);
        ghIcon.setPadding(0, 0, dp(16), 0);
        githubCard.addView(ghIcon);

        LinearLayout ghTextCol = new LinearLayout(this);
        ghTextCol.setOrientation(LinearLayout.VERTICAL);

        TextView ghTitle = new TextView(this);
        ghTitle.setText("Open Source");
        ghTitle.setTextColor(Color.WHITE);
        ghTitle.setTextSize(14f);
        ghTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ghTextCol.addView(ghTitle);

        TextView ghDesc = new TextView(this);
        ghDesc.setText("View source code on GitHub");
        ghDesc.setTextColor(getColor(R.color.text_secondary));
        ghDesc.setTextSize(12f);
        ghTextCol.addView(ghDesc);

        githubCard.addView(ghTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ghArrow = new TextView(this);
        ghArrow.setText("↗");
        ghArrow.setTextColor(getColor(R.color.accent_purple));
        ghArrow.setTextSize(18f);
        ghArrow.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        githubCard.addView(ghArrow);

        githubCard.setOnClickListener(v -> {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                intent.setData(android.net.Uri.parse("https://github.com/uniqueappsorg/DPI-SWITCHER"));
                startActivity(intent);
            } catch (Exception e) {
                showInAppToast("Could not open browser", true);
            }
        });

        LinearLayout.LayoutParams ghLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ghLp.bottomMargin = dp(16);
        rootLayout.addView(githubCard, ghLp);

        // Spacer
        android.widget.Space spacer = new android.widget.Space(this);
        rootLayout.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

        TextView devText = new TextView(this);
        devText.setText("DEVELOPED BY UNIQUE APPS");
        devText.setTextColor(getColor(R.color.accent_purple));
        devText.setTextSize(13f);
        devText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        devText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        devLp.bottomMargin = dp(8);
        rootLayout.addView(devText, devLp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DPI APPLICATION
    // ─────────────────────────────────────────────────────────────────────────

    private void applyDpiSafe(int targetDpi) {
        String err = DpiController.validate(targetDpi);
        if (err != null) {
            showInAppToast(err, false);
            return;
        }

        if (dpiValueDisplay != null) dpiValueDisplay.setText("...");

        DpiController.applyWithSafety(targetDpi,
            val -> showSafetyDialog(),
            result -> {
                if (result.success) {
                    if (dpiValueDisplay != null) dpiValueDisplay.setText(String.valueOf(result.dpi));
                    stateManager.setCurrentDpi(result.dpi);
                    DpiTileService.requestUpdate(this);
                    showInAppToast("✅ Applied " + result.dpi + " SW", false);
                } else {
                    if (dpiValueDisplay != null) dpiValueDisplay.setText("ERR");
                    showInAppToast("Failed: " + result.error, true);
                }
            });
    }

    private void showSafetyDialog() {
        // Use physical density for dp calculations inside dialog so it's not affected by DPI change
        float physDensity = (DpiController.deviceInfo != null && DpiController.deviceInfo.physicalDensity > 0)
                ? DpiController.deviceInfo.physicalDensity / 160f
                : getResources().getDisplayMetrics().density;

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    (int) (320 * physDensity),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // Root container
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(24 * physDensity);
        root.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable rootBg = new android.graphics.drawable.GradientDrawable();
        rootBg.setColor(Color.parseColor("#1E1B2E")); // dark purple-tinted card
        rootBg.setCornerRadius((int)(20 * physDensity));
        rootBg.setStroke((int)(1 * physDensity), Color.parseColor("#44A855F7")); // purple border
        root.setBackground(rootBg);
        root.setElevation(20 * physDensity);

        // Warning icon + title row
        android.widget.LinearLayout titleRow = new android.widget.LinearLayout(this);
        titleRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.TextView warningIcon = new android.widget.TextView(this);
        warningIcon.setText("⚠️");
        warningIcon.setTextSize(22f);
        android.widget.LinearLayout.LayoutParams wiLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wiLp.rightMargin = (int)(10 * physDensity);
        titleRow.addView(warningIcon, wiLp);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Confirm DPI Change");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Countdown badge
        android.widget.TextView countdown = new android.widget.TextView(this);
        countdown.setText("10s");
        countdown.setTextColor(Color.parseColor("#EF4444"));
        countdown.setTextSize(13f);
        countdown.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        countdown.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cdBg = new android.graphics.drawable.GradientDrawable();
        cdBg.setColor(Color.parseColor("#22EF4444"));
        cdBg.setCornerRadius((int)(20 * physDensity));
        cdBg.setStroke((int)(1 * physDensity), Color.parseColor("#44EF4444"));
        countdown.setBackground(cdBg);
        countdown.setPadding((int)(10*physDensity), (int)(4*physDensity), (int)(10*physDensity), (int)(4*physDensity));
        titleRow.addView(countdown);

        android.widget.LinearLayout.LayoutParams titleRowLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleRowLp.bottomMargin = (int)(12 * physDensity);
        root.addView(titleRow, titleRowLp);

        // Divider
        android.view.View divider = new android.view.View(this);
        divider.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        android.widget.LinearLayout.LayoutParams divLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(1 * physDensity));
        divLp.bottomMargin = (int)(14 * physDensity);
        root.addView(divider, divLp);

        // Message
        android.widget.TextView msg = new android.widget.TextView(this);
        msg.setText("Screen looks good? Tap \"Keep\" to confirm.\nIf unusable, wait — it auto-reverts in 10 seconds.");
        msg.setTextColor(Color.parseColor("#94A3B8"));
        msg.setTextSize(13f);
        msg.setLineSpacing(0, 1.3f);
        android.widget.LinearLayout.LayoutParams msgLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.bottomMargin = (int)(20 * physDensity);
        root.addView(msg, msgLp);

        // Buttons row
        android.widget.LinearLayout btns = new android.widget.LinearLayout(this);
        btns.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int btnGap = (int)(10 * physDensity);

        android.widget.Button cancel = new android.widget.Button(this);
        cancel.setText("↩ Revert");
        cancel.setBackgroundResource(R.drawable.btn_secondary);
        cancel.setTextColor(Color.WHITE);
        cancel.setMinimumWidth(0);
        cancel.setMinHeight(0);
        cancel.setTextSize(13f);
        cancel.setPadding((int)(16*physDensity), (int)(10*physDensity), (int)(16*physDensity), (int)(10*physDensity));
        android.widget.LinearLayout.LayoutParams cLp = new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cLp.rightMargin = btnGap;
        btns.addView(cancel, cLp);

        android.widget.Button apply = new android.widget.Button(this);
        apply.setText("✓ Keep");
        apply.setBackgroundResource(R.drawable.btn_primary);
        apply.setTextColor(Color.WHITE);
        apply.setMinimumWidth(0);
        apply.setMinHeight(0);
        apply.setTextSize(13f);
        apply.setPadding((int)(16*physDensity), (int)(10*physDensity), (int)(16*physDensity), (int)(10*physDensity));
        btns.addView(apply, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(btns);

        dialog.setContentView(root);
        dialog.show();
        safetyDialog = dialog;

        safetyTimer = new android.os.CountDownTimer(10000, 1000) {
            public void onTick(long millis) {
                countdown.setText((millis / 1000) + "s");
            }
            public void onFinish() {
                if (safetyDialog != null && safetyDialog.isShowing()) {
                    safetyDialog.dismiss();
                    DpiController.cancelAndRevert(r -> {
                        if (r.success) {
                            if (dpiValueDisplay != null) dpiValueDisplay.setText(String.valueOf(r.dpi));
                            stateManager.setCurrentDpi(r.dpi);
                            showInAppToast("Safety Reset Applied", false);
                        }
                    });
                }
            }
        }.start();

        cancel.setOnClickListener(v -> {
            safetyTimer.cancel();
            safetyDialog.dismiss();
            DpiController.cancelAndRevert(r -> {
                if (r.success) {
                    if (dpiValueDisplay != null) dpiValueDisplay.setText(String.valueOf(r.dpi));
                    stateManager.setCurrentDpi(r.dpi);
                    showInAppToast("Reverted", false);
                }
            });
        });

        apply.setOnClickListener(v -> {
            safetyTimer.cancel();
            safetyDialog.dismiss();
            DpiController.confirmKeepDpi();
            showInAppToast("✅ DPI Kept!", false);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ICON SETTINGS OVERLAY
    // ─────────────────────────────────────────────────────────────────────────

    private void showIconSettingsOverlay() {
        if (iconOverlay != null) return;

        ViewGroup content = (ViewGroup) getWindow().getDecorView().getRootView();

        iconOverlay = new LinearLayout(this);
        ((LinearLayout) iconOverlay).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) iconOverlay).setGravity(Gravity.CENTER);
        iconOverlay.setBackgroundColor(Color.parseColor("#E6000000"));
        iconOverlay.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_glass);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView cardTitle = new TextView(this);
        cardTitle.setText("Choose Tile Icon");
        cardTitle.setTextColor(Color.WHITE);
        cardTitle.setTextSize(16f);
        cardTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(12);
        card.addView(cardTitle, titleLp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        scroll.setLayoutParams(scrollParams);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);

        int currentIcon = getSharedPreferences("DpiSwitcherPrefs", Context.MODE_PRIVATE).getInt("tile_icon", android.R.drawable.ic_menu_sort_by_size);

        // Collect icons sorted alphabetically by field name
        java.util.List<java.util.Map.Entry<String,Integer>> iconEntries = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Field[] fields = android.R.drawable.class.getFields();
            for (java.lang.reflect.Field field : fields) {
                String name = field.getName();
                if (name.startsWith("ic_") || name.startsWith("stat_")) {
                    iconEntries.add(new java.util.AbstractMap.SimpleEntry<>(name, field.getInt(null)));
                }
            }
        } catch (Exception e) {}
        java.util.Collections.sort(iconEntries, (a, b) -> a.getKey().compareTo(b.getKey()));

        if (iconEntries.isEmpty()) {
            java.util.Map.Entry<String,Integer> fallback =
                new java.util.AbstractMap.SimpleEntry<>("ic_menu_preferences", android.R.drawable.ic_menu_preferences);
            iconEntries.add(fallback);
        }

        for (java.util.Map.Entry<String,Integer> entry : iconEntries) {
            int iconRes = entry.getValue();
            ImageButton btn = new ImageButton(this);
            try {
                btn.setImageResource(iconRes);
            } catch (Exception e) {
                continue;
            }
            btn.setColorFilter(Color.WHITE);

            boolean isSelected = (iconRes == currentIcon);
            android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
            btnBg.setColor(isSelected ? Color.parseColor("#33A855F7") : Color.parseColor("#12FFFFFF"));
            btnBg.setCornerRadius(dp(12));
            if (isSelected) btnBg.setStroke(dp(1), Color.parseColor("#88A855F7"));
            btn.setBackground(btnBg);

            btn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            btn.setPadding(dp(14), dp(14), dp(14), dp(14));

            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0;
            p.height = dp(64);
            p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            p.setMargins(dp(4), dp(4), dp(4), dp(4));
            btn.setLayoutParams(p);

            btn.setOnClickListener(v -> {
                getSharedPreferences("DpiSwitcherPrefs", Context.MODE_PRIVATE).edit().putInt("tile_icon", iconRes).apply();
                DpiTileService.requestUpdate(this);
                showInAppToast("Tile Icon Updated!", false);
                closeIconSettingsOverlay();
            });
            grid.addView(btn);
        }
        scroll.addView(grid);
        card.addView(scroll, scrollParams);

        Button closeBtn = new Button(this);
        closeBtn.setText("CLOSE");
        closeBtn.setTextColor(getColor(R.color.accent_purple));
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setOnClickListener(v -> closeIconSettingsOverlay());

        LinearLayout.LayoutParams closeBtnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeBtnLp.gravity = Gravity.END;
        closeBtnLp.topMargin = dp(16);
        card.addView(closeBtn, closeBtnLp);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(24), 0, dp(24), 0);
        ((LinearLayout) iconOverlay).addView(card, cardParams);
        content.addView(iconOverlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeIconSettingsOverlay() {
        if (iconOverlay != null) {
            ViewGroup content = (ViewGroup) getWindow().getDecorView().getRootView();
            content.removeView(iconOverlay);
            iconOverlay = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void addTitle(String text) {
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(0, dp(4), 0, dp(16));

        ImageView headerLogo = new ImageView(this);
        headerLogo.setImageResource(R.drawable.ic_app);
        headerLogo.setClipToOutline(true);
        headerLogo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoParams.rightMargin = dp(12);
        headerLayout.addView(headerLogo, logoParams);

        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerLayout.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        rootLayout.addView(headerLayout);
    }

    private int dp(float dp) {
        float density = getResources().getDisplayMetrics().density;
        if (DpiController.deviceInfo != null && DpiController.deviceInfo.physicalDensity > 0) {
            density = DpiController.deviceInfo.physicalDensity / 160f;
        }
        return (int) (dp * density + 0.5f);
    }

    /**
     * Custom in-app notification — replaces system Toast.
     *
     * Why: Android system Toast is rendered by the system and is scaled by the
     * current system DPI. When the user sets a very high SW (low density), the
     * system DPI drops and Toast text becomes tiny. This implementation draws
     * entirely inside our own window using PHYSICAL density pixels, so it is
     * completely immune to DPI changes.
     *
     * @param message  Text to display
     * @param isError  true = red/error style, false = purple/success style
     */
    private android.os.Handler _toastHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private View _currentToast = null;

    private void showInAppToast(String message, boolean isError) {
        // Always size using physical density — never the current DPI-overridden density
        final float pd = (DpiController.deviceInfo != null && DpiController.deviceInfo.physicalDensity > 0)
                ? DpiController.deviceInfo.physicalDensity / 160f
                : getResources().getDisplayMetrics().density;

        // Run on UI thread — this can be called from callbacks
        runOnUiThread(() -> {
            // Dismiss any existing in-app toast
            if (_currentToast != null) {
                _toastHandler.removeCallbacksAndMessages(null);
                try { ((ViewGroup) getWindow().getDecorView()).removeView(_currentToast); } catch (Exception ignored) {}
                _currentToast = null;
            }

            // ── Build the toast view ──────────────────────────────────────────
            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.HORIZONTAL);
            container.setGravity(Gravity.CENTER_VERTICAL);
            int hPad = (int)(16 * pd), vPad = (int)(11 * pd);
            container.setPadding(hPad, vPad, hPad, vPad);

            // Background pill
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(isError ? Color.parseColor("#E8200E1A") : Color.parseColor("#E80D1B1E"));
            bg.setCornerRadius((int)(28 * pd));
            bg.setStroke((int)(1 * pd), isError
                    ? Color.parseColor("#99EF4444")
                    : Color.parseColor("#99A855F7"));
            container.setBackground(bg);
            container.setElevation(24 * pd);

            // App icon (small, rounded)
            ImageView appIcon = new ImageView(this);
            appIcon.setImageResource(R.drawable.ic_app);
            appIcon.setClipToOutline(true);
            appIcon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), (int)(4 * pd));
                }
            });
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    (int)(18 * pd), (int)(18 * pd));
            iconLp.rightMargin = (int)(8 * pd);
            container.addView(appIcon, iconLp);

            // Message text — sized in raw pixels from physical density (DPI-immune)
            TextView tv = new TextView(this);
            tv.setText(message);
            tv.setTextColor(Color.WHITE);
            // IMPORTANT: COMPLEX_UNIT_PX bypasses sp scaling — always correct size
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (int)(12.5f * pd));
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tv.setSingleLine(false);
            tv.setMaxLines(2);
            container.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // ── Position at bottom-center above bottom nav ────────────────────
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // bottomMargin: clear the nav bar (≈64dp) + system nav inset + a gap
            int navBarHeight = 0;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) navBarHeight = insets.getSystemWindowInsetBottom();
            }
            lp.bottomMargin = navBarHeight + (int)(80 * pd);

            ((android.widget.FrameLayout) getWindow().getDecorView()).addView(container, lp);
            _currentToast = container;

            // ── Animate in ────────────────────────────────────────────────────
            container.setAlpha(0f);
            container.setTranslationY((int)(20 * pd));
            container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

            // ── Auto-dismiss after 2.5s ───────────────────────────────────────
            _toastHandler.postDelayed(() -> {
                container.animate()
                        .alpha(0f)
                        .translationY((int)(16 * pd))
                        .setDuration(250)
                        .withEndAction(() -> {
                            try { ((ViewGroup) getWindow().getDecorView()).removeView(container); } catch (Exception ignored) {}
                            if (_currentToast == container) _currentToast = null;
                        }).start();
            }, 2500);
        });
    }
}

