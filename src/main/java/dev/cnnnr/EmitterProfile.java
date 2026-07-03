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
	static final String TARGET_PLAYER = "player";
	static final String TARGET_PROJECTILE = "projectile";

	private String name;
	/**
	 * What this profile emits from: a player model piece or a projectile.
	 */
	private String targetType = TARGET_PLAYER;
	/**
	 * Projectile (graphic) ID for projectile profiles; -1 otherwise.
	 */
	private int projectileId = -1;
	/**
	 * Topology signature of the mesh piece this profile attaches to. Multiple
	 * profiles may share a signature (e.g. one per recolored item variant).
	 * Null for non-player targets.
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
	 * Optional frame windows within a matched action animation, e.g.
	 * "9-13, 15-19" or "7"; blank = all frames. Ignored for pose animation
	 * matches.
	 */
	private String animFrames = "";
	/**
	 * Legacy single frame window, migrated into {@link #animFrames} on load.
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
	/**
	 * Particles per tile of emitter movement, spread evenly along each
	 * anchor's path since the last tick. Spatially uniform emission for
	 * ribbon-like weapon trails; 0 = off. Combine with rate 0 for a pure
	 * trail that only emits while the anchor moves.
	 */
	private int trailDensity = 0;
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
	 * Feathered emission strength: spawn along a line chained through the
	 * emitter vertices, smoothed by averaging each point over this many
	 * neighbors to either side. 0 = off, 1 = light rounding, higher values
	 * cut across jagged notches into a soft continuous band.
	 */
	private int featherStrength = 0;
	/**
	 * Legacy on/off flag, migrated into {@link #featherStrength} on load.
	 */
	private boolean feather = false;
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
		c.targetType = targetType;
		c.projectileId = projectileId;
		c.signature = signature;
		c.enabled = enabled;
		c.vertices = new HashSet<>(vertices);
		c.copyStyleFrom(this);
		return c;
	}

	boolean isProjectileTarget()
	{
		return TARGET_PROJECTILE.equals(targetType);
	}

	/**
	 * Copy the editable settings: style plus the item variant filter.
	 */
	void copyStyleFrom(EmitterProfile other)
	{
		color = other.color;
		size = other.size;
		particlesPerSecond = other.particlesPerSecond;
		trailDensity = other.trailDensity;
		lifetimeMs = other.lifetimeMs;
		riseSpeed = other.riseSpeed;
		spreadSpeed = other.spreadSpeed;
		spawnJitter = other.spawnJitter;
		featherStrength = other.featherStrength;
		offsetX = other.offsetX;
		offsetY = other.offsetY;
		offsetZ = other.offsetZ;
		itemIds = new HashSet<>(other.itemIds);
		animationIds = new HashSet<>(other.animationIds);
		animFrames = other.animFrames;
	}
}
