package com.oracle.svm.core.jdk;

import java.io.IOException;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.DirectoryStream;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class ResourceDirectoryStream implements DirectoryStream<Path> {

    private final ResourceFileSystem fileSystem;
    private final DirectoryStream.Filter<? super Path> filter;
    private volatile boolean isClosed = false;
    private final List<String> data;

    public ResourceDirectoryStream(ResourcePath path, Filter<? super Path> filter) throws IOException {
        this.fileSystem = path.getFileSystem();
        this.filter = filter;
        this.data = formatDirData(path);
        if (!fileSystem.isDirectory(path.getResolvedPath())) {
            throw new NotDirectoryException(path.toString());
        }
    }

    // TODO: After recomposing resource storage, get with zero index will be replaced with
    // appropriate one.
    private List<String> formatDirData(ResourcePath path) {
        byte[] data = Resources.get(fileSystem.getString(path.getResolvedPath())).get(0);
        return Arrays.asList(fileSystem.getString(data).split("\n"));
    }

    // TODO: We need to filter data, based on filter parameter.
    @Override
    public Iterator<Path> iterator() {
        if (isClosed) {
            throw new ClosedDirectoryStreamException();
        }

        return new Iterator<Path>() {
            final Iterator<String> iterator = data.iterator();

            @Override
            public boolean hasNext() {
                if (isClosed) {
                    return false;
                }
                return iterator.hasNext();
            }

            @Override
            public Path next() {
                if (isClosed) {
                    throw new NoSuchElementException();
                }
                return new ResourcePath(fileSystem, fileSystem.getBytes(iterator.next()));
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public void close() throws IOException {
        if (!isClosed) {
            isClosed = true;
        }
    }
}
