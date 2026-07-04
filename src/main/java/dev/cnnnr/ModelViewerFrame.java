package dev.cnnnr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.Getter;

/**
 * Dev tool window with a target mode selector: player-model authoring
 * (emitter profiles and mesh pieces on the left with a per-profile style
 * editor, orbitable wireframe viewport on the right; clicking a vertex
 * toggles it as an emitter) and projectile authoring (profiles plus a live
 * capture list of recently seen projectile IDs). Profiles can be duplicated
 * and gated on worn item IDs so recolored variants of one model get their
 * own particles.
 */
class ModelViewerFrame extends JFrame
{
	/**
	 * Everything the viewer needs from the plugin. All methods run on the EDT.
	 */
	interface Callbacks
	{
		void refreshSnapshot();

		void vertexToggled(@Nullable String profileKey, int vertex);

		void boxSelected(@Nullable String profileKey, Set<Integer> vertices, boolean add);

		void selectionChanged();

		@Nullable
		EmitterProfile profile(String profileKey);

		void saveProfile(String profileKey, EmitterProfile profile);

		@Nullable
		String duplicateProfile(String profileKey);

		void deleteProfile(String profileKey);

		String createProjectileProfile(int projectileId);
	}

	/**
	 * One list entry per profile, or per piece if it has no profiles.
	 */
	static class ProfileEntry
	{
		final String key;
		final String name;
		final boolean filtered;

		ProfileEntry(String key, String name, boolean filtered)
		{
			this.key = key;
			this.name = name;
			this.filtered = filtered;
		}
	}

	private static class Row
	{
		final int pieceIndex;
		@Nullable
		final String profileKey;
		/**
		 * Projectile ID of a capture-list row, or -1.
		 */
		final int captureId;

		Row(int pieceIndex, @Nullable String profileKey)
		{
			this(pieceIndex, profileKey, -1);
		}

		Row(int pieceIndex, @Nullable String profileKey, int captureId)
		{
			this.pieceIndex = pieceIndex;
			this.profileKey = profileKey;
			this.captureId = captureId;
		}
	}

	private final Callbacks callbacks;
	private final ViewportPanel viewport;
	private final DefaultListModel<String> rowListModel = new DefaultListModel<>();
	private final JList<String> rowList;
	private final List<Row> rows = new ArrayList<>();
	private final JComboBox<String> modeSelector = new JComboBox<>(new String[]{"Player model", "Projectiles"});
	private final JPanel projectileAddPanel = new JPanel(new BorderLayout(4, 0));
	private final JTextField projectileIdField = new JTextField();

