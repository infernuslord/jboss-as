/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.integration.domain.management.util;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.test.shared.FileUtils;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.exporter.StreamExporter;
import org.junit.Assert;

/**
 * Utilities for running tests of domain mode.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class DomainTestSupport {


    private static final Configuration DEFAULT_CONFIG;

    private static final Logger log = Logger.getLogger("org.jboss.as.test.integration.domain");

    public static final String masterAddress = System.getProperty("jboss.test.host.master.address", "127.0.0.1");
    public static final String slaveAddress = System.getProperty("jboss.test.host.slave.address", "127.0.0.1");
    public static final long domainBootTimeout = Long.valueOf(System.getProperty("jboss.test.domain.boot.timeout", "60000"));
    public static final long domainShutdownTimeout = Long.valueOf(System.getProperty("jboss.test.domain.shutdown.timeout", "20000"));
    public static final String masterJvmHome = System.getProperty("jboss.test.host.master.jvmhome");
    public static final String slaveJvmHome = System.getProperty("jboss.test.host.slave.jvmhome");
    public static final String masterControllerJvmHome = System.getProperty("jboss.test.host.master.controller.jvmhome");
    public static final String slaveControllerJvmHome = System.getProperty("jboss.test.host.slave.controller.jvmhome");

    static {
        DEFAULT_CONFIG = DomainTestSupport.Configuration.create("domain-configs/domain-standard.xml", "host-configs/host-master.xml", "host-configs/host-slave.xml", JBossAsManagedConfigurationParameters.STANDARD, JBossAsManagedConfigurationParameters.STANDARD);
    }

    /**
     * Create and start a default configuration for the domain tests.
     *
     * @param testName the test name
     * @return a started domain test support
     */
    public static DomainTestSupport createAndStartDefaultSupport(final String testName) {
        try {
            final DomainTestSupport testSupport = DomainTestSupport.create(testName, DEFAULT_CONFIG);
            // Start!
            testSupport.start();
            return testSupport;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static JBossAsManagedConfiguration getMasterConfiguration(String domainConfigPath, String hostConfigPath, String testName, JBossAsManagedConfigurationParameters params, boolean readOnlyDomain, boolean readOnlyHost) throws URISyntaxException {
        final String hostName = "master";
        File domains = getBaseDir(testName);
        File extraModules = getAddedModulesDir(testName);
        File overrideModules = getHostOverrideModulesDir(testName, hostName);
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        final JBossAsManagedConfiguration masterConfig = new JBossAsManagedConfiguration(params);
        configureModulePath(masterConfig, overrideModules, extraModules);
        masterConfig.setHostControllerManagementAddress(masterAddress);
        masterConfig.setHostCommandLineProperties("-Djboss.test.host.master.address=" + masterAddress);
        masterConfig.setReadOnlyDomain(readOnlyDomain);
        masterConfig.setReadOnlyHost(readOnlyHost);
        URL url = tccl.getResource(domainConfigPath);
        assert url != null : "cannot find domainConfigPath";
        masterConfig.setDomainConfigFile(new File(url.toURI()).getAbsolutePath());
        System.out.println(masterConfig.getDomainConfigFile());
        url = tccl.getResource(hostConfigPath);
        assert url != null : "cannot find hostConfigPath";
        System.out.println(masterConfig.getHostConfigFile());
        masterConfig.setHostConfigFile(new File(url.toURI()).getAbsolutePath());
        File masterDir = new File(domains, hostName);
        // TODO this should not be necessary
        new File(masterDir, "configuration").mkdirs();
        masterConfig.setDomainDirectory(masterDir.getAbsolutePath());
        if (masterJvmHome != null) masterConfig.setJavaHome(masterJvmHome);
        if (masterControllerJvmHome != null) masterConfig.setControllerJavaHome(masterControllerJvmHome);
        return masterConfig;
    }

    public static JBossAsManagedConfiguration getSlaveConfiguration(String hostConfigPath, String testName, JBossAsManagedConfigurationParameters params, boolean readOnlyHost) throws URISyntaxException {
        final String hostName = "slave";
        File domains = getBaseDir(testName);
        File extraModules = getAddedModulesDir(testName);
        File overrideModules = getHostOverrideModulesDir(testName, hostName);
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        final JBossAsManagedConfiguration slaveConfig = new JBossAsManagedConfiguration(params);
        configureModulePath(slaveConfig, overrideModules, extraModules);
        slaveConfig.setHostName(hostName);
        slaveConfig.setHostControllerManagementAddress(slaveAddress);
        slaveConfig.setHostControllerManagementPort(19999);
        slaveConfig.setHostCommandLineProperties("-Djboss.test.host.master.address=" + masterAddress +
                " -Djboss.test.host.slave.address=" + slaveAddress);
        slaveConfig.setReadOnlyHost(readOnlyHost);
        URL url = tccl.getResource(hostConfigPath);
        slaveConfig.setHostConfigFile(new File(url.toURI()).getAbsolutePath());
        System.out.println(slaveConfig.getHostConfigFile());
        File slaveDir = new File(domains, hostName);
        // TODO this should not be necessary
        new File(slaveDir, "configuration").mkdirs();
        slaveConfig.setDomainDirectory(slaveDir.getAbsolutePath());
        System.out.println(slaveConfig.getDomainDirectory());
        if (slaveJvmHome != null) slaveConfig.setJavaHome(slaveJvmHome);
        if (slaveControllerJvmHome != null) slaveConfig.setControllerJavaHome(slaveControllerJvmHome);
        return slaveConfig;
    }

    public static void startHosts(long timeout, DomainLifecycleUtil... hosts) throws Exception {
        Future<?>[] futures = new Future<?>[hosts.length];
        for (int i = 0; i < hosts.length; i++) {
            futures[i] = hosts[i].startAsync();
        }

        processFutures(futures, timeout);
    }

    public static void stopHosts(long timeout, DomainLifecycleUtil... hosts) throws Exception {
        Future<?>[] futures = new Future<?>[hosts.length];
        for (int i = 0; i < hosts.length; i++) {
            futures[i] = hosts[i].stopAsync();
        }

        processFutures(futures, timeout);
    }

    public static File getBaseDir(String testName) {
        return new File("target" + File.separator + "domains" + File.separator + testName);
    }

    public static File getHostDir(String testName, String hostName) {
        return new File(getBaseDir(testName), hostName);
    }

    public static File getAddedModulesDir(String testName) {
        File f = new File(getBaseDir(testName), "added-modules");
        f.mkdirs();
        return f;
    }

    public static File getHostOverrideModulesDir(String testName, String hostName) {
        final File f = new File(getHostDir(testName, hostName), "added-modules");
        f.mkdirs();
        return f;
    }

    public static ModelNode createOperationNode(String address, String operation) {
        ModelNode op = new ModelNode();

        // set address
        ModelNode list = op.get("address").setEmptyList();
        if (address != null) {
            String [] pathSegments = address.split("/");
            for (String segment : pathSegments) {
                String[] elements = segment.split("=");
                list.add(elements[0], elements[1]);
            }
        }
        op.get("operation").set(operation);
        return op;
    }

    public static ModelNode validateResponse(ModelNode response) {
        return validateResponse(response, true);
    }

    public static ModelNode validateResponse(ModelNode response, boolean getResult) {

        if(! SUCCESS.equals(response.get(OUTCOME).asString())) {
            System.out.println("Failed response:");
            System.out.println(response);
            Assert.fail(response.get(FAILURE_DESCRIPTION).toString());
        }

        if (getResult) {
            Assert.assertTrue("result exists", response.has(RESULT));
            return response.get(RESULT);
        }
        return null;
    }

    public static ModelNode validateFailedResponse(ModelNode response) {

        if(! FAILED.equals(response.get(OUTCOME).asString())) {
            System.out.println("Response succeeded:");
            System.out.println(response);
            Assert.fail(response.get(OUTCOME).toString());
        }

        Assert.assertTrue("failure description exists", response.has(FAILURE_DESCRIPTION));
        return response.get(FAILURE_DESCRIPTION);
    }

    public static void cleanFile(File file) {
        if (file != null && file.exists()) {
            if (file.isDirectory()) {
                for (File child : file.listFiles()) {
                    cleanFile(child);
                }
            }
            if (!file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    public static void safeClose(final Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (Throwable t) {
            log.errorf(t, "Failed to close resource %s", closeable);
        }
    }

    private static void processFutures(Future<?>[] futures, long timeout) throws Exception {

        try {
            for (int i = 0; i < futures.length; i++) {
                try {
                    futures[i].get(timeout, TimeUnit.MILLISECONDS);
                }  catch (ExecutionException e){
                    throw e.getCause();
                }
            }
        } catch (Exception e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void configureModulePath(JBossAsManagedConfiguration config, File... extraModules) {
        String basePath = config.getModulePath();
        if (basePath == null || basePath.isEmpty()) {
            basePath = config.getJbossHome() + File.separatorChar + "modules";
        }
        final StringBuilder path = new StringBuilder();
        for(final File extraModule : extraModules) {
            path.append(extraModule.getAbsolutePath()).append(File.pathSeparatorChar);
        }
        path.append(basePath);
        config.setModulePath(path.toString());
    }

    private final JBossAsManagedConfiguration masterConfiguration;
    private final JBossAsManagedConfiguration slaveConfiguration;
    private final DomainLifecycleUtil domainMasterLifecycleUtil;
    private final DomainLifecycleUtil domainSlaveLifecycleUtil;
    private final DomainControllerClientConfig sharedClientConfig;
    private final String testClass;



    protected DomainTestSupport(final String testClass, final String domainConfig, final String masterConfig, final String slaveConfig, JBossAsManagedConfigurationParameters masterParams, JBossAsManagedConfigurationParameters slaveParams) throws Exception {
        this(testClass, domainConfig, masterConfig, slaveConfig, masterParams, slaveParams, false, false, false);
    }

    protected DomainTestSupport(final String testClass, final String domainConfig, final String masterConfig, final String slaveConfig, JBossAsManagedConfigurationParameters masterParams,
            JBossAsManagedConfigurationParameters slaveParams, final boolean readOnlyDomainConfig, final boolean readOnlyMasterHostConfig, final boolean readOnlySlaveHostConfig) throws Exception {
        this.testClass = testClass;
        this.sharedClientConfig = DomainControllerClientConfig.create();

        masterConfiguration = getMasterConfiguration(domainConfig, masterConfig, testClass, masterParams, readOnlyDomainConfig, readOnlyMasterHostConfig);
        domainMasterLifecycleUtil = new DomainLifecycleUtil(masterConfiguration, sharedClientConfig);

        if (slaveConfig != null) {
            slaveConfiguration = getSlaveConfiguration(slaveConfig, testClass, slaveParams, readOnlySlaveHostConfig);
            domainSlaveLifecycleUtil = new DomainLifecycleUtil(slaveConfiguration, sharedClientConfig);
        } else {
            slaveConfiguration = null;
            domainSlaveLifecycleUtil = null;
        }
    }

    public static DomainTestSupport create(final String testClass, final Configuration configuration) throws Exception {
        return new DomainTestSupport(testClass, configuration.getDomainConfig(), configuration.getMasterConfig(), configuration.getSlaveConfigs(),
                configuration.getMasterConfigurationParameters(), configuration.getSlaveConfigurationParameters(), configuration.isReadOnlyMasterDomain(), configuration.isReadOnlyMasterHost(), configuration.isReadOnlySlaveHost());
    }

    public static DomainTestSupport create(final String testClass, final String domainConfig, final String masterConfig, final String slaveConfig, JBossAsManagedConfigurationParameters masterParams, JBossAsManagedConfigurationParameters slaveParams) throws Exception {
        return new DomainTestSupport(testClass, domainConfig, masterConfig, slaveConfig, masterParams, slaveParams);
    }

    public JBossAsManagedConfiguration getDomainMasterConfiguration() {
        return masterConfiguration;
    }

    public DomainLifecycleUtil getDomainMasterLifecycleUtil() {
        return domainMasterLifecycleUtil;
    }

    public JBossAsManagedConfiguration getDomainSlaveConfiguration() {
        return slaveConfiguration;
    }

    public DomainLifecycleUtil getDomainSlaveLifecycleUtil() {
        return domainSlaveLifecycleUtil;
    }

    public void start() {
        domainMasterLifecycleUtil.start();
        if (domainSlaveLifecycleUtil != null) {
            domainSlaveLifecycleUtil.start();
        }
    }

    public void addTestModule(String moduleName, InputStream moduleXml, Map<String, StreamExporter> contents) throws IOException {
        File modulesDir = getAddedModulesDir(testClass);
        addModule(modulesDir, moduleName, moduleXml, contents);
    }

    public void addOverrideModule(String hostName, String moduleName, InputStream moduleXml, Map<String, StreamExporter> contents) throws IOException {
        File modulesDir = getHostOverrideModulesDir(testClass, hostName);
        addModule(modulesDir, moduleName, moduleXml, contents);
    }

    static void addModule(final File modulesDir, String moduleName, InputStream moduleXml, Map<String, StreamExporter> resources) throws IOException {
        String modulePath = moduleName.replace('.', File.separatorChar) + File.separatorChar + "main";
        File moduleDir = new File(modulesDir, modulePath);
        moduleDir.mkdirs();
        FileUtils.copyFile(moduleXml, new File(moduleDir, "module.xml"));
        for (Map.Entry<String, StreamExporter> entry : resources.entrySet()) {
            entry.getValue().exportTo(new File(moduleDir, entry.getKey()), true);
        }
    }

    public void stop() {
        try {
            try {
                if (domainSlaveLifecycleUtil != null) {
                    domainSlaveLifecycleUtil.stop();
                }
            } finally {
                domainMasterLifecycleUtil.stop();
            }
        } finally {
            StreamUtils.safeClose(sharedClientConfig);
        }
    }

    public static class Configuration {

        private String domainConfig;
        private String masterConfig;
        private String slaveConfig;
        private JBossAsManagedConfigurationParameters masterParams;
        private JBossAsManagedConfigurationParameters slaveParams;
        private boolean readOnlyMasterDomain;
        private boolean readOnlyMasterHost;
        private boolean readOnlySlaveHost;


        protected Configuration(final String domainConfig, final String masterConfig, final String slaveConfig, JBossAsManagedConfigurationParameters masterParams, JBossAsManagedConfigurationParameters slaveParams) {
            this(domainConfig, masterConfig, slaveConfig, masterParams, slaveParams, false, false, false);
        }

        protected Configuration(final String domainConfig, final String masterConfig, final String slaveConfig, JBossAsManagedConfigurationParameters masterParams,
                JBossAsManagedConfigurationParameters slaveParams, boolean readOnlyMasterDomain, boolean readOnlyMasterHost, boolean readOnlySlaveHost) {
            this.domainConfig = domainConfig;
            this.masterConfig = masterConfig;
            this.slaveConfig = slaveConfig;
            this.masterParams = masterParams;
            this.slaveParams = slaveParams;
            this.readOnlyMasterDomain = readOnlyMasterDomain;
            this.readOnlyMasterHost = readOnlyMasterHost;
            this.readOnlySlaveHost = readOnlySlaveHost;
        }



        public String getDomainConfig() {
            return domainConfig;
        }

        public String getMasterConfig() {
            return masterConfig;
        }

        public String getSlaveConfigs() {
            return slaveConfig;
        }

        public JBossAsManagedConfigurationParameters getMasterConfigurationParameters() {
            return slaveParams;
        }

        public JBossAsManagedConfigurationParameters getSlaveConfigurationParameters() {
            return slaveParams;
        }

        public boolean isReadOnlyMasterDomain() {
            return readOnlyMasterDomain;
        }

        public boolean isReadOnlyMasterHost() {
            return readOnlyMasterHost;
        }

        public boolean isReadOnlySlaveHost() {
            return readOnlySlaveHost;
        }

        public static Configuration create(final String domainConfig, final String masterConfig, final String slaveConfig, JBossAsManagedConfigurationParameters masterParams, JBossAsManagedConfigurationParameters slaveParams) {
            return new Configuration(domainConfig, masterConfig, slaveConfig, masterParams, slaveParams);
        }

        public static Configuration create(final String domainConfig, final String masterConfig, final String slaveConfig, JBossAsManagedConfigurationParameters masterParams,
                JBossAsManagedConfigurationParameters slaveParams, boolean readOnlyMasterDomain, boolean readOnlyMasterHost, boolean readOnlySlaveHost) {
            return new Configuration(domainConfig, masterConfig, slaveConfig, masterParams, slaveParams, readOnlyMasterDomain, readOnlyMasterHost, readOnlySlaveHost);
        }
    }


}
