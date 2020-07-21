package com.oracle.svm.driver;

import java.util.List;
import java.util.Queue;

abstract class OptionHandler<T extends NativeImage> {
    protected final T nativeImage;

    OptionHandler(T nativeImage) {
        this.nativeImage = nativeImage;
    }

    abstract boolean consume(Queue<String> args);

    void addFallbackBuildArgs(@SuppressWarnings("unused") List<String> buildArgs) {
        /* Override to forward fallback relevant args */
    }
}
