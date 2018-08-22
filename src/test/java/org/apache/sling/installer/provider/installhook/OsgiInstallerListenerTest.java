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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
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
		
		String bundleSymbolicId1 = "org.prj.bundle1";
		String bundleSymbolicId2 = "org.prj.bundle2";
		Set<String> requiredBundleSymbolicNames = new HashSet<String>(Arrays.asList(bundleSymbolicId1, bundleSymbolicId2));
		String configPid1 = "org.prj.config1";
		String configPid2 = "org.prj.config2";
		Set<String> requiredConfigPids = new HashSet<String>(Arrays.asList(configPid1, configPid2));

		osgiInstallerListener = new OsgiInstallerListener(requiredBundleSymbolicNames, requiredConfigPids);

		assertFalse(osgiInstallerListener.isDone());
		osgiInstallerListener.onEvent(getInstallationEventMock(OsgiInstallerListener.ENTITY_ID_PREFIX_BUNDLE + bundleSymbolicId1));
		assertFalse(osgiInstallerListener.isDone());
		osgiInstallerListener.onEvent(getInstallationEventMock(OsgiInstallerListener.ENTITY_ID_PREFIX_BUNDLE + bundleSymbolicId2));
		assertFalse(osgiInstallerListener.isDone());
		osgiInstallerListener.onEvent(getInstallationEventMock(OsgiInstallerListener.ENTITY_ID_PREFIX_CONFIG + configPid1));
		assertFalse(osgiInstallerListener.isDone());
		osgiInstallerListener.onEvent(getInstallationEventMock(OsgiInstallerListener.ENTITY_ID_PREFIX_CONFIG + configPid2));
		assertTrue(osgiInstallerListener.isDone());
		
	}
	
	public InstallationEvent getInstallationEventMock(String entityId) {
		InstallationEvent event = mock(InstallationEvent.class);
		when(event.getType()).thenReturn(TYPE.PROCESSED);
		TaskResource taskResource = mock(TaskResource.class);
		when(event.getSource()).thenReturn(taskResource);
		when(taskResource.getEntityId()).thenReturn(entityId);
		return event;
	}

}
