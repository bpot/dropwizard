package io.dropwizard.jersey;

import io.dropwizard.jersey.caching.CacheControlledResponseFeature;
import io.dropwizard.jersey.errors.LoggingExceptionMapper;
import io.dropwizard.jersey.guava.OptionalQueryParamValueFactoryProvider;
import io.dropwizard.jersey.guava.OptionalResourceMethodResponseWriter;
import io.dropwizard.jersey.jackson.JsonProcessingExceptionMapper;
import io.dropwizard.jersey.sessions.SessionFactoryProvider;
import io.dropwizard.jersey.validation.ConstraintViolationExceptionMapper;

import java.util.List;

import javax.ws.rs.Path;
import javax.ws.rs.ext.Provider;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jersey2.InstrumentedResourceMethodApplicationListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

public class DropwizardResourceConfig extends ResourceConfig {
    private static final String NEWLINE = String.format("%n");
    private static final Logger LOGGER = LoggerFactory.getLogger(DropwizardResourceConfig.class);
    private String urlPattern;
    
    private static class ComponentLoggingListener implements ApplicationEventListener
    {
        final DropwizardResourceConfig config;
        
        public ComponentLoggingListener(DropwizardResourceConfig config)
        {
            this.config = config;
        }

        @Override
        public void onEvent(ApplicationEvent event) {
           if (event.getType() == ApplicationEvent.Type.INITIALIZATION_APP_FINISHED)
               this.config.logComponents();
            
        }

        @Override
        public RequestEventListener onRequest(RequestEvent requestEvent) {
            return null;
        }
        
    }

    public static DropwizardResourceConfig forTesting(MetricRegistry metricRegistry) {
        return new DropwizardResourceConfig(true, metricRegistry);
    }

    public DropwizardResourceConfig(MetricRegistry metricRegistry) {
        this(false, metricRegistry);
    }
    
    public DropwizardResourceConfig() {
        this(true, null);
    }

    private DropwizardResourceConfig(boolean testOnly, MetricRegistry metricRegistry) {
        super();
        
        if (metricRegistry == null)
            metricRegistry = new MetricRegistry();
        
        urlPattern = "/*";
        
        property(ServerProperties.WADL_FEATURE_DISABLE, Boolean.TRUE);
        if (!testOnly) {
            // create a subclass to pin it to Throwable
            register(new LoggingExceptionMapper<Throwable>() {});
            register(new ConstraintViolationExceptionMapper());
            register(new JsonProcessingExceptionMapper());
            register(new ComponentLoggingListener(this));
        }
        register(new InstrumentedResourceMethodApplicationListener(metricRegistry));
        register(CacheControlledResponseFeature.class);
        register(OptionalResourceMethodResponseWriter.class);
        register(new OptionalQueryParamValueFactoryProvider.Binder());
        register(new SessionFactoryProvider.Binder());
    }

    public void logComponents() {
        logResources();
        logProviders();
        logEndpoints();
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
    }

    private Set<String> getResources() {
        final ImmutableSet.Builder<String> builder = ImmutableSet.builder();

        for (Class<?> klass : getClasses()) {
            if (ResourceConfig.isRootResourceClass(klass)) {
                builder.add(klass.getCanonicalName());
            }
        }

        for (Object o : getSingletons()) {
            if (ResourceConfig.isRootResourceClass(o.getClass())) {
                builder.add(o.getClass().getCanonicalName());
            }
        }

        LOGGER.debug("resources = {}", builder.build());
    }

    private Set<String> getProviders() {
        final ImmutableSet.Builder<String> builder = ImmutableSet.builder();

        for (Class<?> klass : getClasses()) {
            if (ResourceConfig.isProviderClass(klass)) {
                builder.add(klass.getCanonicalName());
            }
        }

        for (Object o : getSingletons()) {
            if (ResourceConfig.isProviderClass(o.getClass())) {
                builder.add(o.getClass().getCanonicalName());
            }
        }

        return builder.build();
    }

    public String getEndpointsInfo() {
        final StringBuilder msg = new StringBuilder(1024);
        msg.append("The following paths were found for the configured resources:");
        msg.append(NEWLINE).append(NEWLINE);

        final ImmutableList.Builder<Class<?>> builder = ImmutableList.builder();
        for (Object o : getSingletons()) {
            if (ResourceConfig.isRootResourceClass(o.getClass())) {
                builder.add(o.getClass());
            }
        }
        for (Class<?> klass : getClasses()) {
            if (ResourceConfig.isRootResourceClass(klass)) {
                builder.add(klass);
            }
        }

        String rootPath = urlPattern;
        if (rootPath.endsWith("/*")) {
            rootPath = rootPath.substring(0, rootPath.length() - 1);
        }

        for (Class<?> klass : builder.build()) {
            final List<String> endpoints = Lists.newArrayList();
            populateEndpoints(endpoints, rootPath, klass, false);

            for (String line : Ordering.natural().sortedCopy(endpoints)) {
                msg.append(line).append(NEWLINE);
            }
        }

        return msg.toString();
    }

    private void populateEndpoints(List<String> endpoints, String basePath, Class<?> klass,
                                   boolean isLocator) {
        populateEndpoints(endpoints, basePath, klass, isLocator, Resource.from(klass));
    }

    private void populateEndpoints(List<String> endpoints, String basePath, Class<?> klass,
                                   boolean isLocator, Resource resource) {
        if (!isLocator) {
            basePath = normalizePath(basePath, resource.getPath());
        }

        for (ResourceMethod method : resource.getResourceMethods()) {
            endpoints.add(formatEndpoint(method.getHttpMethod(), basePath, klass));
        }
        
        for (Resource childResource : resource.getChildResources())
        {
            for (ResourceMethod method : childResource.getResourceMethods())
            {
                if (method.getType() == ResourceMethod.JaxrsType.RESOURCE_METHOD)
                {
                    final String path = normalizePath(basePath, childResource.getPath());
                    endpoints.add(formatEndpoint(method.getHttpMethod(), path, klass));
                }
                else if (method.getType() == ResourceMethod.JaxrsType.SUB_RESOURCE_LOCATOR) {
                    final String path = normalizePath(basePath, childResource.getPath());
                    populateEndpoints(endpoints, path, method.getInvocable().getRawResponseType(), true);
                }
            }
        }
    }

    private static String formatEndpoint(String method, String path, Class<?> klass) {
        return String.format("    %-7s %s (%s)", method, path, klass.getCanonicalName());
    }

    private static String normalizePath(String basePath, String path) {
        if (basePath.endsWith("/")) {
            return path.startsWith("/") ? basePath + path.substring(1) : basePath + path;
        }
        return path.startsWith("/") ? basePath + path : basePath + "/" + path;
    }
}
