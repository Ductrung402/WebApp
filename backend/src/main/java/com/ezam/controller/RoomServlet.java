package com.ezam.controller;

import com.ezam.dto.RoomDTO;
import com.ezam.filter.*;
import com.ezam.service.RoomService;
import com.ezam.util.*;
import java.io.IOException;
import java.time.Clock;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet(urlPatterns="/api/rooms/*",loadOnStartup=3)
public final class RoomServlet extends HttpServlet {
    private RoomService service;
    @Override public void init(){try{service=new RoomService(AuthApplication.get(getServletContext()).transactions(),Clock.systemDefaultZone());}catch(RuntimeException unavailable){service=null;}}
    @Override protected void service(HttpServletRequest r,HttpServletResponse s)throws IOException{
        try{if(service==null)throw new AuthException(503,"Room database unavailable.");if(!java.util.Set.of("GET","POST").contains(r.getMethod()))throw new AuthException(405,"Method Not Allowed");super.service(r,s);}
        catch(AuthException error){JsonUtil.error(s,error);}catch(RuntimeException|javax.servlet.ServletException error){JsonUtil.error(s,new AuthException(503,"Room database unavailable."));}
    }
    @Override protected void doPost(HttpServletRequest r,HttpServletResponse s)throws IOException{
        String path=r.getPathInfo();Object payload=r.getAttribute(RoomValidationFilter.PAYLOAD);
        if("/join".equals(path)){JsonUtil.send(s,201,service.join(payload instanceof RoomDTO.Join value?value:RoomDTO.join(JsonUtil.readBody(r))));return;}
        if(path!=null&&!path.equals("/"))throw new AuthException(404,"Not Found");
        JsonUtil.send(s,201,service.create(JwtAuthFilter.teacherId(r),payload instanceof RoomDTO.Create value?value:RoomDTO.create(JsonUtil.readBody(r))));
    }
    @Override protected void doGet(HttpServletRequest r,HttpServletResponse s)throws IOException{
        String path=r.getPathInfo();if(path==null||!path.matches("/[^/]+"))throw new AuthException(404,"Not Found");
        JsonUtil.send(s,200,service.get(JwtAuthFilter.teacherId(r),path.substring(1)));
    }
}
