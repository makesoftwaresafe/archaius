/**
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.archaius.api;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Core API for reading a configuration.  The API is read only.
 */
@SuppressWarnings("JavadocDeclaration") // TODO: Fix up all the javadocs and remove this suppression
public interface Config extends PropertySource {

    /**
     * Interface for a visitor visiting all key, value pairs.
     * <p>
     * Visitors should not have consequences based on specific key-value pairs and in general
     * should be used primarily for logging purposes.
     * <p>
     * Notably, instrumentation is by default disabled on visitors, meaning that if there are
     * visitors that result in consequences based on specific key-value pairs, it is possible
     * that they are still registered as unused and cleaned up, resulting in an unintended
     * code behavior change.
     *
     * @param <T>
     */
    interface Visitor<T> {
        T visitKey(String key, Object value);
    }
    
    /**
     * Register a listener that will receive a call for each property that is added, removed
     * or updated.  It is recommended that the callbacks be invoked only after a full refresh
     * of the properties to ensure they are in a consistent state.
     * 
     * @param listener
     */
    void addListener(ConfigListener listener);

    /**
     * Remove a previously registered listener.
     * @param listener
     */
    void removeListener(ConfigListener listener);

    /**
     * Return the raw, un-interpolated, object associated with a key.
     * @param key
     */
    Object getRawProperty(String key);

    /**
     * Returns the raw object associated with a key, but without reporting on its usage. Only relevant for configs that
     * support property instrumentation.
     * @param key
     */
    default Object getRawPropertyUninstrumented(String key) { return getRawProperty(key); }


    @Override
    default Optional<Object> getProperty(String key) { return Optional.ofNullable(getRawProperty(key)); }

    @Override
    default Optional<Object> getPropertyUninstrumented(String key) {
        return Optional.ofNullable(getRawPropertyUninstrumented(key));
    }

    default void recordUsage(PropertyDetails propertyDetails) {
        throw new UnsupportedOperationException("Property usage instrumentation not supported for this config type.");
    }

    /** Returns whether a config is recording usage on the standard property endpoints. */
    default boolean instrumentationEnabled() {
        return false;
    }

    /**
     * Parse the property as a long.
     * @param key
     */
    Long getLong(String key);
    
    /**
     * Parse the property as a long but return a default if no property defined or the
     * property cannot be parsed successfully. 
     * @param key
     * @param defaultValue
     * @return
     */
    Long getLong(String key, Long defaultValue);

    String getString(String key);
    String getString(String key, String defaultValue);
    
    Double getDouble(String key);
    Double getDouble(String key, Double defaultValue);
    
    Integer getInteger(String key);
    Integer getInteger(String key, Integer defaultValue);
    
    Boolean getBoolean(String key);
    Boolean getBoolean(String key, Boolean defaultValue);
    
    Short getShort(String key);
    Short getShort(String key, Short defaultValue);
    
    BigInteger getBigInteger(String key);
    BigInteger getBigInteger(String key, BigInteger defaultValue);
    
    BigDecimal getBigDecimal(String key);
    BigDecimal getBigDecimal(String key, BigDecimal defaultValue);
    
    Float getFloat(String key);
    Float getFloat(String key, Float defaultValue);
    
    Byte getByte(String key);
    Byte getByte(String key, Byte defaultValue);
    
    /**
     * Get the property as a list.  Depending on the underlying implementation the list
     * may be derived from a comma-delimited string or from an actual list structure.
     * @deprecated Use {@link #getList(String, Class)} instead.
     */
    List<?> getList(String key);

    /**
     * Get the property as a list.  Depending on the underlying implementation the list
     * may be derived from a comma-delimited string or from an actual list structure.
     */
    <T> List<T> getList(String key, Class<T> type);

    /**
     * Get the property as a list.  Depending on the underlying implementation the list
     * may be derived from a comma-delimited string or from an actual list structure.
     * This method is inherently unsafe and should be used with caution. The type of the list may change
     * depending on whether the key is defined or not, because the parser can't know the type of the defaultValue's
     * elements.
     * @deprecated Use {@link #getList(String, Class)} instead.
     */
    @Deprecated
    List<?> getList(String key, List<?> defaultValue);

