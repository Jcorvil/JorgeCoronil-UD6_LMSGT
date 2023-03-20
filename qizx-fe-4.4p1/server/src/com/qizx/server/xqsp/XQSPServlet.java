/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.xqsp;

import com.qizx.api.*;
import com.qizx.api.util.XMLSerializer;
import com.qizx.server.util.QizxDriver;
import com.qizx.server.util.QizxDriver.SessionMaker;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.server.util.ServletBase;
import com.qizx.util.NamespaceContext;
import com.qizx.util.RetryException;
import com.qizx.xquery.EvalContext;
import com.qizx.xquery.ExpressionImpl;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.Enumeration;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Implements "XQuery Server Pages", a mechanism that uses XQuery as a template
 * language for dynamic Web Applications.
 * <p>
 * Technically, this servlet simply invokes a XQuery script and serializes the
 * result to the HTTP output stream, typically in HTML or XHTML.
 * <p>
 * A cache of compiled scripts is managed by Qizx Server, which is able to
 * reload pages when modified. This helps development of applications and ensures
 * a reasonable level of performance.
 */
public class XQSPServlet extends ServletBase
{
    public static final String PARAMETER_NS = "com.qizx.server.xqs.parameter";

    private static final String XQSP_REQUEST_PROP = "xqsp-request";
    private static final String SERVER_DRIVER = "server-driver";
    
    /** DIFFERENCES with xqs:
     *  - no list of services
     *  - richer API
     *  - option for XSLT output (later)
     */
    
    
    public void init()
        throws ServletException
    {
        super.init();
        
        defaultHandler("GET", new Request());
        defaultHandler("POST", new Request());
        defaultHandler("PUT", new Request());
        
        // read configuration
        ServletConfig conf = getServletConfig();
    }

    public void destroy()
    {
        log("destroying servlet " + getClass());
        QizxDriver.terminate(getServletContext());
        log("servlet " + getClass() + " destroyed");
        super.destroy();
    }
    
