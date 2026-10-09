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
	}
}
