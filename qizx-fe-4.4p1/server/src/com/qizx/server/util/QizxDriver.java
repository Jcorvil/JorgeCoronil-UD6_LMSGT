/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.util;

import com.qizx.api.*;
import com.qizx.api.Configuration.Property;
import com.qizx.api.util.DefaultModuleResolver;
import com.qizx.api.util.logging.Statistic;
import com.qizx.api.util.logging.Statistics;
import com.qizx.api.util.time.ScheduleHelper;
import com.qizx.expath.pkg.QizxRepoResolver;
import com.qizx.expath.pkg.QizxRepository;
import com.qizx.server.util.accesscontrol.ACLAccessControl;
import com.qizx.server.util.accesscontrol.BaseUser;
import com.qizx.util.ConfigTable;
import com.qizx.util.basic.FileUtil;
import com.qizx.xdm.DocumentPool;
import com.qizx.xquery.ExpressionImpl;
import com.qizx.xquery.ext.AdminFunctions;

import com.xmlmind.multipartreq.MultipartConfig;

import org.apache.xml.resolver.CatalogManager;
import org.expath.pkg.repo.FileSystemStorage;
import org.expath.pkg.repo.PackageException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

/**
 * Manages access to a Qizx Library engine on behalf of a Web App. A single
 * instance is attached to a Web App context.
 * <p>
 * Services provided:
 * <ul>
 * <li>Load configuration
 * <li>Start and stop Qizx engine
 * <li>Open XML Library sessions.
 * </ul>
 */
public class QizxDriver
{
    private static final int MB = 1048576;
    
    // key used to attach this object to a web app context:
    static final String WAPP_KEY = "Qizx_driver";
    // Location of the configuration .properties file inside server root
    private static final String QIZX_ROOT_PARAMETER = "qizx-server-root";
    // Location of the configuration .properties file inside server root
    private static final String RW_CONF_FILE = "server.conf";
    // old file:
    private static final String QIZX_CONFIG = "qizx-server.conf";

    static final String SERVER_API_NS = "java:com.qizx.server.util.ServerFunctions";
    
    public static final String DRIVER_PROP = "qizxServer";
    
    // -----------------------------------------------------------------------

    /**
     * Name of the server, returned by 'info' request.
     * <p>
     */
    public static final Property SERVER_NAME =
        new Property("server_name", "Server", "admin",
                     "Name of the server, returned by 'info' request",
                     "Qizx Server");
    /**
     * Path to the Qizx XML Library Group.
     * <p>By default, this is the relative path "xlibraries". It can be replaced
     * by an absolute path.
     */
    public static final Property LIBRARY_GROUP =
        new Property("library_group", "Server", "admin",
                     "Path to the XML Library Group",
                     "xlibraries");
    /**
     * XQuery Services: (sub)directory where scripts are stored.
     * <p>
     */
    public static final Property SERVICES_DIR =
        new Property("services_dir", "Server", "expert",
                     "Path to the location of XQuery Services",
                     "xqs");
    /**
     * XQuery Services: used XML Library.
     * Needs to be specified if several XML Libraries are managed by the server.
     */
    public static final Property SERVICES_LIBRARY=
        new Property("services_library", "Server", "admin",
                     "XML Library used by XQuery Services",
                     null);

    /**
     * XQuery module management: (sub)directory where modules are stored.
     * <p>
     */
    public static final Property MODULES_DIR =
        new Property("modules_dir", "Server", "expert",
                     "Path to the location of XQuery modules and XSLT templates",
                     "modules");

    /**
     * XQuery module management: (sub)directory where modules are stored.
     * <p>
     */
    public static final Property EXPATH_REPOSITORY =
        new Property("expath_repository", "Server", "admin",
                     "Path to the EXPath repository",
                     "xpkg_repository");
    
    /**
     * AccessControl class used by the Qizx engine.
     *  <p>full Java class name, must be accessible to the class loader.
     *  <p>Note that support for requests setacl / getacl is provided only for
     *  default class com.qizx.server.util.accesscontrol.ACLAccessControl .
     * <p>
     */
    public static final Property ACCESS_CONTROL =
        new Property("access_control", "Server", "admin",
                     "AccessControl class used by the Qizx engine (full Java class name)",
                     "");
    /**
     * Names of users allowed to invoke admin requests.
     * <p>
     */
    public static final Property ADMIN_USER =
        new Property("admin_user", "Server", "admin",
                     "Names of users allowed to invoke admin requests",
                     "admin,qizx-admin");
    /**
     * User Roles allowed to invoke admin requests.
     * <p>
     */
    public static final Property ADMIN_ROLE =
        new Property("admin_role", "Server", "admin",
                     "User Roles allowed to invoke admin requests",
                     "manager");
    
    /**
     * Maximum size of the Session Pool.
     * <p>value could be approximately the number of simultaneous users
     */
    public static final Property SESSION_CACHE_SIZE =
        new Property("session_pool_size", "Server", "admin",
                     "Maximum size of the session pool",
                     20);
    
    /**
     * Maximum size of the Stored Query Cache.
     * <p>This cache is used for 
     */
    public static final Property STORED_QUERIES_CACHE_SIZE =
        new Property("stored_queries_cache_size", "Server", "admin",
                     "Maximum size of the Stored Query Cache",
                     100);
    
