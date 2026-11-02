package io.github.vlesk.stave.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;

import io.github.vlesk.stave.R;

/**
 * Material 3 colour roles for the custom-drawn UI. On Android 12+ the roles follow the
 * wallpaper-based dynamic colour palette (Material You); otherwise the app's blue palette is used.
 */
public final class Theme {
    public final boolean dark;
    public int primary, onPrimary, primaryContainer, onPrimaryContainer;
    public int surface, surfaceContainer, surfaceContainerHigh, onSurface, onSurfaceVariant, outline, outlineVariant;
    /** Paper and ink of the score itself. */
    public int paper, ink, inkMuted;
    /** Selection colours per voice, as in MuseScore (blue, green, orange, purple). */
    public int[] voice;
    public int playback, cursorFill;
    public final float density;

    private static Typeface music, supplement, text, textItalic, textBold;

    public Theme(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        density = c.getResources().getDisplayMetrics().density;
        primary = c.getColor(R.color.md_primary);
        onPrimary = c.getColor(R.color.md_on_primary);
        primaryContainer = c.getColor(R.color.md_primary_container);
        onPrimaryContainer = c.getColor(R.color.md_on_primary_container);
        surface = c.getColor(R.color.md_surface);
        surfaceContainer = c.getColor(R.color.md_surface_container);
        surfaceContainerHigh = c.getColor(R.color.md_surface_container_high);
        onSurface = c.getColor(R.color.md_on_surface);
        onSurfaceVariant = c.getColor(R.color.md_on_surface_variant);
        outline = c.getColor(R.color.md_outline);
        outlineVariant = c.getColor(R.color.md_outline_variant);
        if (Build.VERSION.SDK_INT >= 31) applyDynamic(c);
        paper = dark ? 0xFF1C1D22 : 0xFFFFFFFF;
        ink = dark ? 0xFFE6E6EA : 0xFF111111;
        inkMuted = dark ? 0xFF9A9CA6 : 0xFF777777;
        voice = dark ? new int[]{0xFF6FA8FF, 0xFF4CD383, 0xFFFFA94D, 0xFFE07BF0}
                : new int[]{0xFF1A62D6, 0xFF00893A, 0xFFD06A00, 0xFFAD2BBF};
        playback = dark ? 0xFFFFB74D : 0xFFE65100;
        cursorFill = (primary & 0x00FFFFFF) | 0x33000000;
    }

    private int sys(Context c, String name, int fallback) {
        int id = c.getResources().getIdentifier(name, "color", "android");
        if (id == 0) return fallback;
        try {
            return c.getColor(id);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void applyDynamic(Context c) {
        if (dark) {
            primary = sys(c, "system_accent1_200", primary);
            onPrimary = sys(c, "system_accent1_800", onPrimary);
            primaryContainer = sys(c, "system_accent1_700", primaryContainer);
            onPrimaryContainer = sys(c, "system_accent1_100", onPrimaryContainer);
            surface = sys(c, "system_neutral1_900", surface);
            surfaceContainer = sys(c, "system_neutral1_800", surfaceContainer);
            surfaceContainerHigh = sys(c, "system_neutral2_800", surfaceContainerHigh);
            onSurface = sys(c, "system_neutral1_100", onSurface);
            onSurfaceVariant = sys(c, "system_neutral2_200", onSurfaceVariant);
            outline = sys(c, "system_neutral2_400", outline);
            outlineVariant = sys(c, "system_neutral2_700", outlineVariant);
        } else {
            primary = sys(c, "system_accent1_600", primary);
            onPrimary = sys(c, "system_accent1_0", onPrimary);
            primaryContainer = sys(c, "system_accent1_100", primaryContainer);
            onPrimaryContainer = sys(c, "system_accent1_900", onPrimaryContainer);
            surface = sys(c, "system_neutral1_10", surface);
            surfaceContainer = sys(c, "system_neutral1_50", surfaceContainer);
            surfaceContainerHigh = sys(c, "system_neutral2_100", surfaceContainerHigh);
            onSurface = sys(c, "system_neutral1_900", onSurface);
            onSurfaceVariant = sys(c, "system_neutral2_700", onSurfaceVariant);
            outline = sys(c, "system_neutral2_500", outline);
            outlineVariant = sys(c, "system_neutral2_200", outlineVariant);
        }
    }

    public int dp(float v) {
        return Math.round(v * density);
    }

    public static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    public static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    public static synchronized Typeface music(Context c) {
        if (music == null) music = Typeface.createFromAsset(c.getAssets(), "fonts/Leland.otf");
        return music;
    }

    /** The supplementary music font: SMuFL glyphs Leland lacks (see Sym.SUPPLEMENT). */
    public static synchronized Typeface supplement(Context c) {
        if (supplement == null) supplement = Typeface.createFromAsset(c.getAssets(), "fonts/StaveSupplement.otf");
        return supplement;
    }

    /** The music font that has a glyph. */
    public static Typeface music(Context c, int glyph) {
        return io.github.vlesk.stave.engrave.Sym.SUPPLEMENT[glyph] ? supplement(c) : music(c);
    }

    public static synchronized Typeface text(Context c) {
        if (text == null) text = Typeface.createFromAsset(c.getAssets(), "fonts/Edwin-Roman.otf");
        return text;
    }

    public static synchronized Typeface textItalic(Context c) {
        if (textItalic == null) textItalic = Typeface.createFromAsset(c.getAssets(), "fonts/Edwin-Italic.otf");
        return textItalic;
    }

    public static synchronized Typeface textBold(Context c) {
        if (textBold == null) textBold = Typeface.createFromAsset(c.getAssets(), "fonts/Edwin-Bold.otf");
        return textBold;
    }
}
