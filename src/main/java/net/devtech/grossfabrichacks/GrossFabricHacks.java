package net.devtech.grossfabrichacks;

import net.auoeke.reflect.ClassDefiner;
import net.devtech.grossfabrichacks.entrypoints.PrePrePreLaunch;
import net.devtech.grossfabrichacks.unsafe.UnsafeUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.impl.entrypoint.EntrypointUtilsHack;
import net.fabricmc.loader.impl.launch.knot.UnsafeKnotClassLoader;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Logger.getLogger;


public class GrossFabricHacks implements LanguageAdapter {
    private static final Logger LOGGER = getLogger("GrossFabricHacks");

    public static final UnsafeKnotClassLoader UNSAFE_LOADER;
    private static ClassLoader KnotClassLoader;

    @Override
    public native <T> T create(net.fabricmc.loader.api.ModContainer mod, String value, Class<T> type);

/*
    private static void loadSimpleMethodHandle() {
        try {
            final String internalName = "net/devtech/grossfabrichacks/reflection/SimpleMethodHandle";
            final ClassReader reader = new ClassReader(GrossFabricHacks.class.getClassLoader().getResourceAsStream(internalName + ".class"));
            final ClassNode klass = new ClassNode();
            reader.accept(klass, 0);

            final MethodNode[] methods = klass.methods.toArray(new MethodNode[0]);

            for (final MethodNode method : methods) {
                if (method.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                    method.access &= ~Opcodes.ACC_NATIVE;

                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, internalName, "delegate", Type.getDescriptor(MethodHandle.class));
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(MethodHandle.class), "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                    method.visitInsn(Opcodes.ARETURN);
                }
            }
        } catch (final Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }
 */

    public static void forceLoadClass(String name) {
        Stack<String> stack = new Stack<>();
        HashMap<String, Class> classes = new HashMap<>();
        stack.push(name);
        while (!stack.isEmpty()) {
            String className = stack.pop();
            try {
                if (!classes.containsKey(className)) {
                    classes.put(className, UnsafeUtil.findAndDefineClass(className, GrossFabricHacks.class.getClassLoader()));
                }
                UnsafeUtil.initialiizeClass(classes.get(className));
            } catch (final Throwable throwable) {
                //LOGGER.log(Level.WARNING, "Failed to define KnotClassDelegateHack, retrying...", throwable);
                if (throwable instanceof NoClassDefFoundError noClassDefFoundError) {
                    stack.push(className);
                    stack.push(noClassDefFoundError.getMessage().replace("Could not initialize class ", ""));
                }
            }
        }
    }

    static {
        LOGGER.info("no good? no, this man is definitely up to evil.");

        try {
            final ClassLoader applicationClassLoader = FabricLoader.class.getClassLoader();
            KnotClassLoader = GrossFabricHacks.class.getClassLoader();

            final String[] classes = {
                "net.gudenau.lib.unsafe.Unsafe",
                "net.devtech.grossfabrichacks.instrumentation.InstrumentationAgent",
                "net.devtech.grossfabrichacks.instrumentation.InstrumentationApi",
                "net.devtech.grossfabrichacks.State",
                "net.devtech.grossfabrichacks.unsafe.UnsafeUtil",
                "net.devtech.grossfabrichacks.unsafe.UnsafeUtil$FirstInt"
            };

            final int classCount = classes.length;

            for (int i = FabricLoader.getInstance().isDevelopmentEnvironment() ? 1 : 0; i < classCount; i++) {
                final String name = classes[i];
                final InputStream classStream = KnotClassLoader.getResourceAsStream(name.replace('.', '/') + ".class");
                final byte[] bytecode = new byte[classStream.available()];

                while (classStream.read(bytecode) != -1) {}

                ClassDefiner.make().name(name).classFile(bytecode).loader(applicationClassLoader).protectionDomain(GrossFabricHacks.class.getProtectionDomain()).define();
            }

            LOGGER.log(Level.WARNING, "KnotClassLoader, you fool! Loading me was a grave mistake.");
            UnsafeUtil.findAndDefineAndInitializeClass("net.fabricmc.loader.impl.launch.knot.KnotClassLoaderHack$DynamicURLClassLoader", KnotClassLoader.getClass().getClassLoader());
            UnsafeUtil.findAndDefineClass("net.fabricmc.loader.impl.launch.knot.KnotClassDelegateHack$ClassLoaderAccess", KnotClassLoader.getClass().getClassLoader());
            UnsafeUtil.findAndDefineAndInitializeClass("net.fabricmc.loader.impl.launch.knot.KnotClassLoaderHack", KnotClassLoader.getClass().getClassLoader());
            UnsafeUtil.findAndDefineAndInitializeClass("org.spongepowered.asm.mixin.transformer.MixinTransformerHack", KnotClassLoader.getClass().getClassLoader());
            Class c = UnsafeUtil.findAndDefineClass("net.fabricmc.loader.impl.launch.knot.KnotClassDelegateHack", KnotClassLoader.getClass().getClassLoader());

            while(true) {
                try {
                    UnsafeUtil.initialiizeClass(c);
                    break;
                } catch (Throwable var7) {
                    if (var7 instanceof NoClassDefFoundError noClassDefFoundError) {
                        UnsafeUtil.findAndDefineAndInitializeClass(noClassDefFoundError.getMessage(), KnotClassLoader.getClass().getClassLoader());
                    }
                }
            }

            UNSAFE_LOADER = UnsafeUtil.defineAndInitializeAndUnsafeCast(KnotClassLoader, "net.fabricmc.loader.impl.launch.knot.UnsafeKnotClassLoader", KnotClassLoader.getClass().getClassLoader());
        } catch (final Throwable throwable) {
            throw new RuntimeException(throwable);
        }

        EntrypointUtilsHack.invoke("gfh:prePrePreLaunch", PrePrePreLaunch.class, PrePrePreLaunch::onPrePrePreLaunch);
    }
}
