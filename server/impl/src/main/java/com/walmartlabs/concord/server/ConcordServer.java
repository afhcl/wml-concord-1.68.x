package com.walmartlabs.concord.server;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.walmartlabs.concord.server.cfg.CustomFormConfiguration;
import com.walmartlabs.concord.server.cfg.ServerConfiguration;
import com.walmartlabs.concord.server.security.ConcordAuthenticatingFilter;
import com.walmartlabs.concord.server.security.ConcordSecurityModule;
import com.walmartlabs.concord.server.security.GithubAuthenticatingFilter;
import com.walmartlabs.concord.server.security.apikey.ApiKeyRealm;
import com.walmartlabs.concord.server.security.github.GithubRealm;
import com.walmartlabs.concord.server.security.internal.InternalRealm;
import com.walmartlabs.concord.server.security.ldap.LdapRealm;
import com.walmartlabs.concord.server.security.sessionkey.SessionKeyRealm;
import com.walmartlabs.concord.server.security.sso.SsoAuthFilter;
import com.walmartlabs.concord.server.security.sso.SsoCallbackFilter;
import com.walmartlabs.concord.server.security.sso.SsoLogoutFilter;
import com.walmartlabs.concord.server.security.sso.SsoRealm;
import com.walmartlabs.concord.server.websocket.ConcordWebSocketServlet;
import com.walmartlabs.ollie.OllieServer;
import com.walmartlabs.ollie.OllieServerBuilder;
import com.walmartlabs.ollie.SessionCookieOptions;
import io.prometheus.client.exporter.MetricsServlet;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ConcordServer {

    private static final Logger log = LoggerFactory.getLogger(ConcordServer.class);

    public void start() {
        log.info("Using the API port: {}", ServerConfiguration.port);

        OllieServerBuilder builder = OllieServer.builder()
                .port(ServerConfiguration.port)
                .apiPatterns("/api/*", "/events/github/*")
                .name("concord-server")
                .module(new ServerModule())
                .securityModuleProvider(ConcordSecurityModule::new)
                .packageToScan("com.walmartlabs.concord.server")
                .realm(InternalRealm.class)
                .realm(ApiKeyRealm.class)
                .realm(SessionKeyRealm.class)
                .realm(LdapRealm.class)
                .realm(GithubRealm.class)
                .realm(SsoRealm.class)
                .filterChain("/api/service/sso/auth", SsoAuthFilter.class)
                .filterChain("/api/service/sso/redirect", SsoCallbackFilter.class)
                .filterChain("/api/service/sso/logout", SsoLogoutFilter.class)
                .filterChain("/api/**", ConcordAuthenticatingFilter.class)
                .filterChain("/forms/**", ConcordAuthenticatingFilter.class)
                .filterChain("/jolokia/**", ConcordAuthenticatingFilter.class)
                .filterChain("/events/github/**", GithubAuthenticatingFilter.class)
                .serve("/forms/*").with(DefaultServlet.class, formsServletParams())
                .serve("/logs/*").with(LogServlet.class) // backward compatibility
                .serve("/metrics").with(MetricsServlet.class) // prometheus integration
                .serve("/concord/*").with(new ServiceInitServlet()) // only to start the background services
                .serve("/websocket").with(new ConcordWebSocketServlet())
                .at("/resources/console").resource("/com/walmartlabs/concord/server/console/static")
                .filter("/service/*", "/api/*", "/logs/*", "/forms/*").through(RequestIdFilter.class)
                .filter("/service/*", "/api/*", "/logs/*", "/forms/*").through(CORSFilter.class)
                .filter("/service/*", "/api/*", "/logs/*", "/forms/*").through(NoCacheFilter.class)
                .sessionCookieOptions(getSessionCookieOptions())
                .sessionsEnabled(true)
                .sessionMaxInactiveInterval(ServerConfiguration.sessionTimeout)
                .jmxEnabled(true);

        OllieServer server = builder.build();
        server.start();
    }

    private static Map<String, String> formsServletParams() {
        Map<String, String> m = new HashMap<>();

        m.put("acceptRanges", "true");
        m.put("dirAllowed", "false");
        m.put("resourceBase", CustomFormConfiguration.baseDir.toAbsolutePath().toString());
        m.put("pathInfoOnly", "true");
        m.put("redirectWelcome", "false");

        return m;
    }

    private static Set<SessionCookieOptions> getSessionCookieOptions() {
        Set<SessionCookieOptions> s = new HashSet<>();
        s.add(SessionCookieOptions.HTTP_ONLY);
        if (ServerConfiguration.secureCookies) {
            s.add(SessionCookieOptions.SECURE);
        }
        return s;
    }

    public static class LogServlet extends HttpServlet {

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            String uri = req.getRequestURI();

            int i = uri.lastIndexOf("/");
            int len = uri.length();
            if (i < 0 || i + 1 >= len || !uri.endsWith(".log")) {
                throw new ServletException("Unknown request: " + uri);
            }

            String instanceId = uri.substring(i + 1, len - 4);
            RequestDispatcher dispatcher = req.getRequestDispatcher("/api/v1/process/" + instanceId + "/log");
            dispatcher.forward(req, resp);
        }
    }

    public static class ServiceInitServlet extends HttpServlet {

        @Inject
        Set<BackgroundTask> tasks; // NOSONAR

        @Override
        public void init() throws ServletException {
            super.init();

            ServletContext ctx = getServletContext();
            Injector injector = (Injector) ctx.getAttribute(Injector.class.getName());

            injector.injectMembers(this);

            if (tasks != null) {
                tasks.forEach(BackgroundTask::start);
            }
        }

        @Override
        public void destroy() {
            if (tasks != null) {
                tasks.forEach(BackgroundTask::stop);
            }

            super.destroy();
        }
    }
}
