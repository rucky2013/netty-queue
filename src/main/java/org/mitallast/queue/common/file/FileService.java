package org.mitallast.queue.common.file;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.typesafe.config.Config;
import org.mitallast.queue.common.component.AbstractComponent;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

public class FileService extends AbstractComponent {

    private final File root;

    @Inject
    public FileService(Config config) throws IOException {
        super(config.getConfig("node"), FileService.class);
        File path = new File(this.config.getString("path"));
        root = new File(path, this.config.getString("name")).getAbsoluteFile();
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("error create directory: " + root);
        }
    }

    public File root() {
        return root;
    }

    public File service(String service) throws IOException {
        Path rootPath = root.toPath().normalize();
        Path servicePath = Paths.get(root.getPath(), service).normalize();
        Preconditions.checkArgument(servicePath.startsWith(rootPath), "service path");

        return servicePath.toFile();
    }

    public File resource(String service, String key) throws IOException {
        Path servicePath = service(service).toPath();

        Path filePath = Paths.get(servicePath.toString(), key).normalize();
        Preconditions.checkArgument(filePath.startsWith(servicePath), "resource path");

        return filePath.toFile();
    }

    public Stream<Path> resources(String service) throws IOException {
        Path servicePath = service(service).toPath();
        return Files.walk(servicePath).map(path -> path.relativize(servicePath));
    }

    public Stream<Path> resources(String service, String prefix) throws IOException {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(prefix);

        Path servicePath = service(service).toPath();
        return Files.walk(servicePath)
                .map(path -> path.relativize(servicePath))
                .filter(matcher::matches);
    }
}
