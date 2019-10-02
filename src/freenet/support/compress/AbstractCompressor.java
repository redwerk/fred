package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

abstract class AbstractCompressor implements Compressor {

    public long compress(InputStream input, OutputStream output, long maxReadLength, long maxWriteLength)
        throws IOException {
        return compress(input, output, maxReadLength, maxWriteLength, Long.MAX_VALUE, 0);
    }

    boolean isCompressionMakeSense(long rawDataVolume, long compressedDataVolume, int minimumCompressionPercentage) {
        assert rawDataVolume != 0;
        assert minimumCompressionPercentage != 0;

        long compressionPercentage = 100 - compressedDataVolume * 100 / rawDataVolume;
        return compressionPercentage >= minimumCompressionPercentage;
    }
}
