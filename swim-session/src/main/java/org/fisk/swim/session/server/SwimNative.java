package org.fisk.swim.session.server;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class SwimNative {
    private static final MethodHandle SETSID = lookupSetsid();

    private SwimNative() {
    }

    static int setsid() {
        try {
            return (int) SETSID.invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException("setsid failed", e);
        }
    }

    private static MethodHandle lookupSetsid() {
        try {
            return Linker.nativeLinker().downcallHandle(
                    SymbolLookup.loaderLookup().find("setsid")
                            .or(() -> Linker.nativeLinker().defaultLookup().find("setsid"))
                            .orElseThrow(() -> new IllegalStateException("setsid not found")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Unable to link setsid", e);
        }
    }
}
