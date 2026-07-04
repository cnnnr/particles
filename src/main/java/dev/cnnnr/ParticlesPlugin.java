package dev.cnnnr;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Particles",
	description = "Cosmetic particle effects for capes, halos, etc.",
	tags = {"particles", "cosmetic", "effects"}
)
public class ParticlesPlugin extends Plugin implements ModelViewerFrame.Callbacks
{
	/**
	 * One enabled emitter profile resolved onto the current model: its style,
	 * global vertex indices, spawn accumulator, and its slice of the shared
	 * anchor arrays.
	 */
	private static class ActiveEmitter
	{
		final ParticleStyle style;
		final int[] vertices;
		/**
		 * Feathering: emitter vertices chained along mesh edges, as anchor
		 * offsets within this emitter; null when feathering is off.
		 */
		final int[][] chains;
		/**
		 * O(1) uniform sampling over all chain positions.
		 */
		final int[] sampleChainOf;
		final int[] samplePosOf;
		double carry;
		int anchorStart;
		int anchorCount;

		ActiveEmitter(ParticleStyle style, int[] vertices, int[][] chains)
		{
			this.style = style;
			this.vertices = vertices;
			this.chains = chains;
			if (chains != null)
			{
				int total = 0;
				for (int[] chain : chains)
				{
					total += chain.length;
				}
				sampleChainOf = new int[total];
				samplePosOf = new int[total];
				int k = 0;
				for (int c = 0; c < chains.length; c++)
				{
					for (int j = 0; j < chains[c].length; j++)
					{
						sampleChainOf[k] = c;
						samplePosOf[k] = j;
						k++;
					}
				}
			}
			else
			{
				sampleChainOf = null;
				samplePosOf = null;
			}
		}
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ParticlesConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ParticlesOverlay overlay;

	/**
	 * Preview the shipped experience from a developer client: treats
	 * developer mode as off everywhere (authoring UI, WIP category gating).
	 * The dev harness always launches with developer mode on, so this is the
	 * only way to see the player view locally. Safe to ship true by accident
	 * - players already run without developer mode.
	 */
	private static final boolean PREVIEW_PLAYER_VIEW = false;

	/**
	 * Authoring tools (vertex picker, profile edit controls) only exist in
	 * developer mode; shipped users get read-only presets with toggles.
	 */
	@Inject
	@Named("developerMode")
	private boolean developerMode;

	private final Random random = new Random();

	@Getter
	private final ParticleSystem particleSystem = new ParticleSystem();

	private ParticleRenderer renderer;
	private EmitterStore store;
	private NavigationButton navButton;
	private ParticlesPanel panel;
	private ModelViewerFrame viewerFrame;

	/**
	 * Per-player emitter state: resolution cache against that player's gear,
	 * transformed anchors, and trail history. Every player in the scene gets
	 * particles, not just the local one. Client thread.
	 */
	private static class PlayerEmitters
	{
		final List<ActiveEmitter> emitters = new ArrayList<>();
		int[] equipmentIds;
		int revision = -1;
		float[] anchorXs = new float[0];
		float[] anchorYs = new float[0];
		/**
		 * Absolute scene z (negative up), based on the same base height the
		 * engine renders the model at, so anchors stay glued on slopes.
		 */
		float[] anchorZs = new float[0];
		// Last tick's anchors: spawns spread along each anchor's movement
		// segment so fast-moving emitters lay a smooth trail, not clumps
		float[] prevXs = new float[0];
		float[] prevYs = new float[0];
		float[] prevZs = new float[0];
		/**
		 * Fractional carry of distance-based emission per anchor slot.
		 */
		float[] trailCarry = new float[0];
		/**
		 * Rate carries for point emission from the actor's active spot
		 * anims, by graphic ID. Lazily created; most actors have none.
		 */
		@Nullable
		Map<Integer, double[]> spotAnimCarries;
		int anchorCount;
		int prevCount;
		boolean rebuilt;
		int stamp;
	}

	private final Map<Player, PlayerEmitters> playerEmitters = new HashMap<>();
	private int playerStamp;
	/**
	 * Mirror of the engine's stacked-actor claim (tileLastDrawnActor): among
	 * players standing exactly at tile center, one draws per tile, with
	 * precedence local player -> the local player's player combat target ->
	 * size-1 centered NPCs -> remaining players by ascending player index.
	 * Field-validated with the hover-menu oracle (see updateStackOracle):
	 * idle stacks matched ascending Player.getId() consistently, and getId()
	 * agreed with the menu identifiers everywhere tested. This map holds the
	 * lowest-index centered player per tile; the precedence layers above it
	 * are applied in the drawn gate. Reused per tick.
	 */
	private final Map<Long, Player> tileOwners = new HashMap<>();
	/**
	 * Tiles claimed by a size-1 NPC standing at their center; the engine's
	 * NPC pass runs before non-local players, so those players aren't drawn.
	 */
	private final Set<Long> npcClaimedTiles = new HashSet<>();
	/**
	 * This tick's higher-precedence claims: the local player's tile beats
	 * everyone on it, and their player combat target's tile beats NPC claims
	 * and index order.
	 */
	private long localClaimKey = Long.MIN_VALUE;
	private long targetClaimKey = Long.MIN_VALUE;
	@Nullable
	private Player localClaimTarget;

	/**
	 * Per-instance emitter state for tracked scenery. Objects are static or
	 * engine-animated in place, so there is no trail, movement or stack
	 * state - just resolved emitters and this tick's anchors. Client thread.
	 */
	private static class ObjectEmitters
	{
		final List<ActiveEmitter> emitters = new ArrayList<>();
		float[] anchorXs = new float[0];
		float[] anchorYs = new float[0];
		float[] anchorZs = new float[0];
		int anchorCount;
		int revision = -1;
		boolean loggedResolveMiss;
	}

	/**
	 * Tracked scenery instances, maintained by spawn/despawn events plus a
	 * scene rescan when the profile store changes. Client thread.
	 */
	private final Map<TileObject, ObjectEmitters> objectEmitters = new HashMap<>();
	/**
	 * Object IDs that carry at least one object profile; spawns of anything
	 * else are ignored without allocation.
	 */
	private Set<Integer> profiledObjectIds = Set.of();
	private int profiledIdsRevision = -1;

	/**
	 * Tracked NPC instances; NPCs reuse the player emitter state since they
	 * move and animate the same way. Client thread.
	 */
	private final Map<NPC, PlayerEmitters> npcEmitters = new HashMap<>();
	private Set<Integer> profiledNpcIds = Set.of();

	/**
	 * One enabled graphic profile resolved for emission: point-based when no
	 * vertices were picked, otherwise anchored to the spot anim model's
	 * piece vertices. Graphic models are single cache models, so the global
	 * indices resolved from the first instance hold for every instance.
	 */
	private static class GraphicEmitter
	{
		final ParticleStyle style;
		@Nullable
		final String signature;
		final int[] locals;
		@Nullable
		int[] globals;
		boolean resolveTried;

		GraphicEmitter(ParticleStyle style, @Nullable String signature, int[] locals)
		{
			this.style = style;
			this.signature = signature;
			this.locals = locals;
		}
	}

	/**
	 * Enabled graphic profiles by spot anim ID: emission at graphics objects
	 * and on actors playing the spot anim. Client thread.
	 */
	private final Map<Integer, List<GraphicEmitter>> graphicEmitters = new HashMap<>();
	/**
	 * Rate carries per live graphics object, swept against the live set.
	 */
	private final Map<GraphicsObject, double[]> graphicCarries = new HashMap<>();
	private final Set<GraphicsObject> liveGraphics = new HashSet<>();
	/**
	 * Recently seen spot anim / graphics IDs for the capture list, as
	 * id -> [count, lastSeenMs].
	 */
	private final Map<Integer, long[]> recentGraphics = new LinkedHashMap<>();
	/**
	 * Graphic ID armed for deferred viewer capture: spell gfx are gone
	 * before Load can be clicked, so the mesh is grabbed the next time the
	 * graphic plays. -1 when idle. Client thread.
	 */
	private int pendingGraphicCapture = -1;

	// Debug marker aggregate across all players, read by the overlay; only
	// filled while markers are shown. Client thread.
	@Getter
	private int anchorCount;
	@Getter
	private float[] anchorXs = new float[0];
	@Getter
	private float[] anchorYs = new float[0];
	@Getter
	private float[] anchorZs = new float[0];
	@Getter
	private int anchorWorldView = -1;
	@Getter
	private int anchorLevel;
	/**
	 * Smoothed feather paths for the debug overlay, one flat [x,y,z, ...] per
	 * chain. Rebuilt per tick only while markers are shown.
	 */
	@Getter
	private final List<float[]> featherDebugPaths = new ArrayList<>();
	/**
	 * Segments longer than this are teleports/rebinds, not movement.
	 */
	private static final float MAX_TRAIL_SEGMENT = 256f;

	/**
	 * The last snapshot loaded into the viewer; vertex toggles resolve their
	 * piece against it. EDT only.
	 */
	private ModelSnapshot viewerSnapshot;

	/**
	 * The scenery object or NPC ID the viewer's snapshot was captured from,
	 * or -1 when it shows the player. Decides which profile family vertex
	 * clicks create. EDT only; snapshot captures receive them as parameters.
	 */
	private int viewerObjectId = -1;
	private int viewerNpcId = -1;
	private int viewerGraphicId = -1;
	/**
	 * Game name of the loaded object or NPC, resolved at capture time on the
	 * client thread; null when unavailable. New profiles are named after it.
	 * EDT only.
	 */
	@Nullable
	private String viewerTargetName;

	/**
	 * Piece signatures present on the currently worn model; written on the
	 * client thread, read by the sidebar for its "worn" indicator.
	 */
	private volatile Set<String> presentSignatures = Set.of();

	/**
	 * A projectile-targeted profile that passed its gear gate; matched
	 * against active projectiles by ID every tick.
	 */
	private static class ActiveProjectileProfile
	{
		final int projectileId;
		final ParticleStyle style;

		ActiveProjectileProfile(int projectileId, ParticleStyle style)
		{
			this.projectileId = projectileId;
			this.style = style;
		}
	}

	/**
	 * Per-projectile state: last tick's position for trail segments, and
	 * fractional emission carries per matched style.
	 */
	private static class ProjectileTracker
	{
		float prevX, prevY, prevZ;
		boolean prevValid;
		int stamp;
		final Map<ParticleStyle, double[]> carries = new HashMap<>();
	}

	// Projectile emission state; profile list is gear-gated by the local
	// player and rebuilt with their resolution. Client thread.
	private final List<ActiveProjectileProfile> activeProjectileProfiles = new ArrayList<>();
	private final Map<Projectile, ProjectileTracker> projectileTrackers = new HashMap<>();
	private int projectileStamp;
	/**
	 * Recently seen projectile IDs -> [count, lastSeenMs], for the picker's
	 * capture list. Client thread.
	 */
	private final LinkedHashMap<Integer, long[]> recentProjectiles = new LinkedHashMap<>();
	private int stylesRevision = -1;

	// Reused consumers; a method reference expression allocates per evaluation
	private final java.util.function.Consumer<Particle> deathStats = this::onParticleDeath;
	private static final java.util.function.Consumer<Particle> DISCARD = p ->
	{
	};

	private long lastNanos;
	private int lastLevel = -1;

	// Diagnostics, aggregated over one-second windows and shown by the overlay
	private long statsWindowStart;
	private int windowSpawns;
	private int windowDeaths;
	private float windowDeathAgeSum;
	/**
	 * Most recent non-idle action animation, sticky so short animations can be
	 * read off the stats line for authoring animation gates.
	 */
	private int lastActionAnimation = -1;
	@Getter
	private String statsLine = "";
	/**
	 * Stack-oracle diagnostic line for the hovered tile; empty outside
	 * developer mode. See {@link #updateStackOracle}.
	 */
	@Getter
	private String oracleLine = "";
	private String lastOracleDump = "";