    /**<module prefix='request'/>*/
    public static class Request extends QizxRequestBase
        implements SessionMaker
    {

        public Request()
        {
        }
        
        public String getName()
        {
            return "xqsp"; // whatever
        }

        protected void startErrorContent() throws IOException
        {
            // for best compatibility with broken clients (eg Flex), no HTTP error
            // but a specific content-type
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/html");
            println("<html><body><pre style='color: red;'>");
        }
        
        protected void endErrorContent() throws IOException
        {
            println("</pre></body></html>");
        }
        
        public void handleGet()
            throws ServletException, IOException
        {
            handlePost();
        }

        public void handlePost()
            throws ServletException, IOException
        {
            // relative path of the stored XQ script: 
            //   normally mapped by *.xqsp so use ServletPath
            //   
            String queryPath = request.getServletPath();
            if (request.getPathInfo() != null)
                queryPath = request.getPathInfo();            
            
            Expression expr = null;
            for (;;) {
                try {
                    expr = execPage(queryPath);
                    break;  // normal exec: no forward
                }
                catch (RetryException e) { // used forward()
                    queryPath = e.getMessage();
                    System.err.println("FORWARD "+e);
                    // retry (loop)
                }
                catch (RequestException e) {
                    throw (e);
                }
                catch (Exception e) {
                    throw new RequestException(e);
                }
                finally {
                    if (expr != null)
                        driver.releaseStoredQuery(expr); // useful if no cache
                }
            }
        }

        protected Expression execPage(String queryPath)
            throws Exception
        {
            Expression expr;
            // Finds a compiled expression representing the stored query
            // Based on session pooling and caching of expr. for each session
            expr = getScript(queryPath);
            if (expr == null) {
                throw new RequestException(BAD_REQUEST,
                                           "unknown request " + queryPath);
            }
            ((ExpressionImpl) expr).setProperty(XQSP_REQUEST_PROP, this);
            ((ExpressionImpl) expr).setProperty(QizxDriver.DRIVER_PROP, driver);
            
            // look for global variables with NS matching 'req',
            // and check if there is a matching request parameter
            XQueryContext xctx = expr.getContext();
            for (QName varName : xctx.getVariableNames()) {
                if (PARAMETER_NS.equals(varName.getNamespaceURI())) {
                    SequenceType type = xctx.getVariableType(varName);
                    //println(" param "+varName.getLocalPart()+" type=" + type);
                    String paramValue = getParameter(varName.getLocalPart());

                    // if value is null, then erase it
                    expr.bindVariable(varName, paramValue,
                                      type.getItemType());
                }
            }

            // look for options in script: 
            String mimeType = null, format = "XML";
            XMLSerializer resout = new XMLSerializer(output, "UTF-8");
            for(QName name : xctx.getOptionNames()) {
                //println("option "+name+" "+xctx.getOptionValue(name));
                if(name.getNamespaceURI() == NamespaceContext.OUTPUT_NS) {
                    String value = xctx.getOptionValue(name);
                    String sname = name.getLocalPart();
                    if("content-type".equalsIgnoreCase(sname))
                        mimeType = value;
                    else {
                        if("method".equalsIgnoreCase(sname))
                            format = value;
                        resout.setOption(sname, value);
                    }
                }
            }

            // mime-type:
            if (mimeType == null) {
                if (format == null)
                    mimeType = "text/xml";
                else if ("text".equalsIgnoreCase(format))
                    mimeType = "text/plain";
                else
                    mimeType = "text/" + format.toLowerCase();
            }
            response.setContentType(mimeType);

            // evaluate page: TODO streamed eval
            ItemSequence seq = expr.evaluate();
            
            for (; seq.moveToNextItem();) {
                Item it = seq.getCurrentItem();
                if (it.isNode())
                    resout.putNodeCopy(it.getNode(), 0);
                else
                    println(it.getString());
            }

            resout.flush();
            return expr;
        }

        /**
         * Cached access to a script by its path.
         * @param storedQuery relative path of the query
         */
        private Expression getScript(String storedQuery)
            throws Exception
        {
            QizxDriver driver = requireQizxDriver();
            // the query path is resolved relatively to the webapp:
            URL loc = driver.resolve(storedQuery);
            if (loc == null)
                throw new RequestException(NOT_FOUND, 
                                           "XQSP page not found: "+ storedQuery);
            
            return driver.getStoredQuery(loc, storedQuery,
                                         getUserName(), this, this);
        }
        
        // -------------- SessionMaker ----------------------
        
        public String libraryName(String serviceId, URL location)
        {
            return driver.getServicesDefaultLibrary();
        }

        public void prepare(Library session)
        {
            session.getContext().declarePrefix("param", PARAMETER_NS);

            XQuerySessionManager.bind(session, "request", Request.class);
            XQuerySessionManager.bind(session, "response", Response.class);
            XQuerySessionManager.bind(session, "session", Session.class);
        }
        
        // -------------- XQuery API (Java Binding) ----------------------
        
        /**<function name='get-content-length'>
         * <summary>Returns the length, in bytes, of the request body</summary>
         * <returns type='xs:integer'>The length in bytes</returns>
         *</function>
         */
        public static long getContentLength(EvalContext ctx)  
        {
            return getHttpRequest(ctx).getContentLength();
        }

        /**<function name='get-content-type'>
        * <summary>Returns the MIME type of the body of the request</summary>
        * <returns type='xs:string'>The MIME type as a string: e.g "text/html"</returns>
        *</function>
        */
        public static String getContentType(EvalContext ctx)
        {
            return getHttpRequest(ctx).getContentType();
        }

        /**<function name='get-content-encoding'>
        * <summary>Returns the name of the character encoding used in the body of this request. </summary>
        * <description>This function returns an empty sequence if the request does not specify a character encoding</description>
        * <returns type='xs:string'>The name of the character encoding</returns>
        *</function>
        */
        public static String getContentEncoding(EvalContext ctx)
        {
            return getHttpRequest(ctx).getCharacterEncoding();
        }

        /**<function name='get-method'>
        * <summary>Returns the name of the HTTP method with which this request was made, for example, GET, POST, or PUT.</summary>
        * <returns type='xs:string'>"GET", "POST", or "PUT" (other methods are not supported).</returns>
        *</function>
        */
        public static String getMethod(EvalContext ctx)
        {
            return getHttpRequest(ctx).getMethod();
        }

        /**<function name='get-protocol'>
        * <summary>Returns the name and version of the protocol the request uses in the form protocol/majorVersion.minorVersion, for example, HTTP/1.1</summary>
        * <returns type='xs:string'>The protocol</returns>
        *</function>
        */
        public static String getProtocol(EvalContext ctx)
        {
            return getHttpRequest(ctx).getProtocol();
        }

        /**<function name='get-scheme'>
        * <summary>Returns the name of the scheme used to make this request, 
        * for example, http, https, or ftp.</summary>
        * <returns type='xs:string'>The scheme</returns>
        *</function>
        */
        public static String getScheme(EvalContext ctx)
        {
            return getHttpRequest(ctx).getScheme();
        }

        /**<function name='get-context-path'>
        * <summary>Returns the portion of the request URI that indicates the context of the request. </summary>
        * <description>The context path always comes first in a request URI. 
        * The path starts with a "/" character but does not end with a "/" character.
        *  For servlets in the default (root) context, this method returns "".
        *   The container does not decode this string.</description>
        * <returns type='xs:string'>The context of the request.</returns>
        *</function>
        */
        public static String getContextPath(EvalContext ctx)
        {
            return getHttpRequest(ctx).getContextPath();
        }

        /**<function name='get-path-info'>
        * <summary>Returns any extra path information associated with the URL the client sent when it made this request.</summary>
        *  <description>The extra path information follows the servlet path but precedes the query string. </description>
        * <returns type='xs:string'>The path information.</returns>
        *</function>
        */
        public static String getPathInfo(EvalContext ctx)
        {
            return getHttpRequest(ctx).getPathInfo();
        }

        /**<function name='get-query-string'>
        * <summary>Returns the query string that is contained in the request URL after the path. 
        * This method returns empty() if the URL does not have a query string. </summary>
        * <returns type='xs:string'>The query string.</returns>
        *</function>
        */
        public static String getQueryString(EvalContext ctx)
        {
            return getHttpRequest(ctx).getQueryString();
        }

        /**<function name='get-servlet-path'>
        * <summary>Returns the part of this request's URL that 
        * calls the servlet. This includes either the servlet name or a path to 
        * the servlet, but does not include any extra path information or 
        * a query string</summary>
        * <returns type='xs:string'>The Servlet path</returns>
        *</function>
        */
        public static String getServletPath(EvalContext ctx)
        {
            return getHttpRequest(ctx).getServletPath();
        }

        /**<function name='user-name'>
         * <summary>Returns the name of the current authenticated user.</summary>
         * <returns type='xs:string'>The name of user, or empty sequence 
         * if no authentication.</returns>
         *</function>
         */
        public static String userName(EvalContext ctx)
        {
            return getRequest(ctx).getUserName();
        }

        /**<function name='user-agent'>
        * <summary>Returns the name of the user agent that intiated the request.</summary>
        * <returns type='xs:string'>The user agent </returns>
        *</function>
        */
        public static String userAgent(EvalContext ctx)
        {
            return getRequest(ctx).getUserAgent();
        }

        /**<function name='get-remote-host'>
         * <summary>Returns the fully qualified name of the client that sent the request.
         *  If the engine cannot or chooses not to resolve the hostname
         *   (to improve performance), this method returns the dotted-string 
         *   form of the IP address</summary>
         * <returns type='xs:string'>The name of the client machine that sent the request.</returns>
         *</function>
         */
        public static String getRemoteHost(EvalContext ctx)
        {
            Request req = getRequest(ctx);
            return req.getRequest().getRemoteHost();
        }

        /**<function name='get-remote-addr'>
        * <summary>Returns the Internet Protocol (IP) address of the client that sent the request.</summary>
        * <returns type='xs:string'>The IP address of the client</returns>
        *</function>
        */
        public static String getRemoteAddr(EvalContext ctx)
        {
            Request req = getRequest(ctx);
            return req.getRequest().getRemoteAddr();
        }

        /**<function name='get-remote-port'>
        * <summary>Returns the port of the client that sent the request.</summary>
        * <returns type='xs:int'>The integer port number.</returns>
        *</function>
        */
        public static int getRemotePort(EvalContext ctx)
        {
            Request req = getRequest(ctx);
            return req.getRequest().getRemotePort();
        }

        /**<function name='get-server-name'>
        * <summary>Returns the host name of the server that received the request.</summary>
        * <returns type='xs:string'>The server name</returns>
        *</function>
        */
        public static String getServerName(EvalContext ctx)
        {
            Request req = getRequest(ctx);
            return req.getRequest().getServerName();
        }

        /**<function name='get-server-port'>
        * <summary>Returns the port number on which this request was received.</summary>
        * <returns type='xs:int'>The integer port number.</returns>
        *</function>
        */
        public static int getServerPort(EvalContext ctx)
        {
            Request req = getRequest(ctx);
            return req.getRequest().getServerPort();
        }

        /**<function name='get-attribute'>
         * <summary>Returns the value of the named request attribute as an item, 
         * or empty() if no attribute of the given name exists.</summary>
         * <param name='name' type='xs:string'></param>
         * <returns type='item()'>An item or the empty sequence.</returns>
         *</function>
         */
        public static Object getAttribute(EvalContext ctx, String name)
        {
            return getHttpRequest(ctx).getAttribute(name);
        }

        /**<function name='set-attribute'>
        * <summary>Stores an attribute on this request. </summary>
        * <param name='name' type='xs:string'>attribute name</param>
        * <param name='value' type='item()'>value set</param>
        * <returns type='empty()'>none.</returns>
        *</function>
        */
        public static void setAttribute(EvalContext ctx, String name, Object value)
        {
            getHttpRequest(ctx).setAttribute(name, value);
        }

        /**<function name='get-attribute-names'>
        * <summary>Returns a sequence of the names of the attributes 
        * available to this request.</summary>
        * <returns type='xs:string*'>Name sequence.</returns>
        *</function>
        */
        public static Enumeration<String> getAttributeNames(EvalContext ctx)
        {
            return getHttpRequest(ctx).getAttributeNames();
        }

        /**<function name='get-header-names'>
         * <summary>Returns a sequence of all the header names this request contains. </summary>
         * <returns type='xs:string*'>Name sequence.</returns>
         *</function>
         */
        public static Enumeration getHeaderNames(EvalContext ctx)
        {
            return getHttpRequest(ctx).getHeaderNames();
        }
        
         /**<function name='header-names'>
         * <summary>Returns a sequence of all the header names this request contains.
         *  Alias of get-header-names()</summary>
         * <returns type='xs:string*'>Name sequence.</returns>
         *</function>
         */
        public static Enumeration headerNames(EvalContext ctx)
        {
            return getHeaderNames(ctx);
        }

        /**<function name='header'>
        * <summary>Alias of get-header()</summary>
        * <param name='name' type='xs:string'>a String specifying the header name</param>
        * <returns type='xs:string'>a String containing the value of the requested header, 
        * or empty() if the request does not have a header of that name</returns>
        *</function>
        */
        public static String header(EvalContext ctx, String name) {
            return getHttpRequest(ctx).getHeader(name);
        }

        /**<function name='get-header'>
        * <summary></summary>
        * <param name='name' type='xs:string'>a String specifying the header name</param>
        * <returns type='xs:string'>a String containing the value of the requested header, 
        * or empty() if the request does not have a header of that name</returns>
        *</function>
        */
        public static String getHeader(EvalContext ctx, String name) {
            return header(ctx, name);
        }

        /**<function name='parameter-names'>
        * <summary>Returns a sequence of all the header names this request contains.
        * Alias of get-parameter-names().</summary>
        * <returns type='xs:string*'>Name sequence.</returns>
        *</function>
        */
        public static Enumeration parameterNames(EvalContext ctx)
        {
            Request req = getRequest(ctx);
            return req.getParameterNames();
        }

        /**<function name='get-parameter-names'>
        * <summary>Returns a sequence of all the header names this request contains.</summary>
        * <returns type='xs:string*'>Name sequence.</returns>
        *</function>
        */
        public static Enumeration getParameterNames(EvalContext ctx)
        {
            return parameterNames(ctx);
        }

        /**<function name='has-parameter'>
        * <summary>Returns true if the request bears this parameter (even though
        * it has an empty value).</summary>
        * <param name='name' type='xs:string'>Name of the parameter.</param>
        * <returns type='xs:boolean'></returns>
        *</function>
        */
        public static boolean hasParameter(EvalContext ctx, String name)
        {
            Request req = getRequest(ctx);
            return req.getParameter(name) != null;
        }

        /**<function name='parameter'>
          <summary>
          Returns the string value of a simple parameter. 
          <para>Note: File parts of a multipart POST return a void value.
          </para></summary>
          <param name='name' type='xs:string'>name of the required parameter
          </param>
          <returns type='xs:string'>The string value of parameter,
           or an empty sequence if not found.</returns>
        *</function>
         */
        public static String parameter(EvalContext ctx, String name)
        {
            Request req = getRequest(ctx);
            return req.getParameter(name);
        }

        /**<function name='get-parameter'>
          <summary>
          Returns the string value of a simple parameter. 
          <para>Note: File parts of a multipart POST return a void value.
          </para></summary>
          <param name='name' type='xs:string'>name of the required parameter
          </param>
          <returns type='xs:string'>The string value of the parameter,
           or an empty sequence if not found.</returns>
         *</function>
         */
        public static String getParameter(EvalContext ctx, String name)
        {
            return parameter(ctx, name);
        }
        
        /**<function name='set-parameter'>
         * <summary></summary>
         * <param name='name' type='xs:string'></param>
         * <param name='value' type='xs:string'></param>
         * <returns type='empty()'>none.</returns>
         *</function>
         */
        public static void setParameter(EvalContext ctx, String name, String value)
        {
            Request req = getRequest(ctx);
            // TODO refactoring
        }

        /**<function name='get-cookie-names'>
        * <summary>Returns a sequence of all the cookie names this request contains.</summary>
        * <returns type='xs:string*'>The cookie name sequence.</returns>
        *</function>
        */
        public static String[] getCookieNames(EvalContext ctx)
        {
            Cookie[] cookies = getHttpRequest(ctx).getCookies();
            String[] names = new String[cookies.length];
            for (int i = 0; i < cookies.length; i++) {
                names[i] = cookies[i].getName();
            }
            return names;
        }

        /**<function name='get-cookie'>
        * <summary>Returns the value of a cookie found by its name.</summary>
        * <param name='name' type='xs:string'>Cookie name.</param>
        * <returns type='xs:string'>The string value of the cookie.</returns>
        *</function>
        */
        public static String getCookie(EvalContext ctx, String name)
        {
            Cookie[] cookies = getHttpRequest(ctx).getCookies();
            for (int i = 0; i < cookies.length; i++) {
                if (cookies[i].getName().equals(name))
                    return cookies[i].getValue();
            }
            return null;
        }

        /**<function name='redirect-to'>
         <summary>
         Performs a redirect to any URL. This method uses the redirect 
         mechanism of HTTP (code 307), therefore doing a roundtrip to the client.
         </summary>
        <param name='pageURL' type='xs:string'>URL of the page to redirect to.</param>
        <returns type='empty()'>none.</returns>
        *</function>
        */
        public static void redirectTo(EvalContext ctx, String pageURL)
            throws IOException
        {
            Request req = getRequest(ctx);
            req.getResponse().sendRedirect(pageURL);
        }

        /**<function name='forward'>
        <summary>
         Forwards the request to another XQSP script, without client roundtrip.
         </summary>
        <param name='pageURI' type='xs:string'>URI of the requested page:
        this must be a path relative to the web application</param>
        <returns type='empty()'>This functions never returns: 
        it transfers control to the new XQSP script.</returns>
        *</function>
        */
        public static void forward(EvalContext ctx, String pageURI)
            throws EvaluationException
        {
            Request req = getRequest(ctx);
            throw new RetryException(pageURI);
        }

        static Request getRequest(EvalContext ctx)
        {
            return (Request) ctx.getProperty(XQSP_REQUEST_PROP);
        }

        static HttpServletRequest getHttpRequest(EvalContext ctx)
        {
            return getRequest(ctx).getRequest();
        }
        
    }
    
