package com.netflix.archaius;

import com.netflix.archaius.api.Property;
import com.netflix.archaius.api.PropertyListener;

/** @deprecated This class has no known users and doesn't offer any actual advantage over implementing {@link Property}
 * directly. Scheduled to be removed by the end of 2025.
 **/
@Deprecated
// TODO Remove by the end of 2025
public abstract class AbstractProperty<T> implements Property<T> {

    private final String key;
    
    public AbstractProperty(String key) {
        this.key = key;
    }
    
    @Override
    public void addListener(PropertyListener<T> listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeListener(PropertyListener<T> listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getKey() {
        return key;
    }
}
