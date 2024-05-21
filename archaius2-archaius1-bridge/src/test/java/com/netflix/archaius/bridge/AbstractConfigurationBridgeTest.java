package com.netflix.archaius.bridge;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.MapConfiguration;
import org.apache.commons.configuration.event.ConfigurationListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.netflix.archaius.api.Config;
import com.netflix.archaius.api.annotations.ConfigurationSource;
import com.netflix.archaius.api.config.SettableConfig;
import com.netflix.archaius.api.inject.RuntimeLayer;
import com.netflix.archaius.guice.ArchaiusModule;
import com.netflix.config.AggregatedConfiguration;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.config.DeploymentContext.ContextKey;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AbstractConfigurationBridgeTest extends BaseBridgeTest  {
    public static class TestModule extends AbstractModule {
        private Properties properties;
        TestModule(Properties props) {
            this.properties = props;
        }
        
        TestModule() {
            this.properties = new Properties();
        }
        
        @Override
        protected void configure() {
            install(new StaticArchaiusBridgeModule());
            install(new ArchaiusModule().withApplicationOverrides(properties)) ;
            
            bind(SomeClient.class).asEagerSingleton();
        }
    }
    private static SettableConfig settable;
    private static Injector injector;
    private static AbstractConfiguration commonsConfig;
    private static Config config;
    
    @BeforeAll
    public static void before() {
        final Properties props = new Properties();
        props.setProperty("app.override.foo", "bar");
        props.setProperty(ContextKey.environment.getKey(), "test");
        
        injector = Guice.createInjector(
            new TestModule(props),
            new AbstractModule() {
                @Override
                protected void configure() {
                    bind(SomeClient.class).asEagerSingleton();
                }
            });
        
        config = injector.getInstance(Config.class);
        settable = injector.getInstance(Key.get(SettableConfig.class, RuntimeLayer.class));
        assertTrue(ConfigurationManager.isConfigurationInstalled());
        commonsConfig = ConfigurationManager.getConfigInstance();
        assertEquals(StaticAbstractConfiguration.class, commonsConfig.getClass());
    }
    
    @Test
    public void testBasicWiring() {
        SomeClient client = injector.getInstance(SomeClient.class);
        assertNotNull(ConfigurationManager.getConfigInstance());
        assertEquals("bar", client.fooValue);
    }
    
    @ConfigurationSource(value={"AbstractConfigurationBridgeTest_libA"})
    static class LibA {
    }
    
    @ConfigurationSource(value={"AbstractConfigurationBridgeTest_libB"})
    static class LibB {
    }
    
    @Test
    public void lastLoadedLibraryWins() {
        Config config = injector.getInstance(Config.class);
        
        injector.getInstance(LibA.class);
        
        assertTrue(config.getBoolean("libA.loaded",  false));
        assertEquals("libA", config.getString("lib.override", null));
        
        injector.getInstance(LibB.class);
        
        assertTrue(config.getBoolean("libB.loaded", false));
        assertEquals("libA", config.getString("lib.override", null));
    }
    
    @Test
    public void basicBridgeTest() throws IOException {
        DeploymentContext context1 = ConfigurationManager.getDeploymentContext();
        assertNotNull(context1);
        assertEquals("test", context1.getDeploymentEnvironment());
        
        AbstractConfiguration config1 = ConfigurationManager.getConfigInstance();
        DeploymentContext contextDi = injector.getInstance(DeploymentContext.class);
        assertNotSame(contextDi, context1);
        ConfigurationManager.loadCascadedPropertiesFromResources("AbstractConfigurationBridgeTest_libA");
        assertTrue(config1.getBoolean("libA.loaded",  false));
        assertEquals("libA", config1.getString("lib.override", null));
        
        Config config2 = injector.getInstance(Config.class);
        SettableConfig settable = injector.getInstance(Key.get(SettableConfig.class, RuntimeLayer.class));
        settable.setProperty("@environment", "foo");
        
        DeploymentContext context2 = ConfigurationManager.getDeploymentContext();
        
        assertEquals("foo", ConfigurationManager.getDeploymentContext().getDeploymentEnvironment());
        assertEquals("foo", context2.getDeploymentEnvironment());
        assertNotSame(contextDi, context1);
        assertEquals("foo", context1.getDeploymentEnvironment());
        
        assertTrue(config2.getBoolean("libA.loaded",  false));
        assertEquals("libA", config2.getString("lib.override", null));
        
        ConfigurationManager.loadCascadedPropertiesFromResources("AbstractConfigurationBridgeTest_libB");
        assertTrue(config1.getBoolean("libB.loaded", false));
        assertEquals("libA", config1.getString("lib.override", null));
        assertTrue(config2.getBoolean("libB.loaded", false));
        assertEquals("libA", config2.getString("lib.override", null));
        
    }
    
    /**
     * This test was written to confirm the legacy API behavior.  It cannot be run 
     * with the other tests since the static state of ConfigurationManager cannot
     * be reset between tests.
     * @throws IOException
     */
    @Test
    public void testBridgePropertiesFromLegacyToNew() throws IOException {
        AbstractConfiguration config1 = ConfigurationManager.getConfigInstance();
        Config                config2 = injector.getInstance(Config.class);
        
        ConfigurationManager.loadCascadedPropertiesFromResources("AbstractConfigurationBridgeTest_libA");
        assertTrue(config1.getBoolean("libA.loaded",  false));
        assertEquals("libA", config1.getString("lib.override", null));
        assertTrue(config2.getBoolean("libA.loaded",  false));
        assertEquals("libA", config2.getString("lib.override", null));
        
        ConfigurationManager.loadCascadedPropertiesFromResources("AbstractConfigurationBridgeTest_libB");
        assertTrue(config1.getBoolean("libB.loaded", false));
        assertEquals("libA", config1.getString("lib.override", null));
        assertTrue(config2.getBoolean("libB.loaded", false));
        assertEquals("libA", config2.getString("lib.override", null));

    }

    /**
     * This test was written to confirm the legacy API behavior.  It cannot be run 
     * with the other tests since the static state of ConfigurationManager cannot
     * be reset between tests.
     * @throws IOException
     */
    @Test
    public void confirmLegacyOverrideOrder() throws IOException {
        AbstractConfiguration config = ConfigurationManager.getConfigInstance();
        
        ConfigurationManager.loadCascadedPropertiesFromResources("AbstractConfigurationBridgeTest_libA");
        assertTrue(config.getBoolean("libA.loaded",  false));
        assertEquals("libA", config.getString("lib.override", null));
        
        ConfigurationManager.loadCascadedPropertiesFromResources("AbstractConfigurationBridgeTest_libB");
        assertTrue(config.getBoolean("libB.loaded", false));
        assertEquals("libA", config.getString("lib.override", null));
        
        ConfigurationManager.loadCascadedPropertiesFromResources("AbstractConfigurationBridgeTest_libB");
    }
    
    /**
     * This test was written to confirm the legacy API behavior.  It cannot be run 
     * with the other tests since the static state of ConfigurationManager cannot
     * be reset between tests.
     * @throws IOException
     * @throws ConfigurationException 
     */
    @Test
    public void confirmLegacyOverrideOrderResources() throws IOException, ConfigurationException {
    	super.confirmLegacyOverrideOrderResources();
    	
    	assertEquals("libA", config.getString("lib.legacy.override"));
        assertTrue(config.getBoolean("libA.legacy.loaded"));
        assertTrue(config.getBoolean("libB.legacy.loaded"));
    }
    
    /**
     * This test was written to confirm the legacy API behavior.  It cannot be run 
     * with the other tests since the static state of ConfigurationManager cannot
     * be reset between tests.
     * @throws IOException
     */
    @Test
    public void confirmLegacyOverrideOrderAddConfig() throws IOException {
        AggregatedConfiguration aggregatedConfig = (AggregatedConfiguration) ConfigurationManager.getConfigInstance();
        
        Properties p1 = new Properties();
        p1.setProperty("lib.override", "libA");
        p1.setProperty("libA.loaded", "true");
        aggregatedConfig.addConfiguration(new MapConfiguration(p1));
        
        assertTrue(aggregatedConfig.getBoolean("libA.loaded",  false));
        assertEquals("libA", aggregatedConfig.getString("lib.override", null));
        
        Properties p2 = new Properties();
        p2.setProperty("lib.override", "libB");
        p2.setProperty("libB.loaded", "true");
        aggregatedConfig.addConfiguration(new MapConfiguration(p2));
        
        assertTrue(aggregatedConfig.getBoolean("libB.loaded", false));
        assertEquals("libA", aggregatedConfig.getString("lib.override", null));
    }
    
    @Test
    public void testCommonConfigurationListener() {
        ConfigurationListener listener = Mockito.mock(ConfigurationListener.class);
    	AbstractConfiguration config = ConfigurationManager.getConfigInstance();
    	config.addConfigurationListener(listener);
    	
    	SettableConfig settable = injector.getInstance(Key.get(SettableConfig.class, RuntimeLayer.class));
    	settable.setProperty("new_property", "new_value");
    	
    	Mockito.verify(listener, Mockito.times(2)).configurationChanged(Mockito.any());
    	
    }

    @Test
    public void verifyValueInterpollation() {
        AbstractConfiguration config = ConfigurationManager.getConfigInstance();
        config.setProperty("foo", "${ABC:true}");
        boolean value = config.getBoolean("foo");
        assertTrue(value);
    }

    @Test
    public void verifyMissingProperty() {
        AbstractConfiguration config = ConfigurationManager.getConfigInstance();
        Boolean value = config.getBoolean("foo", null);
        assertNull(value);
    }
}
