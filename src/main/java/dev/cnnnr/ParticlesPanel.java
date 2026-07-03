package dev.cnnnr;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Insets;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel: opens the vertex picker and lists saved emitter pieces
 * (identified by mesh topology, so they survive gear changes), with
 * enable/disable toggles, rename and delete.
 */
class ParticlesPanel extends PluginPanel
{
	private final BiConsumer<String, Boolean> onToggleProfile;
	private final Consumer<String> onDeleteProfile;
	private final Consumer<String> onRenameProfile;
	private final Consumer<String> onEditProfile;
	private final JPanel profileList = new JPanel();

	ParticlesPanel(Runnable openViewer, BiConsumer<String, Boolean> onToggleProfile,
		Consumer<String> onDeleteProfile, Consumer<String> onRenameProfile, Consumer<String> onEditProfile)
	{
		this.onToggleProfile = onToggleProfile;
		this.onDeleteProfile = onDeleteProfile;
		this.onRenameProfile = onRenameProfile;
		this.onEditProfile = onEditProfile;

		setLayout(new BorderLayout(0, 10));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JButton open = new JButton("Open vertex picker");
		open.addActionListener(e -> openViewer.run());

		JLabel hint = new JLabel("<html>Pick a mesh piece from the list, then click vertices "
			+ "to toggle particle emission from them. Emitters are saved per piece and follow "
			+ "it across gear changes.</html>");

		profileList.setLayout(new BoxLayout(profileList, BoxLayout.Y_AXIS));

		JPanel top = new JPanel(new BorderLayout(0, 10));
		top.add(open, BorderLayout.NORTH);
		top.add(hint, BorderLayout.CENTER);
		add(top, BorderLayout.NORTH);
		add(profileList, BorderLayout.CENTER);
	}

	/**
	 * Rebuild the emitter piece list. Must be called on the Swing EDT.
	 *
	 * @param presentSignatures signatures of pieces on the currently worn model
	 */
	void rebuild(Map<String, EmitterProfile> profiles, Set<String> presentSignatures)
	{
		profileList.removeAll();

		if (!profiles.isEmpty())
		{
			JLabel header = new JLabel("Emitter pieces");
			header.setAlignmentX(Component.LEFT_ALIGNMENT);
			profileList.add(header);
			profileList.add(Box.createVerticalStrut(6));
		}

		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			String profileKey = entry.getKey();
			EmitterProfile profile = entry.getValue();

			boolean worn = presentSignatures.contains(profile.getSignature());
			String text = profile.getName()
				+ (profile.isProjectileTarget() ? " [proj " + profile.getProjectileId() + "]" : "")
				+ (profile.getItemIds().isEmpty() ? "" : "*");

			JCheckBox toggle = new JCheckBox(text, profile.isEnabled());
			toggle.setToolTipText(worn
				? "This profile's piece is part of the model you are wearing now"
				: "This profile's piece is not on your current model");
			toggle.addActionListener(e -> onToggleProfile.accept(profileKey, toggle.isSelected()));

			JButton edit = new JButton("e");
			edit.setMargin(new Insets(0, 4, 0, 4));
			edit.setToolTipText(worn
				? "Edit this profile's vertices and style in the vertex picker"
				: "Opens the vertex picker; wear the piece to see and edit it");
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

			JPanel row = new JPanel(new BorderLayout());
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(toggle, BorderLayout.CENTER);
			row.add(buttons, BorderLayout.EAST);
			profileList.add(row);
		}

		profileList.revalidate();
		profileList.repaint();
	}
}
