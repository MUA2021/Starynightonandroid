package app.spicetify.extension.spotify.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import java.util.Random;

/**
 * An animated night sky in the style of the StarryNight desktop theme: a black-to-navy gradient,
 * scattered stars, some twinkling, and shooting stars crossing now and then. The still stars are drawn
 * once into a bitmap; each frame adds only the twinkling stars and the shooting stars, at about 30
 * frames a second, and nothing runs while the view isn't visible.
 */
final class StarrySky extends Drawable implements Runnable {
    private static final int TOP = 0xFF000000;
    private static final int BOTTOM = 0xFF142B44;
    private static final long FRAME_MS = 33;
    private static final int SHOOTING_STARS = 4;
    /** One star for every this many square dp of screen, as StarryNight uses per CSS pixel. */
    private static final float DP2_PER_STAR = 4000f;

    private final float density;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap still;
    private float[] twinkleX = new float[0];
    private float[] twinkleY = new float[0];
    private float[] twinkleR = new float[0];
    private long[] twinkleOffset = new long[0];
    private final Shooting[] shooting = new Shooting[SHOOTING_STARS];
    private boolean running = true;
    /** What this layer draws: the gradient and the stars, the gradient alone, or the stars alone. */
    static final int FULL = 0, SKY = 1, STARS = 2;
    private final int mode;
    /** Over every screen: navy that blacks and dark greys take on, while white stays white. */
    private static final int TINT_TOP = 0xFF0B1626;
    private static final int TINT_BOTTOM = 0xFF142B44;
    private final boolean tint;
    private final Paint tintPaint = new Paint();
    /** Stars drawn over Spotify's pages are a little dimmer, so text stays easy to read. */
    private static final float OVERLAY_BRIGHTNESS = 0.7f;

    private static final class Shooting {
        float startX, startY, dx, dy;
        long start, duration;
    }

    StarrySky(float density) {
        this(density, FULL);
    }

    StarrySky(float density, int mode) {
        this(density, mode, false);
    }

