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
package org.eclipse.jdt.internal.debug.core;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.internal.debug.core.model.JDIDebugTarget;

import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.EventRequest;

/**
 * Dispatches events generated by an underlying VM.
 * There is one event dispatcher per JDI debug target.
 * <p>
 * Event listeners register with a debug target to handle
 * specific event requests. A debug target forwards event
 * listeners and requests to its event dispatcher. As events
 * are received from the underlying VM, those listeners that
 * registered to handle the specific events are notified.
 * </p>
 * <p>
 * Events are processed in event sets. It is possible that one
 * event can trigger more than one event request to be processed.
 * In such cases all event requests triggered by that one event are
 * processed, and each event listener votes on whether the thread
 * in which the event occurred should be resumed. A thread is only
 * resumed in if all event handlers agree that the thread should be
 * resumed.
 * </p>
 */

public class EventDispatcher implements Runnable {
	/**
	 * The debug target this event dispatcher belongs to.
	 */
	private JDIDebugTarget fTarget;
	/**
	 * Whether this dispatcher is shutdown.
	 */
	private boolean fShutdown;
	/**
	 * Table of event listeners. Table is
	 * a mapping of <code>EventRequest</code>
	 * to <code>IJDIEventListener</code>.
	 */
	private HashMap fEventHandlers;
	
	/**
	 * Queue of debug model events to fire, created
	 * when processing events on the target VM
	 */
	private List fDebugEvents = new ArrayList(5);
	
	/**
	 * Collection of deferred events for conditional breakpoints.
	 * Conditional breakpoints are handled after all other events
	 * in an event set, such that we only evaluate conditions
	 * if required.
	 */
	private List fDeferredEvents = new ArrayList(5);
	
	/**
	 * Constructs a new event dispatcher listening for events
	 * originating from the specified debug target's underlying
	 * VM.
	 * 
	 * @param target the target this event dispatcher belongs to
	 */
	public EventDispatcher(JDIDebugTarget target) {
		fEventHandlers = new HashMap(10);
		fTarget= target;
		fShutdown = false;
	}

	/**
	 * Dispatch the given event set.
	 * 
	 * @param eventSet events to dispatch
	 */
	protected void dispatch(EventSet eventSet) {
		if (isShutdown()) {
			return;
		}
		EventIterator iter= eventSet.eventIterator();
		boolean vote = false; 
		boolean resume = true;
		int voters = 0; 
		Event winningEvent = null;
		IJDIEventListener winner = null;
		while (iter.hasNext()) {
			if (isShutdown()) {
				return;
			}
			Event event= iter.nextEvent();
			if (event == null) {
				continue;
			}
			// Dispatch events to registered listeners, if any
			IJDIEventListener listener = (IJDIEventListener)fEventHandlers.get(event.request());
			if (listener != null) {
				if (listener instanceof IJavaLineBreakpoint) {
					// Event dispatch to conditional breakpoints is deferred until after
					// other listeners vote.
					try {
						if (((IJavaLineBreakpoint)listener).isConditionEnabled()) {
							defer(event);
							continue;
						}
					} catch (CoreException exception) {
						JDIDebugPlugin.log(exception);
					}
				}
				vote = true;
				resume = listener.handleEvent(event, fTarget) && resume;
				voters++;
				if (!resume && winner == null) {
					winner = listener;
					winningEvent = event;
				}
				continue;
			}
			
			// Dispatch VM start/end events
			if (event instanceof VMDeathEvent) {
				fTarget.handleVMDeath((VMDeathEvent) event);
				shutdown(); // stop listening for events
			} else if (event instanceof VMDisconnectEvent) {
				fTarget.handleVMDisconnect((VMDisconnectEvent) event);
				shutdown(); // stop listening for events
			} else if (event instanceof VMStartEvent) {
				fTarget.handleVMStart((VMStartEvent)event);
			} else {
				// Unhandled Event
			}
		}
		
		if (resume) {
			// process deferred events if event handlers have voted
			// to resume the thread
			if (!getDeferredEvents().isEmpty()) {
				Iterator deferredIter= getDeferredEvents().iterator();
				while (deferredIter.hasNext()) {
					if (isShutdown()) {
						return;
					}
					Event event= (Event)deferredIter.next();
					if (event == null) {
						continue;
					}
					// Dispatch events to registered listeners, if any
					IJDIEventListener listener = (IJDIEventListener)fEventHandlers.get(event.request());
					if (listener != null) {
						vote = true;
						resume = listener.handleEvent(event, fTarget) && resume;
						continue;
					}
				}
			}
		}
		// clear any deferred events (processed or not)
		getDeferredEvents().clear();
		
		// let the winner know they won
		if (winner != null && voters > 1) {
			winner.wonSuspendVote(winningEvent, fTarget);
		}
		
		fireEvents();
		
		if (vote && resume) {
			try {
				eventSet.resume();
			} catch (VMDisconnectedException e) {
			} catch (RuntimeException e) {
				try {
					fTarget.targetRequestFailed(JDIDebugMessages.EventDispatcher_0, e); 
				} catch (DebugException de) {
					JDIDebugPlugin.log(de);
				}
			}
		}
	}

