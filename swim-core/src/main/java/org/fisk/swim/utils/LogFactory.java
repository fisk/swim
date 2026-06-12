package org.fisk.swim.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogFactory {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public static String getCallerClassName() {
        return getCallerClass().getName();
    }

    public static Class<?> getCallerClass() {
        return WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(clazz -> clazz != LogFactory.class)
                .findFirst()
                .orElse(LogFactory.class));
    }

    public static Logger createLog() {
        return LoggerFactory.getLogger(getCallerClass());
    }
}
