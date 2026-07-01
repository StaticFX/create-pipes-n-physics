package de.devin.pipesnphysics.mixin;

import net.neoforged.fml.loading.FMLLoader;
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
 *   <li>{@code FluidTankWeightGoggleMixin} — goggle weight readout for the mass feature,
 *       so it follows Sable Full as well</li>
 * </ul>
 * Mod presence is checked via the loading mod list so we never force-load Sable classes
 * during mixin bootstrap.
 */
public class SableMixinPlugin implements IMixinConfigPlugin {
    private static final boolean SABLE_COMPANION_PRESENT =
            FMLLoader.getLoadingModList().getModFileById("sablecompanion") != null;
    private static final boolean SABLE_FULL_PRESENT =
            FMLLoader.getLoadingModList().getModFileById("sable") != null;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // The mass feature and its goggle readout need full Sable (physics API).
        if (mixinClassName.endsWith("FluidTankMassMixin")
                || mixinClassName.endsWith("FluidTankWeightGoggleMixin")) {
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
