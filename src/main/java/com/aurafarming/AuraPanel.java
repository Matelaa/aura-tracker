package com.aurafarming;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Read-only sidebar panel. Only ever reads from {@link AuraSessionTracker} and
 * {@link AuraScoreCalculator} — it never mutates tracking state itself.
 * <p>
 * Stats are boxed "cards" (own background, border, padding) rather than bare labels
 * sitting directly on the panel background — modeled after how core RuneLite panels
 * like {@code XpPanel} separate content from the base panel visually, instead of
 * everything blending into one flat gray field.
 * <p>
 * The outer layout is {@link BorderLayout} with content anchored at
 * {@link BorderLayout#NORTH}, not a bare top-level {@link BoxLayout} — the same trick
 * {@code XpPanel} uses so the stat cards keep their natural height instead of stretching
 * to fill leftover vertical space.
 * <p>
 * All text stays at {@link FontManager}'s native size (16px) — checked directly against
 * {@code XpPanel}/{@code XpInfoBox}/{@code PartyStatusOverlay}/{@code DevToolsOverlay} in
 * RuneLite core: none of them ever call {@code deriveFont} with a size above 16. An
 * earlier version of this panel scaled the headline Aura number up to 22-26px, which
 * rendered as illegible blocky glyphs — not a one-off font bug, but this codebase's fonts
 * simply not being designed to scale past their native size. Emphasis here comes from
 * color and bold weight only, the same way {@code XpInfoBox#htmlLabel} highlights numbers
 * within otherwise plain-sized text.
 */
public class AuraPanel extends PluginPanel
{
	private static final Color CARD_BORDER_COLOR = ColorScheme.DARK_GRAY_HOVER_COLOR;

	private final AuraScoreCalculator scoreCalculator;

	private final JLabel auraValueLabel = new JLabel();
	private final JLabel totalTimeValueLabel = new JLabel();
	private final JLabel sessionTimeValueLabel = new JLabel();
	private final JLabel stateValueLabel = new JLabel();

	/**
	 * Every card built by {@link #statCard}, kept around so the constructor can freeze
	 * their height <em>after</em> the first real {@link #update} call rather than inside
	 * {@code statCard} itself. Measuring height any earlier was the actual bug behind a
	 * string of "text looks bottom-aligned" reports: at construction time the value
	 * labels are still empty (text arrives later via {@code update}), and this look and
	 * feel (RuneLite's FlatLaf-based {@code RuneLiteLAF}) reports a shorter preferred
	 * height for an empty label than for one with real text. Freezing height from that
	 * too-short empty-state measurement left every card a few pixels short of what the
	 * real text needed, and since children stack top-down, the shortfall always ate into
	 * the bottom margin first — confirmed by literally rendering the panel to a PNG and
	 * measuring pixel rows (top gap 13px, bottom gap 1px) rather than guessing again.
	 */
	private final List<JPanel> statCards = new ArrayList<>();

	/**
	 * @param onOpenLeaderboardClicked invoked when the user clicks "Open Leaderboard" —
	 *                                 most players never see the leaderboard link
	 *                                 otherwise, since it only ever appears once, in the
	 *                                 one-time sync disclosure chat message.
	 * @param onUpdateModelClicked invoked when the user clicks the "Update 3D Model"
	 *                             button — a deliberate, user-initiated action (same UX
	 *                             as RuneProfile's own "Update Player Model" button),
	 *                             never triggered automatically. The panel only relays
	 *                             the click; it has no opinion on what happens after,
	 *                             keeping it purely presentational like the rest of this
	 *                             class.
	 */
	public AuraPanel(AuraScoreCalculator scoreCalculator, Runnable onOpenLeaderboardClicked, Runnable onUpdateModelClicked)
	{
		super(false);
		this.scoreCalculator = scoreCalculator;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(new EmptyBorder(10, 10, 10, 10));

		content.add(buildTitle());
		content.add(Box.createRigidArea(new Dimension(0, 18)));
		content.add(addCard(statCard("AURA", auraValueLabel, true)));
		content.add(verticalGap());
		content.add(addCard(statCard("TIME AT THE GE", totalTimeValueLabel, false)));
		content.add(verticalGap());
		content.add(addCard(statCard("CURRENT SESSION", sessionTimeValueLabel, false)));
		content.add(verticalGap());
		content.add(addCard(statCard("STATUS", stateValueLabel, false)));
		content.add(Box.createRigidArea(new Dimension(0, 18)));
		content.add(buildButton("Open Leaderboard", onOpenLeaderboardClicked));
		content.add(verticalGap());
		content.add(buildButton("Update 3D Model", onUpdateModelClicked));

		add(content, BorderLayout.NORTH);

		update(new AuraSession(), AuraState.LOGGED_OUT);

		// Only now do the value labels hold real text ("0", "0m", "Offline") — freeze
		// each card's height off of *this* preferred size, not the empty-label one from
		// construction. See the statCards field doc for why this ordering matters.
		for (JPanel card : statCards)
		{
			card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		}
	}

	private JPanel addCard(JPanel card)
	{
		statCards.add(card);
		return card;
	}

	private static Component verticalGap()
	{
		return Box.createRigidArea(new Dimension(0, 12));
	}

	private static JLabel buildTitle()
	{
		// Left-aligned, not centered — everything below it (cards, button) is
		// left-aligned too, and a centered title against left-aligned content read as
		// inconsistent/misaligned. One shared left margin for the whole panel instead.
		JLabel title = new JLabel("AURA TRACKER");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setHorizontalAlignment(SwingConstants.LEFT);
		title.setAlignmentX(LEFT_ALIGNMENT);
		return title;
	}

	/**
	 * @param emphasized the Aura card gets the brand color and the bold weight — it's the
	 *                   headline number, everything else is supporting detail. Same point
	 *                   size as every other value, matching the rest of this codebase's
	 *                   fonts (see the class doc for why).
	 */
	private static JPanel statCard(String label, JLabel valueLabel, boolean emphasized)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new CompoundBorder(
			new LineBorder(CARD_BORDER_COLOR, 1, true),
			new EmptyBorder(12, 16, 12, 16)));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JLabel labelText = new JLabel(label);
		labelText.setFont(FontManager.getRunescapeSmallFont());
		labelText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		labelText.setHorizontalAlignment(SwingConstants.LEFT);
		labelText.setAlignmentX(LEFT_ALIGNMENT);

		valueLabel.setForeground(emphasized ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		valueLabel.setFont(emphasized ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		valueLabel.setHorizontalAlignment(SwingConstants.LEFT);
		valueLabel.setAlignmentX(LEFT_ALIGNMENT);

		// Glue above and below the label+value pair, not just insets, so the pair is
		// forced to sit at the card's true vertical center regardless of how tall the
		// card ends up being — insets alone assume the label/value block's own preferred
		// height already accounts for all the vertical space the card allocates, which
		// isn't reliable with this pixel font's own inflated internal line metrics.
		card.add(Box.createVerticalGlue());
		card.add(labelText);
		card.add(Box.createRigidArea(new Dimension(0, 6)));
		card.add(valueLabel);
		card.add(Box.createVerticalGlue());

		// Max height is frozen later, in the constructor, once every value label holds
		// its real text — not here. See the statCards field doc for why. (Max width is
		// still left unbounded here on purpose: that's what lets the constructor's later
		// setMaximumSize call — width MAX_VALUE, height pinned — stretch the card to the
		// panel's full width instead of shrinking to its own content width, the standard
		// BoxLayout "fill" workaround.)

		return card;
	}

	private static JButton buildButton(String label, Runnable onClick)
	{
		JButton button = new JButton(label);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setForeground(Color.WHITE);
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height + 8));
		button.addActionListener(e -> onClick.run());
		return button;
	}

	/**
	 * Refreshes the displayed values. Intended to be called on a fixed-rate timer
	 * (e.g. once per second), never once per {@code GameTick} — the panel does not need
	 * tick-level precision and redrawing on every tick would be wasted work.
	 */
	public void update(AuraSession session, AuraState state)
	{
		long auraPoints = scoreCalculator.toAuraPoints(session.getEligibleAuraDurationSeconds());
		auraValueLabel.setText(formatNumber(auraPoints));

		totalTimeValueLabel.setText(formatDuration(session.getEligibleAuraDurationSeconds()));

		sessionTimeValueLabel.setText(formatDuration(session.getCurrentSessionEligibleSeconds()));

		stateValueLabel.setText(describeState(state));
	}

	private static String describeState(AuraState state)
	{
		switch (state)
		{
			case EARNING_AURA:
				return "Farming Aura";
			case WAITING_FOR_IDLE:
				return "Waiting for you to stop";
			case IN_GE:
			case IN_GAME:
				return "Walk to the GE to start";
			case PAUSED:
				return "Paused";
			case LOGGED_OUT:
			default:
				return "Offline";
		}
	}

	private static String formatNumber(long value)
	{
		return String.format(Locale.US, "%,d", value);
	}

	private static String formatDuration(long totalSeconds)
	{
		if (totalSeconds < 0)
		{
			totalSeconds = 0;
		}

		long days = totalSeconds / 86400;
		long hours = (totalSeconds % 86400) / 3600;
		long minutes = (totalSeconds % 3600) / 60;

		if (days > 0)
		{
			return String.format("%dd %02dh %02dm", days, hours, minutes);
		}
		if (hours > 0)
		{
			return String.format("%dh %02dm", hours, minutes);
		}
		return String.format("%dm", minutes);
	}
}