    /**
     * Size of the Sequence Cache.
     * <p>value could be approximately the number of simultaneous users
     */
    public static final Property SEQUENCE_CACHE_SIZE =
        new Property("sequence_cache_size", "Server", "admin",
                     "Size of the Sequence Cache",
                     20);
    /**
     * Policy of the Sequence Cache, used in 'eval' request.
     * <p>value is "smart" by default, or "brutal" (invalidate whole cache on each update)
     */
    public static final Property SEQUENCE_CACHE_POLICY =
        new Property("sequence_cache_policy", "Server", "admin",
                     "Invalidation policy of the Sequence Cache: 'smart': analyses " +
                     "queries to find which are concerned by an update, 'dumb': the" +
                     " whole cache is invalidated on each update",
                     "smart");
    /**
     * Maximum execution time for XQuery evaluations, in milliseconds.
     * <p>If value is <= 0, there is not maximum.
     */
    public static final Property EVAL_TIME_OUT =
        new Property("eval_time_out", "Server", "admin",
                     "Maximum execution time for XQuery evaluations, in milliseconds",
                     0);
    /**
     * Maximum size in Mb of a POST request.
     * <p>Beware that the J2EE container might have its own limits.
     */
    public static final Property POST_LIMIT =
        new Property("POST_limit", "Server", "admin",
                     "Maximum size in Mb of a POST request.",
                     -1);
    /**
     * XML schema and DTD catalogs for XML parsing in the server:
     * a list of paths containing XML catalogs.
     * <p>
     */
    public static final Property CATALOGS =
        new Property("catalogs", "Server", "admin",
                     "XML catalogs for XML parsing in the server: a list of paths containing XML catalogs",
                     "");
    /**
     * XML schema and DTD catalogs for XML parsing in the server:
     * .
     * <p>
     */
    public static final Property CATALOGS_VERBOSITY =
        new Property("catalogs_verbosity", "Server", "expert",
                     "XML catalogs : verbosity, an integer from 0 to 9, for debugging.",
                     0);
    /**
     * XML schema and DTD catalogs for XML parsing in the server:
     * XML catalogs ID preference: "public" or "system".
     * <p>
     */
    public static final Property CATALOGS_PREFER =
        new Property("catalogs_prefer", "Server", "admin",
                     "XML catalogs ID preference: \"public\" or \"system\"",
                     "public");

    /**
     * Path of the target directory for backup.
     */
    public static final Property BACKUP_DIR =
        new Property("scheduled_backup_dir", "Server", "admin",
                     "Path of the target directory for backup. " +
                     "If N directories are used, this path is appended with digits 1 to N",
                     "");
    /**
     * Number of target directories for backup.
     */
    public static final Property BACKUP_DIR_COUNT =
        new Property("scheduled_backup_dir_count", "Server", "admin",
                     "Number of target directories for backup",
                     2);
    /**
     * Interval in hours between backups.
     */
    public static final Property BACKUP_INTERVAL =
        new Property("scheduled_backup_interval", "Server", "admin",
                     "Interval in hours between backups",
                     24);
    /**
     * Time of day when backup starts.
     * Only minutes are used if backup interval is less than 24 hours.
     */
    public static final Property BACKUP_START_TIME =
        new Property("scheduled_backup_start", "Server", "admin",
                     "Time of day when backup starts",
                     "02:00");

    // hidden property for remembering latest backup
    static final Property BACKUP_LATEST = 
        new Property("scheduled_backup_latest", "Server", "expert",
                     null, 0);

    /**
     * Path of the target directory for backup.
     */
    public static final Property IBACKUP_DIR =
        new Property("scheduled_ibackup_dir", "Server", "admin",
                     "Path of the target directory for incremental backup. " +
                     "If N directories are used, this path is appended with digits 1 to N",
                     "");
    /**
     * Interval in hours between backups.
     */
    public static final Property IBACKUP_INTERVAL =
        new Property("scheduled_ibackup_interval", "Server", "admin",
                     "Interval in hours between incremental backups",
                     1);
    /**
     * Time of day when backup starts.
     * Only minutes are used if backup interval is less than 24 hours.
     */
    public static final Property IBACKUP_START_TIME =
        new Property("scheduled_ibackup_start", "Server", "admin",
                     "Time of day when incremental backup starts",
                     "00:30");

    
    /**
     * Interval in hours between scheduled database optimizations.
     */
    public static final Property SCH_OPTIMIZE_INTERVAL =
        new Property("scheduled_optimize_interval", "Server", "admin",
                     "Interval in hours between scheduled database optimizations",
                     24);
    /**
     * Time of day when scheduled database optimization starts.
     * Only minutes are used if interval is less than 24 hours.
     */
    public static final Property SCH_OPTIMIZE_START_TIME =
        new Property("scheduled_optimize_start", "Server", "admin",
                     "Time of day when scheduled optimization starts",
                     "02:00");
    /**
     * Max time spent for database optimization, in minutes.
     */
    public static final Property SCH_OPTIMIZE_MAX_TIME =
        new Property("scheduled_optimize_max_time", "Server", "admin",
                     "Max time spent for database optimization, in minutes",
                     30);
    
    
    // -----------------------------------------------------------------------
    
    private ServletContext context;
    
    public boolean debug;

    // root directory of a server
    private File serverRootDir;

    // Configuration properties:
    protected ConfigTable config;
//    // Qizx configuration loaded from specific .properties file:
//    private Properties configuration;
    
    private MultipartConfig multipartConfig;
    private long multipartMaxSize = -1;

    // abs path of the Qizx Library group:
    private File libGroupDir;
    // Qizx engine:
    private volatile LibraryManager libManager;
    
    // list of XML Library names (to resolve void name)
    private volatile String[] libNames;
    private HashMap<String,AccessControl> acMap;
    
    // Long actions (backup etc) in progress:
    protected ArrayList<LongAction> actions = new ArrayList<LongAction>();
    protected ScheduledExecutorService actionService;
    
    private String adminRoleName;
    private String[] adminUsers;

    private File servicesRoot;
    private String servicesDefaultLibrary;

