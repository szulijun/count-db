package be.bagofwords.db.cached;

import be.bagofwords.application.BowTaskScheduler;
import be.bagofwords.application.memory.MemoryGobbler;
import be.bagofwords.application.memory.MemoryManager;
import be.bagofwords.application.memory.MemoryStatus;
import be.bagofwords.cache.CachesManager;
import be.bagofwords.cache.DynamicMap;
import be.bagofwords.cache.ReadCache;
import be.bagofwords.db.DataInterface;
import be.bagofwords.db.LayeredDataInterface;
import be.bagofwords.iterator.CloseableIterator;
import be.bagofwords.ui.UI;
import be.bagofwords.util.KeyValue;
import be.bagofwords.util.SafeThread;
import be.bagofwords.util.Utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class CachedDataInterface<T extends Object> extends LayeredDataInterface<T> implements MemoryGobbler {

    private static final int TIME_BETWEEN_FLUSHES_WRITE_BUFFER = 1000;
    private static final int NUM_OF_WRITE_BUFFERS = 10;

    private ReadCache<T> readCache;
    private boolean readCacheDirty;
    private List<SwappableDynamicMap> writeBuffers;
    private final MemoryManager memoryManager;
    private final SafeThread initializeCachesThread;
    private long timeOfLastFlushOfWriteBuffer;

    public CachedDataInterface(MemoryManager memoryManager, CachesManager cachesManager, DataInterface<T> baseInterface, BowTaskScheduler taskScheduler) {
        super(baseInterface);
        this.memoryManager = memoryManager;
        this.memoryManager.registerMemoryGobbler(this);
        this.readCache = cachesManager.createNewCache(getName(), baseInterface.getObjectClass());
        this.readCacheDirty = false;
        this.writeBuffers = new ArrayList<>();
        for (int i = 0; i < NUM_OF_WRITE_BUFFERS; i++) {
            this.writeBuffers.add(new SwappableDynamicMap());
        }
        this.initializeCachesThread = new InitializeCachesThread(baseInterface);
        this.initializeCachesThread.start();
        taskScheduler.schedulePeriodicTask(() -> ifNotClosed(this::flushWriteBuffer), TIME_BETWEEN_FLUSHES_WRITE_BUFFER);
        timeOfLastFlushOfWriteBuffer = System.currentTimeMillis();
    }

    @Override
    public T read(long key) {
        KeyValue<T> cachedValue = readCache.get(key);
        if (cachedValue == null) {
            //never read, read from direct
            T value = baseInterface.read(key);
            readCache.put(key, value);
            return value;
        } else {
            return cachedValue.getValue();
        }
    }

    @Override
    public boolean mightContain(long key) {
        KeyValue<T> cachedValue = readCache.get(key);
        if (cachedValue != null) {
            if (cachedValue.getValue() == null) {
                return false;
            } else {
                return true;
            }
        } else {
            return baseInterface.mightContain(key);
        }
    }

    @Override
    public void write(long key, T value) {
        checkWriteConditions();
        unsafeWrite(key, value);
    }

    private void checkWriteConditions() {
        memoryManager.waitForSufficientMemory();
        waitForSlowFlushes();
    }

    private void unsafeWrite(long key, T value) {
        int writeBufferInd = (int) (key % NUM_OF_WRITE_BUFFERS);
        if (writeBufferInd < 0) {
            writeBufferInd += NUM_OF_WRITE_BUFFERS;
        }
        SwappableDynamicMap writeBuffer = writeBuffers.get(writeBufferInd);
        synchronized (writeBuffer) {
            KeyValue<T> cachedValue = writeBuffer.getMap().get(key);
            if (cachedValue == null) {
                //first write of this key
                writeBuffer.getMap().put(key, value);
            } else {
                if (value != null && cachedValue.getValue() != null) {
                    T combinedValue = getCombinator().combine(cachedValue.getValue(), value);
                    writeBuffer.getMap().put(key, combinedValue);
                } else {
                    writeBuffer.getMap().put(key, value);
                }
            }
        }
    }

    private void waitForSlowFlushes() {
        while (System.currentTimeMillis() - timeOfLastFlushOfWriteBuffer > TIME_BETWEEN_FLUSHES_WRITE_BUFFER * 10) {
            //exceptionally long time since last flush, let's wait for the flush to finish
            Utils.threadSleep(10);
        }
    }

    @Override
    public CloseableIterator<KeyValue<T>> cachedValueIterator() {
        final Iterator<KeyValue<T>> iterator = readCache.iterator();
        return new CloseableIterator<KeyValue<T>>() {
            @Override
            protected void closeInt() {
                //ok
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public KeyValue<T> next() {
                return iterator.next();
            }
        };
    }

    @Override
    public void write(Iterator<KeyValue<T>> entries) {
        int numOfEntriesWritten = 0;
        while (entries.hasNext()) {
            if (numOfEntriesWritten % 100 == 0) {
                checkWriteConditions();
            }
            KeyValue<T> next = entries.next();
            write(next.getKey(), next.getValue());
            numOfEntriesWritten++;
        }
    }

    @Override
    public synchronized void doCloseImpl() {
        try {
            stopInitializeCachesThread();
            flush();
        } finally {
            //even if the flush failed, we remove our data structures
            readCache.clear();
            readCache = null;
            writeBuffers = null;
            baseInterface.close();
        }
    }

    public synchronized void flush() {
        flushWriteBuffer();
        baseInterface.flush();
        cleanDirtyReadCache();
    }

    private void cleanDirtyReadCache() {
        if (readCacheDirty) {
            stopInitializeCachesThread();
            readCacheDirty = false; //should come before clearing read cache
            readCache.clear();
        }
    }

    private synchronized long flushWriteBuffer() {
        //flush values in write cache
        long valuesRemoved = writeBuffers.parallelStream().collect(Collectors.summingLong(
                buffer -> {
                    DynamicMap<T> oldValues;
                    synchronized (buffer) {
                        oldValues = buffer.putNew();
                    }
                    if (oldValues.size() > 0) {
                        baseInterface.write(oldValues.iterator());
                        readCacheDirty = true; //should come after writing values
                    }
                    return oldValues.size();
                }
        ));
        timeOfLastFlushOfWriteBuffer = System.currentTimeMillis();
        return valuesRemoved;
    }

    @Override
    public void dropAllData() {
        stopInitializeCachesThread();
        for (SwappableDynamicMap writeBuffer : writeBuffers) {
            synchronized (writeBuffer) {
                writeBuffer.putNew();
            }
        }
        readCache.clear();
        baseInterface.dropAllData();
    }

    private void stopInitializeCachesThread() {
        if (!initializeCachesThread.isFinished()) {
            initializeCachesThread.terminate();
            initializeCachesThread.waitForFinish();
        }
    }

    @Override
    public long freeMemory() {
        return flushWriteBuffer();
    }

    @Override
    public long getMemoryUsage() {
        return sizeOfWriteBuffers();
    }

    private long sizeOfWriteBuffers() {
        long result = 0;
        for (SwappableDynamicMap writeBuffer : writeBuffers) {
            synchronized (writeBuffer) {
                result += writeBuffer.getMap().size();
            }
        }
        return result;
    }


    private class InitializeCachesThread extends SafeThread {

        public InitializeCachesThread(DataInterface<T> baseInterface) {
            super("initialize_cache_" + baseInterface.getName(), false);
        }

        @Override
        protected void runInt() throws Exception {
            CloseableIterator<KeyValue<T>> iterator = baseInterface.cachedValueIterator();
            int numOfValuesWritten = 0;
            long start = System.currentTimeMillis();
            while (iterator.hasNext() && memoryManager.getMemoryStatus() == MemoryStatus.FREE && !isTerminateRequested()) {
                KeyValue<T> next = iterator.next();
                readCache.put(next.getKey(), next.getValue());
                numOfValuesWritten++;
            }
            if (iterator.hasNext()) {
                UI.write("Could not add (all) values to cache of " + baseInterface.getName() + " because memory was full");
            }
            /*else {
                UI.write("Added " + numOfValuesWritten + " values to cache of " + baseInterface.getName() + " in " + (System.currentTimeMillis() - start) + " ms");
            }*/
            iterator.close();
        }
    }

    private class SwappableDynamicMap {
        private DynamicMap<T> map;

        private SwappableDynamicMap() {
            map = new DynamicMap<>(getObjectClass());
        }

        public DynamicMap<T> putNew() {
            DynamicMap<T> old = map;
            map = new DynamicMap<>(getObjectClass());
            return old;
        }

        public DynamicMap<T> getMap() {
            return map;
        }
    }

}


