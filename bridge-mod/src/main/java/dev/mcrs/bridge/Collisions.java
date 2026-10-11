package dev.mcrs.bridge;

/**
 * At most {@code -Dmcrs.maxEntityCollisions} (default 8) entities pushed by one entity per
 * tick, like Paper's {@code max-entity-collisions}: NOT vanilla behavior, on only in the
 * performance edition ({@link Edition}) or with {@code -Dmcrs.collisionLimit=true}. Crowds
 * (mob farms, animal pens) then push apart more slowly; entity cramming still counts them all.
 */
public final class Collisions {
	public static final boolean ENABLED = Edition.flag("mcrs.collisionLimit", Edition.PERFORMANCE);
	public static final int MAX = Math.max(1, Integer.getInteger("mcrs.maxEntityCollisions", 8));

	private Collisions() {
	}
}
