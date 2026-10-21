package com.ezam.filter;

import com.ezam.dto.RoomDTO;
import com.ezam.util.*;
import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;

/** Validation precedes JWT SQL, matching the strict request contract. */
public final class RoomValidationFilter implements Filter {
    public static final String PAYLOAD=RoomValidationFilter.class.getName()+".payload";
    @Override public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws IOException,ServletException{
        var r=(HttpServletRequest)req;String path=r.getServletPath()+java.util.Objects.toString(r.getPathInfo(),"");
        boolean join=path.equals("/api/rooms/join");
        if(!r.getMethod().equals("POST")||!(join||path.equals("/api/rooms")||path.equals("/api/rooms/"))){chain.doFilter(req,res);return;}
        try{var cached=new CachedJsonRequest(r);String body=JsonUtil.readBody(cached);cached.setAttribute(PAYLOAD,join?RoomDTO.join(body):RoomDTO.create(body));chain.doFilter(cached,res);}
        catch(AuthException error){JsonUtil.error((HttpServletResponse)res,error);}
    }
}
