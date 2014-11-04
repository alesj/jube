/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.quickstarts.cxfcdi;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ListenerInfo;
import org.apache.cxf.cdi.CXFCdiServlet;
import org.jboss.weld.environment.servlet.BeanManagerResourceBindingListener;
import org.jboss.weld.environment.servlet.Listener;

public final class ApplicationStarter {

    private ApplicationStarter() {
    }

    public static void main(final String[] args) throws Exception {

        // use system property first
        String port = System.getProperty("server.port");
        if (port == null) {
            // and fallback to use environment variable
            port = System.getenv("FABRIC8_HTTP_PORT");
        }
        if (port == null) {
            // and use port 8585 by default
            port = "8585";
        }
        int num = Integer.parseInt(port);

        System.out.println("Starting REST server on port: " + port);

        // Register and map the dispatcher servlet
        startServer(num);
    }

    private static void startServer(int port) throws Exception {
        DeploymentInfo servletBuilder = Servlets.deployment()
            .setClassLoader(ApplicationStarter.class.getClassLoader())
            .setContextPath("/")
            .setDeploymentName("cxfcdi.war")
            .addServlets(Servlets.servlet("CXFCDIServlet", CXFCdiServlet.class).addMapping("/cxfcdi/*"))
            .addListener(new ListenerInfo(Listener.class))
            .addListener(new ListenerInfo(BeanManagerResourceBindingListener.class));

        DeploymentManager manager = Servlets.defaultContainer().addDeployment(servletBuilder);
        manager.deploy();
        PathHandler path = Handlers.path(Handlers.redirect("/")).addPrefixPath("/", manager.start());

        Undertow server = Undertow.builder()
            .addHttpListener(port, "localhost")
            .setHandler(path)
            .build();
        server.start();
    }
}
