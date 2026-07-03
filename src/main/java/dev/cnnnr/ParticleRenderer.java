package dev.cnnnr;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;

/**
 * Renders particles as camera-facing soft discs, batched into one merged
 * scene model per occupied tile.
 *
 * The client draws renderables through per-tile slots, and a tile only holds
 * a few - hundreds of individual RuneLiteObjects stacked on the same tile
 * mostly don't draw. So, like the game's own area effects, all particles on a
 * tile are merged into a single model rendered by one pooled RuneLiteObject.
 *
 * To keep the hot path allocation-free, batches are pre-built "canvases":
 * a model merged and lit ONCE from K disc slots, whose live vertex,
 * transparency and color arrays are then overwritten in place every tick -
 * no clones, merges or relights at steady state. Vertices are billboarded
 * toward the camera during the write; transparency (fade x radial falloff)
 * and colors are array-copied from pre-baked per-style templates, only when
 * a slot's style, size or fade step changes. Both lit and unlit color arrays
 * are written since renderers differ in which they consume.
 *
 * The engine welds identical-position vertices when merging, which would
 * break slot slicing, so the canvas prototype gets tiny per-vertex epsilon
 * offsets and the build is verified; on failure the renderer falls back to
 * legacy per-tick merging.
 *
 * All methods must be called on the client thread.
 */
@Slf4j
class ParticleRenderer
{
	/**
	 * Thickness of the billboard lens relative to its radius. Nonzero so the
	 * two hemispheres of the flattened source mesh aren't perfectly coplanar.
	 */
	private static final float LENS_FLATTEN = 0.12f;
	/**
	 * High ambient and weak directional light so discs read as self-lit glow
	 * instead of shaded surfaces.
	 */
	private static final int LIGHT_AMBIENT = 90;
	private static final int LIGHT_CONTRAST = 2000;
	/**
	 * Cap on faces per batch model; a tile gets multiple batch objects if its
	 * particles exceed this.
	 */
	private static final int MAX_FACES_PER_BATCH = 8000;
	private static final byte INVISIBLE = (byte) 254;

	/**
	 * A pre-merged, pre-lit batch model whose arrays are rewritten in place.
	 */
	private class BatchCanvas
	{
		final ModelData modelData;
		final Model lit;
		final float[] vx, vy, vz;
		final byte[] transparencies;
		final short[] unlitColors;
		final int[] colors1, colors2, colors3;
		final RuneLiteObject object;
		final ParticleStyle[] slotStyle;
		final int[] slotVariant;

		boolean claimed;
		long tileKey;
		LocalPoint centerLp;
		int centerHeight;
		int usedSlots;
		/**
		 * Highest slot count written since slots were last cleared; slots in
		 * [usedSlots, highWater) hold stale geometry and need clearing.
		 */
		int highWater;

		BatchCanvas(ModelData merged)
		{
			modelData = merged;
			lit = merged.light(LIGHT_AMBIENT, LIGHT_CONTRAST,
				ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
			vx = merged.getVerticesX();
			vy = merged.getVerticesY();
			vz = merged.getVerticesZ();
			transparencies = merged.getFaceTransparencies();
			unlitColors = merged.getFaceColors();
			colors1 = lit.getFaceColors1();
			colors2 = lit.getFaceColors2();
			colors3 = lit.getFaceColors3();
			slotStyle = new ParticleStyle[slotsPerCanvas];
			slotVariant = new int[slotsPerCanvas];
			Arrays.fill(slotVariant, -1);

			object = client.createRuneLiteObject();
			object.setDrawFrontTilesFirst(true);
			object.setOrientation(0);
			object.setModel(lit);
		}
	}

	private final Client client;

	private final List<BatchCanvas> canvases = new ArrayList<>();
	private boolean canvasModeFailed;
	private ModelData canvasProto;
	private int slotsPerCanvas;

	/**
	 * Legacy per-tick merge path, kept as fallback if canvas slicing fails.
	 */
	private final List<RuneLiteObject> legacyPool = new ArrayList<>();
	private int legacyUsed;

	/**
	 * Resolved styles by profile key, rebuilt when profiles change.
	 */
	private final Map<String, ParticleStyle> styles = new HashMap<>();
	private ModelData sourceMesh;
	private int templateVertexCount;
	private int templateFaceCount;

	/**
	 * Diagnostics.
	 */
	@Getter
	private int activeObjects;
	@Getter
	private int lastBatchedVertices;
	@Getter
	private String cameraDebug = "";
	private int outOfSceneKills;

