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
import com.qizx.api.QizxException;
import com.qizx.server.util.QizxDriver;
import com.qizx.server.util.QizxDriver.LongAction;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;

import java.io.IOException;

/**
 * Request an optimization of the Library.
 * <p>
 * Long operation returning a Progress Identifier.
 */
public class QuickOptimizeRequest extends QizxRequestBase
{
    public String getName()
    {
        return "quickoptimize";
    }

    public void handlePost()
        throws RequestException, IOException
    {
        String libName = getLibraryParam();

        try {
            QizxDriver driver = requireQizxDriver();
            Library lib = acquireSession(libName);
            checkAdminRole(driver);
            final int timeHint = getIntParameter("timeHint", -1);
            final boolean blocking = getBooleanParameter("blocking", false);
            
            if(blocking) {
                lib.quickOptimize(timeHint, blocking);
                println("OK");
            }
            else {
                LongAction action = driver.new LongAction(lib, "qoptimize " + lib.getName()) {
                    public void act() throws DataModelException
                    {
                        library.quickOptimize(timeHint, blocking);
                    }
                };

                response.setContentType(MIME_PLAIN_TEXT);
                println(action.getId());
                driver.startAction(action);
                // Attention must not be cleaned up, since used by long action:
                libSession = null;
            }
        }
        catch (QizxException e) {
            e.printStackTrace();
            throw new RequestException(e);
        }
    }
}
