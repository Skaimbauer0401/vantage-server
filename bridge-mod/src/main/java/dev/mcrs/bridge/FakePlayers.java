package dev.mcrs.bridge;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side fake players for benchmarks ({@code /mcrs bots <count> <speed> <ticks>}). Each
 * joins through {@code PlayerList.placeNewPlayer} with a connection that drops outgoing packets
 * (chunk packets are still built, as for a real client) and answers like a client: teleport
 * confirms, keep-alives, chunk batch acknowledgements (32 chunks per tick wanted) and the
 * "level loaded" packet. Every tick each bot sends a movement packet to its real packet
 * listener, flying outward from spawn in its own direction 20 blocks above the terrain, and
 * the listener is ticked as the network layer would. The run reports tick times and the
 * number of chunk packets built, then the bots disconnect.
 */
public final class FakePlayers {
	private static final float CHUNKS_PER_TICK = 32.0F;
	private static final boolean DEBUG = Boolean.getBoolean("mcrs.bots.debug");
	private static Run run;

	private FakePlayers() {
	}

	/** A connection without a channel that answers what a client must answer. */
	static final class FakeConnection extends Connection {
		final ArrayDeque<Packet<?>> replies = new ArrayDeque<>();
		long chunksSent;
		boolean closed;

		FakeConnection() {
			super(PacketFlow.SERVERBOUND);
		}

