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
package org.jboss.jube.main;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import io.fabric8.kubernetes.api.KubernetesClient;
import io.fabric8.kubernetes.api.KubernetesFactory;
import io.fabric8.kubernetes.api.KubernetesManager;
import io.fabric8.kubernetes.template.TemplateManager;
import io.fabric8.utils.Systems;
import io.hawt.aether.AetherFacade;
import io.hawt.git.GitFacade;
import io.hawt.kubernetes.KubernetesService;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ListenerInfo;
import io.undertow.servlet.api.ServletContainer;
import org.apache.cxf.cdi.CXFCdiServlet;
import org.jboss.jube.apimaster.ApiMasterService;
import org.jboss.weld.environment.servlet.BeanManagerResourceBindingListener;
import org.jboss.weld.environment.servlet.Listener;

/**
 * A main class to run jube.
 */
public final class Main {

    private Main() {
        // run as main class
    }

    public static void main(final String[] args) throws Exception {
        try {
            System.setProperty("hawtio.authenticationEnabled", "false");

            String port = Systems.getEnvVarOrSystemProperty("HTTP_PORT", "HTTP_PORT", "8585");
            int portNumber = Integer.parseInt(port);

            System.out.println("Starting REST server on port: " + port);

            ServletContainer container = Servlets.defaultContainer();
            PathHandler pathHandler = new PathHandler();

            initaliseGitStuff();

            // lets find wars on the classpath
            Set<String> foundURLs = new HashSet<>();
            findWarsOnClassPath(container, pathHandler, Thread.currentThread().getContextClassLoader(), foundURLs, portNumber);

            ClassLoader classLoader = Main.class.getClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);


            findWarsOnClassPath(container, pathHandler, classLoader, foundURLs, portNumber);

            // In case you want to run in an IDE, and it does not setup the classpath right.. lets
            // find the .war files in the maven dir.  Assumes you set the working dir to the target/jube dir.
            if (foundURLs.isEmpty()) {
                File[] files = new File("maven").listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".war")) {
                            createWebapp(container, pathHandler, foundURLs, portNumber, file.getAbsolutePath());
                        }
                    }
                }
            }

            if (foundURLs.isEmpty()) {
                System.out.println("WARNING: did not find any war files on the classpath to embed!");
            }

            initialiseHawtioStuff();

            DeploymentInfo servletBuilder = Servlets.deployment()
                .setClassLoader(classLoader)
                .setContextPath("/")
                .setDeploymentName("cxfcdi.war")
                .addServlets(Servlets.servlet("CXFCDIServlet", CXFCdiServlet.class).addMapping("/api/*").addInitParam("service-list-path", "/cxf/servicesList"))
                .addListener(new ListenerInfo(Listener.class))
                .addListener(new ListenerInfo(BeanManagerResourceBindingListener.class));

            DeploymentManager deploymentManager = container.addDeployment(servletBuilder);
            deploymentManager.deploy();
            pathHandler.addExactPath(servletBuilder.getContextPath(), deploymentManager.start());

            Undertow server = Undertow.builder()
                .addHttpListener(portNumber, "localhost")
                .setHandler(pathHandler)
                .build();
            server.start();
        } catch (Throwable e) {
            logException(e);
        }
    }

    protected static void initialiseHawtioStuff() throws Exception {
        final String kubernetesAddress = "http://" + ApiMasterService.getHostName() + ":8585/";
        KubernetesClient kubernetesClient = new KubernetesClient(new KubernetesFactory(kubernetesAddress));

        KubernetesService kubernetesService = new KubernetesService() {
            @Override
            public String getKubernetesAddress() {
                return kubernetesAddress;
            }
        };
        kubernetesService.init();

        AetherFacade aetherFacade = new AetherFacade();
        aetherFacade.init();

        KubernetesManager kubernetesManager = new KubernetesManager();
        kubernetesManager.setKubernetes(kubernetesClient);
        kubernetesManager.init();

        TemplateManager templateManager = new TemplateManager();
        templateManager.init();
    }

    private static void initaliseGitStuff() throws Exception {
        GitFacade gitFacade = new GitFacade();
        gitFacade.init();
    }

    protected static void logException(Throwable e) {
        if (e instanceof InvocationTargetException) {
            InvocationTargetException invocationTargetException = (InvocationTargetException) e;
            e = invocationTargetException.getTargetException();
            System.out.println("=== was InvocationTargetException caught: " + e);
        }
        Set<Throwable> exceptions = new HashSet<>();
        exceptions.add(e);
        System.out.println("CAUGHT: " + e);
        e.printStackTrace();

        // show all causes
        Throwable t = e;
        while (true) {
            Throwable cause = t.getCause();
            if (cause != null && exceptions.add(cause)) {
                System.out.println();
                System.out.println("Caused by: " + cause);
                cause.printStackTrace();
                t = cause;
            } else {
                break;
            }
        }
    }

    protected static void findWarsOnClassPath(ServletContainer container, PathHandler pathHandler, ClassLoader classLoader, Set<String> foundURLs, Integer port) {
        try {
            Enumeration<URL> resources = classLoader.getResources("WEB-INF/web.xml");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String text = url.toString();
                if (text.startsWith("jar:")) {
                    text = text.substring(4);
                }
                createWebapp(container, pathHandler, foundURLs, port, text);
            }
        } catch (Exception e) {
            System.out.println("Failed to find web.xml on classpath: " + e);
            e.printStackTrace();
        }

    }

    private static void createWebapp(ServletContainer container, PathHandler pathHandler, Set<String> foundURLs, Integer port, String war) {
        if (foundURLs.add(war)) {
            String contextPath = createContextPath(war);
            String filePath = createFilePath(war);
            if (contextPath.equals("hawtio")) {
                System.out.println();
                System.out.println("==================================================");
                System.out.println("hawtio is running on http://" + ApiMasterService.getHostName() + ":" + port + "/" + contextPath + "/");
                System.out.println("==================================================");
                System.out.println();
            } else {
                System.out.println("adding web context path: /" + contextPath + " war: " + filePath);
            }

            DeploymentInfo servletBuilder = Servlets.deployment();
            servletBuilder.setContextPath("/" + contextPath);
            servletBuilder.setResourceManager(new FileResourceManager(new File(filePath), 500 * 1000));

            DeploymentManager deploymentManager = container.addDeployment(servletBuilder);
            deploymentManager.deploy();

            try {
                System.out.println("Starting web app: " + contextPath);
                pathHandler.addExactPath(servletBuilder.getContextPath(), deploymentManager.start());
                System.out.println("Started web app: " + contextPath + " without any exceptions!");
            } catch (Throwable e) {
                logException(e);
            }
        }
    }

    /**
     * Returns the web context path for the given URL
     */
    public static String createContextPath(String uri) {
        String contextPath = trimUpToLastIndexOf(uri, '!', '.');
        contextPath = trimFromAfterLastIndexOf(contextPath, '/', '\\');
        if (contextPath.startsWith("hawtio-") || contextPath.startsWith("console-")) {
            contextPath = "hawtio";
        }
        return contextPath;
    }

    /**
     * Returns the file path of the given URL
     */
    public static String createFilePath(String uri) {
        String answer = trimUpToLastIndexOf(uri, '!');
        int idx = answer.indexOf(':');
        if (idx > 0) {
            answer = answer.substring(idx + 1);
        }
        if (answer.startsWith("///")) {
            answer = answer.substring(2);
        }
        return answer;
    }

    public static String trimFromAfterLastIndexOf(String text, char... characters) {
        String answer = text;
        for (char ch : characters) {
            int idx = answer.lastIndexOf(ch);
            if (idx > 0) {
                answer = answer.substring(idx + 1, answer.length());
            }
        }
        return answer;
    }

    public static String trimUpToLastIndexOf(String text, char... characters) {
        String answer = text;
        for (char ch : characters) {
            int idx = answer.lastIndexOf(ch);
            if (idx > 0) {
                answer = answer.substring(0, idx);
            }
        }
        return answer;
    }

}