	ParticleRenderer(Client client)
	{
		this.client = client;
	}

	boolean isReady()
	{
		return sourceMesh != null && !styles.isEmpty();
	}

	/**
	 * @return the resolved style for a profile key, or null
	 */
	ParticleStyle getStyle(String profileKey)
	{
		return styles.get(profileKey);
	}

	/**
	 * (Re)build disc templates for every profile's style.
	 *
	 * @return true on success
	 */
	boolean rebuildStyles(Map<String, EmitterProfile> profiles)
	{
		if (sourceMesh == null)
		{
			sourceMesh = loadSourceMesh();
			if (sourceMesh == null)
			{
				log.debug("Particle base model not yet available");
				return false;
			}
			templateVertexCount = sourceMesh.getVerticesCount();
			templateFaceCount = sourceMesh.getFaceCount();
			slotsPerCanvas = Math.max(1, MAX_FACES_PER_BATCH / Math.max(1, templateFaceCount));
			buildCanvasProto();
		}

		styles.clear();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			styles.put(entry.getKey(), buildStyle(entry.getValue()));
		}
		return true;
	}

	/**
	 * The canvas prototype is the disc topology with every vertex nudged by a
	 * unique epsilon, so the engine's identical-position vertex welding can't
	 * fuse slots (or vertices within a slot) when canvases are merged.
	 * Positions are overwritten every tick, so the nudges never render.
	 */
	private void buildCanvasProto()
	{
		canvasProto = sourceMesh.shallowCopy().cloneVertices();
		float[] xs = canvasProto.getVerticesX();
		float[] ys = canvasProto.getVerticesY();
		float[] zs = canvasProto.getVerticesZ();
		for (int j = 0; j < templateVertexCount; j++)
		{
			xs[j] += j * 0.001953125f;
			ys[j] += j * 0.0009765625f;
			zs[j] += j * 0.00048828125f;
		}
	}

