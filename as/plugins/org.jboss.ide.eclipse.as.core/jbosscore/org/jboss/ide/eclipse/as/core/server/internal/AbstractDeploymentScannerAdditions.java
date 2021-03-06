/*******************************************************************************
 * Copyright (c) 2007 - 2013 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.jboss.ide.eclipse.as.core.server.internal;

import java.io.File;
import java.util.ArrayList;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IRuntimeType;
import org.eclipse.wst.server.core.IServer;
import org.jboss.ide.eclipse.as.core.Messages;
import org.jboss.ide.eclipse.as.core.server.IDeployableServer;
import org.jboss.ide.eclipse.as.core.server.IDeploymentScannerModifier;
import org.jboss.ide.eclipse.as.core.server.IServerModeDetails;
import org.jboss.ide.eclipse.as.core.util.IJBossToolingConstants;
import org.jboss.ide.eclipse.as.core.util.JBossServerBehaviorUtils;
import org.jboss.ide.eclipse.as.core.util.RemotePath;
import org.jboss.ide.eclipse.as.core.util.ServerConverter;
import org.jboss.ide.eclipse.as.wtp.core.server.behavior.IControllableServerBehavior;
import org.jboss.ide.eclipse.as.wtp.core.server.behavior.ServerProfileModel;
import org.jboss.tools.as.core.server.controllable.systems.IDeploymentOptionsController;

/**
 * @since 3.0
 */
public abstract class AbstractDeploymentScannerAdditions implements IDeploymentScannerModifier {
	// Can this listener handle this server?
	public abstract boolean accepts(IServer server);
	
	protected boolean acceptsSetting(IServer server, String setting, boolean defaultVal) {
		return accepts(server) && server.getAttribute(setting, defaultVal);
	}
	
	/* Do whatever action you need to do to add the scanners (if they don't already exist) for the following folders */
	protected abstract void ensureScannersAdded(final IServer server, final String[] folders);
	
	protected String getJobName(IServer server) {
		return Messages.bind(Messages.UpdateDeploymentScannerJobName, server.getName() );
	}

	public void updateDeploymentScanners(final IServer server) {
		if( acceptsSetting(server, IJBossToolingConstants.PROPERTY_ADD_DEPLOYMENT_SCANNERS, true)) {
			try {
				String[] folders = getDeployLocationFolders(server);
				ensureScannersAdded(server, folders);
			} catch(Exception e) {
				e.printStackTrace();
			}
		}
	}

	public Job getUpdateDeploymentScannerJob(final IServer server) {
		if( !acceptsSetting(server, IJBossToolingConstants.PROPERTY_ADD_DEPLOYMENT_SCANNERS, true))
			return null;
		
		return new Job(getJobName(server)) {
			protected IStatus run(IProgressMonitor monitor) {
				updateDeploymentScanners(server);
				return Status.OK_STATUS;
			}
		};
	}
	
	public void removeAddedDeploymentScanners(IServer server) {
		if( acceptsSetting(server, IJBossToolingConstants.PROPERTY_REMOVE_DEPLOYMENT_SCANNERS, true)) {
			String[] folders = getDeployLocationFolders(server);
			ensureScannersRemoved(server, folders);
		}
	}
	
	public Job getRemoveDeploymentScannerJob(final IServer server) {
		if( !acceptsSetting(server, IJBossToolingConstants.PROPERTY_REMOVE_DEPLOYMENT_SCANNERS, true))
			return null;
		
		return new Job(getJobName(server)) {
			protected IStatus run(IProgressMonitor monitor) {
				removeAddedDeploymentScanners(server);
				return Status.OK_STATUS;
			}
		};
	}
	/* 
	 * Do whatever action you need to do to remove the scanners (if they exist) for the following folders 
	 * SUBCLASSES that support this must override!
	 */
	protected void ensureScannersRemoved(final IServer server, final String[] folders) {
	}

	
	protected String getServerMode(IServer server) {
		return ServerProfileModel.getProfile(server);
	}
	
	protected char getSeparatorCharacter(IServer server) {

		// Discover the proper separator character for the paths being used
		char sep = File.separatorChar;
		IControllableServerBehavior beh = JBossServerBehaviorUtils.getControllableBehavior(server);
		if( beh != null ) {
			try {
				IDeploymentOptionsController cont = (IDeploymentOptionsController)beh.getController(IDeploymentOptionsController.SYSTEM_ID);
				if( cont != null ) {
					sep = cont.getPathSeparatorCharacter();
				}
			} catch(CoreException ce) {
				// Ignore
			}
		}
		return sep;
	}
	
