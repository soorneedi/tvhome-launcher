package com.example.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final long ROTATE_MS = 10 * 60 * 1000L;
    private static final long FADE_MS = 3000L;
    private static final int FOLDER_COLS = 6;
    private static final int CELL_W_DP = 130;

    private static final int[] WALLS = {
            R.drawable.wall1, R.drawable.wall2, R.drawable.wall3,
            R.drawable.wall4, R.drawable.wall5, R.drawable.wall6
    };

    private static class Slot {
        final String label;
        final String[] pkgs;
        final int color;

        Slot(String label, String[] pkgs, int color) {
            this.label = label;
            this.pkgs = pkgs;
            this.color = color;
        }
    }

    private static final Slot[] DOCK = {
            new Slot("YouTube", new String[]{"com.google.android.youtube.tv", "com.vanced.android.youtube", "com.teamsmart.videomanager.tv"}, 0xFFE53935),
            new Slot("Netflix", new String[]{"com.netflix.ninja"}, 0xFFB71C1C),
            new Slot("Prime Video", new String[]{"com.amazon.amazonvideo.livingroom"}, 0xFF1A98FF),
            new Slot("Hotstar", new String[]{"in.startv.hotstar"}, 0xFF1F3C88),
            new Slot("JioCinema", new String[]{"com.jio.media.ondemand"}, 0xFFD81B60),
            new Slot("Sony LIV", new String[]{"com.sonyliv"}, 0xFF6A1B9A)
    };
    private static final Slot KODI = new Slot("Kodi", new String[]{"org.xbmc.kodi"}, 0xFF1A6FA8);

    private static class AppEntry {
        final String label;
        final ResolveInfo info;

        AppEntry(String label, ResolveInfo info) {
            this.label = label;
            this.info = info;
        }
    }

    private final Handler handler = new Handler();
    private PackageManager pm;
    private ImageView wallA, wallB;
    private boolean showingA = true;
    private int wallIdx = 0;

    private LinearLayout dock;
    private FrameLayout folderOverlay;
    private LinearLayout folderGrid;
    private View appsFolderTile;

    private final Runnable rotateTask = new Runnable() {
        @Override
        public void run() {
            rotateWallpaper();
            handler.postDelayed(this, ROTATE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ViewGroup.LayoutParams match = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // Wallpapers
        wallA = newWall();
        wallB = newWall();
        wallB.setAlpha(0f);
        wallA.setImageBitmap(loadWall(WALLS[0]));
        root.addView(wallA, match);
        root.addView(wallB, match);

        // Apple TV subtle vignette
        View scrim = new View(this);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22000000, 0x88000000});
        scrim.setBackground(sg);
        root.addView(scrim, match);

        // Main Stage Container
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(36), dp(56), dp(40));
        noClip(col);

        // Top Status Bar (Clock in top-right)
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("h:mm a");
        clock.setTextColor(0xDDFFFFFF);
        clock.setTextSize(26);
        clock.setShadowLayer(8, 0, 2, 0xCC000000);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END;
        col.addView(clock, clp);

        // Center / Bottom Dock Container
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(20), dp(14), dp(20), dp(14));
        dock.setBackground(round(0x28FFFFFF, dp(32)));
        noClip(dock);

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dlp.topMargin = dp(180);
        col.addView(dock, dlp);

        root.addView(col, match);

        // Apple TV Modal "Apps" Folder Overlay
        folderOverlay = new FrameLayout(this);
        folderOverlay.setBackgroundColor(0xD90E0E12); // Deep translucent tvOS blur shade
        folderOverlay.setVisibility(View.GONE);
        folderOverlay.setClickable(true);
        folderOverlay.setFocusable(false);

        LinearLayout folderCol = new LinearLayout(this);
        folderCol.setOrientation(LinearLayout.VERTICAL);
        folderCol.setPadding(dp(64), dp(48), dp(64), dp(48));
        noClip(folderCol);

        TextView folderTitle = new TextView(this);
        folderTitle.setText("App Library");
        folderTitle.setTextColor(Color.WHITE);
        folderTitle.setTextSize(26);
        folderTitle.setGravity(Gravity.START);
        folderTitle.setShadowLayer(6, 0, 2, 0x99000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(24);
        folderCol.addView(folderTitle, tlp);

        ScrollView folderScroll = new ScrollView(this);
        folderScroll.setVerticalScrollBarEnabled(false);
        folderScroll.setClipToPadding(false);
        folderScroll.setClipChildren(false);

        folderGrid = new LinearLayout(this);
        folderGrid.setOrientation(LinearLayout.VERTICAL);
        folderGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        noClip(folderGrid);

        folderScroll.addView(folderGrid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        folderCol.addView(folderScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        folderOverlay.addView(folderCol, match);
        root.addView(folderOverlay, match);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        populate();
        handler.removeCallbacks(rotateTask);
        handler.postDelayed(rotateTask, ROTATE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateTask);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (folderOverlay.getVisibility() == View.VISIBLE) {
            closeFolder();
        }
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (folderOverlay.getVisibility() == View.VISIBLE) {
            closeFolder();
        }
    }

    private void openFolder() {
        folderOverlay.setAlpha(0f);
        folderOverlay.setVisibility(View.VISIBLE);
        folderOverlay.animate().alpha(1f).setDuration(180).start();
        if (folderGrid.getChildCount() > 0) {
            LinearLayout firstRow = (LinearLayout) folderGrid.getChildAt(0);
            if (firstRow.getChildCount() > 0) {
                firstRow.getChildAt(0).requestFocus();
            }
        }
    }

    private void closeFolder() {
        folderOverlay.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
            @Override
            public void run() {
                folderOverlay.setVisibility(View.GONE);
                if (appsFolderTile != null) {
                    appsFolderTile.requestFocus();
                } else if (dock.getChildCount() > 0) {
                    dock.getChildAt(0).requestFocus();
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView newWall() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private Bitmap loadWall(int res) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        return BitmapFactory.decodeResource(getResources(), res, o);
    }

    private void rotateWallpaper() {
        wallIdx = (wallIdx + 1) % WALLS.length;
        final ImageView incoming = showingA ? wallB : wallA;
        final ImageView outgoing = showingA ? wallA : wallB;
        incoming.setImageBitmap(loadWall(WALLS[wallIdx]));
        incoming.setAlpha(0f);
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        outgoing.animate().alpha(0f).setDuration(FADE_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                outgoing.setImageDrawable(null);
            }
        }).start();
        showingA = !showingA;
    }

    // ---------------------------------------------------------------- dock + library folder

    private void populate() {
        dock.removeAllViews();
        folderGrid.removeAllViews();

        Set<String> used = new HashSet<String>();
        used.add(getPackageName());

        // 1. Build the Primary Apple TV Dock (pinned streaming apps)
        for (Slot s : DOCK) {
            dock.addView(slotCell(s, 86, used));
        }

        // 2. Discover all non-docked applications
        List<AppEntry> apps = new ArrayList<AppEntry>();
        Set<String> seen = new HashSet<String>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (used.contains(pkg) || !seen.add(pkg)) continue;
                apps.add(new AppEntry(String.valueOf(ri.loadLabel(pm)), ri));
            }
        }
        Collections.sort(apps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        // 3. Populate Modal App Library
        List<View> folderCells = new ArrayList<View>();
        folderCells.add(slotCell(KODI, 72, used)); // Kodi included inside Library if not in dock

        for (final AppEntry e : apps) {
            folderCells.add(cell(e.label, e.info.loadIcon(pm), 72, false, new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.setComponent(new ComponentName(e.info.activityInfo.packageName, e.info.activityInfo.name));
                    launch(i);
                }
            }));
        }

        LinearLayout row = null;
        for (int n = 0; n < folderCells.size(); n++) {
            if (n % FOLDER_COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                noClip(row);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(16);
                folderGrid.addView(row, rlp);
            }
            row.addView(folderCells.get(n));
        }

        // 4. Append the "Apps" Folder tile to the end of the Dock
        appsFolderTile = cell("Apps", folderTileIcon(), 86, false, new Runnable() {
            @Override
            public void run() {
                openFolder();
            }
        });
        dock.addView(appsFolderTile);

        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    private View slotCell(final Slot s, int iconDp, Set<String> used) {
        String chosen = null;
        Intent launch = null;
        for (String p : s.pkgs) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(p);
            if (i == null) i = pm.getLaunchIntentForPackage(p);
            if (i != null) {
                chosen = p;
                launch = i;
                break;
            }
        }
        if (chosen != null) {
            used.add(chosen);
            Drawable icon;
            try {
                icon = pm.getApplicationIcon(chosen);
            } catch (PackageManager.NameNotFoundException e) {
                icon = letterTile(s.label, s.color);
            }
            final Intent li = launch;
            return cell(s.label, icon, iconDp, false, new Runnable() {
                @Override
                public void run() {
                    launch(li);
                }
            });
        }
        return cell(s.label, letterTile(s.label, s.color), iconDp, true, new Runnable() {
            @Override
            public void run() {
                openStore(s.pkgs[0]);
            }
        });
    }

    private View cell(String label, Drawable icon, int iconDp, final boolean dim, final Runnable action) {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(8), dp(10), dp(8), dp(8));
        c.setFocusable(true);
        c.setClickable(true);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        c.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setShadowLayer(4, 0, 1, 0xCC000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                dp(CELL_W_DP - 16), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        c.addView(tv, tlp);

        c.setAlpha(dim ? 0.45f : 1f);
        c.setLayoutParams(new LinearLayout.LayoutParams(
                dp(CELL_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        c.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                float s = has ? 1.15f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(120).start();
                v.setBackground(has ? round(0x44FFFFFF, dp(18)) : null);
                v.setAlpha(dim ? (has ? 0.85f : 0.45f) : 1f);
            }
        });
        return c;
    }

    // ---------------------------------------------------------------- graphics & tiles

    private Drawable folderTileIcon() {
        int size = 192;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);

        // Apple TV frosted app-folder tile background
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0x40FFFFFF);
        cv.drawRoundRect(new RectF(0, 0, size, size), 42, 42, bgPaint);

        // 3x3 App Drawer icon matrix
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(0xEEFFFFFF);
        float radius = 10f;
        float[] coords = {54f, 96f, 138f};
        for (float x : coords) {
            for (float y : coords) {
                cv.drawCircle(x, y, radius, dotPaint);
            }
        }
        return new BitmapDrawable(getResources(), b);
    }

    private Drawable letterTile(String label, int color) {
        int size = 192;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        cv.drawRoundRect(new RectF(0, 0, size, size), 40, 40, p);
        p.setColor(Color.WHITE);
        p.setTextSize(110);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        cv.drawText(label.substring(0, 1), size / 2f, y, p);
        return new BitmapDrawable(getResources(), b);
    }

    // ---------------------------------------------------------------- launching

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that app", Toast.LENGTH_SHORT).show();
        }
    }

    private void openStore(String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not installed", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void noClip(ViewGroup g) {
        g.setClipChildren(false);
        g.setClipToPadding(false);
    }

    private Drawable round(int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }
}
