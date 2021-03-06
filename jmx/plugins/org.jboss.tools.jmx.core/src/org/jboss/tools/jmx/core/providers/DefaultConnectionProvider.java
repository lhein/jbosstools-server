/*******************************************************************************
 * Copyright (c) 2006 Jeff Mesnil
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    "Rob Stryker" <rob.stryker@redhat.com> - Initial implementation
 *******************************************************************************/
/*******************************************************************************
 * Copyright (c) 2013 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/

package org.jboss.tools.jmx.core.providers;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jboss.tools.jmx.core.AbstractConnectionProvider;
import org.jboss.tools.jmx.core.IConnectionCategory;
import org.jboss.tools.jmx.core.IConnectionProvider;
import org.jboss.tools.jmx.core.IConnectionProviderEventEmitter;
import org.jboss.tools.jmx.core.IConnectionWrapper;
import org.jboss.tools.jmx.core.IMemento;
import org.jboss.tools.jmx.core.JMXActivator;
import org.jboss.tools.jmx.core.JMXCoreMessages;
import org.jboss.tools.jmx.core.util.XMLMemento;


/**
 * The default connection type that comes bundled
 */
public class DefaultConnectionProvider extends AbstractConnectionProvider 
	implements IConnectionProvider, IConnectionProviderEventEmitter, IConnectionCategory {
	
	public static final String PROVIDER_ID = "org.jboss.tools.jmx.core.providers.DefaultConnectionProvider"; //$NON-NLS-1$
	public static final String ID = "id"; //$NON-NLS-1$
	public static final String URL = "url"; //$NON-NLS-1$
	public static final String USERNAME = "username"; //$NON-NLS-1$
	public static final String PASSWORD = "password"; //$NON-NLS-1$
	public static final String CONNECTION = "connection";  //$NON-NLS-1$
	public static final String CONNECTIONS = "connections";  //$NON-NLS-1$
	public static final String STORE_FILE = "defaultConnections.xml"; //$NON-NLS-1$
	private Map<String, DefaultConnectionWrapper> connections;

	public DefaultConnectionProvider() {
		// I think it's inappropriate to automatically start
		// Users creating the connection in advance of starting 
		// the server will experience errors for no good reason. 
		//addListener(new AutomaticStarter());    
	}
	
	@Override
	public String getId() {
		return PROVIDER_ID;
	}

	@Override
	public boolean canCreate() {
		return true;
	}
	
	@Override
	public boolean canDelete(IConnectionWrapper wrapper) {
		return wrapper instanceof DefaultConnectionWrapper;
	}

	public boolean canEdit(IConnectionWrapper wrapper) {
		return wrapper instanceof DefaultConnectionWrapper;
	}
	
	@Override
	public DefaultConnectionWrapper createConnection(Map map) throws CoreException {
		String id = (String)map.get(ID);
		String url = (String)map.get(URL);
		String username = (String)map.get(USERNAME);
		String password = (String)map.get(PASSWORD);
		MBeanServerConnectionDescriptor desc = new
			MBeanServerConnectionDescriptor(id, url, username, password);
		try {
			return new DefaultConnectionWrapper(desc);
		} catch( MalformedURLException murle) {
			throw new CoreException(new Status(IStatus.ERROR, JMXActivator.PLUGIN_ID, murle.getLocalizedMessage(), murle));
		}
	}
	
	@Override
	public IConnectionWrapper[] getConnections() {
		ensureConnectionIsInitialized();
		return connections.values().toArray(new IConnectionWrapper[connections.values().size()]);
	}
	
	@Override
	public void addConnection(IConnectionWrapper connection) {
		if( connection instanceof DefaultConnectionWrapper ) {
			MBeanServerConnectionDescriptor descriptor = ((DefaultConnectionWrapper)connection).getDescriptor();
			ensureConnectionIsInitialized();
			connections.put(descriptor.getID(), (DefaultConnectionWrapper)connection);
			try {
				save();
				fireAdded(connection);
			} catch( IOException ioe ) {
				IStatus s = new Status(IStatus.ERROR, JMXActivator.PLUGIN_ID, JMXCoreMessages.DefaultConnection_ErrorAdding, ioe);
				JMXActivator.log(s);
			}
		}
	}
	
	public DefaultConnectionWrapper getConnection(String id) {
		IConnectionWrapper[] wraps = getConnections();
		for( int i = 0; i < wraps.length; i++ ) {
			if( ((DefaultConnectionWrapper)wraps[i]).getDescriptor().getID().equals(id))
				return (DefaultConnectionWrapper)wraps[i];
		}
		return null;
	}
	
	@Override
	public void removeConnection(IConnectionWrapper connection) {
		if( connection instanceof DefaultConnectionWrapper ) {
			MBeanServerConnectionDescriptor descriptor =
				((DefaultConnectionWrapper)connection).getDescriptor();
			if (descriptor != null) {
				ensureConnectionIsInitialized();
				connections.remove(descriptor.getID());
			}
			try {
				save();
				fireRemoved(connection);
			} catch( IOException ioe ) {
				IStatus s = new Status(IStatus.ERROR, JMXActivator.PLUGIN_ID, JMXCoreMessages.DefaultConnection_ErrorRemoving, ioe);
				JMXActivator.log(s);
			}
		}
	}

	private void ensureConnectionIsInitialized() {
		if( connections == null ) {
			loadConnections();
		}
	}

	@Override
	public void connectionChanged(IConnectionWrapper connection) {
		if( connection instanceof DefaultConnectionWrapper ) {
			try {
				save();
				fireChanged(connection);
			} catch( IOException ioe ) {
				IStatus s = new Status(IStatus.ERROR, JMXActivator.PLUGIN_ID, JMXCoreMessages.DefaultConnection_ErrorChanging, ioe);
				JMXActivator.log(s);
			}
		}
	}

	protected void loadConnections() {
		String filename = JMXActivator.getDefault().getStateLocation().append(STORE_FILE).toOSString();
		Map<String, DefaultConnectionWrapper> map = new HashMap<>();
		if( new File(filename).exists()) {
			try {
				IMemento root = XMLMemento.loadMemento(filename);
				IMemento[] child = root.getChildren(CONNECTION);
				for( int i = 0; i < child.length; i++ ) {
					String id = child[i].getString(ID);
					String url = child[i].getString(URL);
					String username = child[i].getString(USERNAME);
					String password = child[i].getString(PASSWORD);
					MBeanServerConnectionDescriptor desc = new MBeanServerConnectionDescriptor(id, url, username, password);
					try {
						DefaultConnectionWrapper connection = new DefaultConnectionWrapper(desc);
						map.put(id, connection);
					} catch( MalformedURLException murle) {
						// TODO LOG
					}
				}
				connections = map;
			} catch( IOException ioe ) {
				IStatus s = new Status(IStatus.ERROR, JMXActivator.PLUGIN_ID, JMXCoreMessages.DefaultConnection_ErrorLoading, ioe);
				JMXActivator.log(s);
			}
		} else {
			connections = map;
		}
	}

	protected void save() throws IOException {
		String filename = JMXActivator.getDefault().getStateLocation().append(STORE_FILE).toOSString();
		List<String> keys = new ArrayList<>();
		keys.addAll(connections.keySet());
		Collections.sort(keys);
		DefaultConnectionWrapper wrapper;
		MBeanServerConnectionDescriptor descriptor;
		XMLMemento root = XMLMemento.createWriteRoot(CONNECTIONS);
		Iterator<String> i = keys.iterator();
		while(i.hasNext()) {
			 wrapper = connections.get(i.next());
			if( wrapper != null ) {
				descriptor = wrapper.getDescriptor();
				if( descriptor != null ) {
					IMemento child = root.createChild(CONNECTION);
					child.putString(ID, descriptor.getID());
					child.putString(URL, descriptor.getURL());
					child.putString(USERNAME, descriptor.getUserName());
					child.putString(PASSWORD, descriptor.getPassword());
				}
			}
		}
		root.saveToFile(filename);
	}

	@Override
	public String getName(IConnectionWrapper wrapper) {
		if( wrapper instanceof DefaultConnectionWrapper ) {
			MBeanServerConnectionDescriptor desc =
				((DefaultConnectionWrapper)wrapper).getDescriptor();
			if( desc != null )
				return desc.getID();
		}
		return null;
	}

	@Override
	public String getCategoryId() {
		return IConnectionCategory.DEFINED_CATEGORY;
	}
}
