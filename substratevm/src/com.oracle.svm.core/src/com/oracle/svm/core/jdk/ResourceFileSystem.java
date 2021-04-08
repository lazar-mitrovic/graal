package com.oracle.svm.core.jdk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

// TODO: Add support for encoding/decoding based on env parameter.
public class ResourceFileSystem extends FileSystem {

    private static final String GLOB_SYNTAX = "glob";
    private static final String REGEX_SYNTAX = "regex";

    private static final Set<String> supportedFileAttributeViews = Collections.unmodifiableSet(
                    new HashSet<>(Arrays.asList("basic", "resource")));

    private final ResourceFileSystemProvider provider;
    private boolean isOpen = true;
    private final ReadWriteLock rwlock = new ReentrantReadWriteLock();

    private final HashMap<String, Entry> entries = new HashMap<>();

    public ResourceFileSystem(ResourceFileSystemProvider provider, String path, Map<String, ?> env) {
        this.provider = provider;
        if (!"true".equals(env.get("create"))) {
            throw new FileSystemNotFoundException(path);
        }
    }

    private void ensureOpen() {
        if (!isOpen)
            throw new ClosedFileSystemException();
    }

    private void beginWrite() {
        rwlock.writeLock().lock();
    }

    private void endWrite() {
        rwlock.writeLock().unlock();
    }

    private void beginRead() {
        rwlock.readLock().lock();
    }

    private void endRead() {
        rwlock.readLock().unlock();
    }

    // TODO: Replace body of this method with call of adequate encoder class.
    byte[] getBytes(String path) {
        return path.getBytes(StandardCharsets.UTF_8);
    }

    // TODO: Replace body of this method with call of adequate decoder class.
    String getString(byte[] path) {
        return new String(path, StandardCharsets.UTF_8);
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    // TODO: When file system is closed, we need to remove it from provider's cache.
    @Override
    public void close() throws IOException {
        beginWrite();
        try {
            if (!isOpen)
                return;
            isOpen = false;
        } finally {
            endWrite();
        }
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singleton(new ResourcePath(this, new byte[]{'/'}));
    }

    // TODO: Implement FileStore.
    @Override
    public Iterable<FileStore> getFileStores() {
        return null;
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return supportedFileAttributeViews;
    }

    @Override
    public Path getPath(String first, String... more) {
        String path;
        if (more.length == 0) {
            path = first;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(first);
            for (String segment : more) {
                if (segment.length() > 0) {
                    if (sb.length() > 0)
                        sb.append('/');
                    sb.append(segment);
                }
            }
            path = sb.toString();
        }
        return new ResourcePath(this, getBytes(path));
    }

    // TODO: Implementation.
    public static String toRegexPattern(String globPattern) {
        return null;
    }

    // TODO: Test this method.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int pos = syntaxAndPattern.indexOf(':');
        if (pos <= 0 || pos == syntaxAndPattern.length()) {
            throw new IllegalArgumentException();
        }
        String syntax = syntaxAndPattern.substring(0, pos);
        String input = syntaxAndPattern.substring(pos + 1);
        String expr;
        if (syntax.equals(GLOB_SYNTAX)) {
            expr = toRegexPattern(input);
        } else {
            if (syntax.equals(REGEX_SYNTAX)) {
                expr = input;
            } else {
                throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized");
            }
        }

        Pattern pattern = Pattern.compile(expr);
        return path -> pattern.matcher(path.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    // TODO: After recomposing resource storage, get with zero index will be replaced with
    // appropriate one.
    private Entry getEntry(byte[] path) {
        String pathString = getString(path);
        Entry entry = entries.get(pathString);
        if (entry == null) {
            entry = new Entry(path, Resources.get(pathString).get(0).length);
            entries.put(pathString, entry);
        }
        return entry;
    }

    public ResourceAttributes getFileAttributes(byte[] path) {
        Entry entry;
        beginRead();
        try {
            ensureOpen();
            entry = getEntry(path);
        } finally {
            endRead();
        }
        return new ResourceAttributes(entry);
    }

    private void checkOptions(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option == null)
                throw new NullPointerException();
            if (!(option instanceof StandardOpenOption))
                throw new IllegalArgumentException();
        }
    }

    private void checkPermissions(Set<? extends OpenOption> options) {
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND)) {
            throw new ReadOnlyFileSystemException();
        }
    }

    // TODO: Add check if path is pointing to dir of regular file.
    // TODO: After recomposing resource storage, get with zero index will be replaced with
    // appropriate one.
    public SeekableByteChannel newByteChannel(byte[] resolvedPath, Set<? extends OpenOption> options, FileAttribute<?>[] attrs) {
        checkOptions(options);
        checkPermissions(options);
        beginRead();
        ensureOpen();
        byte[] data = Resources.get(getString(resolvedPath)).get(0);
        try {
            final ReadableByteChannel rbc = Channels.newChannel(new ByteArrayInputStream(data));
            return new SeekableByteChannel() {
                long read = 0;

                public boolean isOpen() {
                    return rbc.isOpen();
                }

                public long position() {
                    return read;
                }

                public SeekableByteChannel position(long pos) {
                    throw new UnsupportedOperationException();
                }

                public int read(ByteBuffer dst) throws IOException {
                    int n = rbc.read(dst);
                    if (n > 0) {
                        read += n;
                    }
                    return n;
                }

                public SeekableByteChannel truncate(long size) {
                    throw new NonWritableChannelException();
                }

                public int write(ByteBuffer src) {
                    throw new NonWritableChannelException();
                }

                public long size() {
                    return data.length;
                }

                public void close() throws IOException {
                    rbc.close();
                }
            };
        } finally {
            endRead();
        }
    }

    // TODO: Add support for checking if path is directory.
    // TODO: Size should be 0, if the path is directory.
    public static class Entry {

        private final byte[] path;
        private final int size;

        public Entry(byte[] path, int size) {
            this.path = path;
            this.size = size;
        }

        public boolean isDirectory() {
            return false;
        }

        public long size() {
            return size;
        }
    }
}
