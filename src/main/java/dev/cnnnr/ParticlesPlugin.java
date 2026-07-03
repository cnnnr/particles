package dev.cnnnr;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
		double carry;
		int anchorStart;
		int anchorCount;

		ActiveEmitter(ParticleStyle style, int[] vertices)
		{
			this.style = style;
			this.vertices = vertices;
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

	private final Random random = new Random();

	@Getter
	private final ParticleSystem particleSystem = new ParticleSystem();

	private ParticleRenderer renderer;
	private EmitterStore store;
	private NavigationButton navButton;
	private ParticlesPanel panel;
	private ModelViewerFrame viewerFrame;

	// Anchor state, updated once per client tick and read by the overlay.
	// Both run on the client thread, so no synchronization is needed.
	@Getter
	private int anchorCount;
	@Getter
	private float[] anchorXs = new float[0];
	@Getter
	private float[] anchorYs = new float[0];
	/**
	 * Absolute scene z per anchor (negative up), based on the same base height
	 * the engine renders the model at, so anchors stay glued on slopes.
	 */
	@Getter
	private float[] anchorZs = new float[0];
	@Getter
	private int anchorWorldView = -1;
	@Getter
	private int anchorLevel;

	// Last tick's anchor positions: spawns are spread along each anchor's
	// movement segment so fast-moving emitters (weapon swings) lay a smooth
	// trail instead of one clump per tick
	private float[] prevAnchorXs = new float[0];
	private float[] prevAnchorYs = new float[0];
	private float[] prevAnchorZs = new float[0];
	private int prevAnchorCount;
	private boolean anchorsRebuilt;
	/**
	 * Fractional carry of distance-based emission per anchor slot.
	 */
	private float[] trailCarry = new float[0];
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

	// Emitter resolution cache: rebuilt only when gear or profiles change.
	// Client thread.
	private final List<ActiveEmitter> activeEmitters = new ArrayList<>();
	private String resolvedCompositionKey;
	private int resolvedRevision = -1;
	private int stylesRevision = -1;

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
		lastNanos = System.nanoTime();
		resolvedCompositionKey = null;
		resolvedRevision = -1;
		stylesRevision = -1;
		renderer = new ParticleRenderer(client);
		store = new EmitterStore(configManager, gson);
		store.load();
		store.setChangeListener(this::refreshPanel);

		overlayManager.add(overlay);

		panel = new ParticlesPanel(this::openViewer, store::setEnabled, store::delete,
			this::renameProfile, this::editProfile);
		navButton = NavigationButton.builder()
			.tooltip("Particles")
			.icon(createIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
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

		clientThread.invokeLater(() ->
		{
			particleSystem.clear(p ->
			{
			});
			renderer.reset();
			anchorCount = 0;
		});

		log.debug("Particles stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
				// The scene is rebasing; live particles' local coordinates
				// become meaningless
				particleSystem.clear(p ->
				{
				});
				renderer.reset();
				break;
			case LOGGED_IN:
				// The player model is rebuilt on scene load and its vertex
				// indices can change; re-resolve emitters against the new
				// model or emission goes subtly wrong until gear is re-equipped
				resolvedCompositionKey = null;
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
		// up immediately since they're re-merged every tick
		if (stylesRevision != store.getRevision() && renderer.rebuildStyles(store.snapshotAll()))
		{
			stylesRevision = store.getRevision();
			resolvedCompositionKey = null;
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			anchorCount = 0;
			particleSystem.clear(p ->
			{
			});
			renderer.reset();
			lastLevel = -1;
			return;
		}

		// Drop all particles when the player changes floor; their positions
		// are only meaningful on the level they spawned on
		int level = player.getWorldView().getPlane();
		if (level != lastLevel)
		{
			particleSystem.clear(p ->
			{
			});
			lastLevel = level;
		}
		anchorWorldView = player.getLocalLocation().getWorldView();
		anchorLevel = level;

		updateAnchors(player);
		emit(dt, player);
		particleSystem.update(dt, this::onParticleDeath);
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
			+ " | last anim " + lastActionAnimation
			+ " | " + renderer.getCameraDebug();
		windowSpawns = 0;
		windowDeaths = 0;
		windowDeathAgeSum = 0;
	}

	/**
	 * Transform every active emitter's vertices from the player's posed model
	 * into world (local scene) coordinates.
	 */
	private void updateAnchors(Player player)
	{
		// Keep last tick's anchors for movement-segment interpolation
		prevAnchorCount = anchorCount;
		if (anchorCount > 0)
		{
			if (prevAnchorXs.length < anchorXs.length)
			{
				prevAnchorXs = new float[anchorXs.length];
				prevAnchorYs = new float[anchorXs.length];
				prevAnchorZs = new float[anchorXs.length];
			}
			System.arraycopy(anchorXs, 0, prevAnchorXs, 0, anchorCount);
			System.arraycopy(anchorYs, 0, prevAnchorYs, 0, anchorCount);
			System.arraycopy(anchorZs, 0, prevAnchorZs, 0, anchorCount);
		}
		anchorCount = 0;

		Model model = player.getModel();
		if (model == null)
		{
			return;
		}

		resolveEmitters(player, model);
		if (activeEmitters.isEmpty())
		{
			return;
		}

		int vertexCount = model.getVerticesCount();
		if (vertexCount == 0)
		{
			return;
		}

		int totalVertices = 0;
		for (ActiveEmitter emitter : activeEmitters)
		{
			totalVertices += emitter.vertices.length;
		}
		if (anchorXs.length < totalVertices)
		{
			anchorXs = new float[totalVertices];
			anchorYs = new float[totalVertices];
			anchorZs = new float[totalVertices];
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

		for (ActiveEmitter emitter : activeEmitters)
		{
			emitter.anchorStart = anchorCount;
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

				anchorXs[anchorCount] = lp.getX() + (vz * sin + vx * cos) / 65536f;
				anchorYs[anchorCount] = lp.getY() + (vz * cos - vx * sin) / 65536f;
				// Model y is already in the engine's negative-up convention
				anchorZs[anchorCount] = playerBaseZ + vy;
				anchorCount++;
				emitter.anchorCount++;
			}
		}

		if (trailCarry.length < anchorXs.length)
		{
			trailCarry = new float[anchorXs.length];
		}
		// Anchor slots only correspond across ticks while the layout is stable
		if (anchorsRebuilt || prevAnchorCount != anchorCount)
		{
			prevAnchorCount = 0;
			java.util.Arrays.fill(trailCarry, 0, trailCarry.length, 0f);
		}
		anchorsRebuilt = false;
	}

	/**
	 * @return whether an anchor's movement since last tick is usable as an
	 * emission segment (layout stable and not a teleport-sized jump)
	 */
	private boolean segmentUsable(int a)
	{
		if (prevAnchorCount != anchorCount)
		{
			return false;
		}
		float dx = anchorXs[a] - prevAnchorXs[a];
		float dy = anchorYs[a] - prevAnchorYs[a];
		float dz = anchorZs[a] - prevAnchorZs[a];
		return dx * dx + dy * dy + dz * dz <= MAX_TRAIL_SEGMENT * MAX_TRAIL_SEGMENT;
	}

	private float segmentLength(int a)
	{
		float dx = anchorXs[a] - prevAnchorXs[a];
		float dy = anchorYs[a] - prevAnchorYs[a];
		float dz = anchorZs[a] - prevAnchorZs[a];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Map stored piece-local emitters onto the current composite model,
	 * honoring each profile's worn item filter. Cached: re-runs only when
	 * equipment or the profile store changes.
	 */
	private void resolveEmitters(Player player, Model model)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			activeEmitters.clear();
			resolvedCompositionKey = null;
			return;
		}

		String key = compositionKey(composition);
		int revision = store.getRevision();
		if (key.equals(resolvedCompositionKey) && revision == resolvedRevision)
		{
			return;
		}
		resolvedCompositionKey = key;
		resolvedRevision = revision;
		anchorsRebuilt = true;

		Set<Integer> wornItemIds = wornItemIds(composition);
		Map<String, EmitterProfile> profiles = store.snapshotAll();
		ModelSnapshot snapshot = ModelSnapshot.capture(model);

		activeEmitters.clear();
		Set<String> present = new HashSet<>();
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			present.add(piece.getSignature());
			for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
			{
				EmitterProfile profile = entry.getValue();
				if (!piece.getSignature().equals(profile.getSignature())
					|| !profile.isEnabled()
					|| profile.getVertices().isEmpty())
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
				activeEmitters.add(new ActiveEmitter(style, vertices));
			}
		}

		if (!present.equals(presentSignatures))
		{
			presentSignatures = present;
			refreshPanel();
		}
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

	private static String compositionKey(PlayerComposition composition)
	{
		StringBuilder sb = new StringBuilder();
		for (int id : composition.getEquipmentIds())
		{
			sb.append(id).append(',');
		}
		return sb.toString();
	}

	private void emit(float dt, Player player)
	{
		if (anchorCount == 0)
		{
			for (ActiveEmitter emitter : activeEmitters)
			{
				emitter.carry = 0;
			}
			return;
		}

		// Animation gate inputs, shared by all emitters this tick
		int actionAnimation = player.getAnimation();
		int actionFrame = player.getAnimationFrame();
		int poseAnimation = player.getPoseAnimation();
		lastActionAnimation = actionAnimation != -1 ? actionAnimation : lastActionAnimation;

		int budget = config.maxParticles();
		for (ActiveEmitter emitter : activeEmitters)
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

			for (int i = 0; i < count; i++)
			{
				if (particleSystem.getParticles().size() >= budget)
				{
					return;
				}
				int a = emitter.anchorStart + random.nextInt(emitter.anchorCount);
				spawnParticle(emitter, a, random.nextFloat());
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
				if (!segmentUsable(a))
				{
					trailCarry[a] = 0;
					continue;
				}
				float owed = trailCarry[a] + segmentLength(a) / 128f * density;
				int n = (int) owed;
				trailCarry[a] = owed - n;
				for (int i = 0; i < n; i++)
				{
					if (particleSystem.getParticles().size() >= budget)
					{
						return;
					}
					// Stratified positions along the segment: an even ribbon
					spawnParticle(emitter, a, (i + random.nextFloat()) / n);
				}
			}
		}
	}

	/**
	 * Spawn one particle at fraction t along the anchor's movement segment
	 * since last tick (falling back to its current position), plus jitter.
	 */
	private void spawnParticle(ActiveEmitter emitter, int a, float t)
	{
		ParticleStyle style = emitter.style;

		float ax = anchorXs[a];
		float ay = anchorYs[a];
		float az = anchorZs[a];
		if (segmentUsable(a))
		{
			ax = prevAnchorXs[a] + (ax - prevAnchorXs[a]) * t;
			ay = prevAnchorYs[a] + (ay - prevAnchorYs[a]) * t;
			az = prevAnchorZs[a] + (az - prevAnchorZs[a]) * t;
		}

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
			style.getLifetimeSec(), style, sizeVariant, wobblePhase, wobbleFreq, spread);
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
			SwingUtilities.invokeLater(() ->
			{
				viewerSnapshot = snapshot;
				if (viewerFrame != null)
				{
					viewerFrame.setSnapshot(snapshot,
						selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()),
						profileEntriesBySignature(), wornItems);
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
			if (selected != null && !signaturePresent(snapshot, selected.getSignature()))
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
		viewerFrame.refreshRows(profileEntriesBySignature());
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
