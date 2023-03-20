/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.api.admin;

import com.qizx.api.LibraryManager;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;

import java.io.IOException;
import java.util.Properties;

/**
 * Updates and save Configuration properties on the server and/or
 * the XML Library group.
 * <p>Parameters: each parameter name is followed by N is an ordinal number,
 * scanning starts with property0 / value0 and ends when no more properties are found.
 * <li>propertyN: name of a configuration property
 * <li>valueN: value of a configuration property
 * <p>Returns: 
 * 
 */
public class ChangeConfigurationRequest extends QizxRequestBase
{
    public String getName()
    {
        return "changeconfig";
    }

    public void handlePost()
        throws RequestException, IOException
    {
        try {
            LibraryManager engine = requireEngine();
            checkAdminRole(driver);
            
            response.setContentType(MIME_PLAIN_TEXT);
            Properties props = new Properties();
            int rank = 0;
            for( ;; ++rank) {
                String propName = getParameter("property" + rank);
                String propValue = getParameter("value" + rank);
                if (propName == null)
                    if(rank == 0) // try property1
                        continue;
                    else 
                        break;
                if (propValue == null)
                    requiredParam("value" + rank);
                props.setProperty(propName, propValue);
                log("set configuration: " + propName + "=" + propValue);
            }
            
            boolean changed = false;
            if (driver.configure(props)) {    // actually changed
                driver.saveConfiguration();
                changed = true;
            }
            // Database manager properties:
            if (engine.configure(props))  {  // actually changed
                engine.saveConfiguration();
                changed = true;
            }
            println(changed? "true" : "false");
        }
        catch (RequestException e) {
            throw e;
        }
        catch (Exception e) {
            throw new RequestException(e);
        }
    }
}