    StarrySky(float density, int mode, boolean tint) {
        this.density = density;
        this.mode = mode;
        this.tint = tint && mode == STARS;
        // Screen blending lightens what's below by the navy: black becomes navy, white stays white.
        tintPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        if (mode == SKY) running = false;
        paint.setColor(0xFFFFFFFF);
        glow.setColor(0xFFFFFFFF);
        glow.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < SHOOTING_STARS; i++) shooting[i] = new Shooting();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        int width = bounds.width();
        int height = bounds.height();
        if (still != null) {
            still.recycle();
            still = null;
        }
        if (width <= 0 || height <= 0) return;
        tintPaint.setShader(new LinearGradient(0, 0, 0, height, TINT_TOP, TINT_BOTTOM, Shader.TileMode.CLAMP));
        still = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(still);
        if (mode != STARS) {
            Paint sky = new Paint();
            sky.setShader(new LinearGradient(0, 0, 0, height, TOP, BOTTOM, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, sky);
        }
        if (mode == SKY) return;
        float dim = mode == STARS ? OVERLAY_BRIGHTNESS : 1f;

        float areaDp = (width / density) * (height / density);
        int count = Math.max(20, Math.round(areaDp / DP2_PER_STAR));
        int twinkling = 0;
        float[] tx = new float[count];
        float[] ty = new float[count];
        float[] tr = new float[count];
        Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
        star.setColor(0xFFFFFFFF);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * width;
            float y = random.nextFloat() * height;
            float radius = (random.nextBoolean() ? 0.5f : 1f) * density;
            if (random.nextFloat() < 0.2f) {
                tx[twinkling] = x;
                ty[twinkling] = y;
                tr[twinkling] = radius;
                twinkling++;
            } else {
                star.setAlpha((int) ((128 + random.nextInt(128)) * dim));
                canvas.drawCircle(x, y, radius, star);
            }
        }
        twinkleX = new float[twinkling];
        twinkleY = new float[twinkling];
        twinkleR = new float[twinkling];
        twinkleOffset = new long[twinkling];
        System.arraycopy(tx, 0, twinkleX, 0, twinkling);
        System.arraycopy(ty, 0, twinkleY, 0, twinkling);
        System.arraycopy(tr, 0, twinkleR, 0, twinkling);
        for (int i = 0; i < twinkling; i++) twinkleOffset[i] = random.nextInt(5000);
        long now = SystemClock.uptimeMillis();
        for (Shooting s : shooting) launch(s, now, width, height);
    }

    /** Sets a shooting star off after a random wait, from the top edge or the right edge, heading down-left. */
    private void launch(Shooting s, long now, int width, int height) {
        if (random.nextFloat() < 0.75f) {
            s.startX = width * (0.1f + random.nextFloat() * 0.9f);
            s.startY = 0;
        } else {
            s.startX = width;
            s.startY = height * random.nextFloat() * 0.5f;
        }
        float distance = Math.max(width, height) * 0.8f;
        s.dx = -distance * 0.7071f;
        s.dy = distance * 0.7071f;
        s.duration = 3000 + random.nextInt(3000);
        s.start = now + random.nextInt(7000);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (still == null) {
            if (mode != STARS) canvas.drawColor(TOP);
            return;
        }
        if (tint) {
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), tintPaint);
            canvas.restore();
        }
        canvas.drawBitmap(still, bounds.left, bounds.top, null);
        // The gradient alone never changes, so it isn't redrawn.
        if (mode == SKY) return;
        float dim = mode == STARS ? OVERLAY_BRIGHTNESS : 1f;
        long now = SystemClock.uptimeMillis();

        for (int i = 0; i < twinkleX.length; i++) {
            // A 5-second cycle, like the theme's twinkle keyframes: bright, dim, bright.
            double phase = ((now + twinkleOffset[i]) % 5000) / 5000.0 * 2 * Math.PI;
            int alpha = (int) (60 + 195 * (0.5 + 0.5 * Math.cos(phase)));
            paint.setAlpha((int) (alpha * dim));
            canvas.drawCircle(bounds.left + twinkleX[i], bounds.top + twinkleY[i], twinkleR[i], paint);
        }

        float tail = 80 * density;
        for (Shooting s : shooting) {
            if (now < s.start) continue;
            float progress = (now - s.start) / (float) s.duration;
            if (progress >= 1f) {
                launch(s, now, bounds.width(), bounds.height());
                continue;
            }
            float x = bounds.left + s.startX + s.dx * progress;
            float y = bounds.top + s.startY + s.dy * progress;
            // Fades in, then out, like the theme's shooting star.
            float fade = progress < 0.1f ? progress / 0.1f : progress > 0.7f ? (1f - progress) / 0.3f : 1f;
            float length = (float) Math.hypot(s.dx, s.dy);
            float ux = s.dx / length;
            float uy = s.dy / length;
            glow.setShader(new LinearGradient(x, y, x - ux * tail, y - uy * tail,
                    0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            glow.setAlpha((int) (255 * fade * dim));
            glow.setStrokeWidth(1.5f * density);
            canvas.drawLine(x, y, x - ux * tail, y - uy * tail, glow);
            glow.setShader(null);
            paint.setAlpha((int) (255 * fade * dim));
            canvas.drawCircle(x, y, 2f * density, paint);
            paint.setAlpha((int) (40 * fade * dim));
            canvas.drawCircle(x, y, 6f * density, paint);
        }
        paint.setAlpha(255);
        if (running) scheduleSelf(this, now + FRAME_MS);
    }

    @Override
    public void run() {
        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (mode == SKY) return changed;
        running = visible;
        if (visible) {
            invalidateSelf();
        } else {
            unscheduleSelf(this);
        }
        return changed;
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter colorFilter) {}

    @Override
    public int getOpacity() {
        return mode == STARS ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }
}
