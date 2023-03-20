/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.util;

import com.qizx.api.Configuration.Property;
import com.qizx.api.*;
import com.qizx.api.util.PushNodeBuilder;
import com.qizx.api.util.logging.Statistic;
import com.qizx.api.util.logging.Statistics;
import com.qizx.apps.restapi.RestAPI;
import com.qizx.util.TableGenerator;
import com.qizx.xquery.EvalContext;
import com.qizx.xquery.LibraryPlus;
import com.qizx.xquery.ext.AdminFunctions;
import com.qizx.xquery.fn.JavaFunction.NoCaching;

import java.io.File;
import java.util.Map;
import java.util.Properties;

/**<module prefix='server'/>*/

/**
 * Server Administration XQuery functions.
 * Prefix <code>server:</code>. Available only in Qizx Server.
 */
public class ServerFunctions
{
    /**<function name='info'>
     * <summary>Returns information about the server, in XML form.</summary>
     * <para>The XML format is examplified hereafter:</para>
     * <programlisting>&lt;info&gt;
  &lt;property&gt;&lt;Name&gt;server-name&lt;/Name&gt;&lt;Value&gt;Qizx Server&lt;/Value&gt;
  &lt;/property&gt;
  &lt;property&gt;&lt;Name&gt;product-name&lt;/Name&gt;&lt;Value&gt;Qizx&lt;/Value&gt;
  &lt;/property&gt;
  &lt;property&gt;&lt;Name&gt;product-version&lt;/Name&gt;&lt;Value&gt;4.4&lt;/Value&gt;
  &lt;/property&gt;
...
&lt;/info&gt;
</programlisting>
     * <returns type='node()'>A XML document whose main element name is info.</returns>
     *</function>
     */
    @NoCaching
    public static Node info(EvalContext ctx)
    throws Exception
    {
        TableGenerator tg = new TableGenerator(RestAPI.INFO_FIELDS);
        tg.setNodeFormat();
        tg.setTableElementName("info");
        tg.setRowElementName("property");

        tg.startTable();
        AdminFunctions.putProp(tg, "server-name", server(ctx).getName());
        AdminFunctions.generateInfo(tg);
        tg.endTable();
        return tg.getNode();
    }

    /**<function name='reload'>
     * <summary>Restarts the server and reloads its configuration.</summary>
     * <returns type='empty()'>none</returns>
     *</function>
     */
    @NoCaching
    public static void reload(EvalContext ctx)
    throws Exception
    {
        server(ctx).reload(null);
    }

    /**<function name='backup'>
     * <summary>Performs a backup of the XML Library (given by its name 
     * $library-name) to a directory of the server's file-system.</summary>
     * <param name='library-name' type='xs:string'>Name of an XML Library handled by this server</param>
     * <param name='path' type='xs:string'>Backup directory in the server's file-system.</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void backup(EvalContext ctx, String library, String path)
    throws EvaluationException, DataModelException
    {
        Library lib = library(ctx, library);
        lib.backup(new File(path));
    }

    /**<function name='incremental-backup'>
     * <summary>Performs an incremental backup of the XML Library (given by its name 
     * $library-name) to a directory of the server's file-system.</summary>
     * <para>The former contents of the target directory are kept when possible 
     * (compatible databases), then the function computes the differences with 
     * the source database and updates only the documents, collections and 
     * indexes that have changed since the latest incremental or full backup.</para>
     * <caution><para>the incremental backup cannot be used to synchronize 2 databases 
     * created separately. The target database must have been created by 
     * backup (full or incremental) of the source database, otherwise the 
     * backed up database could become inconsistent.</para></caution>
     * <param name='library-name' type='xs:string'>Name of an XML Library handled by the server</param>
     * <param name='path' type='xs:string'>Backup directory in the server's file-system.</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void incrementalBackup(EvalContext ctx, String library,String path)
    throws EvaluationException, DataModelException
    {
        Library lib = library(ctx, library);
        lib.incrementalBackup(new File(path));
    }

    /**<function name='optimize'>
     * <summary>Performs an optimization of the XML Library (given by its name $library-name).</summary>
     * <param name='library-name' type='xs:string'>Name of an XML Library handled by the server</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void optimize(EvalContext ctx, String library)
    throws EvaluationException, DataModelException
    {
        Library lib = library(ctx, library);
        lib.optimize();
    }

    /**<function name='quick-optimize'>
     * <summary>Performs an optimization of the XML Library (given by its name $library-name) in limited time.</summary>
     * <param name='library-name' type='xs:string'>Name of an XML Library handled by the server</param>
     * <param name='max-time' type='xs:int'>Maximum time in seconds spent for the optimize operation (per XML Library).</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void quickOptimize(EvalContext ctx, String library, int maxTime)
    throws EvaluationException, DataModelException
    {
        Library lib = library(ctx, library);
        lib.quickOptimize(60 * maxTime, true);
    }

    /**<function name='reindex'>
     * <summary>Performs a complete reindexing of the XML Library (given by its name $library-name).</summary>
     * <param name='library-name' type='xs:string'>Name of an XML Library handled by the server</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void reindex(EvalContext ctx, String library)
    throws EvaluationException, DataModelException
    {
        Library lib = library(ctx, library);
        lib.reIndex();
    }

    /**<function name='get-indexing'>
     * <summary>Returns the indexing specifications of the specified XML Library.</summary>
     * <param name='library-name' type='xs:string'>Name of an XML Library handled by the server</param>
     * <returns type='node()'>indexing specifications in XML form</returns>
     *</function>
     */
    @NoCaching
    public static Node getIndexing(EvalContext ctx, String library)
    throws Exception
    {
        Library lib = library(ctx, library);

        Indexing specs = lib.getIndexing();
        PushNodeBuilder builder = new PushNodeBuilder();
        specs.export(builder);
        return builder.reap();
    }

