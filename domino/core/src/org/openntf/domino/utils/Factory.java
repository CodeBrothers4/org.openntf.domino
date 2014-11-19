/*
 * Copyright 2013
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at:
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or 
 * implied. See the License for the specific language governing 
 * permissions and limitations under the License.
 */
package org.openntf.domino.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import lotus.domino.NotesException;
import lotus.notes.NotesThread;

import org.openntf.domino.Base;
import org.openntf.domino.Database;
import org.openntf.domino.DocumentCollection;
import org.openntf.domino.Session;
import org.openntf.domino.Session.RunContext;
import org.openntf.domino.WrapperFactory;
import org.openntf.domino.exceptions.DataNotCompatibleException;
import org.openntf.domino.exceptions.UndefinedDelegateTypeException;
import org.openntf.domino.graph.DominoGraph;
import org.openntf.domino.session.INamedSessionFactory;
import org.openntf.domino.session.ISessionFactory;
import org.openntf.domino.session.NamedSessionFactory;
import org.openntf.domino.session.NativeSessionFactory;
import org.openntf.domino.session.SessionFullAccessFactory;
import org.openntf.domino.session.TrustedSessionFactory;
import org.openntf.domino.thread.model.XotsSessionType;
import org.openntf.domino.thread.model.XotsTasklet;
import org.openntf.domino.types.FactorySchema;
import org.openntf.domino.types.SessionDescendant;
import org.openntf.service.IServiceLocator;
import org.openntf.service.ServiceLocatorFinder;

/**
 * The Enum Factory. Does the Mapping lotusObject <=> OpenNTF-Object
 */
public enum Factory {
	;

	/**
	 * An identifier for the different session types, the factory can create
	 * 
	 * @author Roland Praml, FOCONIS AG
	 * 
	 */
	public enum SessionType {
		/**
		 * The current session. This means:
		 * 
		 * <ul>
		 * <li>The current XPage session, if you are IN a XPage-Thread. This is equivalent to the "session" SSJS variable</li>
		 * <li>The current XOTS session, if you are IN a XOTS-Thread. <br>
		 * This is either the session that {@link XotsTasklet.Interface#getSessionFactory()} can create (if the Runnable implements that
		 * interface and provide a Factory)<br>
		 * or the session, you specified for that runnable with the {@link XotsTasklet#session()} annotation. See {@link XotsSessionType}
		 * for available types.
		 * </ul>
		 * <b>The Method will fail, if you are running in a wrongly set up Thread</b><br>
		 * (But there should be only XPage-Threads or XOTS-Threads. TODO RPr: and maybe DominoTheads)
		 */
		CURRENT(0, "CURRENT"),

		/**
		 * Returns a session with full access. This is a named session (name is equal to {@link #CURRENT}s session name) but with full
		 * access rights. {@link #FULL_ACCESS} may provide the same session as {@link #CURRENT} if the Runnable was annotated with a
		 * *_FULL_ACCESS {@link XotsSessionType}
		 */
		CURRENT_FULL_ACCESS(1, "CURRENT_FULL_ACCESS"),

		/**
		 * Returns a named session as signer. The code-signer is either the server (if the runnable is not inside an NSF) or the signer of
		 * that runnable. <br>
		 * <b>Note 1:</b> This session becomes invalid, if you the classloader gets tainted by loading classes that are signed by different
		 * users! <br/>
		 * <b>Note 2:</b> Due a bug, we return always SessionAsSigner (@see http://www.mydominolab.com/2011/10/xpages-sessionassigner.html)
		 */
		SIGNER(2, "SIGNER"),

		/**
		 * This is currently the SAME session as {@link #SIGNER} due a Bug in XPages.
		 */
		SIGNER_FULL_ACCESS(3, "SIGNER_FULL_ACCESS"),

		/**
		 * Returns a NATIVE session
		 */
		NATIVE(4, "NATIVE"),

		/**
		 * Returns a TRUSTED session (This does not yet work!)
		 */
		TRUSTED(5, "TRUSTED"),

		/**
		 * Returns a Session with full access.
		 */
		FULL_ACCESS(6, "FULL_ACCESS");

		static int SIZE = 7;
		int index;
		String alias;

		SessionType(final int index, final String alias) {
			this.index = index;
			this.alias = alias;
		}
	}

	/**
	 * Container Class for all statistic counters
	 * 
	 * @author Roland Praml, FOCONIS AG
	 * 
	 */
	private static class Counters {

		/** The lotus counter. */
		private final Counter lotus;

		/** The recycle err counter. */
		private final Counter recycleErr;

		/** The auto recycle counter. */
		private final Counter autoRecycle;

		/** The manual recycle counter. */
		private final Counter manualRecycle;

		private boolean countPerThread_;

		private Map<Class<?>, Counter> classes;

		/**
		 * Returns a counter for a certain class
		 * 
		 * @param clazz
		 *            the class
		 * @return a counter for the class
		 */
		public Counter forClass(final Class<?> clazz) {
			Counter ret = classes.get(clazz);
			if (ret == null) {
				ret = new Counter(countPerThread_);
				classes.put(clazz, ret);
			}
			return ret;
		}

		Counters(final boolean countPerThread) {
			countPerThread_ = countPerThread;
			lotus = new Counter(countPerThread);
			recycleErr = new Counter(countPerThread);
			autoRecycle = new Counter(countPerThread);
			manualRecycle = new Counter(countPerThread);
			classes = new ConcurrentHashMap<Class<?>, Counter>();
		}
	}

	/**
	 * We have so many threadLocals here, so that it is worth to handle them all in a container class.
	 * 
	 * @author Roland Praml, FOCONIS AG
	 * 
	 */
	private static class ThreadVariables {
		private WrapperFactory wrapperFactory;

		private ClassLoader classLoader;

		private IServiceLocator serviceLocator;

		/**
		 * Support for different Locale
		 */
		private Locale userLocale;

		/** the factories can create a new session */
		public ISessionFactory[] sessionFactories = new ISessionFactory[SessionType.SIZE];

		/** the sessions are stored in the sessionHolder */
		private Session[] sessionHolders = new Session[SessionType.SIZE];

		public INamedSessionFactory namedSessionFactory;
		public INamedSessionFactory namedSessionFullAccessFactory;

		/** These sessions will be recycled at the end of that thread. Key = UserName of session */
		public Map<String, Session> ownSessions = new HashMap<String, Session>();

		/** clear the object */
		private void clear() {
			wrapperFactory = null;
			classLoader = null;
			serviceLocator = null;
			for (int i = 0; i < SessionType.SIZE; i++) {
				sessionHolders[i] = null;
				sessionFactories[i] = null;
			}
			userLocale = null;
			namedSessionFactory = null;
			namedSessionFullAccessFactory = null;
			terminateHooks.clear();
		}
	}

	private static ISessionFactory[] defaultSessionFactories = new ISessionFactory[SessionType.SIZE];
	private static INamedSessionFactory defaultNamedSessionFactory;
	private static INamedSessionFactory defaultNamedSessionFullAccessFactory;

