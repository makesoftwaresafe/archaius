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
package com.netflix.archaius.config;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.netflix.archaius.api.ArchaiusType;
import com.netflix.archaius.api.Config;
import com.netflix.archaius.api.ConfigListener;
import com.netflix.archaius.exceptions.ParseException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AbstractConfigTest {

    private final AbstractConfig config = new AbstractConfig() {
        private final Map<String, Object> entries = new HashMap<>();

        {
            entries.put("foo", "bar");
            entries.put("byte", (byte) 42);
            entries.put("int", 42);
            entries.put("long", 42L);
            entries.put("float", 42.0f);
            entries.put("double", 42.0d);
            entries.put("numberList", "1, 2,3 "); // The embedded spaces should not trip the numeric parsers.
            entries.put("stringList", "a,b,c");
            entries.put("uriList", "http://example.com,http://example.org");
            entries.put("underlyingList", Arrays.asList("a", "b", "c"));
            entries.put("springYmlList[0]", "1");
            entries.put("springYmlList[1]", "2");
            entries.put("springYmlList[2]", "3");
            entries.put("springYmlMap.key1", "1");
            entries.put("springYmlMap.key2", "2");
            entries.put("springYmlMap.key3", "3");
            entries.put("springYmlWithSomeInvalidList[0]", "abc,def");
            entries.put("springYmlWithSomeInvalidList[1]", "abc");
            entries.put("springYmlWithSomeInvalidList[2]", "a=b");
            entries.put("springYmlWithSomeInvalidMap.key1", "a=b");
            entries.put("springYmlWithSomeInvalidMap.key2", "c");
            entries.put("springYmlWithSomeInvalidMap.key3", "d,e");
        }

        @Override
        public boolean containsKey(String key) {
            return entries.containsKey(key);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public Iterator<String> getKeys() {
            return Collections.unmodifiableSet(entries.keySet()).iterator();
        }

        @Override
        public Object getRawProperty(String key) {
            return entries.get(key);
        }

        @Override
        public void forEachProperty(BiConsumer<String, Object> consumer) {
            entries.forEach(consumer);
        }
    };

    @Test
    public void testGet() {
        assertEquals("bar", config.get(String.class, "foo"));
    }
    
    @Test
    public void getExistingProperty() {
        //noinspection OptionalGetWithoutIsPresent
        assertEquals("bar", config.getProperty("foo").get());
    }
    
    @Test
    public void getNonExistentProperty() {
        assertFalse(config.getProperty("non_existent").isPresent());
    }

    @Test
    public void testGetLists() {
        assertEquals(Arrays.asList(1, 2, 3), config.getList("numberList", Integer.class));
        assertEquals(Arrays.asList(1L, 2L, 3L), config.getList("numberList", Long.class));
        // Watch out for the trailing space in the original value in the config!
        assertEquals(Arrays.asList("1", "2", "3 "), config.getList("numberList", String.class));
        assertEquals(Arrays.asList("a", "b", "c"), config.getList("stringList", String.class));
        assertEquals(Arrays.asList(URI.create("http://example.com"), URI.create("http://example.org")), config.getList("uriList", URI.class));

        // Watch out for the trailing space in the list in the original value in the config!
        assertEquals(Arrays.asList("1", "2", "3 "), config.getList("numberList"));
        assertEquals(Arrays.asList("a", "b", "c"), config.getList("stringList"));
        assertEquals(Arrays.asList("http://example.com", "http://example.org"), config.getList("uriList"));

        // TODO: Fix this! The current implementation returns the list  ["[a", "b", "c]"]
        //assertEquals(Arrays.asList("a", "b", "c"), config.getList("underlyingList"));
    }

    @Test
    public void testBadLists() {
        ParseException pe1 = assertThrows(ParseException.class, () -> config.getList("numberList", Boolean.class));
        assertThat(pe1.getMessage(), allOf(
                containsString(config.getString("numberList")),
                containsString("java.util.List<java.lang.Boolean>")));

        ParseException pe2 = assertThrows(ParseException.class, () -> config.getList("stringList", Duration.class));
        assertThat(pe2.getMessage(), allOf(
                containsString(config.getString("stringList")),
                containsString("java.util.List<java.time.Duration>")));
    }

    @Test
    public void testGetRawNumerics() {
        // First, get each entry as its expected type and the corresponding wrapper.
        assertEquals(42, config.get(int.class, "int"));
        assertEquals(42, config.get(Integer.class, "int"));
        assertEquals(42L, config.get(long.class, "long"));
        assertEquals(42L, config.get(Long.class, "long"));
        assertEquals((byte) 42, config.get(byte.class, "byte"));
        assertEquals((byte) 42, config.get(Byte.class, "byte"));
        assertEquals(42.0f, config.get(float.class, "float"));
        assertEquals(42.0f, config.get(Float.class, "float"));
        assertEquals(42.0d, config.get(double.class, "double"));
        assertEquals(42.0d, config.get(Double.class, "double"));

        // Then, get each entry as a string
        assertEquals("42", config.get(String.class, "int"));
        assertEquals("42", config.get(String.class, "long"));
        assertEquals("42", config.get(String.class, "byte"));
        assertEquals("42.0", config.get(String.class, "float"));
        assertEquals("42.0", config.get(String.class, "double"));

        // Then, narrowed types
        assertEquals((byte) 42, config.get(byte.class, "int"));
        assertEquals((byte) 42, config.get(byte.class, "long"));
        assertEquals((byte) 42, config.get(byte.class, "float"));
        assertEquals((byte) 42, config.get(byte.class, "double"));
        assertEquals(42.0f, config.get(double.class, "double"));

        // Then, widened
        assertEquals(42L, config.get(long.class, "int"));
        assertEquals(42L, config.get(long.class, "byte"));
        assertEquals(42L, config.get(long.class, "float"));
        assertEquals(42L, config.get(long.class, "double"));
        assertEquals(42.0d, config.get(double.class, "float"));

        // On floating point
        assertEquals(42.0f, config.get(float.class, "int"));
        assertEquals(42.0f, config.get(float.class, "byte"));
        assertEquals(42.0f, config.get(float.class, "long"));
        assertEquals(42.0f, config.get(float.class, "double"));

        // As doubles
        assertEquals(42.0d, config.get(double.class, "int"));
        assertEquals(42.0d, config.get(double.class, "byte"));
        assertEquals(42.0d, config.get(double.class, "long"));
        assertEquals(42.0d, config.get(double.class, "float"));

        // Narrowed types in wrapper classes
        assertEquals((byte) 42, config.get(Byte.class, "int"));
        assertEquals((byte) 42, config.get(Byte.class, "long"));
        assertEquals((byte) 42, config.get(Byte.class, "float"));

        // Widened types in wrappers
        assertEquals(42L, config.get(Long.class, "int"));
        assertEquals(42L, config.get(Long.class, "byte"));
    }

    @Test
    public void testListeners() {
        ConfigListener goodListener = mock(ConfigListener.class, "goodListener");
        ConfigListener alwaysFailsListener = mock(ConfigListener.class, invocation -> { throw new RuntimeException("This listener fails on purpose"); });
        ConfigListener secondGoodListener = mock(ConfigListener.class, "secondGoodListener");
        RuntimeException mockError = new RuntimeException("Mock error");

        Config mockChildConfig = mock(Config.class);

        config.addListener(alwaysFailsListener);
        config.addListener(goodListener);
        config.addListener(secondGoodListener);

        config.notifyConfigUpdated(mockChildConfig);
        config.notifyConfigAdded(mockChildConfig);
        config.notifyConfigRemoved(mockChildConfig);
        config.notifyError(mockError, mockChildConfig);

        // All 3 listeners should receive all notifications (In order, actually, but we should not verify that
        // because it's not part of the contract).
        for (ConfigListener listener : Arrays.asList(goodListener, alwaysFailsListener, secondGoodListener)) {
            verify(listener).onConfigUpdated(mockChildConfig);
            verify(listener).onConfigAdded(mockChildConfig);
            verify(listener).onConfigRemoved(mockChildConfig);
            verify(listener).onError(mockError, mockChildConfig);
        }
    }

    @Test
    public void testSpringYml() {
        // Working cases for set, list, and map
        Set<Integer> set =
                config.get(ArchaiusType.forSetOf(Integer.class), "springYmlList", Collections.singleton(1));
        assertEquals(set.size(), 3);
        assertTrue(set.contains(1));
        assertTrue(set.contains(2));
        assertTrue(set.contains(3));

        List<Integer> list =
                config.get(ArchaiusType.forListOf(Integer.class), "springYmlList", Arrays.asList(1));
        assertEquals(Arrays.asList(1, 2, 3), list);

        Map<String, Integer> map =
                config.get(ArchaiusType.forMapOf(String.class, Integer.class),
                        "springYmlMap", Collections.emptyMap());
        assertEquals(map.size(), 3);
        assertEquals(1, map.get("key1"));
        assertEquals(2, map.get("key2"));
        assertEquals(3, map.get("key3"));

        // Not a proper list, so we have the default value returned
        List<Integer> invalidList =
                config.get(ArchaiusType.forListOf(Integer.class), "springYmlMap", Arrays.asList(1));
        assertEquals(invalidList, Arrays.asList(1));

        // Not a proper map, so we have the default value returned
        Map<String, String> invalidMap =
                config.get(
                        ArchaiusType.forMapOf(String.class, String.class),
                        "springYmlList",
                        Collections.singletonMap("default", "default"));
        assertEquals(1, invalidMap.size());
        assertEquals("default", invalidMap.get("default"));

        // Some illegal values, so we return with those filtered
        List<String> listWithSomeInvalid =
                config.get(ArchaiusType.forListOf(String.class), "springYmlWithSomeInvalidList", Arrays.asList("bad"));
        assertEquals(listWithSomeInvalid, Arrays.asList("abc", "a=b"));

        Map<String, String> mapWithSomeInvalid =
                config.get(
                        ArchaiusType.forMapOf(String.class, String.class),
                        "springYmlWithSomeInvalidMap",
                        Collections.emptyMap());
        assertEquals(1, mapWithSomeInvalid.size());
        assertEquals("c", mapWithSomeInvalid.get("key2"));
    }
}
