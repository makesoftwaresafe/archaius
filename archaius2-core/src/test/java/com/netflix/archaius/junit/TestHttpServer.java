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
package com.netflix.archaius.junit;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import com.netflix.archaius.util.ThreadFactories;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Rule for running an embedded HTTP server for basic testing.  The
 * server uses ephemeral ports to ensure there are no conflicts when
 * running concurrent unit tests
 * 
 * The server uses HttpServer classes available in the JVM to avoid
 * pulling in any extra dependencies.
 * 
 * Available endpoints are,
 * 
 * /                      Returns a 200
 * /status?code=${code}   Returns a request provide code
 * /noresponse            No response from the server
 * 
 * Optional query parameters
 * delay=${delay}         Inject a delay into the request
 * 
 * @author elandau
 *
 */
public class TestHttpServer implements BeforeAllCallback, AfterAllCallback, ParameterResolver {
    public static final String INTERNALERROR_PATH = "/internalerror";
    public static final String NORESPONSE_PATH = "/noresponse";
    public static final String STATUS_PATH = "/status";
    public static final String OK_PATH = "/ok";
    public static final String ROOT_PATH = "/";
    
    private static final int DEFAULT_THREAD_COUNT = 10;
    private static final String DELAY_QUERY_PARAM = "delay";
    
    private HttpServer server;
    private int localHttpServerPort = 0;
    private ExecutorService service;
    private int threadCount = DEFAULT_THREAD_COUNT;
    private final LinkedHashMap<String, HttpHandler> handlers = new LinkedHashMap<>();
    
    private final String GENERIC_RESPONSE = "GenericTestHttpServer Response";

    public TestHttpServer() {
        handlers.put(ROOT_PATH, new TestHttpHandler() {
            @Override
            protected void handle(RequestContext context) throws IOException {
                context.response(200, GENERIC_RESPONSE);
            }});

        handlers.put(OK_PATH, new TestHttpHandler() {
            @Override
            protected void handle(RequestContext context) throws IOException {
                context.response(200, GENERIC_RESPONSE);
            }});

        handlers.put(STATUS_PATH, new TestHttpHandler() {
            @Override
            protected void handle(RequestContext context) throws IOException {
                context.response(Integer.parseInt(context.query("code")), GENERIC_RESPONSE);
            }});

        handlers.put(NORESPONSE_PATH, new TestHttpHandler() {
            @Override
            protected void handle(RequestContext context) throws IOException {
            }});

        handlers.put(INTERNALERROR_PATH, new TestHttpHandler() {
            @Override
            protected void handle(RequestContext context) throws IOException {
                throw new RuntimeException("InternalError");
            }});
    }

    public TestHttpServer handler(String path, HttpHandler handler) {
        handlers.put(path, handler);
        return this;
    }
    
    public TestHttpServer port(int port) {
        this.localHttpServerPort = port;
        return this;
    }
    
    public TestHttpServer threadCount(int threads) {
        this.threadCount = threads;
        return this;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        this.service = Executors.newFixedThreadPool(
                threadCount,
                ThreadFactories.newNamedDaemonThreadFactory("TestHttpServer-%d"));

        InetSocketAddress inetSocketAddress = new InetSocketAddress("localhost", 0);
        server = HttpServer.create(inetSocketAddress, 0);
        server.setExecutor(service);

        for (Entry<String, HttpHandler> handler : handlers.entrySet()) {
            server.createContext(handler.getKey(), handler.getValue());
        }

        server.start();
        localHttpServerPort = server.getAddress().getPort();

        System.out.println("TestServer is started: " + getServerUrl());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        server.stop(0);
        ((ExecutorService) server.getExecutor()).shutdownNow();

        System.out.println("TestServer is shutdown: " + getServerUrl());
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType().equals(TestHttpServer.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return this;
    }

    private interface RequestContext {
        void response(int code, String body) throws IOException;
        String query(String key);
    }
    
    private static abstract class TestHttpHandler implements HttpHandler {
        @Override
        public final void handle(final HttpExchange t) throws IOException {
            try {
                final Map<String, String> queryParameters = queryToMap(t);
                
                if (queryParameters.containsKey(DELAY_QUERY_PARAM)) {
                    long delay = Long.parseLong(queryParameters.get(DELAY_QUERY_PARAM));
                    try {
                        TimeUnit.MILLISECONDS.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                handle(new RequestContext() {
                    @Override
                    public void response(int code, String body) throws IOException {
                        OutputStream os = t.getResponseBody();
                        t.sendResponseHeaders(code, body.length());
                        os.write(body.getBytes());
                        os.close();
                    }
    
                    @Override
                    public String query(String key) {
                        return queryParameters.get(key);
                    } 
                });
            }
            catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                String body = sw.toString();
                
                OutputStream os = t.getResponseBody();
                t.sendResponseHeaders(500, body.length());
                os.write(body.getBytes());
                os.close();
            }
        }
        
        protected abstract void handle(RequestContext context) throws IOException;
        
        private static Map<String, String> queryToMap(HttpExchange t) {
            String queryString = t.getRequestURI().getQuery();
            Map<String, String> result = new HashMap<>();
            if (queryString != null) {
                for (String param : queryString.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1) {
                        result.put(pair[0], pair[1]);
                    }
                    else{
                        result.put(pair[0], "");
                    }
                }
            }
            return result;
        }

    }

    /**
     * @return Get the root server URL
     */
    public String getServerUrl() {
        return "http://localhost:" + localHttpServerPort;
    }

    /**
     * @return Get the root server URL
     * @throws URISyntaxException 
     */
    public URI getServerURI() throws URISyntaxException {
        return new URI(getServerUrl());
    }

    /**
     * @param path
     * @return  Get a path to this server
     */
    public String getServerPath(String path) {
        return getServerUrl() + path;
    }

    /**
     * @param path
     * @return  Get a path to this server
     */
    public URI getServerPathURI(String path) throws URISyntaxException {
        return new URI(getServerUrl() + path);
    }

    /**
     * @return Return the ephemeral port used by this server
     */
    public int getServerPort() {
        return localHttpServerPort;
    }
}