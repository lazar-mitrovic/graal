package com.oracle.svm.core.jdk;

import org.graalvm.collections.MapCursor;

import java.util.Arrays;
import java.util.List;

public class ResourceStorage {

    private ResourceStorage() {
    }

    public static MapCursor<String, List<byte[]>> iterator() {
        return Resources.singleton().resources().getEntries();
    }

    public static byte[] getBytes(String resourceName, boolean readOnly) {
        List<byte[]> listBytes = Resources.singleton().resources().get(resourceName);
        if (listBytes == null) {
            return new byte[0];
        }
        byte[] bytes = listBytes.get(0);
        if (readOnly) {
            return bytes;
        } else {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    public int getSize(String resourceName) {
        return Resources.singleton().resources().get(resourceName).get(0).length;
    }
}
