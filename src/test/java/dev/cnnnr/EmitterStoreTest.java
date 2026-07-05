package dev.cnnnr;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The seed/merge behavior decides whether a plugin update actually delivers
 * new presets to existing users without clobbering their toggles.
 * mergeWithBundle takes the saved JSON directly, so no ConfigManager is needed.
 */
public class EmitterStoreTest
{
	private EmitterStore store()
	{
		// ConfigManager is unused by mergeWithBundle
		return new EmitterStore(null, new Gson());
	}

	@Test
	public void freshInstallSeedsTheWholeBundle()
	{
		Map<String, EmitterProfile> all = store().mergeWithBundle(null);
		assertTrue("fresh install should seed many presets", all.size() > 10);
	}

	@Test
	public void returningUserKeepsTogglesAndGetsNewPresets()
	{
		// A user who toggled one custom profile off and saved. The custom key
		// is not in the bundle, standing in for "state the user set".
		String saved = "{\"user-custom\":{\"name\":\"mine\",\"targetType\":\"player\","
			+ "\"enabled\":false,\"signature\":\"sig\",\"vertices\":[1],\"movementLifetime\":100}}";
		Map<String, EmitterProfile> all = store().mergeWithBundle(saved);

		// Their saved profile survives, with its toggle intact
		assertTrue("saved profile must be kept", all.containsKey("user-custom"));
		assertFalse("toggled-off state must be preserved", all.get("user-custom").isEnabled());

		// And the bundled presets are merged in alongside it
		assertTrue("bundled presets must be added for a returning user", all.size() > 10);
	}

	@Test
	public void savedProfileIsNotOverwrittenByABundledKeyOfTheSameName()
	{
		// If a saved key collides with a bundled key, the user's version wins
		// (their toggle/edits are never clobbered).
		String bundledKey = firstBundledKey();
		String saved = "{\"" + bundledKey + "\":{\"name\":\"kept\",\"targetType\":\"player\","
			+ "\"enabled\":false,\"signature\":\"sig\",\"vertices\":[1],\"movementLifetime\":100}}";
		Map<String, EmitterProfile> all = store().mergeWithBundle(saved);

		assertFalse("collision must keep the user's disabled state",
			all.get(bundledKey).isEnabled());
		assertTrue("kept".equals(all.get(bundledKey).getName()));
	}

	private String firstBundledKey()
	{
		return store().mergeWithBundle(null).keySet().iterator().next();
	}
}
