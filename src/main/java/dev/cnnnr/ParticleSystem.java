package dev.cnnnr;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * Holds and integrates live particles. All access is on the client thread.
 */
class ParticleSystem
{
	// Backstop only; the real budget is the maxParticles config, enforced at emission
	private static final int MAX_PARTICLES = 8192;

	@Getter
	private final List<Particle> particles = new ArrayList<>();

	@Nullable
	Particle spawn(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant,
		float wobblePhase, float wobbleFreq, float wobbleAmp)
	{
		if (particles.size() >= MAX_PARTICLES)
		{
			return null;
		}
		Particle p = new Particle(x, y, z, velX, velY, velZ,
			lifetime, style, sizeVariant, wobblePhase, wobbleFreq, wobbleAmp);
		particles.add(p);
		return p;
	}

	void update(float dt, Consumer<Particle> onDeath)
	{
		for (Iterator<Particle> it = particles.iterator(); it.hasNext(); )
		{
			Particle p = it.next();
			p.update(dt);
			if (p.isDead())
			{
				onDeath.accept(p);
				it.remove();
			}
		}
	}

	void clear(Consumer<Particle> onDeath)
	{
		particles.forEach(onDeath);
		particles.clear();
	}
}
