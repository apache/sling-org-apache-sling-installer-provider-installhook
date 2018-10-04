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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.apache.jackrabbit.vault.fs.io.Archive;
import org.apache.jackrabbit.vault.fs.io.Archive.Entry;
import org.apache.jackrabbit.vault.packaging.PackageProperties;
import org.apache.sling.installer.api.InstallableResource;
import org.apache.sling.installer.provider.installhook.OsgiInstallerHook.BundleInPackage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OsgiInstallerHookTest {

    OsgiInstallerHook osgiInstallerHook;

    @Mock
    Node node;

    @Mock
    Node parentNode;

    @Mock
    Property lastModifiedProperty;

    @Mock
    Property contentDataProperty;

    @Mock
    Archive archive;

    @Mock
    PackageProperties packageProperties;

    @Mock
    Entry entry;

    Calendar now;

    @Before
    public void setup() throws RepositoryException {
        osgiInstallerHook = new OsgiInstallerHook();

        now = Calendar.getInstance();
        when(node.getProperty(OsgiInstallerHook.JCR_CONTENT_LAST_MODIFIED)).thenReturn(lastModifiedProperty);
        when(lastModifiedProperty.getDate()).thenReturn(now);
        when(node.getProperty(OsgiInstallerHook.JCR_CONTENT_DATA)).thenReturn(contentDataProperty);
        when(node.getParent()).thenReturn(parentNode);

    }

    @Test
    public void testConvert() throws IOException, RepositoryException {

        File pathToTest = new File("/apps/myproj/install/mybundle.jar");
        when(parentNode.getName()).thenReturn(pathToTest.getParentFile().getName());

        InstallableResource installableResource = osgiInstallerHook.convert(node, pathToTest.getAbsolutePath(), packageProperties);

        assertEquals(String.valueOf(now.getTimeInMillis()), installableResource.getDigest());
        assertEquals(pathToTest.getParentFile().getName(), installableResource.getDictionary().get(InstallableResource.INSTALLATION_HINT));

    }

    public void testCollectResources() throws IOException, RepositoryException {

        File pathToTest = new File("/apps/myproj/install.author/myconfig.config");
        String dirPath = "/jcr_root" + pathToTest.getParentFile().getPath() + "/";

        when(entry.getName()).thenReturn(pathToTest.getName());

        List<BundleInPackage> bundleResources = new ArrayList<BundleInPackage>();
        List<String> configResources = new ArrayList<String>();

        osgiInstallerHook.collectResources(archive, entry, dirPath, bundleResources, configResources,
                "/apps/other.*", new HashSet<String>(Arrays.asList("author", "dev")));

        assertTrue(bundleResources.isEmpty());
        assertTrue(configResources.isEmpty());

        osgiInstallerHook.collectResources(archive, entry, dirPath, bundleResources, configResources,
                "/apps/myproj.*", new HashSet<String>(Arrays.asList("publish", "dev")));

        assertTrue(bundleResources.isEmpty());
        assertTrue(configResources.isEmpty());

        osgiInstallerHook.collectResources(archive, entry, dirPath, bundleResources, configResources,
                "/apps/myproj.*", new HashSet<String>(Arrays.asList("author", "dev")));

        assertTrue(bundleResources.isEmpty());
        assertEquals(1, configResources.size());
        assertEquals(pathToTest.getAbsolutePath(), configResources.get(0));

    }

}
