package dev.cnnnr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
				if (EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType()))
				{
					assertNotNull("player profile " + key + " has no signature", profile.getSignature());
					assertFalse("player profile " + key + " has no vertices", profile.getVertices().isEmpty());
				}
				else
				{
					assertTrue("projectile profile " + key + " has no projectile id",
						profile.getProjectileId() >= 0);
				}
				int move = profile.getMovementLifetime();
				assertTrue("movementLifetime out of range in " + key, move >= 10 && move <= 100);
			});
		}
	}
}
