package dev.cnnnr;

/**
 * Particle silhouette. Default is the soft round glow; Star and Diamond warp
 * the shared billboard disc's vertices into their outline (an eight-point
 * star is two diamonds crossed at the center, a diamond is four points on the
 * axes). Warping keeps the mesh topology intact, so the batch canvas is
 * unaffected.
 */
enum Shape
{
	DEFAULT("Default"),
	STAR("Star"),
	DIAMOND("Diamond");

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
