package org.mitallast.queue.common.stream;

import java.io.IOException;

@FunctionalInterface
public interface StreamableReader<T extends Streamable> {

    T read(StreamInput streamInput) throws IOException;
}
