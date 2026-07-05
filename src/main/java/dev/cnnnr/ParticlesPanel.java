package dev.cnnnr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/**
 * Sidebar panel: lists saved emitter profiles with search and category
 * filters. Authoring controls (vertex picker, edit/rename/delete) only
 * appear in developer mode; end users see read-only presets with
 * enable/disable toggles.
 */
class ParticlesPanel extends PluginPanel
{
	enum Category
	{
		ALL("All", false),
		PLAYER("Player", false),
		PROJECTILE("Proj", true),
		WORLD("World", false),
		NPC("NPC", true),
		GRAPHIC("Gfx", true),
		ANIMATED("Anim", true);

		private final String label;
		/**
		 * Work-in-progress category: outside developer mode its tab shows a
		 * Coming soon placeholder, its profiles vanish from the sidebar, and
		 * they are force-disabled at emission time. Flip to false when the
		 * category is ready to ship.
		 */
		private final boolean wip;

		Category(String label, boolean wip)
		{
			this.label = label;
			this.wip = wip;
		}

		boolean matches(EmitterProfile profile)
		{
			switch (this)
			{
				case PLAYER:
					return EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType());
				case PROJECTILE:
					return profile.isProjectileTarget();
				case WORLD:
					return profile.isObjectTarget();
				case NPC:
					return profile.isNpcTarget();
				case GRAPHIC:
					return profile.isGraphicTarget();
				case ANIMATED:
					return !profile.getAnimationIds().isEmpty();
				default:
					return true;
			}
		}

