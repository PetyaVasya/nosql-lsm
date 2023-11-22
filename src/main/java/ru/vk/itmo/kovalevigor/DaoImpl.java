package ru.vk.itmo.kovalevigor;

import ru.vk.itmo.Config;
import ru.vk.itmo.Dao;
import ru.vk.itmo.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DaoImpl implements Dao<MemorySegment, Entry<MemorySegment>> {

    private final SSTableManager ssManager;
    private static final ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> EMPTY_MAP =
            new ConcurrentSkipListMap<>(SSTable.COMPARATOR);
    private ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> flushedStorage;
    private ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> currentStorage;
    private long currentMemoryByteSize;
    private final long flushThresholdBytes;
    private final ExecutorService flushService;
    private final ExecutorService compactService;
    private Future<Void> flushFuture;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public DaoImpl(final Config config) throws IOException {
        ssManager = new SSTableManager(config.basePath());
        currentStorage = new ConcurrentSkipListMap<>(SSTable.COMPARATOR);
        flushedStorage = EMPTY_MAP;
        flushThresholdBytes = config.flushThresholdBytes();
        flushService = Executors.newSingleThreadExecutor();
        compactService = Executors.newSingleThreadExecutor();
    }

    private static <T> Iterator<T> getValuesIterator(final SortedMap<?, T> map) {
        return map.values().iterator();
    }

    private static Iterator<Entry<MemorySegment>> getIterator(
            final SortedMap<MemorySegment, Entry<MemorySegment>> sortedMap,
            final MemorySegment from,
            final MemorySegment to
    ) {
        if (from == null) {
            if (to == null) {
                return getValuesIterator(sortedMap);
            } else {
                return getValuesIterator(sortedMap.headMap(to));
            }
        } else if (to == null) {
            return getValuesIterator(sortedMap.tailMap(from));
        } else {
            return getValuesIterator(sortedMap.subMap(from, to));
        }
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(final MemorySegment from, final MemorySegment to) {
        final List<PriorityShiftedIterator<Entry<MemorySegment>>> iterators = new ArrayList<>(3);
        final Lock readLock = lock.readLock();
        readLock.lock();
        try {
            iterators.add(new MemEntryPriorityIterator(0, getIterator(currentStorage, from, to)));
            iterators.add(new MemEntryPriorityIterator(1, getIterator(flushedStorage, from, to)));
        } finally {
            readLock.unlock();
        }
        try {
            iterators.add(new MemEntryPriorityIterator(2, ssManager.get(from, to)));
        } catch (IOException e) {
            log(e);
        }
        return new MergeEntryIterator(iterators);
    }

    private static long getMemorySegmentSize(final MemorySegment memorySegment) {
        return memorySegment == null ? 0 : memorySegment.byteSize();
    }

    private static long getEntrySize(final Entry<MemorySegment> entry) {
        return getMemorySegmentSize(entry.key()) + getMemorySegmentSize(entry.value());
    }

    @Override
    public void upsert(final Entry<MemorySegment> entry) {
        Objects.requireNonNull(entry);
        final long entrySize = getEntrySize(entry);

        // overwrite - прикольный тест
        if (entrySize >= flushThresholdBytes) {
            currentStorage.put(entry.key(), entry);
            return;
        }

        final Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            final long newSize = currentMemoryByteSize + entrySize;
            if (newSize >= flushThresholdBytes) {
                if (flushedStorage != EMPTY_MAP) {
                    throw new IllegalStateException("Limit is reached. U should wait");
                }
                flush();
            }

            currentStorage.put(entry.key(), entry);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Entry<MemorySegment> get(final MemorySegment key) {
        Objects.requireNonNull(key);
        Entry<MemorySegment> result;
        final Lock readLock = lock.readLock();
        readLock.lock();
        try {
            result = currentStorage.get(key);
            if (result == null) {
                result = flushedStorage.get(key);
            }
        } finally {
            readLock.unlock();
        }

        if (result != null) {
            if (result.value() == null) {
                return null;
            }
            return result;
        }

        try {
            return ssManager.get(key);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void flush() {
        if (flushedStorage != EMPTY_MAP) {
            return;
        }
        final Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {

            if (currentStorage.isEmpty()) {
                return;
            }

            flushedStorage = currentStorage;
            currentStorage = new ConcurrentSkipListMap<>(SSTable.COMPARATOR);

            currentMemoryByteSize = 0;
            flushFuture = flushService.submit(() -> {
                try {
                    ssManager.write(flushedStorage);
                } catch (IOException e) {
                    Logger.getAnonymousLogger().log(Logger.getAnonymousLogger().getLevel(), e.getMessage());
                } finally {
                    flushedStorage = EMPTY_MAP;
                }
            }, null);
        } finally {
            writeLock.unlock();
        }
    }

    private static void awaitShutdown(ExecutorService service) {
        while (true) {
            try {
                if (service.awaitTermination(1, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                log(e);
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void compact() throws IOException {
        compactService.execute(() -> {
            try {
                ssManager.compact();
            } catch (IOException e) {
                log(e);
            }
        });
    }

    @Override
    public void close() throws IOException {
        try {
            if (flushFuture != null) {
                flushFuture.get();
            }
        } catch (InterruptedException e) {
            log(e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log(e);
        }
        flush();
        compactService.shutdown();
        flushService.shutdown();
        awaitShutdown(compactService);
        awaitShutdown(flushService);
        currentStorage.clear();
        flushedStorage.clear();
        ssManager.close();
    }

    private static void log(Exception e) {
        if (Logger.getAnonymousLogger().isLoggable(Level.WARNING)) {
            Logger.getAnonymousLogger().log(Level.WARNING, Arrays.toString(e.getStackTrace()));
        }
    }
}
