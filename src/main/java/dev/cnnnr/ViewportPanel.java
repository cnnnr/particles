package dev.cnnnr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import javax.swing.JPanel;

/**
 * Software-rendered wireframe viewer for a {@link ModelSnapshot}.
 * Drag to orbit, scroll to zoom, hover to inspect a vertex, click to toggle
 * a vertex as an emitter. Shift+drag box-adds many vertices at once;
 * Ctrl+drag box-removes them.
 */
class ViewportPanel extends JPanel
{
	private static final Color COLOR_BACKGROUND = new Color(27, 27, 31);
	private static final Color COLOR_EDGE = new Color(62, 62, 72);
	private static final Color COLOR_VERTEX = new Color(170, 170, 180);
	private static final Color COLOR_HOVER = new Color(0, 220, 255);
	private static final Color COLOR_SELECTED = new Color(255, 152, 31);
	private static final Color COLOR_TEXT = new Color(200, 200, 205);
	private static final int HIT_RADIUS = 10;

	private final IntConsumer onVertexToggled;

	/**
	 * Box selection result: the vertices inside the box, and true to add them
	 * as emitters or false to remove them.
	 */
	private final BiConsumer<Set<Integer>, Boolean> onBoxSelected;

	private ModelSnapshot snapshot;
	private int pieceFilter = -1; // -1 = all pieces

	/**
	 * Scrubber pose: replacement vertex positions sharing the snapshot's
	 * topology, or null to show the snapshot's own pose.
	 */
	private float[] overrideX;
	private float[] overrideY;
	private float[] overrideZ;

	private double yaw = 0.5;
	private double pitch = 0.25;
	private double zoom = 1.0;

	// Fit computed from the visible vertex set
	private float centerX, centerY, centerZ;
	private float fitRadius = 1;

	// Per-vertex projection cache, rebuilt each paint, used for hit testing
	private int[] screenX;
	private int[] screenY;
	private boolean[] vertexVisible;

	private int hoverVertex = -1;
	private Set<Integer> selectedVertices = new HashSet<>();
	private boolean labelAll;

	private int lastDragX, lastDragY;
	private boolean dragged;

	// Rubber-band box selection state (shift+drag adds, ctrl+drag removes)
	private boolean boxSelecting;
	private boolean boxRemove;
	private int boxStartX, boxStartY, boxEndX, boxEndY;

