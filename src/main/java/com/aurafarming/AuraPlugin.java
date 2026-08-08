package com.aurafarming;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import com.aurafarming.modelexporter.GlbExporter;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Aura Tracker — a purely cosmetic tracker for time spent standing still in the Grand
 * Exchange.
 * <p>
 * Local tracking (position reading, scoring, local persistence) is always on and never
 * touches the network — see {@link LocalAuraRepository}, {@link AuraSessionTracker}.
 * Online leaderboard sync ({@link AuraApiClient}) is the one part of this plugin that
 * does make network calls. It is opt-out: on by default, matching how RuneProfile (the
 * closest real precedent among existing RuneLite plugins that sync a client-calculated
 * value) behaves, rather than gated behind a confirmation dialog. The disclosure Plugin
 * Hub rule 1.3 requires is satisfied by {@link AuraConfig}'s config description plus a
 * one-time, non-blocking chat notice the first time a sync actually sends (see
 * {@link #doSyncOnline()}). No other player's data is ever read by any part of this
 * plugin.
 */
@Slf4j
@PluginDescriptor(
	name = "Aura Tracker",
	description = "Farm Aura by standing still at the Grand Exchange — brag about it on the optional community leaderboard.",
	tags = {"aura", "grand exchange", "idle", "tracker", "fun"}
)
public class AuraPlugin extends Plugin
{
	/**
	 * Safety-net interval only — logout is the primary sync trigger (see
	 * {@link #onGameStateChanged}), matching how the official OSRS Hiscores and the
	 * ecosystem's own WiseOldMan/XP Updater plugin work: they update on logout, not on a
	 * timer. A periodic fallback still exists for sessions long enough that waiting for
	 * logout would leave the leaderboard stale for hours.
	 */
	private static final int ONLINE_SYNC_INTERVAL_MINUTES = 15;

	/**
	 * Fixed, not user-configurable. Was previously an exposed config option; removed
	 * deliberately to keep the config panel to only the one setting that actually needs
	 * to be user-facing (online sync) — per the project's own "don't add settings unless
	 * genuinely useful" principle.
	 */
	private static final int IDLE_DELAY_SECONDS = 5;

	/**
	 * Hidden config key (no {@code @ConfigItem}, never shown in the settings panel) that
	 * tracks whether the one-time sync disclosure chat message has already been shown —
	 * see {@link #doSyncOnline()}. Deliberately never reset by toggling sync off and back
	 * on; once shown, it stays shown, so re-enabling never nags the user a second time.
	 */
	private static final String DISCLOSURE_SHOWN_KEY = "syncDisclosureShown";

	@Inject
	private Client client;

	@Inject
	private AuraConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private LocalAuraRepository repository;

	@Inject
	private AuraApiClient apiClient;

	@Inject
	private Gson gson;

	private final GrandExchangeArea grandExchangeArea = new GrandExchangeArea();
	private final AuraScoreCalculator scoreCalculator = new AuraScoreCalculator();

	private AuraSessionTracker tracker;
	private AuraPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> panelRefreshTask;
	private ScheduledFuture<?> autosaveTask;
	private ScheduledFuture<?> onlineSyncTask;

	/**
	 * Cached on the client thread every {@code GameTick} whenever the local player is
	 * resolvable (see {@link #onGameTick}), and read from other threads by
	 * {@link #syncOnline()}. This sidesteps two problems at once: {@code Client} methods
	 * like {@code getLocalPlayer()} are not safe to call from a background thread, and by
	 * the time a logout-triggered sync runs, {@code getLocalPlayer()} may already be null
	 * — the whole point of syncing on logout is to send the final state of a session that
	 * just ended, so it must not depend on reading the client fresh at that exact moment.
	 * {@code volatile} for cross-thread visibility without needing a lock for a single
	 * reference assignment.
	 */
	private volatile String lastKnownDisplayName;

	@Provides
	AuraConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AuraConfig.class);
	}

	@Override
	protected void startUp()
	{
		AuraSession loadedSession = repository.load();
		loadedSession.resetSession();
		tracker = new AuraSessionTracker(grandExchangeArea, new AuraTimingClock(), loadedSession);

		// The tracker always starts at LOGGED_OUT and otherwise only advances by reacting
		// to future GameStateChanged events. If the plugin is enabled while already
		// logged in (the common case — toggling a plugin on mid-session), no such event
		// will ever fire again, so the state machine would stay stuck at LOGGED_OUT
		// forever. Bootstrap it from the client's current state instead of assuming the
		// plugin always starts before login.
		tracker.onGameStateChanged(client.getGameState());

		panel = new AuraPanel(scoreCalculator, this::updateModelAsync);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "aura_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Aura Tracker")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		panelRefreshTask = executor.scheduleAtFixedRate(this::refreshPanel, 1, 1, TimeUnit.SECONDS);
		autosaveTask = executor.scheduleAtFixedRate(this::persist, 60, 60, TimeUnit.SECONDS);

		// On by default (see AuraConfig) — resumes/starts syncing on every plugin start
		// unless the user has turned it off.
		if (config.onlineSyncEnabled())
		{
			startOnlineSync();
		}
	}

	@Override
	protected void shutDown()
	{
		if (panelRefreshTask != null)
		{
			panelRefreshTask.cancel(true);
		}
		if (autosaveTask != null)
		{
			autosaveTask.cancel(true);
		}
		stopOnlineSync();

		persist();

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// Belt-and-braces flush in case the client is closed without a clean plugin
		// shutDown() call (e.g. force-quit).
		persist();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (tracker == null)
		{
			return;
		}

		net.runelite.api.Player localPlayer = client.getLocalPlayer();
		net.runelite.api.coords.WorldPoint location = localPlayer != null ? localPlayer.getWorldLocation() : null;

		if (localPlayer != null)
		{
			String name = localPlayer.getName();
			if (name != null && !name.isEmpty())
			{
				lastKnownDisplayName = name;
			}
		}

		tracker.onGameTick(location, IDLE_DELAY_SECONDS);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (tracker == null)
		{
			return;
		}

		GameState newState = event.getGameState();
		tracker.onGameStateChanged(newState);

		if (newState == GameState.LOGIN_SCREEN || newState == GameState.CONNECTION_LOST)
		{
			// Flush immediately on logout/disconnect rather than waiting for the next
			// autosave tick, so a quick logout never loses progress.
			persist();

			// Logout is the PRIMARY online sync trigger, not the periodic timer — matches
			// how the official OSRS Hiscores and WiseOldMan/XP Updater already behave in
			// this exact ecosystem (update on logout, not on a schedule). Only fires if
			// the user has actually opted in. Safe to call even though the local player
			// is no longer resolvable at this point — syncOnline() uses the cached
			// lastKnownDisplayName, not a fresh client read.
			if (config.onlineSyncEnabled())
			{
				syncOnline();
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"aurafarming".equals(event.getGroup()) || !"onlineSyncEnabled".equals(event.getKey()))
		{
			return;
		}

		if ("true".equals(event.getNewValue()))
		{
			startOnlineSync();
		}
		else
		{
			stopOnlineSync();
		}
	}

	private void startOnlineSync()
	{
		if (onlineSyncTask != null && !onlineSyncTask.isCancelled())
		{
			return;
		}

		onlineSyncTask = executor.scheduleAtFixedRate(
			this::syncOnline, 0, ONLINE_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES);
	}

	private void stopOnlineSync()
	{
		if (onlineSyncTask != null)
		{
			onlineSyncTask.cancel(false);
			onlineSyncTask = null;
		}
	}

	/**
	 * Called both by the periodic safety-net timer (background executor thread) and
	 * directly by {@link #onGameStateChanged} on logout (client thread) — must be safe
	 * from either. Never touches {@code Client} directly; uses {@link #lastKnownDisplayName}
	 * instead, which sidesteps both the thread-affinity requirement and the "player
	 * already logged out" problem.
	 * <p>
	 * Wrapped in try/catch as defense in depth: a {@code ScheduledExecutorService}
	 * silently and permanently stops running a periodic task the moment it throws an
	 * uncaught exception, with no log and no way to notice short of the sync simply never
	 * happening again — a failure mode this method must never be able to trigger,
	 * whatever future change might otherwise introduce one.
	 */
	private void syncOnline()
	{
		try
		{
			doSyncOnline();
		}
		catch (RuntimeException e)
		{
			log.warn("Aura Tracker: online sync tick failed unexpectedly", e);
		}
	}

	private void doSyncOnline()
	{
		if (tracker == null)
		{
			return;
		}

		String displayName = lastKnownDisplayName;
		if (displayName == null)
		{
			// Never successfully resolved the local player's name this session (e.g. a
			// sync fired before the very first GameTick) — nothing to send yet.
			return;
		}

		showSyncDisclosureOnce();

		AuraSession session = tracker.getSession();
		String deviceId = session.getOrCreateDeviceId();
		long eligibleSeconds = session.getEligibleAuraDurationSeconds();

		apiClient.syncAsync(deviceId, displayName, eligibleSeconds);
		executor.execute(this::persist);
	}

	/**
	 * Exports and uploads the player's current 3D model (equipment, colors, textures)
	 * for the profile page's model viewer — see {@link GlbExporter}. The only trigger is
	 * the panel's "Update 3D Model" button — deliberately never automatic, so exporting
	 * the character's appearance is always a decision the player actively makes. Runs
	 * entirely on the client thread via {@link ClientThread}, so
	 * {@code getLocalPlayer()}/{@code getModel()} are always safe to read fresh here.
	 */
	private void updateModelAsync()
	{
		if (!config.onlineSyncEnabled())
		{
			clientThread.invoke(() -> client.addChatMessage(ChatMessageType.CONSOLE, "",
				"Aura Tracker: enable online sync first (Config → Aura Tracker).", null));
			return;
		}

		clientThread.invoke(() ->
		{
			if (tracker == null)
			{
				return;
			}

			Player localPlayer = client.getLocalPlayer();
			Model model = localPlayer != null ? localPlayer.getModel() : null;
			if (model == null)
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"Aura Tracker: couldn't read your character right now, try again.", null);
				return;
			}

			byte[] glb;
			try
			{
				glb = GlbExporter.toBytes(client, model, "player", gson);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Aura Tracker: failed to export player model", e);
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"Aura Tracker: failed to export your 3D model.", null);
				return;
			}

			String deviceId = tracker.getSession().getOrCreateDeviceId();
			client.addChatMessage(ChatMessageType.CONSOLE, "", "Aura Tracker: updating your 3D model...", null);
			apiClient.syncModelAsync(deviceId, glb);
		});
	}

	/**
	 * Satisfies Plugin Hub rule 1.3's disclosure requirement with a one-time, unobtrusive
	 * chat message instead of a blocking confirmation dialog — sync is opt-out, so this
	 * informs rather than asks. {@code ChatMessageType.CONSOLE} matches how other plugins
	 * post non-intrusive system messages. Only ever shown once per install: the flag is
	 * never cleared by toggling sync off and back on.
	 */
	private void showSyncDisclosureOnce()
	{
		if ("true".equals(configManager.getConfiguration("aurafarming", DISCLOSURE_SHOWN_KEY)))
		{
			return;
		}
		configManager.setConfiguration("aurafarming", DISCLOSURE_SHOWN_KEY, true);

		String message = "Aura Tracker: your progress is being sent to "
			+ apiClient.baseUrlForDisplay() + " (community ranking, unofficial). "
			+ "To turn this off, see the plugin's settings.";

		// addChatMessage requires the client thread; doSyncOnline() runs on either the
		// background executor or the client thread depending on the caller (see its own
		// javadoc), so this can't assume it's already there.
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", message, null));
	}

	private void refreshPanel()
	{
		if (tracker == null || panel == null)
		{
			return;
		}

		panel.update(tracker.getSession(), tracker.getState());
	}

	private void persist()
	{
		if (tracker == null)
		{
			return;
		}

		repository.save(tracker.getSession());
	}
}
