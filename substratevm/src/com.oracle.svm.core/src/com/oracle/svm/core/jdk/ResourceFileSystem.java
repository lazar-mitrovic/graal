package com.oracle.svm.core.jdk;

import org.graalvm.collections.MapCursor;

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
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    private static final byte[] ROOT_PATH = new byte[]{'/'};
    private final IndexNode LOOKUP_KEY = new IndexNode(null, true);
    LinkedHashMap<IndexNode, IndexNode> inodes = new LinkedHashMap<>(10);

    public ResourceFileSystem(ResourceFileSystemProvider provider, Path resourcePath, Map<String, ?> env) {
        this.provider = provider;
        this.resourcePath = resourcePath;
        this.root = new ResourcePath(this, new byte[]{'/'});
        if (!"true".equals(env.get("create"))) {
            throw new FileSystemNotFoundException(resourcePath.toString());
        }
        readAllEntries();
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
        return false;
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

    // TODO: After recomposing resource storage, get with zero index will be replaced with appropriate one.
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
            entry = getEntry0(path);
            if (entry == null) {
                return null;
            }
        } finally {
            endRead();
        }
        return new ResourceAttributes(this, entry);
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

    public boolean exists(byte[] path) throws IOException {
        beginRead();
        try {
            ensureOpen();
            return getInode(path) != null;
        } finally {
            endRead();
        }
    }

    public FileStore getFileStore(ResourcePath resourcePath) {
        return new ResourceFileStore(resourcePath);
    }

    public void checkAccess(byte[] path) throws NoSuchFileException {
        beginRead();
        try {
            ensureOpen();
            if (getInode(path) == null) {
                throw new NoSuchFileException(toString());
            }
        } finally {
            endRead();
        }
    }

    public void setTimes(byte[] path, FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws NoSuchFileException {
        beginWrite();
        try {
            ensureOpen();
            Entry e = getEntry0(path);
            if (e == null) {
                throw new NoSuchFileException(getString(path));
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
            update(e);
        } finally {
            endWrite();
        }
    }

    boolean isDirectory(byte[] path) {
        beginRead();
        try {
            IndexNode n = getInode(path);
            return n != null && n.isDir();
        } finally {
            endRead();
        }
    }

    public void createDirectory(byte[] dir, FileAttribute<?>... attrs) throws IOException {
        beginWrite();
        try {
            ensureOpen();
            if (dir.length == 0 || exists(dir)) {
                throw new FileAlreadyExistsException(getString(dir));
            }
            checkParents(dir);
            Entry e = new Entry(dir, Entry.NEW, true);
            update(e);
        } finally {
            endWrite();
        }
    }

    public boolean deleteFile(byte[] path, boolean failIfNotExists) throws IOException {
        IndexNode inode = getInode(path);
        if (inode == null) {
            if (path != null && path.length == 0) {
                throw new ResourceException("Root directory </> can't not be deleted!");
            }
            if (failIfNotExists) {
                throw new NoSuchFileException(getString(path));
            } else {
                return false;
            }
        } else {
            if (inode.isDir() && inode.child != null) {
                throw new DirectoryNotEmptyException(getString(path));
            }
            updateDelete(inode);
        }
        return true;
    }

    // TODO: Implementation.
    public void copyFile(boolean deleteSource, byte[] src, byte[] dst, CopyOption[] options) throws IOException {
    }

    private void checkParents(byte[] path) throws IOException {
        beginRead();
        try {
            while ((path = getParent(path)) != null && path != ROOT_PATH) {
                if (!inodes.containsKey(IndexNode.keyOf(path))) {
                    throw new NoSuchFileException(getString(path));
                }
            }
        } finally {
            endRead();
        }
    }

    IndexNode getInode(byte[] path) {
        if (path == null) {
            throw new NullPointerException("Path is null!");
        }
        return inodes.get(IndexNode.keyOf(path));
    }

    Entry getEntry0(byte[] path) {
        IndexNode inode = getInode(path);
        if (inode instanceof Entry) {
            return (Entry) inode;
        }
        if (inode == null) {
            return null;
        }
        return new Entry(inode.name, inode.isDir);
    }

    static byte[] getParent(byte[] path) {
        int off = getParentOff(path);
        if (off <= 1) {
            return ROOT_PATH;
        }
        return Arrays.copyOf(path, off);
    }

    private static int getParentOff(byte[] path) {
        int off = path.length - 1;
        if (off > 0 && path[off] == '/') {
            off--;
        }
        while (off > 0 && path[off] != '/') {
            off--;
        }
        return off;
    }

    private void removeFromTree(IndexNode inode) {
        IndexNode parent = inodes.get(LOOKUP_KEY.as(getParent(inode.name)));
        IndexNode child = parent.child;
        if (child.equals(inode)) {
            parent.child = child.sibling;
        } else {
            IndexNode last = child;
            while ((child = child.sibling) != null) {
                if (child.equals(inode)) {
                    last.sibling = child.sibling;
                    break;
                } else {
                    last = child;
                }
            }
        }
    }

    private void updateDelete(IndexNode inode) {
        beginWrite();
        try {
            removeFromTree(inode);
            inodes.remove(inode);
        } finally {
            endWrite();
        }
    }

    private void update(Entry e) {
        beginWrite();
        try {
            IndexNode old = inodes.put(e, e);
            if (old != null) {
                removeFromTree(old);
            }
            if (e.type == Entry.NEW || e.type == Entry.FILE_CH || e.type == Entry.COPY) {
                IndexNode parent = inodes.get(LOOKUP_KEY.as(getParent(e.name)));
                e.sibling = parent.child;
                parent.child = e;
            }
        } finally {
            endWrite();
        }
    }

    boolean isDir(byte[] name) {
        return name != null && (name.length == 0 || name[name.length - 1] == '/');
    }

    private void readAllEntries() {
        MapCursor<String, List<byte[]>> entries = Resources.singleton().resources().getEntries();
        while (entries.advance()) {
            byte[] name = getBytes(entries.getKey());
            boolean isDir = isDir(name);
            if (!isDir) {
                IndexNode newIndexNode = new IndexNode(name, false);
                inodes.put(newIndexNode, newIndexNode);
            }
        }
        buildNodeTree();
    }

    private void buildNodeTree() {
        beginWrite();
        try {
            IndexNode root = inodes.get(LOOKUP_KEY.as(ROOT_PATH));
            if (root == null) {
                root = new IndexNode(ROOT_PATH, true);
            } else {
                inodes.remove(root);
            }
            IndexNode[] nodes = inodes.keySet().toArray(new IndexNode[0]);
            inodes.put(root, root);
            ParentLookup lookup = new ParentLookup();
            for (IndexNode node : nodes) {
                IndexNode parent;
                while (true) {
                    int off = getParentOff(node.name);
                    if (off <= 1) {    // parent is root
                        node.sibling = root.child;
                        root.child = node;
                        break;
                    }
                    lookup = lookup.as(node.name, off);
                    if (inodes.containsKey(lookup)) {
                        parent = inodes.get(lookup);
                        node.sibling = parent.child;
                        parent.child = node;
                        break;
                    }
                    // add new pseudo directory entry
                    parent = new IndexNode(Arrays.copyOf(node.name, off), true);
                    inodes.put(parent, parent);
                    node.sibling = parent.child;
                    parent.child = node;
                    node = parent;
                }
            }
        } finally {
            endWrite();
        }
    }

    // Temporary, for debug purpose.
    void printTree() {
        System.out.println(">>> Tree...");
        IndexNode node = inodes.get(LOOKUP_KEY.as(ROOT_PATH));
        node = node.child;
        ArrayList<IndexNode> queue = new ArrayList<>();
        queue.add(node);
        while (!queue.isEmpty()) {
            node = queue.remove(0);
            while (node != null) {
                System.out.println(getString(node.name) + " " + node.isDir);
                queue.add(node.child);
                node = node.sibling;
            }
            System.out.println("------");
        }
    }

    private static class IndexNode {

        private static final ThreadLocal<IndexNode> cachedKey = new ThreadLocal<>();

        byte[] name;
        int hashcode;
        boolean isDir;

        IndexNode child;
        IndexNode sibling;

        IndexNode() {
        }

        IndexNode(byte[] name) {
            name(name);
        }

        IndexNode(byte[] name, boolean isDir) {
            name(name);
            this.isDir = isDir;
        }

        // TODO: Need further testing.
        private byte[] normalize(byte[] path) {
            int len = path.length;
            if (len == 0)
                return path;
            byte prevC = 0;
            for (int pathPos = 0; pathPos < len; pathPos++) {
                byte c = path[pathPos];
                if (c == '/' && prevC == '/') {
                    return normalize(path, pathPos - 1);
                }
                prevC = c;
            }
            if (len > 1 && prevC == '/') {
                return Arrays.copyOf(path, len - 1);
            }
            return path;
        }

        private byte[] normalize(byte[] path, int off) {
            byte[] to = new byte[path.length - 1];
            int pathPos = 0;
            while (pathPos < off) {
                to[pathPos] = path[pathPos];
                pathPos++;
            }
            int toPos = pathPos;
            byte prevC = 0;
            while (pathPos < path.length) {
                byte c = path[pathPos++];
                if (c == '/' && prevC == '/') {
                    continue;
                }
                to[toPos++] = c;
                prevC = c;
            }
            if (toPos > 1 && to[toPos - 1] == '/') {
                toPos--;
            }
            return (toPos == to.length) ? to : Arrays.copyOf(to, toPos);
        }

        static IndexNode keyOf(byte[] name) {
            IndexNode key = cachedKey.get();
            if (key == null) {
                key = new IndexNode(name);
                cachedKey.set(key);
            }
            return key.as(name);
        }

        final void name(byte[] name) {
            this.name = name;
            this.hashcode = Arrays.hashCode(name);
        }

        final IndexNode as(byte[] name) {
            name(name);
            return this;
        }

        boolean isDir() {
            return isDir;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof IndexNode)) {
                return false;
            }
            if (other instanceof ParentLookup) {
                return other.equals(this);
            }
            return Arrays.equals(name, ((IndexNode) other).name);
        }

        @Override
        public int hashCode() {
            return hashcode;
        }
    }

    // For parent lookup, so we don't have to copy the parent name every time.
    private static class ParentLookup extends IndexNode {

        int length;

        ParentLookup() {
        }

        ParentLookup as(byte[] name, int length) {
            name(name, length);
            return this;
        }

        void name(byte[] name, int length) {
            this.name = name;
            this.length = length;
            int result = 1;
            for (int i = 0; i < length; i++) {
                result = 31 * result + name[i];
            }
            this.hashcode = result;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof IndexNode)) {
                return false;
            }
            byte[] otherName = ((IndexNode) other).name;
            return Arrays.equals(name, 0, length, otherName, 0, otherName.length);
        }
    }

    class Entry extends IndexNode {

        private static final int NEW = 1;       // Updated contents in bytes or file.
        private static final int FILE_CH = 2;   // File channel update in file.
        private static final int COPY = 3;      // Copy entry.

        private int size;
        public int type;
        public long lastModifiedTime;
        public long lastAccessTime;
        public long createTime;

        public Entry(byte[] name, int size) {
            name(name);
            this.size = size;
            this.lastModifiedTime = this.lastAccessTime = this.createTime = ResourceFileSystem.this.defaultTimeStamp;
        }

        public Entry(byte[] name, boolean isDir) {
            name(name);
            this.type = Entry.NEW;
            this.isDir = isDir;
            this.lastModifiedTime = this.lastAccessTime = this.createTime = ResourceFileSystem.this.defaultTimeStamp;
        }

        public Entry(byte[] name, int type, boolean isDir) {
            name(name);
            this.type = type;
            this.isDir = isDir;
        }

        public boolean isDirectory() {
            return isDir;
        }

        // TODO: Maybe it will be better to do this in another way.
        public long size() {
            return !isDir ? size : 0;
        }
    }
}