	@Override
	protected void startUp() throws Exception
	{
		developerMode &= !PREVIEW_PLAYER_VIEW;
		lastNanos = System.nanoTime();
		stylesRevision = -1;
		playerEmitters.clear();
		// Migration: the Just me checkbox became the Apply to dropdown
		if ("true".equals(configManager.getConfiguration(ParticlesConfig.GROUP, "justMe"))
			&& configManager.getConfiguration(ParticlesConfig.GROUP, "applyTo") == null)
		{
			configManager.setConfiguration(ParticlesConfig.GROUP, "applyTo", ParticlesConfig.ApplyTo.ME);
		}
		configManager.unsetConfiguration(ParticlesConfig.GROUP, "justMe");

		renderer = new ParticleRenderer(client);
		store = new EmitterStore(configManager, gson);
		store.load();
		store.setChangeListener(this::refreshPanel);

		overlayManager.add(overlay);

		panel = new ParticlesPanel(developerMode, this::openViewer, store::setEnabled, store::setEnabledAll,
			store::delete, this::renameProfile, this::editProfile);
		navButton = NavigationButton.builder()
			.tooltip("Particles")
			.icon(createIcon())
			.priority(6)
			.panel(panel)
			.build();
		if (!config.hideSidePanel())
		{
			clientToolbar.addNavigation(navButton);
		}
		refreshPanel();

		log.debug("Particles started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;

		SwingUtilities.invokeLater(() ->
		{
			if (viewerFrame != null)
			{
				viewerFrame.dispose();
				viewerFrame = null;
			}
			viewerSnapshot = null;
		});

		// Capture the instance: a quick disable-enable reassigns the field
		// before this lambda runs, which would reset the NEW renderer and
		// orphan the old one's still-active scene objects
		final ParticleRenderer stopped = renderer;
		clientThread.invokeLater(() ->
		{
			particleSystem.clear(DISCARD);
			stopped.reset();
			anchorCount = 0;
			playerEmitters.clear();
			objectEmitters.clear();
			npcEmitters.clear();
			graphicCarries.clear();
			recentGraphics.clear();
			pendingGraphicCapture = -1;
			projectileTrackers.clear();
			activeProjectileProfiles.clear();
			recentProjectiles.clear();
			featherDebugPaths.clear();
		});

		log.debug("Particles stopped");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ParticlesConfig.GROUP.equals(event.getGroup()) || navButton == null)
		{
			return;
		}
		if ("hideSidePanel".equals(event.getKey()))
		{
			if (config.hideSidePanel())
			{
				clientToolbar.removeNavigation(navButton);
			}
			else
			{
				clientToolbar.addNavigation(navButton);
			}
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		trackObject(event.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		objectEmitters.remove(event.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		trackObject(event.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		objectEmitters.remove(event.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		trackObject(event.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		objectEmitters.remove(event.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		trackObject(event.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		objectEmitters.remove(event.getGroundObject());
	}

	private void trackObject(TileObject object)
	{
		if (profiledObjectIds.contains(object.getId()))
		{
			objectEmitters.putIfAbsent(object, new ObjectEmitters());
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		trackNpc(event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		npcEmitters.remove(event.getNpc());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		// Transmog changed the ID; drop state and re-evaluate tracking
		npcEmitters.remove(event.getNpc());
		trackNpc(event.getNpc());
	}

	private void trackNpc(NPC npc)
	{
		if (profiledNpcIds.contains(npc.getId()))
		{
			npcEmitters.putIfAbsent(npc, new PlayerEmitters());
		}
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		noteGraphic(event.getGraphicsObject().getId());
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		for (ActorSpotAnim spotAnim : event.getActor().getSpotAnims())
		{
			noteGraphic(spotAnim.getId());
		}
	}

	/**
	 * Record a spot anim / graphics ID sighting for the capture list,
	 * evicting the stalest entry past the cap.
	 */
	private void noteGraphic(int id)
	{
		long[] seen = recentGraphics.get(id);
		if (seen != null)
		{
			seen[0]++;
			seen[1] = System.currentTimeMillis();
			return;
		}
		if (recentGraphics.size() >= 24)
		{
			Integer oldestId = null;
			long oldestSeen = Long.MAX_VALUE;
			for (Map.Entry<Integer, long[]> entry : recentGraphics.entrySet())
			{
				if (entry.getValue()[1] < oldestSeen)
				{
					oldestSeen = entry.getValue()[1];
					oldestId = entry.getKey();
				}
			}
			recentGraphics.remove(oldestId);
		}
		recentGraphics.put(id, new long[]{1, System.currentTimeMillis()});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
				// The scene is rebasing; live particles' local coordinates
				// become meaningless, and scenery respawns with fresh events
				particleSystem.clear(DISCARD);
				renderer.reset();
				objectEmitters.clear();
				npcEmitters.clear();
				graphicCarries.clear();
				break;
			case LOGGED_IN:
				// Player models are rebuilt on scene load and their vertex
				// indices can change; re-resolve emitters against the new
				// models or emission goes subtly wrong until gear is re-equipped.
				// Invalidate only - the draw-order mirror must survive, since
				// these players never left the engine's processing list.
				invalidateResolutions();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		long now = System.nanoTime();
		float dt = (now - lastNanos) / 1_000_000_000f;
		lastNanos = now;
		// Clamp dt so hitches don't cause bursts or huge integration steps
		dt = Math.min(dt, 0.1f);

		// Rebuild styles when profiles change; batches pick the new templates
		// up immediately since they're re-merged every tick. Read the revision
		// BEFORE the snapshot: an edit landing in between then leaves
		// stylesRevision behind, forcing a clean rebuild next tick instead of
		// recording content it never saw
		int storeRevision = store.getRevision();
		if (stylesRevision != storeRevision && renderer.rebuildStyles(store.snapshotAll()))
		{
			stylesRevision = storeRevision;
			// Emitters hold style references; re-resolve everyone
			invalidateResolutions();
		}
		// Gate on renderer readiness so styles resolve on the first rebuild;
		// before the particle mesh loads, getStyle would return null forever
		if (renderer.isReady() && profiledIdsRevision != storeRevision)
		{
			profiledIdsRevision = storeRevision;
			rebuildProfiledObjects();
			rebuildProfiledNpcs();
			rebuildGraphicStyles();
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			anchorCount = 0;
			playerEmitters.clear();
			particleSystem.clear(DISCARD);
			renderer.reset();
			lastLevel = -1;
			return;
		}

		// Drop all particles when the scene plane changes; their positions
		// are only meaningful on the level they spawned on
		int level = localPlayer.getWorldView().getPlane();
		if (level != lastLevel)
		{
			particleSystem.clear(DISCARD);
			// Break trail continuity too: the inter-floor height (~240) is
			// under MAX_TRAIL_SEGMENT, so stale prev anchors would smear a
			// vertical streak between floors on the first tick up a staircase
			for (PlayerEmitters pe : playerEmitters.values())
			{
				pe.prevCount = 0;
				Arrays.fill(pe.trailCarry, 0f);
			}
			lastLevel = level;
		}
		anchorWorldView = localPlayer.getLocalLocation().getWorldView();
		anchorLevel = level;

		// Debug aggregates rebuilt during the player loop
		// Marker circles are a dev authoring aid; users only get the text line
		boolean markers = config.showAnchor() && developerMode;
		anchorCount = 0;
		featherDebugPaths.clear();

		// Stacked-actor claim mirror (tileLastDrawnActor /
		// tileLastOccupiedCycle): the engine's dedup only applies to actors
		// standing exactly at tile center - movers and the local player
		// always draw. The winner precedence below was field-validated with
		// the hover-menu oracle after the original deob-derived mirror
		// failed; see updateStackOracle.
		playerStamp++;
		tileOwners.clear();
		npcClaimedTiles.clear();

		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getWorldLocation().getPlane() != level)
			{
				continue;
			}
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null || composition.getSize() != 1)
			{
				continue;
			}
			LocalPoint lp = npc.getLocalLocation();
			if ((lp.getX() & 127) != 64 || (lp.getY() & 127) != 64)
			{
				continue;
			}
			npcClaimedTiles.add(((long) (lp.getX() >> 7) << 32) | ((lp.getY() >> 7) & 0xffffffffL));
		}
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null)
			{
				continue;
			}
			PlayerEmitters pe = playerEmitters.computeIfAbsent(player, p -> new PlayerEmitters());
			pe.stamp = playerStamp;

			if (player.getWorldLocation().getPlane() != level || !isCentered(player))
			{
				continue;
			}
			long key = tileKey(player);
			Player current = tileOwners.get(key);
			if (current == null || player.getId() < current.getId())
			{
				tileOwners.put(key, player);
			}
		}

		localClaimKey = isCentered(localPlayer) ? tileKey(localPlayer) : Long.MIN_VALUE;
		localClaimTarget = localPlayer.getInteracting() instanceof Player
			? (Player) localPlayer.getInteracting() : null;
		targetClaimKey = localClaimTarget != null
			&& localClaimTarget.getWorldLocation().getPlane() == level
			&& isCentered(localClaimTarget)
			? tileKey(localClaimTarget) : Long.MIN_VALUE;

		ParticlesConfig.ApplyTo applyTo = config.applyTo();
		int radiusUnits = config.effectRadius() * 128;
		LocalPoint localLp = localPlayer.getLocalLocation();

		// Every provably drawn player emits, not just the local one.
		// players() spans all four planes of the scene, but the client only
		// draws the current one - other planes must not emit at all.
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null)
			{
				continue;
			}
			PlayerEmitters pe = playerEmitters.get(player);
			if (pe == null)
			{
				continue;
			}
			boolean drawn;
			if (player != localPlayer
				&& (applyTo == ParticlesConfig.ApplyTo.ME
					|| (applyTo == ParticlesConfig.ApplyTo.FRIENDS && !player.isFriend())
					|| player.getLocalLocation().distanceTo(localLp) > radiusUnits))
			{
				// Out of scope by user preference; the hidden branch below
				// still clears their state so re-entry starts clean
				drawn = false;
			}
			else if (player.getWorldLocation().getPlane() != level)
			{
				drawn = false;
			}
			else if (!isCentered(player) || player == localPlayer)
			{
				drawn = true;
			}
			else
			{
				// Engine claim precedence for a centered stack member; movers
				// and the local player were already granted above
				long key = tileKey(player);
				if (key == localClaimKey)
				{
					drawn = false;
				}
				else if (key == targetClaimKey)
				{
					drawn = player == localClaimTarget;
				}
				else if (npcClaimedTiles.contains(key))
				{
					drawn = false;
				}
				else
				{
					drawn = tileOwners.get(key) == player;
				}
			}
			if (!drawn)
			{
				// Hidden under a stack: stop emitting and break trail
				// continuity so un-stacking doesn't lerp from stale positions
				pe.anchorCount = 0;
				pe.prevCount = 0;
				for (ActiveEmitter emitter : pe.emitters)
				{
					emitter.carry = 0;
				}
				continue;
			}

			resolvePlayer(pe, player);
			updateAnchors(pe, player, markers);
			emit(dt, pe, player);
			emitActorSpotAnims(dt, pe, player);
		}
		Iterator<PlayerEmitters> peIt = playerEmitters.values().iterator();
		while (peIt.hasNext())
		{
			if (peIt.next().stamp != playerStamp)
			{
				peIt.remove();
			}
		}

		if (pendingGraphicCapture >= 0)
		{
			Model pendingModel = findGraphicModel(pendingGraphicCapture);
			if (pendingModel != null)
			{
				pushGraphicSnapshot(pendingModel);
			}
		}

		processObjects(dt, level, radiusUnits, localLp);
		processNpcs(dt, level, radiusUnits, localLp);
		emitGraphicsObjects(dt, level, radiusUnits, localLp);
		updateStackOracle(level);
		emitProjectiles(dt);
		particleSystem.update(dt, deathStats);
		renderer.sync(particleSystem.getParticles(), anchorWorldView, anchorLevel);
		updateStats(now);
	}

	/**
	 * Dev diagnostic: the hover menu lists every player in a stack and the
	 * engine puts the DRAWN player's entry on top - ground truth for the
	 * stack winner that no API exposes, and that seven falsified
	 * reconstruction attempts never had. While hovering a stack, show the
	 * oracle winner next to the gate's decision and the lowest/highest
	 * menu-index candidates, and log one full state dump per distinct
	 * situation so the true precedence rule can be mined offline.
	 */
	private void updateStackOracle(int level)
	{
		oracleLine = "";
		if (!developerMode || !config.showAnchor())
		{
			return;
		}

		// Menus build bottom-up: the last vanilla player entry renders as the
		// top row. Only vanilla options - plugin-injected entries would lie.
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		Player winner = null;
		Map<Player, Integer> menuIndices = new HashMap<>();
		for (MenuEntry entry : entries)
		{
			Player p = entry.getPlayer();
			if (p != null && isVanillaPlayerOption(entry.getType()))
			{
				menuIndices.put(p, entry.getIdentifier());
				winner = p;
			}
		}
		if (winner == null)
		{
			return;
		}

		long key = tileKey(winner);
		List<Player> stack = new ArrayList<>();
		for (Player p : client.getTopLevelWorldView().players())
		{
			if (p != null && p.getWorldLocation().getPlane() == level
				&& isCentered(p) && tileKey(p) == key)
			{
				stack.add(p);
			}
		}
		String gate;
		if (key == localClaimKey)
		{
			gate = "local";
		}
		else if (key == targetClaimKey && localClaimTarget != null)
		{
			gate = localClaimTarget.getName();
		}
		else if (npcClaimedTiles.contains(key))
		{
			gate = "silent-npc";
		}
		else
		{
			Player owner = tileOwners.get(key);
			gate = owner == null ? "none" : owner.getName();
		}

		Player lo = null;
		Player hi = null;
		for (Player p : stack)
		{
			Integer idx = menuIndices.get(p);
			if (idx == null)
			{
				continue;
			}
			if (lo == null || idx < menuIndices.get(lo))
			{
				lo = p;
			}
			if (hi == null || idx > menuIndices.get(hi))
			{
				hi = p;
			}
		}

		// The shippable rule, scored live against the oracle: the engine's
		// claim precedence (local -> local's player target -> size-1 centered
		// NPC -> ascending index), with indices from Player.getId() - the
		// scene-wide source the rule would use outside the hovered tile
		Player localPlayer = client.getLocalPlayer();
		Player pred = null;
		String predWhy;
		if (stack.contains(localPlayer))
		{
			pred = localPlayer;
			predWhy = "local";
		}
		else
		{
			Player target = localPlayer != null && localPlayer.getInteracting() instanceof Player
				? (Player) localPlayer.getInteracting() : null;
			if (target != null && stack.contains(target))
			{
				pred = target;
				predWhy = "target";
			}
			else if (npcClaimedTiles.contains(key))
			{
				predWhy = "npc-claim";
			}
			else
			{
				for (Player p : stack)
				{
					if (pred == null || p.getId() < pred.getId())
					{
						pred = p;
					}
				}
				predWhy = "idx";
			}
		}
		// Menus never list the local player, so when we predict ourselves the
		// oracle cannot confirm it - that is blindness, not a falsification
		String predText = (pred == null ? predWhy : pred.getName() + "[" + predWhy + "]")
			+ (!isCentered(winner) ? " n/a-moving"
			: pred == winner ? " MATCH"
			: pred == localPlayer ? " oracle-blind-self"
			: " MISS");

		// Does the scene-wide index source agree with the menu identifiers?
		// A disagreement here would explain the old engine-mirror failures.
		boolean idxOk = true;
		for (Player p : stack)
		{
			Integer menuIdx = menuIndices.get(p);
			if (menuIdx != null && menuIdx != p.getId())
			{
				idxOk = false;
			}
		}

		// Mirror the scope filters (Apply to, effect radius) the drawn loop
		// applies before the claim - the claim can approve a player that
		// scope then excludes, which otherwise reads as a MATCH that fails
		String scoped = "";
		if (winner != localPlayer)
		{
			ParticlesConfig.ApplyTo applyTo = config.applyTo();
			if (applyTo == ParticlesConfig.ApplyTo.ME
				|| (applyTo == ParticlesConfig.ApplyTo.FRIENDS && !winner.isFriend()))
			{
				scoped = " SCOPED-OUT(" + applyTo + ")";
			}
			else if (localPlayer != null && winner.getLocalLocation()
				.distanceTo(localPlayer.getLocalLocation()) > config.effectRadius() * 128)
			{
				scoped = " SCOPED-OUT(radius " + config.effectRadius() + ")";
			}
		}

		// Resolution state: 0 emitters = their gear matched no enabled
		// preset; emitters but 0 anchors = blocked by the gate or scope
		PlayerEmitters winnerPe = playerEmitters.get(winner);
		String emitState = winnerPe == null
			? "emit -"
			: "emit " + winnerPe.emitters.size() + "e/" + winnerPe.anchorCount + "a";

		oracleLine = "stack " + stack.size()
			+ " | drawn " + winner.getName() + (isCentered(winner) ? "" : " (moving)")
			+ " | gate " + gate
			+ " | pred " + predText
			+ " | loIdx " + (lo == null ? "-" : lo.getName())
			+ " | hiIdx " + (hi == null ? "-" : hi.getName())
			+ " | idxSrc " + (idxOk ? "ok" : "MISMATCH")
			+ " | " + emitState + scoped;

		// One dump per distinct situation, with everything a candidate
		// precedence rule could depend on
		StringBuilder dump = new StringBuilder("stack oracle: drawn=")
			.append(winner.getName()).append('#').append(menuIndices.getOrDefault(winner, -1))
			.append(" gate=").append(gate)
			.append(" pred=").append(predText)
			.append(" idxOk=").append(idxOk)
			.append(' ').append(emitState).append(scoped);
		for (Player p : stack)
		{
			LocalPoint lp = p.getLocalLocation();
			dump.append(" | ").append(p.getName())
				.append('#').append(menuIndices.getOrDefault(p, -1))
				.append('/').append(p.getId())
				.append(" lp=").append(lp.getX()).append(',').append(lp.getY())
				.append(p == localPlayer ? " LOCAL" : "")
				.append(p.getInteracting() != null ? " ->" + p.getInteracting().getName() : "");
		}
		String text = dump.toString();
		if (!text.equals(lastOracleDump))
		{
			lastOracleDump = text;
			log.debug(text);
		}
	}

	private static boolean isVanillaPlayerOption(MenuAction action)
	{
		switch (action)
		{
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	private void onParticleDeath(Particle p)
	{
		windowDeaths++;
		// 1.0 = lived its full lifetime; below that it was killed early
		windowDeathAgeSum += 1f - p.lifeFraction();
	}

	private void updateStats(long now)
	{
		if (now - statsWindowStart < 1_000_000_000L)
		{
			return;
		}
		statsWindowStart = now;
		int avgDeathAgePct = windowDeaths == 0 ? -1
			: Math.round(windowDeathAgeSum / windowDeaths * 100);
		statsLine = "alive " + particleSystem.getParticles().size()
			+ " | batches " + renderer.getActiveObjects()
			+ " | verts/tick " + renderer.getLastBatchedVertices()
			+ " | spawns/s " + windowSpawns
			+ " | deaths/s " + windowDeaths
			+ " | avg death age " + (avgDeathAgePct < 0 ? "-" : avgDeathAgePct + "%")
			+ " | oob kills " + renderer.drainOutOfSceneKills()
			+ " | last anim " + lastActionAnimation;
		windowSpawns = 0;
		windowDeaths = 0;
		windowDeathAgeSum = 0;
	}

	/**
	 * Transform a player's active emitter vertices from their posed model
	 * into world (local scene) coordinates.
	 */
	private void updateAnchors(PlayerEmitters pe, Actor player, boolean markers)
	{
		// Keep last tick's anchors for movement-segment interpolation
		pe.prevCount = pe.anchorCount;
		if (pe.anchorCount > 0)
		{
			if (pe.prevXs.length < pe.anchorXs.length)
			{
				pe.prevXs = new float[pe.anchorXs.length];
				pe.prevYs = new float[pe.anchorXs.length];
				pe.prevZs = new float[pe.anchorXs.length];
			}
			System.arraycopy(pe.anchorXs, 0, pe.prevXs, 0, pe.anchorCount);
			System.arraycopy(pe.anchorYs, 0, pe.prevYs, 0, pe.anchorCount);
			System.arraycopy(pe.anchorZs, 0, pe.prevZs, 0, pe.anchorCount);
		}
		pe.anchorCount = 0;

		if (pe.emitters.isEmpty())
		{
			return;
		}

		Model model = player.getModel();
		if (model == null)
		{
			return;
		}
		int vertexCount = model.getVerticesCount();
		if (vertexCount == 0)
		{
			return;
		}

		int totalVertices = 0;
		for (ActiveEmitter emitter : pe.emitters)
		{
			totalVertices += emitter.vertices.length;
		}
		if (pe.anchorXs.length < totalVertices)
		{
			pe.anchorXs = new float[totalVertices];
			pe.anchorYs = new float[totalVertices];
			pe.anchorZs = new float[totalVertices];
		}

		// Rotate model space into the world by the player's current facing
		int orientation = player.getCurrentOrientation();
		int sin = Perspective.SINE[orientation];
		int cos = Perspective.COSINE[orientation];
		LocalPoint lp = player.getLocalLocation();
		// The engine renders the whole model relative to a single base height:
		// the tile height averaged over the actor's footprint, adjusted by the
		// current animation - the same formula ModelOutlineRenderer uses. A
		// plain point getTileHeight desyncs from the model on uneven ground.
		int playerBaseZ = Perspective.getFootprintTileHeight(client, lp, anchorLevel, player.getFootprintSize())
			- player.getAnimationHeightOffset();

		for (ActiveEmitter emitter : pe.emitters)
		{
			emitter.anchorStart = pe.anchorCount;
			emitter.anchorCount = 0;
			// Style offset in model space, so it rotates with the player
			ParticleStyle style = emitter.style;
			for (int v : emitter.vertices)
			{
				if (v < 0 || v >= vertexCount)
				{
					continue;
				}
				float vx = model.getVerticesX()[v] + style.getOffsetX();
				float vy = model.getVerticesY()[v] - style.getOffsetZ();
				float vz = model.getVerticesZ()[v] + style.getOffsetY();

				pe.anchorXs[pe.anchorCount] = lp.getX() + (vz * sin + vx * cos) / 65536f;
				pe.anchorYs[pe.anchorCount] = lp.getY() + (vz * cos - vx * sin) / 65536f;
				// Model y is already in the engine's negative-up convention
				pe.anchorZs[pe.anchorCount] = playerBaseZ + vy;
				pe.anchorCount++;
				emitter.anchorCount++;
			}
		}

		if (pe.trailCarry.length < pe.anchorXs.length)
		{
			pe.trailCarry = new float[pe.anchorXs.length];
		}
		// Anchor slots only correspond across ticks while the layout is stable
		if (pe.rebuilt || pe.prevCount != pe.anchorCount)
		{
			pe.prevCount = 0;
			Arrays.fill(pe.trailCarry, 0, pe.trailCarry.length, 0f);
		}
		pe.rebuilt = false;

		if (markers)
		{
			appendDebugMarkers(pe);
		}
	}

	/**
	 * Aggregate this player's anchors and feathered emission lines into the
	 * overlay's debug arrays, for tuning.
	 */
	private void appendDebugMarkers(PlayerEmitters pe)
	{
		int needed = anchorCount + pe.anchorCount;
		if (anchorXs.length < needed)
		{
			anchorXs = Arrays.copyOf(anchorXs, Math.max(needed, anchorXs.length * 2 + 16));
			anchorYs = Arrays.copyOf(anchorYs, anchorXs.length);
			anchorZs = Arrays.copyOf(anchorZs, anchorXs.length);
		}
		System.arraycopy(pe.anchorXs, 0, anchorXs, anchorCount, pe.anchorCount);
		System.arraycopy(pe.anchorYs, 0, anchorYs, anchorCount, pe.anchorCount);
		System.arraycopy(pe.anchorZs, 0, anchorZs, anchorCount, pe.anchorCount);
		anchorCount += pe.anchorCount;

		for (ActiveEmitter emitter : pe.emitters)
		{
			int w = emitter.style.getFeatherStrength();
			if (w <= 0 || emitter.chains == null || emitter.anchorCount != emitter.vertices.length)
			{
				continue;
			}
			for (int[] chain : emitter.chains)
			{
				if (chain.length < 2)
				{
					continue;
				}
				float[] points = new float[chain.length * 3];
				for (int j = 0; j < chain.length; j++)
				{
					points[j * 3] = smoothed(pe.anchorXs, emitter, chain, j, w);
					points[j * 3 + 1] = smoothed(pe.anchorYs, emitter, chain, j, w);
					points[j * 3 + 2] = smoothed(pe.anchorZs, emitter, chain, j, w);
				}
				featherDebugPaths.add(points);
			}
		}
	}

	/**
	 * @return whether an anchor's movement since last tick is usable as an
	 * emission segment (layout stable and not a teleport-sized jump)
	 */
	private static boolean segmentUsable(PlayerEmitters pe, int a)
	{
		if (pe.prevCount != pe.anchorCount)
		{
			return false;
		}
		float dx = pe.anchorXs[a] - pe.prevXs[a];
		float dy = pe.anchorYs[a] - pe.prevYs[a];
		float dz = pe.anchorZs[a] - pe.prevZs[a];
		return dx * dx + dy * dy + dz * dz <= MAX_TRAIL_SEGMENT * MAX_TRAIL_SEGMENT;
	}

	private static float segmentLength(PlayerEmitters pe, int a)
	{
		float dx = pe.anchorXs[a] - pe.prevXs[a];
		float dy = pe.anchorYs[a] - pe.prevYs[a];
		float dz = pe.anchorZs[a] - pe.prevZs[a];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Map stored piece-local emitters onto one player's composite model,
	 * honoring each profile's worn item filter against that player's gear.
	 * Cached: re-runs only when their equipment or the profile store changes.
	 */
	private void resolvePlayer(PlayerEmitters pe, Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			pe.emitters.clear();
			pe.equipmentIds = null;
			return;
		}

		// Compare gear directly; building a key string every tick is garbage
		int[] equipmentIds = composition.getEquipmentIds();
		int revision = store.getRevision();
		if (Arrays.equals(equipmentIds, pe.equipmentIds) && revision == pe.revision)
		{
			return;
		}

		Model model = player.getModel();
		if (model == null)
		{
			pe.emitters.clear();
			pe.equipmentIds = null;
			return;
		}
		pe.equipmentIds = equipmentIds.clone();
		pe.revision = revision;
		pe.rebuilt = true;

		Set<Integer> wornItemIds = wornItemIds(composition);
		Map<String, EmitterProfile> profiles = store.snapshotAll();
		ModelSnapshot snapshot = ModelSnapshot.capture(model);

		pe.emitters.clear();
		Set<String> present = new HashSet<>();
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			present.add(piece.getSignature());
			for (String mirror : piece.getMirrorSignatures())
			{
				present.add(mirror);
			}
			for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
			{
				EmitterProfile profile = entry.getValue();
				if (!EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
					|| !piece.matchesSignature(profile.getSignature())
					|| !profile.isEnabled()
					|| profile.getVertices().isEmpty()
					|| (!developerMode && ParticlesPanel.Category.isWip(profile)))
				{
					continue;
				}
				// Item variant gate: distinguishes recolored items sharing a mesh
				if (!profile.getItemIds().isEmpty() && Collections.disjoint(profile.getItemIds(), wornItemIds))
				{
					continue;
				}
				ParticleStyle style = renderer.getStyle(entry.getKey());
				if (style == null)
				{
					continue;
				}

				List<Integer> globals = new ArrayList<>();
				int[] pieceVerts = piece.verticesFor(profile.getSignature());
				for (int local : profile.getVertices())
				{
					if (local >= 0 && local < pieceVerts.length)
					{
						globals.add(pieceVerts[local]);
					}
				}
				if (globals.isEmpty())
				{
					continue;
				}
				int[] vertices = new int[globals.size()];
				for (int i = 0; i < vertices.length; i++)
				{
					vertices[i] = globals.get(i);
				}
				int[][] chains = style.getFeatherStrength() > 0
					? buildChains(snapshot, piece, vertices)
					: null;
				pe.emitters.add(new ActiveEmitter(style, vertices, chains));
			}
		}

		if (player != client.getLocalPlayer())
		{
			return;
		}

		// Local-player extras: the sidebar's worn indicator, and projectile
		// profiles gear-gated by the local player's equipment
		activeProjectileProfiles.clear();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isProjectileTarget() || !profile.isEnabled() || profile.getProjectileId() < 0
				|| (!developerMode && ParticlesPanel.Category.isWip(profile)))
			{
				continue;
			}
			if (!profile.getItemIds().isEmpty() && Collections.disjoint(profile.getItemIds(), wornItemIds))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style != null)
			{
				activeProjectileProfiles.add(new ActiveProjectileProfile(profile.getProjectileId(), style));
			}
		}
		projectileTrackers.clear();

		if (!present.equals(presentSignatures))
		{
			presentSignatures = present;
			refreshPanel();
		}
	}

	/**
	 * Chain an emitter's vertices along the mesh edges of their piece, for
	 * feathered emission. Each chain is a walkable path of anchor offsets;
	 * vertices with no emitter neighbor become single-point chains. Cold
	 * path, runs only at emitter resolution.
	 */
	private static int[][] buildChains(ModelSnapshot snapshot, ModelSnapshot.Piece piece, int[] emitterVertices)
	{
		Map<Integer, Integer> offsetOf = new HashMap<>();
		for (int i = 0; i < emitterVertices.length; i++)
		{
			offsetOf.put(emitterVertices[i], i);
		}

		// Adjacency between emitter vertices sharing a mesh edge
		List<List<Integer>> adjacency = new ArrayList<>(emitterVertices.length);
		for (int i = 0; i < emitterVertices.length; i++)
		{
			adjacency.add(new ArrayList<>());
		}
		Set<Long> seenEdges = new HashSet<>();
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		for (int f : piece.getFaces())
		{
			chainEdge(offsetOf, adjacency, seenEdges, f1[f], f2[f]);
			chainEdge(offsetOf, adjacency, seenEdges, f2[f], f3[f]);
			chainEdge(offsetOf, adjacency, seenEdges, f1[f], f3[f]);
		}

		// Walk paths from endpoints and junctions first, then leftover cycles
		boolean[] visited = new boolean[emitterVertices.length];
		List<List<Integer>> chains = new ArrayList<>();
		for (int pass = 0; pass < 2; pass++)
		{
			for (int start = 0; start < emitterVertices.length; start++)
			{
				if (visited[start] || (pass == 0 && adjacency.get(start).size() == 2))
				{
					continue;
				}
				List<Integer> path = new ArrayList<>();
				int current = start;
				visited[current] = true;
				path.add(current);
				boolean extended = true;
				while (extended)
				{
					extended = false;
					for (int next : adjacency.get(current))
					{
						if (!visited[next])
						{
							visited[next] = true;
							path.add(next);
							current = next;
							extended = true;
							break;
						}
					}
				}
				chains.add(path);
			}
		}

		bridgeChains(snapshot, emitterVertices, chains);

		int[][] result = new int[chains.size()][];
		for (int c = 0; c < chains.size(); c++)
		{
			List<Integer> path = chains.get(c);
			int[] chain = new int[path.size()];
			for (int i = 0; i < chain.length; i++)
			{
				chain[i] = path.get(i);
			}
			result[c] = chain;
		}
		return result;
	}

	/**
	 * Distance particles will feather across even without a connecting mesh
	 * edge, e.g. between the separate puffs of a fur trim.
	 */
	private static final float CHAIN_BRIDGE_DISTANCE = 40f;

	/**
	 * Repeatedly join chains whose endpoints are close in space, reversing as
	 * needed so the joined path stays walkable. Cold path.
	 */
	private static void bridgeChains(ModelSnapshot snapshot, int[] emitterVertices, List<List<Integer>> chains)
	{
		boolean merged = true;
		while (merged && chains.size() > 1)
		{
			merged = false;
			outer:
			for (int i = 0; i < chains.size(); i++)
			{
				for (int j = i + 1; j < chains.size(); j++)
				{
					List<Integer> a = chains.get(i);
					List<Integer> b = chains.get(j);
					// Endpoint pairings: a-head/tail against b-head/tail
					if (endpointsClose(snapshot, emitterVertices, a.get(a.size() - 1), b.get(0)))
					{
						a.addAll(b);
					}
					else if (endpointsClose(snapshot, emitterVertices, a.get(a.size() - 1), b.get(b.size() - 1)))
					{
						Collections.reverse(b);
						a.addAll(b);
					}
					else if (endpointsClose(snapshot, emitterVertices, a.get(0), b.get(b.size() - 1)))
					{
						a.addAll(0, b);
					}
					else if (endpointsClose(snapshot, emitterVertices, a.get(0), b.get(0)))
					{
						Collections.reverse(b);
						a.addAll(0, b);
					}
					else
					{
						continue;
					}
					chains.remove(j);
					merged = true;
					break outer;
				}
			}
		}
	}

	private static boolean endpointsClose(ModelSnapshot snapshot, int[] emitterVertices, int offsetA, int offsetB)
	{
		int globalA = emitterVertices[offsetA];
		int globalB = emitterVertices[offsetB];
		float dx = snapshot.getVerticesX()[globalA] - snapshot.getVerticesX()[globalB];
		float dy = snapshot.getVerticesY()[globalA] - snapshot.getVerticesY()[globalB];
		float dz = snapshot.getVerticesZ()[globalA] - snapshot.getVerticesZ()[globalB];
		return dx * dx + dy * dy + dz * dz <= CHAIN_BRIDGE_DISTANCE * CHAIN_BRIDGE_DISTANCE;
	}

	private static void chainEdge(Map<Integer, Integer> offsetOf, List<List<Integer>> adjacency,
		Set<Long> seenEdges, int globalA, int globalB)
	{
		Integer a = offsetOf.get(globalA);
		Integer b = offsetOf.get(globalB);
		if (a == null || b == null)
		{
			return;
		}
		long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
		if (seenEdges.add(key))
		{
			adjacency.get(a).add(b);
			adjacency.get(b).add(a);
		}
	}

	/**
	 * Force re-resolution for everyone without touching the draw-order
	 * mirror, e.g. when styles rebuild or models are recreated on scene load.
	 */
	private void invalidateResolutions()
	{
		for (PlayerEmitters pe : playerEmitters.values())
		{
			pe.equipmentIds = null;
		}
	}

	/**
	 * Standing exactly at a tile center - the only state the engine's
	 * stacked-actor dedup applies to.
	 */
	private static boolean isCentered(Player player)
	{
		LocalPoint lp = player.getLocalLocation();
		return (lp.getX() & 127) == 64 && (lp.getY() & 127) == 64;
	}

	private static long tileKey(Player player)
	{
		LocalPoint lp = player.getLocalLocation();
		return ((long) (lp.getX() >> 7) << 32) | ((lp.getY() >> 7) & 0xffffffffL);
	}

	private static Set<Integer> wornItemIds(PlayerComposition composition)
	{
		Set<Integer> ids = new HashSet<>();
		for (int equipmentId : composition.getEquipmentIds())
		{
			if (equipmentId >= PlayerComposition.ITEM_OFFSET)
			{
				ids.add(equipmentId - PlayerComposition.ITEM_OFFSET);
			}
		}
		return ids;
	}

	private void emit(float dt, PlayerEmitters pe, Actor player)
	{
		if (pe.anchorCount == 0)
		{
			for (ActiveEmitter emitter : pe.emitters)
			{
				emitter.carry = 0;
			}
			return;
		}

		// Animation gate inputs from THIS player, shared by their emitters
		int actionAnimation = player.getAnimation();
		int actionFrame = player.getAnimationFrame();
		int poseAnimation = player.getPoseAnimation();
		boolean isLocal = player == client.getLocalPlayer();
		if (isLocal)
		{
			lastActionAnimation = actionAnimation != -1 ? actionAnimation : lastActionAnimation;
		}
		// Movement lifetime: a full-lifetime plume smears a tile behind a
		// moving wearer, so profiles can shorten it while THIS player's walk
		// or run pose animation is playing (weapon-specific poses included)
		boolean moving = poseAnimation == player.getRunAnimation() || poseAnimation == player.getWalkAnimation();

		int budget = config.maxParticles();
		float densityScale = config.density().getFactor();
		if (isLocal && densityScale < 1f && config.fullSelfDensity())
		{
			densityScale = 1f;
		}
		for (ActiveEmitter emitter : pe.emitters)
		{
			if (emitter.anchorCount == 0)
			{
				emitter.carry = 0;
				continue;
			}
			ParticleStyle style = emitter.style;
			if (!style.animationMatches(actionAnimation, actionFrame, poseAnimation))
			{
				// Reset so the gate opening doesn't release a burst
				emitter.carry = 0;
				continue;
			}

			// Time-based emission, throttled to what the budget can sustain:
			// outrunning it makes births and deaths synchronize into waves
			float sustainable = budget / style.getLifetimeSec() * 0.95f;
			float rate = Math.min(style.getParticlesPerSecond() * densityScale, sustainable);
			emitter.carry += rate * dt;
			int count = (int) emitter.carry;
			emitter.carry -= count;

			float lifeScale = moving ? style.getMovementLifetimeScale() : 1f;
			boolean feathered = style.getFeatherStrength() > 0 && emitter.chains != null
				&& emitter.anchorCount == emitter.vertices.length;
			for (int i = 0; i < count; i++)
			{
				if (particleSystem.getParticles().size() >= budget)
				{
					return;
				}
				if (feathered)
				{
					spawnFeathered(pe, emitter, lifeScale);
				}
				else
				{
					int a = emitter.anchorStart + random.nextInt(emitter.anchorCount);
					spawnParticle(pe, emitter, a, random.nextFloat(), lifeScale);
				}
			}

			// Distance-based emission: spread spawns evenly along each
			// anchor's movement this tick, for ribbon-like weapon trails
			float density = style.getTrailDensity() * densityScale;
			if (density <= 0)
			{
				continue;
			}
			for (int a = emitter.anchorStart; a < emitter.anchorStart + emitter.anchorCount; a++)
			{
				if (!segmentUsable(pe, a))
				{
					pe.trailCarry[a] = 0;
					continue;
				}
				float owed = pe.trailCarry[a] + segmentLength(pe, a) / 128f * density;
				int n = (int) owed;
				pe.trailCarry[a] = owed - n;
				for (int i = 0; i < n; i++)
				{
					if (particleSystem.getParticles().size() >= budget)
					{
						return;
					}
					// Stratified positions along the segment: an even ribbon
					spawnParticle(pe, emitter, a, (i + random.nextFloat()) / n, lifeScale);
				}
			}
		}
	}

	/**
	 * Spawn one particle at fraction t along the anchor's movement segment
	 * since last tick (falling back to its current position), plus jitter.
	 */
	private void spawnParticle(PlayerEmitters pe, ActiveEmitter emitter, int a, float t, float lifeScale)
	{
		float ax = pe.anchorXs[a];
		float ay = pe.anchorYs[a];
		float az = pe.anchorZs[a];
		if (segmentUsable(pe, a))
		{
			ax = pe.prevXs[a] + (ax - pe.prevXs[a]) * t;
			ay = pe.prevYs[a] + (ay - pe.prevYs[a]) * t;
			az = pe.prevZs[a] + (az - pe.prevZs[a]) * t;
		}

		spawnAt(emitter.style, ax, ay, az, lifeScale);
	}

	/**
	 * Spawn along the smoothed line through the emitter's vertex chains:
	 * a quadratic curve through segment midpoints with the vertex as control
	 * point, which rounds off jagged hem corners into a soft band.
	 */
	private void spawnFeathered(PlayerEmitters pe, ActiveEmitter emitter, float lifeScale)
	{
		int k = random.nextInt(emitter.sampleChainOf.length);
		int[] chain = emitter.chains[emitter.sampleChainOf[k]];
		int j = emitter.samplePosOf[k];
		int w = emitter.style.getFeatherStrength();
		int jA = Math.max(0, j - 1);
		int jC = Math.min(chain.length - 1, j + 1);
		float t = random.nextFloat();

		float x = quadratic(smoothed(pe.anchorXs, emitter, chain, jA, w),
			smoothed(pe.anchorXs, emitter, chain, j, w),
			smoothed(pe.anchorXs, emitter, chain, jC, w), t);
		float y = quadratic(smoothed(pe.anchorYs, emitter, chain, jA, w),
			smoothed(pe.anchorYs, emitter, chain, j, w),
			smoothed(pe.anchorYs, emitter, chain, jC, w), t);
		float z = quadratic(smoothed(pe.anchorZs, emitter, chain, jA, w),
			smoothed(pe.anchorZs, emitter, chain, j, w),
			smoothed(pe.anchorZs, emitter, chain, jC, w), t);

		// Time-lerp along the anchors' movement this tick, like point spawns
		if (segmentUsable(pe, emitter.anchorStart + chain[j]))
		{
			float timeT = random.nextFloat();
			float px = quadratic(smoothed(pe.prevXs, emitter, chain, jA, w),
				smoothed(pe.prevXs, emitter, chain, j, w),
				smoothed(pe.prevXs, emitter, chain, jC, w), t);
			float py = quadratic(smoothed(pe.prevYs, emitter, chain, jA, w),
				smoothed(pe.prevYs, emitter, chain, j, w),
				smoothed(pe.prevYs, emitter, chain, jC, w), t);
			float pz = quadratic(smoothed(pe.prevZs, emitter, chain, jA, w),
				smoothed(pe.prevZs, emitter, chain, j, w),
				smoothed(pe.prevZs, emitter, chain, jC, w), t);
			x = px + (x - px) * timeT;
			y = py + (y - py) * timeT;
			z = pz + (z - pz) * timeT;
		}

		spawnAt(emitter.style, x, y, z, lifeScale);
	}

	/**
	 * Chain position averaged over a window of neighbors: the feathering
	 * filter that turns a jagged vertex path into a smooth band.
	 */
	private float smoothed(float[] coords, ActiveEmitter emitter, int[] chain, int j, int w)
	{
		int from = Math.max(0, j - w);
		int to = Math.min(chain.length - 1, j + w);
		float sum = 0;
		for (int i = from; i <= to; i++)
		{
			sum += coords[emitter.anchorStart + chain[i]];
		}
		return sum / (to - from + 1);
	}

	/**
	 * Quadratic Bezier from midpoint(a,b) to midpoint(b,c) with b as control:
	 * the smoothed-polyline curve.
	 */
	private static float quadratic(float a, float b, float c, float t)
	{
		float m1 = (a + b) / 2f;
		float m2 = (b + c) / 2f;
		float inv = 1f - t;
		return inv * inv * m1 + 2f * inv * t * b + t * t * m2;
	}

	/**
	 * Emit for projectile-targeted profiles: every active projectile whose ID
	 * matches an enabled profile emits along its movement segment - trail
	 * density is the natural driver for comet tails. Also records recently
	 * seen projectile IDs for the picker's capture list. The player animation
	 * gate doesn't apply here; projectiles are inherently lifecycle-gated.
	 */
	private void emitProjectiles(float dt)
	{
		projectileStamp++;
		long nowMs = System.currentTimeMillis();
		int budget = config.maxParticles();
		float densityScale = config.density().getFactor();

		for (Projectile projectile : client.getProjectiles())
		{
			if (client.getGameCycle() < projectile.getStartCycle())
			{
				// Queued but not launched yet
				continue;
			}

			ProjectileTracker tracker = projectileTrackers.get(projectile);
			if (tracker == null)
			{
				tracker = new ProjectileTracker();
				projectileTrackers.put(projectile, tracker);
				noteRecentProjectile(projectile.getId(), nowMs);
			}
			tracker.stamp = projectileStamp;

			float x = (float) projectile.getX();
			float y = (float) projectile.getY();
			float z = (float) projectile.getZ();
			float px = tracker.prevX;
			float py = tracker.prevY;
			float pz = tracker.prevZ;
			float distSq = (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz);
			boolean segment = tracker.prevValid && distSq <= MAX_TRAIL_SEGMENT * MAX_TRAIL_SEGMENT;
			tracker.prevX = x;
			tracker.prevY = y;
			tracker.prevZ = z;
			tracker.prevValid = true;

			for (ActiveProjectileProfile profile : activeProjectileProfiles)
			{
				if (profile.projectileId != projectile.getId())
				{
					continue;
				}
				ParticleStyle style = profile.style;
				double[] carries = tracker.carries.computeIfAbsent(style, s -> new double[2]);

				// Time-based emission, spread along this tick's movement
				float sustainable = budget / style.getLifetimeSec() * 0.95f;
				carries[0] += Math.min(style.getParticlesPerSecond() * densityScale, sustainable) * dt;
				int count = (int) carries[0];
				carries[0] -= count;
				for (int i = 0; i < count; i++)
				{
					if (particleSystem.getParticles().size() >= budget)
					{
						return;
					}
					float t = segment ? random.nextFloat() : 1f;
					spawnAt(style, px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t, 1f);
				}

				// Distance-based ribbon emission
				if (style.getTrailDensity() > 0 && segment)
				{
					double owed = carries[1] + Math.sqrt(distSq) / 128f * style.getTrailDensity() * densityScale;
					int n = (int) owed;
					carries[1] = owed - n;
					for (int i = 0; i < n; i++)
					{
						if (particleSystem.getParticles().size() >= budget)
						{
							return;
						}
						float t = (i + random.nextFloat()) / n;
						spawnAt(style, px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t, 1f);
					}
				}
			}
		}

		// Drop trackers whose projectiles ended
		Iterator<ProjectileTracker> it = projectileTrackers.values().iterator();
		while (it.hasNext())
		{
			if (it.next().stamp != projectileStamp)
			{
				it.remove();
			}
		}
	}

	private void noteRecentProjectile(int projectileId, long nowMs)
	{
		long[] seen = recentProjectiles.get(projectileId);
		if (seen != null)
		{
			seen[0]++;
			seen[1] = nowMs;
			return;
		}
		if (recentProjectiles.size() >= 24)
		{
			Integer oldestId = null;
			long oldestSeen = Long.MAX_VALUE;
			for (Map.Entry<Integer, long[]> entry : recentProjectiles.entrySet())
			{
				if (entry.getValue()[1] < oldestSeen)
				{
					oldestSeen = entry.getValue()[1];
					oldestId = entry.getKey();
				}
			}
			recentProjectiles.remove(oldestId);
		}
		recentProjectiles.put(projectileId, new long[]{1, nowMs});
	}

	private void spawnAt(ParticleStyle style, float ax, float ay, float az, float lifeScale)
	{
		// Spawn within a small volume around the point, not at it exactly
		float jitter = style.getSpawnJitter();
		double jitterAngle = random.nextFloat() * 2 * Math.PI;
		float jitterRadius = jitter * (float) Math.sqrt(random.nextFloat());
		float x = ax + (float) Math.cos(jitterAngle) * jitterRadius;
		float y = ay + (float) Math.sin(jitterAngle) * jitterRadius;
		float z = az + (random.nextFloat() - 0.5f) * jitter;

		float spread = style.getSpreadSpeed();
		float velX = (random.nextFloat() - 0.5f) * spread;
		float velY = (random.nextFloat() - 0.5f) * spread;
		// Scene z is negative-up, so rising means decreasing z
		float velZ = -style.getRiseSpeed() * (0.75f + random.nextFloat() * 0.5f);
		// Slow sinusoidal drift; amplitude reuses the spread speed
		float wobblePhase = random.nextFloat() * 2f * (float) Math.PI;
		float wobbleFreq = 1.5f + random.nextFloat() * 2f;
		int sizeVariant = random.nextInt(ParticleStyle.SIZE_MULTIPLIERS.length);

		Particle p = particleSystem.spawn(x, y, z, velX, velY, velZ,
			style.getLifetimeSec() * lifeScale, style, sizeVariant, wobblePhase, wobbleFreq, spread);
		if (p != null)
		{
			windowSpawns++;
		}
	}

	/**
	 * Open (or focus) the vertex picker window and load a fresh snapshot.
	 * Called on the Swing EDT.
	 */
	void openViewer()
	{
		if (viewerFrame == null)
		{
			viewerFrame = new ModelViewerFrame(this);
			viewerFrame.addWindowListener(new java.awt.event.WindowAdapter()
			{
				@Override
				public void windowClosed(java.awt.event.WindowEvent e)
				{
					viewerFrame = null;
				}
			});
		}
		viewerFrame.setVisible(true);
		viewerFrame.toFront();
		refreshSnapshot();
	}

	// ==================== Scenery ====================

	/**
	 * Recompute which object IDs carry profiles and rescan the loaded scene
	 * for instances. The scene walk is expensive but runs only when the
	 * profile store changes; spawn events keep the registry current
	 * otherwise.
	 */
	private void rebuildProfiledObjects()
	{
		Set<Integer> ids = new HashSet<>();
		for (EmitterProfile profile : store.snapshotAll().values())
		{
			if (profile.isObjectTarget() && profile.getObjectId() >= 0)
			{
				ids.add(profile.getObjectId());
			}
		}
		profiledObjectIds = ids;
		objectEmitters.clear();
		if (ids.isEmpty())
		{
			return;
		}
		Scene scene = client.getTopLevelWorldView().getScene();
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject gameObject : gameObjects)
						{
							if (gameObject != null)
							{
								trackObject(gameObject);
							}
						}
					}
					if (tile.getWallObject() != null)
					{
						trackObject(tile.getWallObject());
					}
					if (tile.getDecorativeObject() != null)
					{
						trackObject(tile.getDecorativeObject());
					}
					if (tile.getGroundObject() != null)
					{
						trackObject(tile.getGroundObject());
					}
				}
			}
		}
	}

	/**
	 * Resolve, anchor and emit for tracked scenery. No claim gate applies -
	 * scenery never participates in the actor dedup - only plane and radius.
	 */
	private void processObjects(float dt, int level, int radiusUnits, LocalPoint localLp)
	{
		if (objectEmitters.isEmpty())
		{
			return;
		}
		int revision = store.getRevision();
		boolean markers = config.showAnchor() && developerMode;
		for (Map.Entry<TileObject, ObjectEmitters> entry : objectEmitters.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectEmitters oe = entry.getValue();
			if (object.getPlane() != level
				|| object.getLocalLocation().distanceTo(localLp) > radiusUnits)
			{
				oe.anchorCount = 0;
				for (ActiveEmitter emitter : oe.emitters)
				{
					emitter.carry = 0;
				}
				continue;
			}
			if (oe.revision != revision)
			{
				resolveObject(oe, object, revision);
			}
			updateObjectAnchors(oe, object, markers);
			emitObject(dt, oe);
		}
	}

	/**
	 * Map stored object profiles onto one instance's model, matching by
	 * object ID plus piece signature.
	 */
	private void resolveObject(ObjectEmitters oe, TileObject object, int revision)
	{
		oe.revision = revision;
		oe.emitters.clear();
		Map<String, EmitterProfile> profiles = store.snapshotAll();
		Model model = objectModel(object);
		if (model == null)
		{
			logResolveMissOnce(oe, object, profiles, null);
			return;
		}
		ModelSnapshot snapshot = ModelSnapshot.capture(model);
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isObjectTarget() || profile.getObjectId() != object.getId()
				|| !profile.isEnabled() || profile.getVertices().isEmpty()
				|| (!developerMode && ParticlesPanel.Category.isWip(profile)))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style == null)
			{
				continue;
			}
			// Attach to EVERY matching piece: identical twin fragments (a
			// double sconce's two flames) share a signature and should all
			// emit, matching the player path's behavior
			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				if (!piece.matchesSignature(profile.getSignature()))
				{
					continue;
				}
				List<Integer> globals = new ArrayList<>();
				int[] pieceVerts = piece.verticesFor(profile.getSignature());
				for (int local : profile.getVertices())
				{
					if (local >= 0 && local < pieceVerts.length)
					{
						globals.add(pieceVerts[local]);
					}
				}
				if (!globals.isEmpty())
				{
					int[] vertices = new int[globals.size()];
					for (int i = 0; i < vertices.length; i++)
					{
						vertices[i] = globals.get(i);
					}
					int[][] chains = style.getFeatherStrength() > 0
						? buildChains(snapshot, piece, vertices)
						: null;
					oe.emitters.add(new ActiveEmitter(style, vertices, chains));
				}
			}
		}
		if (oe.emitters.isEmpty())
		{
			logResolveMissOnce(oe, object, profiles, model);
		}
	}

	/**
	 * Hypothesis probe for placements matching nothing despite a profiled
	 * ID: if the dump shows the same NvMf counts with a different hash
	 * suffix, the placement uses a mirrored model whose reversed winding
	 * changes the topology hash. Once per instance, debug level.
	 */
	private void logResolveMissOnce(ObjectEmitters oe, TileObject object,
		Map<String, EmitterProfile> profiles, @Nullable Model primary)
	{
		if (oe.loggedResolveMiss || !log.isDebugEnabled())
		{
			return;
		}
		oe.loggedResolveMiss = true;
		StringBuilder sb = new StringBuilder("object resolve miss: id=").append(object.getId());
		appendPieceSignatures(sb, " primary=", primary);
		appendPieceSignatures(sb, " secondary=", secondaryModel(object));
		sb.append(" profiles=");
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.isObjectTarget() && profile.getObjectId() == object.getId())
			{
				sb.append(profile.getSignature()).append('(').append(profile.getName()).append(") ");
			}
		}
		log.debug(sb.toString());
	}

	private static void appendPieceSignatures(StringBuilder sb, String label, @Nullable Model model)
	{
		sb.append(label);
		if (model == null)
		{
			sb.append("null");
			return;
		}
		for (ModelSnapshot.Piece piece : ModelSnapshot.capture(model).getPieces())
		{
			sb.append(piece.getSignature()).append(' ');
		}
	}

	@Nullable
	private static Model secondaryModel(TileObject object)
	{
		if (object instanceof WallObject)
		{
			return modelOf(((WallObject) object).getRenderable2());
		}
		if (object instanceof DecorativeObject)
		{
			return modelOf(((DecorativeObject) object).getRenderable2());
		}
		return null;
	}

	private void updateObjectAnchors(ObjectEmitters oe, TileObject object, boolean markers)
	{
		oe.anchorCount = 0;
		if (oe.emitters.isEmpty())
		{
			return;
		}
		Model model = objectModel(object);
		if (model == null)
		{
			return;
		}
		int vertexCount = model.getVerticesCount();
		int total = 0;
		for (ActiveEmitter emitter : oe.emitters)
		{
			total += emitter.vertices.length;
		}
		if (oe.anchorXs.length < total)
		{
			oe.anchorXs = new float[total];
			oe.anchorYs = new float[total];
			oe.anchorZs = new float[total];
		}
		LocalPoint lp = object.getLocalLocation();
		int baseZ = Perspective.getTileHeight(client, lp, object.getPlane());
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		for (ActiveEmitter emitter : oe.emitters)
		{
			emitter.anchorStart = oe.anchorCount;
			emitter.anchorCount = 0;
			ParticleStyle style = emitter.style;
			for (int v : emitter.vertices)
			{
				if (v < 0 || v >= vertexCount)
				{
					continue;
				}
				// Scene models arrive pre-rotated, so offsets apply on world
				// axes: X east, Y north, Z up
				oe.anchorXs[oe.anchorCount] = lp.getX() + vx[v] + style.getOffsetX();
				oe.anchorYs[oe.anchorCount] = lp.getY() + vz[v] + style.getOffsetY();
				oe.anchorZs[oe.anchorCount] = baseZ + vy[v] - style.getOffsetZ();
				oe.anchorCount++;
				emitter.anchorCount++;
			}
		}
		if (markers && oe.anchorCount > 0)
		{
			int needed = anchorCount + oe.anchorCount;
			if (anchorXs.length < needed)
			{
				anchorXs = Arrays.copyOf(anchorXs, Math.max(needed, anchorXs.length * 2 + 16));
				anchorYs = Arrays.copyOf(anchorYs, anchorXs.length);
				anchorZs = Arrays.copyOf(anchorZs, anchorXs.length);
			}
			System.arraycopy(oe.anchorXs, 0, anchorXs, anchorCount, oe.anchorCount);
			System.arraycopy(oe.anchorYs, 0, anchorYs, anchorCount, oe.anchorCount);
			System.arraycopy(oe.anchorZs, 0, anchorZs, anchorCount, oe.anchorCount);
			anchorCount = needed;
		}
	}

	private void emitObject(float dt, ObjectEmitters oe)
	{
		if (oe.anchorCount == 0)
		{
			for (ActiveEmitter emitter : oe.emitters)
			{
				emitter.carry = 0;
			}
			return;
		}
		int budget = config.maxParticles();
		float densityScale = config.density().getFactor();
		for (ActiveEmitter emitter : oe.emitters)
		{
			if (emitter.anchorCount == 0)
			{
				emitter.carry = 0;
				continue;
			}
			ParticleStyle style = emitter.style;
			float sustainable = budget / style.getLifetimeSec() * 0.95f;
			float rate = Math.min(style.getParticlesPerSecond() * densityScale, sustainable);
			emitter.carry += rate * dt;
			int count = (int) emitter.carry;
			emitter.carry -= count;
			boolean feathered = style.getFeatherStrength() > 0 && emitter.chains != null
				&& emitter.anchorCount == emitter.vertices.length;
			for (int i = 0; i < count; i++)
			{
				if (particleSystem.getParticles().size() >= budget)
				{
					return;
				}
				if (feathered)
				{
					spawnFeatheredStatic(oe.anchorXs, oe.anchorYs, oe.anchorZs, emitter);
				}
				else
				{
					int a = emitter.anchorStart + random.nextInt(emitter.anchorCount);
					spawnAt(style, oe.anchorXs[a], oe.anchorYs[a], oe.anchorZs[a], 1f);
				}
			}
		}
	}

	/**
	 * Feathered spawn over static anchors: the smoothed-chain curve without
	 * the movement lerp that the player variant threads through prev arrays.
	 */
	private void spawnFeatheredStatic(float[] xs, float[] ys, float[] zs, ActiveEmitter emitter)
	{
		int k = random.nextInt(emitter.sampleChainOf.length);
		int[] chain = emitter.chains[emitter.sampleChainOf[k]];
		int j = emitter.samplePosOf[k];
		int w = emitter.style.getFeatherStrength();
		int jA = Math.max(0, j - 1);
		int jC = Math.min(chain.length - 1, j + 1);
		float t = random.nextFloat();

		float x = quadratic(smoothed(xs, emitter, chain, jA, w),
			smoothed(xs, emitter, chain, j, w),
			smoothed(xs, emitter, chain, jC, w), t);
		float y = quadratic(smoothed(ys, emitter, chain, jA, w),
			smoothed(ys, emitter, chain, j, w),
			smoothed(ys, emitter, chain, jC, w), t);
		float z = quadratic(smoothed(zs, emitter, chain, jA, w),
			smoothed(zs, emitter, chain, j, w),
			smoothed(zs, emitter, chain, jC, w), t);

		spawnAt(emitter.style, x, y, z, 1f);
	}

	@Nullable
	private static Model objectModel(TileObject object)
	{
		if (object instanceof GameObject)
		{
			return modelOf(((GameObject) object).getRenderable());
		}
		if (object instanceof WallObject)
		{
			Model model = modelOf(((WallObject) object).getRenderable1());
			return model != null ? model : modelOf(((WallObject) object).getRenderable2());
		}
		if (object instanceof DecorativeObject)
		{
			Model model = modelOf(((DecorativeObject) object).getRenderable());
			return model != null ? model : modelOf(((DecorativeObject) object).getRenderable2());
		}
		if (object instanceof GroundObject)
		{
			return modelOf(((GroundObject) object).getRenderable());
		}
		return null;
	}

	@Nullable
	private static Model modelOf(@Nullable Renderable renderable)
	{
		if (renderable instanceof Model)
		{
			return (Model) renderable;
		}
		if (renderable instanceof DynamicObject)
		{
			return ((DynamicObject) renderable).getModel();
		}
		return null;
	}

	/**
	 * Every scenery instance on one plane of the loaded scene, deduplicated
	 * (multi-tile objects appear on each covered tile). Dev-tool path only -
	 * this walks the whole scene.
	 */
	private List<TileObject> sceneObjects(int plane)
	{
		List<TileObject> out = new ArrayList<>();
		Set<TileObject> seen = new HashSet<>();
		Tile[][] tiles = client.getTopLevelWorldView().getScene().getTiles()[plane];
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null)
				{
					for (GameObject gameObject : gameObjects)
					{
						if (gameObject != null && seen.add(gameObject))
						{
							out.add(gameObject);
						}
					}
				}
				if (tile.getWallObject() != null && seen.add(tile.getWallObject()))
				{
					out.add(tile.getWallObject());
				}
				if (tile.getDecorativeObject() != null && seen.add(tile.getDecorativeObject()))
				{
					out.add(tile.getDecorativeObject());
				}
				if (tile.getGroundObject() != null && seen.add(tile.getGroundObject()))
				{
					out.add(tile.getGroundObject());
				}
			}
		}
		return out;
	}

	/**
	 * Nearby scenery for the viewer's capture list: one entry per object ID,
	 * nearest instance, sorted by distance. Client thread.
	 */
	private List<ModelViewerFrame.ObjectSighting> nearbySightings()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return List.of();
		}
		LocalPoint lp = player.getLocalLocation();
		Map<Integer, ModelViewerFrame.ObjectSighting> best = new HashMap<>();
		for (TileObject object : sceneObjects(player.getWorldView().getPlane()))
		{
			int dist = object.getLocalLocation().distanceTo(lp) / 128;
			if (dist > 12)
			{
				continue;
			}
			ModelViewerFrame.ObjectSighting current = best.get(object.getId());
			if (current != null && current.distanceTiles <= dist)
			{
				continue;
			}
			String name = client.getObjectDefinition(object.getId()).getName();
			if (name == null || name.isEmpty() || name.equals("null"))
			{
				continue;
			}
			best.put(object.getId(), new ModelViewerFrame.ObjectSighting(object.getId(), name, dist));
		}
		List<ModelViewerFrame.ObjectSighting> out = new ArrayList<>(best.values());
		out.sort((a, b) -> Integer.compare(a.distanceTiles, b.distanceTiles));
		return out.size() > 30 ? new ArrayList<>(out.subList(0, 30)) : out;
	}

	// ==================== NPCs and graphics ====================

	private void rebuildProfiledNpcs()
	{
		Set<Integer> ids = new HashSet<>();
		for (EmitterProfile profile : store.snapshotAll().values())
		{
			if (profile.isNpcTarget() && profile.getNpcId() >= 0)
			{
				ids.add(profile.getNpcId());
			}
		}
		profiledNpcIds = ids;
		npcEmitters.clear();
		if (ids.isEmpty())
		{
			return;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null)
			{
				trackNpc(npc);
			}
		}
	}

	private void rebuildGraphicStyles()
	{
		graphicEmitters.clear();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isGraphicTarget() || profile.getGraphicId() < 0 || !profile.isEnabled()
				|| (!developerMode && ParticlesPanel.Category.isWip(profile)))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style == null)
			{
				continue;
			}
			int[] locals = new int[profile.getVertices().size()];
			int i = 0;
			for (int local : profile.getVertices())
			{
				locals[i++] = local;
			}
			graphicEmitters.computeIfAbsent(profile.getGraphicId(), k -> new ArrayList<>())
				.add(new GraphicEmitter(style, profile.getSignature(), locals));
		}
	}

	/**
	 * Map a vertex-based graphic emitter onto the spot anim's model, once
	 * per rebuild. Attaches across every matching piece like the other
	 * vertex targets.
	 */
	private static void resolveGraphic(GraphicEmitter ge, Model model)
	{
		ge.resolveTried = true;
		if (ge.signature == null || ge.locals.length == 0)
		{
			return;
		}
		List<Integer> globals = new ArrayList<>();
		for (ModelSnapshot.Piece piece : ModelSnapshot.capture(model).getPieces())
		{
			if (!piece.matchesSignature(ge.signature))
			{
				continue;
			}
			int[] pieceVerts = piece.verticesFor(ge.signature);
			for (int local : ge.locals)
			{
				if (local >= 0 && local < pieceVerts.length)
				{
					globals.add(pieceVerts[local]);
				}
			}
		}
		if (!globals.isEmpty())
		{
			ge.globals = new int[globals.size()];
			for (int i = 0; i < ge.globals.length; i++)
			{
				ge.globals[i] = globals.get(i);
			}
		}
	}

	/**
	 * One spawn for a graphic emitter: on a random attached vertex when the
	 * profile picked some (falling back to the base point if the piece never
	 * resolved), else at the base point.
	 */
	private void spawnGraphic(GraphicEmitter ge, @Nullable Model model, float baseX, float baseY, float baseZ)
	{
		ParticleStyle style = ge.style;
		float x = baseX + style.getOffsetX();
		float y = baseY + style.getOffsetY();
		float z = baseZ - style.getOffsetZ();
		if (model != null && ge.globals != null)
		{
			int g = ge.globals[random.nextInt(ge.globals.length)];
			if (g >= 0 && g < model.getVerticesCount())
			{
				x += model.getVerticesX()[g];
				y += model.getVerticesZ()[g];
				z += model.getVerticesY()[g];
			}
		}
		spawnAt(style, x, y, z, 1f);
	}

	/**
	 * NPCs run the player pipeline - posed model anchors, orientation,
	 * trails, feathering, animation gates - minus the equipment resolution
	 * (the NPC ID is the whole identity) and minus the stack gate (stacked
	 * NPCs are rare enough that all tracked ones emit).
	 */
	private void processNpcs(float dt, int level, int radiusUnits, LocalPoint localLp)
	{
		if (npcEmitters.isEmpty())
		{
			return;
		}
		int revision = store.getRevision();
		boolean markers = config.showAnchor() && developerMode;
		for (Map.Entry<NPC, PlayerEmitters> entry : npcEmitters.entrySet())
		{
			NPC npc = entry.getKey();
			PlayerEmitters pe = entry.getValue();
			if (npc.getWorldLocation().getPlane() != level
				|| npc.getLocalLocation().distanceTo(localLp) > radiusUnits)
			{
				pe.anchorCount = 0;
				pe.prevCount = 0;
				for (ActiveEmitter emitter : pe.emitters)
				{
					emitter.carry = 0;
				}
				continue;
			}
			if (pe.revision != revision || pe.equipmentIds == null
				|| pe.equipmentIds[0] != npc.getId())
			{
				resolveNpc(pe, npc, revision);
			}
			updateAnchors(pe, npc, markers);
			emit(dt, pe, npc);
			emitActorSpotAnims(dt, pe, npc);
		}
	}

	private void resolveNpc(PlayerEmitters pe, NPC npc, int revision)
	{
		pe.revision = revision;
		// The equipment cache slot doubles as the resolve key for NPCs: a
		// single-element array holding the (transform-aware) NPC ID
		pe.equipmentIds = new int[]{npc.getId()};
		pe.rebuilt = true;
		pe.emitters.clear();
		Model model = npc.getModel();
		if (model == null)
		{
			pe.equipmentIds = null;
			return;
		}
		ModelSnapshot snapshot = ModelSnapshot.capture(model);
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isNpcTarget() || profile.getNpcId() != npc.getId()
				|| !profile.isEnabled() || profile.getVertices().isEmpty()
				|| (!developerMode && ParticlesPanel.Category.isWip(profile)))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style == null)
			{
				continue;
			}
			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				if (!piece.matchesSignature(profile.getSignature()))
				{
					continue;
				}
				List<Integer> globals = new ArrayList<>();
				int[] pieceVerts = piece.verticesFor(profile.getSignature());
				for (int local : profile.getVertices())
				{
					if (local >= 0 && local < pieceVerts.length)
					{
						globals.add(pieceVerts[local]);
					}
				}
				if (!globals.isEmpty())
				{
					int[] vertices = new int[globals.size()];
					for (int i = 0; i < vertices.length; i++)
					{
						vertices[i] = globals.get(i);
					}
					int[][] chains = style.getFeatherStrength() > 0
						? buildChains(snapshot, piece, vertices)
						: null;
					pe.emitters.add(new ActiveEmitter(style, vertices, chains));
				}
			}
		}
	}

	/**
	 * Point emission for graphic profiles matching the actor's active spot
	 * anims (vengeance, skulls, teleports). No vertices or feathering - the
	 * anchor is the actor's base height plus the spot anim's height offset.
	 */
	private void emitActorSpotAnims(float dt, PlayerEmitters pe, Actor actor)
	{
		if (graphicEmitters.isEmpty())
		{
			return;
		}
		int budget = config.maxParticles();
		float densityScale = config.density().getFactor();
		LocalPoint lp = actor.getLocalLocation();
		int actorBase = Integer.MIN_VALUE;
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			List<GraphicEmitter> list = graphicEmitters.get(spotAnim.getId());
			if (list == null)
			{
				continue;
			}
			if (pe.spotAnimCarries == null)
			{
				pe.spotAnimCarries = new HashMap<>();
			}
			double[] carries = pe.spotAnimCarries.get(spotAnim.getId());
			if (carries == null || carries.length != list.size())
			{
				carries = new double[list.size()];
				pe.spotAnimCarries.put(spotAnim.getId(), carries);
			}
			Model model = null;
			for (int gi = 0; gi < list.size(); gi++)
			{
				GraphicEmitter ge = list.get(gi);
				float sustainable = budget / ge.style.getLifetimeSec() * 0.95f;
				carries[gi] += Math.min(ge.style.getParticlesPerSecond() * densityScale, sustainable) * dt;
				int count = (int) carries[gi];
				carries[gi] -= count;
				if (count == 0)
				{
					continue;
				}
				if (model == null)
				{
					model = spotAnim.getModel();
				}
				if (!ge.resolveTried && model != null)
				{
					resolveGraphic(ge, model);
				}
				if (actorBase == Integer.MIN_VALUE)
				{
					actorBase = Perspective.getFootprintTileHeight(client, lp, anchorLevel, actor.getFootprintSize())
						- actor.getAnimationHeightOffset();
				}
				float z = actorBase - spotAnim.getHeight();
				for (int i = 0; i < count; i++)
				{
					if (particleSystem.getParticles().size() >= budget)
					{
						return;
					}
					spawnGraphic(ge, model, lp.getX(), lp.getY(), z);
				}
			}
		}
	}

	/**
	 * Point emission at live graphics objects (spell splats, tile effects)
	 * whose IDs carry a graphic profile.
	 */
	private void emitGraphicsObjects(float dt, int level, int radiusUnits, LocalPoint localLp)
	{
		if (graphicEmitters.isEmpty())
		{
			if (!graphicCarries.isEmpty())
			{
				graphicCarries.clear();
			}
			return;
		}
		liveGraphics.clear();
		int budget = config.maxParticles();
		float densityScale = config.density().getFactor();
		for (GraphicsObject graphic : client.getTopLevelWorldView().getGraphicsObjects())
		{
			if (graphic.finished())
			{
				continue;
			}
			List<GraphicEmitter> list = graphicEmitters.get(graphic.getId());
			if (list == null)
			{
				continue;
			}
			liveGraphics.add(graphic);
			LocalPoint lp = graphic.getLocation();
			if (graphic.getLevel() != level || lp.distanceTo(localLp) > radiusUnits)
			{
				continue;
			}
			double[] carries = graphicCarries.get(graphic);
			if (carries == null || carries.length != list.size())
			{
				carries = new double[list.size()];
				graphicCarries.put(graphic, carries);
			}
			Model model = graphic.getModel();
			for (int gi = 0; gi < list.size(); gi++)
			{
				GraphicEmitter ge = list.get(gi);
				if (!ge.resolveTried && model != null)
				{
					resolveGraphic(ge, model);
				}
				float sustainable = budget / ge.style.getLifetimeSec() * 0.95f;
				carries[gi] += Math.min(ge.style.getParticlesPerSecond() * densityScale, sustainable) * dt;
				int count = (int) carries[gi];
				carries[gi] -= count;
				for (int i = 0; i < count; i++)
				{
					if (particleSystem.getParticles().size() >= budget)
					{
						return;
					}
					spawnGraphic(ge, model, lp.getX(), lp.getY(), graphic.getZ());
				}
			}
		}
		graphicCarries.keySet().retainAll(liveGraphics);
	}

	/**
	 * Nearby NPCs for the viewer's capture list: one entry per NPC ID,
	 * nearest instance, sorted by distance. Client thread.
	 */
	private List<ModelViewerFrame.ObjectSighting> npcSightings()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return List.of();
		}
		LocalPoint lp = player.getLocalLocation();
		int plane = player.getWorldView().getPlane();
		Map<Integer, ModelViewerFrame.ObjectSighting> best = new HashMap<>();
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getWorldLocation().getPlane() != plane)
			{
				continue;
			}
			int dist = npc.getLocalLocation().distanceTo(lp) / 128;
			if (dist > 12)
			{
				continue;
			}
			ModelViewerFrame.ObjectSighting current = best.get(npc.getId());
			if (current != null && current.distanceTiles <= dist)
			{
				continue;
			}
			String name = npc.getName();
			if (name == null || name.isEmpty() || name.equals("null"))
			{
				continue;
			}
			best.put(npc.getId(), new ModelViewerFrame.ObjectSighting(npc.getId(), name, dist));
		}
		List<ModelViewerFrame.ObjectSighting> out = new ArrayList<>(best.values());
		out.sort((a, b) -> Integer.compare(a.distanceTiles, b.distanceTiles));
		return out.size() > 30 ? new ArrayList<>(out.subList(0, 30)) : out;
	}

	/**
	 * Recently seen spot anim / graphics IDs as [id, count, secondsAgo],
	 * newest first. Client thread.
	 */
	private List<int[]> recentGraphicList()
	{
		long nowMs = System.currentTimeMillis();
		List<int[]> out = new ArrayList<>(recentGraphics.size());
		for (Map.Entry<Integer, long[]> entry : recentGraphics.entrySet())
		{
			out.add(new int[]{
				entry.getKey(),
				(int) entry.getValue()[0],
				(int) ((nowMs - entry.getValue()[1]) / 1000)});
		}
		out.sort((a, b) -> Integer.compare(a[2], b[2]));
		return out;
	}

	// ==================== ModelViewerFrame.Callbacks ====================

	@Override
	public void refreshSnapshot()
	{
		int objectId = viewerObjectId;
		int npcId = viewerNpcId;
		int graphicId = viewerGraphicId;
		clientThread.invokeLater(() ->
		{
			if (objectId >= 0)
			{
				captureObjectSnapshot(objectId);
			}
			else if (npcId >= 0)
			{
				captureNpcSnapshot(npcId);
			}
			else if (graphicId >= 0)
			{
				captureGraphicSnapshot(graphicId);
			}
			else
			{
				capturePlayerSnapshot();
			}
		});
	}

	@Override
	public void loadObject(int objectId)
	{
		viewerObjectId = objectId;
		viewerNpcId = -1;
		viewerGraphicId = -1;
		refreshSnapshot();
	}

	@Override
	public void loadNpc(int npcId)
	{
		viewerNpcId = npcId;
		viewerObjectId = -1;
		viewerGraphicId = -1;
		refreshSnapshot();
	}

	@Override
	public void loadGraphic(int graphicId)
	{
		viewerGraphicId = graphicId;
		viewerObjectId = -1;
		viewerNpcId = -1;
		refreshSnapshot();
	}

	@Override
	public void playerViewSelected()
	{
		if (viewerObjectId != -1 || viewerNpcId != -1 || viewerGraphicId != -1)
		{
			viewerObjectId = -1;
			viewerNpcId = -1;
			viewerGraphicId = -1;
			refreshSnapshot();
		}
	}

	@Override
	public String createGraphicProfile(int graphicId)
	{
		return store.ensureGraphicProfile(graphicId, "gfx " + graphicId);
	}

	/**
	 * Capture the local player's composite into the viewer. Client thread.
	 */
	private void capturePlayerSnapshot()
	{
		pendingGraphicCapture = -1;
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		PlayerComposition composition = player.getPlayerComposition();
		Model model = player.getModel();
		if (composition == null || model == null)
		{
			return;
		}
		ModelSnapshot snapshot = ModelSnapshot.capture(model);
		List<String> wornItems = new ArrayList<>();
		for (int itemId : wornItemIds(composition))
		{
			wornItems.add(itemId + " - " + client.getItemDefinition(itemId).getName());
		}
		List<int[]> recent = recentProjectileList();
		List<ModelViewerFrame.ObjectSighting> sightings = nearbySightings();
		List<ModelViewerFrame.ObjectSighting> npcs = npcSightings();
		List<int[]> recentGfx = recentGraphicList();
		SwingUtilities.invokeLater(() ->
		{
			viewerSnapshot = snapshot;
			viewerTargetName = null;
			if (viewerFrame != null)
			{
				viewerFrame.setSnapshot(snapshot,
					selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()),
					profileEntriesBySignature(), wornItems,
					projectileProfileEntries(), recent,
					objectProfileEntries(), sightings,
					npcProfileEntries(), npcs,
					graphicProfileEntries(), recentGfx);
			}
		});
	}

	/**
	 * Capture the nearest instance of this object into the viewer, falling
	 * back to the player when none is loaded. Client thread.
	 */
	private void captureObjectSnapshot(int objectId)
	{
		pendingGraphicCapture = -1;
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		LocalPoint lp = player.getLocalLocation();
		TileObject best = null;
		int bestDist = Integer.MAX_VALUE;
		for (TileObject object : sceneObjects(player.getWorldView().getPlane()))
		{
			if (object.getId() != objectId)
			{
				continue;
			}
			int dist = object.getLocalLocation().distanceTo(lp);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = object;
			}
		}
		Model model = best == null ? null : objectModel(best);
		if (model == null)
		{
			SwingUtilities.invokeLater(() -> viewerObjectId = -1);
			capturePlayerSnapshot();
			return;
		}
		String name = cleanTargetName(client.getObjectDefinition(objectId).getName());
		pushNonPlayerSnapshot(ModelSnapshot.capture(model), name);
	}

	/**
	 * Capture the nearest instance of this NPC into the viewer, falling back
	 * to the player when none is in scene. Client thread.
	 */
	private void captureNpcSnapshot(int npcId)
	{
		pendingGraphicCapture = -1;
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		LocalPoint lp = player.getLocalLocation();
		NPC best = null;
		int bestDist = Integer.MAX_VALUE;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getId() != npcId)
			{
				continue;
			}
			int dist = npc.getLocalLocation().distanceTo(lp);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = npc;
			}
		}
		Model model = best == null ? null : best.getModel();
		if (model == null)
		{
			SwingUtilities.invokeLater(() -> viewerNpcId = -1);
			capturePlayerSnapshot();
			return;
		}
		String name = cleanTargetName(best.getName());
		pushNonPlayerSnapshot(ModelSnapshot.capture(model), name);
	}

	/**
	 * Capture a live instance of this spot anim into the viewer - a graphics
	 * object or an actor playing it - falling back to the player when none
	 * is active. Client thread.
	 */
	private void captureGraphicSnapshot(int graphicId)
	{
		Model model = findGraphicModel(graphicId);
		if (model == null)
		{
			// Not playing right now; capture it the moment it next appears
			pendingGraphicCapture = graphicId;
			return;
		}
		pushGraphicSnapshot(model);
	}

	@Nullable
	private Model findGraphicModel(int graphicId)
	{
		for (GraphicsObject graphic : client.getTopLevelWorldView().getGraphicsObjects())
		{
			if (!graphic.finished() && graphic.getId() == graphicId)
			{
				Model model = graphic.getModel();
				if (model != null)
				{
					return model;
				}
			}
		}
		for (Player p : client.getTopLevelWorldView().players())
		{
			Model model = p == null ? null : spotAnimModel(p, graphicId);
			if (model != null)
			{
				return model;
			}
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			Model model = npc == null ? null : spotAnimModel(npc, graphicId);
			if (model != null)
			{
				return model;
			}
		}
		return null;
	}

	private void pushGraphicSnapshot(Model model)
	{
		pendingGraphicCapture = -1;
		pushNonPlayerSnapshot(ModelSnapshot.capture(model), null);
		SwingUtilities.invokeLater(() ->
		{
			if (viewerFrame != null)
			{
				viewerFrame.showModelView();
			}
		});
	}

	@Nullable
	private static Model spotAnimModel(Actor actor, int graphicId)
	{
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			if (spotAnim.getId() == graphicId)
			{
				return spotAnim.getModel();
			}
		}
		return null;
	}

	/**
	 * A usable game name, or null: strips color tags and rejects the
	 * cache's "null" placeholder.
	 */
	@Nullable
	private static String cleanTargetName(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		String clean = net.runelite.client.util.Text.removeTags(name).trim();
		return clean.isEmpty() || clean.equals("null") ? null : clean;
	}

	/**
	 * Push an object, NPC or graphic snapshot to the viewer. Client thread.
	 */
	private void pushNonPlayerSnapshot(ModelSnapshot snapshot, @Nullable String targetName)
	{
		List<int[]> recent = recentProjectileList();
		List<ModelViewerFrame.ObjectSighting> sightings = nearbySightings();
		List<ModelViewerFrame.ObjectSighting> npcs = npcSightings();
		List<int[]> recentGfx = recentGraphicList();
		SwingUtilities.invokeLater(() ->
		{
			viewerSnapshot = snapshot;
			viewerTargetName = targetName;
			if (viewerFrame != null)
			{
				viewerFrame.setSnapshot(snapshot,
					selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()),
					profileEntriesBySignature(), List.of(),
					projectileProfileEntries(), recent,
					objectProfileEntries(), sightings,
					npcProfileEntries(), npcs,
					graphicProfileEntries(), recentGfx);
			}
		});
	}

	@Override
	public void vertexToggled(@Nullable String profileKey, int globalVertex)
	{
		ModelSnapshot snapshot = viewerSnapshot;
		if (snapshot == null)
		{
			return;
		}
		ModelSnapshot.Piece piece = snapshot.pieceContaining(globalVertex);
		if (piece == null)
		{
			return;
		}

		// If the selected profile's piece isn't on this model at all (stale
		// signature or authored on other gear), clicking a vertex re-attaches
		// the profile to the clicked piece, keeping its style and filter.
		// Confirmed, because doing this accidentally hijacks the profile.
		if (profileKey != null)
		{
			EmitterProfile selected = store.snapshotAll().get(profileKey);
			if (selected != null && !selected.isProjectileTarget()
				&& !signaturePresent(snapshot, selected.getSignature()))
			{
				if (javax.swing.JOptionPane.showConfirmDialog(viewerFrame,
					"Re-attach profile '" + selected.getName() + "' to this piece? Its vertices will be re-picked.",
					"Re-attach profile", javax.swing.JOptionPane.YES_NO_OPTION)
					!= javax.swing.JOptionPane.YES_OPTION)
				{
					return;
				}
				store.rebind(profileKey, piece.getSignature());
			}
		}

		int local = piece.localIndexOf(globalVertex);
		if (local < 0)
		{
			return;
		}

		String target = targetProfileKey(profileKey, piece);
		store.toggleVertex(target, local);
		refreshViewerRows(target);
		refreshViewerMarkers();
	}

	private static boolean signaturePresent(ModelSnapshot snapshot, String signature)
	{
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			if (piece.getSignature().equals(signature))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public void boxSelected(@Nullable String profileKey, Set<Integer> globalVertices, boolean add)
	{
		ModelSnapshot snapshot = viewerSnapshot;
		if (snapshot == null)
		{
			return;
		}

		Map<String, List<Integer>> localsByTarget = new HashMap<>();
		for (int globalVertex : globalVertices)
		{
			ModelSnapshot.Piece piece = snapshot.pieceContaining(globalVertex);
			if (piece == null)
			{
				continue;
			}
			int local = piece.localIndexOf(globalVertex);
			if (local < 0)
			{
				continue;
			}
			String target = add
				? targetProfileKey(profileKey, piece)
				: existingProfileKey(profileKey, piece);
			if (target != null)
			{
				localsByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(local);
			}
		}

		for (Map.Entry<String, List<Integer>> entry : localsByTarget.entrySet())
		{
			if (add)
			{
				store.addVertices(entry.getKey(), entry.getValue());
			}
			else
			{
				store.removeVertices(entry.getKey(), entry.getValue());
			}
		}
		refreshViewerRows(localsByTarget.size() == 1
			? localsByTarget.keySet().iterator().next()
			: viewerFrame != null ? viewerFrame.getSelectedProfileKey() : null);
		refreshViewerMarkers();
	}

	@Override
	public void selectionChanged()
	{
		refreshViewerMarkers();
	}

	@Override
	@Nullable
	public EmitterProfile profile(String profileKey)
	{
		return store.snapshotAll().get(profileKey);
	}

	@Override
	public void saveProfile(String profileKey, EmitterProfile profile)
	{
		store.updateStyle(profileKey, profile);
	}

	@Override
	@Nullable
	public String duplicateProfile(String profileKey)
	{
		return store.duplicate(profileKey);
	}

	@Override
	public void deleteProfile(String profileKey)
	{
		store.delete(profileKey);
		refreshViewerRows(null);
		refreshViewerMarkers();
	}

	@Override
	public String createProjectileProfile(int projectileId)
	{
		return store.ensureProjectileProfile(projectileId, "proj " + projectileId);
	}

	// ====================================================================

	/**
	 * The profile a click should apply to: the viewer's selected profile when
	 * it targets this piece, otherwise the piece's default profile (created
	 * if missing).
	 */
	private String targetProfileKey(@Nullable String profileKey, ModelSnapshot.Piece piece)
	{
		String existing = existingProfileKey(profileKey, piece);
		if (existing != null)
		{
			return existing;
		}
		String defaultName = piece.getVertices().length + "v " + piece.getFaces().length + "f";
		// Prefer the game name captured with the snapshot; fall back to the
		// old id/verts/faces naming when the cache offers none
		if (viewerObjectId >= 0)
		{
			return store.ensureObjectPieceProfile(piece.getSignature(),
				viewerTargetName != null ? viewerTargetName : "obj " + viewerObjectId + " " + defaultName,
				viewerObjectId);
		}
		if (viewerNpcId >= 0)
		{
			return store.ensureNpcPieceProfile(piece.getSignature(),
				viewerTargetName != null ? viewerTargetName : "npc " + viewerNpcId + " " + defaultName,
				viewerNpcId);
		}
		if (viewerGraphicId >= 0)
		{
			return store.ensureGraphicPieceProfile(piece.getSignature(),
				"gfx " + viewerGraphicId + " " + defaultName, viewerGraphicId);
		}
		return store.ensureProfileFor(piece.getSignature(), defaultName);
	}

	@Nullable
	private String existingProfileKey(@Nullable String profileKey, ModelSnapshot.Piece piece)
	{
		if (profileKey != null)
		{
			EmitterProfile selected = store.snapshotAll().get(profileKey);
			if (selected != null && piece.getSignature().equals(selected.getSignature())
				&& matchesViewerContext(selected))
			{
				return profileKey;
			}
		}
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			if (piece.getSignature().equals(entry.getValue().getSignature())
				&& matchesViewerContext(entry.getValue()))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Does this profile belong to what the viewer is showing - the loaded
	 * object, or the player? Signatures alone could collide across the two
	 * families. EDT only.
	 */
	private boolean matchesViewerContext(EmitterProfile profile)
	{
		if (viewerObjectId >= 0)
		{
			return profile.isObjectTarget() && profile.getObjectId() == viewerObjectId;
		}
		if (viewerNpcId >= 0)
		{
			return profile.isNpcTarget() && profile.getNpcId() == viewerNpcId;
		}
		if (viewerGraphicId >= 0)
		{
			return profile.isGraphicTarget() && profile.getGraphicId() == viewerGraphicId;
		}
		return EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType());
	}

	/**
	 * Rebuild the viewer's row list in place (a toggle may have created a
	 * profile), selecting the given profile's row. EDT only.
	 */
	private void refreshViewerRows(@Nullable String selectProfileKey)
	{
		if (viewerFrame == null)
		{
			return;
		}
		if (selectProfileKey != null)
		{
			viewerFrame.selectProfileOnNextSnapshot(selectProfileKey);
		}
		viewerFrame.refreshRows(profileEntriesBySignature(), projectileProfileEntries(),
			objectProfileEntries(), npcProfileEntries(), graphicProfileEntries());
	}

	private void refreshViewerMarkers()
	{
		ModelSnapshot snapshot = viewerSnapshot;
		if (viewerFrame == null || snapshot == null)
		{
			return;
		}
		viewerFrame.setSelectedVertices(selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()));
		viewerFrame.refreshStyleEditor();
	}

	/**
	 * Global indices of stored emitters on the snapshot's pieces: the selected
	 * profile's when one is selected, else all profiles'. EDT only.
	 */
	private Set<Integer> selectedGlobals(ModelSnapshot snapshot, @Nullable String profileKey)
	{
		Map<String, EmitterProfile> profiles = store.snapshotAll();
		Set<Integer> out = new HashSet<>();
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
			{
				// Context matters: trivial fragments (a 5v4f flame) share a
				// signature across unrelated models, so another object's
				// profile must not highlight on this snapshot
				if (!piece.getSignature().equals(entry.getValue().getSignature())
					|| !matchesViewerContext(entry.getValue())
					|| (profileKey != null && !profileKey.equals(entry.getKey())))
				{
					continue;
				}
				for (int local : entry.getValue().getVertices())
				{
					if (local >= 0 && local < piece.getVertices().length)
					{
						out.add(piece.getVertices()[local]);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Recently seen projectiles as [id, count, secondsAgo], newest first.
	 * Client thread.
	 */
	private List<int[]> recentProjectileList()
	{
		long nowMs = System.currentTimeMillis();
		List<int[]> out = new ArrayList<>(recentProjectiles.size());
		for (Map.Entry<Integer, long[]> entry : recentProjectiles.entrySet())
		{
			out.add(new int[]{
				entry.getKey(),
				(int) entry.getValue()[0],
				(int) ((nowMs - entry.getValue()[1]) / 1000)});
		}
		out.sort((a, b) -> Integer.compare(a[2], b[2]));
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> projectileProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isProjectileTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					profile.getName() + " [proj " + profile.getProjectileId() + "]",
					!profile.getItemIds().isEmpty()));
			}
		}
		return out;
	}

	private Map<String, List<ModelViewerFrame.ProfileEntry>> profileEntriesBySignature()
	{
		Map<String, List<ModelViewerFrame.ProfileEntry>> out = new HashMap<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			// Only the family the viewer is showing; object and player
			// profiles both carry signatures
			if (profile.getSignature() == null || !matchesViewerContext(profile))
			{
				continue;
			}
			out.computeIfAbsent(profile.getSignature(), k -> new ArrayList<>())
				.add(new ModelViewerFrame.ProfileEntry(entry.getKey(), profile.getName(),
					!profile.getItemIds().isEmpty()));
		}
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> objectProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isObjectTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					profile.getName() + " [obj " + profile.getObjectId() + "]", false));
			}
		}
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> npcProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isNpcTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					profile.getName() + " [npc " + profile.getNpcId() + "]", false));
			}
		}
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> graphicProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isGraphicTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					profile.getName() + " [gfx " + profile.getGraphicId() + "]", false));
			}
		}
		return out;
	}

	/**
	 * Open the vertex picker with this profile selected, if its piece is on
	 * the current model. EDT only.
	 */
	private void editProfile(String profileKey)
	{
		// Object and NPC profiles edit against their own model, not the player's
		EmitterProfile profile = store.snapshotAll().get(profileKey);
		if (profile != null && profile.isObjectTarget())
		{
			viewerObjectId = profile.getObjectId();
			viewerNpcId = -1;
			viewerGraphicId = -1;
		}
		else if (profile != null && profile.isNpcTarget())
		{
			viewerNpcId = profile.getNpcId();
			viewerObjectId = -1;
			viewerGraphicId = -1;
		}
		else if (profile != null && !profile.isProjectileTarget() && !profile.isGraphicTarget())
		{
			viewerObjectId = -1;
			viewerNpcId = -1;
			viewerGraphicId = -1;
		}
		openViewer();
		if (viewerFrame != null)
		{
			viewerFrame.selectProfileOnNextSnapshot(profileKey);
		}
	}

	private void renameProfile(String profileKey)
	{
		EmitterProfile profile = store.snapshotAll().get(profileKey);
		if (profile == null)
		{
			return;
		}
		String name = javax.swing.JOptionPane.showInputDialog(panel,
			"Nickname for this profile:", profile.getName());
		if (name != null)
		{
			store.rename(profileKey, name);
		}
	}

	private void refreshPanel()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.rebuild(store.snapshotAll(), presentSignatures);
			}
		});
	}

	private static BufferedImage createIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(255, 152, 31));
		g.fillOval(2, 10, 5, 5);
		g.fillOval(9, 8, 4, 4);
		g.setColor(new Color(255, 152, 31, 170));
		g.fillOval(6, 4, 3, 3);
		g.fillOval(11, 1, 3, 3);
		g.dispose();
		return image;
	}

	@Provides
	ParticlesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ParticlesConfig.class);
	}
}
