package com.netflix.archaius;

import com.netflix.archaius.api.Property;
import com.netflix.archaius.api.PropertyListener;

/**
 * Base class for Property implementations that delegate to another Property.
 * @deprecated There are no known implementations of this class. To be removed by the end of 2025
 */
@Deprecated
// TODO Remove by the end of 2025
public abstract class DelegatingProperty<T> implements Property<T> {

    protected Property<T> delegate;

    public DelegatingProperty(Property<T> delegate) {
        this.delegate = delegate;
    }
    
    @Override
    public void addListener(PropertyListener<T> listener) {
        delegate.addListener(listener);
    }
    
    @Override
    public void removeListener(PropertyListener<T> listener) {
        delegate.removeListener(listener);
    }
    
    @Override
    public String getKey() {
        return delegate.getKey();
    }
}
