/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.clients.http.PageMaker.THEME;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.clients.http.updateableelements.PushDataManager;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.crypt.SSL;
import freenet.io.AllowedHosts;
import freenet.io.NetworkInterface;
import freenet.io.SSLNetworkInterface;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.Ticker;
import freenet.node.SecurityLevelListener;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.pluginmanager.FredPluginL10n;
import freenet.support.Executor;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.api.BooleanCallback;
import freenet.support.api.BucketFactory;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.ArrayBucketFactory;

/** 
 * The Toadlet (HTTP) Server
 * 
 * Provide a HTTP server for FProxy
 */
public final class SimpleToadletServer implements ToadletContainer, Runnable {
	/** List of urlPrefix / Toadlet */ 
	private final LinkedList<ToadletElement> toadlets;
	private static class ToadletElement {
		public ToadletElement(Toadlet t2, String urlPrefix) {
			t = t2;
			prefix = urlPrefix;
		}
		Toadlet t;
		String prefix;
	}

	// Socket / Binding
	private final int port;
	private String bindTo;
	private String allowedHosts;
	private NetworkInterface networkInterface;
	private boolean ssl = false;
	public static final int DEFAULT_FPROXY_PORT = 8888;
	
	// ACL
	private final AllowedHosts allowedFullAccess;
	private boolean publicGatewayMode;
	
	// Theme 
	private THEME cssTheme;
	private File cssOverride;
	private boolean advancedModeEnabled;
	private final PageMaker pageMaker;
	
	// Control
	private Thread myThread;
	private final Executor executor;
	private BucketFactory bf;
	private NodeClientCore core;
	
	// HTTP Option
	private boolean doRobots;
	private boolean enablePersistentConnections;
	private boolean enableInlinePrefetch;
	private boolean enableActivelinks;
	private boolean enableExtendedMethodHandling;
	
	// Something does not really belongs to here
	volatile static boolean isPanicButtonToBeShown;				// move to QueueToadlet ?
	volatile static boolean noConfirmPanic;
	public BookmarkManager bookmarkManager;				// move to WelcomeToadlet / BookmarkEditorToadlet ?
	private volatile boolean fProxyJavascriptEnabled;	// ugh?
	private volatile boolean fproxyHasCompletedWizard;	// hmmm..
	private volatile boolean disableProgressPage;
	
	/** The PushDataManager handles all the pushing tasks*/
	public PushDataManager pushDataManager; 
	
	/** The IntervalPusherManager handles interval pushing*/
	public IntervalPusherManager intervalPushManager;
	
