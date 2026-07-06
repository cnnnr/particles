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
	static final String TARGET_OBJECT = "object";
	static final String TARGET_NPC = "npc";
	static final String TARGET_GRAPHIC = "graphic";

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
	 * Scenery object ID for object profiles; -1 otherwise. Object profiles
	 * also carry a piece signature and vertices - the ID says which object,
	 * the signature says which piece of its model.
	 */
	private int objectId = -1;
	/**
	 * NPC ID for NPC profiles; -1 otherwise. Like objects: ID plus piece
	 * signature plus vertices.
	 */
	private int npcId = -1;
	/**
	 * Spot anim / graphics object ID for graphic profiles; -1 otherwise.
	 * Point-based: emits at any graphics object with this ID and on any
	 * actor playing it as a spot anim. No vertices.
	 */
	private int graphicId = -1;
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
	 * Particle silhouette, baked as a per-face alpha mask over the shared
	 * disc geometry. Null on profiles saved before shapes existed, migrated
	 * to DEFAULT on load.
	 */
	private Shape shape = Shape.DEFAULT;
	/**
	 * Particle diameter in local units (a tile is 128).
	 */
	private int size = 6;
	private int particlesPerSecond = 24;
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
	 * Downward acceleration in local units per second squared; 0 = constant
	 * velocity. Turns drift into falling: drops start slow and speed up, the
	 * signature of a blood or water drip.
	 */
	private int gravity = 0;
	/**
	 * Elongate the particle this percent along its screen-projected velocity
	 * (0 = round). Makes fast drops read as falling streaks. Auto-capped so
	 * the stretched billboard stays inside the render bounds.
	 */
	private int stretch = 0;
	/**
	 * How much of the particle's life it holds its round shape before
	 * stretching to the full amount (0 = stretched the whole time; 100 =
	 * starts round and elongates late, like a droplet letting go). The ramp
	 * is late-biased over the remaining life.
	 */
	private int stretchRamp = 0;
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
	 * Extra emitter points inserted between each pair of mesh-adjacent
	 * picked vertices (1 roughly doubles the emitter count). Densifies
	 * emission on low-poly meshes without feathering's smoothing.
	 */
	private int interpolation = 0;
	/**
	 * Nudge particles this many local units toward the camera at render
	 * time. Garment faces that win by render priority (a cape over a skirt)
	 * would otherwise swallow particles by plain depth; a small bias lets
	 * particles ride on top the way their host piece does.
	 */
	private int depthBias = 0;
	/**
	 * Percent of normal particle lifetime while the wearer's walk or run
	 * pose animation is playing (10-100). Shortening it keeps fast-moving
	 * plumes tight instead of smearing a tile behind the player.
	 */
	private int movementLifetime = 100;
	/**
	 * Legacy flag, migrated into {@link #movementLifetime} on load.
	 */
	private boolean dynamicLifetime = false;
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
		c.objectId = objectId;
		c.npcId = npcId;
		c.graphicId = graphicId;
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

	boolean isObjectTarget()
	{
		return TARGET_OBJECT.equals(targetType);
	}

	boolean isNpcTarget()
	{
		return TARGET_NPC.equals(targetType);
	}

	boolean isGraphicTarget()
	{
		return TARGET_GRAPHIC.equals(targetType);
	}

	/**
	 * Copy the editable settings: style plus the item variant filter.
	 */
	void copyStyleFrom(EmitterProfile other)
	{
		color = other.color;
		shape = other.shape;
		size = other.size;
		particlesPerSecond = other.particlesPerSecond;
		trailDensity = other.trailDensity;
		lifetimeMs = other.lifetimeMs;
		riseSpeed = other.riseSpeed;
		spreadSpeed = other.spreadSpeed;
		gravity = other.gravity;
		stretch = other.stretch;
		stretchRamp = other.stretchRamp;
		spawnJitter = other.spawnJitter;
		featherStrength = other.featherStrength;
		interpolation = other.interpolation;
		depthBias = other.depthBias;
		movementLifetime = other.movementLifetime;
		offsetX = other.offsetX;
		offsetY = other.offsetY;
		offsetZ = other.offsetZ;
		itemIds = new HashSet<>(other.itemIds);
		animationIds = new HashSet<>(other.animationIds);
		animFrames = other.animFrames;
	}
}
