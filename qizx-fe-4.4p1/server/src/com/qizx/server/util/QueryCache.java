/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.util;

import com.qizx.api.Expression;
import com.qizx.api.util.logging.Statistics;
import com.qizx.util.basic.Check;
import com.qizx.util.basic.FileUtil;

import java.io.File;
import java.util.ArrayList;

/**
 * A cache for "Stored Queries", i.e compiled XQuery expressions.
 * <p>
 * Checks that a query has been modified on disk and reloads if necessary.
 * A minimum time between 2 lookups on disk is used to avoid too many disk accesses
 */
public class QueryCache
{
    private QizxDriver serverDriver;
    private ArrayList<Entry> lru;
    private int minCheckTime = 1000;
    private Statistics.Cache stats;
    private int maxEntryCount;
    private boolean trace = !true;
    
    public QueryCache(QizxDriver serverDriver, int maxEntryCount) // TODO memory size
    {
        this.serverDriver = serverDriver;
        this.maxEntryCount = maxEntryCount;
        lru = new ArrayList<QueryCache.Entry>();
    }

    public Statistics.Cache getStats()
    {
        return stats;
    }

    public void setStats(Statistics.Cache stats)
    {
        this.stats = stats;
    }

    /**
     * Looks for a ready compiled expression matching queryURL, library, and user.
     * <p>
     * removed from the cache and returned.
     * @param mode
     */
    public synchronized Expression get(String queryURL, String libraryName,
                                       String user)
    {
        Check.nonNull("libraryName", libraryName);
        Check.nonNull("queryURL", queryURL);

        int h = hashKey(queryURL, libraryName, user);
        if (trace)
            System.err.println("get " + queryURL + " " + user + " " + h);

        for (int i = 0, size = lru.size(); i < size; i++) {
            Entry e = lru.get(i);
            if (e.hash == h && !e.locked
                && e.matches(queryURL, libraryName, user))
            {
                lru.remove(i);
                if (isStale(e.queryURL, e.loadTime)) {
                    serverDriver.releaseSession(e.expr.getLibrary());
                    if (trace)
                        System.err.println("stale entry "
                                           + e.expr.getIdentifier());
                    return null;
                }
                if (trace)
                    System.err.println("hit " + e.expr);
                if (stats != null)
                    stats.addAccess(true);
                lru.add(0, e);
                e.locked = true;
                return e.expr;
            }
        }
        // miss counted in put()
        if (trace)
            System.err.println("miss ");
        return null;
    }

    public synchronized void put(Expression expr, String queryURL,
                                 String libName, String userName,
                                 long loadTimeNanos)
    {
        Entry e = new Entry(expr, queryURL, libName, userName);
        e.loadTime = System.currentTimeMillis() - loadTimeNanos / 1000000; // picky!
        
        lru.add(0, e);
        if (lru.size() > maxEntryCount) {
            e = lru.remove(lru.size() - 1);
            serverDriver.releaseSession(e.expr.getLibrary());
        }
        if (stats != null) {
            stats.addMiss(-1, loadTimeNanos);
        }
    }

    public synchronized void release(Expression expr)
    {
        for(int i = 0, size = lru.size(); i < size; i++) {
            Entry e = lru.get(i);
            if (e.expr == expr) {
                e.locked = false;
                return;
            }
        }
        // dropped:
        serverDriver.releaseSession(expr.getLibrary());
    }

    private boolean isStale(String queryURI, long timeStamp)
    {
        File file = FileUtil.urlToFile(queryURI);
        long now = System.currentTimeMillis();
        
        return file != null && now  > timeStamp + minCheckTime &&
               file.lastModified() > timeStamp;
    }

    private static int hashKey(String queryURL, String library, String user)
    {
        return queryURL.hashCode() * 31 +
               library.hashCode() * 7 +
               (user == null? 0 : user.hashCode());
    }

    public static class Entry
    {
        long    loadTime;
        Expression expr;
        String  queryURL;
        String  userName;
        String  libName;
        int     hash; // speedup
        boolean locked;
        
        public Entry(Expression expression, String queryURL, String libName, String user)
        {
            this.expr = expression;
            this.queryURL = queryURL;
            this.libName = libName;
            this.userName = user;
            hash = hashKey(queryURL, libName, userName);
        }
        
        public boolean matches(String queryURL, String libraryName, String userName)
        {
            if ( !this.queryURL.equals(queryURL) || !libName.equals(libraryName))
                return false;
            if (this.userName == userName)
                return true;
            return (userName != null && userName.equals(this.userName));                
        }
    }
}
