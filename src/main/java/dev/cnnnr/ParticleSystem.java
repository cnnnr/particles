package dev.cnnnr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * Holds and integrates live particles. Dead particles are recycled through a
 * pool, and removal is swap-with-last, so steady-state updates allocate
 * nothing. Particle order is not meaningful. All access is on the client
 * thread.
 */
class ParticleSystem
{
	// Backstop only; the real budget is the maxParticles config, enforced at emission
	private static final int MAX_PARTICLES = 8192;

	@Getter
	private final List<Particle> particles = new ArrayList<>();
	private final Deque<Particle> pool = new ArrayDeque<>();

	@Nullable
	Particle spawn(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant,
		float wobblePhase, float wobbleFreq, float wobbleAmp)
	{
		if (particles.size() >= MAX_PARTICLES)
		{
			return null;
		}
		Particle p = pool.pollFirst();
		if (p == null)
		{
			p = new Particle();
		}
		p.reset(x, y, z, velX, velY, velZ,
			lifetime, style, sizeVariant, wobblePhase, wobbleFreq, wobbleAmp);
		particles.add(p);
		return p;
	}

	void update(float dt, Consumer<Particle> onDeath)
	{
		for (int i = particles.size() - 1; i >= 0; i--)
		{
			Particle p = particles.get(i);
			p.update(dt);
			if (p.isDead())
			{
				onDeath.accept(p);
				int last = particles.size() - 1;
				particles.set(i, particles.get(last));
				particles.remove(last);
				pool.addFirst(p);
			}
		}
	}

	void clear(Consumer<Particle> onDeath)
	{
		for (Particle p : particles)
		{
			onDeath.accept(p);
			pool.addFirst(p);
		}
		particles.clear();
	}
}
