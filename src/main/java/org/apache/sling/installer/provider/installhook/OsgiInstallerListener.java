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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.sling.installer.api.event.InstallationEvent;
import org.apache.sling.installer.api.event.InstallationEvent.TYPE;
import org.apache.sling.installer.api.event.InstallationListener;
import org.apache.sling.installer.api.tasks.TaskResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsgiInstallerListener implements InstallationListener {

    private static final Logger LOG = LoggerFactory.getLogger(OsgiInstallerListener.class);

    private final Set<String> initialBundleUrlsToInstall;
    private final Set<String> initialConfigUrlsToInstall;

    private final Set<String> bundleUrlsToInstall;
    private final Set<String> configUrlsToInstall;

    public OsgiInstallerListener(Set<String> bundleUrlsToInstall, Set<String> configUrlsToInstall) {
        this.initialBundleUrlsToInstall = bundleUrlsToInstall;
        this.initialConfigUrlsToInstall = configUrlsToInstall;

        this.bundleUrlsToInstall = Collections.synchronizedSet(new HashSet<>(initialBundleUrlsToInstall));
        this.configUrlsToInstall = Collections.synchronizedSet(new HashSet<>(initialConfigUrlsToInstall));
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
            String url = source.getURL();

            LOG.trace("Received event about processed entityId={} url={}", entityId, url);

            if (bundleUrlsToInstall.contains(url)) {
                LOG.debug("Received event for bundle installed with url={}", url);
                bundleUrlsToInstall.remove(url);
            }
            if (configUrlsToInstall.contains(url)) {
                LOG.debug("Received event for config installed with url={}", url);
                configUrlsToInstall.remove(url);
            }
        }
    }

    public int bundlesLeftToInstall() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("initialBundleUrlsToInstall: {}", initialBundleUrlsToInstall);
            LOG.trace("bundleUrlsToInstall: {}", bundleUrlsToInstall);
        }
        return bundleUrlsToInstall.size();
    }

    public int configsLeftToInstall() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("initialConfigUrlsToInstall: {}", initialConfigUrlsToInstall);
            LOG.trace("configUrlsToInstall: {}", configUrlsToInstall);
        }
        return configUrlsToInstall.size();
    }

}
