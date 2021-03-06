package org.hotswap.agent.watch.nio;


import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.watch.WatchEventListener;
import org.hotswap.agent.watch.Watcher;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;

/**
 * NIO2 watcher implementation.
 * <p/>
 * Java 7 (NIO2) watch a directory (or tree) for changes to files.
 * <p/>
 * By http://docs.oracle.com/javase/tutorial/essential/io/examples/WatchDir.java
 *
 * @author Jiri Bubnik
 */
public class WatcherNIO2 implements Watcher {
    private static AgentLogger LOGGER = AgentLogger.getLogger(WatcherNIO2.class);

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys;
    private final Map<Path, List<WatchEventListener>> listeners = new HashMap<Path, List<WatchEventListener>>();

    Thread runner;
    boolean stopped;

    public WatcherNIO2() throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey, Path>();
    }

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    @Override
    public synchronized void addEventListener(URI pathPrefix, WatchEventListener listener) {
        List<WatchEventListener> list = listeners.get(Paths.get(pathPrefix));
        if (list == null) {
            list = new ArrayList<WatchEventListener>();
            listeners.put(Paths.get(pathPrefix), list);
        }
        list.add(listener);
    }

    /**
     * Registers the given directory
     */
    public void addDirectory(URI path) throws IOException {
        try {
            Path dir = Paths.get(path);
            registerAll(dir);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URI format " + path, e);
        } catch (FileSystemNotFoundException e) {
            throw new IOException("Invalid URI " + path, e);
        } catch (SecurityException e) {
            throw new IOException("Security exception for URI " + path, e);
        }
    }


    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        // check duplicate registration
        if (keys.values().contains(dir))
            return;

        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        keys.put(key, dir);
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }


    /**
     * Process all events for keys queued to the watcher
     *
     * @return true if should continue
     */
    private boolean processEvents() {

        // wait for key to be signalled
        WatchKey key;
        try {
            key = watcher.take();
        } catch (InterruptedException x) {
            return false;
        }


        Path dir = keys.get(key);
        if (dir == null) {
            LOGGER.warning("WatchKey '{}' not recognized", key);
            return true;
        }

        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind kind = event.kind();

            if (kind == OVERFLOW) {
                LOGGER.warning("WatchKey '{}' overflowed", key);
                continue;
            }

            // Context for directory entry event is the file name of entry
            WatchEvent<Path> ev = cast(event);
            Path name = ev.context();
            Path child = dir.resolve(name);

            LOGGER.trace("Watch event '{}' on '{}'", event.kind().name(), child);

            callListeners(event, child);

            // if directory is created, and watching recursively, then
            // register it and its sub-directories
            if (kind == ENTRY_CREATE) {
                try {
                    if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                        registerAll(child);
                    }
                } catch (IOException x) {
                    LOGGER.warning("Unable to register events for directory {}", x, child);
                }
            }
        }

        // reset key and remove from set if directory no longer accessible
        boolean valid = key.reset();
        if (!valid) {
            keys.remove(key);

            // all directories are inaccessible
            if (keys.isEmpty()) {
                return false;
            }
        }

        return true;

    }

    // notify listeners about new event
    private void callListeners(final WatchEvent event, final Path path) {
        LOGGER.trace("Logger event {} on path {}", event.kind().name(), path);
        for (Map.Entry<Path, List<WatchEventListener>> list : listeners.entrySet()) {
            for (WatchEventListener listener : list.getValue()) {
                if (path.startsWith(list.getKey())) {
                    org.hotswap.agent.watch.WatchEvent agentEvent = new org.hotswap.agent.watch.WatchEvent() {

                        @Override
                        public WatchEventType getEventType() {
                            return toAgentEvent(event.kind());
                        }

                        @Override
                        public URI getURI() {
                            return path.toUri();
                        }

                        @Override
                        public boolean isFile() {
                            return Files.isRegularFile(path);
                        }

                        @Override
                        public boolean isDirectory() {
                            return Files.isDirectory(path);
                        }

                        @Override
                        public String toString() {
                            return "WatchEvent on path " + path + " for event " + event;
                        }
                    };

                    listener.onEvent(agentEvent);
                }
            }
        }
    }

    // translate constants between NIO event and ageent event
    private org.hotswap.agent.watch.WatchEvent.WatchEventType toAgentEvent(WatchEvent.Kind kind) {
        if (kind == ENTRY_CREATE)
            return org.hotswap.agent.watch.WatchEvent.WatchEventType.CREATE;
        else if (kind == ENTRY_MODIFY)
            return org.hotswap.agent.watch.WatchEvent.WatchEventType.MODIFY;
        else if (kind == ENTRY_DELETE)
            return org.hotswap.agent.watch.WatchEvent.WatchEventType.DELETE;
        else
            throw new IllegalArgumentException("Unknown event type " + kind.name());
    }

    @Override
    public void run() {
        runner = new Thread() {
            @Override
            public void run() {
                for (; ; ) {
                    if (stopped || !processEvents())
                        break;
                }
            }
        };

        runner.setDaemon(true);
        runner.start();
    }

    @Override
    public void stop() {
        stopped = true;
    }
}