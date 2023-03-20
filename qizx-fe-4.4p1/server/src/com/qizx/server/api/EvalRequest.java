/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */

package com.qizx.server.api;

import com.qizx.api.CompilationException;
import com.qizx.api.EvaluationException;
import com.qizx.api.ItemSequence;
import com.qizx.api.QName;
import com.qizx.api.QizxException;
import com.qizx.api.admin.Profiling;
import com.qizx.api.util.XMLSerializer;
import com.qizx.apps.restapi.RestAPI;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.xdm.IQName;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;

/**
 * Execute a XQuery script.
 */
public class EvalRequest extends QizxRequestBase
{
    private static final String ITEMS_FORMAT = "items";
    private static final String HTML_FMT = "html";
    private static final String XHTML_FMT = "xhtml";

    public String getName()
    {
        return "eval";
    }

    public void handleGet()
        throws ServletException, IOException
    {
        handlePost();
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
        int maxTime = getIntParameter("maxtime", -1);
        
        String mode = getParameter("mode");
        String counting = getParameter("counting");
        int count = getIntParameter("count", -1);
        int first = getIntParameter("first", 0);
        
        boolean wrapped = ITEMS_FORMAT.equals(format);
//        if(count < 0 && !wrapped)
//            count = 1;
        
        XMLSerializer serial = null;
        
        try {
            requireQizxDriver();
            // looks in caches for the best matching sequence
            // if not found, acquire a session and evaluate it
            ItemSequence items =
                driver.acquireSequence(this, libName, query,
                                       RestAPI.PROFILE.equalsIgnoreCase(mode),
                                       first, maxTime, getUserName());
            
            
            serial = new XMLSerializer(output, encoding);
            QName RESULTS = IQName.get("items");

            if(HTML_FMT.equalsIgnoreCase(format)) {
                serial.setOption(XMLSerializer.METHOD, "html");
                response.setContentType("text/html");
            }
            else if(XHTML_FMT.equalsIgnoreCase(format)) {
                serial.setOption(XMLSerializer.METHOD, "xhtml");
                response.setContentType("text/xhtml+xml");
            }
            else {
                response.setContentType(MIME_XML);
            }

            int itemCnt = 0;

            items.moveTo(first);

            if (wrapped) {
                serial.putDocumentStart();
                serial.putElementStart(RESULTS);
                if (counting == null || "exact".equalsIgnoreCase(counting)) {
                    serial.putAttribute(IQName.get("total-count"),
                                        Long.toString(items.countItems()), null);
                }
                else if ("estimated".equalsIgnoreCase(counting)) {
                    serial.putAttribute(IQName.get("estimated-count"),
                                        Long.toString(items.estimatedDocumentCount()), null);
                }
            }

            for(; (count < 0 || itemCnt < count) && items.moveToNextItem(); ++itemCnt)
            {
                if (wrapped) {
                    serial.putElementStart(RESTAPIServlet.NM_ITEM);
                    serial.putAttribute(RESTAPIServlet.NM_TYPE,
                                        items.getType().toString(), null);
                }
                
                if (items.isNode()) {
                    items.export(serial);
                }
                else {
                    if (itemCnt > 0 && !wrapped)
                        serial.putText(" "); // some space
                    serial.putAtomText(items.getString());
                }
                if (wrapped)
                    serial.putElementEnd(RESTAPIServlet.NM_ITEM);
            }
            if (wrapped) {
                // put Profiling annotations at the end of the sequence
                // (counts would not be correct if put at the beginning)
                List<Profiling> profs = items.getProfilingAnnotations();

                if (profs != null) {
                    serial.putElementStart(RESTAPIServlet.NM_PROFILING);
                    for(Profiling p : profs) {
                        serial.putElementStart(RESTAPIServlet.NM_PROFILING);
                        serial.putAttribute(IQName.get("type"), p.getType(), null);
                        serial.putAttribute(IQName.get("count"), 
                                            Integer.toString(p.getCount()), null);
                        serial.putAttribute(IQName.get("start"), 
                                            Integer.toString(p.startPoint()), null);
                        serial.putAttribute(IQName.get("end"),
                                            Integer.toString(p.endPoint()), null);
                        if (p.getMessage() != null)
                            serial.putText(p.getMessage());
                        serial.putElementEnd(RESTAPIServlet.NM_PROFILING);
                    }                    
                    serial.putElementEnd(RESTAPIServlet.NM_PROFILING);
                }

                serial.putElementEnd(RESULTS);
                serial.putDocumentEnd();
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