	/**
	 * Holder for variables that are different per thread
	 */
	private static ThreadLocal<ThreadVariables> threadVariables_ = new ThreadLocal<ThreadVariables>();

	private static List<Runnable> terminateHooks = new ArrayList<Runnable>();
	private static List<Runnable> shutdownHooks = new ArrayList<Runnable>();

	private static String localServerName;

	private static ThreadVariables getThreadVariables() {
		ThreadVariables tv = threadVariables_.get();
		if (tv == null)
			throw new IllegalStateException(Factory.class.getName() + " is not initialized for this thread!");
		return tv;
	}

	// RPr: I have objections in this type of setup (locating the correct notes.ini is NOT trivial)
	//	/**
	//	 * setup the environment and loggers
	//	 * 
	//	 * @author praml
	//	 * 
	//	 */
	//	private static class SetupJob implements Runnable {
	//		@Override
	//		public void run() {
	//			try {
	//				AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
	//					@Override
	//					public Object run() throws Exception {
	//						// Windows stores the notes.ini in the program directory; Linux stores it in the data directory
	//						String progpath = System.getProperty("notes.binary");
	//						File iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");
	//						if (!iniFile.exists()) {
	//							//							System.out.println("Inifile not found on notes.binary path: " + progpath);
	//							progpath = System.getProperty("user.dir");
	//							iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");
	//						}
	//						if (!iniFile.exists()) {
	//							//							System.out.println("Inifile not found on notes.binary path: " + progpath);
	//							progpath = System.getProperty("java.home");
	//							if (progpath.endsWith("jvm")) {
	//								iniFile = new File(progpath + System.getProperty("file.separator") + ".."
	//										+ System.getProperty("file.separator") + "notes.ini");
	//							} else {
	//								iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");
	//
	//							}
	//						}
	//						if (!iniFile.exists()) {
	//							progpath = System.getProperty("java.library.path"); // Otherwise the tests will not work
	//							iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");
	//						}
	//						if (!iniFile.exists()) {
	//							//							System.out.println("Inifile still not found on user.dir path: " + progpath);
	//							if (progpath.contains("framework")) {
	//								String pp2 = progpath.replace("framework", "");
	//								iniFile = new File(pp2 + "notes.ini");
	//								//								System.out.println("Attempting to use path: " + pp2);
	//								if (!iniFile.exists()) {
	//									System.out
	//											.println("WARNING: Unable to read environment for log setup. Please look at the following properties...");
	//									for (Object rawName : System.getProperties().keySet()) {
	//										if (rawName instanceof String) {
	//											System.out.println((String) rawName + " = " + System.getProperty((String) rawName));
	//										}
	//									}
	//								}
	//							}
	//						}
	//
	//						Scanner scanner = new Scanner(iniFile);
	//						scanner.useDelimiter("\n|\r\n");
	//						loadEnvironment(scanner);
	//						scanner.close();
	//						return null;
	//					}
	//				});
	//			} catch (AccessControlException e) {
	//				e.printStackTrace();
	//			} catch (PrivilegedActionException e) {
	//				e.printStackTrace();
	//			}
	//
	//			try {
	//				AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
	//					@Override
	//					public Object run() throws Exception {
	//						Logging.getInstance().startUp();
	//						return null;
	//					}
	//				});
	//			} catch (AccessControlException e) {
	//				e.printStackTrace();
	//			} catch (PrivilegedActionException e) {
	//				e.printStackTrace();
	//			}
	//		}
	//	}
	//
	//	static {
	//		SetupJob job = new SetupJob();
	//		job.run();
	//		//		TrustedDispatcher td = new TrustedDispatcher();
	//		//		td.process(job);
	//		//		System.out.println("DEBUG: SetupJob dispatched");
	//		//		td.stop(false);
	//	}

	private static Map<String, String> ENVIRONMENT;
	@SuppressWarnings("unused")
	//private static boolean session_init = false;
	//private static boolean jar_init = false;
	private static boolean started = false;

