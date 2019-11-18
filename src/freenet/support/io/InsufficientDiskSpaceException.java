package freenet.support.io;

import java.io.File;
import java.io.IOException;

public class InsufficientDiskSpaceException extends IOException {
    private static final long serialVersionUID = 1795900904922247498L;

    private File dir;
    private long size;

    public InsufficientDiskSpaceException() {}

    public InsufficientDiskSpaceException(File dir, long size) {
        this.dir = dir;
        this.size = size;
    }

    public File getDir() {
        return dir;
    }

    public long getSize() {
        return size;
    }
}
