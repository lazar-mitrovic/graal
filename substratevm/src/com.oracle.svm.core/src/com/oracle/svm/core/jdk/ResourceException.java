package com.oracle.svm.core.jdk;

import java.io.IOException;

public class ResourceException extends IOException {
    private static final long serialVersionUID = 8000196834066748623L;

    public ResourceException(String message) {
        super(message);
    }
}