	/**
	 * load the configuration
	 * 
	 */
	private static void loadEnvironment(final Scanner scanner) {
		if (ENVIRONMENT == null) {
			ENVIRONMENT = new HashMap<String, String>();
		}
		if (scanner != null) {
			while (scanner.hasNextLine()) {
				String nextLine = scanner.nextLine();
				int i = nextLine.indexOf('=');
				if (i > 0) {
					String key = nextLine.substring(0, i).toLowerCase();
					String value = nextLine.substring(i + 1);
					//					System.out.println("DEBUG " + key + " : " + value);
					ENVIRONMENT.put(key, value);
				}
			}
			//			System.out.println("DEBUG: Added " + keyCount + " environment variables to avoid using a session");
		}
		try {
			AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
				@Override
				public Object run() throws Exception {
					try {
						ClassLoader cl = Factory.class.getClassLoader();
						// we MUST use the Factory-classloader to find the correct MANIFEST
						Enumeration<URL> resources = cl.getResources("META-INF/MANIFEST.MF");
						while (resources.hasMoreElements()) {

							Manifest manifest = new Manifest(resources.nextElement().openStream());
							// check that this is your manifest and do what you need or get the next one
							Attributes attrib = manifest.getMainAttributes();

							String bundleName = attrib.getValue("Bundle-SymbolicName");
							if (bundleName != null) {
								int pos;
								if ((pos = bundleName.indexOf(';')) != -1) {
									bundleName = bundleName.substring(0, pos);
								}
								if ("org.openntf.domino".equals(bundleName)) {
									ENVIRONMENT.put("version", attrib.getValue("Bundle-Version"));
									ENVIRONMENT.put("title", attrib.getValue("Implementation-Title"));
									ENVIRONMENT.put("url", attrib.getValue("Implementation-Vendor-URL"));
									return null;
								}
							}

						}

					} catch (Exception e) {
						e.printStackTrace();
					}
					return null;
				}
			});
		} catch (AccessControlException e) {
			e.printStackTrace();
		} catch (PrivilegedActionException e) {
			e.printStackTrace();
		}
		if (!ENVIRONMENT.containsKey("version")) {
			ENVIRONMENT.put("version", "0.0.0.unknown");
		}

	}

	public static String getEnvironment(final String key) {
		if (ENVIRONMENT == null) {
			loadEnvironment(null);
		}
		return ENVIRONMENT.get(key);
	}

	public static String getTitle() {
		return getEnvironment("title");
	}

	public static String getUrl() {
		return getEnvironment("url");
	}

	public static String getVersion() {
		return getEnvironment("version");
	}

	public static String getDataPath() {
		return getEnvironment("directory");
	}

	public static String getProgramPath() {
		return getEnvironment("notesprogram");
	}

	public static String getHTTPJVMHeapSize() {
		return getEnvironment("httpjvmheapsize");
	}

	/** The Constant log_. */
	private static final Logger log_ = Logger.getLogger(Factory.class.getName());

	/** The lotus counter. */
	private static Counters counters;

	public static void enableCounters(final boolean enable, final boolean perThread) {
		if (enable) {
			counters = new Counters(perThread);
		} else {
			counters = null;
		}
	}

	/**
	 * Gets the lotus count.
	 * 
	 * @return the lotus count
	 */
	public static int getLotusCount() {
		return counters == null ? 0 : counters.lotus.intValue();
	}

	/**
	 * Count a created lotus element.
	 */
	public static void countLotus(final Class<?> c) {
		if (counters != null) {
			counters.lotus.increment();
			counters.forClass(c).increment();
		}
	}

	/**
	 * Gets the recycle error count.
	 * 
	 * @return the recycle error count
	 */
	public static int getRecycleErrorCount() {
		return counters == null ? 0 : counters.recycleErr.intValue();
	}

	/**
	 * Count recycle error.
	 */
	public static void countRecycleError(final Class<?> c) {
		if (counters != null)
			counters.recycleErr.increment();
	}

	/**
	 * Gets the auto recycle count.
	 * 
	 * @return the auto recycle count
	 */
	public static int getAutoRecycleCount() {
		return counters == null ? 0 : counters.autoRecycle.intValue();
	}

	/**
	 * Count auto recycle.
	 * 
	 * @return the int
	 */
	public static int countAutoRecycle(final Class<?> c) {
		if (counters != null) {
			counters.forClass(c).decrement();
			return counters.autoRecycle.increment();
		} else {
			return 0;
		}
	}

	/**
	 * Gets the manual recycle count.
	 * 
	 * @return the manual recycle count
	 */
	public static int getManualRecycleCount() {
		return counters == null ? 0 : counters.manualRecycle.intValue();
	}

	/**
	 * Count a manual recycle
	 */
	public static int countManualRecycle(final Class<?> c) {
		if (counters != null) {
			counters.forClass(c).decrement();
			return counters.manualRecycle.increment();
		} else {
			return 0;
		}
	}

	/**
	 * get the active object count
	 * 
	 * @return The current active object count
	 */
	public static int getActiveObjectCount() {
		if (counters != null) {
			return counters.lotus.intValue() - counters.autoRecycle.intValue() - counters.manualRecycle.intValue();
		} else {
			return 0;
		}
	}

	/**
	 * Determine the run context where we are
	 * 
	 * @return The active RunContext
	 */
	public static RunContext getRunContext() {
		// TODO finish this implementation, which needs a lot of work.
		// - ADDIN
		// - APPLET
		// - DIIOP
		// - DOTS
		// - PLUGIN
		// - SERVLET
		// - XPAGES_NSF
		// maybe a simple way to determine => create a Throwable and look into the stack trace
		RunContext result = RunContext.UNKNOWN;
		SecurityManager sm = System.getSecurityManager();
		if (sm == null)
			return RunContext.CLI;

		Object o = sm.getSecurityContext();
		if (log_.isLoggable(Level.INFO))
			log_.log(Level.INFO, "SecurityManager is " + sm.getClass().getName() + " and context is " + o.getClass().getName());
		if (sm instanceof lotus.notes.AgentSecurityManager) {
			lotus.notes.AgentSecurityManager asm = (lotus.notes.AgentSecurityManager) sm;
			Object xsm = asm.getExtenderSecurityContext();
			if (xsm instanceof lotus.notes.AgentSecurityContext) {
			}
			Object asc = asm.getSecurityContext();
			if (asc != null) {
				// System.out.println("Security context is " + asc.getClass().getName());
			}
			// ThreadGroup tg = asm.getThreadGroup();
			// System.out.println("ThreadGroup name: " + tg.getName());

			result = RunContext.AGENT;
		}
		//		com.ibm.domino.http.bootstrap.logger.RCPLoggerConfig rcplc;
		try {
			Class<?> BCLClass = Class.forName("com.ibm.domino.http.bootstrap.BootstrapClassLoader");
			if (BCLClass != null) {
				ClassLoader cl = (ClassLoader) BCLClass.getMethod("getSharedClassLoader", null).invoke(null, null);
				if ("com.ibm.domino.http.bootstrap.BootstrapOSGIClassLoader".equals(cl.getClass().getName())) {
					result = RunContext.XPAGES_OSGI;
				}
			}
		} catch (Exception e) {

		}

		return result;
	}

	/**
	 * returns the wrapper factory for this thread
	 * 
	 * @return the thread's wrapper factory
	 */
	public static WrapperFactory getWrapperFactory() {
		ThreadVariables tv = getThreadVariables();
		WrapperFactory wf = tv.wrapperFactory;
		if (wf == null) {
			try {
				List<WrapperFactory> wfList = findApplicationServices(WrapperFactory.class);
				wf = wfList.size() > 0 ? wfList.get(0) : new org.openntf.domino.impl.WrapperFactory();
			} catch (Throwable t) {
				t.printStackTrace();
				wf = new org.openntf.domino.impl.WrapperFactory();
			}
			tv.wrapperFactory = wf;
		}
		return wf;
	}

	/**
	 * Returns the wrapper factory if initialized
	 * 
	 * @return The active WrapperFactory
	 */
	public static WrapperFactory getWrapperFactory_unchecked() {
		ThreadVariables tv = threadVariables_.get();
		return tv == null ? null : threadVariables_.get().wrapperFactory;
	}

	// RPr: A setter is normally not needed. The wrapperFactory should be configure with an application service!
	//	/**
	//	 * Set/changes the wrapperFactory for this thread
	//	 * 
	//	 * @param wf
	//	 *            The new WrapperFactory
	//	 */
	//	public static void setWrapperFactory(final WrapperFactory wf) {
	//		currentWrapperFactory.set(wf);
	//	}

	// --- session handling 

	//	@SuppressWarnings("rawtypes")
	//	@Deprecated
	//	public static org.openntf.domino.Document fromLotusDocument(final lotus.domino.Document lotus, final Base parent) {
	//		return getWrapperFactory().fromLotus(lotus, Document.SCHEMA, (Database) parent);
	//	}

	//This should not be needed any more
	//public static void setNoRecycle(final Base<?> base, final boolean value) {
	//	getWrapperFactory().setNoRecycle(base, value);
	//}

	/*
	 * (non-JavaDoc)
	 * 
	 * @see org.openntf.domino.WrapperFactory#fromLotus(lotus.domino.Base, FactorySchema, Base)
	 */
	@SuppressWarnings("rawtypes")
	public static <T extends Base, D extends lotus.domino.Base, P extends Base> T fromLotus(final D lotus,
			final FactorySchema<T, D, P> schema, final P parent) {
		T result = getWrapperFactory().fromLotus(lotus, schema, parent);

		//		if (result instanceof org.openntf.domino.Session) {
		//			ThreadVariables tv = getThreadVariables();
		//			org.openntf.domino.Session check = tv.sessionHolders[SessionType.CURRENT.index];
		//			if (check == null) {
		//				// TODO RPr: I have really objections to this.
		//				// Setting the first session as default session is NOT nice
		//				log_.log(Level.WARNING, "WARNING! Setting the Session " + result
		//						+ " as CURRENT session. This means you run in a wrong initialized thread", new Throwable());
		//				setSession((org.openntf.domino.Session) result, SessionType.CURRENT);
		//			}
		//		}
		return result;
	}

	// RPr: Should be done directly to current wrapperFactory
	//	public static boolean recacheLotus(final lotus.domino.Base lotus, final Base<?> wrapper, final Base<?> parent) {
	//		return getWrapperFactory().recacheLotusObject(lotus, wrapper, parent);
	//	}

	/**
	 * From lotus wraps a given lotus collection in an org.openntf.domino collection
	 * 
	 * @param <T>
	 *            the generic org.openntf.domino type (drapper)
	 * @param <D>
	 *            the generic lotus.domino type (delegate)
	 * @param <P>
	 *            the generic org.openntf.domino type (parent)
	 * @param lotus
	 *            the object to wrap
	 * @param schema
	 *            the generic schema to ensure type safeness (may be null)
	 * @param parent
	 *            the parent
	 * @return the wrapped object
	 */
	@SuppressWarnings({ "rawtypes" })
	public static <T extends Base, D extends lotus.domino.Base, P extends Base> Collection<T> fromLotus(final Collection<?> lotusColl,
			final FactorySchema<T, D, P> schema, final P parent) {
		return getWrapperFactory().fromLotus(lotusColl, schema, parent);
	}

	/**
	 * From lotus wraps a given lotus collection in an org.openntf.domino collection
	 * 
	 * @param <T>
	 *            the generic org.openntf.domino type (wrapper)
	 * @param <D>
	 *            the generic lotus.domino type (delegate)
	 * @param <P>
	 *            the generic org.openntf.domino type (parent)
	 * @param lotus
	 *            the object to wrap
	 * @param schema
	 *            the generic schema to ensure type safeness (may be null)
	 * @param parent
	 *            the parent
	 * @return the wrapped object
	 */
	@SuppressWarnings("rawtypes")
	public static <T extends Base, D extends lotus.domino.Base, P extends Base> Vector<T> fromLotusAsVector(final Collection<?> lotusColl,
			final FactorySchema<T, D, P> schema, final P parent) {
		return getWrapperFactory().fromLotusAsVector(lotusColl, schema, parent);
	}

	// RPr: Deprecated, so I commented this out
	//	/**
	//	 * From lotus.
	//	 * 
	//	 * @deprecated Use {@link #fromLotus(lotus.domino.Base, FactorySchema, Base)} instead
	//	 * 
	//	 * 
	//	 * @param <T>
	//	 *            the generic type
	//	 * @param lotus
	//	 *            the lotus
	//	 * @param T
	//	 *            the t
	//	 * @param parent
	//	 *            the parent
	//	 * @return the t
	//	 */
	//	@SuppressWarnings({ "rawtypes", "unchecked" })
	//	@Deprecated
	//	public static <T> T fromLotus(final lotus.domino.Base lotus, final Class<? extends Base> T, final Base parent) {
	//		return (T) getWrapperFactory().fromLotus(lotus, (FactorySchema) null, parent);
	//	}
	//
	//	/**
	//	 * From lotus.
	//	 * 
	//	 * @deprecated Use {@link #fromLotus(Collection, FactorySchema, Base)} instead
	//	 * 
	//	 * @param <T>
	//	 *            the generic type
	//	 * @param lotusColl
	//	 *            the lotus coll
	//	 * @param T
	//	 *            the t
	//	 * @param parent
	//	 *            the parent
	//	 * @return the collection
	//	 */
	//	@SuppressWarnings({ "unchecked", "rawtypes" })
	//	@Deprecated
	//	public static <T> Collection<T> fromLotus(final Collection<?> lotusColl, final Class<? extends Base> T, final Base<?> parent) {
	//		return getWrapperFactory().fromLotus(lotusColl, (FactorySchema) null, parent);
	//	}
	//
	//	/**
	//	 * @deprecated Use {@link #fromLotusAsVector(Collection, FactorySchema, Base)}
	//	 */
	//	@Deprecated
	//	@SuppressWarnings({ "unchecked", "rawtypes" })
	//	public static <T> Vector<T> fromLotusAsVector(final Collection<?> lotusColl, final Class<? extends org.openntf.domino.Base> T,
	//			final org.openntf.domino.Base<?> parent) {
	//		return getWrapperFactory().fromLotusAsVector(lotusColl, (FactorySchema) null, parent);
	//	}

	/**
	 * Wrap column values.
	 * 
	 * @param values
	 *            the values
	 * @return the java.util. vector
	 */
	public static java.util.Vector<Object> wrapColumnValues(final Collection<?> values, final org.openntf.domino.Session session) {
		if (values == null) {
			log_.log(Level.WARNING, "Request to wrapColumnValues for a collection of null");
			return null;
		}
		return getWrapperFactory().wrapColumnValues(values, session);
	}

	/**
	 * Method to unwrap a object
	 * 
	 * @param the
	 *            object to unwrap
	 * @return the unwrapped object
	 */
	public static <T extends lotus.domino.Base> T toLotus(final T base) {
		return getWrapperFactory().toLotus(base);
	}

	/**
	 * Gets the session.
	 * 
	 * @return the session
	 */
	@Deprecated
	public static org.openntf.domino.Session getSession() {
		return getSession(SessionType.CURRENT);
	}

	/**
	 * Gets the session full access.
	 * 
	 * @return the session full access
	 */
	@Deprecated
	public static org.openntf.domino.Session getSessionFullAccess() {
		return getSession(SessionType.FULL_ACCESS);
	}

	/**
	 * Gets the trusted session.
	 * 
	 * @return the trusted session
	 */
	@Deprecated
	public static org.openntf.domino.Session getTrustedSession() {
		return getSession(SessionType.TRUSTED);
	}

	/**
	 * Gets the trusted session.
	 * 
	 * @return the trusted session
	 */
	@Deprecated
	public static org.openntf.domino.Session getSessionAsSigner() {
		return getSession(SessionType.SIGNER);
	}

	/**
	 * 
	 * @param mode
	 * @return
	 */
	public static org.openntf.domino.Session getSession(final SessionType mode) {
		ThreadVariables tv = getThreadVariables();
		org.openntf.domino.Session result = tv.sessionHolders[mode.index];
		if (result == null) {
			//			System.out.println("TEMP DEBUG: No session found of type " + mode.name() + " in thread "
			//					+ System.identityHashCode(Thread.currentThread()) + " from TV " + System.identityHashCode(tv));

			try {
				ISessionFactory sf = getSessionFactory(mode);
				if (sf != null) {
					result = sf.createSession();
					tv.sessionHolders[mode.index] = result;
					// Per default. Session objects are not recycled by the ODA and thats OK so.
					// this is our own session which will be recycled in terminate
					tv.ownSessions.put(mode.alias, result);
					//					System.out.println("TEMP DEBUG: Created new session " + System.identityHashCode(result) + " of type " + mode.name()
					//							+ " in thread " + System.identityHashCode(Thread.currentThread()) + " from TV " + System.identityHashCode(tv));
				}
			} catch (PrivilegedActionException ne) {
				log_.log(Level.SEVERE, "Unable to get the session of type " + mode.alias
						+ ". This probably means that you are running in an unsupported configuration "
						+ "or you forgot to set up your context at the start of the operation. "
						+ "If you're running in XPages, check the xsp.properties of your database. "
						+ "If you are running in an Agent, make sure you start with a call to "
						+ "Factory.setSession() and pass in your lotus.domino.Session", ne);
			}
		} else {
			//			System.out.println("TEMP DEBUG: Found an existing session " + System.identityHashCode(result) + " of type " + mode.name()
			//					+ " in thread " + System.identityHashCode(Thread.currentThread()) + " from TV " + System.identityHashCode(tv));
		}
		return result;
	}

	/**
	 * Returns the current session, if available. Does never create a session
	 * 
	 * @return the session
	 */
	public static org.openntf.domino.Session getSession_unchecked(final SessionType type) {
		ThreadVariables tv = threadVariables_.get();
		return tv == null ? null : tv.sessionHolders[type.index];
	}

	/**
	 * Sets the session for a certain sessionMode
	 * 
	 * @param session
	 * @param mode
	 */
	//	public static void setSession(final lotus.domino.Session session, final SessionType mode) {
	//		if (session instanceof org.openntf.domino.Session) {
	//			getThreadVariables().sessionHolders[mode.index] = (org.openntf.domino.Session) session;
	//			//			throw new UnsupportedOperationException("You should not set an org.openntf.domino.session as Session");
	//		} else {
	//			getThreadVariables().sessionHolders[mode.index] = fromLotus(session, Session.SCHEMA, null);
	//		}
	//	}

	public static void setSessionFactory(final ISessionFactory sessionFactory, final SessionType mode) {
		getThreadVariables().sessionFactories[mode.index] = sessionFactory;
	}

	public static ISessionFactory getSessionFactory(final SessionType mode) {
		ThreadVariables tv = threadVariables_.get();
		if (tv == null || tv.sessionFactories[mode.index] == null) {
			return defaultSessionFactories[mode.index];
		}
		return tv.sessionFactories[mode.index];
	}

	/**
	 * // * Sets the current session // * // * @param session // * the lotus session //
	 */
	//	public static void setSession(final lotus.domino.Session session) {
	//		setSession(session, SessionType.DEFAULT);
	//	}
	//
	//	/**
	//	 * Sets the current trusted session
	//	 * 
	//	 * @param session
	//	 *            the lotus session
	//	 */
	//	public static void setTrustedSession(final lotus.domino.Session session) {
	//		setSession(session, SessionType.TRUSTED);
	//	}
	//
	//	/**
	//	 * Sets the current session with full access
	//	 * 
	//	 * @param session
	//	 *            the lotus session
	//	 */
	//	public static void setSessionFullAccess(final lotus.domino.Session session) {
	//		setSession(session, SessionType.FULL_ACCESS);
	//	}

	//	/**
	//	 * clears the current session
	//	 */
	//	public static void clearSession() {
	//		threadVariables.get().sessionHolder = null;
	//	}

	// TODO: Determine if this is the right way to deal with Xots access to faces contexts

	// RPr: use getSession_unchecked().getCurrentDatabase
	//	/**
	//	 * Returns the session's current database if available. Does never create a session.
	//	 * 
	//	 * @see #getSession_unchecked()
	//	 * @return The session's current database
	//	 */
	//	public static Database getDatabase_unchecked() {
	//		Session sess = getSession_unchecked(SessionType.CURRENT);
	//		return (sess == null) ? null : sess.getCurrentDatabase();
	//	}

	// RPr: I think it is a better idea to set the currentDatabase on the currentSesssion

	// TODO remove that code
	//	public static void setDatabase(final Database database) {
	//		setNoRecycle(database, true);
	//		currentDatabaseHolder_.set(database);
	//	}
	//
	//	public static void clearDatabase() {
	//		currentDatabaseHolder_.set(null);
	//	}

	public static ClassLoader getClassLoader() {
		ThreadVariables tv = getThreadVariables();
		if (tv.classLoader == null) {
			ClassLoader loader = null;
			try {
				loader = AccessController.doPrivileged(new PrivilegedExceptionAction<ClassLoader>() {
					@Override
					public ClassLoader run() throws Exception {
						return Thread.currentThread().getContextClassLoader();
					}
				});
			} catch (AccessControlException e) {
				e.printStackTrace();
			} catch (PrivilegedActionException e) {
				e.printStackTrace();
			}
			setClassLoader(loader);
		}
		return tv.classLoader;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static <T> List<T> findApplicationServices(final Class<T> serviceClazz) {

		ThreadVariables tv = getThreadVariables();

		if (tv.serviceLocator == null) {
			tv.serviceLocator = ServiceLocatorFinder.findServiceLocator();
		}
		if (tv.serviceLocator == null) {
			throw new IllegalStateException("No service locator available so we cannot find the application services for "
					+ serviceClazz.getName());
		}

		return tv.serviceLocator.findApplicationServices(serviceClazz);
	}

	public static void setClassLoader(final ClassLoader loader) {
		getThreadVariables().classLoader = loader;
	}

	// avoid clear methods
	//	public static void clearWrapperFactory() {
	//		currentWrapperFactory.remove();
	//	}
	//
	//	public static void clearClassLoader() {
	//		currentClassLoader_.remove();
	//	}
	//
	//	public static void clearServiceLocator() {
	//		currentServiceLocator_.remove();
	//	}
	//
	//	public static void clearDominoGraph() {
	//		DominoGraph.clearDocumentCache();
	//	}
	//
	//	public static void clearNoteCoordinateBuffer() {
	//		NoteCoordinate.clearLocals();
	//	}
	//
	//	public static void clearBubbleExceptions() {
	//		DominoUtils.setBubbleExceptions(null);
	//	}

	/**
	 * Begin with a clear environment. Initialize this thread
	 * 
	 */
	public static void initThread() { // RPr: Method was deliberately renamed
		if (!started) {
			throw new IllegalStateException("Factory is not yet statetd");
		}
		if (log_.isLoggable(Level.FINER)) {
			log_.log(Level.FINER, "Factory.initThread()", new Throwable());
		}
		if (threadVariables_.get() != null) {
			log_.log(Level.SEVERE, "WARNING - Thread " + Thread.currentThread().getName()
					+ " was not correctly terminated or initialized twice", new Throwable());
		}
		//		System.out.println("TEMP DEBUG: Factory thread initializing.");
		//		Throwable t = new Throwable();
		//		t.printStackTrace();
		threadVariables_.set(new ThreadVariables());
	}

	/**
	 * terminate the current thread.
	 */
	@SuppressWarnings("deprecation")
	public static void termThread() { // RPr: Method was deliberately renamed
		if (log_.isLoggable(Level.FINER)) {
			log_.log(Level.FINER, "Factory.termThread()", new Throwable());
		}
		ThreadVariables tv = threadVariables_.get();
		if (tv == null) {
			log_.log(Level.SEVERE, "WARNING - Thread " + Thread.currentThread().getName()
					+ " was not correctly initalized or terminated twice", new Throwable());
			return;
		}
		//		System.out.println("TEMP DEBUG: Factory thread terminating.");
		//		Throwable trace = new Throwable();
		//		trace.printStackTrace();
		try {

			for (Runnable term : terminateHooks) {
				term.run();
			}
			if (tv.wrapperFactory != null) {
				tv.wrapperFactory.terminate();
			}
			//		System.out.println("DEBUG: cleared " + termCount + " references from the queue...");
			DominoUtils.setBubbleExceptions(null);
			DominoGraph.clearDocumentCache();
			// The last step is to recycle ALL own sessions
			for (Session sess : tv.ownSessions.values()) {
				if (sess != null) {
					sess.recycle();
				}
			}
		} catch (Throwable t) {
			log_.log(Level.SEVERE, "An error occured while terminating the factory", t);
		} finally {
			tv.clear();
			threadVariables_.set(null);
			System.gc();
		}
		if (counters != null) {
			System.out.println(dumpCounters(true));
		}
	}

	private static File getConfigFileFallback() {
		String progpath = System.getProperty("notes.binary");
		File iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");
		if (!iniFile.exists()) {
			//							System.out.println("Inifile not found on notes.binary path: " + progpath);
			progpath = System.getProperty("user.dir");
			iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");
		}
		if (!iniFile.exists()) {
			//							System.out.println("Inifile not found on notes.binary path: " + progpath);
			progpath = System.getProperty("java.home");
			if (progpath.endsWith("jvm")) {
				iniFile = new File(progpath + System.getProperty("file.separator") + ".." + System.getProperty("file.separator")
						+ "notes.ini");
			} else {
				iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");

			}
		}
		if (!iniFile.exists()) {
			progpath = System.getProperty("java.library.path"); // Otherwise the tests will not work
			iniFile = new File(progpath + System.getProperty("file.separator") + "notes.ini");
		}
		if (!iniFile.exists()) {
			//							System.out.println("Inifile still not found on user.dir path: " + progpath);
			if (progpath.contains("framework")) {
				String pp2 = progpath.replace("framework", "");
				iniFile = new File(pp2 + "notes.ini");
				//								System.out.println("Attempting to use path: " + pp2);
				if (!iniFile.exists()) {
					System.out.println("WARNING: Unable to read environment for log setup. Please look at the following properties...");
					for (Object rawName : System.getProperties().keySet()) {
						if (rawName instanceof String) {
							System.out.println((String) rawName + " = " + System.getProperty((String) rawName));
						}
					}
				}
			}
		}
		return iniFile;
	}

	public static void startup() {

		synchronized (Factory.class) {

			NotesThread.sinitThread();
			try {
				lotus.domino.Session sess = lotus.domino.NotesFactory.createSession();
				try {
					startup(sess);
				} finally {
					sess.recycle();
				}
			} catch (NotesException e) {
				e.printStackTrace();
			} finally {
				NotesThread.stermThread();
			}
		}
	}

	public static synchronized void startup(final lotus.domino.Session session) {
		if (session instanceof org.openntf.domino.Session) {
			throw new UnsupportedOperationException("Initialization must be done on the raw session! How did you get that session?");
		}
		if (started) {
			System.out.println("OpenNTF Domino API is already started. Cannot start it again");
		}

		File iniFile;
		try {
			localServerName = session.getUserName();
			iniFile = new File(session.evaluate("@ConfigFile").get(0).toString());
		} catch (NotesException e) {
			System.out.println("WARNING: @ConfigFile returned " + e.getMessage() + " Using fallback to locate notes.ini");
			iniFile = getConfigFileFallback();
		}

		System.out.println("Starting the OpenNTF Domino API... Using notes.ini: " + iniFile);

		try {
			Scanner scanner = new Scanner(iniFile);
			scanner.useDelimiter("\n|\r\n");
			loadEnvironment(scanner);
			scanner.close();
		} catch (FileNotFoundException e) {
			System.out.println("Cannot read notes.ini. Giving up");
			e.printStackTrace();
		}

		// There is NO(!) Default SessionFactory for the current session. you have to set it!
		defaultSessionFactories[SessionType.CURRENT.index] = null;

		// For CURRENT_FULL_ACCESS, we return a named session with full access = true
		defaultSessionFactories[SessionType.CURRENT_FULL_ACCESS.index] = new ISessionFactory() {
			private static final long serialVersionUID = 1L;

			private String getName() {
				return Factory.getSession(SessionType.CURRENT).getEffectiveUserName();
			}

			@Override
			public Session createSession() throws PrivilegedActionException {
				return Factory.getNamedSession(getName(), true);
			}
		};

		// In XPages environment, this factory will not be used!
		defaultSessionFactories[SessionType.SIGNER.index] = new NativeSessionFactory();

		// In XPages environment, this factory will not be used!
		defaultSessionFactories[SessionType.SIGNER_FULL_ACCESS.index] = new SessionFullAccessFactory();

		// This will ALWAYS return the native/trusted/full access session (not overridden in XPages)
		defaultSessionFactories[SessionType.NATIVE.index] = new NativeSessionFactory();
		defaultSessionFactories[SessionType.TRUSTED.index] = new TrustedSessionFactory();
		defaultSessionFactories[SessionType.FULL_ACCESS.index] = new SessionFullAccessFactory();

		defaultNamedSessionFactory = new NamedSessionFactory((String) null);
		defaultNamedSessionFullAccessFactory = new SessionFullAccessFactory();

		started = true;
		System.out.println("OpenNTF API Version " + ENVIRONMENT.get("version") + " started");
	}

	public static void setNamedFactories4XPages(final INamedSessionFactory normal, final INamedSessionFactory fullaccess) {
		defaultNamedSessionFactory = normal;
		defaultNamedSessionFullAccessFactory = fullaccess;
	}

	public static synchronized void shutdown() {
		System.out.println("Shutting down the OpenNTF Domino API... ");
		Runnable[] copy = shutdownHooks.toArray(new Runnable[shutdownHooks.size()]);
		for (Runnable term : copy) {
			System.out.println("* shutting down " + term);
			try {
				term.run();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		System.out.println("... OpenNTF Domino API shut down");
		started = false;
	}

	public static boolean isStarted() {
		return started;
	}

	public static void setUserLocale(final Locale loc) {
		getThreadVariables().userLocale = loc;
	}

	public static Locale getUserLocale() {
		return getThreadVariables().userLocale;
	}

	/**
	 * Returns the internal locale. The Locale is retrieved by this way:
	 * <ul>
	 * <li>If a currentDatabase is set, the DB is queried for its locale</li>
	 * <li>If there is no database.locale, the system default locale is returned</li>
	 * </ul>
	 * This locale should be used, if you write log entries in a server log for example.
	 * 
	 * @return the currentDatabase-locale or default-locale
	 */
	public static Locale getInternalLocale() {
		Locale ret = null;
		// are we in context of an NotesSession? Try to figure out the current database.
		Session sess = getSession_unchecked(SessionType.CURRENT);
		Database db = (sess == null) ? null : sess.getCurrentDatabase();
		if (db != null)
			ret = db.getLocale();
		if (ret == null)
			ret = Locale.getDefault();
		return ret;
	}

	/**
	 * Returns the external locale. The Locale is retrieved by this way:
	 * <ul>
	 * <li>Return the external locale (= the browser's locale in most cases) if available</li>
	 * <li>If a currentDatabase is set, the DB is queried for its locale</li>
	 * <li>If there is no database.locale, the system default locale is returned</li>
	 * </ul>
	 * This locale should be used, if you generate messages for the current (browser)user.
	 * 
	 * @return the external-locale, currentDatabase-locale or default-locale
	 */
	public static Locale getExternalLocale() {
		Locale ret = getUserLocale();
		if (ret == null)
			ret = getInternalLocale();
		return ret;
	}

	/**
	 * Debug method to get statistics
	 * 
	 */
	public static String dumpCounters(final boolean details) {
		if (counters == null)
			return "Counters are disabled";
		StringBuilder sb = new StringBuilder();
		sb.append("LotusCount: ");
		sb.append(getLotusCount());

		sb.append(" AutoRecycled: ");
		sb.append(getAutoRecycleCount());
		sb.append(" ManualRecycled: ");
		sb.append(getManualRecycleCount());
		sb.append(" RecycleErrors: ");
		sb.append(getRecycleErrorCount());
		sb.append(" ActiveObjects: ");
		sb.append(getActiveObjectCount());

		if (!counters.classes.isEmpty() && details) {
			sb.append("\n=== The following objects were left in memory ===");
			for (Entry<Class<?>, Counter> e : counters.classes.entrySet()) {
				int i = e.getValue().intValue();
				if (i != 0) {
					sb.append("\n" + i + "\t" + e.getKey().getName());
				}
			}
		}
		return sb.toString();
	}

	public static INamedSessionFactory getNamedSessionFactory(final boolean fullAccess) {
		ThreadVariables tv = getThreadVariables();
		if (fullAccess) {
			return tv.namedSessionFullAccessFactory != null ? tv.namedSessionFullAccessFactory : defaultNamedSessionFullAccessFactory;
		} else {
			return tv.namedSessionFactory != null ? tv.namedSessionFactory : defaultNamedSessionFactory;
		}

	}

	public static org.openntf.domino.Session getNamedSession(final String name, final boolean fullAccess) {
		ThreadVariables tv = getThreadVariables();
		String key = name.toLowerCase() + (fullAccess ? ":full" : ":normal");
		Session sess = tv.ownSessions.get(key);
		if (sess == null) {
			try {
				INamedSessionFactory sf = getNamedSessionFactory(fullAccess);
				if (sf != null) {
					sess = sf.createSession(name);
				}
				tv.ownSessions.put(key, sess);
			} catch (PrivilegedActionException e) {
				log_.log(Level.SEVERE, "Unable to create named session for '" + name + "'", e);
			}
		}
		return sess;

	}

	//	/**
	//	 * Gets the parent database.
	//	 * 
	//	 * @param base
	//	 *            the base
	//	 * @return the parent database
	//	 */
	//	@Deprecated
	//	public static Database getParentDatabase(final Base<?> base) {
	//		if (base instanceof org.openntf.domino.Database) {
	//			return (org.openntf.domino.Database) base;
	//		} else if (base instanceof DatabaseDescendant) {
	//			return ((DatabaseDescendant) base).getAncestorDatabase();
	//		} else if (base == null) {
	//			throw new NullPointerException("Base object cannot be null");
	//		} else {
	//			throw new UndefinedDelegateTypeException("Couldn't find session for object of type " + base.getClass().getName());
	//		}
	//	}

	/**
	 * Gets the session.
	 * 
	 * @param base
	 *            the base
	 * @return the session
	 */
	public static Session getSession(final lotus.domino.Base base) {
		org.openntf.domino.Session result = null;
		if (base instanceof SessionDescendant) {
			result = ((SessionDescendant) base).getAncestorSession();
		} else if (base instanceof org.openntf.domino.Session) {
			result = (org.openntf.domino.Session) base;
		} else if (base == null) {
			throw new NullPointerException("Base object cannot be null");
		}
		if (result == null) {
			throw new UndefinedDelegateTypeException("Couldn't find session for object of type " + base.getClass().getName());
		}
		return result;
	}

	// public static boolean toBoolean(Object value) {
	// if (value instanceof String) {
	// char[] c = ((String) value).toCharArray();
	// if (c.length > 1 || c.length == 0) {
	// return false;
	// } else {
	// return c[0] == '1';
	// }
	// } else if (value instanceof Double) {
	// if (((Double) value).intValue() == 0) {
	// return false;
	// } else {
	// return true;
	// }
	// } else {
	// throw new DataNotCompatibleException("Cannot convert a " + value.getClass().getName() + " to boolean primitive.");
	// }
	// }
	//
	// public static int toInt(Object value) {
	// if (value instanceof Integer) {
	// return ((Integer) value).intValue();
	// } else if (value instanceof Double) {
	// return ((Double) value).intValue();
	// } else {
	// throw new DataNotCompatibleException("Cannot convert a " + value.getClass().getName() + " to int primitive.");
	// }
	// }
	//
	// public static double toDouble(Object value) {
	// if (value instanceof Integer) {
	// return ((Integer) value).doubleValue();
	// } else if (value instanceof Double) {
	// return ((Double) value).doubleValue();
	// } else {
	// throw new DataNotCompatibleException("Cannot convert a " + value.getClass().getName() + " to double primitive.");
	// }
	// }
	//
	// public static long toLong(Object value) {
	// if (value instanceof Integer) {
	// return ((Integer) value).longValue();
	// } else if (value instanceof Double) {
	// return ((Double) value).longValue();
	// } else {
	// throw new DataNotCompatibleException("Cannot convert a " + value.getClass().getName() + " to long primitive.");
	// }
	// }
	//
	// public static short toShort(Object value) {
	// if (value instanceof Integer) {
	// return ((Integer) value).shortValue();
	// } else if (value instanceof Double) {
	// return ((Double) value).shortValue();
	// } else {
	// throw new DataNotCompatibleException("Cannot convert a " + value.getClass().getName() + " to short primitive.");
	// }
	//
	// }
	//
	// public static float toFloat(Object value) {
	// if (value instanceof Integer) {
	// return ((Integer) value).floatValue();
	// } else if (value instanceof Double) {
	// return ((Double) value).floatValue();
	// } else {
	// throw new DataNotCompatibleException("Cannot convert a " + value.getClass().getName() + " to float primitive.");
	// }
	//
	// }
	//
	// public static Object toPrimitive(Vector<Object> values, Class<?> ctype) {
	// if (ctype.isPrimitive()) {
	// throw new DataNotCompatibleException(ctype.getName() + " is not a primitive type.");
	// }
	// if (values.size() > 1) {
	// throw new DataNotCompatibleException("Cannot create a primitive " + ctype + " from data because we have a multiple values.");
	// }
	// if (values.isEmpty()) {
	// throw new DataNotCompatibleException("Cannot create a primitive " + ctype + " from data because we don't have any values.");
	// }
	// if (ctype == Boolean.TYPE)
	// return toBoolean(values.get(0));
	// if (ctype == Integer.TYPE)
	// return toInt(values.get(0));
	// if (ctype == Short.TYPE)
	// return toShort(values.get(0));
	// if (ctype == Long.TYPE)
	// return toLong(values.get(0));
	// if (ctype == Float.TYPE)
	// return toFloat(values.get(0));
	// if (ctype == Double.TYPE)
	// return toDouble(values.get(0));
	// if (ctype == Byte.TYPE)
	// throw new UnimplementedException("Primitive conversion for byte not yet defined");
	// if (ctype == Character.TYPE)
	// throw new UnimplementedException("Primitive conversion for char not yet defined");
	// throw new DataNotCompatibleException("");
	// }
	//
	// public static String join(Collection<Object> values, String separator) {
	// StringBuilder sb = new StringBuilder();
	// Iterator<Object> it = values.iterator();
	// while (it.hasNext()) {
	// sb.append(String.valueOf(it.next()));
	// if (it.hasNext())
	// sb.append(separator);
	// }
	// return sb.toString();
	// }
	//
	// public static String join(Collection<Object> values) {
	// return join(values, ", ");
	// }
	//
	// public static Object toPrimitiveArray(Vector<Object> values, Class<?> ctype) throws DataNotCompatibleException {
	// Object result = null;
	// int size = values.size();
	// if (ctype == Boolean.TYPE) {
	// boolean[] outcome = new boolean[size];
	// // TODO NTF - should allow for String fields that are binary sequences: "1001001" (SOS)
	// for (int i = 0; i < size; i++) {
	// Object o = values.get(i);
	// outcome[i] = toBoolean(o);
	// }
	// result = outcome;
	// } else if (ctype == Byte.TYPE) {
	// byte[] outcome = new byte[size];
	// // TODO
	// result = outcome;
	// } else if (ctype == Character.TYPE) {
	// char[] outcome = new char[size];
	// // TODO How should this work? Just concatenate the char arrays for each String?
	// result = outcome;
	// } else if (ctype == Short.TYPE) {
	// short[] outcome = new short[size];
	// for (int i = 0; i < size; i++) {
	// Object o = values.get(i);
	// outcome[i] = toShort(o);
	// }
	// result = outcome;
	// } else if (ctype == Integer.TYPE) {
	// int[] outcome = new int[size];
	// for (int i = 0; i < size; i++) {
	// Object o = values.get(i);
	// outcome[i] = toInt(o);
	// }
	// result = outcome;
	// } else if (ctype == Long.TYPE) {
	// long[] outcome = new long[size];
	// for (int i = 0; i < size; i++) {
	// Object o = values.get(i);
	// outcome[i] = toLong(o);
	// }
	// result = outcome;
	// } else if (ctype == Float.TYPE) {
	// float[] outcome = new float[size];
	// for (int i = 0; i < size; i++) {
	// Object o = values.get(i);
	// outcome[i] = toFloat(o);
	// }
	// result = outcome;
	// } else if (ctype == Double.TYPE) {
	// double[] outcome = new double[size];
	// for (int i = 0; i < size; i++) {
	// Object o = values.get(i);
	// outcome[i] = toDouble(o);
	// }
	// result = outcome;
	// }
	// return result;
	// }
	//
	// public static Date toDate(Object value) throws DataNotCompatibleException {
	// if (value == null)
	// return null;
	// if (value instanceof Long) {
	// return new Date(((Long) value).longValue());
	// } else if (value instanceof String) {
	// // TODO finish
	// DateFormat df = new SimpleDateFormat();
	// try {
	// return df.parse((String) value);
	// } catch (ParseException e) {
	// throw new DataNotCompatibleException("Cannot create a Date from String value " + (String) value);
	// }
	// } else if (value instanceof lotus.domino.DateTime) {
	// return DominoUtils.toJavaDateSafe((lotus.domino.DateTime) value);
	// } else {
	// throw new DataNotCompatibleException("Cannot create a Date from a " + value.getClass().getName());
	// }
	// }
	//
	// public static Date[] toDates(Collection<Object> vector) throws DataNotCompatibleException {
	// if (vector == null)
	// return null;
	//
	// Date[] result = new Date[vector.size()];
	// int i = 0;
	// for (Object o : vector) {
	// result[i++] = toDate(o);
	// }
	// return result;
	// }
	//
	// public static org.openntf.domino.DateTime[] toDateTimes(Collection<Object> vector, org.openntf.domino.Session session)
	// throws DataNotCompatibleException {
	// if (vector == null)
	// return null;
	//
	// org.openntf.domino.DateTime[] result = new org.openntf.domino.DateTime[vector.size()];
	// int i = 0;
	// for (Object o : vector) {
	// result[i++] = session.createDateTime(toDate(o));
	// }
	// return result;
	// }
	//
	// public static org.openntf.domino.Name[] toNames(Collection<Object> vector, org.openntf.domino.Session session)
	// throws DataNotCompatibleException {
	// if (vector == null)
	// return null;
	//
	// org.openntf.domino.Name[] result = new org.openntf.domino.Name[vector.size()];
	// int i = 0;
	// for (Object o : vector) {
	// result[i++] = session.createName(String.valueOf(o));
	// }
	// return result;
	// }
	//
	// public static String[] toStrings(Collection<Object> vector) throws DataNotCompatibleException {
	// if (vector == null)
	// return null;
	// String[] strings = new String[vector.size()];
	// int i = 0;
	// for (Object o : vector) {
	// if (o instanceof DateTime) {
	// strings[i++] = ((DateTime) o).getGMTTime();
	// } else {
	// strings[i++] = String.valueOf(o);
	// }
	// }
	// return strings;
	// }

	/**
	 * To lotus note collection.
	 * 
	 * @param collection
	 *            the collection
	 * @return the org.openntf.domino. note collection
	 */
	public static org.openntf.domino.NoteCollection toNoteCollection(final lotus.domino.DocumentCollection collection) {
		org.openntf.domino.NoteCollection result = null;
		if (collection instanceof DocumentCollection) {
			org.openntf.domino.Database db = ((DocumentCollection) collection).getParent();
			result = db.createNoteCollection(false);
			result.add(collection);
		} else {
			throw new DataNotCompatibleException("Cannot convert a non-OpenNTF DocumentCollection to a NoteCollection");
		}
		return result;
	}

	/**
	 * Add a hook that will run on the next "terminate" call
	 * 
	 * @param hook
	 *            the hook that should run on next terminate
	 */
	public static void addTerminateHook(final Runnable hook) {
		terminateHooks.add(hook);
	}

	public static void removeTerminateHook(final Runnable hook) {
		terminateHooks.remove(hook);
	}

	/**
	 * Add a hook that will run on shutdown
	 */
	public static void addShutdownHook(final Runnable hook) {
		shutdownHooks.add(hook);
	}

	/**
	 * Remove a shutdown hook
	 * 
	 * @param hook
	 *            the hook that should be removed
	 */
	public static void removeShutdownHook(final Runnable hook) {
		shutdownHooks.remove(hook);
	}

	public static String getLocalServerName() {
		return localServerName;
	}

}
