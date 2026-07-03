package dev.cnnnr;

import lombok.Getter;

/**
 * A single particle in local scene coordinates (128 units per tile).
 * z is the absolute scene height (negative up), anchored to the player's base
 * height at spawn so particles stay glued to the model on uneven ground.
 * Purely simulation state; rendering is done by {@link ParticleRenderer}
 * batching live particles into per-tile models.
 */
class Particle
{
	@Getter
	private float x;
	@Getter
	private float y;
	@Getter
	private float z;

	private final float velX;
	private final float velY;
	private final float velZ;
	private final float lifetime;
	// Sinusoidal drift so motion meanders instead of travelling straight
	private final float wobblePhase;
	private final float wobbleFreq;
	private final float wobbleAmp;
	private float age;

	/**
	 * The style this particle renders with.
	 */
	@Getter
	private final ParticleStyle style;

	/**
	 * Which pre-baked size variant of the style this particle renders with.
	 */
	@Getter
	private final int sizeVariant;

	Particle(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant,
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
		this.wobblePhase = wobblePhase;
		this.wobbleFreq = wobbleFreq;
		this.wobbleAmp = wobbleAmp;
	}

	void update(float dt)
	{
		age += dt;
		float t = age * wobbleFreq + wobblePhase;
		x += (velX + (float) Math.sin(t) * wobbleAmp) * dt;
		y += (velY + (float) Math.cos(t * 1.3f) * wobbleAmp) * dt;
		z += velZ * dt;
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
