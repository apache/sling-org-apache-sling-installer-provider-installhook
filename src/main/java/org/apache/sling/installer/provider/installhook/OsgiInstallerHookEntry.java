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

import org.apache.jackrabbit.vault.packaging.InstallContext;
import org.apache.jackrabbit.vault.packaging.InstallHook;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsgiInstallerHookEntry implements InstallHook {
    private static final Logger LOG = LoggerFactory.getLogger(OsgiInstallerHookEntry.class);

    public static String OSGI_INSTALLER_CLASSNAME = "org.apache.sling.installer.api.OsgiInstaller";
    public static String HOOK_CLASSNAME = "org.apache.sling.installer.provider.installhook.OsgiInstallerHook";

    @Override
    public void execute(InstallContext context) throws PackageException {

        try {
            Class<?> osgiInstallerClass = getClass().getClassLoader().loadClass(OSGI_INSTALLER_CLASSNAME);
            LOG.debug("Osgi Installer Class found: {}", osgiInstallerClass);
            loadAndRunInstallHook(context);
        } catch (ClassNotFoundException e) {
            LOG.info("Class {} not found, skipping installer hook for package {}", OSGI_INSTALLER_CLASSNAME, context.getPackage().getId());
        }
    }

    private void loadAndRunInstallHook(InstallContext context) throws PackageException {
        InstallHook actualHook = null;
        try {
            Class<?> actualHookClass = getClass().getClassLoader().loadClass(HOOK_CLASSNAME);
            actualHook = (InstallHook) actualHookClass.newInstance();
        } catch (Exception e) {
            LOG.error("Could not load/instantiate " + HOOK_CLASSNAME + ": " + e, e);
            return;
        }

        actualHook.execute(context);
    }

}
