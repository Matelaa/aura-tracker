package com.aurafarming;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("aurafarming")
public interface AuraConfig extends Config
{
	@ConfigSection(
		name = "Online leaderboard",
		description = "On by default, same as this project's own RuneProfile precedent — turn off below anytime.",
		position = 0
	)
	String onlineSection = "onlineSection";

	/**
	 * On by default (opt-out), matching how RuneProfile — the closest real precedent for
	 * a RuneLite plugin syncing a client-calculated value to its own site — behaves: it
	 * creates and updates your profile automatically, no confirmation dialog gating it.
	 */
	@ConfigItem(
		keyName = "onlineSyncEnabled",
		name = "Enable online leaderboard sync",
		description = "Puts your total Aura on the community leaderboard so you can prove who farms hardest. "
			+ "Unofficial fan ranking, on by default, turn off here anytime.",
		section = onlineSection,
		position = 0
	)
	default boolean onlineSyncEnabled()
	{
		return true;
	}
}
