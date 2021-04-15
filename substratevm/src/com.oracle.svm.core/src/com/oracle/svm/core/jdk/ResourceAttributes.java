package com.oracle.svm.core.jdk;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Formatter;

public class ResourceAttributes implements BasicFileAttributes {

    private final ResourceFileSystem.Entry entry;

    public ResourceAttributes(ResourceFileSystem.Entry entry) {
        this.entry = entry;
    }

    @Override
    public FileTime lastModifiedTime() {
        return FileTime.fromMillis(entry.lastModifiedTime);
    }

    @Override
    public FileTime lastAccessTime() {
        return FileTime.fromMillis(entry.lastAccessTime);
    }

    @Override
    public FileTime creationTime() {
        return FileTime.fromMillis(entry.createTime);
    }

    @Override
    public boolean isRegularFile() {
        return !entry.isDirectory();
    }

    @Override
    public boolean isDirectory() {
        return entry.isDirectory();
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return entry.size();
    }

    // TODO: Need further thinking.
    @Override
    public Object fileKey() {
        return null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(1024);
        Formatter fm = new Formatter(sb);
        fm.format("    creationTime    : %tc%n", creationTime().toMillis());
        fm.format("    lastAccessTime  : %tc%n", lastAccessTime().toMillis());
        fm.format("    lastModifiedTime: %tc%n", lastModifiedTime().toMillis());
        fm.format("    isRegularFile   : %b%n", isRegularFile());
        fm.format("    isDirectory     : %b%n", isDirectory());
        fm.format("    isSymbolicLink  : %b%n", isSymbolicLink());
        fm.format("    isOther         : %b%n", isOther());
        fm.format("    fileKey         : %s%n", fileKey());
        fm.format("    size            : %d%n", size());
        fm.close();
        return sb.toString();
    }
}