	// Config Callbacks
	private class FProxySSLCallback extends BooleanCallback  {
		@Override
		public Boolean get() {
			return ssl;
		}
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			if(!SSL.available()) {
				throw new InvalidConfigValueException("Enable SSL support before use ssl with Fproxy");
			}
			ssl = val;
			throw new InvalidConfigValueException("Cannot change SSL on the fly, please restart freenet");
		}
		@Override
		public boolean isReadOnly() {
			return true;
		}
	}
	
	private static class FProxyPassthruMaxSize extends LongCallback {
		@Override
		public Long get() {
			return FProxyToadlet.MAX_LENGTH;
		}
		
		@Override
		public void set(Long val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			FProxyToadlet.MAX_LENGTH = val;
		}
	}

	private class FProxyPortCallback extends IntCallback  {
		private Integer savedPort;
		@Override
		public Integer get() {
			if (savedPort == null)
				savedPort = port;
			return savedPort;
		}
		
		@Override
		public void set(Integer newPort) throws NodeNeedRestartException {
			if(savedPort != (int)newPort) {
				savedPort = port;
				throw new NodeNeedRestartException("Port cannot change on the fly");
			}
		}
	}
	
	private class FProxyBindtoCallback extends StringCallback  {
		@Override
		public String get() {
			return bindTo;
		}
		
		@Override
		public void set(String bindTo) throws InvalidConfigValueException {
			if(!bindTo.equals(get())) {
				try {
					networkInterface.setBindTo(bindTo, false);
					SimpleToadletServer.this.bindTo = bindTo;
				} catch (IOException e) {
					// This is an advanced option for reasons of reducing clutter,
					// but it is expected to be used by regular users, not devs.
					// So we translate the error messages.
					throw new InvalidConfigValueException(l10n("couldNotChangeBindTo", "error", e.getLocalizedMessage()));
				}
			}
		}
	}
	private class FProxyAllowedHostsCallback extends StringCallback  {
		@Override
		public String get() {
			return networkInterface.getAllowedHosts();
		}
		
		@Override
		public void set(String allowedHosts) {
			if (!allowedHosts.equals(get())) {
				networkInterface.setAllowedHosts(allowedHosts);
			}
		}
	}
	private class FProxyCSSNameCallback extends StringCallback implements EnumerableOptionCallback {
		@Override
		public String get() {
			return cssTheme.code;
		}
		
		@Override
		public void set(String CSSName) throws InvalidConfigValueException {
			if((CSSName.indexOf(':') != -1) || (CSSName.indexOf('/') != -1))
				throw new InvalidConfigValueException(l10n("illegalCSSName"));
			cssTheme = THEME.themeFromName(CSSName);
			pageMaker.setTheme(cssTheme);
			if (core.node.pluginManager != null)
				core.node.pluginManager.setFProxyTheme(cssTheme);
		}

		public String[] getPossibleValues() {
			return THEME.possibleValues;
		}
	}
	private class FProxyCSSOverrideCallback extends StringCallback  {
		@Override
		public String get() {
			return (cssOverride == null ? "" : cssOverride.toString());
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			if(core == null) return;
			if(val.equals(get()) || val.equals(""))
				cssOverride = null;
			else {
				File tmp = new File(val.trim());
				if(!core.allowUploadFrom(tmp))
					throw new InvalidConfigValueException(l10n("cssOverrideNotInUploads", "filename", tmp.toString()));
				else if(!tmp.canRead() || !tmp.isFile())
					throw new InvalidConfigValueException(l10n("cssOverrideCantRead", "filename", tmp.toString()));
				cssOverride = tmp.getAbsoluteFile();
			}
			pageMaker.setOverride(cssOverride);
		}
	}
	private class FProxyEnabledCallback extends BooleanCallback  {
		@Override
		public Boolean get() {
			synchronized(SimpleToadletServer.this) {
				return myThread != null;
			}
		}
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
			synchronized(SimpleToadletServer.this) {
				if(val) {
					// Start it
					myThread = new Thread(SimpleToadletServer.this, "SimpleToadletServer");
				} else {
					myThread.interrupt();
					myThread = null;
					return;
				}
			}
			createFproxy();
			myThread.setDaemon(true);
			myThread.start();
		}
	}
	private static class FProxyAdvancedModeEnabledCallback extends BooleanCallback  {
		private final SimpleToadletServer ts;
		
		FProxyAdvancedModeEnabledCallback(SimpleToadletServer ts){
			this.ts = ts;
		}
		
		@Override
		public Boolean get() {
			return ts.isAdvancedModeEnabled();
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
				ts.enableAdvancedMode(val);
		}
	}
	private static class FProxyJavascriptEnabledCallback extends BooleanCallback  {
		
		private final SimpleToadletServer ts;
		
		FProxyJavascriptEnabledCallback(SimpleToadletServer ts){
			this.ts = ts;
		}
		
		@Override
		public Boolean get() {
			return ts.isFProxyJavascriptEnabled();
		}
		
		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			if (get().equals(val))
				return;
				ts.enableFProxyJavascript(val);
		}
	}
	
	private boolean haveCalledFProxy = false;
	
	public void createFproxy() {
		synchronized(this) {
			if(haveCalledFProxy) return;
			haveCalledFProxy = true;
		}
		
		pushDataManager=new PushDataManager(getTicker());
		intervalPushManager=new IntervalPusherManager(getTicker(), pushDataManager);
		bookmarkManager = new BookmarkManager(core);
		try {
			FProxyToadlet.maybeCreateFProxyEtc(core, core.node, core.node.config, this, bookmarkManager);
		} catch (IOException e) {
			Logger.error(this, "Could not start fproxy: "+e, e);
			System.err.println("Could not start fproxy:");
			e.printStackTrace();
		}
	}
	

	
	public synchronized void setCore(NodeClientCore core) {
		this.core = core;
	}
	
	/**
	 * Create a SimpleToadletServer, using the settings from the SubConfig (the fproxy.*
	 * config).
	 */
	public SimpleToadletServer(SubConfig fproxyConfig, BucketFactory bucketFactory, Executor executor, Node node) throws IOException, InvalidConfigValueException {

		this.executor = executor;
		this.core = node.clientCore;
		
		int configItemOrder = 0;
		
		fproxyConfig.register("enabled", true, configItemOrder++, true, true, "SimpleToadletServer.enabled", "SimpleToadletServer.enabledLong",
				new FProxyEnabledCallback());
		
		boolean enabled = fproxyConfig.getBoolean("enabled");
		
		fproxyConfig.register("ssl", false, configItemOrder++, true, true, "SimpleToadletServer.ssl", "SimpleToadletServer.sslLong",
				new FProxySSLCallback());
		fproxyConfig.register("port", DEFAULT_FPROXY_PORT, configItemOrder++, true, true, "SimpleToadletServer.port", "SimpleToadletServer.portLong",
				new FProxyPortCallback(), false);
		fproxyConfig.register("bindTo", NetworkInterface.DEFAULT_BIND_TO, configItemOrder++, true, true, "SimpleToadletServer.bindTo", "SimpleToadletServer.bindToLong",
				new FProxyBindtoCallback());
		fproxyConfig.register("css", "clean-dropdown", configItemOrder++, false, false, "SimpleToadletServer.cssName", "SimpleToadletServer.cssNameLong",
				new FProxyCSSNameCallback());
		fproxyConfig.register("CSSOverride", "", configItemOrder++, true, false, "SimpleToadletServer.cssOverride", "SimpleToadletServer.cssOverrideLong",
				new FProxyCSSOverrideCallback());
		fproxyConfig.register("advancedModeEnabled", false, configItemOrder++, true, false, "SimpleToadletServer.advancedMode", "SimpleToadletServer.advancedModeLong",
				new FProxyAdvancedModeEnabledCallback(this));
		fproxyConfig.register("enableExtendedMethodHandling", false, configItemOrder++, true, false, "SimpleToadletServer.enableExtendedMethodHandling", "SimpleToadletServer.enableExtendedMethodHandlingLong",
				new BooleanCallback() {
					@Override
					public Boolean get() {
						return enableExtendedMethodHandling;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(get().equals(val)) return;
						enableExtendedMethodHandling = val;
					}
		});
		fproxyConfig.register("javascriptEnabled", true, configItemOrder++, true, false, "SimpleToadletServer.enableJS", "SimpleToadletServer.enableJSLong",
				new FProxyJavascriptEnabledCallback(this));
		fproxyConfig.register("hasCompletedWizard", false, configItemOrder++, true, false, "SimpleToadletServer.hasCompletedWizard", "SimpleToadletServer.hasCompletedWizardLong",
				new BooleanCallback() {
					@Override
					public Boolean get() {
						return fproxyHasCompletedWizard;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(get().equals(val)) return;
						fproxyHasCompletedWizard = val;
					}
		});
		fproxyConfig.register("disableProgressPage", false, configItemOrder++, true, false, "SimpleToadletServer.disableProgressPage", "SimpleToadletServer.disableProgressPageLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return disableProgressPage;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						disableProgressPage = val;
					}
			
		});
		fproxyHasCompletedWizard = fproxyConfig.getBoolean("hasCompletedWizard");
		fProxyJavascriptEnabled = fproxyConfig.getBoolean("javascriptEnabled");
		disableProgressPage = fproxyConfig.getBoolean("disableProgressPage");
		enableExtendedMethodHandling = fproxyConfig.getBoolean("enableExtendedMethodHandling");

		fproxyConfig.register("showPanicButton", false, configItemOrder++, true, true, "SimpleToadletServer.panicButton", "SimpleToadletServer.panicButtonLong",
				new BooleanCallback(){
				@Override
				public Boolean get() {
					return SimpleToadletServer.isPanicButtonToBeShown;
				}
			
				@Override
				public void set(Boolean value) {
					if(value == SimpleToadletServer.isPanicButtonToBeShown) return;
					else	SimpleToadletServer.isPanicButtonToBeShown = value;
				}
		});
		
		fproxyConfig.register("noConfirmPanic", false, configItemOrder++, true, true, "SimpleToadletServer.noConfirmPanic", "SimpleToadletServer.noConfirmPanicLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return SimpleToadletServer.noConfirmPanic;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(val == SimpleToadletServer.noConfirmPanic) return;
						else SimpleToadletServer.noConfirmPanic = val;
					}
		});
		
		fproxyConfig.register("publicGatewayMode", false, configItemOrder++, true, true, "SimpleToadletServer.publicGatewayMode", "SimpleToadletServer.publicGatewayModeLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return publicGatewayMode;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				publicGatewayMode = val;
			}
			
		});
		publicGatewayMode = fproxyConfig.getBoolean("publicGatewayMode");
		
		// This is OFF BY DEFAULT because for example firefox has a limit of 2 persistent 
		// connections per server, but 8 non-persistent connections per server. We need 8 conns
		// more than we need the efficiency gain of reusing connections - especially on first
		// install.
		
		fproxyConfig.register("enablePersistentConnections", false, configItemOrder++, true, false, "SimpleToadletServer.enablePersistentConnections", "SimpleToadletServer.enablePersistentConnectionsLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						synchronized(SimpleToadletServer.this) {
							return enablePersistentConnections;
						}
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						synchronized(SimpleToadletServer.this) {
							enablePersistentConnections = val;
						}
					}
		});
		enablePersistentConnections = fproxyConfig.getBoolean("enablePersistentConnections");
		
		// Off by default.
		// I had hoped it would yield a significant performance boost to bootstrap performance
		// on browsers with low numbers of simultaneous connections. Unfortunately the bottleneck
		// appears to be that the node does very few local requests compared to external requests
		// (for anonymity's sake).
		
		fproxyConfig.register("enableInlinePrefetch", false, configItemOrder++, true, false, "SimpleToadletServer.enableInlinePrefetch", "SimpleToadletServer.enableInlinePrefetchLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						synchronized(SimpleToadletServer.this) {
							return enableInlinePrefetch;
						}
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						synchronized(SimpleToadletServer.this) {
							enableInlinePrefetch = val;
						}
					}
		});
		enableInlinePrefetch = fproxyConfig.getBoolean("enableInlinePrefetch");
		
		fproxyConfig.register("enableActivelinks", false, configItemOrder++, false, false, "SimpleToadletServer.enableActivelinks", "SimpleToadletServer.enableActivelinksLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enableActivelinks;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				enableActivelinks = val;
			}
			
		});
		enableActivelinks = fproxyConfig.getBoolean("enableActivelinks");
		
		fproxyConfig.register("passthroughMaxSize", (2L*1024*1024*11)/10, configItemOrder++, true, false, "SimpleToadletServer.passthroughMaxSize", "SimpleToadletServer.passthroughMaxSizeLong", new FProxyPassthruMaxSize(), true);
		FProxyToadlet.MAX_LENGTH = fproxyConfig.getLong("passthroughMaxSize");
		
		fproxyConfig.register("allowedHosts", "127.0.0.1,0:0:0:0:0:0:0:1", configItemOrder++, true, true, "SimpleToadletServer.allowedHosts", "SimpleToadletServer.allowedHostsLong",
				new FProxyAllowedHostsCallback());
		fproxyConfig.register("allowedHostsFullAccess", "127.0.0.1,0:0:0:0:0:0:0:1", configItemOrder++, true, true, "SimpleToadletServer.allowedFullAccess", 
				"SimpleToadletServer.allowedFullAccessLong",
				new StringCallback() {

					@Override
					public String get() {
						return allowedFullAccess.getAllowedHosts();
					}

					@Override
					public void set(String val) throws InvalidConfigValueException {
						allowedFullAccess.setAllowedHosts(val);
					}
			
		});
		allowedFullAccess = new AllowedHosts(fproxyConfig.getString("allowedHostsFullAccess"));
		fproxyConfig.register("doRobots", false, configItemOrder++, true, false, "SimpleToadletServer.doRobots", "SimpleToadletServer.doRobotsLong",
				new BooleanCallback() {
					@Override
					public Boolean get() {
						return doRobots;
					}
					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						doRobots = val;
					}
		});
		doRobots = fproxyConfig.getBoolean("doRobots");
		
		SimpleToadletServer.isPanicButtonToBeShown = fproxyConfig.getBoolean("showPanicButton");
		SimpleToadletServer.noConfirmPanic = fproxyConfig.getBoolean("noConfirmPanic");
		
		this.bf = bucketFactory;
		port = fproxyConfig.getInt("port");
		bindTo = fproxyConfig.getString("bindTo");
		String cssName = fproxyConfig.getString("css");
		if((cssName.indexOf(':') != -1) || (cssName.indexOf('/') != -1))
			throw new InvalidConfigValueException("CSS name must not contain slashes or colons!");
		cssTheme = THEME.themeFromName(cssName);
		pageMaker = new PageMaker(cssTheme, node);
	
		if(!fproxyConfig.getOption("CSSOverride").isDefault()) {
			cssOverride = new File(fproxyConfig.getString("CSSOverride"));
			pageMaker.setOverride(cssOverride);
		} else
			cssOverride = null;
		
		this.advancedModeEnabled = fproxyConfig.getBoolean("advancedModeEnabled");
		toadlets = new LinkedList<ToadletElement>();

		if(SSL.available()) {
			ssl = fproxyConfig.getBoolean("ssl");
		}
		
		this.allowedHosts=fproxyConfig.getString("allowedHosts");

		if(!enabled) {
			Logger.normal(SimpleToadletServer.this, "Not starting FProxy as it's disabled");
			System.out.println("Not starting FProxy as it's disabled");
		} else {
			maybeGetNetworkInterface();
			myThread = new Thread(this, "SimpleToadletServer");
			myThread.setDaemon(true);
		}
		
		// Register static toadlet and startup toadlet
		
		StaticToadlet statictoadlet = new StaticToadlet();
		register(statictoadlet, null, "/static/", false, false);

		
		// "Freenet is starting up..." page, to be removed at #removeStartupToadlet()
		startupToadlet = new StartupToadlet(statictoadlet);
		register(startupToadlet, null, "/", false, false);
	}
	
	public StartupToadlet startupToadlet;
	
	public void removeStartupToadlet() {
		unregister(startupToadlet);
		// Ready to be GCed
		startupToadlet = null;
		// Not in the navbar.
	}
	
	private void maybeGetNetworkInterface() throws IOException {
		if (this.networkInterface!=null) return;
		if(ssl) {
			this.networkInterface = SSLNetworkInterface.create(port, this.bindTo, allowedHosts, executor, true);
		} else {
			this.networkInterface = NetworkInterface.create(port, this.bindTo, allowedHosts, executor, true);
		}
	}		

	public boolean doRobots() {
		return doRobots;
	}
	
	public boolean publicGatewayMode() {
		return publicGatewayMode;
	}
	
	public void start() {
		if(myThread != null) try {
			maybeGetNetworkInterface();
			myThread.start();
			Logger.normal(this, "Starting FProxy on "+bindTo+ ':' +port);
			System.out.println("Starting FProxy on "+bindTo+ ':' +port);
		} catch (IOException e) {
			Logger.error(this, "Could not bind network port for FProxy?", e);
		}
	}
	
	public void finishStart() {
		core.node.securityLevels.addPhysicalThreatLevelListener(new SecurityLevelListener<PHYSICAL_THREAT_LEVEL> () {

			public void onChange(PHYSICAL_THREAT_LEVEL oldLevel, PHYSICAL_THREAT_LEVEL newLevel) {
				if(newLevel != oldLevel && newLevel == PHYSICAL_THREAT_LEVEL.LOW) {
					isPanicButtonToBeShown = false;
				} else if(newLevel != oldLevel) {
					isPanicButtonToBeShown = true;
				}
			}
			
		});
	}
	
	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, boolean fullOnly) {
		register(t, menu, urlPrefix, atFront, null, null, fullOnly, null);
	}
	
	public void register(Toadlet t, String menu, String urlPrefix, boolean atFront, String name, String title, boolean fullOnly, LinkEnabledCallback cb) {
		ToadletElement te = new ToadletElement(t, urlPrefix);
		if(atFront) toadlets.addFirst(te);
		else toadlets.addLast(te);
		t.container = this;
		if (name != null) {
			pageMaker.addNavigationLink(menu, urlPrefix, name, title, fullOnly, cb);
		}
	}
	
	public void registerMenu(String link, String name, String title, FredPluginL10n plugin) {
		pageMaker.addNavigationCategory(link, name, title, plugin);
	}

	public synchronized void unregister(Toadlet t) {
		for(Iterator<ToadletElement> i=toadlets.iterator();i.hasNext();) {
			ToadletElement e = i.next();
			if(e.t == t) {
				i.remove();
				return;
			}
		}
	}
	
	public StartupToadlet getStartupToadlet() {
		return startupToadlet;
	}
	
	public boolean fproxyHasCompletedWizard() {
		return fproxyHasCompletedWizard;
	}
	
	public Toadlet findToadlet(URI uri) throws PermanentRedirectException {
		String path = uri.getPath();

		// Show the wizard until dismissed by the user (See bug #2624)
		if(core != null && core.node != null && !fproxyHasCompletedWizard) {
			if(!(core.node.isOpennetEnabled() || core.node.getPeerNodes().length > 0)) {
				
				if(!(path.startsWith(FirstTimeWizardToadlet.TOADLET_URL) ||
						path.startsWith(StaticToadlet.ROOT_URL))) {
					try {
						throw new PermanentRedirectException(new URI(FirstTimeWizardToadlet.TOADLET_URL));
					} catch(URISyntaxException e) { throw new Error(e); }
				}
			} else {
				// Assume it's okay.
				fproxyHasCompletedWizard = true;
			}
		}

		Iterator<ToadletElement> i = toadlets.iterator();
		while(i.hasNext()) {
			ToadletElement te = i.next();
						
			if(path.startsWith(te.prefix))
					return te.t;
			if(te.prefix.length() > 0 && te.prefix.charAt(te.prefix.length()-1) == '/') {
				if(path.equals(te.prefix.substring(0, te.prefix.length()-1))) {
					URI newURI;
					try {
						newURI = new URI(te.prefix);
					} catch (URISyntaxException e) {
						throw new Error(e);
					}
					throw new PermanentRedirectException(newURI);
				}
			}
		}
		return null;
	}

	public void run() {
		try {
			networkInterface.setSoTimeout(500);
		} catch (SocketException e1) {
			Logger.error(this, "Could not set so-timeout to 500ms; on-the-fly disabling of the interface will not work");
		}
		while(true) {
			synchronized(this) {
				if(myThread == null) return;
			}
			Socket conn = networkInterface.accept();
            if(conn == null)
                continue; // timeout
            if(Logger.shouldLog(Logger.MINOR, this))
                Logger.minor(this, "Accepted connection");
            SocketHandler sh = new SocketHandler(conn);
            sh.start();
		}
	}
	
	public class SocketHandler implements Runnable {

		Socket sock;
		
		public SocketHandler(Socket conn) {
			this.sock = conn;
		}

		void start() {
			executor.execute(this, "HTTP socket handler@"+hashCode());
		}
		
		public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Handling connection");
			try {
				ToadletContextImpl.handle(sock, SimpleToadletServer.this, pageMaker);
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("SimpleToadletServer request above failed.");
				Logger.error(this, "OOM in SocketHandler");
			} catch (Throwable t) {
				System.err.println("Caught in SimpleToadletServer: "+t);
				t.printStackTrace();
				Logger.error(this, "Caught in SimpleToadletServer: "+t, t);
			}
			if(logMINOR) Logger.minor(this, "Handled connection");
		}

	}

	public THEME getTheme() {
		return this.cssTheme;
	}

	public void setCSSName(THEME theme) {
		this.cssTheme = theme;
	}

	public synchronized boolean isAdvancedModeEnabled() {
		return this.advancedModeEnabled;
	}
	
	public void setAdvancedMode(boolean enabled) {
		synchronized(this) {
			if(advancedModeEnabled == enabled) return;
			advancedModeEnabled = enabled;
		}
		core.node.config.store();
	}
	
	public synchronized void enableAdvancedMode(boolean b){
		advancedModeEnabled = b;
	}

	public synchronized boolean isFProxyJavascriptEnabled() {
		return this.fProxyJavascriptEnabled;
	}
	
	public synchronized void enableFProxyJavascript(boolean b){
		fProxyJavascriptEnabled = b;
	}

	public String getFormPassword() {
		if(core == null) return "";
		return core.formPassword;
	}

	public boolean isAllowedFullAccess(InetAddress remoteAddr) {
		return this.allowedFullAccess.allowed(remoteAddr);
	}
	
	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("SimpleToadletServer."+key, pattern, value);
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("SimpleToadletServer."+key);
	}

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id) {
		HTMLNode formNode =
			parentNode.addChild("form", new String[] { "action", "method", "enctype", "id",  "accept-charset" }, 
					new String[] { target, "post", "multipart/form-data", id, "utf-8"} ).addChild("div");
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "hidden", "formPassword", getFormPassword() });
		
		return formNode;
	}

	public void setBucketFactory(BucketFactory tempBucketFactory) {
		this.bf = tempBucketFactory;
	}

	public boolean isEnabled() {
		return myThread != null;
	}

	public BookmarkManager getBookmarks() {
		return bookmarkManager;
	}

	public FreenetURI[] getBookmarkURIs() {
		if(bookmarkManager == null) return new FreenetURI[0];
		return bookmarkManager.getBookmarkURIs();
	}

	public boolean enablePersistentConnections() {
		return enablePersistentConnections;
	}

	public boolean enableInlinePrefetch() {
		return enableInlinePrefetch;
	}

	public boolean enableExtendedMethodHandling() {
		return enableExtendedMethodHandling;
	}

	public synchronized boolean allowPosts() {
		return !(bf instanceof ArrayBucketFactory);
	}

	public synchronized BucketFactory getBucketFactory() {
		return bf;
	}
	


	public boolean enableActivelinks() {
		return enableActivelinks;
	}



	public boolean disableProgressPage() {
		return disableProgressPage;
	}



	public PageMaker getPageMaker() {
		return pageMaker;
	}
	
	public Ticker getTicker(){
		return core.node.ps;
	}
	
	public NodeClientCore getCore(){
		return core;
	}

}
