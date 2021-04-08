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
        return null;
    }

    @Override
    public FileTime lastAccessTime() {
        return null;
    }

    @Override
    public FileTime creationTime() {
        return null;
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

    @Override
    public Object fileKey() {
        return null;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(1024);
        Formatter fm = new Formatter(sb);
        fm.format("    creationTime    : null%n");
        fm.format("    lastAccessTime  : null%n");
        fm.format("    lastModifiedTime: null%n");
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
