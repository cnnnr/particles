package dev.cnnnr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
	private static final String FOLDERS_KEY = "pieceFolders";
	private static final Type MAP_TYPE = new TypeToken<Map<String, EmitterProfile>>()
	{
	}.getType();
	private static final Type FOLDERS_TYPE = new TypeToken<Map<String, ProfileFolder>>()
	{
	}.getType();

	/**
	 * A consistent (profiles, folders) pair - both the merged result and the
	 * atomic read snapshot the emission gates and sidebar resolve against, so a
	 * profile is never scored against a folder map from a different instant.
	 */
	static final class Snapshot
	{
		final Map<String, EmitterProfile> profiles;
		final Map<String, ProfileFolder> folders;

		Snapshot(Map<String, EmitterProfile> profiles, Map<String, ProfileFolder> folders)
		{
			this.profiles = profiles;
			this.folders = folders;
		}
	}

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
	private final Map<String, ProfileFolder> folders = new LinkedHashMap<>();
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
		folders.clear();
		Snapshot merged = mergeAll(
			configManager.getConfiguration(ParticlesConfig.GROUP, CONFIG_KEY),
			configManager.getConfiguration(ParticlesConfig.GROUP, FOLDERS_KEY));
		profiles.putAll(merged.profiles);
		folders.putAll(merged.folders);
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
		return mergeAll(savedJson, null).profiles;
	}

	/**
	 * Merge profiles and folders together and reconcile them: nulls any
	 * folderId that no longer resolves and dissolves folders left with fewer
	 * than two members, so the returned pair always satisfies the folder
	 * invariant. Package-private for testing.
	 */
	Snapshot mergeAll(@Nullable String savedProfilesJson, @Nullable String savedFoldersJson)
	{
		Map<String, EmitterProfile> saved = parse(savedProfilesJson);
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
					// Content from the bundle; the user's toggle carries over only
					// for profiles the bundle keeps ungrouped - foldered children
					// are dev-controlled (users toggle the folder, not the child).
					if (prior != null && profile.getFolderId() == null)
					{
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

		Map<String, ProfileFolder> folderResult = mergeFolders(savedFoldersJson);
		reconcileFolders(result, folderResult);
		return new Snapshot(result, folderResult);
	}

	/**
	 * Merge saved folders with the bundle on the same terms as profiles:
	 * dev mode's config wins; shipped users take folder content from the bundle
	 * and keep only their own enable toggle.
	 */
	private Map<String, ProfileFolder> mergeFolders(@Nullable String savedFoldersJson)
	{
		Map<String, ProfileFolder> saved = parseFolders(savedFoldersJson);
		Map<String, ProfileFolder> bundled = parseFolders(loadBundledFolders());

		Map<String, ProfileFolder> result = new LinkedHashMap<>();
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
				bundled.forEach((id, folder) ->
				{
					ProfileFolder prior = saved == null ? null : saved.get(id);
					if (prior != null)
					{
						// Content (name, wip) from the bundle, toggle from the user
						folder.setEnabled(prior.isEnabled());
					}
					result.put(id, folder);
				});
			}
			if (saved != null)
			{
				saved.forEach(result::putIfAbsent);
			}
		}
		return result;
	}

	/**
	 * Enforce the folder invariant on a merged pair: null any folderId with no
	 * matching folder, then drop any folder left with fewer than two members
	 * (nulling those members). Runs in both modes; defensive in dev mode where
	 * the mutators already hold the invariant.
	 */
	private static void reconcileFolders(Map<String, EmitterProfile> profiles,
		Map<String, ProfileFolder> folders)
	{
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.getFolderId() != null && !folders.containsKey(profile.getFolderId()))
			{
				profile.setFolderId(null);
			}
		}
		Map<String, Integer> counts = new HashMap<>();
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.getFolderId() != null)
			{
				counts.merge(profile.getFolderId(), 1, Integer::sum);
			}
		}
		folders.keySet().removeIf(id -> counts.getOrDefault(id, 0) < 2);
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.getFolderId() != null && !folders.containsKey(profile.getFolderId()))
			{
				profile.setFolderId(null);
			}
		}
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
	private Map<String, ProfileFolder> parseFolders(@Nullable String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, FOLDERS_TYPE);
		}
		catch (Exception e)
		{
			log.warn("Failed to parse profile folders", e);
			return null;
		}
	}

	@Nullable
	private static String loadBundledPresets()
	{
		return loadResource("/presets.json", "presets");
	}

	@Nullable
	private static String loadBundledFolders()
	{
		return loadResource("/folders.json", "folders");
	}

	@Nullable
	private static String loadResource(String path, String what)
	{
		try (InputStream in = EmitterStore.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			log.warn("Failed to read bundled {}", what, e);
			return null;
		}
	}

	private void save()
	{
		configManager.setConfiguration(ParticlesConfig.GROUP, CONFIG_KEY, gson.toJson(profiles, MAP_TYPE));
		configManager.setConfiguration(ParticlesConfig.GROUP, FOLDERS_KEY, gson.toJson(folders, FOLDERS_TYPE));
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
		EmitterProfile removed = profiles.remove(profileKey);
		if (removed != null)
		{
			if (removed.getFolderId() != null)
			{
				autoDissolve(removed.getFolderId());
			}
			save();
		}
	}

	/**
	 * @return a deep copy of all profiles by key, for the non-gating lookups
	 * (rename/edit dialogs, worn-model scans) that only need profiles
	 */
	synchronized Map<String, EmitterProfile> snapshotAll()
	{
		Map<String, EmitterProfile> copy = new LinkedHashMap<>();
		profiles.forEach((k, v) -> copy.put(k, v.copy()));
		return copy;
	}

	/**
	 * @return an atomic deep copy of both maps, for the emission gates and the
	 * sidebar - so a profile is always scored against a coherent folder view
	 */
	synchronized Snapshot snapshot()
	{
		Map<String, EmitterProfile> p = new LinkedHashMap<>();
		profiles.forEach((k, v) -> p.put(k, v.copy()));
		Map<String, ProfileFolder> f = new LinkedHashMap<>();
		folders.forEach((k, v) -> f.put(k, v.copy()));
		return new Snapshot(p, f);
	}

	/**
	 * Developer helper: write the live profiles and folders out as the bundled
	 * presets.json and folders.json in a chosen directory, so the authoring
	 * config can be re-exported into the shipped pack in one click. Refuses to
	 * write (and reports why) if the folder invariant is violated, so a broken
	 * pair can never land. Returns a human summary for a dialog.
	 */
	synchronized String exportBundle(File dir) throws IOException
	{
		List<String> problems = new ArrayList<>();
		Map<String, Integer> counts = new HashMap<>();
		for (EmitterProfile profile : profiles.values())
		{
			String folderId = profile.getFolderId();
			if (folderId == null)
			{
				continue;
			}
			if (!folders.containsKey(folderId))
			{
				problems.add("profile \"" + profile.getName() + "\" points at missing folder " + folderId);
			}
			else
			{
				counts.merge(folderId, 1, Integer::sum);
			}
		}
		for (Map.Entry<String, ProfileFolder> entry : folders.entrySet())
		{
			int members = counts.getOrDefault(entry.getKey(), 0);
			if (members < 2)
			{
				problems.add("folder \"" + entry.getValue().getName() + "\" has " + members
					+ " member(s); needs at least 2");
			}
		}
		if (!problems.isEmpty())
		{
			return "Nothing written - fix these first:\n - " + String.join("\n - ", problems);
		}

		Files.write(new File(dir, "presets.json").toPath(),
			gson.toJson(profiles, MAP_TYPE).getBytes(StandardCharsets.UTF_8));
		Files.write(new File(dir, "folders.json").toPath(),
			gson.toJson(folders, FOLDERS_TYPE).getBytes(StandardCharsets.UTF_8));
		return "Wrote " + profiles.size() + " profiles and " + folders.size() + " folders to\n"
			+ dir.getAbsolutePath();
	}

	// --- Folders -------------------------------------------------------------

	/**
	 * Group two profiles: if the target is already in a folder the member joins
	 * it, otherwise a new folder is created named after the target. Rejects a
	 * cross-category or self grouping. The member leaves any prior folder.
	 */
	synchronized void createFolder(String targetKey, String memberKey)
	{
		EmitterProfile target = profiles.get(targetKey);
		EmitterProfile member = profiles.get(memberKey);
		if (target == null || member == null || targetKey.equals(memberKey)
			|| !sameCategory(target, member))
		{
			return;
		}
		String folderId = target.getFolderId();
		if (folderId == null)
		{
			folderId = freeFolderId();
			folders.put(folderId, new ProfileFolder(folderId, target.getName()));
			target.setFolderId(folderId);
		}
		moveToFolder(member, folderId);
		save();
	}

	/**
	 * Add a profile to an existing folder (dropping onto its header or a child),
	 * rejecting a cross-category join. The member leaves any prior folder.
	 */
	synchronized void addToFolder(String folderId, String memberKey)
	{
		ProfileFolder folder = folders.get(folderId);
		EmitterProfile member = profiles.get(memberKey);
		if (folder == null || member == null || folderId.equals(member.getFolderId()))
		{
			return;
		}
		EmitterProfile reference = anyMember(folderId);
		if (reference != null && !sameCategory(reference, member))
		{
			return;
		}
		moveToFolder(member, folderId);
		save();
	}

	/**
	 * Orphan one profile back to a loose row; dissolves the folder if it falls
	 * below two members.
	 */
	synchronized void removeFromFolder(String profileKey)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null || profile.getFolderId() == null)
		{
			return;
		}
		String folderId = profile.getFolderId();
		profile.setFolderId(null);
		autoDissolve(folderId);
		save();
	}

	/**
	 * Remove a folder, orphaning every member back to a loose row.
	 */
	synchronized void dissolveFolder(String folderId)
	{
		if (folders.remove(folderId) == null)
		{
			return;
		}
		for (EmitterProfile profile : profiles.values())
		{
			if (folderId.equals(profile.getFolderId()))
			{
				profile.setFolderId(null);
			}
		}
		save();
	}

	synchronized void setFolderEnabled(String folderId, boolean enabled)
	{
		ProfileFolder folder = folders.get(folderId);
		if (folder != null && folder.isEnabled() != enabled)
		{
			folder.setEnabled(enabled);
			save();
		}
	}

	synchronized void setFolderWip(String folderId, boolean wip)
	{
		ProfileFolder folder = folders.get(folderId);
		if (folder != null && folder.isWip() != wip)
		{
			folder.setWip(wip);
			save();
		}
	}

	synchronized void renameFolder(String folderId, String name)
	{
		ProfileFolder folder = folders.get(folderId);
		if (folder != null && name != null && !name.trim().isEmpty())
		{
			folder.setName(name.trim());
			save();
		}
	}

	/**
	 * Bulk enable/disable the rendered rows in one write: folder preferences for
	 * folders and profile toggles for loose profiles. Foldered children are
	 * never touched - users control the folder, not the child.
	 */
	synchronized void setEnabledMany(Collection<String> looseProfileKeys,
		Collection<String> folderIds, boolean enabled)
	{
		boolean changed = false;
		for (String key : looseProfileKeys)
		{
			EmitterProfile profile = profiles.get(key);
			if (profile != null && profile.isEnabled() != enabled)
			{
				profile.setEnabled(enabled);
				changed = true;
			}
		}
		for (String id : folderIds)
		{
			ProfileFolder folder = folders.get(id);
			if (folder != null && folder.isEnabled() != enabled)
			{
				folder.setEnabled(enabled);
				changed = true;
			}
		}
		if (changed)
		{
			save();
		}
	}

	private void moveToFolder(EmitterProfile member, String folderId)
	{
		String old = member.getFolderId();
		member.setFolderId(folderId);
		if (old != null && !old.equals(folderId))
		{
			autoDissolve(old);
		}
	}

	/**
	 * Dissolve a folder that has dropped below two members, orphaning any lone
	 * remaining member. Mutates without saving; the caller persists.
	 */
	private void autoDissolve(String folderId)
	{
		int count = 0;
		for (EmitterProfile profile : profiles.values())
		{
			if (folderId.equals(profile.getFolderId()))
			{
				count++;
			}
		}
		if (count < 2)
		{
			folders.remove(folderId);
			for (EmitterProfile profile : profiles.values())
			{
				if (folderId.equals(profile.getFolderId()))
				{
					profile.setFolderId(null);
				}
			}
		}
	}

	@Nullable
	private EmitterProfile anyMember(String folderId)
	{
		for (EmitterProfile profile : profiles.values())
		{
			if (folderId.equals(profile.getFolderId()))
			{
				return profile;
			}
		}
		return null;
	}

	private static boolean sameCategory(EmitterProfile a, EmitterProfile b)
	{
		return Objects.equals(a.getTargetType(), b.getTargetType());
	}

	private String freeFolderId()
	{
		int n = 1;
		while (folders.containsKey("folder:" + n))
		{
			n++;
		}
		return "folder:" + n;
	}
}
