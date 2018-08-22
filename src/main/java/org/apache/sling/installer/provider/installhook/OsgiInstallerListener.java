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

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.sling.installer.api.event.InstallationEvent;
import org.apache.sling.installer.api.event.InstallationEvent.TYPE;
import org.apache.sling.installer.api.event.InstallationListener;
import org.apache.sling.installer.api.tasks.TaskResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsgiInstallerListener implements InstallationListener {

	private static final Logger LOG = LoggerFactory.getLogger(OsgiInstallerListener.class);

	static final String ENTITY_ID_PREFIX_BUNDLE = "bundle:";
	static final String ENTITY_ID_PREFIX_CONFIG = "config:";

	private final Set<String> requiredBundleSymbolicNames;
	private final Set<String> requiredConfigPids;
	private final Set<String> installedBundleSymbolicNames = new HashSet<>();
	private final Set<String> installedConfigPids = new HashSet<>();

	public OsgiInstallerListener(Set<String> requiredBundleSymbolicNames, Set<String> requiredConfigPids) {
		this.requiredBundleSymbolicNames = requiredBundleSymbolicNames;
		this.requiredConfigPids = requiredConfigPids;
	}

	@Override
	public void onEvent(InstallationEvent installationEvent) {
		if (installationEvent.getType() == TYPE.PROCESSED) {
			Object sourceRaw = installationEvent.getSource();
			if (!(sourceRaw instanceof TaskResource)) {
				throw new IllegalStateException("Expected source of type " + TaskResource.class.getName());
			}
			TaskResource source = (TaskResource) sourceRaw;
			String entityId = source.getEntityId();

			LOG.debug("Received event about processed entityId {}", entityId);

			if (entityId.startsWith(ENTITY_ID_PREFIX_BUNDLE)) {
				String installedBundleSymbolicName = StringUtils.substringAfter(entityId, ENTITY_ID_PREFIX_BUNDLE);
				installedBundleSymbolicNames.add(installedBundleSymbolicName);
			} else if (entityId.startsWith(ENTITY_ID_PREFIX_CONFIG)) {
				String installedConfigPid = StringUtils.substringAfter(entityId, ENTITY_ID_PREFIX_CONFIG);
				installedConfigPids.add(installedConfigPid);
			}
		}
	}

	public boolean isDone() {
		LOG.trace("requiredBundleSymbolicNames: {}", requiredBundleSymbolicNames);
		LOG.trace("installedBundleSymbolicNames: {}", installedBundleSymbolicNames);
		HashSet<String> bundlesLeftToInstall = new HashSet<String>(requiredBundleSymbolicNames);
		bundlesLeftToInstall.removeAll(installedBundleSymbolicNames);
		LOG.debug("bundlesLeftToInstall: {}", bundlesLeftToInstall);

		LOG.trace("requiredConfigPids: {}", requiredConfigPids);
		LOG.trace("installedConfigPids: {}", installedConfigPids);
		HashSet<String> configsLeftToInstall = new HashSet<String>(requiredConfigPids);
		configsLeftToInstall.removeAll(installedConfigPids);
		LOG.debug("configsLeftToInstall: {}", configsLeftToInstall);

		return bundlesLeftToInstall.isEmpty() && configsLeftToInstall.isEmpty();
	}

}
