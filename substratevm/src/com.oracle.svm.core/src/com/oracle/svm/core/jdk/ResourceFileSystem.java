package com.oracle.svm.core.jdk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    private final long defaultTimeStamp = System.currentTimeMillis();

    private final ResourceFileSystemProvider provider;
    private final Path resourcePath;
    private final ResourcePath root;
    private boolean isOpen = true;
    private final ReadWriteLock rwlock = new ReentrantReadWriteLock();

    private final HashMap<String, Entry> entries = new HashMap<>();

    public ResourceFileSystem(ResourceFileSystemProvider provider, Path resourcePath, Map<String, ?> env) {
        this.provider = provider;
        this.resourcePath = resourcePath;
        this.root = new ResourcePath(this, new byte[]{'/'});
        if (!"true".equals(env.get("create"))) {
            throw new FileSystemNotFoundException(resourcePath.toString());
        }
    }

    private void ensureOpen() {
        if (!isOpen) {
            throw new ClosedFileSystemException();
        }
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

    @Override
    public void close() throws IOException {
        beginWrite();
        try {
            if (!isOpen) {
                return;
            }
            isOpen = false;
        } finally {
            endWrite();
        }

        provider.removeFileSystem(resourcePath, this);
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
        return Collections.singleton(root);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.singleton(new ResourceFileStore(root));
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return supportedFileAttributeViews;
    }

    Path getResourcePath() {
        return resourcePath;
    }

    @Override
    public String toString() {
        return resourcePath.toString();
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
                    if (sb.length() > 0) {
                        sb.append('/');
                    }
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
            if (option == null) {
                throw new NullPointerException();
            }
            if (!(option instanceof StandardOpenOption)) {
                throw new IllegalArgumentException();
            }
        }
    }

    // TODO: Add check if path is pointing to dir or regular file.
    // TODO: After recomposing resource storage, get with zero index will be replaced with appropriate one.
    // TODO: Need enhancement.
    public SeekableByteChannel newByteChannel(byte[] path, Set<? extends OpenOption> options, FileAttribute<?>[] attrs) throws NoSuchFileException {
        checkOptions(options);
        byte[] data = Resources.get(getString(path)).get(0);
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND)) {
            beginRead();
            try {
                final WritableByteChannel wbc = Channels.newChannel(new ByteArrayOutputStream(1024));
                long leftover = 0;
                if (options.contains(StandardOpenOption.APPEND)) {
                    Entry e = getEntry(path);
                    if (e != null && e.size >= 0)
                        leftover = e.size;
                }
                final long offset = leftover;
                return new SeekableByteChannel() {
                    long written = offset;

                    @Override
                    public boolean isOpen() {
                        return wbc.isOpen();
                    }

                    @Override
                    public long position() {
                        return written;
                    }

                    @Override
                    public SeekableByteChannel position(long pos) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int read(ByteBuffer dst) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public SeekableByteChannel truncate(long size) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int write(ByteBuffer src) throws IOException {
                        int n = wbc.write(src);
                        written += n;
                        return n;
                    }

                    @Override
                    public long size() throws IOException {
                        return written;
                    }

                    @Override
                    public void close() throws IOException {
                        wbc.close();
                    }
                };
            } finally {
                endRead();
            }
        } else {
            beginRead();
            try {
                ensureOpen();
                Entry entry = getEntry(path);
                if (entry == null || entry.isDirectory()) {
                    throw new NoSuchFileException(getString(path));
                }
                final ReadableByteChannel rbc = Channels.newChannel(new ByteArrayInputStream(data));
                return new SeekableByteChannel() {
                    long read = 0;

                    @Override
                    public boolean isOpen() {
                        return rbc.isOpen();
                    }

                    @Override
                    public long position() {
                        return read;
                    }

                    @Override
                    public SeekableByteChannel position(long pos) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int read(ByteBuffer dst) throws IOException {
                        int n = rbc.read(dst);
                        if (n > 0) {
                            read += n;
                        }
                        return n;
                    }

                    @Override
                    public SeekableByteChannel truncate(long size) {
                        throw new NonWritableChannelException();
                    }

                    @Override
                    public int write(ByteBuffer src) {
                        throw new NonWritableChannelException();
                    }

                    @Override
                    public long size() {
                        return data.length;
                    }

                    @Override
                    public void close() throws IOException {
                        rbc.close();
                    }
                };
            } finally {
                endRead();
            }
        }
    }

    // TODO: Check tree structure if the path exists.
    public boolean exists(byte[] resolvedPath) throws IOException {
        return true;
    }

    public FileStore getFileStore(ResourcePath resourcePath) {
        return new ResourceFileStore(resourcePath);
    }

    public void checkAccess(byte[] path) {
        beginRead();
        try {
            ensureOpen();
            // TODO: Access file in index structure. If it's not present, throw throw new NoSuchFileException(toString())
        } finally {
            endRead();
        }
    }

    public void setTimes(byte[] resolvedPath, FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws NoSuchFileException {
        beginWrite();
        try {
            ensureOpen();
            Entry e = getEntry(resolvedPath);
            if (e == null) {
                throw new NoSuchFileException(getString(resolvedPath));
            }
            if (lastModifiedTime != null) {
                e.lastModifiedTime = lastModifiedTime.toMillis();
            }
            if (lastAccessTime != null) {
                e.lastAccessTime = lastAccessTime.toMillis();
            }
            if (createTime != null) {
                e.createTime = createTime.toMillis();
            }
            // TODO: Update tree hierarchy;
        } finally {
            endWrite();
        }
    }

    public void createDirectory(byte[] dir, FileAttribute<?>[] attrs) throws IOException {
        beginWrite();
        try {
            ensureOpen();
            if (dir.length == 0 || exists(dir))  // root dir, or exiting dir
                throw new FileAlreadyExistsException(getString(dir));
            // TODO: Insert new node in tree like structure.
        } finally {
            endWrite();
        }

    }

    // TODO: Add support for checking if path is directory.
    // TODO: Size should be 0, if the path is directory.
    public class Entry {

        private final byte[] path;
        private final int size;
        public long lastModifiedTime;
        public long lastAccessTime;
        public long createTime;

        public Entry(byte[] path, int size) {
            this.path = path;
            this.size = size;
            this.lastModifiedTime = this.lastAccessTime = this.createTime = ResourceFileSystem.this.defaultTimeStamp;
        }

        public boolean isDirectory() {
            return false;
        }

        public long size() {
            return size;
        }
    }
}