	ViewportPanel(IntConsumer onVertexToggled, BiConsumer<Set<Integer>, Boolean> onBoxSelected)
	{
		this.onVertexToggled = onVertexToggled;
		this.onBoxSelected = onBoxSelected;
		setBackground(COLOR_BACKGROUND);

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				lastDragX = e.getX();
				lastDragY = e.getY();
				dragged = false;
				boxSelecting = e.isShiftDown() || e.isControlDown();
				boxRemove = e.isControlDown();
				boxStartX = boxEndX = e.getX();
				boxStartY = boxEndY = e.getY();
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				int dx = e.getX() - lastDragX;
				int dy = e.getY() - lastDragY;
				if (Math.abs(dx) + Math.abs(dy) > 2)
				{
					dragged = true;
				}
				if (boxSelecting)
				{
					boxEndX = e.getX();
					boxEndY = e.getY();
				}
				else
				{
					yaw += dx * 0.012;
					pitch = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitch + dy * 0.012));
				}
				lastDragX = e.getX();
				lastDragY = e.getY();
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (boxSelecting)
				{
					finishBoxSelection();
					return;
				}
				if (!dragged && hoverVertex != -1)
				{
					onVertexToggled.accept(hoverVertex);
				}
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				int previous = hoverVertex;
				hoverVertex = findVertexAt(e.getX(), e.getY());
				if (hoverVertex != previous)
				{
					repaint();
				}
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e)
			{
				zoom = Math.max(0.2, Math.min(25.0, zoom * Math.pow(1.12, -e.getWheelRotation())));
				repaint();
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);
	}

	void setSnapshot(ModelSnapshot snapshot)
	{
		this.snapshot = snapshot;
		this.pieceFilter = -1;
		this.hoverVertex = -1;
		this.overrideX = null;
		this.overrideY = null;
		this.overrideZ = null;
		screenX = new int[snapshot.getVertexCount()];
		screenY = new int[snapshot.getVertexCount()];
		vertexVisible = new boolean[snapshot.getVertexCount()];
		fit();
		repaint();
	}

	void setPieceFilter(int pieceIndex)
	{
		this.pieceFilter = pieceIndex;
		this.hoverVertex = -1;
		fit();
		repaint();
	}

	void setSelectedVertices(Set<Integer> vertices)
	{
		this.selectedVertices = new HashSet<>(vertices);
		repaint();
	}

	void setLabelAll(boolean labelAll)
	{
		this.labelAll = labelAll;
		repaint();
	}

	/**
	 * Show a different pose of the same topology (the animation scrubber);
	 * null restores the snapshot's own pose. Length-mismatched arrays are
	 * ignored rather than risking out-of-bounds projection.
	 */
	void setPositionOverride(float[] xs, float[] ys, float[] zs)
	{
		// Live model arrays are backing buffers that may run longer than the
		// vertex count; only reject arrays too SHORT to project safely
		if (snapshot != null && xs != null && xs.length < snapshot.getVertexCount())
		{
			return;
		}
		overrideX = xs;
		overrideY = ys;
		overrideZ = zs;
		repaint();
	}

	/**
	 * Recompute the view center and fit radius from the visible vertices.
	 */
	private void fit()
	{
		if (snapshot == null)
		{
			return;
		}

		updateVisibility();

		float sx = 0, sy = 0, sz = 0;
		int n = 0;
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (!vertexVisible[v])
			{
				continue;
			}
			sx += snapshot.getVerticesX()[v];
			sy += snapshot.getVerticesY()[v];
			sz += snapshot.getVerticesZ()[v];
			n++;
		}
		if (n == 0)
		{
			return;
		}
		centerX = sx / n;
		centerY = sy / n;
		centerZ = sz / n;

		float maxDistSq = 1;
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (!vertexVisible[v])
			{
				continue;
			}
			float dx = snapshot.getVerticesX()[v] - centerX;
			float dy = snapshot.getVerticesY()[v] - centerY;
			float dz = snapshot.getVerticesZ()[v] - centerZ;
			float distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > maxDistSq)
			{
				maxDistSq = distSq;
			}
		}
		fitRadius = (float) Math.sqrt(maxDistSq);
		zoom = 1.0;
	}

	private void updateVisibility()
	{
		if (pieceFilter < 0 || pieceFilter >= snapshot.getPieces().size())
		{
			for (int v = 0; v < vertexVisible.length; v++)
			{
				vertexVisible[v] = true;
			}
			return;
		}

		for (int v = 0; v < vertexVisible.length; v++)
		{
			vertexVisible[v] = false;
		}
		for (int v : snapshot.getPieces().get(pieceFilter).getVertices())
		{
			vertexVisible[v] = true;
		}
	}

	private void finishBoxSelection()
	{
		boxSelecting = false;
		int x0 = Math.min(boxStartX, boxEndX);
		int x1 = Math.max(boxStartX, boxEndX);
		int y0 = Math.min(boxStartY, boxEndY);
		int y1 = Math.max(boxStartY, boxEndY);
		repaint();

		if (snapshot == null || (x1 - x0 < 4 && y1 - y0 < 4))
		{
			return;
		}

		Set<Integer> inside = new HashSet<>();
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (vertexVisible[v]
				&& screenX[v] >= x0 && screenX[v] <= x1
				&& screenY[v] >= y0 && screenY[v] <= y1)
			{
				inside.add(v);
			}
		}
		if (!inside.isEmpty())
		{
			onBoxSelected.accept(inside, !boxRemove);
		}
	}

	private int findVertexAt(int x, int y)
	{
		if (snapshot == null)
		{
			return -1;
		}
		int best = -1;
		int bestDistSq = HIT_RADIUS * HIT_RADIUS;
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (!vertexVisible[v])
			{
				continue;
			}
			int dx = screenX[v] - x;
			int dy = screenY[v] - y;
			int distSq = dx * dx + dy * dy;
			if (distSq < bestDistSq)
			{
				bestDistSq = distSq;
				best = v;
			}
		}
		return best;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g;

		if (snapshot == null)
		{
			g2.setColor(COLOR_TEXT);
			g2.drawString("No model snapshot. Log in and press Refresh.", 20, 30);
			return;
		}

		project();

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setStroke(new BasicStroke(1f));

		// Edges
		g2.setColor(COLOR_EDGE);
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		for (int f = 0; f < snapshot.getFaceCount(); f++)
		{
			int a = f1[f], b = f2[f], c = f3[f];
			if (!vertexVisible[a])
			{
				continue;
			}
			g2.drawLine(screenX[a], screenY[a], screenX[b], screenY[b]);
			g2.drawLine(screenX[b], screenY[b], screenX[c], screenY[c]);
			g2.drawLine(screenX[c], screenY[c], screenX[a], screenY[a]);
		}

		// Vertices
		g2.setColor(COLOR_VERTEX);
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (vertexVisible[v])
			{
				g2.fillRect(screenX[v] - 1, screenY[v] - 1, 3, 3);
			}
		}

		for (int v : selectedVertices)
		{
			if (v >= 0 && v < snapshot.getVertexCount() && vertexVisible[v])
			{
				drawMarkedVertex(g2, v, COLOR_SELECTED, labelAll);
			}
		}
		if (hoverVertex != -1 && !selectedVertices.contains(hoverVertex))
		{
			drawMarkedVertex(g2, hoverVertex, COLOR_HOVER, true);
		}

		// Rubber-band box
		if (boxSelecting)
		{
			int x0 = Math.min(boxStartX, boxEndX);
			int y0 = Math.min(boxStartY, boxEndY);
			int w = Math.abs(boxEndX - boxStartX);
			int h = Math.abs(boxEndY - boxStartY);
			Color boxColor = boxRemove ? COLOR_SELECTED : COLOR_HOVER;
			g2.setColor(new Color(boxColor.getRed(), boxColor.getGreen(), boxColor.getBlue(), 40));
			g2.fillRect(x0, y0, w, h);
			g2.setColor(boxColor);
			g2.drawRect(x0, y0, w, h);
		}

		// Status line
		g2.setColor(COLOR_TEXT);
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
		String status = snapshot.getVertexCount() + " vertices, " + snapshot.getFaceCount() + " faces"
			+ (pieceFilter >= 0 ? "  |  piece " + (pieceFilter + 1) : "")
			+ "  |  emitters: " + selectedVertices.size()
			+ "  |  drag orbits, click toggles, shift+drag box-adds, ctrl+drag box-removes";
		g2.drawString(status, 10, getHeight() - 10);
	}

	private void drawMarkedVertex(Graphics2D g2, int v, Color color, boolean label)
	{
		g2.setColor(color);
		g2.fillOval(screenX[v] - 3, screenY[v] - 3, 7, 7);
		g2.drawOval(screenX[v] - 6, screenY[v] - 6, 13, 13);
		if (label)
		{
			g2.drawString("v" + v, screenX[v] + 9, screenY[v] - 6);
		}
	}

	private void project()
	{
		double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
		double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
		double scale = Math.min(getWidth(), getHeight()) * 0.45 / fitRadius * zoom;
		int halfW = getWidth() / 2;
		int halfH = getHeight() / 2;

		float[] vx = overrideX != null ? overrideX : snapshot.getVerticesX();
		float[] vy = overrideY != null ? overrideY : snapshot.getVerticesY();
		float[] vz = overrideZ != null ? overrideZ : snapshot.getVerticesZ();
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			double dx = vx[v] - centerX;
			double dy = vy[v] - centerY;
			double dz = vz[v] - centerZ;

			double rx = dx * cosYaw + dz * sinYaw;
			double rz = -dx * sinYaw + dz * cosYaw;
			double ry = dy * cosPitch - rz * sinPitch;

			screenX[v] = halfW + (int) (rx * scale);
			screenY[v] = halfH + (int) (ry * scale);
		}
	}
}