		/**
		 * True when the profile falls into any WIP category and should be
		 * hidden and force-disabled outside developer mode.
		 */
		static boolean isWip(EmitterProfile profile)
		{
			for (Category value : values())
			{
				if (value.wip && value.matches(profile))
				{
					return true;
				}
			}
			return false;
		}
	}

	private final boolean developerMode;
	private final BiConsumer<String, Boolean> onToggleProfile;
	private final BiConsumer<String, EmitterProfile> onPasteStyle;
	private final Consumer<String> onDeleteProfile;
	private final Consumer<String> onRenameProfile;
	private final Consumer<String> onEditProfile;
	private final JPanel profileList = new JPanel();
	private final IconTextField searchBar = new IconTextField();

	private Category category = Category.ALL;
	private Map<String, EmitterProfile> profiles = Map.of();
	private Set<String> presentSignatures = Set.of();
	/**
	 * Style clipboard for the right-click copy/paste flow; a detached copy
	 * so later edits or deletion of the source don't change what pastes.
	 */
	@Nullable
	private EmitterProfile copiedStyle;

	ParticlesPanel(boolean developerMode, Runnable openViewer, BiConsumer<String, Boolean> onToggleProfile,
		BiConsumer<Set<String>, Boolean> onToggleMany, BiConsumer<String, EmitterProfile> onPasteStyle,
		Consumer<String> onDeleteProfile, Consumer<String> onRenameProfile, Consumer<String> onEditProfile)
	{
		this.developerMode = developerMode;
		this.onToggleProfile = onToggleProfile;
		this.onPasteStyle = onPasteStyle;
		this.onDeleteProfile = onDeleteProfile;
		this.onRenameProfile = onRenameProfile;
		this.onEditProfile = onEditProfile;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		profileList.setLayout(new BoxLayout(profileList, BoxLayout.Y_AXIS));

		JButton support = new JButton(new ImageIcon(ImageUtil.loadImageResource(ParticlesPlugin.class, "/support.png")));
		support.setToolTipText("Thank you! <3");
		support.addActionListener(e -> LinkBrowser.browse("https://buymeacoffee.com/cnnnr"));

		JButton enableAll = new JButton("Enable all");
		enableAll.setToolTipText("Turn on every preset in the selected tab");
		enableAll.addActionListener(e -> onToggleMany.accept(filteredKeys(), true));

		JButton disableAll = new JButton("Disable all");
		disableAll.setToolTipText("Turn off every preset in the selected tab");
		disableAll.addActionListener(e -> onToggleMany.accept(filteredKeys(), false));

		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		// IconTextField paints no background until its focus/hover listeners
		// first fire; give it one up front like the core plugin panels do
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				render();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				render();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				render();
			}
		});
		searchBar.addClearListener(this::render);

		// Search takes three quarters of the top row, the support heart the rest
		JPanel topRow = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.BOTH;
		gbc.weighty = 1;
		gbc.weightx = 0.75;
		topRow.add(searchBar, gbc);
		gbc.weightx = 0.25;
		gbc.insets = new Insets(0, 4, 0, 0);
		topRow.add(support, gbc);
		topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(topRow);
		controls.add(Box.createVerticalStrut(6));

		if (developerMode)
		{
			JButton open = new JButton("Vertex picker");
			open.addActionListener(e -> openViewer.run());
			JPanel openRow = new JPanel(new BorderLayout());
			openRow.add(open, BorderLayout.CENTER);
			openRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			controls.add(openRow);
			controls.add(Box.createVerticalStrut(6));
		}

		MaterialTabGroup tabGroup = new MaterialTabGroup();
		// Three equal tabs per row, wrapping like the button grid above
		tabGroup.setLayout(new GridLayout(0, 3, 4, 4));
		for (Category value : Category.values())
		{
			MaterialTab tab = new MaterialTab(value.label, tabGroup, new JPanel());
			tab.setHorizontalAlignment(SwingConstants.CENTER);
			tab.setOnSelectEvent(() ->
			{
				category = value;
				render();
				return true;
			});
			tabGroup.addTab(tab);
			if (value == Category.ALL)
			{
				tabGroup.select(tab);
			}
		}
		tabGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(tabGroup);
		controls.add(Box.createVerticalStrut(6));

		JPanel bulkRow = new JPanel(new GridLayout(1, 2, 4, 0));
		bulkRow.add(enableAll);
		bulkRow.add(disableAll);
		bulkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(bulkRow);

		JPanel north = new JPanel(new BorderLayout(0, 8));
		north.add(controls, BorderLayout.NORTH);
		add(north, BorderLayout.NORTH);
		add(profileList, BorderLayout.CENTER);
	}

	/**
	 * Keys of the profiles the currently-selected tab shows (ignoring the
	 * search text), for the bulk enable and disable buttons.
	 */
	private Set<String> filteredKeys()
	{
		Set<String> keys = new HashSet<>();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (category.matches(profile) && (developerMode || !Category.isWip(profile)))
			{
				keys.add(entry.getKey());
			}
		}
		return keys;
	}

	/**
	 * Update the profile data and re-render. Must be called on the Swing EDT.
	 *
	 * @param presentSignatures signatures of pieces on the currently worn model
	 */
	void rebuild(Map<String, EmitterProfile> profiles, Set<String> presentSignatures)
	{
		this.profiles = profiles;
		this.presentSignatures = presentSignatures;
		render();
	}

	/**
	 * Re-render the list through the current category tab and search text.
	 */
	private void render()
	{
		profileList.removeAll();

		if (!developerMode && category.wip)
		{
			JLabel comingSoon = new JLabel("Coming soon!");
			comingSoon.setForeground(Color.GRAY);
			comingSoon.setAlignmentX(Component.LEFT_ALIGNMENT);
			profileList.add(comingSoon);
			profileList.revalidate();
			profileList.repaint();
			return;
		}

		String query = searchBar.getText() == null ? "" : searchBar.getText().trim().toLowerCase();
		List<Map.Entry<String, EmitterProfile>> entries = new ArrayList<>(profiles.entrySet());
		entries.removeIf(entry -> !category.matches(entry.getValue())
			|| (!developerMode && Category.isWip(entry.getValue()))
			|| !matchesSearch(entry.getValue(), query));
		entries.sort(Comparator.comparing(entry ->
			entry.getValue().getName() == null ? "" : entry.getValue().getName().toLowerCase()));

		if (!entries.isEmpty())
		{
			JLabel header = new JLabel(entries.size() + (entries.size() == 1 ? " profile" : " profiles"));
			header.setAlignmentX(Component.LEFT_ALIGNMENT);
			profileList.add(header);
			profileList.add(Box.createVerticalStrut(6));
		}

		for (Map.Entry<String, EmitterProfile> entry : entries)
		{
			profileList.add(buildRow(entry.getKey(), entry.getValue()));
		}

		profileList.revalidate();
		profileList.repaint();
	}

	private JPanel buildRow(String profileKey, EmitterProfile profile)
	{
		// Signature presence only means worn for player profiles; trivial
		// fragment signatures collide across unrelated models
		boolean worn = EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
			&& presentSignatures.contains(profile.getSignature());
		String text = profile.getName()
			+ (profile.isProjectileTarget() ? " [proj " + profile.getProjectileId() + "]" : "")
			+ (profile.isObjectTarget() ? " [obj " + profile.getObjectId() + "]" : "")
			+ (profile.isNpcTarget() ? " [npc " + profile.getNpcId() + "]" : "")
			+ (profile.isGraphicTarget() ? " [gfx " + profile.getGraphicId() + "]" : "");

		JCheckBox toggle = new JCheckBox(text, profile.isEnabled());
		// Rows truncate in the narrow sidebar; the tooltip carries the full
		// name and tag, plus the worn state where that concept applies
		toggle.setToolTipText(EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
			? "<html>" + text + "<br>" + (worn
				? "On the model you are wearing now"
				: "Not on your current model") + "</html>"
			: text);
		toggle.addActionListener(e -> onToggleProfile.accept(profileKey, toggle.isSelected()));

		JPanel row = new JPanel(new BorderLayout());
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(toggle, BorderLayout.CENTER);

		if (developerMode)
		{
			JButton edit = new JButton("e");
			edit.setMargin(new Insets(0, 4, 0, 4));
			edit.setToolTipText("Edit this profile in the vertex picker");
			edit.addActionListener(e -> onEditProfile.accept(profileKey));

			JButton rename = new JButton("~");
			rename.setMargin(new Insets(0, 4, 0, 4));
			rename.setToolTipText("Rename this profile");
			rename.addActionListener(e -> onRenameProfile.accept(profileKey));

			JButton delete = new JButton("x");
			delete.setMargin(new Insets(0, 4, 0, 4));
			delete.setToolTipText("Delete this profile");
			delete.addActionListener(e -> onDeleteProfile.accept(profileKey));

			JPanel buttons = new JPanel();
			buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
			buttons.add(edit);
			buttons.add(rename);
			buttons.add(delete);
			row.add(buttons, BorderLayout.EAST);

			JPopupMenu menu = new JPopupMenu();
			JMenuItem copyStyle = new JMenuItem("Copy style");
			copyStyle.addActionListener(e -> copiedStyle = profile.copy());
			JMenuItem pasteStyle = new JMenuItem("Paste style");
			pasteStyle.addActionListener(e ->
			{
				if (copiedStyle != null)
				{
					onPasteStyle.accept(profileKey, copiedStyle);
				}
			});
			menu.add(copyStyle);
			menu.add(pasteStyle);
			menu.addPopupMenuListener(new PopupMenuListener()
			{
				@Override
				public void popupMenuWillBecomeVisible(PopupMenuEvent e)
				{
					pasteStyle.setEnabled(copiedStyle != null);
				}

				@Override
				public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
				{
				}

				@Override
				public void popupMenuCanceled(PopupMenuEvent e)
				{
				}
			});
			row.setComponentPopupMenu(menu);
			toggle.setComponentPopupMenu(menu);
		}

		return row;
	}

	private static boolean matchesSearch(EmitterProfile profile, String query)
	{
		if (query.isEmpty())
		{
			return true;
		}
		if (profile.getName() != null && profile.getName().toLowerCase().contains(query))
		{
			return true;
		}
		if (profile.isProjectileTarget() && String.valueOf(profile.getProjectileId()).contains(query))
		{
			return true;
		}
		if (profile.isObjectTarget() && String.valueOf(profile.getObjectId()).contains(query))
		{
			return true;
		}
		if (profile.isNpcTarget() && String.valueOf(profile.getNpcId()).contains(query))
		{
			return true;
		}
		if (profile.isGraphicTarget() && String.valueOf(profile.getGraphicId()).contains(query))
		{
			return true;
		}
		for (int id : profile.getItemIds())
		{
			if (String.valueOf(id).contains(query))
			{
				return true;
			}
		}
		for (int id : profile.getAnimationIds())
		{
			if (String.valueOf(id).contains(query))
			{
				return true;
			}
		}
		return false;
	}
}
