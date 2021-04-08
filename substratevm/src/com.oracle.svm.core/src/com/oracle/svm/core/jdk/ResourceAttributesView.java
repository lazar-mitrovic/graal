package com.oracle.svm.core.jdk;

import java.io.IOException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResourceAttributesView implements BasicFileAttributeView {

    private enum AttributeID
    {
        size,
        creationTime,
        lastAccessTime,
        lastModifiedTime,
        isDirectory,
        isRegularFile,
        isSymbolicLink,
        isOther,
        fileKey,
    }

    private final ResourcePath path;
    private final boolean isBasic;

    public ResourceAttributesView(ResourcePath path, boolean isBasic) {
        this.path = path;
        this.isBasic = isBasic;
    }

    @SuppressWarnings("unchecked")
    static <V extends FileAttributeView> V get(ResourcePath path, Class<V> type) {
        if (type == null)
            throw new NullPointerException();
        if (type == BasicFileAttributeView.class)
            return (V) new ResourceAttributesView(path, true);
        if (type == ResourceAttributesView.class)
            return (V) new ResourceAttributesView(path, false);
        return null;
    }

    static ResourceAttributesView get(ResourcePath path, String type) {
        if (type == null)
            throw new NullPointerException();
        if (type.equals("basic"))
            return new ResourceAttributesView(path, true);
        if (type.equals("resource"))
            return new ResourceAttributesView(path, false);
        return null;
    }

    @Override
    public String name() {
        return isBasic ? "basic" : "resource";
    }

    @Override
    public ResourceAttributes readAttributes() throws IOException {
        return path.getAttributes();
    }

    Map<String, Object> readAttributes(String attributes) throws IOException {
        ResourceAttributes resourceAttributes = readAttributes();
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        if ("*".equals(attributes)) {
            for (AttributeID id : AttributeID.values()) {
                try {
                    map.put(id.name(), attribute(id, resourceAttributes));
                } catch (IllegalArgumentException x) {
                    // Silently handle this exception.
                }
            }
        } else {
            String[] as = attributes.split(",");
            for (String a : as) {
                try {
                    map.put(a, attribute(AttributeID.valueOf(a), resourceAttributes));
                } catch (IllegalArgumentException x) {
                    // Silently handle this exception.
                }
            }
        }
        return map;
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    Object attribute(AttributeID id, ResourceAttributes resourceAttributes) {
        switch (id) {
            case size:
                return resourceAttributes.size();
            case creationTime:
                return resourceAttributes.creationTime();
            case lastAccessTime:
                return resourceAttributes.lastAccessTime();
            case lastModifiedTime:
                return resourceAttributes.lastModifiedTime();
            case isDirectory:
                return resourceAttributes.isDirectory();
            case isRegularFile:
                return resourceAttributes.isRegularFile();
            case isSymbolicLink:
                return resourceAttributes.isSymbolicLink();
            case isOther:
                return resourceAttributes.isOther();
            case fileKey:
                return resourceAttributes.fileKey();
        }
        return null;
    }
}
