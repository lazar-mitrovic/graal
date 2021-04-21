package com.oracle.svm.core.jdk;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

public class ResourceFileStore extends FileStore {

    private final FileSystem resourceFileSystem;

    ResourceFileStore(Path path) {
        this.resourceFileSystem = path.getFileSystem();
    }

    @Override
    public String name() {
        return resourceFileSystem.toString() + "/";
    }

    @Override
    public String type() {
        return "rfs";
    }

    @Override
    public boolean isReadOnly() {
        return resourceFileSystem.isReadOnly();
    }

    @Override
    public long getTotalSpace() throws IOException {
        return new ResourceFileStoreAttributes(this).totalSpace();
    }

    @Override
    public long getUsableSpace() throws IOException {
        return new ResourceFileStoreAttributes(this).usableSpace();
    }

    @Override
    public long getUnallocatedSpace() throws IOException {
        return new ResourceFileStoreAttributes(this).unallocatedSpace();
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return type == BasicFileAttributeView.class || type == ResourceAttributesView.class;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
        return name.equals("basic") || name.equals("resource");
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        if (type == null) {
            throw new NullPointerException();
        }
        return null;
    }

    @Override
    public Object getAttribute(String attribute) throws IOException {
        if (attribute.equals("totalSpace")) {
            return getTotalSpace();
        }
        if (attribute.equals("usableSpace")) {
            return getUsableSpace();
        }
        if (attribute.equals("unallocatedSpace")) {
            return getUnallocatedSpace();
        }
        throw new UnsupportedOperationException("Attribute isn't supported!");
    }

    private static class ResourceFileStoreAttributes {
        private final FileStore fileStore;
        private final long size;

        public ResourceFileStoreAttributes(ResourceFileStore fileStore) throws IOException {
            Path path = FileSystems.getDefault().getPath(fileStore.name());
            this.size = Files.size(path);
            this.fileStore = Files.getFileStore(path);
        }

        public long totalSpace() {
            return size;
        }

        public long usableSpace() throws IOException {
            return fileStore.getUsableSpace();
        }

        public long unallocatedSpace() throws IOException {
            return fileStore.getUnallocatedSpace();
        }
    }
}
