package net.devtech.grossfabrichacks.mixin;

import net.devtech.grossfabrichacks.State;
import net.devtech.grossfabrichacks.entrypoints.PrePreLaunch;
import net.devtech.grossfabrichacks.transformer.TransformerApi;
import net.fabricmc.loader.impl.entrypoint.EntrypointUtilsHack;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

public class GrossFabricHacksPlugin implements IMixinConfigPlugin {
    public static List preApplyList;
    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String string, ClassNode classNode, String string2, IMixinInfo iMixinInfo) {
        if (preApplyList != null) {
            for (Object e : preApplyList) {
                try {
                    ((Method)e).invoke(null, string, classNode, string2, iMixinInfo);
                }
                catch (IllegalAccessException | InvocationTargetException reflectiveOperationException) {
                    throw new RuntimeException(reflectiveOperationException);
                }
            }
        }
    }
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    static {
        State.mixinLoaded = true;

        EntrypointUtilsHack.invoke("gfh:prePreLaunch", PrePreLaunch.class, PrePreLaunch::onPrePreLaunch);

        if (State.shouldWrite || State.manualLoad) {
            TransformerApi.manualLoad();
        }
    }
}
