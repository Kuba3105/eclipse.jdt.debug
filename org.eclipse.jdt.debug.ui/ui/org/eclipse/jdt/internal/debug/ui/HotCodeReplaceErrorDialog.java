/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.debug.ui;

import java.text.MessageFormat;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;

/**
 * An error dialog reporting a problem with a debug
 * target which gives the user the option to continue
 * or terminate/disconnect or restart the target.
 */
public class HotCodeReplaceErrorDialog extends ErrorDialogWithToggle {

	protected IDebugTarget target;
	// The IDs of the buttons. Set to the sum of the other possible IDs generated by 
	// this dialog to ensure the IDs' uniqueness.
	protected int TERMINATE_ID= IDialogConstants.OK_ID + IDialogConstants.DETAILS_ID + IDialogConstants.CANCEL_ID;
	protected int DISCONNECT_ID= TERMINATE_ID + 1;
	protected int RESTART_ID= TERMINATE_ID + 2;

	/**
	 * Creates a new dialog which can terminate, disconnect or restart the given debug target.
	 * 
	 * @param target the debug target
	 * @see ErrorDialogWithToggle#ErrorDialogWithToggle(Shell, String, String, IStatus, String, String, IPreferenceStore)
	 */
	public HotCodeReplaceErrorDialog(Shell parentShell, String dialogTitle, String message, IStatus status, String preferenceKey, String toggleMessage, IPreferenceStore store, IDebugTarget target) {
		super(parentShell, dialogTitle, message, status, preferenceKey, toggleMessage, store);
		this.target = target;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.Dialog#createButtonsForButtonBar(org.eclipse.swt.widgets.Composite)
	 */
	protected void createButtonsForButtonBar(Composite parent) {
		super.createButtonsForButtonBar(parent);
		getButton(IDialogConstants.OK_ID).setText(DebugUIMessages.HotCodeReplaceErrorDialog_0); 
		boolean canTerminate= target.canTerminate();
		boolean canDisconnect= target.canDisconnect();
		if (canTerminate) {
			createButton(parent, TERMINATE_ID, DebugUIMessages.HotCodeReplaceErrorDialog_1, false); 
		} 
		if (canDisconnect) {
			createButton(parent, DISCONNECT_ID, DebugUIMessages.HotCodeReplaceErrorDialog_3, false); 
		}
		if (canTerminate && !canDisconnect) {
			createButton(parent, RESTART_ID, DebugUIMessages.HotCodeReplaceErrorDialog_7, false); 
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.Dialog#buttonPressed(int)
	 */
	protected void buttonPressed(final int id) {
		if (id == TERMINATE_ID || id == DISCONNECT_ID || id == RESTART_ID) {
			final DebugException[] ex = new DebugException[1];
			final String[] operation = new String[1];
			ex[0] = null;
			Runnable r = new Runnable() {
				public void run() {
					try {
						if (id == TERMINATE_ID) {
							operation[0]= DebugUIMessages.HotCodeReplaceErrorDialog_5; 
							target.terminate();
						} else if (id == DISCONNECT_ID){
							operation[0]= DebugUIMessages.HotCodeReplaceErrorDialog_6; 
							target.disconnect();
						} else {
							operation[0]= DebugUIMessages.HotCodeReplaceErrorDialog_8; 
							ILaunch launch = target.getLaunch();
							launch.terminate();
							ILaunchConfiguration config = launch.getLaunchConfiguration();
							if (config != null  && config.exists()) {
								DebugUITools.launch(config, launch.getLaunchMode());
							}
						}
					} catch (DebugException e) {
						ex[0] = e;
					}
				}
			};
			BusyIndicator.showWhile(getShell().getDisplay(), r);
			if (ex[0] != null) {
				JDIDebugUIPlugin.errorDialog(MessageFormat.format(DebugUIMessages.HotCodeReplaceErrorDialog_2, operation), ex[0].getStatus()); 
			}
			okPressed();
		} else {
			super.buttonPressed(id);
		}
	}
}
