package io.github.hhenson.hgl;

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.PluginSet;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.future.FutureKt;

import java.util.Collections;

/** Run outside the test fixture, with only the product boot jars and this probe on the classpath. */
public final class InstalledDebuggerProbe {
    public static void main(String[] args) throws Exception {
        var manager = PluginManagerCore.INSTANCE;
        manager.scheduleDescriptorLoading(CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault()));
        PluginSet plugins = FutureKt.asCompletableFuture(manager.getInitPluginFuture()).get();

        Object runner = extension(plugins, "com.intellij.programRunner", "HglDebugRunner");
        runner.getClass().getClassLoader().loadClass("com.intellij.platform.dap.DapProcessStarter");
        Object provider = extension(plugins, "com.intellij.platform.dap.debugAdapterSupportProvider", "HglAdapterProvider");
        provider.getClass().getMethod("getAdapterId").invoke(provider);
        extension(plugins, "com.intellij.xdebugger.breakpointType", "HglLineBreakpoint");
    }

    private static Object extension(PluginSet plugins, String point, String simpleName) throws Exception {
        String className = "io.github.hhenson.hgl.debug." + simpleName;
        var descriptors = plugins.sequenceResolvedSortedDescriptorsForRegistration().iterator();
        while (descriptors.hasNext()) {
            IdeaPluginDescriptorImpl descriptor = descriptors.next();
            if (!descriptor.getPluginId().getIdString().equals("io.github.hhenson.hgl")) continue;
            for (var extension : descriptor.getExtensions().getOrDefault(point, Collections.emptyList())) {
                if (className.equals(extension.implementation)) {
                    return descriptor.getPluginClassLoader().loadClass(className).getConstructor().newInstance();
                }
            }
        }
        throw new AssertionError("Missing HGL extension " + className + " for " + point);
    }
}
