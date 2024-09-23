package com.netflix.archaius.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IterablesTest {
    @Test
    public void testSize() {
        assertEquals(0, Iterables.size(ImmutableList.of()));
        assertEquals(1, Iterables.size(ImmutableList.of(1)));
        assertEquals(2, Iterables.size(ImmutableSet.of(1, 2)));
        assertEquals(0, Iterables.size(Collections::emptyIterator));
        assertEquals(1, Iterables.size(() -> Stream.<Object>of("foo").iterator()));
        assertEquals(3, Iterables.size(() -> ImmutableList.<Object>of(1, 2, 3).iterator()));
    }
}