    /**<module prefix='response'/>*/
    public static class Response
    {
        /**<function name='set-header'>
         * <summary>Sets a response header with the given name and value. 
         * If the header had already been set, the new value overwrites the previous one.</summary>
         * <param name='name' type='xs:string'>the name of the header</param>
         * <param name='value' type='xs:string'>the header value</param>
         * <returns type='empty()'>none.</returns>
         *</function>
         */
        public static void setHeader(EvalContext ctx, String name, String value)
        {
            HttpServletResponse resp = getResponse(ctx);
            resp.setHeader(name, value);
        }

        /**<function name='error'>
         * <summary>Sends an error response to the client using the specified status.</summary>
         * <param name='code' type='xs:int'>HTTP status</param>
         * <param name='message' type='xs:string'>descriptive message</param>
         * <returns type='empty()'>none.</returns>
         *</function>
         */
        public static void error(EvalContext ctx, int code, String message)
            throws IOException
        {
            HttpServletResponse resp = getResponse(ctx);
            resp.sendError(code, message);
        }

        /**<function name='set-content-type'>
        * <summary>Sets the content type of the response being sent to the client. 
        * The content type may include the type of character encoding used, 
        * for example, text/html; charset=ISO-8859-4.</summary>
        * <param name='type' type='xs:string'>content type</param>
        * <returns type='empty()'>none.</returns>
        *</function>
        */
        public static void setContentType(EvalContext ctx, String type)
        {
            HttpServletResponse resp = getResponse(ctx);
            resp.setContentType(type);
        }

