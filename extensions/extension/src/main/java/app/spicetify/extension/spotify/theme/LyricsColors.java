package app.spicetify.extension.spotify.theme;

import android.content.Context;
import android.graphics.Color;

/**
 * Replaces the colors Spotify picks for each song's lyrics (the lyrics card and the full-screen
 * lyrics) styled like Spicy Lyrics on desktop: the song's own color as frosted glass, the line being
 * sung in white, and the other lines in see-through white. Spotify's colors stay when the setting is off.
 *
 * <p>The lyrics colors object's constructor calls these, so lyrics from the network, the cache and
 * offline downloads all take them. Each returns a value that doesn't depend on its input, so an
 * object rebuilt from another (a Parcel, say) gets the same colors.
 */
public final class LyricsColors {
    private static final String PREFERENCES = "spicetify_theme";
    private static final String KEY = "themed_lyrics";
    /** The card's opacity: frosted glass, like Spicy Lyrics' card, rather than a solid block. */
    private static final int GLASS_ALPHA = 0xBF;
    /** The other lines: see-through white, the way Spicy Lyrics fades the lines not being sung. */
    private static final int FADED_ALPHA = 0x73;

    /** Read once at startup; changing the setting takes a restart, as other theme changes do. */
    private static volatile boolean enabled = true;

    private LyricsColors() {}

    static void load(Context context) {
        enabled = isEnabled(context);
    }

    public static boolean isEnabled(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY, true);
    }

    public static void setEnabled(Context context, boolean value) {
        context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, value).apply();
    }

    private static boolean active() {
        return enabled;
    }

    /** Whether lyrics lines get Spicy Lyrics' style: bold, with the sung line glowing. */
    public static boolean styled() {
        return enabled;
    }

    /** Behind the lyrics: Spotify's color for the song, as frosted glass. */
    public static int background(int spotify) {
        if (!active()) return spotify;
        return (GLASS_ALPHA << 24) | (spotify & 0x00FFFFFF);
    }

    /** Spotify shrinks the lines not being sung to this; Spicy Lyrics sets them back further. */
    private static final float SPOTIFY_INACTIVE_SCALE = 0.83f;
    private static final float SPICY_INACTIVE_SCALE = 0.72f;

    /** The size of the lines not being sung, next to the sung line at full size. */
    public static float inactiveScale() {
        return active() ? SPICY_INACTIVE_SCALE : SPOTIFY_INACTIVE_SCALE;
    }

    /** The line being sung. */
    public static int highlight(int spotify) {
        return active() ? ThemeRuntime.color("text", Color.WHITE) : spotify;
    }

    /** The other lines: the theme's text color, see-through so they recede behind the sung line. */
    public static int text(int spotify) {
        if (!active()) return spotify;
        int text = ThemeRuntime.color("text", Color.WHITE);
        return (FADED_ALPHA << 24) | (text & 0x00FFFFFF);
    }
}