	// Style editor controls
	private final JButton colorButton = new JButton();
	private final JSpinner alphaSpinner = new JSpinner(new SpinnerNumberModel(150, 0, 255, 5));
	private final JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(12, 2, 64, 1));
	private final JSpinner rateSpinner = new JSpinner(new SpinnerNumberModel(80, 0, 1000, 5));
	private final JSpinner trailSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 200, 5));
	private final JSpinner lifetimeSpinner = new JSpinner(new SpinnerNumberModel(2400, 100, 10000, 100));
	private final JSpinner moveLifetimeSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 100, 5));
	private final JSpinner riseSpinner = new JSpinner(new SpinnerNumberModel(26, 0, 256, 2));
	private final JSpinner spreadSpinner = new JSpinner(new SpinnerNumberModel(12, 0, 256, 2));
	private final JSpinner jitterSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 64, 1));
	private final JSpinner featherSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 8, 1));
	private final JSpinner depthBiasSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 64, 2));
	private final JSpinner offsetXSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 2));
	private final JSpinner offsetYSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 2));
	private final JSpinner offsetZSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 2));
	private final JTextField itemFilterField = new JTextField();
	private final JTextField animFilterField = new JTextField();
	private final JTextField animFramesField = new JTextField();
	private final JComboBox<String> wornItemsCombo = new JComboBox<>();
	private final JButton addWornItemButton = new JButton("+");
	private final JButton duplicateButton = new JButton("Duplicate profile");
	private final JButton deleteButton = new JButton("Delete profile");
	private final JLabel editorHint = new JLabel("Select a piece to edit its style");

	private ModelSnapshot snapshot;
	@Getter
	@Nullable
	private String selectedProfileKey;
	private int selectedCaptureId = -1;
	private String pendingSelection;
	private boolean populating;
	private boolean rebuildingRows;
	private boolean updatingMode;
	private boolean projectileMode;
	private int appliedPieceIndex = -1;

	// Latest data from the plugin, cached so mode switches can rebuild rows
	private Map<String, List<ProfileEntry>> profilesBySignature = Map.of();
	private List<ProfileEntry> projectileEntries = List.of();
	private List<int[]> recentProjectiles = List.of();

	ModelViewerFrame(Callbacks callbacks)
	{
		super("Particles - vertex picker");
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		this.callbacks = callbacks;

		viewport = new ViewportPanel(
			vertex ->
			{
				if (!projectileMode)
				{
					callbacks.vertexToggled(selectedProfileKey, vertex);
				}
			},
			(vertices, add) ->
			{
				if (!projectileMode)
				{
					callbacks.boxSelected(selectedProfileKey, vertices, add);
				}
			});

		rowList = new JList<>(rowListModel);
		rowList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		rowList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting() || rebuildingRows)
			{
				return;
			}
			int index = rowList.getSelectedIndex();
			Row row = index >= 0 && index < rows.size() ? rows.get(index) : new Row(-1, null);
			// Only refit the viewport when the piece actually changes, so row
			// refreshes (e.g. a click creating a profile) keep the camera
			boolean pieceChanged = row.pieceIndex != appliedPieceIndex;
			boolean profileChanged = !Objects.equals(row.profileKey, selectedProfileKey)
				|| row.captureId != selectedCaptureId;
			appliedPieceIndex = row.pieceIndex;
			selectedProfileKey = row.profileKey;
			selectedCaptureId = row.captureId;
			if (pieceChanged)
			{
				viewport.setPieceFilter(row.pieceIndex);
			}
			if (pieceChanged || profileChanged)
			{
				refreshStyleEditor();
				callbacks.selectionChanged();
			}
		});

		modeSelector.addActionListener(e ->
		{
			if (updatingMode)
			{
				return;
			}
			projectileMode = modeSelector.getSelectedIndex() == 1;
			projectileAddPanel.setVisible(projectileMode);
			if (snapshot != null)
			{
				rebuildRows();
			}
		});

		JButton refresh = new JButton("Refresh snapshot");
		refresh.addActionListener(e -> callbacks.refreshSnapshot());

		JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
		top.add(modeSelector);
		top.add(refresh);

		JButton addProjectile = new JButton("Add");
		addProjectile.setToolTipText("Create a profile for the typed projectile ID, or the selected capture row");
		addProjectile.addActionListener(e -> addProjectileProfile());
		projectileIdField.setToolTipText("Projectile ID; leave blank to use the selected capture row");
		projectileAddPanel.add(new JLabel("ID "), BorderLayout.WEST);
		projectileAddPanel.add(projectileIdField, BorderLayout.CENTER);
		projectileAddPanel.add(addProjectile, BorderLayout.EAST);
		projectileAddPanel.setVisible(false);

		JCheckBox labelAll = new JCheckBox("Label emitter vertices");
		labelAll.addActionListener(e -> viewport.setLabelAll(labelAll.isSelected()));

		JPanel left = new JPanel(new BorderLayout(0, 6));
		left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		left.add(top, BorderLayout.NORTH);
		left.add(new JScrollPane(rowList), BorderLayout.CENTER);

		JPanel bottom = new JPanel(new BorderLayout(0, 6));
		bottom.add(projectileAddPanel, BorderLayout.NORTH);
		JPanel bottomMid = new JPanel(new BorderLayout(0, 6));
		bottomMid.add(labelAll, BorderLayout.NORTH);
		bottomMid.add(buildStyleEditor(), BorderLayout.CENTER);
		bottom.add(bottomMid, BorderLayout.CENTER);
		left.add(bottom, BorderLayout.SOUTH);
		left.setPreferredSize(new Dimension(250, 0));

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, viewport);
		split.setDividerLocation(250);
		setContentPane(split);

		setSize(1040, 780);
		setLocationRelativeTo(null);
	}

	private void addProjectileProfile()
	{
		int id = -1;
		try
		{
			id = Integer.parseInt(projectileIdField.getText().trim());
		}
		catch (NumberFormatException ignored)
		{
		}
		if (id < 0)
		{
			id = selectedCaptureId;
		}
		if (id < 0)
		{
			return;
		}
		projectileIdField.setText("");
		pendingSelection = callbacks.createProjectileProfile(id);
		callbacks.refreshSnapshot();
	}

	private JPanel buildStyleEditor()
	{
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 4));
		grid.add(new JLabel("Color"));
		grid.add(colorButton);
		grid.add(new JLabel("Opacity"));
		grid.add(alphaSpinner);
		grid.add(new JLabel("Size"));
		grid.add(sizeSpinner);
		grid.add(new JLabel("Rate /s"));
		grid.add(rateSpinner);
		grid.add(new JLabel("Trail / tile"));
		grid.add(trailSpinner);
		grid.add(new JLabel("Lifetime ms"));
		grid.add(lifetimeSpinner);
		grid.add(new JLabel("Moving life %"));
		grid.add(moveLifetimeSpinner);
		grid.add(new JLabel("Rise"));
		grid.add(riseSpinner);
		grid.add(new JLabel("Spread"));
		grid.add(spreadSpinner);
		grid.add(new JLabel("Jitter"));
		grid.add(jitterSpinner);
		grid.add(new JLabel("Feather"));
		grid.add(featherSpinner);
		grid.add(new JLabel("Depth bias"));
		grid.add(depthBiasSpinner);
		grid.add(new JLabel("Offset X"));
		grid.add(offsetXSpinner);
		grid.add(new JLabel("Offset Y"));
		grid.add(offsetYSpinner);
		grid.add(new JLabel("Offset Z (up)"));
		grid.add(offsetZSpinner);
		grid.add(new JLabel("Item filter"));
		grid.add(itemFilterField);
		grid.add(new JLabel("Anim filter"));
		grid.add(animFilterField);
		grid.add(new JLabel("Anim frames"));
		grid.add(animFramesField);

		colorButton.addActionListener(e ->
		{
			EmitterProfile profile = selectedProfile();
			if (profile == null)
			{
				return;
			}
			Color initial = new Color(profile.getColor(), true);
			Color picked = JColorChooser.showDialog(this, "Particle color", initial);
			if (picked != null)
			{
				colorButton.setBackground(picked);
				saveStyle();
			}
		});
		alphaSpinner.addChangeListener(e -> saveStyle());
		sizeSpinner.addChangeListener(e -> saveStyle());
		rateSpinner.addChangeListener(e -> saveStyle());
		trailSpinner.addChangeListener(e -> saveStyle());
		trailSpinner.setToolTipText("Particles per tile of emitter movement, spread evenly along the path - for weapon and projectile trails. Combine with Rate 0 for a pure ribbon.");
		lifetimeSpinner.addChangeListener(e -> saveStyle());
		moveLifetimeSpinner.addChangeListener(e -> saveStyle());
		moveLifetimeSpinner.setToolTipText("Percent of normal lifetime while the wearer walks or runs. Lower keeps plumes tight at speed instead of smearing a tile behind.");
		riseSpinner.addChangeListener(e -> saveStyle());
		spreadSpinner.addChangeListener(e -> saveStyle());
		jitterSpinner.addChangeListener(e -> saveStyle());
		featherSpinner.addChangeListener(e -> saveStyle());
		featherSpinner.setToolTipText("Emit along a line chained through the emitter vertices, smoothed over this many neighbors (0 = off). Higher values cut across jagged notches; the marker overlay draws the resulting line.");
		depthBiasSpinner.addChangeListener(e -> saveStyle());
		depthBiasSpinner.setToolTipText("Nudge particles this many units toward the camera at render time, so garments that render over their neighbors (a cape over a skirt) don't swallow them. ~16-32 fixes clipping; too much floats particles in front of things.");
		offsetXSpinner.addChangeListener(e -> saveStyle());
		offsetYSpinner.addChangeListener(e -> saveStyle());
		offsetZSpinner.addChangeListener(e -> saveStyle());

		// Save on every edit - an ActionListener would only fire on Enter,
		// silently discarding edits when the field loses focus instead
		DocumentListener saveOnEdit = new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				saveStyle();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				saveStyle();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				saveStyle();
			}
		};
		itemFilterField.getDocument().addDocumentListener(saveOnEdit);
		itemFilterField.setToolTipText("Comma-separated item IDs; only emit while one is worn. Blank = any variant of this mesh.");
		animFilterField.getDocument().addDocumentListener(saveOnEdit);
		animFilterField.setToolTipText("Comma-separated animation IDs; only emit while one is playing (action or pose). Blank = always. The overlay stats line shows the last action animation id.");
		animFramesField.getDocument().addDocumentListener(saveOnEdit);
		animFramesField.setToolTipText("Frame windows within the action animation, e.g. \"9-13, 15-19\" or \"7\". Blank = all frames.");

		addWornItemButton.setToolTipText("Append the selected worn item's ID to the filter");
		addWornItemButton.addActionListener(e ->
		{
			String selected = (String) wornItemsCombo.getSelectedItem();
			if (selected == null || selectedProfile() == null)
			{
				return;
			}
			String id = selected.split(" ")[0];
			String current = itemFilterField.getText().trim();
			itemFilterField.setText(current.isEmpty() ? id : current + ", " + id);
			saveStyle();
		});

		duplicateButton.setToolTipText("Clone this profile so another item variant gets its own particles");
		duplicateButton.addActionListener(e ->
		{
			if (selectedProfileKey == null)
			{
				return;
			}
			String newKey = callbacks.duplicateProfile(selectedProfileKey);
			if (newKey != null)
			{
				pendingSelection = newKey;
				callbacks.refreshSnapshot();
			}
		});

		deleteButton.setToolTipText("Delete this profile entirely");
		deleteButton.addActionListener(e ->
		{
			if (selectedProfileKey == null)
			{
				return;
			}
			EmitterProfile profile = selectedProfile();
			String name = profile != null ? profile.getName() : selectedProfileKey;
			if (javax.swing.JOptionPane.showConfirmDialog(this,
				"Delete profile '" + name + "'?", "Delete profile",
				javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION)
			{
				callbacks.deleteProfile(selectedProfileKey);
			}
		});

		JPanel wornRow = new JPanel(new BorderLayout(4, 0));
		wornRow.add(wornItemsCombo, BorderLayout.CENTER);
		wornRow.add(addWornItemButton, BorderLayout.EAST);

		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.add(duplicateButton);
		buttons.add(deleteButton);

		JPanel south = new JPanel(new BorderLayout(0, 4));
		south.add(wornRow, BorderLayout.NORTH);
		south.add(buttons, BorderLayout.SOUTH);

		JPanel editor = new JPanel(new BorderLayout(0, 6));
		editor.setBorder(BorderFactory.createTitledBorder("Profile"));
		editor.add(editorHint, BorderLayout.NORTH);
		editor.add(grid, BorderLayout.CENTER);
		editor.add(south, BorderLayout.SOUTH);
		setEditorEnabled(false);
		return editor;
	}

	@Nullable
	private EmitterProfile selectedProfile()
	{
		return selectedProfileKey == null ? null : callbacks.profile(selectedProfileKey);
	}

	/**
	 * Reload the style editor from the selected profile, e.g. after a vertex
	 * toggle created one. Must be called on the Swing EDT.
	 */
	void refreshStyleEditor()
	{
		EmitterProfile profile = selectedProfile();
		if (profile == null)
		{
			if (selectedCaptureId >= 0)
			{
				editorHint.setText("Press Add to create a profile for projectile " + selectedCaptureId);
			}
			else if (projectileMode)
			{
				editorHint.setText("Select a profile, or a seen projectile and press Add");
			}
			else
			{
				editorHint.setText(selectedProfileKey == null
					? "Click a vertex to create emitters"
					: "Profile missing; refresh the snapshot");
			}
			setEditorEnabled(false);
			return;
		}

		populating = true;
		Color color = new Color(profile.getColor(), true);
		colorButton.setBackground(new Color(color.getRed(), color.getGreen(), color.getBlue()));
		alphaSpinner.setValue(color.getAlpha());
		sizeSpinner.setValue(profile.getSize());
		rateSpinner.setValue(profile.getParticlesPerSecond());
		trailSpinner.setValue(profile.getTrailDensity());
		lifetimeSpinner.setValue(profile.getLifetimeMs());
		moveLifetimeSpinner.setValue(profile.getMovementLifetime());
		riseSpinner.setValue(profile.getRiseSpeed());
		spreadSpinner.setValue(profile.getSpreadSpeed());
		jitterSpinner.setValue(profile.getSpawnJitter());
		featherSpinner.setValue(profile.getFeatherStrength());
		depthBiasSpinner.setValue(profile.getDepthBias());
		offsetXSpinner.setValue(profile.getOffsetX());
		offsetYSpinner.setValue(profile.getOffsetY());
		offsetZSpinner.setValue(profile.getOffsetZ());
		itemFilterField.setText(joinIds(profile.getItemIds()));
		animFilterField.setText(joinIds(profile.getAnimationIds()));
		animFramesField.setText(profile.getAnimFrames());
		populating = false;

		editorHint.setText(profile.getName());
		setEditorEnabled(true);

		// Vertex-anchored settings don't apply to projectiles
		boolean projectile = profile.isProjectileTarget();
		featherSpinner.setEnabled(!projectile);
		offsetXSpinner.setEnabled(!projectile);
		offsetYSpinner.setEnabled(!projectile);
		offsetZSpinner.setEnabled(!projectile);
		animFilterField.setEnabled(!projectile);
		animFramesField.setEnabled(!projectile);
	}

	private static String joinIds(Set<Integer> ids)
	{
		StringBuilder sb = new StringBuilder();
		for (int id : ids)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(id);
		}
		return sb.toString();
	}

	private void setEditorEnabled(boolean enabled)
	{
		colorButton.setEnabled(enabled);
		alphaSpinner.setEnabled(enabled);
		sizeSpinner.setEnabled(enabled);
		rateSpinner.setEnabled(enabled);
		trailSpinner.setEnabled(enabled);
		lifetimeSpinner.setEnabled(enabled);
		moveLifetimeSpinner.setEnabled(enabled);
		riseSpinner.setEnabled(enabled);
		spreadSpinner.setEnabled(enabled);
		jitterSpinner.setEnabled(enabled);
		featherSpinner.setEnabled(enabled);
		depthBiasSpinner.setEnabled(enabled);
		offsetXSpinner.setEnabled(enabled);
		offsetYSpinner.setEnabled(enabled);
		offsetZSpinner.setEnabled(enabled);
		itemFilterField.setEnabled(enabled);
		animFilterField.setEnabled(enabled);
		animFramesField.setEnabled(enabled);
		wornItemsCombo.setEnabled(enabled);
		addWornItemButton.setEnabled(enabled);
		duplicateButton.setEnabled(enabled);
		deleteButton.setEnabled(enabled);
	}

	private void saveStyle()
	{
		if (populating || selectedProfileKey == null)
		{
			return;
		}
		EmitterProfile profile = selectedProfile();
		if (profile == null)
		{
			return;
		}

		Color rgb = colorButton.getBackground();
		int argb = ((int) alphaSpinner.getValue() & 0xff) << 24
			| (rgb.getRed() << 16) | (rgb.getGreen() << 8) | rgb.getBlue();
		profile.setColor(argb);
		profile.setSize((int) sizeSpinner.getValue());
		profile.setParticlesPerSecond((int) rateSpinner.getValue());
		profile.setTrailDensity((int) trailSpinner.getValue());
		profile.setLifetimeMs((int) lifetimeSpinner.getValue());
		profile.setMovementLifetime((int) moveLifetimeSpinner.getValue());
		profile.setRiseSpeed((int) riseSpinner.getValue());
		profile.setSpreadSpeed((int) spreadSpinner.getValue());
		profile.setSpawnJitter((int) jitterSpinner.getValue());
		profile.setFeatherStrength((int) featherSpinner.getValue());
		profile.setDepthBias((int) depthBiasSpinner.getValue());
		profile.setOffsetX((int) offsetXSpinner.getValue());
		profile.setOffsetY((int) offsetYSpinner.getValue());
		profile.setOffsetZ((int) offsetZSpinner.getValue());
		profile.setItemIds(parseIds(itemFilterField.getText()));
		profile.setAnimationIds(parseIds(animFilterField.getText()));
		profile.setAnimFrames(animFramesField.getText().trim());
		callbacks.saveProfile(selectedProfileKey, profile);
	}

	private static Set<Integer> parseIds(String text)
	{
		Set<Integer> ids = new HashSet<>();
		for (String token : text.split("[,\\s]+"))
		{
			try
			{
				if (!token.isEmpty())
				{
					ids.add(Integer.parseInt(token));
				}
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return ids;
	}

	/**
	 * Swap in a new snapshot and fresh profile data. Must be called on the
	 * Swing EDT.
	 */
	void setSnapshot(ModelSnapshot snapshot, Set<Integer> selectedVertices,
		Map<String, List<ProfileEntry>> profilesBySignature, List<String> wornItems,
		List<ProfileEntry> projectileEntries, List<int[]> recentProjectiles)
	{
		this.snapshot = snapshot;
		this.profilesBySignature = profilesBySignature;
		this.projectileEntries = projectileEntries;
		this.recentProjectiles = recentProjectiles;
		appliedPieceIndex = Integer.MIN_VALUE;
		viewport.setSnapshot(snapshot);
		viewport.setSelectedVertices(selectedVertices);

		wornItemsCombo.setModel(new DefaultComboBoxModel<>(wornItems.toArray(new String[0])));

		// Editing a projectile profile from the sidebar lands in its mode
		if (pendingSelection != null)
		{
			boolean pendingIsProjectile = false;
			for (ProfileEntry entry : projectileEntries)
			{
				if (entry.key.equals(pendingSelection))
				{
					pendingIsProjectile = true;
					break;
				}
			}
			setMode(pendingIsProjectile);
		}
		rebuildRows();
	}

	private void setMode(boolean projectile)
	{
		if (projectileMode == projectile)
		{
			return;
		}
		updatingMode = true;
		projectileMode = projectile;
		modeSelector.setSelectedIndex(projectile ? 1 : 0);
		projectileAddPanel.setVisible(projectile);
		updatingMode = false;
	}

	/**
	 * Rebuild the rows with fresh profile data, e.g. after a vertex click
	 * created a profile. Keeps the camera. EDT only.
	 */
	void refreshRows(Map<String, List<ProfileEntry>> profilesBySignature, List<ProfileEntry> projectileEntries)
	{
		this.profilesBySignature = profilesBySignature;
		this.projectileEntries = projectileEntries;
		if (snapshot != null)
		{
			rebuildRows();
		}
	}

	private void rebuildRows()
	{
		rebuildingRows = true;
		rowListModel.clear();
		rows.clear();
		int selectRow = 0;

		if (!projectileMode)
		{
			rowListModel.addElement("All pieces (" + snapshot.getVertexCount() + "v)");
			rows.add(new Row(-1, null));

			int i = 0;
			Set<String> presentSignatures = new HashSet<>();
			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				presentSignatures.add(piece.getSignature());
				String counts = " (" + piece.getVertices().length + "v, " + piece.getFaces().length + "f)";
				List<ProfileEntry> entries = profilesBySignature.get(piece.getSignature());
				if (entries == null || entries.isEmpty())
				{
					rowListModel.addElement("Piece " + (i + 1) + counts);
					rows.add(new Row(i, null));
				}
				else
				{
					for (ProfileEntry entry : entries)
					{
						rowListModel.addElement(entry.name + (entry.filtered ? " [item-gated]" : "") + counts);
						rows.add(new Row(i, entry.key));
						if (entry.key.equals(pendingSelection))
						{
							selectRow = rows.size() - 1;
						}
					}
				}
				i++;
			}

			// Profiles whose piece isn't on this model: selectable so their
			// style can still be edited, and clicking a vertex re-attaches them
			for (Map.Entry<String, List<ProfileEntry>> mapEntry : profilesBySignature.entrySet())
			{
				if (presentSignatures.contains(mapEntry.getKey()))
				{
					continue;
				}
				for (ProfileEntry entry : mapEntry.getValue())
				{
					rowListModel.addElement(entry.name + " (not on model)");
					rows.add(new Row(-1, entry.key));
					if (entry.key.equals(pendingSelection))
					{
						selectRow = rows.size() - 1;
					}
				}
			}
		}
		else
		{
			for (ProfileEntry entry : projectileEntries)
			{
				rowListModel.addElement(entry.name + (entry.filtered ? " [item-gated]" : ""));
				rows.add(new Row(-1, entry.key));
				if (entry.key.equals(pendingSelection))
				{
					selectRow = rows.size() - 1;
				}
			}
			for (int[] recent : recentProjectiles)
			{
				rowListModel.addElement("seen: " + recent[0] + "  (" + recent[1] + "x, " + recent[2] + "s ago)");
				rows.add(new Row(-1, null, recent[0]));
			}
			if (rows.isEmpty())
			{
				rowListModel.addElement("No projectiles seen yet - fire something nearby, then Refresh");
				rows.add(new Row(-1, null));
			}
		}

		pendingSelection = null;
		rebuildingRows = false;
		rowList.setSelectedIndex(selectRow);
	}

	/**
	 * Select the row of this profile when the next snapshot loads,
	 * e.g. when editing a saved profile from the sidebar. EDT only.
	 */
	void selectProfileOnNextSnapshot(String profileKey)
	{
		pendingSelection = profileKey;
	}

	/**
	 * Update the highlighted emitter vertices. Must be called on the Swing EDT.
	 */
	void setSelectedVertices(Set<Integer> selectedVertices)
	{
		viewport.setSelectedVertices(selectedVertices);
	}
}
