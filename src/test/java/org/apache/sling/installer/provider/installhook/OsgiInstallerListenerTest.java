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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;

import org.apache.sling.installer.api.event.InstallationEvent;
import org.apache.sling.installer.api.event.InstallationEvent.TYPE;
import org.apache.sling.installer.api.tasks.TaskResource;
import org.junit.Test;

public class OsgiInstallerListenerTest {

    private OsgiInstallerListener osgiInstallerListener;

    @Test
    public void testOsgiInstallerListener() {

        Set<String> bundleUrlsToInstall = new HashSet<String>();
        String bundleUrl1 = "jcrinstall:/apps/myproj/install/mybundle1.jar";
        bundleUrlsToInstall.add(bundleUrl1);
        String bundleUrl2 = "jcrinstall:/apps/myproj/install/mybundle2.jar";
        bundleUrlsToInstall.add(bundleUrl2);

        Set<String> configUrlsToInstall = new HashSet<String>();
        String configUrl1 = "jcrinstall:/apps/myproj/config/conf1.config";
        configUrlsToInstall.add(configUrl1);
        String configUrl2 = "jcrinstall:/apps/myproj/config/conf2.config";
        configUrlsToInstall.add(configUrl2);

        osgiInstallerListener = new OsgiInstallerListener(bundleUrlsToInstall, configUrlsToInstall);

        assertEquals(2, osgiInstallerListener.bundlesLeftToInstall());
        assertEquals(2, osgiInstallerListener.configsLeftToInstall());

        osgiInstallerListener.onEvent(getInstallationEventMock(bundleUrl1));
        assertEquals(1, osgiInstallerListener.bundlesLeftToInstall());
        assertEquals(2, osgiInstallerListener.configsLeftToInstall());

        osgiInstallerListener.onEvent(getInstallationEventMock(bundleUrl2));
        assertEquals(0, osgiInstallerListener.bundlesLeftToInstall());
        assertEquals(2, osgiInstallerListener.configsLeftToInstall());

        osgiInstallerListener.onEvent(getInstallationEventMock(configUrl1));
        assertEquals(0, osgiInstallerListener.bundlesLeftToInstall());
        assertEquals(1, osgiInstallerListener.configsLeftToInstall());

        osgiInstallerListener.onEvent(getInstallationEventMock(configUrl2));
        assertEquals(0, osgiInstallerListener.bundlesLeftToInstall());
        assertEquals(0, osgiInstallerListener.configsLeftToInstall());

    }

    public InstallationEvent getInstallationEventMock(String url) {
        InstallationEvent event = mock(InstallationEvent.class);
        when(event.getType()).thenReturn(TYPE.PROCESSED);
        TaskResource taskResource = mock(TaskResource.class);
        when(event.getSource()).thenReturn(taskResource);
        when(taskResource.getURL()).thenReturn(url);
        when(taskResource.getEntityId()).thenReturn("dummyEntityId");
        return event;
    }

}
