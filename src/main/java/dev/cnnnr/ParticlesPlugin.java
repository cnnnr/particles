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
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
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
	description = "Emit particles from model vertices",
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
		int anchorCount;
		int prevCount;
		boolean rebuilt;
		int stamp;
	}

	private final Map<Player, PlayerEmitters> playerEmitters = new HashMap<>();
	private int playerStamp;
	/**
	 * Provable-visibility gate for the engine's stacked-actor dedup
	 * (tileLastDrawnActor): emit only when the engine PROVABLY draws the
	 * player - movers, the local player, and sole centered tile occupants.
	 * Contested tiles stay silent rather than risk emitting for a hidden
	 * player; exact stack winners aren't reliably reconstructible from
	 * plugin-visible state. Reused per tick.
	 */
	private final Map<Long, Player> tileOwners = new HashMap<>();
	/**
	 * Tiles where two or more centered players overlap this tick.
	 */
	private final Set<Long> contestedTiles = new HashSet<>();
	/**
	 * Tiles claimed by a size-1 NPC standing at their center; the engine's
	 * NPC pass runs before other players, so those players aren't drawn.
	 */
	private final Set<Long> npcClaimedTiles = new HashSet<>();

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

	@Override
	protected void startUp() throws Exception
	{
		developerMode &= !PREVIEW_PLAYER_VIEW;
		lastNanos = System.nanoTime();
		stylesRevision = -1;
		playerEmitters.clear();
		renderer = new ParticleRenderer(client);
		store = new EmitterStore(configManager, gson);
		store.load();
		store.setChangeListener(this::refreshPanel);

		overlayManager.add(overlay);

		panel = new ParticlesPanel(developerMode, this::openViewer, store::setEnabled, store::delete,
			this::renameProfile, this::editProfile);
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
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
				// The scene is rebasing; live particles' local coordinates
				// become meaningless
				particleSystem.clear(DISCARD);
				renderer.reset();
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

		// Provable-visibility gate against the engine's stacked-actor dedup
		// (tileLastDrawnActor / tileLastOccupiedCycle): the dedup only
		// applies to actors standing exactly at tile center, so movers and
		// the local player are always drawn, and a sole centered occupant of
		// an NPC-free tile is always drawn. Contested tiles are left silent -
		// the engine's exact winner isn't reliably reconstructible here.
		playerStamp++;
		tileOwners.clear();
		contestedTiles.clear();
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
			if (tileOwners.putIfAbsent(key, player) != null)
			{
				contestedTiles.add(key);
			}
		}

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
			if (player.getWorldLocation().getPlane() != level)
			{
				drawn = false;
			}
			else if (!isCentered(player) || player == localPlayer)
			{
				drawn = true;
			}
			else
			{
				long key = tileKey(player);
				drawn = !contestedTiles.contains(key)
					&& !npcClaimedTiles.contains(key)
					&& tileOwners.get(key) == player;
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
		}
		Iterator<PlayerEmitters> peIt = playerEmitters.values().iterator();
		while (peIt.hasNext())
		{
			if (peIt.next().stamp != playerStamp)
			{
				peIt.remove();
			}
		}

		emitProjectiles(dt);
		particleSystem.update(dt, deathStats);
		renderer.sync(particleSystem.getParticles(), anchorWorldView, anchorLevel);
		updateStats(now);
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
	private void updateAnchors(PlayerEmitters pe, Player player, boolean markers)
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
			for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
			{
				EmitterProfile profile = entry.getValue();
				if (!EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
					|| !piece.getSignature().equals(profile.getSignature())
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
				for (int local : profile.getVertices())
				{
					if (local >= 0 && local < piece.getVertices().length)
					{
						globals.add(piece.getVertices()[local]);
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

	private void emit(float dt, PlayerEmitters pe, Player player)
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
		if (player == client.getLocalPlayer())
		{
			lastActionAnimation = actionAnimation != -1 ? actionAnimation : lastActionAnimation;
		}
		// Dynamic lifetime: at run speed a full-lifetime plume smears a tile
		// behind the wearer, so flagged profiles halve it while THIS player's
		// movement animation is playing (weapon-specific run poses included)
		boolean moving = poseAnimation == player.getRunAnimation() || poseAnimation == player.getWalkAnimation();

		int budget = config.maxParticles();
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
			float rate = Math.min(style.getParticlesPerSecond(), sustainable);
			emitter.carry += rate * dt;
			int count = (int) emitter.carry;
			emitter.carry -= count;

			float lifeScale = style.isDynamicLifetime() && moving ? 0.5f : 1f;
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
			float density = style.getTrailDensity();
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
				carries[0] += Math.min(style.getParticlesPerSecond(), sustainable) * dt;
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
					double owed = carries[1] + Math.sqrt(distSq) / 128f * style.getTrailDensity();
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

	// ==================== ModelViewerFrame.Callbacks ====================

	@Override
	public void refreshSnapshot()
	{
		clientThread.invokeLater(() ->
		{
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
			SwingUtilities.invokeLater(() ->
			{
				viewerSnapshot = snapshot;
				if (viewerFrame != null)
				{
					viewerFrame.setSnapshot(snapshot,
						selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()),
						profileEntriesBySignature(), wornItems,
						projectileProfileEntries(), recent);
				}
			});
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
		return store.ensureProfileFor(piece.getSignature(), defaultName);
	}

	@Nullable
	private String existingProfileKey(@Nullable String profileKey, ModelSnapshot.Piece piece)
	{
		if (profileKey != null)
		{
			EmitterProfile selected = store.snapshotAll().get(profileKey);
			if (selected != null && piece.getSignature().equals(selected.getSignature()))
			{
				return profileKey;
			}
		}
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			if (piece.getSignature().equals(entry.getValue().getSignature()))
			{
				return entry.getKey();
			}
		}
		return null;
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
		viewerFrame.refreshRows(profileEntriesBySignature(), projectileProfileEntries());
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
				if (!piece.getSignature().equals(entry.getValue().getSignature())
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
			if (profile.getSignature() == null)
			{
				continue;
			}
			out.computeIfAbsent(profile.getSignature(), k -> new ArrayList<>())
				.add(new ModelViewerFrame.ProfileEntry(entry.getKey(), profile.getName(),
					!profile.getItemIds().isEmpty()));
		}
		return out;
	}

	/**
	 * Open the vertex picker with this profile selected, if its piece is on
	 * the current model. EDT only.
	 */
	private void editProfile(String profileKey)
	{
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
