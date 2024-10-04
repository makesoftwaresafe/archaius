/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.archaius.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * When applied to an interface, marks it as a candidate for binding via a ConfigProxyFactory (from the archaius2-core
 * module). For this case, the only relevant attributes are {@link #prefix()}, which sets a shared prefix for all the
 * properties bound to the interface's methods, and {@link #immutable()}, which when set to true creates a static proxy
 * that always returns the config values as they were at the moment that the proxy object is created.
 * <p>
 * Note that an interface can be bound via the ConfigProxyFactory even if it does NOT have this annotation.
 * <p>
 * When applied to a field, marks it as a configuration item, to be injected with the value of the specified property
 * key. This usage is deprecated in favor of using your DI-framework options for injecting configuration values.
 * @see PropertyName
 * @see DefaultValue
 */
@Documented
@Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Configuration
{
    /**
     * name/key of the config to assign
     */
    String      prefix() default "";

    /**
     * field names to use for replacement
     */
    String[]    params() default {};
    
    /**
     * user displayable description of this configuration
     */
    String      documentation() default "";
    
    /**
     * true to allow mapping configuration to fields
     */
    boolean     allowFields() default false;
    
    /**
     * true to allow mapping configuration to setters
     */
    boolean     allowSetters() default true;
    
    /**
     * Method to call after configuration is bound
     */
    String      postConfigure() default "";
    
    /**
     * If true then properties cannot change once set otherwise methods will be
     * bound to dynamic properties via PropertyFactory.
     */
    boolean     immutable() default false;
}
