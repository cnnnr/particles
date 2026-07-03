package dev.cnnnr;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Debug overlay: marks the active emitter vertices in-game.
 * Particles themselves are rendered as scene objects by {@link ParticleRenderer}.
 */
class ParticlesOverlay extends Overlay
{
	private static final Color MARKER = new Color(0, 220, 255, 200);

	private final Client client;
	private final ParticlesPlugin plugin;
	private final ParticlesConfig config;

	@Inject
	ParticlesOverlay(Client client, ParticlesPlugin plugin, ParticlesConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showAnchor())
		{
			return null;
		}

		// Diagnostics: sim vs render state, for telling apart early particle
		// death (sim bug) from objects not being drawn (engine limitation)
		String stats = plugin.getStatsLine();
		if (!stats.isEmpty())
		{
			graphics.setColor(Color.BLACK);
			graphics.drawString(stats, 11, 41);
			graphics.setColor(Color.CYAN);
			graphics.drawString(stats, 10, 40);
		}

		if (plugin.getAnchorCount() == 0)
		{
			return null;
		}

		graphics.setColor(MARKER);
		for (int i = 0; i < plugin.getAnchorCount(); i++)
		{
			// Anchor z is absolute scene height, so project it directly rather
			// than letting localToCanvas re-derive height from the terrain
			// under the anchor - that desyncs from the model on slopes
			Point canvas = Perspective.localToCanvas(client,
				plugin.getAnchorWorldView(),
				(int) plugin.getAnchorXs()[i],
				(int) plugin.getAnchorYs()[i],
				(int) plugin.getAnchorZs()[i]);
			if (canvas == null)
			{
				continue;
			}
			graphics.drawOval(canvas.getX() - 3, canvas.getY() - 3, 6, 6);
		}

		// The feathered emission line, for tuning feather strength
		for (float[] points : plugin.getFeatherDebugPaths())
		{
			Point previous = null;
			for (int i = 0; i < points.length; i += 3)
			{
				Point current = Perspective.localToCanvas(client,
					plugin.getAnchorWorldView(),
					(int) points[i], (int) points[i + 1], (int) points[i + 2]);
				if (previous != null && current != null)
				{
					graphics.drawLine(previous.getX(), previous.getY(), current.getX(), current.getY());
				}
				previous = current;
			}
		}

		return null;
	}
}
