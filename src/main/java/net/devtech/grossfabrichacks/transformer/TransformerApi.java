package net.devtech.grossfabrichacks.transformer;

import net.devtech.grossfabrichacks.State;
import net.devtech.grossfabrichacks.transformer.asm.AsmClassTransformer;
import net.devtech.grossfabrichacks.transformer.asm.RawClassTransformer;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.HackedMixinTransformer;

/**
 * The API class for getting access to transforming any and all classes loaded by the KnotClassLoader (or whatever classloader happens to calls mixin)
 */
public class TransformerApi {
	/**
	 * manually load the class, causing it to inject itself into the class loading pipe.
	 */
	public static void manualLoad() {
		if (State.mixinLoaded) {
			try {
				Class.forName("org.spongepowered.asm.mixin.transformer.HackedMixinTransformer");
			} catch (final ClassNotFoundException exception) {
				throw new RuntimeException(exception);
			}
		} else {
			State.manualLoad = true;
		}
	}

	/**
	 * listeners are called before mixins are applied, and gives you raw access to the class' bytecode, allowing you to fiddle with things ASM normally doesn't let you.
	 */
	public static void registerPreMixinRawClassTransformer(RawClassTransformer transformer) {
		if (State.preMixinRawClassTransformer == null) {
			State.preMixinRawClassTransformer = transformer;
			State.transformPreMixinRawClass = true;
		} else {
			State.preMixinRawClassTransformer = State.preMixinRawClassTransformer.andThen(transformer);
		}
	}

	public static void registerNullClassTransformer(RawClassTransformer transformer) {
		if (State.nullRawClassTransformer == null) {
			State.nullRawClassTransformer = transformer;
			State.transformNullRawClass = true;
		} else {
			State.nullRawClassTransformer = State.nullRawClassTransformer.andThen(transformer);
		}
	}

	/**
	 * transformers are called before mixin application with the class' classnode
	 */
	public static void registerPreMixinAsmClassTransformer(AsmClassTransformer transformer) {
		if (State.preMixinAsmClassTransformer == null) {
			State.preMixinAsmClassTransformer = transformer;
			State.transformPreMixinAsmClass = true;
			State.shouldWrite = true;
		} else {
			State.preMixinAsmClassTransformer = State.preMixinAsmClassTransformer.andThen(transformer);
		}
	}

	/**
	 * these are the last transformers to be called, and are fed the output of the classwritten classnode after mixin and postmixinasmtransformers.
	 */
	public static void registerPostMixinRawClassTransformer(RawClassTransformer transformer) {
		if (State.postMixinRawClassTransformer == null) {
			State.postMixinRawClassTransformer = transformer;
			State.transformPostMixinRawClass = true;
			State.shouldWrite = true;
		} else {
			State.postMixinRawClassTransformer = State.postMixinRawClassTransformer.andThen(transformer);
		}
	}

	/**
	 * transformer is called right after mixin application.
	 */
	public static void registerPostMixinAsmClassTransformer(AsmClassTransformer transformer) {
		if (State.postMixinAsmClassTransformer == null) {
			State.postMixinAsmClassTransformer = transformer;
			State.transformPostMixinAsmClass = true;
			State.shouldWrite = true;
		} else {
			State.postMixinAsmClassTransformer = State.postMixinAsmClassTransformer.andThen(transformer);
		}
	}

	public static byte[] transformClass(final ClassNode node) {
		return HackedMixinTransformer.instance.transform(MixinEnvironment.getCurrentEnvironment(), node, null);
	}

	static {
		manualLoad();
	}
}
