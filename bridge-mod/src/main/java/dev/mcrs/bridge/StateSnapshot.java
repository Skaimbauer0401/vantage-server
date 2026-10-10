package dev.mcrs.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reflective snapshot of an object's mutable state (for verify modes): every instance field
 * of it and of the Minecraft objects it reaches, plus {@link AtomicLong} values. Restoring
 * puts back each field (references included); {@link #values} lists the reachable primitive
 * values in walk order, for comparing two states.
 */
public final class StateSnapshot {
	private final List<Object[]> entries = new ArrayList<>();

	private StateSnapshot() {
	}

	public static StateSnapshot capture(Object root) {
		StateSnapshot s = new StateSnapshot();
		s.walk(root, new IdentityHashMap<>());
		return s;
	}

	private void walk(Object o, Map<Object, Boolean> seen) {
		if (o == null || seen.put(o, Boolean.TRUE) != null) {
			return;
		}
		if (o instanceof AtomicLong atomic) {
			entries.add(new Object[] {atomic, null, atomic.get()});
			return;
		}
		if (!o.getClass().getName().startsWith("net.minecraft.")) {
			return;
		}
		for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				try {
					f.setAccessible(true);
					Object value = f.get(o);
					entries.add(new Object[] {o, f, value});
					if (!f.getType().isPrimitive()) {
						walk(value, seen);
					}
				} catch (ReflectiveOperationException | RuntimeException e) {
					throw new IllegalStateException("mcrs: cannot snapshot " + f, e);
				}
			}
		}
	}

	public void restore() {
		for (Object[] e : entries) {
			if (e[1] == null) {
				((AtomicLong) e[0]).set((Long) e[2]);
			} else if (!Modifier.isFinal(((Field) e[1]).getModifiers())) {
				try {
					((Field) e[1]).set(e[0], e[2]);
				} catch (IllegalAccessException ex) {
					throw new IllegalStateException(ex);
				}
			}
		}
	}

	/** The primitive (and boxed / AtomicLong) values, in walk order. */
	public List<Object> values() {
		List<Object> out = new ArrayList<>();
		for (Object[] e : entries) {
			Object v = e[2];
			if (v == null || v instanceof Number || v instanceof Boolean || v instanceof Character) {
				out.add(v);
			}
		}
		return out;
	}

	public static boolean sameValues(StateSnapshot a, StateSnapshot b) {
		return Objects.equals(a.values(), b.values());
	}
}
