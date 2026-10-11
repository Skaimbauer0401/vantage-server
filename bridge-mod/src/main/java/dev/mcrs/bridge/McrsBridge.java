package dev.mcrs.bridge;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McrsBridge implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcrs-bridge");

	@Override
	public void onInitialize() {
		if (!NativeBridge.init()) {
			LOGGER.warn("Not running inside mcrs (no -Dmcrs.bridge); bridge disabled");
			return;
		}
		NativeBridge.log(NativeBridge.INFO, "bridge online (ABI " + NativeBridge.ABI_VERSION + ")");
		NativeBridge.log(NativeBridge.INFO, String.format(java.util.Locale.ROOT,
				"%s edition: activation range %s, brain throttle %s, collision limit %s, spawn rate %s, idle hoppers %s, redstone experiments %s", Edition.CURRENT.name().toLowerCase(java.util.Locale.ROOT),
				onOff(ActivationRange.ENABLED), onOff(BrainThrottle.ENABLED), onOff(Collisions.ENABLED), onOff(SpawnRate.ENABLED), onOff(Hoppers.ENABLED), onOff(Redstone.EXPERIMENTS)));
	}

	private static String onOff(boolean on) {
		return on ? "on" : "off";
	}
}
