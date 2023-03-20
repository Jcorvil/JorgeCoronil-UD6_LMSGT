/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */

package com.qizx.server.api;

import com.qizx.api.*;
import com.qizx.api.util.XMLSerializer;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.xdm.IQName;

import java.io.IOException;

import javax.servlet.ServletException;

/**
 * Execute a XQuery script with side-effects.
 * These queries are not cached, in contrast with 'eval'.
 * There is 
 */
public class UpdateRequest extends QizxRequestBase
{
    private static final String ITEMS_FORMAT = "items";
    private static final String HTML_FMT = "html";
    private static final String XHTML_FMT = "xhtml";

    public String getName()
    {
        return "update";
    }

    public void handlePost()
        throws ServletException, IOException
    {
        String libName = getLibraryParam();
        //String path = getPathParam();
        String query = getParameter("query"); 
        if(query == null) { // in a part?
            query = getPartAsString("query");
            if(query == null)
                requiredParam("query");
        }
        String format = getParameter("format");
        String encoding = getParameter("encoding", "UTF-8");

        
        XMLSerializer serial = null;
        
        try {
            requireQizxDriver();
            // looks in caches for the best matching sequence
            // if not found, acquire a session and evaluate it

            XQuerySession lib = driver.acquireSession(libName, getUserName(), this);
            Expression expr = lib.compileExpression(query);
            ItemSequence items = expr.evaluate();
            
            serial = new XMLSerializer(output, encoding);
            QName RESULTS = IQName.get("items");

            response.setContentType(MIME_XML);

            int itemCnt = 0;
            for(; items.moveToNextItem(); ++itemCnt)
            {
                if (items.isNode()) {
                    items.export(serial);
                }
                else {
                    if (itemCnt > 0)
                        serial.putText(" "); // some space
                    serial.putAtomText(items.getString());
                }
            }

            serial.flush();
            driver.releaseSequence(items);
        }
        catch (CompilationException e) {
            throw new RequestException(e);
        }
        catch (EvaluationException e) {
            if(e.getErrorCode() == EvaluationException.TIME_LIMIT)
                throw new RequestException(TIMEOUT, e);
            throw new RequestException(e);
        }
        catch (QizxException e) {
            throw new RequestException(e);
        }
    }
}
