package be.bagofwords.db;


import be.bagofwords.db.combinator.Combinator;
import be.bagofwords.iterator.CloseableIterator;
import be.bagofwords.iterator.DataIterable;
import be.bagofwords.iterator.IterableUtils;
import be.bagofwords.iterator.SimpleIterator;
import be.bagofwords.text.BowString;
import be.bagofwords.ui.UI;
import be.bagofwords.util.HashUtils;
import be.bagofwords.util.KeyValue;
import be.bagofwords.util.StringUtils;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public abstract class DataInterface<T extends Object> implements DataIterable<KeyValue<T>> {

    private final Combinator<T> combinator;
    private final Class<T> objectClass;
    private final String name;

    private final Object closeLock = new Object();
    private boolean wasClosed;
    private boolean closeWasRequested;
    private final boolean isTemporaryDataInterface;

    protected DataInterface(String name, Class<T> objectClass, Combinator<T> combinator, boolean isTemporaryDataInterface) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Name can not be null or empty");
        }
        this.name = name;
        this.objectClass = objectClass;
        this.combinator = combinator;
        this.isTemporaryDataInterface = isTemporaryDataInterface;
    }

    public abstract T read(long key);

    public T read(String key) {
        return read(HashUtils.hashCode(key));
    }

    public T read(BowString key) {
        return read(HashUtils.hashCode(key));
    }

    public long readCount(long key) {
        Long result = (Long) read(key);
        if (result == null)
            return 0;
        else
            return result;
    }

    public long readCount(BowString key) {
        return readCount(HashUtils.hashCode(key));
    }

    public long readCount(String key) {
        return readCount(HashUtils.hashCode(key));
    }

    public boolean mightContain(String key) {
        return mightContain(HashUtils.hashCode(key));
    }

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public boolean mightContain(long key) {
        return read(key) != null;
    }

    public abstract CloseableIterator<KeyValue<T>> iterator();

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public CloseableIterator<Long> keyIterator() {
        final CloseableIterator<KeyValue<T>> keyValueIterator = iterator();
        return new CloseableIterator<Long>() {
            @Override
            public boolean hasNext() {
                return keyValueIterator.hasNext();
            }

            @Override
            public Long next() {
                return keyValueIterator.next().getKey();
            }

            @Override
            public void closeInt() {
                keyValueIterator.close();
            }
        };
    }

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public CloseableIterator<T> valueIterator() {
        final CloseableIterator<KeyValue<T>> keyValueIterator = iterator();
        return new CloseableIterator<T>() {
            @Override
            public boolean hasNext() {
                return keyValueIterator.hasNext();
            }

            @Override
            public T next() {
                return keyValueIterator.next().getValue();
            }

            @Override
            public void closeInt() {
                keyValueIterator.close();
            }
        };
    }

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public CloseableIterator<KeyValue<T>> iterator(final Iterator<Long> keyIterator) {
        return IterableUtils.iterator(new SimpleIterator<KeyValue<T>>() {
            @Override
            public KeyValue<T> next() throws Exception {
                while (keyIterator.hasNext()) {
                    Long next = keyIterator.next();
                    T value = read(next);
                    if (value != null) {
                        return new KeyValue<>(next, value);
                    }
                }
                return null;
            }
        });
    }

    public CloseableIterator<KeyValue<T>> cachedValueIterator() {
        return new CloseableIterator<KeyValue<T>>() {
            @Override
            protected void closeInt() {
                //ok
            }

            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public KeyValue<T> next() {
                return null;
            }
        };
    }

    public abstract void optimizeForReading();

    public abstract void dropAllData();

    public abstract void flush();

    public abstract long apprSize();

    public Combinator<T> getCombinator() {
        return combinator;
    }

    public abstract DataInterface getCoreDataInterface();

    public Class<T> getObjectClass() {
        return objectClass;
    }


    public void write(BowString key, T value) {
        write(HashUtils.hashCode(key.getS()), value);
    }

    public void write(String key, T value) {
        write(HashUtils.hashCode(key), value);
    }

    public abstract void write(long key, T value);

    public void increaseCount(String key, Long value) {
        write(key, (T) value);
    }

    public void increaseCount(long key, Long value) {
        write(key, (T) value);
    }

    public void increaseCount(String key) {
        increaseCount(key, 1l);
    }

    public void increaseCount(long key) {
        increaseCount(key, 1l);
    }

    public String getName() {
        return name;
    }

    public void remove(String key) {
        remove(HashUtils.hashCode(key));
    }

    public void remove(long key) {
        write(key, null);
    }

    public long dataCheckSum() {
        CloseableIterator<KeyValue<T>> valueIterator = iterator();
        final int numToSample = 10000;
        long checksum = 0;
        int numDone = 0;
        while (valueIterator.hasNext() && numDone < numToSample) {
            KeyValue<T> next = valueIterator.next();
            if (next.getValue() == null) {
                throw new RuntimeException("Iterating over values returned null for key " + next.getKey());
            }
            T value = next.getValue();
            checksum += checksum * 31 + value.hashCode();
            numDone++;
        }
        valueIterator.close();
        return checksum;
    }

    /**
     * You don't want to use this if you could use apprSize()
     */

    public long exactSize() {
        long result = 0;
        CloseableIterator<Long> keyIt = keyIterator();
        while (keyIt.hasNext()) {
            keyIt.next();
            result++;
        }
        keyIt.close();
        return result;
    }

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public void write(Iterator<KeyValue<T>> entries) {
        while (entries.hasNext()) {
            KeyValue<T> entry = entries.next();
            write(entry.getKey(), entry.getValue());
        }
    }

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public Stream<KeyValue<T>> stream() {
        return IterableUtils.stream(this, true);
    }

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public Stream<T> streamValues() {
        return IterableUtils.stream(valueIterator(), apprSize(), false);
    }

    /**
     * This method can be overwritten in a subclass to improve efficiency
     */

    public Stream<Long> streamKeys() {
        return IterableUtils.stream(keyIterator(), apprSize(), true);
    }

    public final void close() {
        requestClose();
        ifNotClosed(() -> {
                    if (isTemporaryDataInterface) {
                        dropAllData();
                    }
                    flush();
                    doClose();
                    wasClosed = true;
                }
        );
    }

    protected void requestClose() {
        closeWasRequested = true;
    }

    protected abstract void doClose();

    public final boolean wasClosed() {
        return wasClosed;
    }

    protected final boolean closeWasRequested() {
        return closeWasRequested;
    }

    @Override
    protected void finalize() throws Throwable {
        ifNotClosed(() -> {
            if (!isTemporaryDataInterface()) {
                //the user did not close the data interface himself?
                UI.write("Closing data interface " + getName() + " because it is about to be garbage collected.");
            }
            close();
        });
        super.finalize();
    }

    public void ifNotClosed(ActionIfNotClosed action) {
        synchronized (closeLock) {
            if (!wasClosed()) {
                action.doAction();
            }
        }
    }

    public interface ActionIfNotClosed {
        public void doAction();
    }

    public boolean isTemporaryDataInterface() {
        return isTemporaryDataInterface;
    }


}
