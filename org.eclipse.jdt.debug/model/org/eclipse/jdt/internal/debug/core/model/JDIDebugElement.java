/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.debug.core.model;

 
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IDisconnect;
import org.eclipse.debug.core.model.IStepFilters;
import org.eclipse.jdi.TimeoutException;
import org.eclipse.jdi.hcr.OperationRefusedException;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.internal.debug.core.EventDispatcher;
import org.eclipse.jdt.internal.debug.core.IJDIEventListener;
import org.eclipse.jdt.internal.debug.core.JDIDebugPlugin;

import com.sun.jdi.ClassNotPreparedException;
import com.sun.jdi.InconsistentDebugInfoException;
import com.sun.jdi.InternalException;
import com.sun.jdi.InvalidCodeIndexException;
import com.sun.jdi.InvalidLineNumberException;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.NativeMethodException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VMMismatchException;
import com.sun.jdi.VMOutOfMemoryException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.DuplicateRequestException;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.InvalidRequestStateException;

public abstract class JDIDebugElement extends PlatformObject implements IDebugElement, IDisconnect {
			
	/**
	 * Collection of possible JDI exceptions (runtime)
	 */
	private static List fgJDIExceptions;
	
	/**
	 * Debug target associated with this element
	 */
	private JDIDebugTarget fDebugTarget;
	
	static {
		fgJDIExceptions = new ArrayList(15);
		
		// Runtime/unchecked exceptions
		fgJDIExceptions.add(ClassNotPreparedException.class);
		fgJDIExceptions.add(InconsistentDebugInfoException.class);
		fgJDIExceptions.add(InternalException.class);
		fgJDIExceptions.add(InvalidCodeIndexException.class);
		fgJDIExceptions.add(InvalidLineNumberException.class);
		fgJDIExceptions.add(InvalidStackFrameException.class);
		fgJDIExceptions.add(NativeMethodException.class);
		fgJDIExceptions.add(ObjectCollectedException.class);
		fgJDIExceptions.add(TimeoutException.class);
		fgJDIExceptions.add(VMDisconnectedException.class);
		fgJDIExceptions.add(VMMismatchException.class);
		fgJDIExceptions.add(VMOutOfMemoryException.class);
		fgJDIExceptions.add(DuplicateRequestException.class);
		fgJDIExceptions.add(InvalidRequestStateException.class);
		fgJDIExceptions.add(OperationRefusedException.class);
	}
	
	/**
	 * Creates a JDI debug element associated with the
	 * specified debug target.
	 * 
	 * @param target The associated debug target
	 */
	public JDIDebugElement(JDIDebugTarget target) {
		setDebugTarget(target);
	}

	/**
	 * Convenience method to log errors
	 */
	protected void logError(Exception e) {
		if (!((JDIDebugTarget)getDebugTarget()).isAvailable()) {
			// Don't log VMDisconnectedExceptions that occur
			// when the VM is unavailable.
			if (e instanceof VMDisconnectedException || 
				(e instanceof CoreException && ((CoreException)e).getStatus().getException() instanceof VMDisconnectedException)) {
				return;
			}
		}
		JDIDebugPlugin.log(e);
	}
	
	/**
	 * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
	 */
	public Object getAdapter(Class adapter) {
		if (adapter == IDebugElement.class) {
			return this;
		}
		if (adapter == IStepFilters.class) {
			return getDebugTarget();
		}
		if (adapter == IDebugTarget.class) {
			return getDebugTarget();
		}
		return super.getAdapter(adapter);
	}
	
	/**
	 * @see org.eclipse.debug.core.model.IDebugElement#getModelIdentifier()
	 */
	public String getModelIdentifier() {
		return JDIDebugModel.getPluginIdentifier();
	}
	
	/**
	 * Fires a debug event marking the creation of this element.
	 */
	protected void fireCreationEvent() {
		fireEvent(new DebugEvent(this, DebugEvent.CREATE));
	}

	/**
	 * Fires a debug event
	 * 
	 * @param event The debug event to be fired to the listeners
	 * @see org.eclipse.debug.core.DebugEvent
	 */
	protected void fireEvent(DebugEvent event) {
		DebugPlugin.getDefault().fireDebugEventSet(new DebugEvent[] {event});
	}

	/**
	 * Queues a debug event with the event dispatcher to be fired
	 * as an event set when all event processing is complete.
	 * 
	 * @param event the event to queue
	 */
	public void queueEvent(DebugEvent event) {
		EventDispatcher dispatcher = ((JDIDebugTarget)getDebugTarget()).getEventDispatcher();
		if (dispatcher != null) {
			dispatcher.queue(event);
		}
	}
	
	/**
	 * Fires a debug event marking the RESUME of this element with
	 * the associated detail.
	 * 
	 * @param detail The int detail of the event
	 * @see org.eclipse.debug.core.DebugEvent
	 */
	public void fireResumeEvent(int detail) {
		fireEvent(new DebugEvent(this, DebugEvent.RESUME, detail));
	}