        /**<function name='set-encoding'>
        * <summary>Sets the content encoding of the response being sent to the client. </summary>
        * <param name='encoding' type='xs:string'>Encoding name</param>
        * <returns type='empty()'>none.</returns>
        *</function>
        */
        public static void setEncoding(EvalContext ctx, String encoding)
        {
            HttpServletResponse resp = getResponse(ctx);
            resp.setCharacterEncoding(encoding);
        }
        
        /**<function name='add-cookie'>
         * <summary>Adds the specified cookie to the response.</summary>
         * <param name='name' type='xs:string'>a String specifying the name of the cookie</param>
         * <param name='value' type='xs:string'>a String specifying the value of the cookie</param>
         * <returns type='empty()'>none.</returns>
         *</function>
         */
        public static void addCookie(EvalContext ctx,
                                     String name, String value)
        {
            HttpServletResponse resp = getResponse(ctx);
            resp.addCookie(new Cookie(name, value));
        }

        /**<function name='add-cookie'>
         * <summary>Adds the specified cookie to the response.</summary>
         * <param name='name' type='xs:string'>a String specifying the name of the cookie</param>
         * <param name='value' type='xs:string'>a String specifying the value of the cookie</param>
         * <param name='max-age' type='xs:int'>maximum age of the cookie in seconds.</param>
         * <param name='path' type='xs:string'>a path for the pages for which the client 
         * should return the cookie.</param>
         * <returns type='empty()'>none.</returns>
         *</function>
         */
        public static void addCookie(EvalContext ctx,
                                     String name, String value,
                                     int maxAge, String path)
        {
            HttpServletResponse resp = getResponse(ctx);
            Cookie cookie = new Cookie(name, value);
            cookie.setMaxAge(maxAge);
            if (path != null)
                cookie.setPath(path);
            resp.addCookie(cookie);
        }

