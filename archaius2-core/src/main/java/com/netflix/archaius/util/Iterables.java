package com.netflix.archaius.util;

import com.netflix.archaius.Internal;

import java.util.Collection;

@Internal
public final class Iterables {
    private Iterables() {}

    /**
     * Returns the number of elements in {@code iterable}.
     */
    public static int size(Iterable<?> iterable) {
        if (iterable instanceof Collection<?>) {
            return ((Collection<?>) iterable).size();
        }
        int size = 0;
        for (Object ignored : iterable) {
            size++;
        }
        return size;
    }
}
