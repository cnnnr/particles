package dev.cnnnr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The bundled preset pack is every fresh install's entire content; a seed
 * that fails to parse would ship a plugin that silently does nothing.
 */
public class PresetsTest
{
	@Test
	public void bundledPresetsParse() throws Exception
	{
		try (InputStream in = EmitterStore.class.getResourceAsStream("/presets.json"))
		{
			assertNotNull("presets.json missing from resources", in);
			Map<String, EmitterProfile> profiles = new Gson().fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8),
				new TypeToken<Map<String, EmitterProfile>>()
				{
				}.getType());
			assertNotNull(profiles);
			assertFalse("preset pack is empty", profiles.isEmpty());

			profiles.forEach((key, profile) ->
			{
				assertNotNull("profile " + key + " has no name", profile.getName());
				if (profile.isProjectileTarget())
				{
					assertTrue("projectile profile " + key + " has no projectile id",
						profile.getProjectileId() >= 0);
				}
				else if (profile.isGraphicTarget())
				{
					assertTrue("graphic profile " + key + " has no graphic id",
						profile.getGraphicId() >= 0);
				}
				else if (profile.isObjectTarget())
				{
					assertTrue("object profile " + key + " has no object id", profile.getObjectId() >= 0);
					assertNotNull("object profile " + key + " has no signature", profile.getSignature());
					assertFalse("object profile " + key + " has no vertices", profile.getVertices().isEmpty());
				}
				else if (profile.isNpcTarget())
				{
					assertTrue("npc profile " + key + " has no npc id", profile.getNpcId() >= 0);
					assertNotNull("npc profile " + key + " has no signature", profile.getSignature());
					assertFalse("npc profile " + key + " has no vertices", profile.getVertices().isEmpty());
				}
				else
				{
					// Player target
					assertNotNull("player profile " + key + " has no signature", profile.getSignature());
					assertFalse("player profile " + key + " has no vertices", profile.getVertices().isEmpty());
				}
				int move = profile.getMovementLifetime();
				assertTrue("movementLifetime out of range in " + key, move >= 10 && move <= 100);
			});
		}
	}

	/**
	 * The bundled folders must parse and stay consistent with the presets: every
	 * membership resolves to a shipped folder and every folder ships at least
	 * two members - a guard against a broken folder re-export.
	 */
	@Test
	public void bundledFoldersParseAndAreConsistent() throws Exception
	{
		Map<String, ProfileFolder> folders;
		try (InputStream in = EmitterStore.class.getResourceAsStream("/folders.json"))
		{
			assertNotNull("folders.json missing from resources", in);
			folders = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				new TypeToken<Map<String, ProfileFolder>>()
				{
				}.getType());
		}
		assertNotNull("folders.json is not a JSON object", folders);

		Map<String, EmitterProfile> profiles;
		try (InputStream in = EmitterStore.class.getResourceAsStream("/presets.json"))
		{
			profiles = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				new TypeToken<Map<String, EmitterProfile>>()
				{
				}.getType());
		}

		Map<String, Integer> counts = new HashMap<>();
		profiles.forEach((key, profile) ->
		{
			String folderId = profile.getFolderId();
			if (folderId != null)
			{
				assertTrue("profile " + key + " points at missing folder " + folderId,
					folders.containsKey(folderId));
				counts.merge(folderId, 1, Integer::sum);
			}
		});
		folders.forEach((id, folder) ->
			assertTrue("folder " + id + " ships fewer than two members",
				counts.getOrDefault(id, 0) >= 2));
	}
}