    /**<function name='set-indexing'>
     * <summary>Define the indexing specifications of the specified XML Library.</summary>
     * <param name='library-name' type='xs:string'>Name of an XML Library handled by the server</param>
     * <param name='indexing-spec' type='node()'>The indexing specification in XML form.</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void setIndexing(EvalContext ctx, String library, Node indexingSpec)
    throws Exception
    {
        Library lib = library(ctx, library);

        Indexing xing = new Indexing();
        xing.parse(indexingSpec);
        lib.setIndexing(xing);
    }

    /**<function name='get-configuration'>
     * <summary>Returns the configuration of the server in XML form.</summary>
     * <para>The XML format is examplified hereafter:</para>
     * <programlisting
>&lt;configuration&gt;
  &lt;property&gt;
    &lt;Name&gt;POST_limit&lt;/Name&gt;
    &lt;Category&gt;Server&lt;/Category&gt;
    &lt;Level&gt;admin&lt;/Level&gt;
    &lt;Type&gt;Integer&lt;/Type&gt;
    &lt;Value&gt;-1&lt;/Value&gt;
    &lt;DefaultValue&gt;-1&lt;/DefaultValue&gt;
    &lt;Description&gt;Maximum size in Mb of a POST request.&lt;/Description&gt;
  &lt;/property&gt;
  &lt;property&gt;
    &lt;Name&gt;logging_level&lt;/Name&gt;
    &lt;Category&gt;Database&lt;/Category&gt;
    &lt;Level&gt;admin&lt;/Level&gt;
    &lt;Type&gt;String&lt;/Type&gt;
    &lt;Value&gt;INFO&lt;/Value&gt;
    &lt;DefaultValue&gt;INFO&lt;/DefaultValue&gt;
    &lt;Description&gt;Logging level applied to all XML Libraries&lt;/Description&gt;
  &lt;/property&gt;
...
&lt;/configuration&gt;                                                                                     
</programlisting>
     * <param name='expert' type='xs:boolean'>If true, return value include properties of level "expert" in addition to standard "admin" properties.</param>
     * <returns type='node()'><para>A XML document whose main element name is <sgmltag class="element">configuration</sgmltag>.</para>
     * </returns>
     *</function>
     */
    @NoCaching
    public static Node getConfiguration(EvalContext ctx, boolean expert)
    throws Exception
    {
        LibraryManager libMan = reqLibManager(ctx);
        Map<Property, Object> conf = libMan.getConfiguration();
        conf.putAll(server(ctx).getConfiguration());

        TableGenerator tg = new TableGenerator(RestAPI.CONFIGURATION_FIELDS);
        tg.setNodeFormat();
        tg.setTableElementName("configuration");
        tg.setRowElementName("property");

        tg.startTable();
        AdminFunctions.generateConfig(conf, expert, tg);
        tg.endTable();
        return tg.getNode();
    }