	private ParticleStyle buildStyle(EmitterProfile profile)
	{
		Color color = new Color(profile.getColor(), true);
		short target = JagexColor.rgbToHSL(color.getRGB(), 1.0d);
		int baseAlpha = color.getAlpha();

		ModelData[][] templates = new ModelData[ParticleStyle.SIZE_MULTIPLIERS.length][ParticleStyle.FADE_STEPS];
		for (int s = 0; s < ParticleStyle.SIZE_MULTIPLIERS.length; s++)
		{
			float radius = profile.getSize() / 2f * ParticleStyle.SIZE_MULTIPLIERS[s];
			ModelData lens = sourceMesh.shallowCopy()
				.cloneVertices()
				.cloneColors();
			spherify(lens, radius);
			flatten(lens, LENS_FLATTEN);

			// Recolor every distinct face color to the style's particle color
			Set<Short> originals = new HashSet<>();
			for (short faceColor : lens.getFaceColors())
			{
				originals.add(faceColor);
			}
			for (short original : originals)
			{
				lens.recolor(original, target);
			}

			// Radial alpha falloff per face - the "texture" of the sprite
			float[] radial = radialFalloff(lens, radius);

			for (int i = 0; i < ParticleStyle.FADE_STEPS; i++)
			{
				// Soft life envelope: born invisible, bloom mid-life, melt away
				float envelope = envelope((i + 0.5f) / ParticleStyle.FADE_STEPS);
				ModelData variant = lens.shallowCopy().cloneTransparencies(true);
				byte[] transparencies = variant.getFaceTransparencies();
				for (int f = 0; f < transparencies.length; f++)
				{
					int alpha = (int) (baseAlpha * envelope * radial[f]);
					transparencies[f] = (byte) Math.min(254, Math.max(1, 255 - alpha));
				}
				templates[s][i] = variant;
			}
		}

		// Bake the style's lit colors once; canvas slots copy them in place of
		// per-tick relighting. Shading is constant, which suits a glow.
		Model probe = templates[1][0].shallowCopy().light(LIGHT_AMBIENT, LIGHT_CONTRAST,
			ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
		int[] lit1 = probe.getFaceColors1().clone();
		int[] lit2 = probe.getFaceColors2().clone();
		int[] lit3 = probe.getFaceColors3().clone();

		return new ParticleStyle(templates, lit1, lit2, lit3, profile);
	}

	/**
	 * Load the smallest available candidate mesh. Every vertex is written per
	 * particle per tick, so lean geometry matters far more than shape -
	 * spherify remolds anything round enough.
	 */
	private ModelData loadSourceMesh()
	{
		int[] candidates = {ItemID.DS2_ORB_INERT, ItemID.MCANNONBALL, ItemID.FEATHER, ItemID.EGG, ItemID.BALL_OF_WOOL};
		ModelData best = null;
		int bestVerts = Integer.MAX_VALUE;
		for (int itemId : candidates)
		{
			ItemComposition comp = client.getItemDefinition(itemId);
			ModelData md = client.loadModelData(comp.getInventoryModel());
			if (md == null)
			{
				continue;
			}
			log.debug("Particle mesh candidate {}: {}v {}f", comp.getName(), md.getVerticesCount(), md.getFaceCount());
			if (md.getVerticesCount() < bestVerts)
			{
				bestVerts = md.getVerticesCount();
				best = md;
			}
		}
		return best;
	}

	/**
	 * Alpha over normalized age: smooth rise to a mid-life peak and gentle
	 * decay, instead of popping in at full brightness.
	 */
	private static float envelope(float ageFraction)
	{
		return (float) Math.pow(Math.sin(Math.PI * ageFraction), 0.8);
	}

	/**
	 * Push all live particles into batch models. Call once per client tick,
	 * after simulation.
	 */
	void sync(List<Particle> particles, int worldView, int level)
	{
		activeObjects = 0;
		lastBatchedVertices = 0;
		WorldView wv = client.getWorldView(worldView);
		if (wv == null || !isReady())
		{
			reset();
			return;
		}

		// Billboard basis: face every disc toward the camera, exactly. On the
		// GPU the scene is rendered with the floating point camera, which can
		// deviate from the int JAU camera - use whichever the renderer uses,
		// mirroring Perspective.localToCanvas
		float yaw;
		float pitch;
		if (client.isGpu())
		{
			yaw = client.getCameraFpYaw();
			pitch = client.getCameraFpPitch();
		}
		else
		{
			yaw = client.getCameraYaw() * (float) (Math.PI / 1024);
			pitch = client.getCameraPitch() * (float) (Math.PI / 1024);
		}
		cameraDebug = String.format("cam int %d/%d jau | used %.1f/%.1f jau (%s)",
			client.getCameraYaw(), client.getCameraPitch(),
			yaw * 1024 / Math.PI, pitch * 1024 / Math.PI,
			client.isGpu() ? "fp" : "int");

		float sinYaw = (float) Math.sin(yaw);
		float cosYaw = (float) Math.cos(yaw);
		float sinPitch = (float) Math.sin(pitch);
		float cosPitch = (float) Math.cos(pitch);

		if (canvasModeFailed)
		{
			syncLegacy(particles, wv, worldView, level, sinYaw, cosYaw, sinPitch, cosPitch);
			return;
		}
		syncCanvases(particles, wv, worldView, level, sinYaw, cosYaw, sinPitch, cosPitch);
	}

	private void syncCanvases(List<Particle> particles, WorldView wv, int worldView, int level,
		float sinYaw, float cosYaw, float sinPitch, float cosPitch)
	{
		for (BatchCanvas canvas : canvases)
		{
			canvas.claimed = false;
		}

		for (Particle p : particles)
		{
			int x = (int) p.getX();
			int y = (int) p.getY();
			int sceneX = x >> 7;
			int sceneY = y >> 7;
			if (sceneX < 0 || sceneX >= wv.getSizeX() || sceneY < 0 || sceneY >= wv.getSizeY())
			{
				outOfSceneKills++;
				p.kill();
				continue;
			}
			long tileKey = ((long) sceneX << 32) | (sceneY & 0xffffffffL);

			BatchCanvas canvas = claimCanvas(tileKey, worldView, level);
			if (canvas == null)
			{
				// Canvas build failed; legacy path takes over next tick
				return;
			}
			writeSlot(canvas, canvas.usedSlots++, p, sinYaw, cosYaw, sinPitch, cosPitch);
			lastBatchedVertices += templateVertexCount;
		}

		for (BatchCanvas canvas : canvases)
		{
			if (!canvas.claimed)
			{
				if (canvas.object.isActive())
				{
					canvas.object.setActive(false);
				}
				continue;
			}

			// Collapse slots that were written last tick but not this one
			for (int slot = canvas.usedSlots; slot < canvas.highWater; slot++)
			{
				clearSlot(canvas, slot);
			}
			canvas.highWater = canvas.usedSlots;

			canvas.lit.calculateBoundsCylinder();
			canvas.object.setLocation(canvas.centerLp, level);
			if (!canvas.object.isActive())
			{
				canvas.object.setActive(true);
			}
			activeObjects++;
		}
	}

	/**
	 * Find this tick's canvas for a tile (one with free slots), claiming an
	 * idle canvas or building a new one as needed. Canvas count grows to the
	 * peak concurrent demand and is reused thereafter.
	 */
	private BatchCanvas claimCanvas(long tileKey, int worldView, int level)
	{
		BatchCanvas idle = null;
		for (BatchCanvas canvas : canvases)
		{
			if (canvas.claimed && canvas.tileKey == tileKey && canvas.usedSlots < slotsPerCanvas)
			{
				return canvas;
			}
			if (idle == null && !canvas.claimed)
			{
				idle = canvas;
			}
		}
		if (idle == null)
		{
			idle = buildCanvas();
			if (idle == null)
			{
				return null;
			}
			canvases.add(idle);
		}

		idle.claimed = true;
		idle.tileKey = tileKey;
		idle.usedSlots = 0;
		int centerX = ((int) (tileKey >> 32) << 7) + 64;
		int centerY = (((int) tileKey) << 7) + 64;
		idle.centerLp = new LocalPoint(centerX, centerY, worldView);
		idle.centerHeight = Perspective.getTileHeight(client, idle.centerLp, level);
		return idle;
	}

	private BatchCanvas buildCanvas()
	{
		ModelData[] parts = new ModelData[slotsPerCanvas];
		for (int i = 0; i < slotsPerCanvas; i++)
		{
			// Distinct whole-unit offsets; epsilons are sub-unit, so no vertex
			// position can coincide across slots and welding can't occur
			parts[i] = canvasProto.shallowCopy().cloneVertices().translate(i * 1024, 0, 0);
		}
		ModelData merged = client.mergeModels(parts).cloneTransparencies(true);

		if (merged.getVerticesCount() != slotsPerCanvas * templateVertexCount
			|| merged.getFaceCount() != slotsPerCanvas * templateFaceCount)
		{
			log.warn("Canvas slicing failed ({}v {}f, expected {}x{}v {}f); falling back to per-tick merging",
				merged.getVerticesCount(), merged.getFaceCount(),
				slotsPerCanvas, templateVertexCount, templateFaceCount);
			canvasModeFailed = true;
			return null;
		}
		return new BatchCanvas(merged);
	}

	/**
	 * Overwrite one slot of a canvas with a particle: billboarded vertices
	 * always; transparency and colors only when the slot's style, size or
	 * fade step changed since the slot was last written.
	 */
	private void writeSlot(BatchCanvas canvas, int slot, Particle p,
		float sinYaw, float cosYaw, float sinPitch, float cosPitch)
	{
		ParticleStyle style = p.getStyle();
		int size = p.getSizeVariant();
		int fade = fadeStep(p.lifeFraction());

		int vertexBase = slot * templateVertexCount;
		float dx = p.getX() - canvas.centerLp.getX();
		float dy = p.getZ() - canvas.centerHeight;
		float dz = p.getY() - canvas.centerLp.getY();

		ModelData sizeTemplate = style.getTemplates()[size][0];
		float[] sx = sizeTemplate.getVerticesX();
		float[] sy = sizeTemplate.getVerticesY();
		float[] sz = sizeTemplate.getVerticesZ();
		for (int j = 0; j < templateVertexCount; j++)
		{
			float x = sx[j];
			float y = sy[j];
			float z = sz[j];

			// Pitch: (0,0,1) -> (0, sinPitch, cosPitch)
			float y1 = y * cosPitch + z * sinPitch;
			float z1 = -y * sinPitch + z * cosPitch;

			// Yaw: (0, sp, cp) -> (-sinYaw*cp, sp, cosYaw*cp) = camera forward
			float x1 = -z1 * sinYaw + x * cosYaw;
			float z2 = z1 * cosYaw + x * sinYaw;

			canvas.vx[vertexBase + j] = x1 + dx;
			canvas.vy[vertexBase + j] = y1 + dy;
			canvas.vz[vertexBase + j] = z2 + dz;
		}

		int variant = size * ParticleStyle.FADE_STEPS + fade;
		if (canvas.slotStyle[slot] != style || canvas.slotVariant[slot] != variant)
		{
			canvas.slotStyle[slot] = style;
			canvas.slotVariant[slot] = variant;
			int faceBase = slot * templateFaceCount;
			ModelData fadeTemplate = style.getTemplates()[size][fade];
			System.arraycopy(fadeTemplate.getFaceTransparencies(), 0, canvas.transparencies, faceBase, templateFaceCount);
			// Unlit colors for renderers that relight, lit for the others
			System.arraycopy(fadeTemplate.getFaceColors(), 0, canvas.unlitColors, faceBase, templateFaceCount);
			System.arraycopy(style.getLitColors1(), 0, canvas.colors1, faceBase, templateFaceCount);
			System.arraycopy(style.getLitColors2(), 0, canvas.colors2, faceBase, templateFaceCount);
			System.arraycopy(style.getLitColors3(), 0, canvas.colors3, faceBase, templateFaceCount);
		}
	}

	private void clearSlot(BatchCanvas canvas, int slot)
	{
		int vertexBase = slot * templateVertexCount;
		Arrays.fill(canvas.vx, vertexBase, vertexBase + templateVertexCount, 0f);
		Arrays.fill(canvas.vy, vertexBase, vertexBase + templateVertexCount, 0f);
		Arrays.fill(canvas.vz, vertexBase, vertexBase + templateVertexCount, 0f);
		int faceBase = slot * templateFaceCount;
		Arrays.fill(canvas.transparencies, faceBase, faceBase + templateFaceCount, INVISIBLE);
		canvas.slotStyle[slot] = null;
		canvas.slotVariant[slot] = -1;
	}

	/**
	 * Legacy fallback: merge and light fresh batch models every tick.
	 */
	private void syncLegacy(List<Particle> particles, WorldView wv, int worldView, int level,
		float sinYaw, float cosYaw, float sinPitch, float cosPitch)
	{
		legacyUsed = 0;

		Map<Long, List<Particle>> byTile = new HashMap<>();
		for (Particle p : particles)
		{
			int x = (int) p.getX();
			int y = (int) p.getY();
			int sceneX = x >> 7;
			int sceneY = y >> 7;
			if (sceneX < 0 || sceneX >= wv.getSizeX() || sceneY < 0 || sceneY >= wv.getSizeY())
			{
				outOfSceneKills++;
				p.kill();
				continue;
			}
			long key = ((long) sceneX << 32) | (sceneY & 0xffffffffL);
			byTile.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
		}

		int batchSize = Math.max(1, MAX_FACES_PER_BATCH / Math.max(1, templateFaceCount));
		ModelData[] parts = new ModelData[batchSize];

		for (Map.Entry<Long, List<Particle>> entry : byTile.entrySet())
		{
			int sceneX = (int) (entry.getKey() >> 32);
			int sceneY = entry.getKey().intValue();
			int centerX = (sceneX << 7) + 64;
			int centerY = (sceneY << 7) + 64;
			LocalPoint centerLp = new LocalPoint(centerX, centerY, worldView);
			int centerHeight = Perspective.getTileHeight(client, centerLp, level);

			List<Particle> group = entry.getValue();
			for (int start = 0; start < group.size(); start += batchSize)
			{
				int n = Math.min(batchSize, group.size() - start);
				for (int i = 0; i < n; i++)
				{
					Particle p = group.get(start + i);
					ModelData md = p.getStyle().getTemplates()
						[p.getSizeVariant()][fadeStep(p.lifeFraction())]
						.shallowCopy()
						.cloneVertices();
					transformLegacy(md, sinYaw, cosYaw, sinPitch, cosPitch,
						p.getX() - centerX, p.getZ() - centerHeight, p.getY() - centerY);
					parts[i] = md;
					lastBatchedVertices += templateVertexCount;
				}

				ModelData merged = n == batchSize
					? client.mergeModels(parts)
					: client.mergeModels(Arrays.copyOf(parts, n));
				Model lit = merged.light(LIGHT_AMBIENT, LIGHT_CONTRAST,
					ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);

				RuneLiteObject obj = nextLegacyObject();
				obj.setModel(lit);
				obj.setLocation(centerLp, level);
				obj.setOrientation(0);
			}
		}

		for (int i = legacyUsed; i < legacyPool.size(); i++)
		{
			RuneLiteObject obj = legacyPool.get(i);
			if (obj.isActive())
			{
				obj.setActive(false);
			}
		}
		activeObjects = legacyUsed;
	}

	private static void transformLegacy(ModelData md, float sinYaw, float cosYaw, float sinPitch, float cosPitch,
		float dx, float dy, float dz)
	{
		float[] xs = md.getVerticesX();
		float[] ys = md.getVerticesY();
		float[] zs = md.getVerticesZ();
		for (int i = 0; i < md.getVerticesCount(); i++)
		{
			float x = xs[i];
			float y = ys[i];
			float z = zs[i];
			float y1 = y * cosPitch + z * sinPitch;
			float z1 = -y * sinPitch + z * cosPitch;
			float x1 = -z1 * sinYaw + x * cosYaw;
			float z2 = z1 * cosYaw + x * sinYaw;
			xs[i] = x1 + dx;
			ys[i] = y1 + dy;
			zs[i] = z2 + dz;
		}
	}

	private RuneLiteObject nextLegacyObject()
	{
		RuneLiteObject obj;
		if (legacyUsed < legacyPool.size())
		{
			obj = legacyPool.get(legacyUsed);
		}
		else
		{
			obj = client.createRuneLiteObject();
			obj.setDrawFrontTilesFirst(true);
			legacyPool.add(obj);
		}
		legacyUsed++;
		if (!obj.isActive())
		{
			obj.setActive(true);
		}
		return obj;
	}

	/**
	 * Deactivate all batch objects, e.g. on logout or shutdown.
	 */
	void reset()
	{
		for (BatchCanvas canvas : canvases)
		{
			canvas.claimed = false;
			if (canvas.object.isActive())
			{
				canvas.object.setActive(false);
			}
		}
		for (RuneLiteObject obj : legacyPool)
		{
			if (obj.isActive())
			{
				obj.setActive(false);
			}
		}
		activeObjects = 0;
	}

	/**
	 * @return particles killed for leaving the scene since the last call
	 */
	int drainOutOfSceneKills()
	{
		int kills = outOfSceneKills;
		outOfSceneKills = 0;
		return kills;
	}

	private static int fadeStep(float lifeFraction)
	{
		int step = (int) ((1f - lifeFraction) * ParticleStyle.FADE_STEPS);
		return Math.max(0, Math.min(ParticleStyle.FADE_STEPS - 1, step));
	}

	/**
	 * Reshape a mesh into a sphere by projecting every vertex onto a sphere of
	 * the given radius around the mesh centroid. Topology is unchanged, so any
	 * reasonably round source model becomes a clean ball. The result is centered
	 * on the model origin so particles sit exactly on their emit position.
	 */
	private static void spherify(ModelData model, float radius)
	{
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		float[] zs = model.getVerticesZ();
		int count = model.getVerticesCount();
		if (count == 0)
		{
			return;
		}

		float cx = 0, cy = 0, cz = 0;
		for (int i = 0; i < count; i++)
		{
			cx += xs[i];
			cy += ys[i];
			cz += zs[i];
		}
		cx /= count;
		cy /= count;
		cz /= count;

		for (int i = 0; i < count; i++)
		{
			float dx = xs[i] - cx;
			float dy = ys[i] - cy;
			float dz = zs[i] - cz;
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 0.001f)
			{
				// Degenerate vertex at the centroid; push it to the pole
				xs[i] = 0;
				ys[i] = -radius;
				zs[i] = 0;
				continue;
			}
			xs[i] = dx / len * radius;
			ys[i] = dy / len * radius;
			zs[i] = dz / len * radius;
		}
	}

