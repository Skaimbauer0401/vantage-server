package dev.mcrs.bridge;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Leaves areas to other performance mods that replace them: with a mod that has its own light
 * engine (Moonrise, ScalableLux, Starlight) our light mixins stay out; with a mod that replaces
 * the chunk system or chunk IO (Moonrise, C2ME) our chunk IO and pipeline mixins stay out.
 * Everything else (Rust terrain, entities, the editions) keeps working alongside them.
 */
public final class McrsMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> LIGHT = Set.of("ThreadedLevelLightEngineMixin", "LightEngineMixin", "SkyLightSourcesMixin");
	private static final Set<String> CHUNK_SYSTEM = Set.of("IOWorkerMixin", "IOWorkerParallelSaveMixin", "ChunkMapParallelSaveMixin", "ChunkStatusTasksMixin");
	/** Further mixins whose targets Moonrise rewrites. */
	private static final Set<String> MOONRISE = Set.of(
			// moonrise-conflicts
			"SectionStorageColumnsMixin",
			// needs SectionStorageColumnsMixin
			"PoiManagerColumnsMixin");

	public static boolean otherLightEngine() {
		FabricLoader loader = FabricLoader.getInstance();
		return loader.isModLoaded("moonrise") || loader.isModLoaded("scalablelux") || loader.isModLoaded("starlight");
	}

	public static boolean otherChunkSystem() {
		FabricLoader loader = FabricLoader.getInstance();
		return loader.isModLoaded("moonrise") || loader.isModLoaded("c2me");
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (LIGHT.contains(name) && otherLightEngine()) {
			return false;
		}
		if (MOONRISE.contains(name) && FabricLoader.getInstance().isModLoaded("moonrise")) {
			return false;
		}
		return !(CHUNK_SYSTEM.contains(name) && otherChunkSystem());
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
