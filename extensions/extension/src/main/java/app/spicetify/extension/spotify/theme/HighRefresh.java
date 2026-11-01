package app.spicetify.extension.spotify.theme;

import android.app.Activity;
import android.content.Context;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * Keeps the screen at its highest refresh rate (120 Hz on most recent phones) while Spotify is open,
 * so lyrics scroll smoothly instead of the display dropping to a lower rate when nothing is touched.
 * It asks for the display's fastest mode at the current resolution; Android decides in the end, and
 * battery saver can still lower it.
 */
public final class HighRefresh {
    private static final String PREFERENCES = "spicetify_theme";
    private static final String KEY = "high_refresh";

    private HighRefresh() {}

    public static boolean isEnabled(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY, false);
    }

    public static void setEnabled(Context context, boolean value) {
        context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, value).apply();
    }

    /** Asks for the fastest mode at the current resolution, or clears the request when off. */
    @SuppressWarnings("deprecation") // getDefaultDisplay: Activity.getDisplay needs Android 11.
    static void applyTo(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int wanted = 0;
        if (isEnabled(activity)) {
            Display display = activity.getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            Display.Mode best = current;
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                        && mode.getPhysicalHeight() == current.getPhysicalHeight()
                        && mode.getRefreshRate() > best.getRefreshRate()) {
                    best = mode;
                }
            }
            wanted = best.getModeId();
        }
        if (params.preferredDisplayModeId == wanted) return;
        params.preferredDisplayModeId = wanted;
        window.setAttributes(params);
    }
}