        /**<function name='add-cookie'>
         * <summary>Adds the specified cookie to the response.</summary>
         * <param name='name' type='xs:string'>a String specifying the name of the cookie</param>
         * <param name='value' type='xs:string'>a String specifying the value of the cookie</param>
         * <param name='max-age' type='xs:int'>maximum age of the cookie in seconds.</param>
         * <param name='path' type='xs:string'>a path for the pages for which the client 
         * should return the cookie.</param>
         * <param name='domain' type='xs:string'>Specifies the domain within which this cookie should be presented.</param>
         * <param name='secure' type='xs:boolean'>Indicates to the browser whether the cookie should only be sent using a secure protocol, such as HTTPS or SSL.</param>
         * <param name='comment' type='xs:string'>Specifies a comment that describes a cookie's purpose. </param>
         * <returns type='empty()'>none.</returns>
         *</function>
         */
        public static void addCookie(EvalContext ctx,
                                     String name, String value,
                                     int maxAge, String path, String domain,
                                     boolean secure, String comment)
        {
            HttpServletResponse resp = getResponse(ctx);
            Cookie cookie = new Cookie(name, value);
            cookie.setMaxAge(maxAge);
            if (comment != null)
                cookie.setComment(comment);
            cookie.setSecure(secure);
            cookie.setDomain(path);
            cookie.setPath(path);
            resp.addCookie(cookie);
        }

