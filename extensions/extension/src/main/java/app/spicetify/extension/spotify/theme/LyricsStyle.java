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
    /** Android blurs text shadows up to about 25px; well under that keeps the glow on the letters. */
    private static final float GLOW_RADIUS = 12f;
    /** The glow's strongest opacity: soft, so the white text stays crisp on top of it. */
    private static final int GLOW_ALPHA = 0xA6;
    /** Off for the whole line: see restyle. The sung words glow instead, in glowSpan. */
    private static final boolean GLOW = false;
    /** SpanStyle's constructor parameters, in Compose's order: shadow is the 14th of 16. */
    private static final int SPAN_SHADOW_BIT = 1 << 13;
    private static final int SPAN_ALL_DEFAULT = (1 << 16) - 1;

    private static Object line;
    private static Method copy;
    private static int weightIndex = -1;
    private static int shadowIndex = -1;
    private static Object bold;
    private static Constructor<?> shadowConstructor;
    private static boolean shadowColorFirst;
    private static boolean broken;
    private static Constructor<?> spanConstructor;
    private static Method spanMerge;
    private static int spanShadowIndex = -1;
    private static int spanMaskIndex = -1;
    private static boolean spanBroken;

    private LyricsStyle() {}

    /** The lyrics line's scale field; the patch makes this return its name. */
    static String scaleField() {
        return "";
    }

    /** The lyrics line about to style its text, for the scale of its sung-line animation. */
    public static void setLine(Object lambda) {
        line = lambda;
    }

    /**
     * The sung words' style, glowing. Spotify fills a line word by word with two spans, the sung words
     * first, so a glow on that span lights up only the letters already sung, the way Spicy Lyrics does,
     * instead of the whole line's layer, which Android glows as a box. Merged in through the span's own
     * merge, so the span keeps its color and everything else.
     */
    public static Object glowSpan(Object span) {
        if (spanBroken || span == null || !LyricsColors.styled()) return span;
        try {
            if (spanConstructor == null) findSpan(span.getClass());
            float glow = glow();
            if (glow <= 0.01f) return span;
            Class<?>[] types = spanConstructor.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) args[i] = zero(types[i]);
            args[spanShadowIndex] = shadow(glow);
            args[spanMaskIndex] = SPAN_ALL_DEFAULT & ~SPAN_SHADOW_BIT;
            Object glowing = spanConstructor.newInstance(args);
            return spanMerge.invoke(span, glowing);
        } catch (Throwable e) {
            spanBroken = true;
            Log.w(TAG, "Lyrics glow unchanged", e);
            return span;
        }
    }

    private static void findSpan(Class<?> spanClass) throws ReflectiveOperationException {
        Class<?> shadowClass = Class.forName("androidx.compose.ui.graphics.Shadow");
        findShadow(shadowClass);
        for (Constructor<?> constructor : spanClass.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length < 10 || types[0] != long.class) continue;
            int mask = types[types.length - 1] == int.class ? types.length - 1
                    : types.length > 1 && types[types.length - 2] == int.class && !types[types.length - 1].isPrimitive()
                    ? types.length - 2 : -1;
            if (mask < 0) continue;
            int shadow = -1;
            for (int i = 0; i < types.length; i++) if (types[i] == shadowClass) shadow = i;
            if (shadow < 0) continue;
            constructor.setAccessible(true);
            spanConstructor = constructor;
            spanShadowIndex = shadow;
            spanMaskIndex = mask;
            break;
        }
        if (spanConstructor == null) throw new NoSuchMethodException("SpanStyle constructor");
        for (Method method : spanClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != spanClass) continue;
            if (types.length != 1 || types[0] != spanClass) continue;
            method.setAccessible(true);
            spanMerge = method;
            break;
        }
        if (spanMerge == null) throw new NoSuchMethodException("SpanStyle merge");
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
            // Spotify draws the sung line as one layer for its word-by-word fill, and Android glows a
            // layer's whole rectangle, not its letters: the glow would be a box, so it's left off.
            if (GLOW && glow > 0.01f) {
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
        findShadow(shadowClass);

    }

    private static void findShadow(Class<?> shadowClass) throws NoSuchMethodException {
        if (shadowConstructor != null) return;
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
        int alpha = Math.round(GLOW_ALPHA * glow);
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
