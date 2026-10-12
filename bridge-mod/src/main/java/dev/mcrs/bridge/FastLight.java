package dev.mcrs.bridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Switches for the light increase fast path ({@code mixin.LightEngineMixin}).
 * {@code -Dmcrs.fastLight=false} restores vanilla; {@code -Dmcrs.fastLight.verify=true}
 * checks every cached lookup against vanilla's own call.
 */
public final class FastLight {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.fastLight")) && !McrsMixinPlugin.otherLightEngine();
	public static final boolean VERIFY = Boolean.getBoolean("mcrs.fastLight.verify");
	public static final AtomicLong CHECKED = new AtomicLong();
	public static final AtomicLong MISMATCHED = new AtomicLong();
	public static final java.util.Map<String, AtomicLong> BY_KIND = new java.util.concurrent.ConcurrentHashMap<>();

	private FastLight() {
	}

	public static void check(boolean same, String what) {
		CHECKED.incrementAndGet();
		BY_KIND.computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
		if (!same && MISMATCHED.incrementAndGet() <= 10) {
			McrsBridge.LOGGER.error("mcrs: fast light {} differs from vanilla", what, new Throwable());
		}
	}
}