    /**
     * Get the property from the Decoder.  All basic data types as well any type
     * will a valueOf or String constructor will be supported.
     * @param type
     * @param key
     * @return
     */
    <T> T get(Class<T> type, String key);
    /**
     * Get the property from the Decoder.  All basic data types as well any type
     * will a valueOf or String constructor will be supported.
     * @param type
     * @param key
     * @return
     */
    <T> T get(Class<T> type, String key, T defaultValue);

    /**
     * Get the property from the Decoder.  Use this method for polymorphic types such as collections.
     * <p>
     * Use the utility methods in {@link ArchaiusType} to get the types for lists, sets and maps.
     *
     * @see ArchaiusType#forListOf(Class)
     * @see ArchaiusType#forSetOf(Class)
     * @see ArchaiusType#forMapOf(Class, Class)
     */
    <T> T get(Type type, String key);
    /**
     * Get the property from the Decoder.  Use this method for polymorphic types such as collections.
     * <p>
     * Use the utility methods in {@link ArchaiusType} to get the types for lists, sets and maps.
     *
     * @see ArchaiusType#forListOf(Class)
     * @see ArchaiusType#forSetOf(Class)
     * @see ArchaiusType#forMapOf(Class, Class)
     */
    <T> T get(Type type, String key, T defaultValue);

    /**
     * @param key
     * @return True if the key is contained within this or any of its child configurations
     */
    boolean containsKey(String key);
    
    /**
     * @return An unmodifiable Iterator over all property names owned by this config
     * @deprecated Use {@link #keys()} instead.
     */
    @Deprecated
    Iterator<String> getKeys();

    /**
     * Returns an unmodifiable Iterable of all property names owned by this config.
     * <p>
     * The default in this interface simply returns a thunk call to {@link #getKeys()}. Implementations are
     * encouraged to provide their own version. The simplest approach, if the implementation has a {@link java.util.Map}
     * or similar as its backing store, is to return an equivalent to
     * <code>Collections.unmodifiableSet(map.keySet())</code>.
     */
    default Iterable<String> keys() {
        return this::getKeys;
    }

    /**
     * @return Return an iterator over all prefixed property names owned by this config
     */
    Iterator<String> getKeys(String prefix);
    
    /**
     * @param prefix
     * @return Return a subset of the configuration prefixed by a key. A prefixed view is NOT independent of its parent
     * config. In particular, setting the decoder or the string interpolator is not supported and causes unspecified
     * behavior.
     * @see #getPrivateView()
     */
    Config getPrefixedView(String prefix);

    /**
     * @return A "private view" of this config. The returned object can have its own {@link Decoder},
     * {@link StrInterpolator}, and {@link ConfigListener}s that will NOT be shared with the original config. Updates to
     * the underlying config's entries WILL be visible and will generate events on any registered listener.
     */
    Config getPrivateView();

    /**
     * Set the interpolator to be used.  The interpolator is normally created from the top level
     * configuration object and is passed down to any children as they are added. Setting the interpolator on a child
     * config is not supported and causes unspecified behavior.
     * @see #getPrivateView()
     * @param interpolator
     */
    void setStrInterpolator(StrInterpolator interpolator);
   
    StrInterpolator getStrInterpolator();
    
    /**
     * Set the Decoder used by get() to parse any type. The decoder is normally created from the top level
     * configuration object and is passed down to children as they are added. Setting the decoder on a child
     * config is not supported and causes unspecified behavior.
     * @see #getPrivateView()
     * @param decoder
     */
    void setDecoder(Decoder decoder);
    
    Decoder getDecoder();
    
    /**
     * Visitor pattern
     * @param visitor
     */
    <T> T accept(Visitor<T> visitor);
    
    default String resolve(String value) {
        throw new UnsupportedOperationException();
    }

    default <T> T resolve(String value, Class<T> type) {
        throw new UnsupportedOperationException();
    }
}
