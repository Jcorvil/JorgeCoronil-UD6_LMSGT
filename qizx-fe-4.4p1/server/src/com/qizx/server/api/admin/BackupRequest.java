/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.api.admin;

import com.qizx.api.DataModelException;
import com.qizx.api.Library;
import com.qizx.server.util.QizxDriver;
import com.qizx.server.util.QizxDriver.LongAction;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;

import java.io.File;
import java.io.IOException;

/**
 * Start backup of a Library. Long operation returning a Progress Identifier.
 */
public class BackupRequest extends QizxRequestBase
{
    public String getName()
    {
        return "backup";
    }

    public void handlePost()
        throws RequestException, IOException
    {        
        handleBackup(false);
    }

    protected void handleBackup(final boolean incremental)
        throws RequestException
    {
        String libName = getLibraryParam();
        String path = getPathParam();
        final File location = new File(path);
        String kind = incremental? "incr backup" : "full backup";

        try {
            final QizxDriver driver = requireQizxDriver();
            boolean doAll = "*".equals(libName);

            LongAction action;
            if(doAll) {
                checkAdminRole(driver);
                action = driver.new LongAction(null, kind + " all") {
                    protected void act()
                        throws RequestException, DataModelException
                    {
                        backupAllLibraries(location, -1, incremental);
                    }
                };
            }
            else {
                Library lib = acquireSession(libName);
                checkAdminRole(driver);
                action = driver.new LongAction(lib, kind + " " + lib.getName()) {
                    public void act() 
                        throws DataModelException
                    {
                        if (incremental)
                            library.incrementalBackup(location);
                        else
                            library.backup(location);
                    }
                };
                // Attention must not be cleaned up, since used by long action
                libSession = null;
                // Not clear what happens 
            }
            response.setContentType(MIME_PLAIN_TEXT);
            println(action.getId());
            driver.startAction(action);
        }
        catch (DataModelException e) {
            throw new RequestException(e);
        }
        catch (IOException e) {
            throw new RequestException(SERVER, e);
        }
    }
}