		@Override
		public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
			if (packet instanceof ClientboundLevelChunkWithLightPacket) {
				chunksSent++;
			} else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
				replies.add(new ServerboundChunkBatchReceivedPacket(CHUNKS_PER_TICK));
			} else if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
				replies.add(new ServerboundKeepAlivePacket(keepAlive.getId()));
			} else if (packet instanceof ClientboundPlayerPositionPacket position) {
				Vec3 pos = position.change().position();
				replies.add(new ServerboundAcceptTeleportationPacket(position.id(), pos.x, pos.y, pos.z, position.change().yRot(), position.change().xRot()));
			}
		}

		@Override
		public boolean isConnected() {
			return !closed;
		}

		@Override
		public boolean isMemoryConnection() {
			return false;
		}

		@Override
		public void flushChannel() {
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
		}

		@Override
		public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
		}

		@Override
		public void setReadOnly() {
		}

		@Override
		public void disconnect(DisconnectionDetails details) {
			if (!closed) {
				com.mojang.logging.LogUtils.getLogger().warn("[bots] fake connection closed: {}", details.reason().getString());
			}
			closed = true;
		}
	}

	static final class Bot {
		final ServerPlayer player;
		final FakeConnection connection;
		final double dirX;
		final double dirZ;
		double x;
		double z;
		double y;

		Bot(ServerPlayer player, FakeConnection connection, double dirX, double dirZ, double x, double z) {
			this.player = player;
			this.connection = connection;
			this.dirX = dirX;
			this.dirZ = dirZ;
			this.x = x;
			this.z = z;
			this.y = player.getY();
		}
	}

	public static int start(CommandSourceStack source, int count, double speed, int ticks) {
		if (run != null) {
			source.sendFailure(Component.literal("Bots are already running"));
			return 0;
		}
		MinecraftServer server = source.getServer();
		ServerLevel level = server.overworld();
		List<Bot> bots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double angle = 2 * Math.PI * i / count;
			double dirX = Math.cos(angle);
			double dirZ = Math.sin(angle);
			double x = dirX * 200;
			double z = dirZ * 200;
			String name = "bot" + i;
			GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
			ClientInformation defaults = ClientInformation.createDefault();
			// A typical client view distance (vanilla's default options) instead of the record's 2.
			ClientInformation info = new ClientInformation(defaults.language(), 12, defaults.chatVisibility(), defaults.chatColors(), defaults.modelCustomisation(),
					defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing(), defaults.particleStatus());
			ServerPlayer player = new ServerPlayer(server, level, profile, info);
			int ground = level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x) & 15,
					(int) Math.floor(z) & 15);
			player.snapTo(x, ground + 20, z, (float) Math.toDegrees(Math.atan2(-dirX, dirZ)), 0.0F);
			FakeConnection connection = new FakeConnection();
			server.getPlayerList().placeNewPlayer(connection, player, new CommonListenerCookie(profile, 0, info, false));
			player.setGameMode(GameType.CREATIVE);
			player.getAbilities().flying = true;
			player.connection.handlePlayerAbilities(new ServerboundPlayerAbilitiesPacket(player.getAbilities()));
			player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
			// The network layer ticks the listener before a client's first movement arrives.
			player.connection.tick();
			bots.add(new Bot(player, connection, dirX, dirZ, x, z));
		}
		run = new Run(source, bots, speed, ticks);
		Benchmarks.report(source, String.format(Locale.ROOT, "[bots] %d bots flying at %.2f blocks/tick for %d ticks", count, speed, ticks));
		return 1;
	}

	public static boolean isRunning() {
		return run != null;
	}

	/** At the start of every server tick (inside the measured tick). */
	public static void onTickStart() {
		Run r = run;
		if (r != null) {
			r.tickBots();
		}
	}

	/** After every server tick with its duration. */
	public static void onTick(long nanos) {
		Run r = run;
		if (r != null && r.record(nanos)) {
			run = null;
			r.finish();
		}
	}

	private static final class Run {
		final CommandSourceStack source;
		final List<Bot> bots;
		final double speed;
		final long[] nanos;
		int tick;

		Run(CommandSourceStack source, List<Bot> bots, double speed, int ticks) {
			this.source = source;
			this.bots = bots;
			this.speed = speed;
			this.nanos = new long[ticks];
		}

		void tickBots() {
			for (Bot bot : bots) {
				ServerPlayer player = bot.player;
				// Only what the client received before this tick; answers can cause new packets.
				for (int n = bot.connection.replies.size(); n > 0; n--) {
					handle(player, bot.connection.replies.poll());
				}
				bot.x += bot.dirX * speed;
				bot.z += bot.dirZ * speed;
				ServerLevel level = player.level();
				int bx = (int) Math.floor(bot.x);
				int bz = (int) Math.floor(bot.z);
				if (level.hasChunk(bx >> 4, bz >> 4)) {
					bot.y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) + 20;
				}
				player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(bot.x, bot.y, bot.z, false, false));
				player.connection.handleClientTickEnd(net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.INSTANCE);
				if (DEBUG && bot == bots.get(0) && tick % 20 == 0) {
					Benchmarks.report(source, String.format(Locale.ROOT, "[bots] debug tick %d: wanted %.1f %.1f %.1f, at %.1f %.1f %.1f", tick, bot.x, bot.y, bot.z,
							player.getX(), player.getY(), player.getZ()));
				}
				player.connection.tick();
			}
		}

		private static void handle(ServerPlayer player, Packet<?> reply) {
			if (reply instanceof ServerboundChunkBatchReceivedPacket p) {
				player.connection.handleChunkBatchReceived(p);
			} else if (reply instanceof ServerboundKeepAlivePacket p) {
				player.connection.handleKeepAlive(p);
			} else if (reply instanceof ServerboundAcceptTeleportationPacket p) {
				player.connection.handleAcceptTeleportPacket(p);
			}
		}

		boolean record(long n) {
			nanos[tick++] = n;
			return tick == nanos.length;
		}

		void finish() {
			long chunks = 0;
			for (Bot bot : bots) {
				chunks += bot.connection.chunksSent;
				Benchmarks.report(source, String.format(Locale.ROOT, "[bots] %s at %.0f %.0f %.0f, %d chunk packets, %d replies queued, loaded %s",
						bot.player.getPlainTextName(), bot.player.getX(), bot.player.getY(), bot.player.getZ(), bot.connection.chunksSent,
						bot.connection.replies.size(), bot.player.connection.hasClientLoaded()));
				bot.player.connection.onDisconnect(new DisconnectionDetails(Component.literal("benchmark finished")));
			}
			long[] sorted = nanos.clone();
			Arrays.sort(sorted);
			double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
			double median = sorted[sorted.length / 2] / 1e6;
			double p95 = sorted[(int) (sorted.length * 0.95)] / 1e6;
			String summary = String.format(Locale.ROOT, "[bots] %d ticks, mean %.3f ms, median %.3f ms, p95 %.3f ms, %d chunk packets", nanos.length, mean, median, p95,
					chunks);
			Benchmarks.report(source, summary);
			try {
				Files.writeString(Path.of("bots-result.txt"), summary + System.lineSeparator());
			} catch (IOException e) {
				// The report above already has it.
			}
		}
	}
}
