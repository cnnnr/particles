package dev.cnnnr;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The seed/merge behavior decides whether a plugin update delivers new
 * presets and updated preset content to existing users without clobbering
 * their toggles. mergeWithBundle takes the saved JSON directly, so no
 * ConfigManager is needed.
 */
public class EmitterStoreTest
{
	private EmitterStore store(boolean developerMode)
	{
		// ConfigManager is unused by mergeWithBundle
		return new EmitterStore(null, new Gson(), developerMode);
	}

	private String savedWithKey(String key, boolean enabled, String name)
	{
		return "{\"" + key + "\":{\"name\":\"" + name + "\",\"targetType\":\"player\","
			+ "\"enabled\":" + enabled + ",\"signature\":\"sig\",\"vertices\":[1],"
			+ "\"movementLifetime\":100}}";
	}

	private String firstBundledKey()
	{
		return store(false).mergeWithBundle(null).keySet().iterator().next();
	}

	@Test
	public void freshInstallSeedsTheWholeBundle()
	{
		assertTrue("fresh install should seed many presets",
			store(false).mergeWithBundle(null).size() > 10);
	}

	@Test
	public void shippedUserKeepsTogglesAndGetsUpdatedContent()
	{
		String bundledKey = firstBundledKey();
		String bundledName = store(false).mergeWithBundle(null).get(bundledKey).getName();

		// A shipped user who toggled a bundled preset off and (defensively)
		// has stale content saved for it under a different name.
		Map<String, EmitterProfile> all =
			store(false).mergeWithBundle(savedWithKey(bundledKey, false, "stale"));

		// Toggle preserved, but content comes from the bundle (not "stale")
		assertFalse("toggle must be preserved", all.get(bundledKey).isEnabled());
		assertEquals("content must come from the bundle", bundledName, all.get(bundledKey).getName());
	}

	@Test
	public void shippedUserGetsNewPresetsAndKeepsOwnProfiles()
	{
		// A user-created key not in the bundle survives; bundle presets are added.
		Map<String, EmitterProfile> all =
			store(false).mergeWithBundle(savedWithKey("user-custom", false, "mine"));
		assertTrue("user-created profile survives", all.containsKey("user-custom"));
		assertFalse("its toggle is preserved", all.get("user-custom").isEnabled());
		assertTrue("bundled presets are added", all.size() > 10);
	}

	@Test
	public void developerModeKeepsConfigAsSourceOfTruth()
	{
		// In dev mode a saved edit to a bundled key is NOT overwritten by the
		// bundle - authoring persists.
		String bundledKey = firstBundledKey();
		Map<String, EmitterProfile> all =
			store(true).mergeWithBundle(savedWithKey(bundledKey, false, "my-edit"));
		assertEquals("dev config wins", "my-edit", all.get(bundledKey).getName());
		assertFalse(all.get(bundledKey).isEnabled());
	}

	@Test
	public void developerModeSeedsBundleWhenConfigEmpty()
	{
		assertTrue("empty dev config still seeds the bundle",
			store(true).mergeWithBundle(null).size() > 10);
	}
}
