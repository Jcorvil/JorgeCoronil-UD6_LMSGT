/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.util;

import com.qizx.api.DataModelException;
import com.qizx.api.Library;
import com.qizx.api.User;
import com.qizx.api.util.logging.Statistics.Cache;

import java.util.ArrayList;
import java.util.HashMap;

public class SessionPool
{
    private ArrayList<Entry> pool;
    private int maxEntryCount;
    private Cache stats;

    public SessionPool(int maxEntryCount)
    {
        pool = new ArrayList<Entry>();
        this.maxEntryCount = maxEntryCount;
    }

    public synchronized Library acquireSession(String libraryName,
                                               String userName)
    {
        int h = hashKey(libraryName, userName);
        for(int i = 0, size = pool.size(); i < size; i++) {
            Entry e = pool.get(i);
            if (e.hash == h && e.matches(libraryName, userName)) {
                pool.remove(i);
                if (stats != null)
                    stats.addAccess(true);
                return e.session;
            }
        }
        if (stats != null)
            stats.addAccess(false);
        return null;
    }

    public synchronized void releaseSession(Library session)
    {
        pool.add(new Entry(session));
        if( pool.size() > maxEntryCount) {
            Entry e = pool.remove(0);
            close(e.session);
        }
    }
    
    public synchronized Library eraseLibrary(String libraryName)
    {
        for(int i = pool.size(); --i >= 0; ) {
            Entry e = pool.get(i);
            if (e.session.getName().equals(libraryName)) {
                pool.remove(i);
            }
        }
        return null;
    }


    private void close(Library library)
    {
        try {
            library.close();
        }
        catch (DataModelException e) {
            // close can throw an exception if modifications occurred:
            // wants a rollback before closing
            try {
                library.rollback();
            }
            catch (DataModelException e1) {
                ; // what to do
            }
        }
    }

    private static int hashKey(String libraryName, String userName)
    {
        return libraryName.hashCode() * 31 + (userName == null? 0 : userName.hashCode());
    }
    
    static class Entry
    {
        Library session;
        int     hash; // speedup
        String userName;
        
        public Entry(Library session)
        {
            this.session = session;
            User user = session.getUser();
            userName = user == null? null : user.getName();
            hash = hashKey(session.getName(), userName);
        }
        
        public boolean matches(String libraryName, String userName)
        {
            if ( !session.getName().equals(libraryName))
                return false;
            if (this.userName == userName)
                return true;
            return (userName != null && userName.equals(this.userName));                
        }
    }

    public void setStats(Cache cache)
    {
        stats = cache;
    }
}