	/**
	 * The implementation here is suitable ONLY for local servers.
	 * 
	 * @param server
	 * @return
	 */
	public String[] getDeployLocationFolders(IServer server) {
		JBossServer ds = (JBossServer)ServerConverter.getJBossServer(server);
		char sep = getSeparatorCharacter(server);

		
		ArrayList<String> folders = new ArrayList<String>();
		String type = ds.getDeployLocationType();
	
		// inside server first, always there
		String insideServer = getInsideServerDeployFolder(server);
		insideServer = new RemotePath(insideServer, sep).removeTrailingSeparator().toOSString(); 
		folders.add(insideServer);
		
		// metadata
		if( type.equals(JBossServer.DEPLOY_METADATA)) {
			String metadata = JBossServer.getDeployFolder(ds, JBossServer.DEPLOY_METADATA);
			metadata = new RemotePath(metadata, sep).removeTrailingSeparator().toOSString();
			if( !folders.contains(metadata))
				folders.add(metadata);
		}
		
		// custom
		if( type.equals(JBossServer.DEPLOY_CUSTOM)) {
			IServerModeDetails det = (IServerModeDetails)Platform.getAdapterManager().getAdapter(server, IServerModeDetails.class);
			String serverHome = det.getProperty(IServerModeDetails.PROP_SERVER_HOME);
			
			String custom1 = server.getAttribute(IDeployableServer.DEPLOY_DIRECTORY, (String)null);
			IPath customPath = new RemotePath(custom1, sep);
			if( custom1 != null && !customPath.isAbsolute()) {
				customPath = new RemotePath(serverHome, sep).append(custom1).removeTrailingSeparator();
			}
			custom1 = customPath.toOSString();
			if( custom1 != null && !folders.contains(custom1) && !serverHome.equals(custom1))
				folders.add(custom1);
		}

		
		IRuntimeType rtt = server.getServerType().getRuntimeType();
		IModule[] modules2 = rtt == null ? null : org.eclipse.wst.server.core.ServerUtil.getModules(rtt.getModuleTypes());
		if (modules2 != null) {
			int size = modules2.length;
			for (int i = 0; i < size; i++) {
				IModule[] module = new IModule[] { modules2[i] };
				IStatus status = server.canModifyModules(module, null, null);
				if (status != null && status.getSeverity() != IStatus.ERROR) {
					IPath moduleDeployPath = ds.getDeploymentLocation(module, false);
					if( moduleDeployPath != null ) {
						String moduleDeploy = moduleDeployPath.toString();
						// we don't want the location. we want its parent. 
						moduleDeploy = new RemotePath(moduleDeploy, sep).removeLastSegments(1).removeTrailingSeparator().toOSString();
						if( !folders.contains(moduleDeploy))
							folders.add(moduleDeploy);
					}
				}
			}
		}
		folders.remove(insideServer); // doesn't need to be added to deployment scanner
		String[] folders2 = (String[]) folders.toArray(new String[folders.size()]);
		return folders2;
	}
	
	/* 
	 * Get the deploy folder for inside the server.
	 *    server/default/deploy,  or
	 *    standalone/deployments
	 */
	protected String getInsideServerDeployFolder(IServer server) {
		IServerModeDetails det = (IServerModeDetails)Platform.getAdapterManager().getAdapter(server, IServerModeDetails.class);
		return det.getProperty(IServerModeDetails.PROP_SERVER_DEPLOYMENTS_FOLDER_ABS);
	}

	/*
	 * An internal method which lets us know whether this app server version
	 * persists changes made to the deployment scanner model or not. 
	 */
	public boolean persistsScannerChanges() {
		return false;
	}


	/*
	 * An internal method which lets us know whether this app server version
	 * can customize the scanner's interval 
	 */
	/**
	 * @since 3.0
	 */
	public boolean canCustomizeInterval() {
		return false;
	}

	/*
	 * An internal method which lets us know whether this app server version
	 * can customize the scanner's timeout 
	 */
	/**
	 * @since 3.0
	 */
	public boolean canCustomizeTimeout() {
		return false;
	}

}
