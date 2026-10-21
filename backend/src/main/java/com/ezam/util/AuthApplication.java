package com.ezam.util;

import com.ezam.dao.TeacherDAO;
import com.ezam.service.*;
import javax.servlet.*;
import javax.servlet.annotation.WebListener;

/** Application-scoped pool and optional explicitly configured real-time server. */
@WebListener
public final class AuthApplication implements ServletContextListener {
    private static final String ATTRIBUTE = AuthApplication.class.getName();
    private DBUtil pool;
    private TransactionManager transactions;
    private AuthService auth;
    private SocketServerManager socket;
    private com.ezam.util.runtime.RuntimeStore runtime;
    private SubmissionService submissions;
    @Override public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext(); context.setAttribute(ATTRIBUTE, this);
        try {
            JwtUtil jwt = JwtUtil.fromEnvironment();
            pool = DBUtil.open(DatabaseConfig.fromEnvironment()); transactions = new TransactionManager(pool::getConnection);
            auth = new AuthService(transactions, new TeacherDAO(), jwt);
        } catch (RuntimeException unavailable) {
            if (pool != null) pool.close(); pool = null;
            context.log("Authentication initialization unavailable; check server configuration.");
        }
        // Explicit opt-in avoids binding a surprise port or touching the legacy .runtime directory.
        // Local/cloud launch configuration must provide SOCKET_PORT and RUNTIME_DIRECTORY.
        if(auth!=null&&System.getenv("RUNTIME_DIRECTORY")!=null)try{
            runtime=com.ezam.util.runtime.RuntimeStore.fromEnvironment(new RuntimeDatabaseEvidence(transactions));
            var jwt=JwtUtil.fromEnvironment();
            var flow=new SocketFlowService(transactions,runtime,jwt,java.time.Clock.systemUTC());
            if(System.getenv("SOCKET_PORT")!=null){socket=new SocketServerManager(System.getenv().getOrDefault("SOCKET_HOST","0.0.0.0"),Integer.parseInt(System.getenv("SOCKET_PORT")),flow,jwt);socket.start();context.log("EZam Socket server started.");}
            submissions=new SubmissionService(flow.gradingService(),eventPacket->{if(socket!=null)socket.notifySubmitted(eventPacket);});
        }catch(RuntimeException unavailable){if(socket!=null)socket.close();socket=null;if(runtime!=null)runtime.close();runtime=null;context.log("Socket initialization unavailable; check SOCKET_PORT and RUNTIME_DIRECTORY.");}
    }
    public static AuthApplication get(ServletContext context) {
        Object value = context.getAttribute(ATTRIBUTE);
        if (!(value instanceof AuthApplication application)) throw new AuthException(503, "Authentication service unavailable.");
        return application;
    }
    public AuthService auth() {
        if (auth == null) throw new AuthException(503, "Authentication service unavailable.");
        return auth;
    }
    public TransactionManager transactions() { auth(); return transactions; }
    public SubmissionService submissions(){auth();if(submissions==null)throw new AuthException(503,"Submission service unavailable.");return submissions;}
    @Override public void contextDestroyed(ServletContextEvent event) {
        try{if(socket!=null)socket.close();}finally{socket=null;
            try{if(runtime!=null)runtime.close();}finally{runtime=null;if(pool!=null)pool.close();}}
        auth = null; transactions = null; submissions = null;
        // JDBC drivers loaded by this WAR must not retain its classloader after redeploy.
        java.sql.DriverManager.drivers().filter(driver -> driver.getClass().getClassLoader() == getClass().getClassLoader())
                .forEach(driver -> { try { java.sql.DriverManager.deregisterDriver(driver); } catch (java.sql.SQLException ignored) { } });
    }
}
