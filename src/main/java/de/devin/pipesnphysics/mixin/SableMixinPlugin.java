package de.devin.pipesnphysics.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Conditionally applies Sable-dependent mixins based on which Sable components are installed.
 * <ul>
 *   <li>{@code FluidTankRendererMixin} — needs Sable Companion (tilted fluid rendering)</li>
 *   <li>{@code FluidTankMassMixin} — needs Sable Full (dynamic tank mass via physics API)</li>
 * </ul>
 * Without this, loading the mixin classes would crash with NoClassDefFoundError
 * because they reference Sable interfaces that don't exist without the mod.
 */
public class SableMixinPlugin implements IMixinConfigPlugin {

    private static final boolean SABLE_COMPANION_PRESENT;
    private static final boolean SABLE_FULL_PRESENT;

    static {
        SABLE_COMPANION_PRESENT = classExists("dev.ryanhcode.sable.companion.SableCompanion");
        SABLE_FULL_PRESENT = classExists("dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor");
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, SableMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // FluidTankMassMixin needs full Sable (BlockEntitySubLevelActor, ServerSubLevel, etc.)
        if (mixinClassName.endsWith("FluidTankMassMixin")) {
            return SABLE_FULL_PRESENT;
        }
        // All other mixins in this config (FluidTankRendererMixin) need Sable Companion
        return SABLE_COMPANION_PRESENT;
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
