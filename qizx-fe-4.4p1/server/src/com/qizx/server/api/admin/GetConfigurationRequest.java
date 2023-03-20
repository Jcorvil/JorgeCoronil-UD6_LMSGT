/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.api.admin;

import com.qizx.api.Configuration.Property;
import com.qizx.api.LibraryManager;
import com.qizx.apps.restapi.RestAPI;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.util.TableGenerator;
import com.qizx.xquery.ext.AdminFunctions;

import java.io.IOException;
import java.util.Map;

/**
 * Returns a list of Configuration properties and their values, 
 * optionally with meta-information (type, default value, description).
 * <p>Parameters:
 * <li>level: expertise level of configuration Properties. 
 * If not "expert", then return only non "expert" properties, otherwise all properties.
 * <li>format: "json", "text" are supported
 * </ul>
 */
public class GetConfigurationRequest extends QizxRequestBase
{
    static final String[] FIELDS = {
        "Name", "Category", "Level", "Type", "Value", "DefaultValue", "Description"
    };

    public String getName()
    {
        return "getconfig";
    }

    public void handleGet()
        throws RequestException, IOException
    {
        checkAdminRole(driver);
        try {
            LibraryManager engine = requireEngine();
            boolean expert =
                RestAPI.EXPERT_LEVEL.equalsIgnoreCase(getParameter("level"));
            String format = getParameter("format", "text");

            // merge server and db engine configs:
            Map<Property, Object> sconf = driver.getConfiguration();
            sconf.putAll(engine.getConfiguration());

            TableGenerator tg = prepareTableFormat(format, FIELDS);
            tg.startTable();
            AdminFunctions.generateConfig(sconf, expert, tg);
            print(tg.endTable().toString());
        }
        catch (RequestException e) {
            throw e;
        }
        catch (Exception e) {
            throw new RequestException(e);
        }
    }
}