        static HttpServletResponse getResponse(EvalContext ctx)
        {
            Request r = Request.getRequest(ctx);
            return r.getResponse();
        }
    }
    
    /**<module prefix='session'/>*/
    public static class Session
    {
        /**<function name='create'>
         * <summary>Creates or recreates a Session.</summary>
         * <returns type='empty()'>none.</returns>
         *</function>
         */
        public static void create(EvalContext ctx)
        {
            Request r = Request.getRequest(ctx);
            r.getRequest().getSession(true);
        }

        /**<function name='exists'>
        * <summary>Returns true if the Session exists.</summary>
        * <returns type='xs:boolean'>true if the Session exists.</returns>
        *</function>
        */
        public static boolean exists(EvalContext ctx)
        {
            Request r = Request.getRequest(ctx);
            return r.getRequest().getSession(false) != null;
        }

        /**<function name='get-id'>
        * <summary>Returns a string containing the unique identifier assigned to the session.</summary>
        * <returns type='xs:string'>identifier</returns>
        *</function>
        */
        public static String getId(EvalContext ctx)
        {
            HttpSession s = getSession(ctx);
            return (s != null)? s.getId() : null;
        }
        

        /**<function name='get-last-accessed-time'>
        * <summary>Returns the last time the client sent a request associated with this session.</summary>
        * <returns type='xs:dateTime'>access time as dateTime.</returns>
        *</function>
        */
        public static Date getLastAccessedTime(EvalContext ctx)
        {
            HttpSession s = getSession(ctx);
            return (s != null)? new Date(s.getLastAccessedTime()) : null;
        }