    /**<function name='change-configuration' params='$name1 as xs:string, $value1 as item()[, $name2, $value2...]'>
     * <summary>Modifies the configuration of the server: can specify one or several properties.</summary>
     * <param name='nameN' type='xs:string'>Name of a defined property of the configuration.</param>
     * <param name='valueN' type='xs:object*'>Value of the property</param>
     * <returns type='xs:boolean'>true if the configuration has actually been modified.</returns>
     *</function>
     */
    @NoCaching
    public static boolean changeConfiguration(EvalContext ctx, Object[] config)
    throws Exception
    {
        Properties props = new Properties();
        if(config.length % 2 != 0)
            throw new EvaluationException("function requires an even number of arguments");
        for(int a = 0; a < config.length; a += 2) {
            Object name = config[a];
            if(!(name instanceof String))
                throw new EvaluationException("argument " + (a+1) + " must be a property name");
            props.setProperty(name.toString(), config[a + 1].toString());
        }

        boolean changed = false;
        QizxDriver driver = server(ctx);
        LibraryManager engine = reqLibManager(ctx);

        if (driver.configure(props)) {    // actually changed
            driver.saveConfiguration();
            changed = true;
        }
        else if (engine.configure(props))  {  // actually changed
            engine.saveConfiguration();
            changed = true;
        }
        return changed;
    }

    /**<function name='list-tasks'>
     * <summary>Returns a list of maintenance tasks executed on the server,
     *  past or active, in XML form.</summary>
     * <para>The XML format is examplified hereafter:</para>
     * <programlisting>&lt;tasks&gt;
  &lt;task&gt;
    &lt;Type&gt;backup&lt;/Type&gt;
    &lt;Database&gt;mydb (session 123)&lt;/Database&gt;
    &lt;StartTime&gt;2011-10-07 21:51:36.600&lt;/StartTime&gt;
    &lt;FinishTime&gt;2011-10-07 21:51:36.879&lt;/FinishTime&gt;
    &lt;Duration&gt;0.27&lt;/Duration&gt;
    &lt;Progress&gt;100.0%&lt;/Progress&gt;
  &lt;/task&gt;
 ...
&lt;/tasks&gt;</programlisting>
     * <param name='timeline' type='xs:int'>A duration in hours: if 0, return currently active tasks; 
     * if > 0, return all tasks that started within this number of hours before now.</param>
     * <returns type='node()'>A XML document whose main element name is tasks.</returns>
     *</function>
     */
    @NoCaching
    public static Node listTasks(EvalContext ctx, int timeline)
    throws Exception
    {
        return AdminFunctions.listTasks(ctx, timeline);
    }

    /**<function name='get-stats'>
     * <summary>Returns statistics of the server as XML</summary>
     * <para>The XML format of statistics is examplified hereafter: a list
     * of &lt;stat&gt; elements enclosed inside a &lt;statistics&gt; wrapper.</para>
<programlisting>&lt;statistics&gt;
  &lt;stat&gt;
    &lt;Id&gt;collections&lt;/Id&gt;
    &lt;Type&gt;count&lt;/Type&gt;
    &lt;Value&gt;20&lt;/Value&gt;
    &lt;Family&gt;Data&lt;/Family&gt;
    &lt;Description&gt;total number of Collections&lt;/Description&gt;
  &lt;/stat&gt;
 ...
  &lt;stat&gt;
    &lt;Id&gt;index|compaction&lt;/Id&gt;
    &lt;Type&gt;time&lt;/Type&gt;
    &lt;Value&gt;1345 ms&lt;/Value&gt;
    &lt;Family&gt;Activity&lt;/Family&gt;
    &lt;Description&gt;Index optimizations&lt;/Description&gt;
  &lt;/stat&gt;
 ...
&lt;/statistics&gt;</programlisting>
     * <param name='expert' type='xs:boolean'>if true return a fully detailed set of statistics,
     *  if false return an aggregated subset relevant for an administrator.</param>
     * <returns type='node()'>statistics as an XML fragment</returns>
     *</function>
     */
    @NoCaching
    public static Node getStats(EvalContext ctx, boolean expert)
        throws Exception
    {
        LibraryManager libMan = reqLibManager(ctx);

        Statistic.Map stats = new Statistic.Map();
        if (!expert)
            stats.setMapping(Statistics.ADMIN_MAPPING);
        libMan.collectStatistics(stats);
        server(ctx).collectStatistics(stats);

        TableGenerator tg = new TableGenerator(RestAPI.STATS_FIELDS);
        tg.setNodeFormat();
        tg.setTableElementName("statistics");
        tg.setRowElementName("stat");

        tg.startTable();
        AdminFunctions.generateStats(stats, tg);
        tg.endTable();
        return tg.getNode();
    }

