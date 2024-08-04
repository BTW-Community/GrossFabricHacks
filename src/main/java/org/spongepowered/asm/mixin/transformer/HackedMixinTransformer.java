package org.spongepowered.asm.mixin.transformer;

import net.devtech.grossfabrichacks.State;
import net.devtech.grossfabrichacks.unsafe.UnsafeUtil;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinInitialisationError;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.throwables.MixinException;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;
import org.spongepowered.asm.mixin.transformer.ext.IHotSwap;
import org.spongepowered.asm.transformers.TreeTransformer;
import org.spongepowered.asm.util.Constants;
import org.spongepowered.asm.util.asm.ASM;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Logger.getLogger;

public class HackedMixinTransformer extends TreeTransformer implements IMixinTransformer {
    // Until a space-separated comment, all of this is part of MixinTransformer itself

    static class Factory implements IMixinTransformerFactory {
        @Override
        public IMixinTransformer createTransformer() throws MixinInitialisationError {
            return new MixinTransformer();
        }

    }

    private static final String MIXIN_AGENT_CLASS = "org.spongepowered.tools.agent.MixinAgent";

    private final SyntheticClassRegistry syntheticClassRegistry;

    private final Extensions extensions;

    private final IHotSwap hotSwapper;

    private final MixinCoprocessorNestHost nestHostCoprocessor;

    private final MixinProcessor processor;

    private final MixinClassGenerator generator;

    HackedMixinTransformer() {
        MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();

        Object globalMixinTransformer = environment.getActiveTransformer();
        if (globalMixinTransformer instanceof IMixinTransformer) {
            throw new MixinException("Terminating MixinTransformer instance " + this);
        }

        // I am a leaf on the wind
        environment.setActiveTransformer(this);

        this.syntheticClassRegistry = new SyntheticClassRegistry();
        this.extensions = new Extensions(this.syntheticClassRegistry);

        this.hotSwapper = this.initHotSwapper(environment);
        this.nestHostCoprocessor = new MixinCoprocessorNestHost();

        this.processor = new MixinProcessor(environment, this.extensions, this.hotSwapper, this.nestHostCoprocessor);
        this.generator = new MixinClassGenerator(environment, this.extensions);

        DefaultExtensions.create(environment, this.extensions, this.syntheticClassRegistry, this.nestHostCoprocessor);
    }

    private IHotSwap initHotSwapper(MixinEnvironment environment) {
        if (!environment.getOption(MixinEnvironment.Option.HOT_SWAP)) {
            return null;
        }

        try {
            MixinProcessor.logger.info("Attempting to load Hot-Swap agent");
            @SuppressWarnings("unchecked")
            Class<? extends IHotSwap> clazz =
                    (Class<? extends IHotSwap>)Class.forName(MIXIN_AGENT_CLASS);
            Constructor<? extends IHotSwap> ctor = clazz.getDeclaredConstructor(IMixinTransformer.class);
            return ctor.newInstance(this);
        } catch (Throwable th) {
            MixinProcessor.logger.info("Hot-swap agent could not be loaded, hot swapping of mixins won't work. {}: {}",
                    th.getClass().getSimpleName(), th.getMessage());
        }

        return null;
    }

    @Override
    public IExtensionRegistry getExtensions() {
        return this.extensions;
    }

    @Override
    public String getName() {
        return this.getClass().getName();
    }

    @Override
    public boolean isDelegationExcluded() {
        return true;
    }

    @Override
    public void audit(MixinEnvironment environment) {
        this.processor.audit(environment);
    }

    @Override
    public List<String> reload(String mixinClass, ClassNode classNode) {
        return this.processor.reload(mixinClass, classNode);
    }

    @Override
    public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
        if (transformedName == null) {
            return basicClass;
        }

        MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();

        if (basicClass == null) {
            return this.generateClass(environment, transformedName);
        }

        return this.transformClass(environment, transformedName, basicClass);
    }

    @Override
    public boolean computeFramesForClass(MixinEnvironment environment, String name, ClassNode classNode) {
        // TODO compute added interfaces
        return false;
    }

    @Override
    public boolean transformClass(MixinEnvironment environment, String name, ClassNode classNode) {
        return this.processor.applyMixins(environment, name, classNode);
    }

    @Override
    public byte[] generateClass(MixinEnvironment environment, String name) {
        ClassNode classNode = createEmptyClass(name);
        if (this.generator.generateClass(environment, name, classNode)) {
            return this.writeClass(classNode);
        }
        return null;
    }

    @Override
    public boolean generateClass(MixinEnvironment environment, String name, ClassNode classNode) {
        return this.generator.generateClass(environment, name, classNode);
    }

    // this comment is in MixinTransformer itself and is too good to delete
    /**
     * You need to ask yourself why you're reading this comment
     */
    private static ClassNode createEmptyClass(String name) {
        ClassNode classNode = new ClassNode(ASM.API_VERSION);
        classNode.name = name.replace('.', '/');
        classNode.version = MixinEnvironment.getCompatibilityLevel().getClassVersion();
        classNode.superName = Constants.OBJECT;
        return classNode;
    }

    // CUSTOM STUFFS BENEATH

    public static final Class<MixinTransformer> superclass = MixinTransformer.class;

    public static final HackedMixinTransformer instance;
    public static final MixinProcessor pub_processor;
    public static final Extensions pub_extensions;

    private static final Logger LOGGER = getLogger("GrossFabricHacks/HackedMixinTransformer");

    @Override
    public byte[] transformClass(final MixinEnvironment environment, final String name, byte[] classBytes) {
        // raw class patching
        /*if (State.transformPreMixinRawClass) {
            classBytes = State.preMixinRawClassTransformer.transform(name, classBytes);
        }*/

        // ASM patching
        return this.transform(environment, this.readClass(name, classBytes), classBytes);
    }

    public byte[] transform(MixinEnvironment environment, ClassNode classNode, byte[] original) {
        final String name = classNode.name;

        // return immediately to reduce jumps and assignments
        if (State.shouldWrite) {
            if (State.transformPreMixinAsmClass) {
                State.preMixinAsmClassTransformer.transform(name, classNode);
            }

            processor.applyMixins(environment, name.replace('/', '.'), classNode);

            if (State.transformPostMixinAsmClass) {
                State.postMixinAsmClassTransformer.transform(name, classNode);
            }

            // post mixin raw patching
            if (State.transformPostMixinRawClass) {
                return State.postMixinRawClassTransformer.transform(name, this.writeClass(classNode));
            }

            return this.writeClass(classNode);
        }

        if (processor.applyMixins(environment, name.replace('/', '.'), classNode)) {
            return this.writeClass(classNode);
        }

        return original;
    }

    static {
        try {
            final Object mixinTransformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();

            LOGGER.info("MixinTransformer found! " + mixinTransformer);

            // here, we modify the klass pointer in the object to point towards the HackedMixinTransformer class, effectively turning the existing
            // MixinTransformer instance into an instance of HackedMixinTransformer
            UnsafeUtil.unsafeCast(mixinTransformer, "org.spongepowered.asm.mixin.transformer.HackedMixinTransformer");

            LOGGER.info("Unsafe cast mixin transformer success!");

            instance = (HackedMixinTransformer) mixinTransformer;

            pub_processor = instance.processor;
            pub_extensions = instance.extensions;
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }
}