        /**<function name='get-creation-time'>
         * <summary>Returns the time when this session was created.</summary>
         * <returns type='xs:ddateTime'>creation time as dateTime.</returns>
         *</function>
         */
        public static Date getCreationTime(EvalContext ctx)
        {
            HttpSession s = getSession(ctx);
            return (s != null)? new Date(s.getCreationTime()) : null;
        }

        /**<function name='is-new'>
        * <summary>Returns true if the client does not yet know about the session 
        * or if the client chooses not to join the session.</summary>
        * <returns type='xs:boolean'>true or false</returns>
        *</function>
        */
        public static boolean isNew(EvalContext ctx)
        {
            HttpSession s = getSession(ctx);
            return (s != null)? s.isNew() : false;
        }

        /**<function name='invalidate'>
        * <summary>Invalidates this session and unbinds any objects bound to it.</summary>
        * <returns type='empty()'>none.</returns>
        *</function>
        */
        public static void invalidate(EvalContext ctx)
        {
            HttpSession s = getSession(ctx);
            if (s != null)
                s.invalidate();
        }

        /**<function name='get-max-inactive-interval'>
        * <summary>Returns the maximum time interval, in seconds, that the server 
        * will keep this session open between client accesses.</summary>
        * <returns type='xs:int'>an integer specifying the number of seconds this session remains open between client requests</returns>
        *</function>
        */
        public static int getMaxInactiveInterval(EvalContext ctx)
        {
            HttpSession s = getSession(ctx);
            return (s != null)? s.getMaxInactiveInterval() : -1;            
        }

        /**<function name='set-max-inactive-interval'>
        * <summary>Specifies the time, in seconds, between client requests before the servlet container will invalidate this session.</summary>
        * <param name='max' type='xs:int'>an integer specifying the number of seconds this session remains open between client requests</param>
        * <returns type='empty()'>none.</returns>
        *</function>
        */
        public static void setMaxInactiveInterval(EvalContext ctx, int max)
        {
            HttpSession s = getSession(ctx);
            if (s != null)
                s.setMaxInactiveInterval(max);            
        }

        /**<function name='get-attribute'>
        * <summary>Returns the object bound with the specified name in this session.</summary>
        * <param name='name' type='xs:string'>attribute name.</param>
        * <returns type='item()'>the item bound, 
        * or the empty sequence if no object is bound under the name.</returns>
        *</function>
        */
        public static Object getAttribute(EvalContext ctx, String name)
        {
            HttpSession s = getSession(ctx);
            return s == null ? null : s.getAttribute(name);
        }

        /**<function name='set-attribute'>
        * <summary>Binds an item to this session, using the name specified.</summary>
        * <param name='name' type='xs:string'>attribute name.</param>
        * <param name='value' type='item()'>the item to be bound</param>
        * <returns type='empty()'>none.</returns>
        *</function>
        */
        public static void setAttribute(EvalContext ctx, String name,
                                        Object value)
        {
            HttpSession s = getSession(ctx);
            if (s != null)
                s.setAttribute(name, value);
        }

        /**<function name='get-attribute-names'>
        * <summary>Returns a sequence of all names of bound attributes.</summary>
        * <returns type='xs:string*'>Name sequence.</returns>
        *</function>
        */
        public static Enumeration<String> getAttributeNames(EvalContext ctx)
        {
            HttpSession s = getSession(ctx);
            return s == null ? null : s.getAttributeNames();
        }

        static HttpSession getSession(EvalContext ctx)
        {
            Request r = Request.getRequest(ctx);
            return r == null? null : r.getRequest().getSession();
        }
    }
}