	/**
	 * Fires a debug event marking the SUSPEND of this element with
	 * the associated detail.
	 * 
	 * @param detail The int detail of the event
	 * @see org.eclipse.debug.core.DebugEvent
	 */
	public void fireSuspendEvent(int detail) {
		getJavaDebugTarget().incrementSuspendCount();
		fireEvent(new DebugEvent(this, DebugEvent.SUSPEND, detail));
	}
	
	/**
	 * Queues a debug event marking the SUSPEND of this element with
	 * the associated detail.
	 * 
	 * @param detail The int detail of the event
	 * @see org.eclipse.debug.core.DebugEvent
	 */
	public void queueSuspendEvent(int detail) {
		getJavaDebugTarget().incrementSuspendCount();
		queueEvent(new DebugEvent(this, DebugEvent.SUSPEND, detail));
	}
			
	/**
	 * Fires a debug event marking the termination of this element.
	 */
	protected void fireTerminateEvent() {
		fireEvent(new DebugEvent(this, DebugEvent.TERMINATE));
	}

	/**
	 * Fires a debug event marking the CHANGE of this element
	 * with the specifed detail code.
	 * 
	 * @param detail one of <code>STATE</code> or <code>CONTENT</code>
	 */
	public void fireChangeEvent(int detail) {
		fireEvent(new DebugEvent(this, DebugEvent.CHANGE, detail));
	}
	
	/**
	 * Throws a new debug exception with a status code of <code>REQUEST_FAILED</code>.
	 * 
	 * @param message Failure message
	 * @param e Exception that has occurred (<code>can be null</code>)
	 * @throws DebugException The exception with a status code of <code>REQUEST_FAILED</code>
	 */
	public void requestFailed(String message,  Exception e) throws DebugException {
		requestFailed(message, e, DebugException.REQUEST_FAILED);
	}
	
	/**
	 * Throws a new debug exception with a status code of <code>TARGET_REQUEST_FAILED</code>
	 * with the given underlying exception. If the underlying exception is not a JDI
	 * exception, the original exception is thrown.
	 * 
	 * @param message Failure message
	 * @param e underlying exception that has occurred
	 * @throws DebugException The exception with a status code of <code>TARGET_REQUEST_FAILED</code>
	 */
	public void targetRequestFailed(String message, RuntimeException e) throws DebugException {
		if (e == null || fgJDIExceptions.contains(e.getClass())) {
			requestFailed(message, e, DebugException.TARGET_REQUEST_FAILED);
		} else {
			throw e;
		}
	}

	/**
	 * Throws a new debug exception with the given status code.
	 * 
	 * @param message Failure message
	 * @param e Exception that has occurred (<code>can be null</code>)
	 * @param code status code
	 * @throws DebugException a new exception with given status code
	 */
	public void requestFailed(String message,  Throwable e, int code) throws DebugException {
		throwDebugException(message, code, e);
	}
		
	/**
	 * Throws a new debug exception with a status code of <code>TARGET_REQUEST_FAILED</code>.
	 * 
	 * @param message Failure message
	 * @param e Throwable that has occurred
	 * @throws DebugException The exception with a status code of <code>TARGET_REQUEST_FAILED</code>
	 */
	public void targetRequestFailed(String message, Throwable e) throws DebugException {
		throwDebugException(message, DebugException.TARGET_REQUEST_FAILED, e);
	}
	
	/**
	 * Throws a new debug exception with a status code of <code>TARGET_REQUEST_FAILED</code>
	 * with the given underlying exception. The underlying exception is an exception thrown
	 * by a JDI request.
	 * 
	 * @param message Failure message
	 * @param e throwable exception that has occurred
	 * @throws DebugException the exception with a status code of <code>TARGET_REQUEST_FAILED</code>
	 */
	public void jdiRequestFailed(String message, Throwable e) throws DebugException {
		throwDebugException(message, DebugException.TARGET_REQUEST_FAILED, e);
	}	
	
	/**
	 * Throws a new debug exception with a status code of <code>NOT_SUPPORTED</code>.
	 * 
	 * @param message Failure message
	 * @throws DebugException The exception with a status code of <code>NOT_SUPPORTED</code>.
	 */
	public void notSupported(String message) throws DebugException {
		throwDebugException(message, DebugException.NOT_SUPPORTED, null);
	}
	