    /**<function name='list-queries'>
     * <summary>Returns a list in XML form of queries currently executing on the server.</summary>
     * <para>The XML format is examplified hereafter:</para>
     * <programlisting>&lt;queries&gt;
  &lt;query&gt;
    &lt;Id&gt;s2-e1&lt;/Id&gt;
    &lt;User&gt;user&lt;/User&gt;
    &lt;Elapsed&gt;9.53&lt;/Elapsed&gt;
    &lt;Source&gt;count(//Product[@id &gt; 40000000])&lt;/Source&gt;
  &lt;/query&gt;
  &lt;query&gt;
    &lt;Id&gt;s3-e14&lt;/Id&gt;
    &lt;User&gt;admin&lt;/User&gt;
    &lt;Elapsed&gt;0.0&lt;/Elapsed&gt;
    &lt;Source&gt;server:list-queries()&lt;/Source&gt;
  &lt;/query&gt;
&lt;/queries&gt;</programlisting>
     * <returns type='node()'>A XML document whose top element name is queries.</returns>
     *</function>
     */
    @NoCaching
    public static Node listQueries(EvalContext ctx)
        throws Exception
    {
        return AdminFunctions.listQueries(ctx);
    }

    /**<function name='cancel-query'>
     * <summary>Cancels a running XQuery specified by its identifier.</summary>
     * <param name='expr-id' type='xs:string'><para>Identifier as returned by 
     * function <literal>server:list-queries()</literal></para></param>
     * <returns type='xs:string'>a status equal to "OK", "idle" or "unknown"
     * <para>"idle" means that the query is not currently being executed.</para></returns>
     *</function>
     */
    @NoCaching
    public static String cancelQuery(EvalContext ctx, String exprId)
    throws Exception
    {
        return AdminFunctions.cancelQuery(ctx, exprId);
    }

    /**<function name='create-library'>
     * <summary>Creates a new XML Library.</summary>
     * <param name='library-name' type='xs:string'>Name of the library to create</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void createLibrary(EvalContext ctx, String libraryName)
    throws Exception
    {
        LibraryManager libMan = reqLibManager(ctx);
        libMan.createLibrary(libraryName, null);
    }

    /**<function name='delete-library'>
     * <summary>Deletes an XML Library.</summary>
     * <param name='library-name' type='xs:string'>Name of the library to delete</param>
     * <returns type='empty()'>none.</returns>
     *</function>
     */
    @NoCaching
    public static void deleteLibrary(EvalContext ctx, String libraryName)
    throws Exception
    {
        LibraryManager libMan = reqLibManager(ctx);
        libMan.deleteLibrary(libraryName);
    }

    // -------------- utilities -------------------------------------------

    private static Library library(EvalContext ctx, String library)
    throws EvaluationException, DataModelException
    {
        LibraryManager libMan = reqLibManager(ctx);
        LibraryPlus curlib = ctx.dynamicContext().getLibrary();
        Library lib = libMan.openLibrary(library, curlib.getAccessControl(),
                                         curlib.getUser());
        if (lib == null)
            throw new LibraryException("unknown XML Library " + library);
        return lib;
    }

    private static LibraryManager reqLibManager(EvalContext ctx)
    throws EvaluationException
    {
        LibraryManager libMan = libManager(ctx);
        if (libMan == null)
            throw new EvaluationException("improper call context for server: function ");
        return libMan;
    }

    private static LibraryManager libManager(EvalContext ctx)
    {
        return (LibraryManager) ctx.getProperty(AdminFunctions.CTX_PROP_LIB_MANAGER);
    }

    private static QizxDriver server(EvalContext ctx)
    throws EvaluationException
    {
        QizxDriver driver = (QizxDriver) ctx.getProperty(QizxDriver.DRIVER_PROP);
        if (driver == null)
            throw new EvaluationException("improper call context for 'server:' function: no server found");
        return driver;
    }
}