	/**
	 * Squash the sphere along z into a thin lens: a billboard "quad" with the
	 * source mesh's topology. The disc spans x (horizontal) and y (vertical),
	 * facing along z.
	 */
	private static void flatten(ModelData model, float factor)
	{
		float[] zs = model.getVerticesZ();
		for (int i = 0; i < model.getVerticesCount(); i++)
		{
			zs[i] *= factor;
		}
	}

	/**
	 * Per-face alpha multiplier by distance of the face centroid from the disc
	 * center: (1 - t^2)^2 falloff, opaque core to transparent rim. This is the
	 * radial alpha mask a particle texture would normally provide.
	 */
	private static float[] radialFalloff(ModelData model, float radius)
	{
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		int[] f1 = model.getFaceIndices1();
		int[] f2 = model.getFaceIndices2();
		int[] f3 = model.getFaceIndices3();

		float[] falloff = new float[model.getFaceCount()];
		for (int f = 0; f < falloff.length; f++)
		{
			float cx = (xs[f1[f]] + xs[f2[f]] + xs[f3[f]]) / 3f;
			float cy = (ys[f1[f]] + ys[f2[f]] + ys[f3[f]]) / 3f;
			float t = Math.min(1f, (float) Math.sqrt(cx * cx + cy * cy) / radius);
			float a = 1f - t * t;
			falloff[f] = a * a;
		}
		return falloff;
	}
}