    private SequenceCache sequenceCache;
    private SessionPool sessionPool;
    private QueryCache queryCache;

    private CatalogManager catManager;

    private QizxRepository expathRepo;

    private String[] allowedClasses;    // Java binding
    public int evalTimeout;

    private Statistics statsTable;
    protected HashMap<String, Statistics.Activity> reqStats;



    public QizxDriver(ServletContext webApp, File serverRootPath)
    {
        this.context = webApp;
        this.serverRootDir = serverRootPath;
        multipartConfig = new MultipartConfig(-1, multipartMaxSize, 200000, null);

        config =
            new ConfigTable("the Qizx Server", new Property[] {
                LIBRARY_GROUP,
                SERVER_NAME, 
                MODULES_DIR, 
                EXPATH_REPOSITORY,
                SERVICES_DIR, SERVICES_LIBRARY, 
                SEQUENCE_CACHE_SIZE, SEQUENCE_CACHE_POLICY, 
                STORED_QUERIES_CACHE_SIZE, SESSION_CACHE_SIZE,
                ACCESS_CONTROL, ADMIN_USER, ADMIN_ROLE,
                CATALOGS, CATALOGS_PREFER, CATALOGS_VERBOSITY, 
                EVAL_TIME_OUT,
                POST_LIMIT,
                BACKUP_DIR, BACKUP_DIR_COUNT, BACKUP_INTERVAL, BACKUP_START_TIME,
                IBACKUP_DIR, IBACKUP_INTERVAL, IBACKUP_START_TIME,
                SCH_OPTIMIZE_INTERVAL, SCH_OPTIMIZE_START_TIME, SCH_OPTIMIZE_MAX_TIME
            });
        
        statsTable = new Statistics();
        reqStats = new HashMap<String, Statistics.Activity>(); // requests
    }