	/**
	 * Throws a debug exception with the given message, error code, and underlying
	 * exception.
	 */
	protected void throwDebugException(String message, int code, Throwable exception) throws DebugException {
		throw new DebugException(new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
			code, message, exception));
	}
	
	/**
	 * Logs the given exception if it is a JDI exception, otherwise throws the 
	 * runtime exception.
	 * 
	 * @param e The internal runtime exception
	 */
	public void internalError(RuntimeException e) {
		if (fgJDIExceptions.contains(e.getClass())) {
			logError(e);
		} else {
			throw e;
		}
	}
	
	/**
	 * Logs a debug exception with the given message,
	 * with a status code of <code>INTERNAL_ERROR</code>.
	 * 
	 * @param message The internal error message
	 */
	protected void internalError(String message) {
		logError(new DebugException(new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
			DebugException.INTERNAL_ERROR, message, null)));
	}

	/**
	 * Returns the common "<unknown>" message.
	 * 
	 * @return the unknown String
	 */
	protected String getUnknownMessage() {
		return JDIDebugModelMessages.getString("JDIDebugElement.unknown"); //$NON-NLS-1$
	}
	
	/**
	 * @see org.eclipse.debug.core.model.IDebugElement#getDebugTarget()
	 */
	public IDebugTarget getDebugTarget() {
		return fDebugTarget;
	}
	
	/**
	 * Returns this elements debug target as its implementation
	 * class.
	 * 
	 * @return Java debug target
	 */
	protected JDIDebugTarget getJavaDebugTarget() {
		return fDebugTarget;
	}

	/**
	 * Returns the target VM associated with this element, or <code>null</code>
	 * if none.
	 * 
	 * @return target VM or <code>null</code> if none
	 */
	protected VirtualMachine getVM() {
		return ((JDIDebugTarget)getDebugTarget()).getVM();
	}
	
	/**
	 * Returns the underlying VM's event request manager, or <code>null</code>
	 * if none (disconnected/terminated)
	 * 
	 * @return event request manager or <code>null</code>
	 */
	public EventRequestManager getEventRequestManager() {
		VirtualMachine vm = getVM();
		if (vm == null) {
			return null;
		} else {
			return vm.eventRequestManager();
		}
	}
	
	/**
	 * Adds the given listener to this target's event dispatcher's
	 * table of listeners for the specified event request. The listener
	 * will be notified each time the event occurs.
	 * 
	 * @param listener the listener to register
	 * @param request the event request
	 */
	public void addJDIEventListener(IJDIEventListener listener, EventRequest request) {
		EventDispatcher dispatcher = ((JDIDebugTarget)getDebugTarget()).getEventDispatcher();
		if (dispatcher != null) {
			dispatcher.addJDIEventListener(listener, request);
		}
	}
	
	/**
	 * Removes the given listener from this target's event dispatcher's
	 * table of listeners for the specifed event request. The listener
	 * will no longer be notified when the event occurs. Listeners
	 * are responsible for deleting the event request if desired.
	 * 
	 * @param listener the listener to remove
	 * @param request the event request
	 */
	public void removeJDIEventListener(IJDIEventListener listener, EventRequest request) {
		EventDispatcher dispatcher = ((JDIDebugTarget)getDebugTarget()).getEventDispatcher();
		if (dispatcher != null) {
			dispatcher.removeJDIEventListener(listener, request);
		}
	}
	
	/**
	 * @see IDebugElement#getLaunch()
	 */
	public ILaunch getLaunch() {
		return getDebugTarget().getLaunch();
	}
	
	protected void setDebugTarget(JDIDebugTarget debugTarget) {
		fDebugTarget = debugTarget;
	}

	/**
	 * The VM has disconnected. Notify the target.
	 */
	protected void disconnected() {
		if (fDebugTarget != null) {
			fDebugTarget.disconnected();
		}
	}
	
	/**
	 * @see IJavaDebugTarget#setRequestTimeout(int)
	 */
	public void setRequestTimeout(int timeout) {
		if (supportsRequestTimeout()) {
			VirtualMachine vm = getVM();
			if (vm != null) {
				((org.eclipse.jdi.VirtualMachine)vm).setRequestTimeout(timeout);
			}
		}
	}
	
	/**
	 * @see IJavaDebugTarget#getRequestTimeout()
	 */
	public int getRequestTimeout() {
		if (supportsRequestTimeout()) {
			VirtualMachine vm = getVM();
			if (vm != null) {
				return ((org.eclipse.jdi.VirtualMachine)vm).getRequestTimeout();
			}
		}
		return -1;
	}	
	/**
	 * @see IJavaDebugTarget#supportsRequestTimeout()
	 */
	public boolean supportsRequestTimeout() {
		return getJavaDebugTarget().isAvailable() && getVM() instanceof org.eclipse.jdi.VirtualMachine;
	}
	
	/**
	 * @see org.eclipse.debug.core.model.IDisconnect#canDisconnect()
	 */
	public boolean canDisconnect() {
		return getDebugTarget().canDisconnect();
	}

	/**
	 * @see org.eclipse.debug.core.model.IDisconnect#disconnect()
	 */
	public void disconnect() throws DebugException {
		getDebugTarget().disconnect();
	}

	/**
	 * @see org.eclipse.debug.core.model.IDisconnect#isDisconnected()
	 */
	public boolean isDisconnected() {
		return getDebugTarget().isDisconnected();
	}

	/**
	 * @see org.eclipse.debug.core.model.IStepFilters#isStepFiltersEnabled()
	 */
	public boolean isStepFiltersEnabled() {
		return getJavaDebugTarget().isStepFiltersEnabled();
	}
}
