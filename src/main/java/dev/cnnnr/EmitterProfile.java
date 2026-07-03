package dev.cnnnr;

import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * A saved set of emitter vertices plus particle style for one mesh piece,
 * identified by its topology signature. Vertices are piece-local indices
 * (rank within the piece), not global composite-model indices.
 */
@Getter
@Setter
class EmitterProfile
{
	private String name;
	/**
	 * Topology signature of the mesh piece this profile attaches to. Multiple
	 * profiles may share a signature (e.g. one per recolored item variant).
	 */
	private String signature;
	private boolean enabled = true;
	private Set<Integer> vertices = new HashSet<>();
	/**
	 * Item IDs gating this profile: only active while one of these items is
	 * worn. Empty = active on any item with this mesh. Distinguishes recolored
	 * variants that share the same model.
	 */
	private Set<Integer> itemIds = new HashSet<>();
	/**
	 * Animation IDs gating this profile: only emit while the player's action
	 * or pose animation is one of these. Empty = no animation gate. Combines
	 * with the item gate (both must pass).
	 */
	private Set<Integer> animationIds = new HashSet<>();
	/**
	 * Optional frame window within a matched action animation; -1 = unbounded.
	 * Ignored for pose animation matches.
	 */
	private int animFrameStart = -1;
	private int animFrameEnd = -1;

	// Particle style, editable per piece in the vertex picker
	/**
	 * Particle color as ARGB; alpha is the peak opacity of the life envelope.
	 */
	private int color = 0x96FF981F;
	/**
	 * Particle diameter in local units (a tile is 128).
	 */
	private int size = 6;
	private int particlesPerSecond = 60;
	private int lifetimeMs = 600;
	/**
	 * Upward drift in local units per second.
	 */
	private int riseSpeed = 12;
	/**
	 * Horizontal drift and wobble speed in local units per second.
	 */
	private int spreadSpeed = 6;
	/**
	 * Random spawn offset around the emitter vertex in local units.
	 */
	private int spawnJitter = 6;
	/**
	 * Fixed emit offset from the vertex in model space (rotates with the
	 * player's facing), local units. X is sideways, Y is forward/back,
	 * Z is up (positive).
	 */
	private int offsetX = 0;
	private int offsetY = 0;
	private int offsetZ = 0;

	EmitterProfile()
	{
	}

	EmitterProfile(String name)
	{
		this.name = name;
	}

	EmitterProfile copy()
	{
		EmitterProfile c = new EmitterProfile(name);
		c.signature = signature;
		c.enabled = enabled;
		c.vertices = new HashSet<>(vertices);
		c.copyStyleFrom(this);
		return c;
	}

	/**
	 * Copy the editable settings: style plus the item variant filter.
	 */
	void copyStyleFrom(EmitterProfile other)
	{
		color = other.color;
		size = other.size;
		particlesPerSecond = other.particlesPerSecond;
		lifetimeMs = other.lifetimeMs;
		riseSpeed = other.riseSpeed;
		spreadSpeed = other.spreadSpeed;
		spawnJitter = other.spawnJitter;
		offsetX = other.offsetX;
		offsetY = other.offsetY;
		offsetZ = other.offsetZ;
		itemIds = new HashSet<>(other.itemIds);
		animationIds = new HashSet<>(other.animationIds);
		animFrameStart = other.animFrameStart;
		animFrameEnd = other.animFrameEnd;
	}
}
