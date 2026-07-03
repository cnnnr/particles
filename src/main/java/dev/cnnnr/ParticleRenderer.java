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
 * mostly don't draw. So, like the game's own area effects (and the 3D Weather
 * plugin), all particles on a tile are merged into a single model each tick
 * and rendered by one pooled RuneLiteObject. Merging also lets the engine
 * depth sort particles against each other within the model, and lets us bake
 * exact per-frame billboarding (camera yaw and pitch) into the vertices.
 * Batches freely mix particles of different styles.
 *
 * The disc itself is faked from a cache mesh with supported mutations only:
 * sphere projection + flatten for the shape, radial per-face transparency for
 * the soft alpha falloff a particle texture would normally provide, pre-baked
 * per style as size variants x lifetime fade steps.
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
	 * Cap on faces per merged batch model; a tile gets multiple batch objects
	 * if its particles exceed this.
	 */
	private static final int MAX_FACES_PER_BATCH = 8000;

	private final Client client;

	/**
	 * Pooled batch objects, reassigned to occupied tiles every tick.
	 */
	private final List<RuneLiteObject> objectPool = new ArrayList<>();
	private int usedThisTick;

	/**
	 * Resolved styles by piece signature, rebuilt when profiles change.
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

	/**
	 * @return the resolved style for a piece signature, or null
	 */
	ParticleStyle getStyle(String signature)
	{
		return styles.get(signature);
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
		}

		styles.clear();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			styles.put(entry.getKey(), buildStyle(entry.getValue()));
		}
		return true;
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

		return new ParticleStyle(templates, profile);
	}

	/**
	 * Load the smallest available candidate mesh. Every vertex is cloned,
	 * transformed and relit per particle per tick, so lean geometry matters
	 * far more than shape - spherify remolds anything round enough.
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
	 * Merge all live particles into per-tile batch models and push them into
	 * pooled scene objects. Call once per client tick, after simulation.
	 */
	void sync(List<Particle> particles, int worldView, int level)
	{
		usedThisTick = 0;
		lastBatchedVertices = 0;
		WorldView wv = client.getWorldView(worldView);
		if (wv != null)
		{
			buildBatches(particles, wv, worldView, level);
		}

		// Park pool objects that no batch claimed this tick
		for (int i = usedThisTick; i < objectPool.size(); i++)
		{
			RuneLiteObject obj = objectPool.get(i);
			if (obj.isActive())
			{
				obj.setActive(false);
			}
		}
		activeObjects = usedThisTick;
	}

	private void buildBatches(List<Particle> particles, WorldView wv, int worldView, int level)
	{
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

		// Group particles by the tile they're over
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

					// Particle z is absolute scene height; make it relative to
					// the batch object's z (the tile center height)
					float dy = p.getZ() - centerHeight;

					transform(md, sinYaw, cosYaw, sinPitch, cosPitch,
						p.getX() - centerX, dy, p.getY() - centerY);
					parts[i] = md;
					lastBatchedVertices += templateVertexCount;
				}

				ModelData merged = n == batchSize
					? client.mergeModels(parts)
					: client.mergeModels(Arrays.copyOf(parts, n));
				Model lit = merged.light(LIGHT_AMBIENT, LIGHT_CONTRAST,
					ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);

				RuneLiteObject obj = nextObject();
				obj.setModel(lit);
				obj.setLocation(centerLp, level);
				obj.setOrientation(0);
			}
		}
	}

	/**
	 * Billboard and position one particle's disc, then translate.
	 *
	 * Derived from Perspective.localToCanvasCpu: the camera's forward vector is
	 * (-sinYaw*cosPitch, +sinPitch, cosYaw*cosPitch) in model axes
	 * (x east, y vertical positive-down, z north). The disc's +z normal is
	 * rotated to lie exactly along it; the lens is double sided, so pointing
	 * along instead of against the view is invisible. Wrong signs here read
	 * fine at some camera angles and go flat or edge-on at others.
	 */
	private static void transform(ModelData md, float sinYaw, float cosYaw, float sinPitch, float cosPitch,
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

			// Pitch: (0,0,1) -> (0, sinPitch, cosPitch)
			float y1 = y * cosPitch + z * sinPitch;
			float z1 = -y * sinPitch + z * cosPitch;

			// Yaw: (0, sp, cp) -> (-sinYaw*cp, sp, cosYaw*cp) = camera forward
			float x1 = -z1 * sinYaw + x * cosYaw;
			float z2 = z1 * cosYaw + x * sinYaw;

			xs[i] = x1 + dx;
			ys[i] = y1 + dy;
			zs[i] = z2 + dz;
		}
	}

	private RuneLiteObject nextObject()
	{
		RuneLiteObject obj;
		if (usedThisTick < objectPool.size())
		{
			obj = objectPool.get(usedThisTick);
		}
		else
		{
			obj = client.createRuneLiteObject();
			obj.setDrawFrontTilesFirst(true);
			objectPool.add(obj);
		}
		usedThisTick++;
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
		for (RuneLiteObject obj : objectPool)
		{
			if (obj.isActive())
			{
				obj.setActive(false);
			}
		}
		usedThisTick = 0;
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
