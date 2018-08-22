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
import org.apache.sling.installer.api.event.InstallationListener;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsgiInstallerHook implements InstallHook {

	private static final Logger LOG = LoggerFactory.getLogger(OsgiInstallerHook.class);

	private static final String PACKAGE_PROPERTY_MAX_WAIT_IN_SEC = "maxWaitForOsgiInstallerInSec";
	private static final String PACKAGE_PROP_INSTALL_PATH_REGEX = "installPathRegex";

	public static final int DEFAULT_PRIORITY_INSTALL_HOOK = 2000;
	private static final int DEFAULT_MAX_WAIT_IN_SEC = 60;

	public static final String URL_SCHEME = "jcrinstall";
	public static final String CONFIG_SUFFIX = ".config";
	public static final String JAR_SUFFIX = ".jar";

	private static final String MANIFEST_BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName";
	private static final String MANIFEST_BUNDLE_VERSION = "Bundle-Version";
	private static final String FOLDER_META_INF = "META-INF";

	static final String JCR_CONTENT = "jcr:content";
	static final String JCR_CONTENT_DATA = JCR_CONTENT + "/jcr:data";
	static final String JCR_LAST_MODIFIED = "jcr:lastModified";
	static final String JCR_CONTENT_LAST_MODIFIED = JCR_CONTENT + "/" + JCR_LAST_MODIFIED;

	public static final String DOT = ".";

	InstallHookLogger logger = new InstallHookLogger();

	@Override
	public void execute(InstallContext context) throws PackageException {

		VaultPackage vaultPackage = context.getPackage();
		PackageProperties packageProperties = vaultPackage.getProperties();
		String installPathRegex = packageProperties.getProperty(PACKAGE_PROP_INSTALL_PATH_REGEX);

		ServiceReference<OsgiInstaller> osgiInstallerServiceRef = null;
		ServiceReference<ConfigurationAdmin> configAdminServiceRef = null;
		ServiceReference<SlingSettingsService> slingSettingsServiceRef = null;
		ServiceRegistration<InstallationListener> hookInstallationListenerServiceRegistration = null;

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

				configAdminServiceRef = getBundleContext().getServiceReference(ConfigurationAdmin.class);
				ConfigurationAdmin confAdmin = (ConfigurationAdmin) getBundleContext()
						.getService(configAdminServiceRef);

				slingSettingsServiceRef = getBundleContext().getServiceReference(SlingSettingsService.class);
				SlingSettingsService slingSettingsService = (SlingSettingsService) getBundleContext()
						.getService(slingSettingsServiceRef);
				Set<String> runModes = slingSettingsService.getRunModes();

				collectResources(archive, archive.getRoot(), "", bundleResources, configResourcePaths, installPathRegex,
						runModes);

				logger.log("Bundles in package " + bundleResources);

				Map<String, String> bundleVersionsBySymbolicId = new HashMap<>();
				for (Bundle bundle : getBundleContext().getBundles()) {
					bundleVersionsBySymbolicId.put(bundle.getSymbolicName(), bundle.getVersion().toString());
				}

				Session session = context.getSession();

				List<InstallableResource> installableResources = new ArrayList<>();

				Set<String> bundleSymbolicNamesToInstall = getBundlesToInstall(bundleResources,
						bundleVersionsBySymbolicId, session, installableResources);

				Set<String> configPidsToInstall = getConfigPidsToInstall(configResourcePaths, session,
						installableResources, confAdmin);

				if (installableResources.isEmpty()) {
					logger.log("No installable resources that are not installed yet found.");
					return;
				}

				logger.log("Installing " + bundleSymbolicNamesToInstall.size() + " bundles and "
						+ configPidsToInstall.size() + " configs");
				osgiInstallerServiceRef = getBundleContext().getServiceReference(OsgiInstaller.class);
				OsgiInstaller osgiInstaller = getBundleContext().getService(osgiInstallerServiceRef);

				OsgiInstallerListener hookInstallationListener = new OsgiInstallerListener(bundleSymbolicNamesToInstall,
						configPidsToInstall);
				hookInstallationListenerServiceRegistration = getBundleContext()
						.registerService(InstallationListener.class, hookInstallationListener, null);

				logger.log("Update resources " + installableResources);
				osgiInstaller.updateResources(URL_SCHEME,
						installableResources.toArray(new InstallableResource[installableResources.size()]), null);

				String maxWaitForOsgiInstallerInSecStr = packageProperties
						.getProperty(PACKAGE_PROPERTY_MAX_WAIT_IN_SEC);
				int maxWaitForOsgiInstallerInSec = maxWaitForOsgiInstallerInSecStr != null
						? Integer.parseInt(maxWaitForOsgiInstallerInSecStr)
						: DEFAULT_MAX_WAIT_IN_SEC;

				long startTime = System.currentTimeMillis();
				while (!hookInstallationListener.isDone()) {
					if ((System.currentTimeMillis() - startTime) > maxWaitForOsgiInstallerInSec * 1000) {
						logger.log("Installable resources " + installableResources
								+ " could not be installed even after waiting " + maxWaitForOsgiInstallerInSec + "sec");
						break;
					}
					logger.log("Waiting for " + installableResources.size() + " to be installed");
					Thread.sleep(1000);
				}

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
			if (configAdminServiceRef != null) {
				getBundleContext().ungetService(configAdminServiceRef);
			}
			if (slingSettingsServiceRef != null) {
				getBundleContext().ungetService(slingSettingsServiceRef);
			}

			if (hookInstallationListenerServiceRegistration != null) {
				hookInstallationListenerServiceRegistration.unregister();
			}
		}
	}

	private Set<String> getConfigPidsToInstall(List<String> configResourcePaths, Session session,
			List<InstallableResource> installableResources, ConfigurationAdmin confAdmin)
			throws IOException, InvalidSyntaxException, RepositoryException {
		Set<String> configIdsToInstall = new HashSet<>();
		for (String configResourcePath : configResourcePaths) {
			boolean needsInstallation = false;
			String configIdToInstall = StringUtils
					.substringBefore(StringUtils.substringAfterLast(configResourcePath, "/"), CONFIG_SUFFIX);
			if (!configIdToInstall.contains("-")) {
				// non-factory configs
				Configuration[] activeConfigs = confAdmin.listConfigurations("(service.pid=" + configIdToInstall + ")");
				if (activeConfigs == null) {
					logger.log("Config PID " + configIdToInstall + " requires installation");

					needsInstallation = true;
				}
			} else {
				// non-factory configs
				String factoryPid = StringUtils.substringBefore(configIdToInstall, "-");
				Configuration[] activeConfigs = confAdmin.listConfigurations("(service.factoryPid=" + factoryPid + ")");
				if (activeConfigs == null) {
					logger.log("There is not a single config for factory PID " + factoryPid + " in system, "
							+ configIdToInstall + " requires installation");
					needsInstallation = true;
				}
			}

			if (needsInstallation) {
				Node node = session.getNode(configResourcePath);
				InstallableResource installableResource = convert(node, configResourcePath);
				installableResources.add(installableResource);
				configIdsToInstall.add(configIdToInstall);
			}
		}
		return configIdsToInstall;
	}

	private Set<String> getBundlesToInstall(List<BundleInPackage> bundleResources,
			Map<String, String> bundleVersionsBySymbolicId, Session session,
			List<InstallableResource> installableResources) throws RepositoryException, IOException {
		Set<String> bundleSymbolicNamesToInstall = new HashSet<>();
		Iterator<BundleInPackage> bundlesIt = bundleResources.iterator();
		while (bundlesIt.hasNext()) {
			BundleInPackage bundle = bundlesIt.next();

			String currentlyActiveBundleVersion = bundleVersionsBySymbolicId.get(bundle.symbolicName);
			boolean needsInstallation = false;
			if (currentlyActiveBundleVersion == null) {
				logger.log("Bundle " + bundle.symbolicName + " is not installed");
				needsInstallation = true;
			} else if (!currentlyActiveBundleVersion.equals(bundle.version)) {
				logger.log("Bundle " + bundle.symbolicName + " is installed with version "
						+ currentlyActiveBundleVersion + " but package contains version " + bundle.version);
				needsInstallation = true;
			} else {
				logger.log("Bundle " + bundle.symbolicName + " is already installed with version "
						+ currentlyActiveBundleVersion);
			}
			if (needsInstallation) {
				logger.log("Bundle " + bundle.symbolicName + " requires installation");
				Node node = session.getNode(bundle.path);
				InstallableResource installableResource = convert(node, bundle.path);
				installableResources.add(installableResource);
				bundleSymbolicNamesToInstall.add(bundle.symbolicName);
			}
		}
		return bundleSymbolicNamesToInstall;
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

	InstallableResource convert(final Node node, final String path) throws IOException, RepositoryException {
		logger.log("Converting " + node + " at path " + path);
		final String digest = String.valueOf(node.getProperty(JCR_CONTENT_LAST_MODIFIED).getDate().getTimeInMillis());
		final InputStream is = node.getProperty(JCR_CONTENT_DATA).getStream();
		final Dictionary<String, Object> dict = new Hashtable<String, Object>();
		dict.put(InstallableResource.INSTALLATION_HINT, node.getParent().getName());
		return new InstallableResource(path, is, dict, digest, null, DEFAULT_PRIORITY_INSTALL_HOOK);
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
				logger.debug(message);
			} else {
				logger.info(message);
			}
		}
	}
}
