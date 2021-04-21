package com.oracle.svm.core.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderMismatchException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ResourceFileSystemProvider extends FileSystemProvider {

    private final Map<Path, ResourceFileSystem> filesystems = new HashMap<>();

    private Path uriToPath(URI uri) {
        String scheme = uri.getScheme();
        if ((scheme == null) || !scheme.equalsIgnoreCase(getScheme())) {
            throw new IllegalArgumentException("URI scheme is not '" + getScheme() + "'");
        }

        try {
            // Syntax is resource:{uri}!/{entry}
            String spec = uri.getRawSchemeSpecificPart();
            int sep = spec.indexOf("!/");
            if (sep != -1) {
                spec = spec.substring(0, sep);
            }
            return Paths.get(new URI(spec)).toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static ResourcePath toResourcePath(Path path) {
        if (path == null) {
            throw new NullPointerException();
        }
        if (!(path instanceof ResourcePath)) {
            throw new ProviderMismatchException();
        }
        return (ResourcePath) path;
    }

    @Override
    public String getScheme() {
        return "resource";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        Path path = uriToPath(uri);
        synchronized (filesystems) {
            Path realPath = path.toRealPath();
            if (filesystems.containsKey(realPath)) {
                throw new FileSystemAlreadyExistsException();
            }
            ResourceFileSystem resourceFileSystem = new ResourceFileSystem(this, path, env);
            filesystems.put(realPath, resourceFileSystem);
            return resourceFileSystem;
        }
    }

    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env) {
        return new ResourceFileSystem(this, path, env);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        synchronized (filesystems) {
            ResourceFileSystem resourceFileSystem;
            try {
                resourceFileSystem = filesystems.get(uriToPath(uri).toRealPath());
            } catch (IOException e) {
                throw new FileSystemNotFoundException();
            }
            if (resourceFileSystem == null) {
                throw new FileSystemNotFoundException();
            }
            return resourceFileSystem;
        }
    }

    @Override
    public Path getPath(URI uri) {
        String spec = uri.getSchemeSpecificPart();
        int sep = spec.indexOf("!/");
        if (sep == -1) {
            throw new IllegalArgumentException("URI: " + uri +
                    " does not contain path info ex. resource:file:/foo.jar!/bar.txt");
        }
        return getFileSystem(uri).getPath(spec.substring(sep + 1));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return toResourcePath(path).newByteChannel(options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return toResourcePath(dir).newDirectoryStream(filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        toResourcePath(dir).createDirectory(attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        toResourcePath(path).delete();
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return toResourcePath(path).deleteIfExists();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        toResourcePath(source).copy(toResourcePath(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        toResourcePath(source).move(toResourcePath(target), options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return toResourcePath(path).isSameFile(path2);
    }

    @Override
    public boolean isHidden(Path path) {
        return toResourcePath(path).isHidden();
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return toResourcePath(path).getFileStore();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        toResourcePath(path).checkAccess(modes);
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return toResourcePath(path).newInputStream(options);
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return toResourcePath(path).newOutputStream(options);
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return toResourcePath(path).newFileChannel(options, attrs);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return ResourceAttributesView.get(toResourcePath(path), type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        if (type == BasicFileAttributes.class || type == ResourceAttributes.class) {
            return (A) toResourcePath(path).getAttributes();
        }
        return null;
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return toResourcePath(path).readAttributes(attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        toResourcePath(path).setAttribute(attribute, value, options);
    }

    void removeFileSystem(Path resourcePath, ResourceFileSystem resourceFileSystem) throws IOException {
        synchronized (filesystems) {
            resourcePath = resourcePath.toRealPath();
            if (filesystems.get(resourcePath) == resourceFileSystem)
                filesystems.remove(resourcePath);
        }
    }
}
