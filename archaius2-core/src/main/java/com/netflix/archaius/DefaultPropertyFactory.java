package com.netflix.archaius;

import com.netflix.archaius.api.Config;
import com.netflix.archaius.api.ConfigListener;
import com.netflix.archaius.api.Property;
import com.netflix.archaius.api.PropertyContainer;
import com.netflix.archaius.api.PropertyFactory;
import com.netflix.archaius.api.PropertyListener;
import com.netflix.archaius.exceptions.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class DefaultPropertyFactory implements PropertyFactory, ConfigListener {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultPropertyFactory.class);
    
    /**
     * Create a Property factory that is attached to a specific config
     * @param config The source of configuration for this factory.
     */
    public static DefaultPropertyFactory from(final Config config) {
        return new DefaultPropertyFactory(config);
    }

    /**
     * Config from which properties are retrieved.  Config may be a composite.
     */
    private final Config config;
    
    /**
     * Cache of properties so PropertyContainer may be re-used
     */
    private final ConcurrentMap<KeyAndType<?>, Property<?>> properties = new ConcurrentHashMap<>();
    
    /**
     * Monotonically incrementing version number whenever a change in the Config
     * is identified.  This version is used as a global dirty flag indicating that
     * properties should be updated when fetched next.
     */
    private final AtomicInteger masterVersion = new AtomicInteger();
    
    /**
     * Array of all active callbacks.  ListenerWrapper#update will be called for any
     * change in config.  
     */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public DefaultPropertyFactory(Config config) {
        this.config = config;
        this.config.addListener(this);
    }

    /** @deprecated Use {@link #get(String, Type)} or {@link #get(String, Class)} instead. */
    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public PropertyContainer getProperty(String propName) {
        return new PropertyContainerImpl(propName);
    }
    
    @Override
    public void onConfigAdded(Config config) {
        invalidate();
    }

    @Override
    public void onConfigRemoved(Config config) {
        invalidate();
    }

    @Override
    public void onConfigUpdated(Config config) {
        invalidate();
    }

    @Override
    public void onError(Throwable error, Config config) {
        // TODO
    }

    public void invalidate() {
        // Incrementing the version will cause all PropertyContainer instances to invalidate their
        // cache on the next call to get
        masterVersion.incrementAndGet();
        
        // We expect a small set of callbacks and invoke all of them whenever there is any change
        // in the configuration regardless of change. The blanket update is done since we don't track
        // a dependency graph of replacements.
        listeners.forEach(Runnable::run);
    }
    
    protected Config getConfig() {
        return this.config;
    }

    @Override
    public <T> Property<T> get(String key, Class<T> type) {
        return getFromSupplier(key, type, () -> config.get(type, key, null));
    }

    @Override
    public <T> Property<T> get(String key, Type type) {
        return getFromSupplier(key, type, () -> config.get(type, key, null));
    }

    private <T> Property<T> getFromSupplier(String key, Type type, Supplier<T> supplier) {
        return getFromSupplier(new KeyAndType<>(key, type), supplier);
    }

    @SuppressWarnings("unchecked")
    private <T> Property<T> getFromSupplier(KeyAndType<T> keyAndType, Supplier<T> supplier) {
        return (Property<T>) properties.computeIfAbsent(keyAndType, (ignore) -> new PropertyImpl<>(keyAndType, supplier));
    }

    /**
     * Implementation of the Property interface. This class looks at the factory's masterVersion on each read to
     * determine if the cached parsed values is stale.
     */
    private final class PropertyImpl<T> implements Property<T> {

        private final KeyAndType<T> keyAndType;
        private final Supplier<T> supplier;

        // Caching machinery. Writes to cachedValue are guarded by updateLock. Reads can be unguarded because the field is volatile.
        private final ReentrantLock updateLock = new ReentrantLock();
        private volatile CachedValue<T> cachedValue;

        // Keep track of old-style listeners so we can unsubscribe them when they are removed
        // Field is initialized on demand only if it's actually needed.
        // Access is synchronized on _this_.
        private Map<PropertyListener<?>, Subscription> oldSubscriptions;
        
        public PropertyImpl(KeyAndType<T> keyAndType, Supplier<T> supplier) {
            this.keyAndType = keyAndType;
            this.supplier = supplier;
        }

        /**
         * Get the current value of the property. If the value is not cached or the cache is stale, the value is
         * updated from the supplier. If the supplier throws an exception, the exception is logged and rethrown.
         * <p>
         * This method is intended to provide the following semantics:
         * <ul>
         *     <li>Changes to a property are atomic.</li>
         *     <li>Updates from the backing Config are eventually consistent.</li>
         *     <li>When multiple updates happen then "last one wins", as ordered by calls to the PropertyFactory's invalidate() method.</li>
         *     <li>A property only changes value *after* a call to invalidate()</li>
         *     <li>Updates *across* different properties are not transactional. A thread may see (newA, oldB) while a different concurrent thread sees (oldA, newB)</li>
         * </ul>
         * @throws RuntimeException if the supplier throws an exception
         */
        @Override
        public T get() {
            int currentMasterVersion = masterVersion.get();

            if (cachedValue != null) {
                // Happy path. We have an up-to-date cached value, so just return that
                if (cachedValue.version >= currentMasterVersion) {
                    return cachedValue.value;
                }

                // The cached value is stale, someone needs to update it
                if (!updateLock.tryLock()) {
                    // The lock is taken, so another thread is already working on the update.
                    // We can just return the stale value. Since cachedValue is a volatile, it may even have gotten updated
                    // by now. That's fine too. :-)
                    return cachedValue.value;
                }

                // If we're here we have the lock and we skip past the else ...

            } else {

                // There is no cached value at all, not even stale, so we MUST wait until it's updated, either by us
                // or by another thread. So no tryLock here, we HAVE to block until the lock is available.
                updateLock.lock();

                // Done waiting, got the lock. But maybe someone did the update while we were waiting for the lock?
                if (cachedValue != null) {
                    // Yes! We can just return the value that was computed by that other thread (and release the lock before leaving!)
                    try {
                        return cachedValue.value;
                    } finally {
                        updateLock.unlock();
                    }
                }

                // Nope, still null. Let's proceed.
            }

            // At this point, we have the lock AND there's no valid cache. The job of updating falls on us.
            try {
                // Get the new value from the supplier. This call could fail.
                T newValue = supplier.get();

                // We successfully got the new value!
                // Atomically update both the value and the version
                cachedValue = new CachedValue<>(newValue, currentMasterVersion);
                // And return
                return newValue;

            } catch (RuntimeException e) {
                // Oh, no, something went wrong while trying to get the new value. Log the error and rethrow the exception
                // so our caller knows there's a problem. We leave the cache unchanged. Next caller will try again.
                LOG.error("Unable to get current version of property '{}'", keyAndType.key, e);
                throw e;
            } finally {

                // Success or not, release the lock before leaving.
                updateLock.unlock();
            }
        }

        @Override
        public String getKey() {
            return keyAndType.key;
        }
        
        @Override
        public Subscription subscribe(Consumer<T> consumer) {
            Runnable action = new Runnable() {
                private T current = get();
                @Override
                public synchronized void run() {
                    T newValue = get();
                    if (current == newValue && current == null) {
                        return;
                    } else if (current == null) {
                        current = newValue;
                    } else if (newValue == null) {
                        current = null;
                    } else if (current.equals(newValue)) {
                        return;
                    } else {
                        current = newValue;
                    }
                    consumer.accept(current);
                }
            };
            
            listeners.add(action);
            return () -> listeners.remove(action);
        }

        @Deprecated
        @Override
        @SuppressWarnings("deprecation")
        public synchronized void addListener(PropertyListener<T> listener) {
            if (oldSubscriptions == null) {
                oldSubscriptions = new HashMap<>();
            }
            oldSubscriptions.put(listener, subscribe(listener));
        }

        /**
         * Remove a listener previously registered by calling addListener
         * @param listener The listener to be removed
         */
        @Deprecated
        @Override
        @SuppressWarnings("deprecation")
        public synchronized void removeListener(PropertyListener<T> listener) {
            if (oldSubscriptions == null) {
                return;
            }

            Subscription subscription = oldSubscriptions.remove(listener);
            if (subscription != null) {
                subscription.unsubscribe();
            }
        }

        @Override
        public Property<T> orElse(T defaultValue) {
            return new PropertyImpl<>(keyAndType, () -> {
                T value = supplier.get();
                return value != null ? value : defaultValue;
            });
        }

        @Override
        public Property<T> orElseGet(String key) {
            if (!keyAndType.hasType()) {
                throw new IllegalStateException("Type information lost due to map() operation.  All calls to orElse[Get] must be made prior to calling map");
            }
            KeyAndType<T> keyAndType = this.keyAndType.withKey(key);
            Property<T> next = DefaultPropertyFactory.this.get(key, keyAndType.type);
            return new PropertyImpl<>(keyAndType, () -> {
                T value = supplier.get();
                return value != null ? value : next.get();
            });
        }

        @Override
        public <S> Property<S> map(Function<T, S> mapper) {
            return new PropertyImpl<>(keyAndType.discardType(), () -> {
                T value = supplier.get();
                if (value != null) {
                    return mapper.apply(value);
                } else {
                    return null;
                }
            });
        }

        @Override
        public String toString() {
            return "Property [Key=" + keyAndType + "; cachedValue="+ cachedValue + "]";
        }
    }

    /**
     * Holder for a pair of property name and type.  Used as a key in the properties map.
     * @param <T>
     */
    private static final class KeyAndType<T> {
        private final String key;
        private final Type type;

        public KeyAndType(String key, Type type) {
            this.key = key;
            this.type = type;
        }

        public <S> KeyAndType<S> discardType() {
            if (type == null) {
                @SuppressWarnings("unchecked") // safe since type is null
                KeyAndType<S> keyAndType = (KeyAndType<S>) this;
                return keyAndType;
            }
            return new KeyAndType<>(key, null);
        }

        public KeyAndType<T> withKey(String newKey) {
            return new KeyAndType<>(newKey, type);
        }

        public boolean hasType() {
            return type != null;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + Objects.hashCode(key);
            result = 31 * result + Objects.hashCode(type);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof KeyAndType)) {
                return false;
            }
            KeyAndType<?> other = (KeyAndType<?>) obj;
            return Objects.equals(key, other.key) && Objects.equals(type, other.type);
        }

        @Override
        public String toString() {
            return "KeyAndType{" +
                    "key='" + key + '\'' +
                    ", type=" + type +
                    '}';
        }
    }

    /** A holder for a cached value and the version of the master config at which it was updated. */
    private static final class CachedValue<T> {
        final T value;
        final int version;

        CachedValue(T value, int version) {
            this.value = value;
            this.version = version;
        }

        @Override
        public String toString() {
            return "CachedValue{" +
                    "value=" + value +
                    ", version=" + version +
                    '}';
        }
    }

    /**
     * Implements the deprecated PropertyContainer interface, for backwards compatibility.
     */
    @SuppressWarnings("deprecation")
    private final class PropertyContainerImpl implements PropertyContainer {
        private final String propName;

        public PropertyContainerImpl(String propName) {
            this.propName = propName;
        }

        @Override
        public Property<String> asString(String defaultValue) {
            return get(propName, String.class).orElse(defaultValue);
        }

        @Override
        public Property<Integer> asInteger(Integer defaultValue) {
            return get(propName, Integer.class).orElse(defaultValue);
        }

        @Override
        public Property<Long> asLong(Long defaultValue) {
            return get(propName, Long.class).orElse(defaultValue);
        }

        @Override
        public Property<Double> asDouble(Double defaultValue) {
            return get(propName, Double.class).orElse(defaultValue);
        }

        @Override
        public Property<Float> asFloat(Float defaultValue) {
            return get(propName, Float.class).orElse(defaultValue);
        }

        @Override
        public Property<Short> asShort(Short defaultValue) {
            return get(propName, Short.class).orElse(defaultValue);
        }

        @Override
        public Property<Byte> asByte(Byte defaultValue) {
            return get(propName, Byte.class).orElse(defaultValue);
        }

        @Override
        public Property<Boolean> asBoolean(Boolean defaultValue) {
            return get(propName, Boolean.class).orElse(defaultValue);
        }

        @Override
        public Property<BigDecimal> asBigDecimal(BigDecimal defaultValue) {
            return get(propName, BigDecimal.class).orElse(defaultValue);
        }

        @Override
        public Property<BigInteger> asBigInteger(BigInteger defaultValue) {
            return get(propName, BigInteger.class).orElse(defaultValue);
        }

        @Override
        public <T> Property<T> asType(Class<T> type, T defaultValue) {
            return get(propName, type).orElse(defaultValue);
        }

        @Override
        public <T> Property<T> asType(Function<String, T> mapper, String defaultValue) {
            T typedDefaultValue = applyOrThrow(mapper, defaultValue);
            return getFromSupplier(propName, null, () -> {
                String value = config.getString(propName, null);
                if (value != null) {
                        return applyOrThrow(mapper, value);
                }

                return typedDefaultValue;
            });
        }

        private <T> T applyOrThrow(Function<String, T> mapper, String value) {
            try {
                return mapper.apply(value);
            } catch (RuntimeException e) {
                throw new ParseException("Invalid value '" + value + "' for property '" + propName + "'.", e);
            }
        }

        @Override
        public String toString() {
            return "PropertyContainer [name=" + propName + "]";
        }
    }
}
