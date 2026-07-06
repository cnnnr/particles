package dev.cnnnr;

import lombok.Getter;

/**
 * A single particle in local scene coordinates (128 units per tile).
 * z is the absolute scene height (negative up), anchored to the player's base
 * height at spawn so particles stay glued to the model on uneven ground.
 * Purely simulation state; rendering is done by {@link ParticleRenderer}
 * batching live particles into per-tile models.
 *
 * Instances are pooled by {@link ParticleSystem} and reinitialized via
 * {@link #reset}, so fields are mutable by design.
 */
class Particle
{
	@Getter
	private float x;
	@Getter
	private float y;
	@Getter
	private float z;

	@Getter
	private float velX;
	@Getter
	private float velY;
	@Getter
	private float velZ;
	private float lifetime;
	// Sinusoidal drift so motion meanders instead of travelling straight
	private float wobblePhase;
	private float wobbleFreq;
	private float wobbleAmp;
	private float age;

	/**
	 * The style this particle renders with.
	 */
	@Getter
	private ParticleStyle style;

	/**
	 * Which pre-baked size variant of the style this particle renders with.
	 */
	@Getter
	private int sizeVariant;

	/**
	 * Per-particle uniform size scale from the profile's size jitter (1 = the
	 * base size), applied on top of the {@link #sizeVariant} auto-variation.
	 */
	@Getter
	private float sizeScale;

	void reset(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant, float sizeScale,
		float wobblePhase, float wobbleFreq, float wobbleAmp)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.velX = velX;
		this.velY = velY;
		this.velZ = velZ;
		this.lifetime = lifetime;
		this.style = style;
		this.sizeVariant = sizeVariant;
		this.sizeScale = sizeScale;
		this.wobblePhase = wobblePhase;
		this.wobbleFreq = wobbleFreq;
		this.wobbleAmp = wobbleAmp;
		this.age = 0;
	}

	void update(float dt)
	{
		age += dt;
		// Gravity: downward acceleration on the vertical velocity (scene z is
		// negative-up, so falling means an increasing z). Constant when 0.
		velZ += style.getGravity() * dt;
		// Drag: shed a fraction of the particle's own velocity so rises,
		// spreads and falls settle over life. With gravity this converges on a
		// terminal fall speed. Wind (below) is a separate steady drift and is
		// not decayed - it is the medium moving, not the particle's momentum.
		float drag = style.getDragPerSec();
		if (drag > 0f)
		{
			float keep = Math.max(0f, 1f - drag * dt);
			velX *= keep;
			velY *= keep;
			velZ *= keep;
		}
		float t = age * wobbleFreq + wobblePhase;
		x += (velX + style.getWindX() + (float) Math.sin(t) * wobbleAmp) * dt;
		y += (velY + style.getWindY() + (float) Math.cos(t * 1.3f) * wobbleAmp) * dt;
		// Wind Z is up-positive like Rise; scene z is negative-up, so subtract.
		z += (velZ - style.getWindZ()) * dt;
	}

	boolean isDead()
	{
		return age >= lifetime;
	}

	void kill()
	{
		age = lifetime;
	}

	/**
	 * @return remaining life as 1 (just spawned) to 0 (dead)
	 */
	float lifeFraction()
	{
		return Math.max(0f, 1f - age / lifetime);
	}
}