    /**
     * Ensures that a LibraryAccess is attached to the Web App.
     */
    public static QizxDriver initialize(ServletBase servlet)
    {
        ServletContext webApp = servlet.getServletContext();
        
        synchronized (webApp)
        {
            QizxDriver driver = (QizxDriver) webApp.getAttribute(WAPP_KEY);
            if(driver != null)
                return driver;
            
            ServletConfig config = servlet.getServletConfig();

            // Root:
            String configLoc = config.getInitParameter(QIZX_ROOT_PARAMETER);
            if(configLoc == null || configLoc.length() == 0) {
                webApp.log("FATAL: location of server root not defined, " +
                           "servlet init parameter " + QIZX_ROOT_PARAMETER);
                return null;
            }

            File rootDir = new File(configLoc);
            if (!rootDir.isAbsolute()) {
                webApp.log("WARNING: it is strongly recommended to use an absolute path " +
                		" (outside the webapp) for the server root: "
                           + rootDir);
                try {
                    // resolve relatively to webapp:
                    String realPath = servlet.getServletContext().getRealPath(configLoc);
                    File relRoot = realPath == null? null : new File(realPath);
                    if (relRoot == null || !relRoot.exists()) {
                        webApp.log("FATAL: relative server root cannot be resolved: "
                                   + configLoc);
                        return null;
                    }
                    rootDir = relRoot;
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

            try {
                if(rootDir == null || !rootDir.isDirectory()) {
                    webApp.log("FATAL: server root is not a directory: " + rootDir);
                    return null;
                }
                if(!rootDir.canRead() || !rootDir.canWrite()) {
                    webApp.log("FATAL: insufficient access rights on server root directory: " + rootDir);
                    return null;
                }
            }
            catch(java.security.AccessControlException e) {
                webApp.log("FATAL: " + e + " while initializing Qizx configuration");
                webApp.log("   *** This can be due to the security policy of the servlet container.");
                return null;
            }

            driver = new QizxDriver(webApp, rootDir);
            driver.basicConfiguration();
            
            servlet.setMultipartConfig(driver.multipartConfig);
            
            // start service:
            try {
                driver.start();
            }
            catch (QizxException e) {
                webApp.log("ERROR: XML Library init error", e);
                return null;
            }
        
            webApp.setAttribute(WAPP_KEY, driver);
            return driver;
        }
    }

    // On servlet destroy:
    public static void terminate(ServletContext webApp)
    {
        synchronized (webApp)
        {
            QizxDriver driver = (QizxDriver) webApp.getAttribute(WAPP_KEY);
            if(driver == null)
                return;
            webApp.setAttribute(WAPP_KEY, null);
            try {
                driver.stop();
            }
            catch (DataModelException e) {
                webApp.log("ERROR closing Qizx driver " + e);
            }
        }
    }
    
    // restart after config reload
    public void reload(ServletBase servlet)
        throws QizxException
    {
        //terminate(context);
        stop();
        
        basicConfiguration();
        
        try {
            start();
        }
        catch (QizxException e) {
            context.log("ERROR: XML Library init error", e);
            return;
        }
    
        context.setAttribute(WAPP_KEY, this);
    }
    
    // start Qizx engine:
    public synchronized void start()
        throws QizxException
    {
        if(libManager != null)
            return;
        
        context.log("opening XML Libraries");
        try {
            libManager = Configuration.openLibraryGroup(libGroupDir);
        }
        catch (DataModelException e) {
            throw e;
        }
        catch (Exception e1) {
            context.log("ERROR opening XML Libraries: " + e1.getMessage(), e1);
        }
        
        context.log("configuring");
        // Qizx engine configuration
        try {
            String acClass = config.stringProp(ACCESS_CONTROL);
            if(acClass != null && acClass.length() > 0) {
                context.log(" Access Control: " + acClass);
                acMap = new HashMap<String, AccessControl>();
                String[] lnames = libManager.listLibraries();
                for(String name : lnames) {
                    AccessControl acctrl = (AccessControl)
                                 instantiateClass(acClass, AccessControl.class);
                    acMap.put(name, acctrl);
                    if(acctrl instanceof ACLAccessControl) {
                        // loads ACL from library itself:
                        ((ACLAccessControl) acctrl).connectTo(libManager, name);
                    }
                }
            }
            else context.log(" no Access Control");
        }
        catch (Exception e) {
            context.log("ERROR in AccessControl instantiation: " + e, e);
        }
        
        
        // XML catalogs
        initCatalogs();
        
        DocumentPool docPool = libManager.getTransientDocumentCache();
        docPool.setLocalCatalogManager(catManager);
        
        int sessionCacheSize = config.intProp(SESSION_CACHE_SIZE);
        if (sessionCacheSize > 1) {
            sessionPool = new SessionPool(sessionCacheSize);
            sessionPool.setStats(statsTable.forCache("server|session_cache", "Cache",
                                                     "Session pool"));
            context.log(" Session Cache size=" + sessionCacheSize);
        }
        else {
            sessionPool = null; // in case it existed
            context.log(" no Session Cache");
        }
        
        int queryCacheSize = config.intProp(STORED_QUERIES_CACHE_SIZE);
        if (queryCacheSize > 1) {
            queryCache = new QueryCache(this, queryCacheSize);
            queryCache.setStats(statsTable.forCache("server|query_cache", "Cache",
                                                    "Stored Queries cache"));
            context.log(" Query Cache size=" + queryCacheSize);
        }
        else {
            sequenceCache = null;
            context.log(" no Query Cache");
        }

        int seqCacheSize = config.intProp(SEQUENCE_CACHE_SIZE);
        if (seqCacheSize > 1) {
            sequenceCache = new SequenceCache(seqCacheSize, 0); // TODO memory size
            sequenceCache.setStats(statsTable.forCache("server|seq_cache", "Cache",
                                                       "Result Sequence cache"));
            
            context.log(" Sequence Cache size=" + seqCacheSize);
            String policy = config.stringProp(SEQUENCE_CACHE_POLICY);
            sequenceCache.setSmart(!"dumb".equalsIgnoreCase(policy));
            libManager.addPostCommitTrigger(null,
                                            new SeqCacheTrigger(sequenceCache));
        }
        else {
            sequenceCache = null;
            context.log(" no Sequence Cache");
        }
        
        
        // modules
        File modules = getFileProperty(MODULES_DIR);
        ModuleResolver resolver = null;
        if(modules != null) {
            if(!modules.isDirectory()) {
                context.log("ERROR: module_dir is not a directory: " + modules);
            }
            else {
                resolver = new DefaultModuleResolver(FileUtil.fileToURL(modules));
                libManager.setModuleResolver(resolver);
            }
        }
        // EXPath Repository:
        File xrepo = getFileProperty(EXPATH_REPOSITORY);
        if (xrepo != null) {
            try {
                context.log(" EXPath Repository at " + xrepo);
                FileSystemStorage storage = new FileSystemStorage(xrepo);
                expathRepo = new QizxRepository(storage);
                QizxRepoResolver qresolver = new QizxRepoResolver(expathRepo);
                // use directory 'modules' as fallback:
                qresolver.setFallback(resolver);
                libManager.setModuleResolver(qresolver);
            }
            catch (PackageException e) {
                context.log("ERROR: initializing EXPath Repository: " + e);
            }
        }
        
        servicesRoot = getFileProperty(SERVICES_DIR);
        servicesDefaultLibrary = config.stringProp(SERVICES_LIBRARY);
        
        evalTimeout = config.intProp(EVAL_TIME_OUT);

        // scheduled backups or optims?
        if (config.intProp(BACKUP_INTERVAL) > 0
            || config.intProp(IBACKUP_INTERVAL) > 0
            || config.intProp(SCH_OPTIMIZE_INTERVAL) > 0) {
                startActionService();
                actionService.schedule(scheduledTaskActivator, 5, TimeUnit.SECONDS);
        }
        
        context.log("Qizx server started");
        
        changedLibraryList(null);
    }
    
    public synchronized void stop()
        throws DataModelException
    {
        if(libManager == null)
            return;
        context.log("stopping Qizx engine... ");
        boolean graceful = libManager.closeAllLibraries(1000);
        changedLibraryList(null);
        libManager = null;
        if (actionService != null) {
            actionService.shutdownNow();
            actionService = null;   // otherwise rejects tasks
        }
        context.log("Qizx engine stopped " + (graceful? "gracefully" : "with rollbacks"));
    }

    public synchronized boolean isRunning()
    {
        return libManager != null;
    }

    public void changedLibraryList(String libName)
    {
        libNames = null;
        if (sessionPool != null && libName != null)
            sessionPool.eraseLibrary(libName);
    }
    
    // returns a non-null library name iff there is one library exactly.
    private String singleLibName()
    {
        if(libNames == null) {
            synchronized (libManager) {
                try {
                    libNames = libManager.listLibraries();
                }
                catch (DataModelException e) {
                    context.log("error getting library names", e);
                }
            }
        }
        return (libNames != null && libNames.length == 1)? libNames[0] : null;
    }

    /*
     * Loads the configuration from the properties file located
     * at the root of the server,
     */
    private synchronized boolean basicConfiguration()
    {
        context.log("=== QizxServer init: root = " + serverRootDir);
        // old config:
        File configLoc = new File(serverRootDir, QIZX_CONFIG);
        if (configLoc.exists()) {
            try {
                Properties props = FileUtil.loadProperties(configLoc);
                configure(props);
            }
            catch (IOException e) {
                context.log("ERROR: cannot load configuration at " + configLoc + ": " + e);
                return false;
            }
        }
        // then load config file
        configLoc = new File(serverRootDir, RW_CONF_FILE);
        if (configLoc.exists()) {
            try {
                Properties props = FileUtil.loadProperties(configLoc);
                configure(props);
            }
            catch (IOException e) {
                context.log("ERROR: cannot load configuration at " + configLoc
                            + ": " + e);
            }
        }
        
        adminRoleName = config.stringProp(ADMIN_ROLE);
        String admins = config.stringProp(ADMIN_USER);
        if(admins != null) {
            adminUsers = admins.split("[ \t;,]+");
        }
        
        long postLimit = config.longProp(POST_LIMIT);
        multipartConfig = new MultipartConfig(-1, postLimit * MB, 200000, null);
    
        // where is the library group (normally same directory as the config)
        libGroupDir = getFileProperty(LIBRARY_GROUP);
        if(libGroupDir == null) {
            context.log("WARNING: no property " + LIBRARY_GROUP + " in Qizx configuration");
            return false;     // no lib access
        }
        
        // rest of config is read in start()
        
        return true;
    }

    public boolean configure(Properties properties)
    {
        boolean changed = false;
        Enumeration props = properties.keys();
        for(; props.hasMoreElements(); ) {
            String name = (String) props.nextElement();
            String value = properties.getProperty(name);
            if(value.length() == 0)
                continue;
            Property prop = config.findProperty(name);
            if(prop == null) {
                continue;   // ignore
            }

            if (configure(prop, value))
                changed = true;
        }
        return changed;
    }

    public boolean configure(Property property, Object value)
    {
        value = property.checkValue(value);
        // special case: restore default value
        if (value == null)
            value = property.getDefaultValue();
        
        Object oldValue = config.get(property);
        if (oldValue == null && !config.containsKey(property))
            return false;   // not recognized: all known properties are in map
        config.put(property, value);
        
        // special processing for properties needing immediate effect:
        if (property == Configuration.MEMORY_LIMIT) {
        
        }
        else if (property == EVAL_TIME_OUT) {
            evalTimeout = property.intValue(value);
        }
        
        return !value.equals(oldValue);
    }
    
    private File getFileProperty(Property prop)
    {
        String path = config.stringProp(prop);
        if(path == null)
            return null;
        // resolved relatively to server root:
        File loc = new File(path);
        if (loc.isAbsolute())
            return loc;
        return new File(serverRootDir, path);
    }

    
    public synchronized void saveConfiguration()
        throws DataModelException
    {
        try {
            config.save(new File(serverRootDir, RW_CONF_FILE));
        }
        catch (IOException ex) {
            context.log("error saving configuration file " + RW_CONF_FILE
                        + ": " + ex.getMessage(), ex);
        }
    }

    public Map<Property, Object> getConfiguration()
    {
        HashMap<Property,Object> copy = new HashMap<Property,Object>();
        for (Entry<Property, Object> e : config.entrySet()) {
            Property p = e.getKey();
            if (p.getDescription() != null)
                copy.put(p, e.getValue());
        }
        return copy;
    }

    private Object instantiateClass(String className, Class<?> type)
        throws Exception
    {
        if (className == null | className.length() == 0)
            return null;
        Object obj = java.beans.Beans.instantiate(getClass().getClassLoader(),
                                                  className);
        if(type != null && !type.isAssignableFrom(obj.getClass()))
            context.log("ERROR: class " + className + " is not a " + type);
        return obj;
    }

    public String getName()
    {
        return config.stringProp(SERVER_NAME);
    }

    public LibraryManager getEngine()
    {
        return libManager;
    }

    public LibraryManager requireEngine()
        throws RequestException
    {
        if(libManager == null)
            throw new RequestException(Request.SERVER, "Qizx server is offline");
        return libManager;
    }

    /**
     * Gets a session.<p>
     * If sessions are pooled, look in the pool, otherwise simply create new session
     * @param qizxRequestBase 
     */
    public synchronized Library acquireSession (String libraryName,
                                                String userName,
                                                QizxRequestBase request)
        throws RequestException, DataModelException
    {
        requireEngine();
        if(libraryName == null || libraryName.length() == 0)
            libraryName = singleLibName();
        if(libraryName == null)
            throw new RequestException(Request.BAD_REQUEST,
                                       "unspecified XML Library name");
        Library lib = null;
        // session pool has a candidate?
        if (sessionPool != null) {
            lib = sessionPool.acquireSession(libraryName, userName);
            if (lib != null) {
                lib.refresh();   // important to be up to date
                return lib;
            }
        }
        
        // no: open a new session
        User user = null;
        AccessControl acctrl = getAccessControl(libraryName);
        //if(acctrl != null)
        user = new ServerUser(userName, request);
        
        lib = libManager.openLibrary(libraryName, acctrl, user);
        if(lib == null)
            throw new RequestException(Request.BAD_REQUEST,
                                       "no XML Library named '" + libraryName +"'");

        // init XQuery context:
        if (hasAdminRole(userName, request.getRequest())) {
            XQuerySessionManager.bind(lib, "admin", AdminFunctions.class);
            XQuerySessionManager.bind(lib, "server", ServerFunctions.class);
        }

        if (allowedClasses != null) {
            for (String cl : allowedClasses) {
                lib.enableJavaBinding(cl);
            }
        }
        return lib;
    }
    
    /**
     * Releases a session.<p>
     * If sessions are pooled, release it to the pool, otherwise simply 
     * close the session.
     */
    public synchronized void releaseSession(Library session)
    {
        if (sessionPool != null) {
            sessionPool.releaseSession(session);
        }
        else {
            // no pooling: directly close
            closeSession(session);
        }
    }

    private void closeSession(Library session)
    {
        try {
            session.close();
        }
        catch (DataModelException e) { 
            // close can throw an exception if modifications occurred:
            // wants a rollback before closing
            try {
                session.rollback();
            }
            catch (DataModelException e1) {
                context.log("closing session: " + e1.getMessage(), e1);
            }
        }
    }
    
    public ItemSequence acquireSequence(QizxRequestBase request, String libName,
                                        String query, boolean profile,
                                        int startPos, int maxTime, String userName)
        throws RequestException, QizxException
    {
        ItemSequence items = null;
        if (sequenceCache != null) {
            items = sequenceCache.acquire(libName, userName, query, profile, startPos);
            if (items != null) {
                if (items.getExpression().isClosed())
                    context.log("BUG: cache returns sequence on closed Expression");
                return items;
            }
        }
        
        XQuerySession lib = acquireSession(libName, userName, request);
        Expression expr = lib.compileExpression(query);
        
//        // controversial: enforce it?
//        if (expr.isUpdating())
//            throw new RequestException(QizxRequestBase.COMPILATION,
//                                       "updating expression not allowed in 'eval' request");
        
        if(maxTime > 0) // priority
            expr.setTimeOut(maxTime);
        else {
            if(evalTimeout > 0)
                expr.setTimeOut(evalTimeout);
        }
        
        if (hasAdminRole(userName, request.getRequest())) {
            ExpressionImpl ex = (ExpressionImpl) expr;
            ex.setProperty(AdminFunctions.CTX_PROP_LIB_MANAGER, libManager);
            ex.setProperty(DRIVER_PROP, this);
        }
        
        if (profile)
            items = expr.profile();
        else
            items = expr.evaluate();
        return items;
    }
    /////((ExpressionImpl) expr).setCompilationTrace(new PrintWriter(System.err, true));

    public void releaseSequence(ItemSequence sequence)
    {
        if (sequenceCache != null) {
            ItemSequence evicted = sequenceCache.release(sequence);
            if(evicted != null) {
                Expression expr = evicted.getExpression();
                

                // There is ONE expression per session: otherwise it would not
                // be guaranteed that a session is used by only one thread
                releaseSession(expr.getLibrary());
                expr.close(); // AFTER release session
            }
        }
        // else just GC it
    }
    
    public void invalidateAll(String libName)
    {
        if (sequenceCache != null) {
            sequenceCache.invalidateAll(libName);
        }
    }

    private void initCatalogs()
    {
        catManager = new CatalogManager();
        catManager.setIgnoreMissingProperties(true);
        catManager.setUseStaticCatalog(false);
        String catalogs = config.stringProp(CATALOGS);
        catManager.setCatalogFiles(catalogs);
        
        int catVerbosity = config.intProp(CATALOGS_VERBOSITY);
        catManager.setVerbosity(catVerbosity);
        
        String prefer = config.stringProp(CATALOGS_PREFER);
        if(prefer != null)
            catManager.setPreferPublic("public".equalsIgnoreCase(prefer));
    }

    public boolean hasAdminRole(String userName, HttpServletRequest request)
    {
        String adminRole = getAdminRoleName();
        return adminRole == null
            || request.isUserInRole(adminRole)
            || isAdminUser(userName);
    }
    
    private AccessControl getAccessControl(String libraryName)
    {
        return (acMap == null)? null : acMap.get(libraryName);
    }

    public File getServicesRoot()
    {
        return servicesRoot;
    }

    public String getServicesDefaultLibrary()
    {
        if (servicesDefaultLibrary == null)
            return singleLibName();
        return servicesDefaultLibrary;
    }

    /**
     * Cached access to a compiled XQuery expression.
     * By default the XML Library used is defined in the configuration.
     * @param location resolved actual location
     * @param storedQuery "servlet path"
     * @return a compiled expression
     */
    public Expression getStoredQuery(URL location,
                                     String storedQuery,
                                     String userName,
                                     QizxRequestBase request,
                                     SessionMaker smaker)
        throws IOException, QizxException, RequestException
    {
        /* For simple but large templates, compilation is likely to be slower
         * than execution itself. */

        String libName = smaker.libraryName(storedQuery, location);
        if (libName == null)
            throw new RequestException(Request.SERVER,
                                       "undefined XML Library name: specify Configuration property SERVICES_LIBRARY");

        Expression expr = null;
        if (queryCache != null) {
            expr = queryCache.get(location.toString(), libName, userName);
            if (expr != null)
                return expr;
        }

        // FIX: test if query is a directory: means 'list-services' & return null
        if ("file".equals(location.getProtocol())) {
            File loc = FileUtil.urlToFile(location);
            if (loc.isDirectory())
                return null;
        }
        
        long t0 = System.nanoTime();
        Library lib = acquireSession(libName, userName, request);
        smaker.prepare(lib);

        // beware: funky. setBaseURI needs a valid URI, but it's not checked
        //lib.getContext().setBaseURI(FileUtil.urlToSystemId(location));
        lib.getContext().setBaseURI(location.toString());
        System.err.println("compile in "+location+" "+storedQuery);
        String query = FileUtil.loadString(location);
        
        expr = lib.compileExpression(query);

        if (hasAdminRole(userName, request.getRequest())) {
            ExpressionImpl ex = (ExpressionImpl) expr;
            ex.setProperty(AdminFunctions.CTX_PROP_LIB_MANAGER, libManager);
            ex.setProperty(DRIVER_PROP, this);
        }
        
        if (queryCache != null) {
            queryCache.put(expr, location.toString(), libName, userName,
                           System.nanoTime() - t0);
        }
        return expr;
    }

    public void releaseStoredQuery(Expression expr)
    {
        if(queryCache != null)
            queryCache.release(expr);
        else
            releaseSession(expr.getLibrary());
    }

    public Map<String, Expression> listStoredQueries(String queryPath,
                                                     String userName,
                                                     QizxRequestBase request,
                                                     SessionMaker smaker)
        throws RequestException, DataModelException
    {
        File location = new File(servicesRoot, queryPath);
        if (!location.exists() || !location.isDirectory())
            return null;

        String libName = smaker.libraryName(queryPath, null);
        if (libName == null)
            throw new RequestException(Request.SERVER, 
                "undefined XML Library name: specify Configuration property SERVICES_LIBRARY");

        Library lib = acquireSession(libName, userName, request);
        smaker.prepare(lib);

        TreeMap<String, Expression> list = new TreeMap<String, Expression>();
        File[] scripts = location.listFiles();
        for (File f : scripts) {
            try {
                String query = FileUtil.loadString(f);
                list.put(f.getName(), lib.compileExpression(query));
            }
            catch (Exception e) {
                ; // ignored
            }
        }
        return list;
    }
    
    public boolean isAdminUser(String userName)
    {
        if(adminUsers == null)
            return adminRoleName == null;   // both null => no control
        for(String aduser : adminUsers) {
            if(aduser.equals(userName))
                return true;
        }
        return false;
    }

    public String getAdminRoleName()
    {
        return adminRoleName;
    }

    static class ServerUser extends BaseUser implements ACLAccessControl.User
    {
        private HttpServletRequest request;

        public ServerUser(String name, Request req)
        {
            super(name);
            this.request = req.request;
        }

        public boolean isInRole(String roleName)
        {
            return request.isUserInRole(roleName);
        }

        @Override
        public String toString()
        {
            return "ServerUser[name=" + name + "]";
        }
    }

    // -------------------- prepares a session for a service ----------------
    
    public interface SessionMaker
    {
        /**
         * Resolves the name of the concerned XML Library based on the
         * required service and/or location.
         * @param serviceId a path or reference to the service
         * @param location resolved location or the service, or null.
         */
        String libraryName(String serviceId, URL location);
        
        /**
         * Prepares a session before use. 
         * Typically defines features in the context, such as function namespaces.
         * @param session
         */
        void prepare(Library session);
    }
    
    // -------------------- progress on long actions ------------------------
    
    public abstract class LongAction
        implements Runnable, LibraryProgressObserver
    {
        protected Library library;
        protected String id;
        protected String description;
        protected long startTime;
        protected long endTime;
        private double fractionDone;
        private Throwable error;
        private DecimalFormat fformat = 
            new DecimalFormat("0.000", new DecimalFormatSymbols(Locale.US)); // FIX

        public LongAction(Library lib, String description)
        {
            library = lib;
            this.description = description;
            if(lib != null)
                lib.setProgressObserver(this);
            
            long now = System.currentTimeMillis();
            synchronized (actions) {
                // cleanup very old finished actions
                for(int a = actions.size(); --a >= 0; ) {
                    LongAction old = (LongAction) actions.get(a);
                    // finished more than 10 minutes ago?
                    if(old.endTime > 0 && old.endTime < now - 600000)
                        actions.remove(a);
                }
                // register action:
                startTime = now;
                id = "A" + hashCode();
                actions.add(this);
            }
        }

        public String getId()
        {
            return id;
        }

//        public void start()
//        {
//            context.log("starting long action " + id +" ("+ description +")");
//            // as long as this is used for rare operations (reindex, backup)
//            // this is OK to create a thread each time:
//            new Thread(this).start();
//        }

        public String getProgress()
        {
            if (error == null)
                return description + "\n" + fformat.format(fractionDone) + "\n";
            
            StringWriter sw = new StringWriter();
            PrintWriter out = new PrintWriter(sw);
            error.printStackTrace(out);
            return description + "\nerror " + error + "\n" + sw.toString() + "\n";
        }
        
        // method to redefine
        protected abstract void act() throws Exception;
        
        public void run()
        {
            try {
                act();
                finishedAction();
            }
            catch (Throwable e) {
                abortedAction(e);
            }
        }

        protected void finishedAction()
        {
            context.log("finishing long action " + id +" ("+ description +")");
            fractionDone = 1;
            endTime = System.currentTimeMillis();
            if(library != null)
                releaseSession(library);
        }

        protected void abortedAction(Throwable e)
        {
            context.log("error in long action " + id +" ("+ description +")", e);
            fractionDone = 1;
            error = e;
            endTime = System.currentTimeMillis();
            if(library != null)
                releaseSession(library);
        }

        // creates a root directory containing all Libs
        public void backupAllLibraries(File location, int index, boolean incremental)
            throws RequestException, DataModelException
        {
            requireEngine();
            String kind = incremental? "incremental backup" : "full backup";

            location = FileUtil.indexFile(location, index);
            if(!location.isDirectory() && !location.mkdirs())
                throw new DataModelException("cannot create backup root " + location);
            
            context.log("starting " + kind + " of all libraries");
            // snapshot of all Libs:
            String[] libNames = libManager.listLibraries();
            Library[] libs = new Library[libNames.length];
            for (int i = 0; i < libs.length; i++) {
                libs[i] = libManager.openLibrary(libNames[i]);
            }
            
            // do backup:
            for (int i = 0; i < libs.length; i++) {
                String name = libNames[i];
                File backupDir = new File(location, name);
                description = "scheduled " + kind + " of " + libNames[i]
                                                  + " to " + backupDir;
                libs[i].setProgressObserver(this);
                if (incremental)
                    libs[i].incrementalBackup(backupDir);
                else
                    libs[i].backup(backupDir);
                libs[i].close();
            }         
            context.log("finishing " + kind + " of all libraries");   
        }

        public void optimizeAllLibraries(int maxTime)
            throws RequestException, DataModelException
        {
            requireEngine();
            String[] libNames = libManager.listLibraries();
            context.log("starting optimization of all libraries");
            for (int i = 0; i < libNames.length; i++) {
                Library lib = libManager.openLibrary(libNames[i]);
                if(maxTime <= 0)
                    lib.optimize();
                else
                    lib.quickOptimize(maxTime * 60, true);
                lib.close();
            }            
            context.log("finishing optimization of all libraries");
        }
        
        public void optimizationProgress(double fraction)
        {
            fractionDone = fraction;
        }

        public void reindexingProgress(double fraction)
        {
            fractionDone = fraction;
        }

        public void backupProgress(double fraction)
        {
            fractionDone = fraction;
        }

        // not yet used:
        
        public void importProgress(double size) { }

        public void commitProgress(double fraction) { }

        @Override
        public String toString()
        {
            return "LongAction [library=" + library + ", id=" + id
                   + ", description=" + description + ", startTime="
                   + startTime + ", endTime=" + endTime + "]";
        }
    }

    public LongAction findAction(String id)
    {
        synchronized (actions) {
            for (int a = actions.size(); --a >= 0;) {
                LongAction act = (LongAction) actions.get(a);
                if (act.id.equals(id))
                    return act;
            }
        }
        return null;
    }

    public void startAction(LongAction action)
    {
        // this is a change: we used to run as many threads as actions
        startActionService();

        context.log("starting long action " + action.id +" ("+ action.description +")");
        actionService.submit(action);
    }


    private void startActionService()
    {
        if (actionService == null)
            actionService = Executors.newScheduledThreadPool(2);
    }

    private Runnable scheduledTaskActivator = new Runnable()
    {
        public void run()
        {
            try {
                ScheduleHelper sc = new ScheduleHelper();
                Date now = new Date();
                
                
                String backupRoot = config.stringProp(BACKUP_DIR);
                // checkTime returns false if interval not defined or if not for now
                if (backupRoot != null && backupRoot.length() > 0
                    && checkTime(now, sc, BACKUP_INTERVAL, BACKUP_START_TIME))
                {    
                    // roundrobin if BACKUP_DIR_COUNT > 1
                    int count =  config.intProp(BACKUP_DIR_COUNT);
                    int index = -1;

                    if (count > 1) {
                        index = (config.intProp(BACKUP_LATEST) + 1) % count;
                        config.put(BACKUP_LATEST, index);
                        try {
                            saveConfiguration();
                        }
                        catch (DataModelException e) {
                            context.log("ERROR: cannot save configuration: " + e);
                        }
                    }

                    final int dirIndex = index; // java bullshit
                    final File location = new File(backupRoot);
                    LongAction backupAction = new LongAction(null, "scheduled backup") {
                        protected void act()
                            throws RequestException, DataModelException
                        {
                            backupAllLibraries(location, dirIndex, false);
                        }
                    };
                    startAction(backupAction);
                }
                
                backupRoot = config.stringProp(IBACKUP_DIR);
                // returns -1 if not defined or if not for today
                if (backupRoot != null && backupRoot.length() > 0
                    && checkTime(now, sc, IBACKUP_INTERVAL, IBACKUP_START_TIME))
                {    
                    final File location = new File(backupRoot);
                    LongAction backupAction = new LongAction(null, "scheduled incr backup") {
                        protected void act()
                            throws RequestException, DataModelException
                        {
                            backupAllLibraries(location, -1, true);
                        }
                    };
                    startAction(backupAction);
                }

                if (checkTime(now, sc, SCH_OPTIMIZE_INTERVAL, SCH_OPTIMIZE_START_TIME))
                {
                    final int maxTime = config.intProp(SCH_OPTIMIZE_MAX_TIME);
                    LongAction optimAction = new LongAction(null, "scheduled optimize") {
                        protected void act()
                            throws RequestException, DataModelException
                        {
                            optimizeAllLibraries(maxTime);
                        }
                    };
                    startAction(optimAction);
                }
            }
            catch(Exception e) {
                e.printStackTrace();
            }
            finally {
                bounce();
            }
        }

        private boolean checkTime(Date now, ScheduleHelper sc,
                                  Property intervalProp,
                                  Property startTimeProp)
        {
            int interval = config.intProp(intervalProp);
            if(interval <= 0)
                return false;
            String moment = config.stringProp(startTimeProp);
            try {
                return sc.checkTime(now, interval, moment);
            }
            catch (ParseException e) {
                context.log("Warning: improper schedule specification, property "
                            + startTimeProp +": " + e);
                return false;
            }
        }

        void bounce()
        {
            long t0 = System.currentTimeMillis() / 1000;
            // resync in middle of a minute:
            int seconds = (int) (t0 % 60);
            actionService.schedule(scheduledTaskActivator, 90 - seconds,
                                   TimeUnit.SECONDS);
        }
    };

    public void collectStatistics(Statistic.Map stats)
    {
        statsTable.collect(stats);
    }

    public Statistics.Activity getActivityStats(String name)
    {
        Statistics.Activity ac = reqStats.get(name);
        if (ac == null) {
            ac = statsTable.forActivity("server|request|" + name, "Activity",
                                        "REST API request '" + name + "'");
            
            reqStats.put(name, ac);
        }
        return ac;
    }

    public URL resolve(String resource)
        throws MalformedURLException
    {
        return context.getResource(resource);
    }
}
