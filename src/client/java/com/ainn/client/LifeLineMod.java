package com.ainn.client;

import com.google.gson.*;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.*;

public class LifeLineMod implements ClientModInitializer {
	// ---- CONFIG ----
	static final String WS_URL = "wss://sos-hub.ainnwss.workers.dev/?key=1355";

	// Configurable Settings (modifiable via /sos commands)
	static boolean enabled = true;                          // Master toggle for all SOS features
	static float triggerHp = 2.0f;                          // Health amount to trigger
	static String customMessage = "§c§lHELP! §e{name} is down at {coords}!";
	static long sosLifetimeMs = 15_000;                     // How long the SOS stays on HUD (in milliseconds)

	static final long COOLDOWN_MS = 30_000;
	// -----------------

	static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("LifelineMod");

	record Sos(String name, int x, int y, int z, String message, long expires) {}

	static final List<Sos> active = new CopyOnWriteArrayList<>();
	static volatile WebSocket socket;
	static long lastSent = 0;
	static boolean wasLow = false;

	static final ScheduledExecutorService KEEPALIVE =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "lifeline-keepalive");
				t.setDaemon(true);
				return t;
			});

	@Override
	public void onInitializeClient() {
		connect();

		KEEPALIVE.scheduleAtFixedRate(() -> {
			WebSocket s = socket;
			if (s != null) {
				try { s.sendText("ping", true); } catch (Exception ignored) {}
			}
		}, 30, 30, TimeUnit.SECONDS);

		// Register client-side commands
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(
					literal("sos")
							.then(literal("toggle")
									.executes(context -> {
										enabled = !enabled;
										String status = enabled ? "§aENABLED" : "§cDISABLED";
										context.getSource().sendFeedback(Component.literal("§7[LifeLine] SOS Mod is now " + status));
										return 1;
									})
							)
							.then(literal("config")
									.then(literal("message")
											.then(argument("text", StringArgumentType.greedyString())
													.executes(context -> {
														customMessage = StringArgumentType.getString(context, "text");
														context.getSource().sendFeedback(Component.literal("§a[LifeLine] Custom message updated to: §f" + customMessage));
														return 1;
													})
											)
									)
									.then(literal("health")
											.then(argument("hp", FloatArgumentType.floatArg(0.0f, 20.0f))
													.executes(context -> {
														triggerHp = FloatArgumentType.getFloat(context, "hp");
														context.getSource().sendFeedback(Component.literal("§a[LifeLine] Trigger HP updated to: §f" + triggerHp));
														return 1;
													})
											)
									)
									.then(literal("duration")
											.then(argument("seconds", IntegerArgumentType.integer(1, 300))
													.executes(context -> {
														int secs = IntegerArgumentType.getInteger(context, "seconds");
														sosLifetimeMs = secs * 1_000L;
														context.getSource().sendFeedback(Component.literal("§a[LifeLine] HUD duration updated to: §f" + secs + " seconds"));
														return 1;
													})
											)
									)
							)
			);
		});

		// Low-health trigger + cleanup
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!enabled || mc.player == null) return;
			boolean low = mc.player.getHealth() <= triggerHp && mc.player.isAlive();
			if (low && !wasLow && System.currentTimeMillis() - lastSent > COOLDOWN_MS) sendSos(mc);
			wasLow = low;
			active.removeIf(s -> s.expires < System.currentTimeMillis());
		});

		// HUD: Compact list overlay
		HudElementRegistry.attachElementAfter(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath("lifelinemod", "sos_hud"),
				(graphics, tickCounter) -> {
					if (!enabled) return;
					Minecraft mc = Minecraft.getInstance();
					if (mc.player == null) return;
					int y = 4;
					for (Sos s : active) {
						String displayMsg = s.message != null && !s.message.isEmpty() ? " - " + s.message : "";
						graphics.drawString(mc.font,
								"§c[SOS] §f" + s.name + displayMsg + " §7@ " + s.x + " " + s.y + " " + s.z,
								4, y, 0xFFFFFFFF);
						y += 10;
					}
				});
	}

	static void showTitle(Minecraft mc, String name, int x, int y, int z, String message) {
		// Shorter fade-in, stay, and fade-out times so title clears quickly
		mc.gui.setTimes(5, 30, 10);
		mc.gui.setSubtitle(Component.literal("§e" + message));
		mc.gui.setTitle(Component.literal("§cSOS (" + name + ")"));
	}

	static void sendSos(Minecraft mc) {
		if (!enabled) return;
		String ip = currentServer(mc);
		if (ip == null) { LOG.warn("SOS not sent: no server address"); return; }
		if (socket == null) { LOG.warn("SOS not sent: websocket not connected"); return; }

		String playerName = mc.player.getGameProfile().name();
		String coordsStr = mc.player.getBlockX() + ", " + mc.player.getBlockY() + ", " + mc.player.getBlockZ();

		// Replace variables {name} and {coords} in the custom message
		String processedMessage = customMessage
				.replace("{name}", playerName)
				.replace("{coords}", coordsStr);

		JsonObject o = new JsonObject();
		o.addProperty("name", playerName);
		o.addProperty("coords", mc.player.getBlockX() + "," + mc.player.getBlockY() + "," + mc.player.getBlockZ());
		o.addProperty("ip", ip);
		o.addProperty("message", processedMessage);

		socket.sendText(o.toString(), true);
		lastSent = System.currentTimeMillis();
		LOG.info("SOS sent: {}", o);
	}

	static void onPacket(String json) {
		if (!enabled) return;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				JsonObject o = JsonParser.parseString(json).getAsJsonObject();
				String name = o.get("name").getAsString();
				String ip = o.get("ip").getAsString();
				String[] c = o.get("coords").getAsString().split(",");
				String rawMessage = o.has("message") ? o.get("message").getAsString() : "";

				String mine = currentServer(mc);
				if (mine == null || !mine.equalsIgnoreCase(ip)) return;
				if (mc.player == null || name.equals(mc.player.getGameProfile().name())) return;

				int x = Integer.parseInt(c[0].trim());
				int y = Integer.parseInt(c[1].trim());
				int z = Integer.parseInt(c[2].trim());
				String coordsStr = x + ", " + y + ", " + z;

				// Process placeholders {name} and {coords} on receive as well
				String processedMessage = rawMessage
						.replace("{name}", name)
						.replace("{coords}", coordsStr);

				active.removeIf(s -> s.name.equals(name));
				active.add(new Sos(name, x, y, z, processedMessage, System.currentTimeMillis() + sosLifetimeMs));

				if (mc.level != null && mc.player != null) {
					mc.level.playSound(
							mc.player,
							mc.player.blockPosition(),
							SoundEvents.RAID_HORN.value(),
							SoundSource.PLAYERS,
							1.0f,
							1.0f
					);
				}

				showTitle(mc, name, x, y, z, processedMessage);
				mc.player.displayClientMessage(
						Component.literal("§c[SOS] " + processedMessage + " §7(" + coordsStr + ")"), false);
			} catch (Exception e) {
				LOG.warn("bad packet: {}", json, e);
			}
		});
	}

	static String currentServer(Minecraft mc) {
		var e = mc.getCurrentServer();
		return e == null ? null : e.ip;
	}

	static void connect() {
		HttpClient.newHttpClient().newWebSocketBuilder()
				.buildAsync(URI.create(WS_URL), new WebSocket.Listener() {
					final StringBuilder buf = new StringBuilder();

					@Override public void onOpen(WebSocket ws) {
						socket = ws;
						LOG.info("websocket connected");
						ws.request(1);
					}

					@Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
						buf.append(data);
						if (last) {
							String m = buf.toString();
							if (!m.equals("pong")) onPacket(m);
							buf.setLength(0);
						}
						ws.request(1);
						return null;
					}

					@Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
						LOG.warn("websocket closed: {} {}", code, reason);
						socket = null;
						retry();
						return null;
					}

					@Override public void onError(WebSocket ws, Throwable t) {
						LOG.warn("websocket error", t);
						socket = null;
						retry();
					}
				})
				.exceptionally(t -> {
					LOG.warn("websocket connect failed", t);
					retry();
					return null;
				});
	}

	static void retry() {
		CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(LifeLineMod::connect);
	}
}