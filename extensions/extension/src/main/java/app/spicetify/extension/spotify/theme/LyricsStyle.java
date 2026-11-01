package app.spicetify.extension.spotify.theme;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Restyles each lyrics line like Spicy Lyrics: every line bold, and the line being sung glowing white.
 *
 * <p>The lyrics line hands its finished text style here. The style's class is obfuscated, but its copy
 * method keeps Compose's FontWeight and Shadow parameters, so the method is found by those, and called
 * with every other parameter left at its default through the copy's default mask. How strongly the line
 * glows follows the line's own scale animation: full at the sung line's size, none at the others'.
 * Anything unexpected leaves Spotify's style as it is.
 */
public final class LyricsStyle {
    private static final String TAG = "Spicetify";
    /** TextStyle.copy's parameters, in Compose's order: fontWeight is the 3rd, shadow the 14th, of 24. */
    private static final int FONT_WEIGHT_BIT = 1 << 2;
    private static final int SHADOW_BIT = 1 << 13;
    private static final int ALL_DEFAULT = (1 << 24) - 1;
    private static final int BOLD = 700;
    private static final float GLOW_RADIUS = 28f;

    private static Object line;
    private static Method copy;
    private static int weightIndex = -1;
    private static int shadowIndex = -1;
    private static Object bold;
    private static Constructor<?> shadowConstructor;
    private static boolean shadowColorFirst;
    private static boolean broken;

    private LyricsStyle() {}

    /** The lyrics line's scale field; the patch makes this return its name. */
    static String scaleField() {
        return "";
    }

    /** The lyrics line about to style its text, for the scale of its sung-line animation. */
    public static void setLine(Object lambda) {
        line = lambda;
    }

    public static Object restyle(Object style) {
        if (broken || style == null || !LyricsColors.styled()) return style;
        try {
            if (copy == null) find(style.getClass());
            float glow = glow();
            Class<?>[] types = copy.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) args[i] = zero(types[i]);
            args[0] = style;
            args[weightIndex] = bold;
            int mask = ALL_DEFAULT & ~FONT_WEIGHT_BIT;
            if (glow > 0.01f) {
                args[shadowIndex] = shadow(glow);
                mask &= ~SHADOW_BIT;
            }
            args[types.length - 1] = mask;
            return copy.invoke(null, args);
        } catch (Throwable e) {
            broken = true;
            Log.w(TAG, "Lyrics style unchanged", e);
            return style;
        }
    }

    /** 1 at the sung line's size, 0 at the others', in between while the line animates. */
    private static float glow() throws ReflectiveOperationException {
        Object lambda = line;
        String name = scaleField();
        if (lambda == null || name.isEmpty()) return 0f;
        Field field = lambda.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object state = field.get(lambda);
        if (state == null) return 0f;
        Object value = state.getClass().getMethod("getValue").invoke(state);
        if (!(value instanceof Number)) return 0f;
        float scale = ((Number) value).floatValue();
        float low = LyricsColors.inactiveScale();
        return Math.max(0f, Math.min(1f, (scale - low) / (1f - low)));
    }

    private static void find(Class<?> styleClass) throws ReflectiveOperationException {
        Class<?> weightClass = Class.forName("androidx.compose.ui.text.font.FontWeight");
        Class<?> shadowClass = Class.forName("androidx.compose.ui.graphics.Shadow");
        for (Method method : styleClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != styleClass) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 10 || types[0] != styleClass || types[types.length - 1] != int.class) continue;
            int weight = -1;
            int shadow = -1;
            for (int i = 1; i < types.length; i++) {
                if (types[i] == weightClass) weight = i;
                if (types[i] == shadowClass) shadow = i;
            }
            if (weight < 0 || shadow < 0) continue;
            method.setAccessible(true);
            copy = method;
            weightIndex = weight;
            shadowIndex = shadow;
            break;
        }
        if (copy == null) throw new NoSuchMethodException("TextStyle copy");
        bold = fontWeight(weightClass, BOLD);
        for (Constructor<?> constructor : shadowClass.getDeclaredConstructors()) {
            Class<?>[] p = constructor.getParameterTypes();
            if (p.length < 3) continue;
            if (p[0] == float.class && p[1] == long.class && p[2] == long.class) {
                shadowColorFirst = false;
            } else if (p[0] == long.class && p[1] == long.class && p[2] == float.class) {
                shadowColorFirst = true;
            } else {
                continue;
            }
            constructor.setAccessible(true);
            shadowConstructor = constructor;
            break;
        }
        if (shadowConstructor == null) throw new NoSuchMethodException("Shadow constructor");
    }

    /** FontWeight for a weight: one of its constants when one matches, else a new one. */
    private static Object fontWeight(Class<?> weightClass, int weight) throws ReflectiveOperationException {
        String wanted = "weight=" + weight + ")";
        for (Field field : weightClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != weightClass) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value != null && value.toString().endsWith(wanted)) return value;
        }
        Constructor<?> constructor = weightClass.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(weight);
    }

    /** A white glow around the text, centred on it. */
    private static Object shadow(float glow) throws ReflectiveOperationException {
        int alpha = Math.round(0xD9 * glow);
        long color = ((long) ((alpha << 24) | 0x00FFFFFF) & 0xFFFFFFFFL) << 32;
        long offset = 0L;
        float radius = GLOW_RADIUS * glow;
        Class<?>[] p = shadowConstructor.getParameterTypes();
        Object[] args = new Object[p.length];
        for (int i = 3; i < p.length; i++) args[i] = zero(p[i]);
        if (shadowColorFirst) {
            args[0] = color;
            args[1] = offset;
            args[2] = radius;
        } else {
            args[0] = radius;
            args[1] = color;
            args[2] = offset;
        }
        return shadowConstructor.newInstance(args);
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == boolean.class) return false;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        return (char) 0;
    }
}
