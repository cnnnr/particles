package dev.cnnnr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Persistent emitter profiles. Each profile targets a mesh piece by topology
 * signature (see {@link ModelSnapshot.Piece}) and stores piece-local vertex
 * indices, so emitters survive model recomposition. Several profiles may
 * target the same signature, each optionally gated on worn item IDs, so
 * recolored variants of one model can carry different particles.
 *
 * Thread-safe: mutated from the Swing EDT (viewer clicks, sidebar actions)
 * and read from the client thread.
 */
@Slf4j
class EmitterStore
{
	private static final String CONFIG_KEY = "pieceProfiles";
	private static final Type MAP_TYPE = new TypeToken<Map<String, EmitterProfile>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;
	/**
	 * In developer mode the config is the source of truth so authoring edits
	 * persist across reloads; shipped users instead take all profile content
	 * from the bundled pack and contribute only their enable/disable toggles,
	 * so preset style updates and new fields reach them on every update.
	 */
	private final boolean developerMode;
	private final Map<String, EmitterProfile> profiles = new LinkedHashMap<>();
	private int revision;

	/**
	 * Notified after any mutation; invoked on the calling thread.
	 */
	@Setter
	private Runnable changeListener;

	EmitterStore(ConfigManager configManager, Gson gson, boolean developerMode)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.developerMode = developerMode;
	}

	synchronized void load()
	{
		profiles.clear();
		profiles.putAll(mergeWithBundle(
			configManager.getConfiguration(ParticlesConfig.GROUP, CONFIG_KEY)));
	}

	/**
	 * Combine a user's saved config with the bundled preset pack and run
	 * migrations. Package-private so the contract can be tested without a
	 * ConfigManager.
	 *
	 * Developer mode: the config is the source of truth (authoring edits
	 * persist); the bundle only seeds a fresh, empty config, and presets are
	 * updated by re-exporting the config to presets.json.
	 *
	 * Shipped users: the bundle is the source of truth for CONTENT - every
	 * profile is taken wholesale from presets.json, so style changes and new
	 * fields ship on update - and only the user's enable/disable toggle is
	 * carried over from their saved config (the one thing they can change).
	 * A profile the bundle no longer contains is kept from the saved config.
	 */
	Map<String, EmitterProfile> mergeWithBundle(@Nullable String savedJson)
	{
		Map<String, EmitterProfile> saved = parse(savedJson);
		Map<String, EmitterProfile> bundled = parse(loadBundledPresets());

		Map<String, EmitterProfile> result = new LinkedHashMap<>();
		if (developerMode)
		{
			if (saved != null && !saved.isEmpty())
			{
				result.putAll(saved);
			}
			else if (bundled != null)
			{
				result.putAll(bundled);
			}
		}
		else
		{
			if (bundled != null)
			{
				bundled.forEach((key, profile) ->
				{
					EmitterProfile prior = saved == null ? null : saved.get(key);
					if (prior != null)
					{
						// Content from the bundle, toggle from the user
						profile.setEnabled(prior.isEnabled());
					}
					result.put(key, profile);
				});
			}
			if (saved != null)
			{
				// Anything the bundle dropped (or the user created) survives
				saved.forEach(result::putIfAbsent);
			}
		}

		result.forEach((key, profile) ->
		{
			// Migration: profiles saved before item variants used their
			// signature as the map key
			if (profile.getSignature() == null)
			{
				profile.setSignature(key);
			}
			// Migration: single frame window fields became a range list
			if ((profile.getAnimFrames() == null || profile.getAnimFrames().isEmpty())
				&& (profile.getAnimFrameStart() >= 0 || profile.getAnimFrameEnd() >= 0))
			{
				int start = Math.max(0, profile.getAnimFrameStart());
				int end = profile.getAnimFrameEnd() < 0 ? 999 : profile.getAnimFrameEnd();
				profile.setAnimFrames(start + "-" + end);
				profile.setAnimFrameStart(-1);
				profile.setAnimFrameEnd(-1);
			}
			else if (profile.getAnimFrames() == null)
			{
				profile.setAnimFrames("");
			}
			// Migration: feather flag became a strength
			if (profile.isFeather() && profile.getFeatherStrength() == 0)
			{
				profile.setFeatherStrength(2);
				profile.setFeather(false);
			}
			// Migration: profiles saved before projectile targets
			if (profile.getTargetType() == null)
			{
				profile.setTargetType(EmitterProfile.TARGET_PLAYER);
			}
			// Migration: dynamic lifetime flag became a movement percent
			if (profile.isDynamicLifetime())
			{
				if (profile.getMovementLifetime() == 100)
				{
					profile.setMovementLifetime(50);
				}
				profile.setDynamicLifetime(false);
			}
			// Migration: profiles saved before shapes have no shape
			if (profile.getShape() == null)
			{
				profile.setShape(Shape.DEFAULT);
			}
			// Migration: emit scale is a percent defaulting to 100; a missing
			// key deserializes to 0, which would collapse emitters to a point
			if (profile.getEmitScale() <= 0)
			{
				profile.setEmitScale(100);
			}
			// Migration: end colour defaults to the start colour (no gradient);
			// a missing key deserializes to 0, which would fade to transparent
			if (profile.getColorEnd() == 0)
			{
				profile.setColorEnd(profile.getColor());
			}
		});
		return result;
	}

	/**
	 * Parse a profile-map JSON string, or null on empty/blank/malformed.
	 */
	@Nullable
	private Map<String, EmitterProfile> parse(@Nullable String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, MAP_TYPE);
		}
		catch (Exception e)
		{
			log.warn("Failed to parse emitter profiles", e);
			return null;
		}
	}

	@Nullable
	private static String loadBundledPresets()
	{
		try (InputStream in = EmitterStore.class.getResourceAsStream("/presets.json"))
		{
			if (in == null)
			{
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			log.warn("Failed to read bundled presets", e);
			return null;
		}
	}

	private void save()
	{
		configManager.setConfiguration(ParticlesConfig.GROUP, CONFIG_KEY, gson.toJson(profiles, MAP_TYPE));
		revision++;
		if (changeListener != null)
		{
			changeListener.run();
		}
	}

	/**
	 * Bumped on every mutation; lets the client thread cheaply detect that
	 * emitters need re-resolving against the current model.
	 */
	synchronized int getRevision()
	{
		return revision;
	}

	/**
	 * @return the key of some existing profile for this signature, or a newly
	 * created one
	 */
	synchronized String ensureProfileFor(String signature, String defaultName)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			if (signature.equals(entry.getValue().getSignature())
				&& EmitterProfile.TARGET_PLAYER.equals(entry.getValue().getTargetType()))
			{
				return entry.getKey();
			}
		}
		String key = freeKey(signature);
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setSignature(signature);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * Clone a profile (vertices, style, filter) under a new key, e.g. to give
	 * another item variant of the same mesh its own particles.
	 *
	 * @return the new profile's key, or null
	 */
	@Nullable
	synchronized String duplicate(String profileKey)
	{
		EmitterProfile source = profiles.get(profileKey);
		if (source == null)
		{
			return null;
		}
		EmitterProfile copy = source.copy();
		copy.setName(source.getName() + " copy");
		String base = source.isProjectileTarget()
			? "proj:" + source.getProjectileId()
			: source.isGraphicTarget()
			? "gfx:" + source.getGraphicId()
			: source.getSignature();
		String key = freeKey(base);
		profiles.put(key, copy);
		save();
		return key;
	}

	/**
	 * @return the key of an existing object profile for this piece signature
	 * on this object ID, or a newly created one
	 */
	synchronized String ensureObjectPieceProfile(String signature, String defaultName, int objectId)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isObjectTarget() && profile.getObjectId() == objectId
				&& signature.equals(profile.getSignature()))
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_OBJECT);
		profile.setSignature(signature);
		profile.setObjectId(objectId);
		String key = freeKey(signature + "@obj" + objectId);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * @return the key of an existing NPC profile for this piece signature on
	 * this NPC ID, or a newly created one
	 */
	synchronized String ensureNpcPieceProfile(String signature, String defaultName, int npcId)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isNpcTarget() && profile.getNpcId() == npcId
				&& signature.equals(profile.getSignature()))
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_NPC);
		profile.setSignature(signature);
		profile.setNpcId(npcId);
		String key = freeKey(signature + "@npc" + npcId);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * The graphic profile for this piece signature on this spot anim ID. A
	 * point-based profile (no signature yet) for the same ID is upgraded in
	 * place rather than duplicated.
	 */
	synchronized String ensureGraphicPieceProfile(String signature, String defaultName, int graphicId)
	{
		String pointKey = null;
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isGraphicTarget() || profile.getGraphicId() != graphicId)
			{
				continue;
			}
			if (signature.equals(profile.getSignature()))
			{
				return entry.getKey();
			}
			if (profile.getSignature() == null && pointKey == null)
			{
				pointKey = entry.getKey();
			}
		}
		if (pointKey != null)
		{
			profiles.get(pointKey).setSignature(signature);
			save();
			return pointKey;
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_GRAPHIC);
		profile.setGraphicId(graphicId);
		profile.setSignature(signature);
		String key = freeKey(signature + "@gfx" + graphicId);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * @return the key of an existing graphic profile for this spot anim ID,
	 * or a newly created one
	 */
	synchronized String ensureGraphicProfile(int graphicId, String defaultName)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isGraphicTarget() && profile.getGraphicId() == graphicId)
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_GRAPHIC);
		profile.setGraphicId(graphicId);
		profile.setRiseSpeed(20);
		String key = freeKey("gfx:" + graphicId);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * @return the key of an existing profile for this projectile ID, or a
	 * newly created one with trail-friendly defaults
	 */
	synchronized String ensureProjectileProfile(int projectileId, String defaultName)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isProjectileTarget() && profile.getProjectileId() == projectileId)
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_PROJECTILE);
		profile.setProjectileId(projectileId);
		// Trails are what projectile particles are usually for
		profile.setTrailDensity(40);
		profile.setRiseSpeed(0);
		profile.setSpreadSpeed(4);
		String key = freeKey("proj:" + projectileId);
		profiles.put(key, profile);
		save();
		return key;
	}

	private String freeKey(String signature)
	{
		String key = signature;
		int n = 2;
		while (profiles.containsKey(key))
		{
			key = signature + "#" + n++;
		}
		return key;
	}

	/**
	 * @return a copy of the piece-local vertex set of a profile
	 */
	synchronized Set<Integer> vertexSet(String profileKey)
	{
		EmitterProfile profile = profiles.get(profileKey);
		return profile == null ? Set.of() : Set.copyOf(profile.getVertices());
	}

	/**
	 * Add or remove a piece-local vertex on an existing profile.
	 */
	synchronized void toggleVertex(String profileKey, int localVertex)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null)
		{
			return;
		}
		if (!profile.getVertices().remove(localVertex))
		{
			profile.getVertices().add(localVertex);
		}
		save();
	}

	/**
	 * Bulk-add piece-local vertices (box selection). Persists once.
	 */
	synchronized void addVertices(String profileKey, Collection<Integer> localVertices)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getVertices().addAll(localVertices))
		{
			save();
		}
	}

	/**
	 * Bulk-remove piece-local vertices (box selection). Persists once.
	 */
	synchronized void removeVertices(String profileKey, Collection<Integer> localVertices)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getVertices().removeAll(localVertices))
		{
			save();
		}
	}

	/**
	 * Copy the editable settings (style and item variant filter) into a profile.
	 */
	synchronized void updateStyle(String profileKey, EmitterProfile settingsSource)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null)
		{
			profile.copyStyleFrom(settingsSource);
			save();
		}
	}

	/**
	 * Re-attach a profile to a different mesh piece, keeping its style, name
	 * and item filter but clearing its vertices (local indices are meaningless
	 * on another piece). Recovery path for profiles orphaned by signature
	 * changes, and for copying a style setup onto new gear.
	 */
	synchronized void rebind(String profileKey, String newSignature)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null || newSignature.equals(profile.getSignature()))
		{
			return;
		}
		profile.setSignature(newSignature);
		profile.getVertices().clear();
		save();
	}

	synchronized void rename(String profileKey, String name)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && name != null && !name.trim().isEmpty())
		{
			profile.setName(name.trim());
			save();
		}
	}

	synchronized void setEnabled(String profileKey, boolean enabled)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.isEnabled() != enabled)
		{
			profile.setEnabled(enabled);
			save();
		}
	}

	/**
	 * Mark a profile work-in-progress (or ship-ready), a developer-only flag
	 * that hides and force-disables it for shipped users. Persisted so it ships
	 * to presets.json.
	 */
	synchronized void setWip(String profileKey, boolean wip)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.isWip() != wip)
		{
			profile.setWip(wip);
			save();
		}
	}

	/**
	 * Flip a set of profiles at once with a single config write.
	 */
	synchronized void setEnabledAll(Collection<String> profileKeys, boolean enabled)
	{
		boolean changed = false;
		for (String profileKey : profileKeys)
		{
			EmitterProfile profile = profiles.get(profileKey);
			if (profile != null && profile.isEnabled() != enabled)
			{
				profile.setEnabled(enabled);
				changed = true;
			}
		}
		if (changed)
		{
			save();
		}
	}

	/**
	 * Overwrite a profile's editable style with another profile's, for the
	 * sidebar copy/paste flow.
	 */
	synchronized void pasteStyle(String profileKey, EmitterProfile source)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null)
		{
			profile.copyStyleFrom(source);
			save();
		}
	}

	synchronized void delete(String profileKey)
	{
		if (profiles.remove(profileKey) != null)
		{
			save();
		}
	}

	/**
	 * @return a deep copy of all profiles by key, for UI rendering and
	 * emitter resolution
	 */
	synchronized Map<String, EmitterProfile> snapshotAll()
	{
		Map<String, EmitterProfile> copy = new LinkedHashMap<>();
		profiles.forEach((k, v) -> copy.put(k, v.copy()));
		return copy;
	}
}
