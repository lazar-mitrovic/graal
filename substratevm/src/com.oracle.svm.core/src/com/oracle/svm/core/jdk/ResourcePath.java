package com.oracle.svm.core.jdk;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class ResourcePath implements Path {

    private final ResourceFileSystem fileSystem;
    private final byte[] path;
    private volatile int[] offsets;
    private byte[] resolved;
    private int hashcode = 0;

    public ResourcePath(ResourceFileSystem fileSystem, byte[] path) {
        this(fileSystem, path, false);
    }

    public ResourcePath(ResourceFileSystem fileSystem, byte[] path, boolean normalized) {
        this.fileSystem = fileSystem;
        if (normalized) {
            this.path = path;
        } else {
            this.path = normalize(path);
        }
    }

    private void initOffsets() {
        if (this.offsets == null) {
            int count = 0;
            int index = 0;
            while (index < path.length) {
                byte c = path[index++];
                if (c != '/') {
                    count++;
                    while (index < path.length && path[index] != '/') {
                        index++;
                    }
                }
            }
            int[] result = new int[count];
            count = 0;
            index = 0;
            while (index < path.length) {
                int m = path[index];
                if (m == '/') {
                    index++;
                } else {
                    result[count++] = index++;
                    while (index < path.length && path[index] != '/') {
                        index++;
                    }
                }
            }
            synchronized (this) {
                if (offsets == null) {
                    offsets = result;
                }
            }
        }
    }

    @Override
    public ResourceFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return (this.path.length > 0 && path[0] == '/');
    }

    @Override
    public ResourcePath getRoot() {
        if (isAbsolute()) {
            return new ResourcePath(fileSystem, new byte[]{this.path[0]});
        }
        return null;
    }

    @Override
    public Path getFileName() {
        initOffsets();
        int nbOffsets = offsets.length;
        if (nbOffsets == 0) {
            return null;
        }
        if (nbOffsets == 1 && path[0] != '/') {
            return this;
        }
        int offset = offsets[nbOffsets - 1];
        int length = path.length - offset;
        byte[] path = new byte[length];
        System.arraycopy(this.path, offset, path, 0, length);
        return new ResourcePath(fileSystem, path);
    }

    @Override
    public ResourcePath getParent() {
        initOffsets();
        int nbOffsets = offsets.length;
        if (nbOffsets == 0) {
            return null;
        }
        int length = offsets[nbOffsets - 1] - 1;
        if (length <= 0) {
            return getRoot();
        }
        byte[] path = new byte[length];
        System.arraycopy(this.path, 0, path, 0, length);
        return new ResourcePath(fileSystem, path);
    }

    @Override
    public int getNameCount() {
        initOffsets();
        return offsets.length;
    }

    @Override
    public Path getName(int index) {
        initOffsets();
        if (index < 0 || index >= offsets.length) {
            throw new IllegalArgumentException();
        }
        int begin = offsets[index];
        int len;
        if (index == (offsets.length - 1)) {
            len = path.length - begin;
        } else
            len = offsets[index + 1] - begin - 1;

        byte[] result = new byte[len];
        System.arraycopy(path, begin, result, 0, len);
        return new ResourcePath(fileSystem, result);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        initOffsets();
        if (beginIndex < 0 ||
                        beginIndex >= offsets.length ||
                        endIndex > offsets.length ||
                        beginIndex >= endIndex) {
            throw new IllegalArgumentException();
        }

        int begin = offsets[beginIndex];
        int len;
        if (endIndex == offsets.length) {
            len = path.length - begin;
        } else
            len = offsets[endIndex] - begin - 1;

        byte[] result = new byte[len];
        System.arraycopy(path, begin, result, 0, len);
        return new ResourcePath(fileSystem, result);
    }

    @Override
    public boolean startsWith(Path other) {
        ResourcePath p1 = this;
        ResourcePath p2 = checkPath(other);
        if (p1.isAbsolute() != p2.isAbsolute() || p1.path.length < p2.path.length) {
            return false;
        }
        int length = p2.path.length;
        for (int idx = 0; idx < length; idx++) {
            if (p1.path[idx] != p2.path[idx]) {
                return false;
            }
        }
        return p1.path.length == p2.path.length || p2.path[length - 1] == '/' || p1.path[length] == '/';
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(getFileSystem().getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        ResourcePath p1 = this;
        ResourcePath p2 = checkPath(other);
        int i1 = p1.path.length - 1;
        if (i1 > 0 && p1.path[i1] == '/') {
            i1--;
        }
        int i2 = p2.path.length - 1;
        if (i2 > 0 && p2.path[i2] == '/') {
            i2--;
        }
        if (i2 == -1) {
            return i1 == -1;
        }
        if ((p2.isAbsolute() && (!isAbsolute() || i2 != i1)) || (i1 < i2)) {
            return false;
        }
        for (; i2 >= 0; i1--) {
            if (p2.path[i2] != p1.path[i1]) {
                return false;
            }
            i2--;
        }
        return (p2.path[i2 + 1] == '/');
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(getFileSystem().getPath(other));
    }

    @Override
    public Path normalize() {
        byte[] p = getResolved();
        if (p == this.path) {
            return this;
        }
        return new ResourcePath(fileSystem, p, true);
    }

    @Override
    public Path resolve(Path other) {
        ResourcePath p1 = this;
        ResourcePath p2 = checkPath(other);
        if (p2.isAbsolute()) {
            return p2;
        }
        byte[] result;
        if (p1.path[p1.path.length - 1] == '/') {
            result = new byte[p1.path.length + p2.path.length];
            System.arraycopy(p1.path, 0, result, 0, p1.path.length);
            System.arraycopy(p2.path, 0, result, p1.path.length, p2.path.length);
        } else {
            result = new byte[p1.path.length + 1 + p2.path.length];
            System.arraycopy(p1.path, 0, result, 0, p1.path.length);
            result[p1.path.length] = '/';
            System.arraycopy(p2.path, 0, result, p1.path.length + 1, p2.path.length);
        }
        return new ResourcePath(fileSystem, result);
    }

    private ResourcePath checkPath(Path paramPath) {
        if (paramPath == null) {
            throw new NullPointerException();
        }
        if (!(paramPath instanceof ResourcePath)) {
            throw new ProviderMismatchException();
        }
        return (ResourcePath) paramPath;
    }

    @Override
    public Path resolve(String other) {
        return resolve(getFileSystem().getPath(other));
    }

    @Override
    public Path resolveSibling(Path other) {
        if (other == null) {
            throw new NullPointerException();
        }
        ResourcePath parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(getFileSystem().getPath(other));
    }

    @Override
    public Path relativize(Path other) {
        ResourcePath p1 = this;
        ResourcePath p2 = checkPath(other);
        if (p2.equals(p1)) {
            return new ResourcePath(fileSystem, new byte[0], true);
        }
        if (p1.isAbsolute() != p2.isAbsolute()) {
            throw new IllegalArgumentException();
        }
        // Check how many segments are common
        int nbNames1 = p1.getNameCount();
        int nbNames2 = p2.getNameCount();
        int l = Math.min(nbNames1, nbNames2);
        int nbCommon = 0;
        while (nbCommon < l && equalsNameAt(p1, p2, nbCommon)) {
            nbCommon++;
        }
        int nbUp = nbNames1 - nbCommon;
        // Compute the resulting length
        int length = nbUp * 3 - 1;
        if (nbCommon < nbNames2) {
            length += p2.path.length - p2.offsets[nbCommon] + 1;
        }
        // Compute result
        byte[] result = new byte[length];
        int idx = 0;
        while (nbUp-- > 0) {
            result[idx++] = '.';
            result[idx++] = '.';
            if (idx < length) {
                result[idx++] = '/';
            }
        }
        // Copy remaining segments
        if (nbCommon < nbNames2) {
            System.arraycopy(p2.path, p2.offsets[nbCommon], result, idx, p2.path.length - p2.offsets[nbCommon]);
        }
        return new ResourcePath(fileSystem, result);
    }

    // TODO: Support URI.
    @Override
    public URI toUri() {
        return null;
    }

    // TODO: Test method.
    @Override
    public ResourcePath toAbsolutePath() {
        if (isAbsolute()) {
            return this;
        }
        byte[] result = new byte[path.length + 1];
        result[0] = '/';
        System.arraycopy(path, 0, result, 1, path.length);
        return new ResourcePath(fileSystem, result, true);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        ResourcePath absolute = new ResourcePath(fileSystem, getResolvedPath()).toAbsolutePath();
        fileSystem.provider().checkAccess(absolute);
        return absolute;
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Path> iterator() {
        return new Iterator<Path>() {
            private int i = 0;

            @Override
            public boolean hasNext() {
                return (i < getNameCount());
            }

            @Override
            public Path next() {
                if (i < getNameCount()) {
                    Path result = getName(i);
                    i++;
                    return result;
                } else {
                    throw new NoSuchElementException();
                }
            }

            @Override
            public void remove() {
                throw new ReadOnlyFileSystemException();
            }
        };
    }

    @Override
    public int compareTo(Path other) {
        ResourcePath p1 = this;
        ResourcePath p2 = checkPath(other);
        byte[] a1 = p1.path;
        byte[] a2 = p2.path;
        int l1 = a1.length;
        int l2 = a2.length;
        for (int i = 0, l = Math.min(l1, l2); i < l; i++) {
            int b1 = a1[i] & 0xFF;
            int b2 = a2[i] & 0xFF;
            if (b1 != b2) {
                return b1 - b2;
            }
        }
        return l1 - l2;
    }

    @Override
    public int hashCode() {
        int h = hashcode;
        if (h == 0) {
            hashcode = h = Arrays.hashCode(path);
        }
        return h;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ResourcePath &&
                        this.fileSystem == ((ResourcePath) obj).fileSystem &&
                        compareTo((Path) obj) == 0;
    }

    @Override
    public String toString() {
        return fileSystem.getString(path);
    }

    public SeekableByteChannel newByteChannel(Set<? extends OpenOption> options, FileAttribute<?>[] attrs) {
        return fileSystem.newByteChannel(getResolvedPath(), options, attrs);
    }

    public DirectoryStream<Path> newDirectoryStream(DirectoryStream.Filter<? super Path> filter) {
        return new ResourceDirectoryStream(this, filter);
    }

    public ResourceAttributes getAttributes() throws NoSuchFileException {
        ResourceAttributes resourceAttributes = fileSystem.getFileAttributes(getResolvedPath());
        if (resourceAttributes == null) {
            throw new NoSuchFileException(toString());
        }
        return resourceAttributes;
    }

    public Map<String, Object> readAttributes(String attributes, LinkOption[] options) throws IOException {
        String view;
        String attrs;
        int colonPos = attributes.indexOf(':');
        if (colonPos == -1) {
            view = "basic";
            attrs = attributes;
        } else {
            view = attributes.substring(0, colonPos++);
            attrs = attributes.substring(colonPos);
        }
        ResourceAttributesView raw = ResourceAttributesView.get(this, view);
        if (raw == null) {
            throw new UnsupportedOperationException("View is not supported!");
        }
        return raw.readAttributes(attrs);
    }

    byte[] getResolvedPath() {
        byte[] r = resolved;
        if (r == null) {
            if (isAbsolute()) {
                r = getResolved();
            } else
                r = toAbsolutePath().getResolvedPath();
            if (r[0] == '/') {
                r = Arrays.copyOfRange(r, 1, r.length);
            }
            resolved = r;
        }
        return resolved;
    }

    private byte[] normalize(byte[] path) {
        if (path.length == 0) {
            return path;
        }
        int i = 0;
        for (int j = 0; j < path.length; j++) {
            int k = path[j];
            if (k == '\\') {
                return normalize(path, j);
            }
            if ((k == '/') && (i == '/')) {
                return normalize(path, j - 1);
            }
            if (k == 0) {
                throw new InvalidPathException(fileSystem.getString(path), "Path: nul character not allowed");
            }
            i = k;
        }
        return path;
    }

    private byte[] normalize(byte[] path, int index) {
        byte[] arrayOfByte = new byte[path.length];
        int i = 0;
        while (i < index) {
            arrayOfByte[i] = path[i];
            i++;
        }
        int j = i;
        int k = 0;
        while (i < path.length) {
            int m = path[i++];
            if (m == '\\') {
                m = '/';
            }
            if ((m != '/') || (k != '/')) {
                if (m == 0) {
                    throw new InvalidPathException(fileSystem.getString(path), "Path: nul character not allowed");
                }
                arrayOfByte[j++] = (byte) m;
                k = m;
            }
        }
        if ((j > 1) && (arrayOfByte[j - 1] == '/')) {
            j--;
        }
        return j == arrayOfByte.length ? arrayOfByte : Arrays.copyOf(arrayOfByte, j);
    }

    private byte[] getResolved() {
        if (path.length == 0) {
            return path;
        }
        for (byte c : path) {
            if (c == '.') {
                return doGetResolved(this);
            }
        }
        return path;
    }

    private static byte[] doGetResolved(ResourcePath p) {
        int nc = p.getNameCount();
        byte[] path = p.path;
        int[] offsets = p.offsets;
        byte[] to = new byte[path.length];
        int[] lastM = new int[nc];
        int lastMOff = -1;
        int m = 0;
        for (int i = 0; i < nc; i++) {
            int n = offsets[i];
            int len = (i == offsets.length - 1) ? (path.length - n) : (offsets[i + 1] - n - 1);
            if (len == 1 && path[n] == (byte) '.') {
                if (m == 0 && path[0] == '/') { // absolute path
                    to[m++] = '/';
                }
                continue;
            }
            if (len == 2 && path[n] == '.' && path[n + 1] == '.') {
                if (lastMOff >= 0) {
                    m = lastM[lastMOff--];  // retreat
                    continue;
                }
                if (path[0] == '/') {  // "/../xyz" skip
                    if (m == 0) {
                        to[m++] = '/';
                    }
                } else {               // "../xyz" -> "../xyz"
                    if (m != 0 && to[m - 1] != '/') {
                        to[m++] = '/';
                    }
                    while (len-- > 0) {
                        to[m++] = path[n++];
                    }
                }
                continue;
            }
            if (m == 0 && path[0] == '/' ||   // absolute path
                            m != 0 && to[m - 1] != '/') {   // not the first name
                to[m++] = '/';
            }
            lastM[++lastMOff] = m;
            while (len-- > 0) {
                to[m++] = path[n++];
            }
        }
        if (m > 1 && to[m - 1] == '/') {
            m--;
        }
        return (m == to.length) ? to : Arrays.copyOf(to, m);
    }

    private static boolean equalsNameAt(ResourcePath p1, ResourcePath p2, int index) {
        int beg1 = p1.offsets[index];
        int len1;
        if (index == p1.offsets.length - 1) {
            len1 = p1.path.length - beg1;
        } else {
            len1 = p1.offsets[index + 1] - beg1 - 1;
        }
        int beg2 = p2.offsets[index];
        int len2;
        if (index == p2.offsets.length - 1) {
            len2 = p2.path.length - beg2;
        } else {
            len2 = p2.offsets[index + 1] - beg2 - 1;
        }
        if (len1 != len2) {
            return false;
        }
        for (int n = 0; n < len1; n++) {
            if (p1.path[beg1 + n] != p2.path[beg2 + n]) {
                return false;
            }
        }
        return true;
    }

    public boolean isSameFile(Path other) {
        if (this.equals(other)) {
            return true;
        }
        if (other == null || this.getFileSystem() != other.getFileSystem()) {
            return false;
        }
        return Arrays.equals(this.getResolvedPath(), ((ResourcePath) other).getResolvedPath());
    }
}
