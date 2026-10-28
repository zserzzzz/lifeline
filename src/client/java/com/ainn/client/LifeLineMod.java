package com.ainn.client;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.*;

public class LifeLineMod implements ClientModInitializer {
	// ---- CONFIG ----
	static final String WS_URL = "wss://sos-hub.ainnwss.workers.dev/?key=1355";
	static final float TRIGGER_HP = 2.0f;          // fires at 2 HP (1 heart) or lower
	static final long COOLDOWN_MS = 30_000;
	static final long SOS_LIFETIME_MS = 120_000;
	static final boolean FORCE_BEAM = false;       // true = always show beam (for testing)
	// -----------------

	static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("LifelineMod");

	record Sos(String name, int x, int y, int z, boolean beam, long expires) {}

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

		// low-health trigger + cleanup
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) return;
			boolean low = mc.player.getHealth() <= TRIGGER_HP && mc.player.isAlive();
			if (low && !wasLow && System.currentTimeMillis() - lastSent > COOLDOWN_MS) sendSos(mc);
			wasLow = low;
			active.removeIf(s -> s.expires < System.currentTimeMillis());
		});

		// HUD: SOS list + beam overlays
		HudElementRegistry.attachElementAfter(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath("lifelinemod", "sos_hud"),
				(graphics, tickCounter) -> {
					Minecraft mc = Minecraft.getInstance();
					if (mc.player == null) return;
					int y = 4;
					for (Sos s : active) {
						graphics.drawString(mc.font,
								"§c[SOS] §f" + s.name + " §7@ " + s.x + " " + s.y + " " + s.z,
								4, y, 0xFFFFFFFF);
						y += 10;
						if (s.beam) drawBeamOverlay(graphics, mc, s);
					}
				});
	}

	// ---------- beam: projected onto the screen as a tall red pillar ----------
	static void drawBeamOverlay(GuiGraphics g, Minecraft mc, Sos s) {
		Vec3 eye = mc.player.getEyePosition();
		double dx = s.x + 0.5 - eye.x, dy = s.y + 0.5 - eye.y, dz = s.z + 0.5 - eye.z;

		double yaw = Math.toRadians(mc.player.getYRot());
		double pitch = Math.toRadians(mc.player.getXRot());
		double fx = -Math.sin(yaw) * Math.cos(pitch);
		double fy = -Math.sin(pitch);
		double fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);

		double depth = dx * fx + dy * fy + dz * fz;
		double right = dx * rx + dz * rz;
		if (depth < 0.5) return; // behind you

		int W = mc.getWindow().getGuiScaledWidth();
		int H = mc.getWindow().getGuiScaledHeight();
		double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
		double aspect = (double) W / H;

		double ndcX = (right / depth) / (tanHalf * aspect);
		if (Math.abs(ndcX) > 1.3) return; // off screen
		int sx = (int) (W / 2.0 * (1 + ndcX));
		int w = (int) Math.max(2, Math.min(40, 0.5 * (W / 2.0) / (depth * tanHalf * aspect)));

		g.fill(sx - w / 2, 0, sx + w / 2, H, 0x55FF2020);   // glow
		g.fill(sx - 1, 0, sx + 1, H, 0xCCFF5555);           // core

		int dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
		String label = "§cSOS §f" + s.name + " §7" + dist + "m";
		g.drawString(mc.font, label, sx - mc.font.width(label) / 2, 28, 0xFFFFFFFF);
	}

	// ---------- title on screen ----------
	static void showTitle(Minecraft mc, String name, int x, int y, int z) {
		mc.gui.setTimes(10, 70, 20);
		mc.gui.setSubtitle(Component.literal("§7" + x + " " + y + " " + z));
		mc.gui.setTitle(Component.literal("§cSOS (" + name + ")"));
	}

	// ---------- waypoint mod detection ----------
	static boolean hasXaero() {
		return FabricLoader.getInstance().isModLoaded("xaerominimap");
	}

	static boolean hasWaypointMod() {
		FabricLoader l = FabricLoader.getInstance();
		return l.isModLoaded("xaerominimap") || l.isModLoaded("wwaypoints");
	}

	// ---------- sending ----------
	static void sendSos(Minecraft mc) {
		String ip = currentServer(mc);
		if (ip == null) { LOG.warn("SOS not sent: no server address (singleplayer/LAN?)"); return; }
		if (socket == null) { LOG.warn("SOS not sent: websocket not connected"); return; }
		JsonObject o = new JsonObject();
		o.addProperty("name", mc.player.getGameProfile().name());
		o.addProperty("coords", mc.player.getBlockX() + "," + mc.player.getBlockY() + "," + mc.player.getBlockZ());
		o.addProperty("ip", ip);
		socket.sendText(o.toString(), true);
		lastSent = System.currentTimeMillis();
		LOG.info("SOS sent: {}", o);
	}

	// ---------- receiving ----------
	static void onPacket(String json) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				JsonObject o = JsonParser.parseString(json).getAsJsonObject();
				String name = o.get("name").getAsString();
				String ip = o.get("ip").getAsString();
				String[] c = o.get("coords").getAsString().split(",");

				String mine = currentServer(mc);
				if (mine == null || !mine.equalsIgnoreCase(ip)) return;            // wrong server
				if (mc.player == null || name.equals(mc.player.getGameProfile().name())) return;

				int x = Integer.parseInt(c[0].trim());
				int y = Integer.parseInt(c[1].trim());
				int z = Integer.parseInt(c[2].trim());

				boolean loaded = isLoaded(mc, name);
				boolean beam = FORCE_BEAM || loaded || !hasWaypointMod();

				active.removeIf(s -> s.name.equals(name));
				active.add(new Sos(name, x, y, z, beam, System.currentTimeMillis() + SOS_LIFETIME_MS));

				showTitle(mc, name, x, y, z);
				mc.player.displayClientMessage(
						Component.literal("§c[SOS] §f" + name + " is low! " + x + " " + y + " " + z), false);

				if (!loaded && hasXaero()) addXaeroWaypoint(mc, name, x, y, z);
			} catch (Exception e) {
				LOG.warn("bad packet: {}", json, e);
			}
		});
	}

	static boolean isLoaded(Minecraft mc, String name) {
		if (mc.level == null) return false;
		for (Player p : mc.level.players())
			if (p.getGameProfile().name().equals(name)) return true;
		return false;
	}

	static String currentServer(Minecraft mc) {
		var e = mc.getCurrentServer();
		return e == null ? null : e.ip;
	}

	static void addXaeroWaypoint(Minecraft mc, String name, int x, int y, int z) {
		String dimKey = mc.level.dimension().toString();
		String dim = dimKey.contains("the_nether") ? "the_nether"
				: dimKey.contains("the_end") ? "the_end" : "overworld";
		String set = "Internal-" + dim + "-waypoints";
		String initial = name.substring(0, 1).toUpperCase();
		String share = "xaero-waypoint:SOS " + name + ":" + initial + ":" + x + ":" + y + ":" + z
				+ ":4:false:0:" + set;
		mc.player.displayClientMessage(Component.literal(share), false);
	}

	// ---------- websocket with auto-reconnect ----------
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