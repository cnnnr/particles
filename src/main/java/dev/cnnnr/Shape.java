package dev.cnnnr;

/**
 * Particle silhouette, realized as a per-face alpha falloff over the shared
 * billboard disc geometry - not a different mesh, so the batch canvas keeps
 * its uniform topology. Every shape stays soft and glowy; the alpha mask just
 * carves the outline.
 */
enum Shape
{
	DEFAULT("Default"),
	RING("Ring"),
	STAR("Star"),
	TEARDROP("Teardrop"),
	CROSS("Cross");

	private final String label;

	Shape(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
