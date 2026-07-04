package dev.cnnnr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
	private final Consumer<String> onDeleteProfile;
	private final Consumer<String> onRenameProfile;
	private final Consumer<String> onEditProfile;
	private final JPanel profileList = new JPanel();
	private final IconTextField searchBar = new IconTextField();

	private Category category = Category.ALL;
	private Map<String, EmitterProfile> profiles = Map.of();
	private Set<String> presentSignatures = Set.of();

	ParticlesPanel(boolean developerMode, Runnable openViewer, BiConsumer<String, Boolean> onToggleProfile,
		Consumer<Boolean> onToggleAll, Consumer<String> onDeleteProfile, Consumer<String> onRenameProfile,
		Consumer<String> onEditProfile)
	{
		this.developerMode = developerMode;
		this.onToggleProfile = onToggleProfile;
		this.onDeleteProfile = onDeleteProfile;
		this.onRenameProfile = onRenameProfile;
		this.onEditProfile = onEditProfile;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		profileList.setLayout(new BoxLayout(profileList, BoxLayout.Y_AXIS));

		JButton enableAll = new JButton("Enable all");
		enableAll.setToolTipText("Turn on every preset");
		enableAll.addActionListener(e -> onToggleAll.accept(true));

		JButton disableAll = new JButton("Disable all");
		disableAll.setToolTipText("Turn off every preset");
		disableAll.addActionListener(e -> onToggleAll.accept(false));

		JButton support = new JButton("Support",
			new ImageIcon(ImageUtil.loadImageResource(ParticlesPlugin.class, "/support.png")));
		support.setToolTipText("Thank you! <3");
		support.addActionListener(e -> LinkBrowser.browse("https://buymeacoffee.com/cnnnr"));

		JPanel buttons = new JPanel(new GridLayout(2, 2, 4, 4));
		buttons.add(enableAll);
		buttons.add(disableAll);
		buttons.add(support);
		if (developerMode)
		{
			JButton open = new JButton("Vertex picker");
			open.addActionListener(e -> openViewer.run());
			buttons.add(open);
		}
		else
		{
			buttons.add(new JLabel());
		}

		JPanel controls = new JPanel(new GridLayout(0, 1, 0, 6));
		controls.add(buttons);

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
		controls.add(searchBar);

		MaterialTabGroup tabGroup = new MaterialTabGroup();
		tabGroup.setLayout(new GridLayout(1, 0, 4, 0));
		for (Category value : Category.values())
		{
			MaterialTab tab = new MaterialTab(value.label, tabGroup, new JPanel());
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
		controls.add(tabGroup);

		JPanel north = new JPanel(new BorderLayout(0, 8));
		north.add(controls, BorderLayout.NORTH);
		add(north, BorderLayout.NORTH);
		add(profileList, BorderLayout.CENTER);
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
		boolean worn = presentSignatures.contains(profile.getSignature());
		String text = profile.getName()
			+ (profile.isProjectileTarget() ? " [proj " + profile.getProjectileId() + "]" : "");

		JCheckBox toggle = new JCheckBox(text, profile.isEnabled());
		toggle.setToolTipText(worn
			? "This profile's piece is part of the model you are wearing now"
			: "This profile's piece is not on your current model");
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
