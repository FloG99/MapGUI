package de.flog99.mapgui.preview;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/**
 * Watches a compiled-output tree and calls back once things settle down.
 *
 * <p>A single recompile fires a burst of events, and reloading part-way through a write gives a
 * spurious error, so changes are coalesced after a short quiet period.
 */
final class ClassWatcher implements AutoCloseable {

    private static final long QUIET_PERIOD_MS = 200;

    private final Path root;
    private final Runnable onChange;
    private final WatchService service;
    private Thread thread;

    ClassWatcher(Path root, Runnable onChange) throws IOException {
        this.root = root;
        this.onChange = onChange;
        this.service = root.getFileSystem().newWatchService();
    }

    void start() throws IOException {
        Files.createDirectories(root);
        registerTree(root);

        thread = new Thread(this::watch, "mapgui-preview-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void registerTree(Path directory) throws IOException {
        Files.walkFileTree(directory, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watch() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }

            boolean newDirectory = drain(key);

            // Keep swallowing events until the compiler stops writing.
            try {
                WatchKey next;
                while ((next = service.poll(QUIET_PERIOD_MS, TimeUnit.MILLISECONDS)) != null) {
                    newDirectory |= drain(next);
                }
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }

            if (newDirectory) {
                try {
                    registerTree(root);
                } catch (IOException ignored) {
                    // A directory vanished mid-walk; the next event re-registers what's left.
                }
            }
            onChange.run();
        }
    }

    /** Returns true if a directory appeared, meaning the watch registrations need refreshing. */
    private boolean drain(WatchKey key) {
        boolean newDirectory = false;
        for (var event : key.pollEvents()) {
            if (event.context() instanceof Path relative
                    && Files.isDirectory(((Path) key.watchable()).resolve(relative))) {
                newDirectory = true;
            }
        }
        key.reset();
        return newDirectory;
    }

    @Override
    public void close() throws IOException {
        if (thread != null) {
            thread.interrupt();
        }
        service.close();
    }
}
