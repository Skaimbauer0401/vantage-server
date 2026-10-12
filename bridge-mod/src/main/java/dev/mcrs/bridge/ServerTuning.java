package dev.mcrs.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * ServerCore-like server tuning: NOT vanilla behavior, on only in the performance edition
 * ({@link Edition}), each with its own switch.
 * <ul>
 * <li>{@code mcrs.dynamicDistance}: every 30 s, at a mean tick time above 40 ms the simulation
 * distance (down to 4) and then the view distance (down to 6) drop by one; below 30 ms they
 * rise again, view distance first, up to the server.properties values.</li>
 * <li>{@code mcrs.villagerLobotomy}: villagers that can't walk anywhere (boxed in, as in trading
 * halls, or riding something) run their brain only every 20th tick. Trading and restocking
 * still work, just with a delay.</li>
 * <li>{@code mcrs.breedingCap}: animals don't breed when 32 of their kind are within 64 blocks
 * (both parents get the normal breeding cooldown).</li>
 * <li>{@code mcrs.mergeRadius}: items merge within 1.5 blocks horizontally and 0.5
 * vertically (vanilla 0.5 and 0), experience orbs within 2 blocks (vanilla 0.5).</li>
 * </ul>
 */
public final class ServerTuning {
	public static final boolean DYNAMIC_DISTANCE = Edition.flag("mcrs.dynamicDistance", Edition.PERFORMANCE);
	public static final boolean VILLAGER_LOBOTOMY = Edition.flag("mcrs.villagerLobotomy", Edition.PERFORMANCE);
	public static final boolean BREEDING_CAP = Edition.flag("mcrs.breedingCap", Edition.PERFORMANCE);
	public static final boolean MERGE_RADIUS = Edition.flag("mcrs.mergeRadius", Edition.PERFORMANCE);

	private static final double HIGH_MSPT = Double.parseDouble(System.getProperty("mcrs.dynamicDistance.high", "40"));
	private static final double LOW_MSPT = Double.parseDouble(System.getProperty("mcrs.dynamicDistance.low", "30"));
	private static final int MIN_VIEW = Integer.getInteger("mcrs.dynamicDistance.minView", 6);
	private static final int MIN_SIMULATION = Integer.getInteger("mcrs.dynamicDistance.minSimulation", 4);
	private static final int WINDOW_TICKS = 600;
	private static final int BREEDING_LIMIT = Integer.getInteger("mcrs.breedingCap.limit", 32);
	private static final double BREEDING_RANGE = Integer.getInteger("mcrs.breedingCap.range", 64);
	public static final int LOBOTOMY_INTERVAL = 20;

	private static long windowNanos;
	private static int windowTicks;
	private static int maxView = -1;
	private static int maxSimulation = -1;

	private ServerTuning() {
	}

	/** After every server tick. */
	public static void onTick(MinecraftServer server, long nanos) {
		if (!DYNAMIC_DISTANCE) {
			return;
		}
		windowNanos += nanos;
		if (++windowTicks < WINDOW_TICKS) {
			return;
		}
		double mspt = windowNanos / 1e6 / windowTicks;
		windowNanos = 0;
		windowTicks = 0;
		PlayerList players = server.getPlayerList();
		if (maxView < 0) {
			maxView = players.getViewDistance();
			maxSimulation = players.getSimulationDistance();
		}
		int view = players.getViewDistance();
		int simulation = players.getSimulationDistance();
		if (mspt > HIGH_MSPT) {
			if (simulation > Math.min(MIN_SIMULATION, maxSimulation)) {
				players.setSimulationDistance(simulation - 1);
			} else if (view > Math.min(MIN_VIEW, maxView)) {
				players.setViewDistance(view - 1);
			} else {
				return;
			}
		} else if (mspt < LOW_MSPT) {
			if (view < maxView) {
				players.setViewDistance(view + 1);
			} else if (simulation < maxSimulation) {
				players.setSimulationDistance(simulation + 1);
			} else {
				return;
			}
		} else {
			return;
		}
		McrsBridge.LOGGER.info("mcrs: mean tick {} ms: view distance {}, simulation distance {}", String.format(java.util.Locale.ROOT, "%.1f", mspt),
				players.getViewDistance(), players.getSimulationDistance());
	}

	/** Whether a villager can't walk to any neighboring block. */
	public static boolean trapped(Villager villager) {
		if (villager.isPassenger()) {
			return true;
		}
		ServerLevel level = (ServerLevel) villager.level();
		BlockPos feet = villager.blockPosition();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos next = feet.relative(direction);
			if (level.getBlockState(next).getCollisionShape(level, next).isEmpty()
					&& level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Whether breeding is refused because too many animals of this kind are near. */
	public static boolean breedingCapped(Animal animal) {
		if (!BREEDING_CAP) {
			return false;
		}
		AABB area = animal.getBoundingBox().inflate(BREEDING_RANGE);
		return animal.level().getEntities(animal.getType(), area, e -> true).size() >= BREEDING_LIMIT;
	}
}