	/**
	 * Continuously reads events that are coming from the event queue,
	 * until this event dispatcher is shutdown. A debug target starts
	 * a thread on this method on startup.
	 * 
	 * @see #shutdown()
	 */
	public void run() {
		VirtualMachine vm = fTarget.getVM();
		if (vm != null) {
			EventQueue q= vm.eventQueue();
			EventSet eventSet= null;
			while (!isShutdown()) {
				try {
					try {
						// Get the next event set.
						eventSet= q.remove(1000);
					} catch (VMDisconnectedException e) {
						break;
					}
									
					if(!isShutdown() && eventSet != null) {
						dispatch(eventSet);
					}
				} catch (InterruptedException e) {
					break;
				}
			}
		}
	}

	/**
	 * Shutdown this event dispatcher - i.e. causes this event
	 * dispatcher to stop reading and dispatching events from the
	 * event queue.	The thread associated with this runnable
	 * will exit.
	 */
	public void shutdown() {
		fShutdown= true;
	}
	
	/**
	 * Returns whether this event dispatcher has been
	 * shutdown.
	 * 
	 * @return whether this event dispatcher has been
	 * shutdown
	 */
	protected boolean isShutdown() {
		return fShutdown;
	}
	
	/**
	 * Registers the given listener for with the given event request.
	 * When an event is received from the underlying VM, that is
	 * assocaited with the given event request, the listener will
	 * be notified.
	 * 
	 * @param listener the listener to register
	 * @param request the event request associated with events
	 * 	the listener is interested in
	 */
	public void addJDIEventListener(IJDIEventListener listener, EventRequest request) {
		fEventHandlers.put(request, listener);
	}

	/**
	 * Deregisters the given listener and event request. The listener
	 * will no longer be notified of events associated with the request.
	 * Listeners are responsible for deleting the assocaited event
	 * request if required.
	 * 
	 * @param listener the listener to deregister
	 * @param request the event request to deregister
	 */	
	public void removeJDIEventListener(IJDIEventListener listener, EventRequest request) {
		fEventHandlers.remove(request);
	}
	
	/** 
	 * Adds the given event to the queue of debug events to fire when done
	 * dispatching events from the current event set.
	 * 
	 * @param event the event to queue
	 */
	public void queue(DebugEvent event) {
		fDebugEvents.add(event);
	}
	
	/**
	 * Fires debug events in the event queue, and clears the queue
	 */
	protected void fireEvents() {
		DebugPlugin plugin= DebugPlugin.getDefault();
		if (plugin != null) { //check that not in the process of shutting down
			DebugEvent[] events = (DebugEvent[])fDebugEvents.toArray(new DebugEvent[fDebugEvents.size()]);
			fDebugEvents.clear();
			plugin.fireDebugEventSet(events);
		}
	}

	/**
	 * Defer the given event, to be handled after all other events in
	 * an event set.
	 * 
	 * @param event event to defer
	 */
	protected void defer(Event event) {
		fDeferredEvents.add(event);
	}

	/**
	 * Returns the events currently deferred.
	 * 
	 * @return deferred events
	 */
	protected List getDeferredEvents() {
		return fDeferredEvents;
	}
		
}

