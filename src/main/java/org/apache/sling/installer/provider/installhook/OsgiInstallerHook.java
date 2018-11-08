/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at
    
    http://www.apache.org/licenses/LICENSE-2.0
    
    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
*/
package org.apache.sling.installer.provider.installhook;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.fs.io.Archive;
import org.apache.jackrabbit.vault.fs.io.Archive.Entry;
import org.apache.jackrabbit.vault.fs.io.ImportOptions;
import org.apache.jackrabbit.vault.packaging.InstallContext;
import org.apache.jackrabbit.vault.packaging.InstallHook;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.PackageProperties;
import org.apache.jackrabbit.vault.packaging.VaultPackage;
import org.apache.sling.installer.api.InstallableResource;
import org.apache.sling.installer.api.OsgiInstaller;
import org.apache.sling.installer.api.event.InstallationEvent;
import org.apache.sling.installer.api.event.InstallationListener;
import org.apache.sling.installer.api.info.InfoProvider;
import org.apache.sling.installer.api.info.InstallationState;
import org.apache.sling.installer.api.info.Resource;
import org.apache.sling.installer.api.info.ResourceGroup;
import org.apache.sling.installer.api.tasks.ResourceState;
import org.apache.sling.installer.api.tasks.TaskResource;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsgiInstallerHook implements InstallHook {

    private static final Logger LOG = LoggerFactory.getLogger(OsgiInstallerHook.class);

    private static final String PACKAGE_PROP_INSTALL_PATH_REGEX = "installPathRegex";
    private static final String PACKAGE_PROPERTY_MAX_WAIT_IN_SEC = "maxWaitForOsgiInstallerInSec";
    private static final String PACKAGE_PROPERTY_INSTALL_PRIORITY = "osgiInstallerPriority";
    private static final String PACKAGE_PROPERTY_WAIT_FOR_OSGI_EVENTS_QUIET_IN_SEC = "waitForOsgiEventsQuietInSec";

    public static final int DEFAULT_PRIORITY_INSTALL_HOOK = 2000;
    private static final int DEFAULT_MAX_WAIT_IN_SEC = 60;
    private static final int DEFAULT_WAIT_FOR_OSGI_EVENTS_QUIET_IN_SEC = 1;

    public static final String URL_SCHEME = "jcrinstall";
    public static final String CONFIG_SUFFIX = ".config";
    public static final String JAR_SUFFIX = ".jar";

    private static final String ENTITY_ID_PREFIX_BUNDLE = "bundle:";

    private static final String MANIFEST_BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName";
    private static final String MANIFEST_BUNDLE_VERSION = "Bundle-Version";
    private static final String FOLDER_META_INF = "META-INF";

    static final String JCR_CONTENT = "jcr:content";
    static final String JCR_CONTENT_DATA = JCR_CONTENT + "/jcr:data";
    static final String JCR_LAST_MODIFIED = "jcr:lastModified";
    static final String JCR_CONTENT_LAST_MODIFIED = JCR_CONTENT + "/" + JCR_LAST_MODIFIED;

    public static final String DOT = ".";

    InstallHookLogger logger = new InstallHookLogger();

    public OsgiInstallerHook() {
        LOG.debug("Preloading classes to ensure to not run into a NoClassDefFoundError"
                + " due to a reloading dynamic classloader: {}, {}, {}, {}",
                new Object[] { TaskResource.class, InstallationEvent.TYPE.class, ResourceState.class,
                        InstallerHookOsgiEventListener.class });
    }

    @Override
    public void execute(InstallContext context) throws PackageException {

        VaultPackage vaultPackage = context.getPackage();
        PackageProperties packageProperties = vaultPackage.getProperties();
        String installPathRegex = packageProperties.getProperty(PACKAGE_PROP_INSTALL_PATH_REGEX);

        ServiceReference<OsgiInstaller> osgiInstallerServiceRef = null;
        ServiceReference<SlingSettingsService> slingSettingsServiceRef = null;
        ServiceRegistration<InstallationListener> hookInstallationListenerServiceRegistration = null;

        ServiceReference<InfoProvider> infoProviderServiceRef = null;

        try {
            switch (context.getPhase()) {
            case PREPARE:
                if (StringUtils.isBlank(installPathRegex)) {
                    throw new IllegalArgumentException(
                            "When using OSGi installer install hook for synchronous installation, the package property "
                                    + PACKAGE_PROP_INSTALL_PATH_REGEX + " has to be provided.");
                }
                break;
            case INSTALLED:
                ImportOptions options = context.getOptions();
                logger.setOptions(options);

                logger.log(getClass().getSimpleName() + " is active in " + vaultPackage.getId());

                List<BundleInPackage> bundleResources = new ArrayList<>();
                List<String> configResourcePaths = new ArrayList<>();
                Archive archive = vaultPackage.getArchive();

                infoProviderServiceRef = getBundleContext().getServiceReference(InfoProvider.class);
                InfoProvider infoProvider = (InfoProvider) getBundleContext().getService(infoProviderServiceRef);
                InstallationState installationState = infoProvider.getInstallationState();

                slingSettingsServiceRef = getBundleContext().getServiceReference(SlingSettingsService.class);
                SlingSettingsService slingSettingsService = (SlingSettingsService) getBundleContext().getService(slingSettingsServiceRef);
                Set<String> runModes = slingSettingsService.getRunModes();

                collectResources(archive, archive.getRoot(), "", bundleResources, configResourcePaths, installPathRegex,
                        runModes);

                logger.log("Bundles in package " + bundleResources);

                Session session = context.getSession();

                Map<String, InstallableResource> bundlesToInstallByUrl = getBundlesToInstall(bundleResources, session, installationState,
                        packageProperties);
                Map<String, InstallableResource> configsToInstallByUrl = getConfigsToInstall(configResourcePaths, session,
                        installationState, packageProperties);

                if (bundlesToInstallByUrl.isEmpty() && configsToInstallByUrl.isEmpty()) {
                    logger.log("No installable resources that are not installed yet found.");
                    return;
                }

                logger.log("Installing " + bundlesToInstallByUrl.size() + " bundles and "
                        + configsToInstallByUrl.size() + " configs");
                osgiInstallerServiceRef = getBundleContext().getServiceReference(OsgiInstaller.class);
                OsgiInstaller osgiInstaller = getBundleContext().getService(osgiInstallerServiceRef);

                OsgiInstallerListener hookInstallationListener = new OsgiInstallerListener(bundlesToInstallByUrl.keySet(),
                        configsToInstallByUrl.keySet());
                hookInstallationListenerServiceRegistration = getBundleContext()
                        .registerService(InstallationListener.class, hookInstallationListener, null);

                List<InstallableResource> resourcesToUpdate = new ArrayList<>();
                resourcesToUpdate.addAll(bundlesToInstallByUrl.values());
                resourcesToUpdate.addAll(configsToInstallByUrl.values());
                logger.log("Updating resources " + resourcesToUpdate);
                osgiInstaller.updateResources(URL_SCHEME, resourcesToUpdate.toArray(new InstallableResource[resourcesToUpdate.size()]),
                        null);

                int maxWaitForOsgiInstallerInSec = getNumericPackageProperty(packageProperties, PACKAGE_PROPERTY_MAX_WAIT_IN_SEC,
                        DEFAULT_MAX_WAIT_IN_SEC);

                long startTime = System.currentTimeMillis();
                int bundlesLeftToInstall = 0;
                int configsLeftToInstall = 0;
                while ((bundlesLeftToInstall = hookInstallationListener.bundlesLeftToInstall()) > 0
                        || (configsLeftToInstall = hookInstallationListener.configsLeftToInstall()) > 0) {
                    if ((System.currentTimeMillis() - startTime) > maxWaitForOsgiInstallerInSec * 1000) {
                        logger.log("Installable resources " + resourcesToUpdate
                                + " could not be installed even after waiting " + maxWaitForOsgiInstallerInSec + "sec");
                        break;
                    }
                    logger.log("Waiting for " + bundlesLeftToInstall + " bundles / " + configsLeftToInstall + " configs to be installed");
                    Thread.sleep(1000);

                    // the events are not always reliably received, also update listener explicitly with current installation state
                    hookInstallationListener.updateWith(infoProvider.getInstallationState().getInstalledResources());
                }
                if (bundlesLeftToInstall == 0 && configsLeftToInstall == 0) {
                    logger.log("All " + bundlesToInstallByUrl.size() + " bundles / " + configsToInstallByUrl.size()
                            + " configs have been successfully installed in " + (System.currentTimeMillis() - startTime) + "ms");
                }

                int waitForOsgiEventsQuietInSec = getNumericPackageProperty(packageProperties,
                        PACKAGE_PROPERTY_WAIT_FOR_OSGI_EVENTS_QUIET_IN_SEC, DEFAULT_WAIT_FOR_OSGI_EVENTS_QUIET_IN_SEC);
                waitForServiceChanges(waitForOsgiEventsQuietInSec);

                break;
            default:
                break;
            }
        } catch (Exception e) {
            throw new PackageException("Could not execute install hook to for synchronous installation: " + e, e);
        } finally {
            if (osgiInstallerServiceRef != null) {
                getBundleContext().ungetService(osgiInstallerServiceRef);
            }
            if (slingSettingsServiceRef != null) {
                getBundleContext().ungetService(slingSettingsServiceRef);
            }
            if (infoProviderServiceRef != null) {
                getBundleContext().ungetService(infoProviderServiceRef);
            }

            if (hookInstallationListenerServiceRegistration != null) {
                hookInstallationListenerServiceRegistration.unregister();
            }
        }
    }

    private int getNumericPackageProperty(PackageProperties packageProperties, String propertyName, int defaultVal) {
        String strVal = packageProperties.getProperty(propertyName);
        int intVal = strVal != null ? Integer.parseInt(strVal) : defaultVal;
        return intVal;
    }

    private Map<String, InstallableResource> getBundlesToInstall(List<BundleInPackage> bundlesInPackage, Session session,
            InstallationState installationState, PackageProperties packageProperties) throws RepositoryException, IOException {
        Map<String, InstallableResource> installableResources = new HashMap<>();
        Iterator<BundleInPackage> bundlesIt = bundlesInPackage.iterator();
        while (bundlesIt.hasNext()) {
            BundleInPackage bundle = bundlesIt.next();

            List<Resource> currentInstallerBundleResources = getBundleResources(installationState, bundle.symbolicName);

            boolean needsInstallation = false;
            if (currentInstallerBundleResources.isEmpty()) {
                needsInstallation = true;
            } else if (currentInstallerBundleResources.size() == 1) {
                Resource resource = currentInstallerBundleResources.get(0);

                if (resource.getState() == ResourceState.INSTALLED) {
                    String currentlyActiveBundleVersion = resource.getVersion().toString();
                    if (!StringUtils.equals(currentlyActiveBundleVersion, bundle.version)) {
                        logger.log("Bundle " + bundle.symbolicName + " is installed with version "
                                + currentlyActiveBundleVersion + " but package contains version " + bundle.version);
                        needsInstallation = true;
                    } else {
                        logger.log("Bundle " + bundle.symbolicName + " is already installed with version "
                                + currentlyActiveBundleVersion + " that matches " + bundle.version + " as provided in package");
                    }
                } else {
                    logger.log("Bundle " + bundle.symbolicName + " is not in state INSTALLED but in " + resource.getState());
                    needsInstallation = true;
                }

            } else {
                logger.log("Bundle " + bundle.symbolicName + " exists with multiple installer resources");
                boolean installedBundleResourceFound = false;
                for (Resource resource : currentInstallerBundleResources) {
                    logger.log("Resource " + resource);
                    if (resource.getState() == ResourceState.INSTALLED
                            && StringUtils.equals(resource.getVersion().toString(), bundle.version)) {
                        installedBundleResourceFound = true;
                    }
                }
                if (!installedBundleResourceFound) {
                    needsInstallation = true;
                }

            }

            if (needsInstallation) {
                logger.log("Bundle " + bundle.symbolicName + " requires installation");
                Node node = session.getNode(bundle.path);
                InstallableResource installableResource = convert(node, bundle.path, packageProperties);
                String bundleUrl = URL_SCHEME + ":" + bundle.path;
                installableResources.put(bundleUrl, installableResource);
            }
        }
        return installableResources;
    }

    private List<Resource> getBundleResources(InstallationState installationState, String symbolicId) {

        List<Resource> bundleResources = new ArrayList<Resource>();

        List<ResourceGroup> allGroups = new ArrayList<ResourceGroup>();
        allGroups.addAll(installationState.getInstalledResources());
        allGroups.addAll(installationState.getActiveResources());
        for (ResourceGroup resourceGroup : allGroups) {
            List<Resource> resources = resourceGroup.getResources();
            for (Resource resource : resources) {
                if (StringUtils.equals(resource.getEntityId(), ENTITY_ID_PREFIX_BUNDLE + symbolicId)) {
                    bundleResources.add(resource);
                }
            }
        }
        return bundleResources;
    }

    private Map<String, InstallableResource> getConfigsToInstall(List<String> configResourcePaths, Session session,
            InstallationState installationState, PackageProperties packageProperties)
            throws IOException, InvalidSyntaxException, RepositoryException {
        Map<String, InstallableResource> configsToInstallByUrl = new HashMap<>();
        for (String configResourcePath : configResourcePaths) {
            boolean needsInstallation = false;

            String configUrl = URL_SCHEME + ":" + configResourcePath;
            boolean configFound = false;
            List<ResourceGroup> installedResources = installationState.getInstalledResources();
            for (ResourceGroup resourceGroup : installedResources) {
                for (Resource resource : resourceGroup.getResources()) {
                    if (StringUtils.equals(configUrl, resource.getURL())) {
                        configFound = true;
                        logger.log("Config " + configResourcePath + " is already installed");
                    }
                }
            }
            if (!configFound) {
                logger.log("Config " + configResourcePath + " has not been installed");
                needsInstallation = true;
            }

            if (needsInstallation) {

                Node node = session.getNode(configResourcePath);
                InstallableResource installableResource = convert(node, configResourcePath, packageProperties);

                configsToInstallByUrl.put(configUrl, installableResource);
            }
        }
        return configsToInstallByUrl;
    }

    void collectResources(Archive archive, Entry entry, String dirPath, List<BundleInPackage> bundleResources,
            List<String> configResources, String installPathRegex, Set<String> actualRunmodes) {
        String entryName = entry.getName();
        if (entryName.equals(FOLDER_META_INF)) {
            return;
        }

        String dirPathWithoutJcrRoot = StringUtils.substringAfter(dirPath, "/jcr_root");
        String entryPath = dirPathWithoutJcrRoot + entryName;
        String dirPathWithoutSlash = StringUtils.chomp(dirPathWithoutJcrRoot, "/");

        boolean runmodesMatch;
        if (dirPathWithoutSlash.contains(DOT)) {
            String[] bits = dirPathWithoutSlash.split("\\" + DOT, 2);
            List<String> runmodesOfResource = Arrays.asList(bits[1].split("\\" + DOT));
            Set<String> matchingRunmodes = new HashSet<String>(runmodesOfResource);
            matchingRunmodes.retainAll(actualRunmodes);
            LOG.debug("Entry with runmode(s): entryPath={} runmodesOfResource={} actualRunmodes={} matchingRunmodes={}",
                    entryPath, runmodesOfResource, actualRunmodes, matchingRunmodes);
            runmodesMatch = matchingRunmodes.size() == runmodesOfResource.size();
            if (!runmodesMatch) {
                logger.log("Skipping installation of  " + entryPath
                        + " because the path is not matching all actual runmodes " + actualRunmodes);
            }
        } else {
            runmodesMatch = true;
        }

        if (entryPath.matches(installPathRegex) && runmodesMatch) {

            if (entryName.endsWith(CONFIG_SUFFIX)) {
                configResources.add(entryPath);
            } else if (entryName.endsWith(JAR_SUFFIX)) {
                try (InputStream entryInputStream = archive.getInputSource(entry).getByteStream();
                        JarInputStream jarInputStream = new JarInputStream(entryInputStream)) {
                    Manifest manifest = jarInputStream.getManifest();
                    String symbolicName = manifest.getMainAttributes().getValue(MANIFEST_BUNDLE_SYMBOLIC_NAME);
                    String version = manifest.getMainAttributes().getValue(MANIFEST_BUNDLE_VERSION);

                    bundleResources.add(new BundleInPackage(entryPath, symbolicName, version));
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Could not read symbolic name and version from manifest of bundle " + entryName);
                }
            }

        }

        for (Entry child : entry.getChildren()) {
            collectResources(archive, child, dirPath + entryName + "/", bundleResources, configResources,
                    installPathRegex, actualRunmodes);
        }
    }

    InstallableResource convert(final Node node, final String path, PackageProperties packageProperties)
            throws IOException, RepositoryException {
        LOG.trace("Converting {} at path {}", node, path);
        final String digest = String.valueOf(node.getProperty(JCR_CONTENT_LAST_MODIFIED).getDate().getTimeInMillis());
        final InputStream is = node.getProperty(JCR_CONTENT_DATA).getBinary().getStream();
        final Dictionary<String, Object> dict = new Hashtable<String, Object>();
        dict.put(InstallableResource.INSTALLATION_HINT, node.getParent().getName());
        int priority = getNumericPackageProperty(packageProperties, PACKAGE_PROPERTY_INSTALL_PRIORITY, DEFAULT_PRIORITY_INSTALL_HOOK);
        return new InstallableResource(path, is, dict, digest, null, priority);
    }

    private void waitForServiceChanges(int waitForOsgiEventsQuietInSec) {
        if (waitForOsgiEventsQuietInSec <= 0) {
            return;
        }
        InstallerHookOsgiEventListener osgiListener = new InstallerHookOsgiEventListener();
        BundleContext bundleContext = getBundleContext();
        bundleContext.addServiceListener(osgiListener);
        bundleContext.addBundleListener(osgiListener);

        long waitStart = System.currentTimeMillis();
        osgiListener.waitUntilQuiet(waitForOsgiEventsQuietInSec);
        logger.log("Waited " + (System.currentTimeMillis() - waitStart) + "ms in total for OSGi events to become quiet (for at least "
                + waitForOsgiEventsQuietInSec + "sec)");

        bundleContext.removeServiceListener(osgiListener);
        bundleContext.removeBundleListener(osgiListener);

    }

    // always get fresh bundle context to avoid "Dynamic class loader has already
    // been deactivated" exceptions
    private BundleContext getBundleContext() {
        // use the vault bundle to hook into the OSGi world
        Bundle currentBundle = FrameworkUtil.getBundle(InstallHook.class);
        if (currentBundle == null) {
            throw new IllegalStateException(
                    "The class " + InstallHook.class + " was not loaded through a bundle classloader");
        }
        BundleContext bundleContext = currentBundle.getBundleContext();
        if (bundleContext == null) {
            throw new IllegalStateException("Could not get bundle context for bundle " + currentBundle);
        }
        return bundleContext;
    }

    class BundleInPackage {
        final String path;
        final String symbolicName;
        final String version;

        public BundleInPackage(String path, String symbolicName, String version) {
            super();
            this.path = path;
            this.symbolicName = symbolicName;
            this.version = version;
        }

        @Override
        public String toString() {
            return "BundleInPackage [path=" + path + ", symbolicId=" + symbolicName + ", version=" + version + "]";
        }

    }

    static class InstallHookLogger {

        private ProgressTrackerListener listener;

        public void setOptions(ImportOptions options) {
            this.listener = options.getListener();
        }

        public void logError(Logger logger, String message, Throwable throwable) {
            if (listener != null) {
                listener.onMessage(ProgressTrackerListener.Mode.TEXT, "ERROR: " + message, "");
            }
            logger.error(message, throwable);
        }

        public void log(String message) {
            log(LOG, message);
        }

        public void log(Logger logger, String message) {
            if (listener != null) {
                listener.onMessage(ProgressTrackerListener.Mode.TEXT, message, "");
            }
            logger.info(message);
        }
    }

    static class InstallerHookOsgiEventListener implements ServiceListener, BundleListener {

        private long lastEventTimestamp = System.currentTimeMillis();

        @Override
        public void serviceChanged(ServiceEvent event) {
            lastEventTimestamp = System.currentTimeMillis();
            LOG.trace("Service changed event {}", event);
        }

        @Override
        public void bundleChanged(BundleEvent event) {
            lastEventTimestamp = System.currentTimeMillis();
            LOG.trace("Bundle changed event {}", event);
        }

        public void waitUntilQuiet(long waitInSec) {
            try {
                while (System.currentTimeMillis() - lastEventTimestamp < waitInSec * 1000) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                LOG.warn("Wait for OSGi events was interrupted");
            }
        }
    }

}
