package com.oracle.svm.core.jdk;

import java.io.IOException;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ResourceDirectoryStream implements DirectoryStream<Path> {

    private final ResourceFileSystem fileSystem;
    private final DirectoryStream.Filter<? super Path> filter;
    private final ResourcePath dir;
    private volatile boolean isClosed = false;
    private Iterator<Path> directoryIterator;

    public ResourceDirectoryStream(ResourcePath dir, Filter<? super Path> filter) throws IOException {
        this.fileSystem = dir.getFileSystem();
        this.dir = dir;
        this.filter = filter;
        if (!fileSystem.isDirectory(dir.getResolvedPath())) {
            throw new NotDirectoryException(dir.toString());
        }
    }

    @Override
    public Iterator<Path> iterator() {
        if (isClosed) {
            throw new ClosedDirectoryStreamException();
        }
        if (directoryIterator != null) {
            throw new IllegalStateException("Iterator has already been returned");
        }

        try {
            directoryIterator = fileSystem.iteratorOf(dir, filter);
        } catch (IOException ioException) {
            throw new DirectoryIteratorException(ioException);
        }

        return new Iterator<Path>() {

            @Override
            public boolean hasNext() {
                if (isClosed) {
                    return false;
                }
                return directoryIterator.hasNext();
            }

            @Override
            public Path next() {
                if (isClosed) {
                    throw new NoSuchElementException();
                }
                return directoryIterator.next();
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
