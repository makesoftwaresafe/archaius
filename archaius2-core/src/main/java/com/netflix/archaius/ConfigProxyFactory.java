package com.netflix.archaius;

import com.netflix.archaius.api.Config;
import com.netflix.archaius.api.Decoder;
import com.netflix.archaius.api.Property;
import com.netflix.archaius.api.PropertyFactory;
import com.netflix.archaius.api.PropertyRepository;
import com.netflix.archaius.api.TypeConverter;
import com.netflix.archaius.api.annotations.Configuration;
import com.netflix.archaius.api.annotations.DefaultValue;
import com.netflix.archaius.api.annotations.PropertyName;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.text.StrLookup;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Factory for binding a configuration interface to properties in a {@link PropertyFactory}
 * instance.  Getter methods on the interface are mapped by naming convention
 * by the property name or may be overridden using the @{@link PropertyName} annotation.
 * <p>
 * For example,
 * <pre>
 * {@code
 * @Configuration(prefix="foo")
 * interface FooConfiguration {
 *    int getTimeout();     // maps to "foo.timeout"
 *
 *    String getName();     // maps to "foo.name"
 *
 *    @PropertyName(name="bar")
 *    String getSomeOtherName();  // maps to "foo.bar"
 * }
 * }
 * </pre>
 *
 * Default values may be set by adding a {@literal @}{@link DefaultValue} with a default value string.  Note
 * that the default value type is a string to allow for interpolation.  Alternatively, methods can
 * provide a default method implementation.  Note that {@literal @}DefaultValue cannot be added to a default
 * method as it would introduce ambiguity as to which mechanism wins.
 * <p>
 * For example,
 * <pre>
 * {@code
 * @Configuration(prefix="foo")
 * interface FooConfiguration {
 *    @DefaultValue("1000")
 *    int getReadTimeout();     // maps to "foo.timeout"
 *    
 *    default int getWriteTimeout() {
 *        return 1000;
 *    }
 * }
 * }
 * 
 * To create a proxy instance,
 * <pre>
 * {@code 
 * FooConfiguration fooConfiguration = configProxyFactory.newProxy(FooConfiguration.class);
 * }
 * </pre>
 * 
 * To override the prefix in {@literal @}{@link Configuration} or provide a prefix when there is no
 * {@literal @}Configuration annotation simply pass in a prefix in the call to newProxy.
 * 
 * <pre>
 * {@code 
 * FooConfiguration fooConfiguration = configProxyFactory.newProxy(FooConfiguration.class, "otherprefix.foo");
 * }
 * </pre>
 * 
 * By default, all properties are dynamic and can therefore change from call to call.  To make the
 * configuration static set {@link Configuration#immutable()}  to true.  Creation of an immutable configuration
 * will fail if the interface contains parametrized methods or methods that return primitive types and do not have a
 * value set at the moment of creation, from either the underlying config, a {@link DefaultValue} annotation, or a
 * default method implementation.
 * <p>
 * Note that an application should normally have just one instance of ConfigProxyFactory
 * and PropertyFactory since PropertyFactory caches {@link com.netflix.archaius.api.Property} objects.
 * 
 * @see Configuration
 */
@SuppressWarnings("deprecation")
public class ConfigProxyFactory {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigProxyFactory.class);

    // Users sometimes leak both factories and proxies, leading to hard-to-track-down memory problems.
    // We use these maps to keep track of how many instances of each are created and make log noise to help them
    // track down the culprits. WeakHashMaps to avoid holding onto objects ourselves.
    /**
     * Global count of proxy factories, indexed by Config object. An application could legitimately have more
     * than one proxy factory per config, if they want to use different Decoders or PropertyFactories.
      */
    private static final Map<Config, Integer> FACTORIES_COUNT = Collections.synchronizedMap(new WeakHashMap<>());
    private static final String EXCESSIVE_PROXIES_LIMIT = "archaius.excessiveProxiesLogging.limit";

    /**
     * Per-factory count of proxies, indexed by implemented interface and prefix. Because this count is kept per-proxy,
     * it's also implicitly indexed by Config object :-)
     */
    private final Map<InterfaceAndPrefix, Integer> PROXIES_COUNT = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The decoder is used for the purpose of decoding any @DefaultValue annotation
     */
    private final Decoder decoder;
    private final PropertyRepository propertyRepository;
    private final Config config;
    private final int excessiveProxyLimit;


    /**
     * Build a proxy factory from the provided config, decoder and PropertyFactory. Normal usage from most applications
     * is to just set up injection bindings for those 3 objects and let your DI framework find this constructor.
     *
     * @param config Used to perform string interpolation in values from {@link DefaultValue} annotations. Weird things
     *               will happen if this is not the same Config that the PropertyFactory exposes!
     * @param decoder Used to parse strings from {@link DefaultValue} annotations into the proper types.
     * @param factory Used to access the config values that are returned by proxies created by this factory.
     */
    @Inject
    public ConfigProxyFactory(Config config, Decoder decoder, PropertyFactory factory) {
        this.decoder = decoder;
        this.config = config;
        this.propertyRepository = factory;
        excessiveProxyLimit = config.getInteger(EXCESSIVE_PROXIES_LIMIT, 5);

        warnWhenTooMany(FACTORIES_COUNT, config, excessiveProxyLimit, () -> String.format("ProxyFactory(Config:%s)", config.hashCode()));
    }

    /**
     * Build a proxy factory for a given Config. Use this ONLY if you need proxies associated with a different Config
     * that your DI framework would normally give you.
     * <p>
     * The constructed factory will use the Config's Decoder and a {@link DefaultPropertyFactory} built from that same
     * Config object.
     * @see #ConfigProxyFactory(Config, Decoder, PropertyFactory)
     */
    @Deprecated
    public ConfigProxyFactory(Config config) {
        this(config, config.getDecoder(), DefaultPropertyFactory.from(config));
    }

    /**
     * Build a proxy factory for a given Config and PropertyFactory. Use ONLY if you need to use a specialized
     * PropertyFactory in your proxies. The constructed proxy factory will use the Config's Decoder.
     * @see #ConfigProxyFactory(Config, Decoder, PropertyFactory)
     */
    @Deprecated
    public ConfigProxyFactory(Config config, PropertyFactory factory) {
        this(config, config.getDecoder(), factory);
    }
    

    /**
     * Create a proxy for the provided interface type for which all getter methods are bound
     * to a Property.
     */
    public <T> T newProxy(final Class<T> type) {
        return newProxy(type, null);
    }
    
    /**
     * Create a proxy for the provided interface type for which all getter methods are bound
     * to a Property. The proxy uses the provided prefix, even if there is a {@link Configuration} annotation in TYPE.
     */
    public <T> T newProxy(final Class<T> type, final String initialPrefix) {
        Configuration annot = type.getAnnotation(Configuration.class);
        return newProxy(type, initialPrefix, annot != null && annot.immutable());
    }
    
    /**
     * Encapsulate the invocation of a single method of the interface
     */
    protected interface PropertyValueGetter<T> {
        /**
         * Invoke the method with the provided arguments
         */
        T invoke(Object[] args);
    }

    /**
     * Providers of "empty" defaults for the known collection types that we support as proxy method return types.
     */
    private static final Map<Type, Function<Object[], ?>> knownCollections = new HashMap<>();

    static {
        knownCollections.put(Map.class, (ignored) -> Collections.emptyMap());
        knownCollections.put(Set.class, (ignored) -> Collections.emptySet());
        knownCollections.put(SortedSet.class, (ignored) -> Collections.emptySortedSet());
        knownCollections.put(List.class, (ignored) -> Collections.emptyList());
        knownCollections.put(LinkedList.class, (ignored) -> new LinkedList<>());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> T newProxy(final Class<T> type, final String initialPrefix, boolean immutable) {
        Configuration annot = type.getAnnotation(Configuration.class);
        final String prefix = derivePrefix(annot, initialPrefix);

        warnWhenTooMany(PROXIES_COUNT, new InterfaceAndPrefix(type, prefix), excessiveProxyLimit, () -> String.format("Proxy(%s, %s)", type, prefix));


        // There's a circular dependency between these maps and the proxy object. They must be created first because the
        // proxy's invocation handler needs to keep a reference to them, but the proxy must be created before they get
        // filled because we may need to call methods on the interface in order to fill the maps :-|
        final Map<Method, PropertyValueGetter<?>> invokers = new HashMap<>();
        final Map<Method, String> propertyNames = new HashMap<>();

        final InvocationHandler handler = new ConfigProxyInvocationHandler<>(type, prefix, invokers, propertyNames);

        final T proxyObject = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[] { type }, handler);
        List<RuntimeException> proxyingExceptions = new LinkedList<>();

        // Iterate through all declared methods of the class looking for setter methods.
        // Each setter will be mapped to a Property<T> for the property name:
        //      prefix + lowerCamelCaseDerivedPropertyName
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            try {
                MethodInvokerHolder methodInvokerHolder = buildInvokerForMethod(type, prefix, method, proxyObject, immutable);

                propertyNames.put(method, methodInvokerHolder.propertyName);

                if (immutable) {
                    // Cache the current value of the property and always return that.
                    // Note that this will fail for parameterized properties and for primitive-valued methods
                    // with no value set!
                    Object value = methodInvokerHolder.invoker.invoke(new Object[]{});
                    invokers.put(method, (args) -> value);
                } else {
                    invokers.put(method, methodInvokerHolder.invoker);
                }
            } catch (RuntimeException e) {
                // Capture the exception and continue processing the other methods. We'll throw them all at the end.
                proxyingExceptions.add(e);
            }
        }

        if (!proxyingExceptions.isEmpty()) {
            String errors = proxyingExceptions.stream()
                    .map(Throwable::getMessage)
                    .collect(Collectors.joining("\n\t"));
            RuntimeException exception = new RuntimeException(
                    "Failed to create a configuration proxy for class " + type.getName()
                    + ":\n\t" + errors, proxyingExceptions.get(0));
            proxyingExceptions.subList(1, proxyingExceptions.size()).forEach(exception::addSuppressed);
            throw exception;
        }

        return proxyObject;
    }

    /**
     * Build the actual prefix to use for config values read by a proxy.
     * @param annot The (possibly null) annotation from the proxied interface.
     * @param prefix A (possibly null) explicit prefix being passed by the user (or by an upper level proxy,
     *               in the case of nested interfaces). If present, it always overrides the annotation.
     * @return A prefix to be prepended to all the config keys read by the methods in the proxy. If not empty, it will
     *         always end in a period <code>.</code>
     */
    private String derivePrefix(Configuration annot, String prefix) {
        if (prefix == null && annot != null) {
            prefix = annot.prefix();
            if (prefix == null) {
                prefix = "";
            }
        }
        if (prefix == null)
            return "";

        if (prefix.endsWith(".") || prefix.isEmpty())
            return prefix;

        return prefix + ".";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> MethodInvokerHolder buildInvokerForMethod(Class<T> proxyObjectType, String prefix, Method m, T proxyObject, boolean immutable) {
        try {

            final Class<?> returnType = m.getReturnType();
            final PropertyName nameAnnot = m.getAnnotation(PropertyName.class);
            final String propName = getPropertyName(prefix, m, nameAnnot);

            // A supplier for the value to be returned when the method's associated property is not set
            // The proper parametrized type for this would be Function<Object[], returnType>, but we can't say that in Java.
            final Function<Object[], ?> defaultValueSupplier = defaultValueSupplierForMethod(proxyObjectType, m, returnType, proxyObject, propName);

            // This object encapsulates the way to get the value for the current property.
            final PropertyValueGetter propertyValueGetter;

            if (!knownCollections.containsKey(returnType)
                    && returnType.isInterface()
                    && !(decoder instanceof TypeConverter.Registry && ((TypeConverter.Registry) decoder).get(m.getGenericReturnType()).isPresent())) {
                // Our return type is an interface but not a known collection and is also not a type our decoder can handle.
                // We treat it as a nested Config proxy interface and create a proxy with it, with the current property name as the initial prefix for nesting.
                propertyValueGetter = createInterfaceProperty(propName, newProxy(returnType, propName, immutable));

            } else if (m.getParameterCount() > 0) {
                // A parameterized property. Note that this requires a @PropertyName annotation to extract the interpolation positions!
                if (nameAnnot == null) {
                    throw new IllegalArgumentException("Missing @PropertyName annotation on method with parameters " + m.getName());
                }

                // A previous version allowed the full name to be specified, even if the prefix was specified. So, for
                // backwards compatibility, we allow both including or excluding the prefix for parameterized names.
                String propertyNameTemplate;
                if (!StringUtils.isBlank(prefix) && !nameAnnot.name().startsWith(prefix)) {
                    propertyNameTemplate = prefix + nameAnnot.name();
                } else {
                    propertyNameTemplate = nameAnnot.name();
                }

                // TODO: Figure out a way to validate the template. It should have params in the form ${0}, ${1}, etc.
                propertyValueGetter = createParameterizedProperty(m.getGenericReturnType(), propertyNameTemplate, defaultValueSupplier);

            } else {
                // Anything else.
                propertyValueGetter = createScalarProperty(m.getGenericReturnType(), propName, defaultValueSupplier);
            }

            return new MethodInvokerHolder(propertyValueGetter, propName);
        } catch (RuntimeException e) {
            throw new RuntimeException("Failed to create a proxy for method " + m.getName() + ": " + e, e);
        }
    }

    /**
     * Build a supplier for the default value to be returned when the underlying property for a method is not set.
     * Because of the way {@link Property} works, this will ALSO be called if the underlying property is set to null
     * OR if it's set to a "bad" value that can't be decoded to the method's return type.
     **/
    private <PT> Function<Object[], ?> defaultValueSupplierForMethod(Class<PT> proxyObjectType, Method m, Type returnType, PT proxyObject, String propName) {
        if (m.getAnnotation(DefaultValue.class) != null) {
            // The method has a @DefaultValue annotation. Decode the string from there and return that.
            return createAnnotatedMethodSupplier(m, m.getGenericReturnType(), config, decoder);
        }

        if (m.isDefault()) {
            // The method has a default implementation in the interface. Obtain the default value by calling that implementation.
            return createDefaultMethodSupplier(m, proxyObjectType, proxyObject);
        }

        // No default value available.
        // For collections, return an empty
        if (knownCollections.containsKey(returnType)) {
            return knownCollections.get(returnType);
        }

        // For primitive return types, our historical behavior of returning a null causes an NPE with no message and an
        // obscure trace. Instead of that we now use a fake supplier that will still throw the NPE, but adds a message to it.
        if (returnType instanceof Class && ((Class<?>) returnType).isPrimitive()) {
            return (ignored) -> {
                String msg = String.format("Property '%s' is not set or has an invalid value and method %s.%s does not define a default value",
                        propName, proxyObjectType.getName(), m.getName());
                throw new NullPointerException(msg);
            };
        }

        // For any other return type return nulls.
        return (ignored) -> null;
    }

    /**
     * Compute the name of the property that will be returned by this method.
     */
    private static String getPropertyName(String prefix, Method m, PropertyName nameAnnot) {
        final String verb;
        if (m.getName().startsWith("get")) {
            verb = "get";
        } else if (m.getName().startsWith("is")) {
            verb = "is";
        } else {
            verb = "";
        }
        return nameAnnot != null && nameAnnot.name() != null
                        ? prefix + nameAnnot.name()
                        : prefix + Character.toLowerCase(m.getName().charAt(verb.length())) + m.getName().substring(verb.length() + 1);
    }


    /** Build a supplier that returns the (interpolated and decoded) value from the method's @DefaultValue annotation */
    private static <T> Function<Object[], T> createAnnotatedMethodSupplier(Method m, Type returnType, Config config, Decoder decoder) {
        if (m.isDefault()) {
            throw new IllegalArgumentException("@DefaultValue cannot be defined on a method with a default implementation for method "
                                               + m.getDeclaringClass().getName() + "#" + m.getName());
        }

        String value = m.getAnnotation(DefaultValue.class).value();
        if (returnType == String.class) {
            //noinspection unchecked
            return memoize((T) config.resolve(value)); // The cast is actually a no-op, T == String here!
        } else {
            return memoize(decoder.decode(returnType, config.resolve(value)));
        }
    }

    /** Build a supplier that always returns VALUE */
    private static <T> Function<Object[], T> memoize(T value) {
        return (ignored) -> value;
    }

    /** A supplier that calls a default method in the proxied interface and returns its output */
    private static <T> Function<Object[], T> createDefaultMethodSupplier(Method method, Class<T> proxyObjectType, T proxyObject) {
        final MethodHandle methodHandle;

        try {
            if (SystemUtils.IS_JAVA_1_8) {
                Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
                        .getDeclaredConstructor(Class.class, int.class);
                constructor.setAccessible(true);
                methodHandle = constructor.newInstance(proxyObjectType, MethodHandles.Lookup.PRIVATE)
                        .unreflectSpecial(method, proxyObjectType)
                        .bindTo(proxyObject);
            }
            else {
                // Java 9 onwards
                methodHandle = MethodHandles.lookup()
                        .findSpecial(proxyObjectType,
                                method.getName(),
                                MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                                proxyObjectType)
                        .bindTo(proxyObject);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create temporary object for " + proxyObjectType.getName(), e);
        }

        return (args) -> {
            try {
                if (methodHandle.type().parameterCount() == 0) {
                    //noinspection unchecked
                    return (T) methodHandle.invokeWithArguments();
                } else if (args != null) {
                    //noinspection unchecked
                    return (T) methodHandle.invokeWithArguments(args);
                } else {
                    // This is a handle to a method WITH arguments, being called with none. This happens when toString()
                    // is trying to build a representation of a proxy that has a parameterized property AND the interface
                    // provides a default method for it. There's no good default to return here, so we'll just use null
                    return null;
                }
            } catch (Throwable e) {
                maybeWrapThenRethrow(e);
                return null; // Unreachable, but the compiler doesn't know
            }
        };
    }

    /** A value getter for a nested Config proxy */
    protected <T> PropertyValueGetter<T> createInterfaceProperty(String propName, final T proxy) {
        LOG.debug("Creating interface property `{}` for type `{}`", propName, proxy.getClass());
        return (args) -> proxy;
    }

    /**
     * A value getter for a "simple" property. Returns the value set in config for the given propName,
     * or calls the defaultValueSupplier if the property is not set.
     */
    protected <T> PropertyValueGetter<T> createScalarProperty(final Type type, final String propName, Function<Object[], T> defaultValueSupplier) {
        LOG.debug("Creating scalar property `{}` for type `{}`", propName, type.getClass());
        final Property<T> prop = propertyRepository.get(propName, type);
        return args -> {
            T value = prop.get();
            return value != null ? value : defaultValueSupplier.apply(null);
        };
    }

    /**
     * A value getter for a parameterized property. Takes the arguments passed to the method call and interpolates them
     * into the property name from the method's @PropertyName annotation, then returns the value set in config for the
     * computed property name. If not set, it forwards the call with the same parameters to the defaultValueSupplier.
     */
    protected <T> PropertyValueGetter<T> createParameterizedProperty(final Type returnType, final String propertyNameTemplate, Function<Object[], T> defaultValueSupplier) {
        LOG.debug("Creating parameterized property `{}` for type `{}`", propertyNameTemplate, returnType);

        return args -> {
            if (args == null) {
                // Why would args be null if this is a parameterized property? Because toString() abuses its
                // access to this internal representation :-/
                // We'll fall back to trying to call the provider for the default value. That works properly if
                // it comes from an annotation or the known collections. Our wrapper for default interface methods
                // catches this case and just returns a null, which is probably the least bad response.
                return defaultValueSupplier.apply(null);
            }

            // Determine the actual property name by replacing with arguments using the argument index
            // to the method.  For example,
            //      @PropertyName(name="foo.${1}.${0}")
            //      String getFooValue(String arg0, Integer arg1)
            //
            // called as getFooValue("bar", 1) would look for the property 'foo.1.bar'
            String interpolatedPropertyName = new StrSubstitutor(new ArrayLookup<>(args), "${", "}", '$')
                    .replace(propertyNameTemplate);

            //noinspection unchecked
            T result = (T) propertyRepository.get(interpolatedPropertyName, returnType).get();
            if (result == null) {
                result = defaultValueSupplier.apply(args);
            }
            return result;
        };
    }

    private static void maybeWrapThenRethrow(Throwable t) {
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new RuntimeException(t);
    }

    private static <T> void warnWhenTooMany(Map<T, Integer> counters, T countKey, int limit, Supplier<String> objectDescription) {
        int currentCount = counters.merge(countKey, 1, Integer::sum);

        // Emit warning if we're over the limit BUT only when the current count is a multiple of the limit :-)
        // This is to avoid being *too* noisy
        if (LOG.isWarnEnabled() &&
            currentCount >= limit &&
            (currentCount % limit == 0 )) {

                LOG.warn(
                        "Too many {} objects are being created ({} so far).\n" +
                        "Please review the calling code to prevent memory leaks.\n" +
                        "Normal usage for ConfigProxyFactory is to create singletons via your DI mechanism.\n" +
                        "For special use cases that *require* creating multiple instances you can tune reporting\n" +
                        "by setting the `{}` config key to a higher threshold.\nStack trace for debugging follows:",
                        objectDescription.get(), currentCount, EXCESSIVE_PROXIES_LIMIT, new Throwable());
        }
    }

    /** InvocationHandler for config proxies. */
    private static class ConfigProxyInvocationHandler<P> implements InvocationHandler {
        private final Map<Method, PropertyValueGetter<?>> invokers;
        private final Class<P> type;
        private final String prefix;
        private final Map<Method, String> propertyNames;

        public ConfigProxyInvocationHandler(Class<P> proxiedClass, String prefix, Map<Method, PropertyValueGetter<?>> invokers, Map<Method, String> propertyNames) {
            this.invokers = invokers;
            this.type = proxiedClass;
            this.prefix = prefix;
            this.propertyNames = propertyNames;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws NoSuchMethodError{
            PropertyValueGetter<?> invoker = invokers.get(method);
            if (invoker != null) {
                return invoker.invoke(args);
            }

            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return proxyToString();
                default:
                    throw new NoSuchMethodError(method.getName() + " not found on interface " + type.getName());
            }
        }

        /**
         * Create a reasonable string representation of the proxy object: "InterfaceName[propName=currentValue, ...]".
         * For the case of parameterized properties, fudges it and just uses "null" as the value.
         */
        private String proxyToString() {
            String propertyNamesAndValues = invokers.entrySet().stream()
                    .map(this::toNameAndValue)
                    .collect(Collectors.joining(","));

            return String.format("%s[%s]", type.getSimpleName(), propertyNamesAndValues);
        }

        /** Maps one (method, valueGetter) entry to a "propertyName=value" string */
        private String toNameAndValue(Map.Entry<Method, PropertyValueGetter<?>> entry) {
            String propertyName = propertyNames.get(entry.getKey()).substring(prefix.length());
            Object propertyValue;
            try {
                // This call should fail for parameterized properties, because the PropertyValueGetter has a non-empty
                // argument list. Fortunately, the implementation there cooperates with us and returns a null instead :-)
                propertyValue = entry.getValue().invoke(null);
            } catch (Exception e) {
                // Just in case
                propertyValue = e.getMessage();
            }

            return String.format("%s='%s'", propertyName, propertyValue);
        }
    }

    /**
     * A holder for the two pieces of information we compute for each method: Its invoker and the property's name.
     * This would just be a record in Java 17 :-)
     */
    private static class MethodInvokerHolder<T> {
        final PropertyValueGetter<T> invoker;
        final String propertyName;

        private MethodInvokerHolder(PropertyValueGetter<T> invoker, String propertyName) {
            this.invoker = invoker;
            this.propertyName = propertyName;
        }
    }

    /** Key to index counts of created proxies */
    private static final class InterfaceAndPrefix {
        final Class<?> configInterface;
        final String prefix;

        private InterfaceAndPrefix(Class<?> configInterface, String prefix) {
            this.configInterface = configInterface;
            this.prefix = prefix;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            InterfaceAndPrefix that = (InterfaceAndPrefix) o;
            return Objects.equals(configInterface, that.configInterface) &&
                   Objects.equals(prefix, that.prefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(configInterface, prefix);
        }
    }

    /** Implement apache-commons StrLookup by interpreting the key as an index into an array */
    private static class ArrayLookup<V> extends StrLookup<V> {
        private final V[] elements;

        private ArrayLookup(V[] elements) {
            super();
            this.elements = elements;
        }

        @Override
        public String lookup(String key) {
            if (elements == null || elements.length == 0 || StringUtils.isBlank(key)) {
                return null;
            }

            try {
                int index = Integer.parseInt(key);
                if (index < 0 || index >= elements.length || elements[index] == null) {
                    return null;
                }
                return elements[index].toString();
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
