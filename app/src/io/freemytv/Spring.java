package io.freemytv;

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
final class Spring {
    float value, velocity, target;
    private final float response, damping;

    /**
     * Every spring in the app, 28% quicker than its own response says: the same curves and bounce (damping unchanged),
     * just faster, so focus, the dock, the drawer, All Apps and the tabs all speed up together. 1 = as designed.
     */
    static final float SPEED = 0.72f;

    Spring(float response, float damping, float initial) {
        this.response = response * SPEED;
        this.damping = damping;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        // Android's "Remove animations" (animator duration scale 0): straight to the end, no motion.
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            value = target;
            velocity = 0;
            return false;
        }
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        float omega = (float) (2 * Math.PI / response);
        float stiffness = omega * omega, friction = 2 * damping * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.004f;
    }
}
