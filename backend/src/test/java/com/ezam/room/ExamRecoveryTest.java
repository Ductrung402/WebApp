package com.ezam.room;

import com.ezam.dto.*;
import com.ezam.service.*;
import com.ezam.util.*;
import com.ezam.util.runtime.RuntimeStore;
import java.sql.SQLException;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cheap boundary/error tests; actual transitions and controlled Clock live in PostgresRecoveryIT. */
class ExamRecoveryTest {
    @Test void teacherCannotUseStudentRecoveryOrTriggerDisconnectWrite(){var tx=mock(TransactionManager.class);var store=mock(RuntimeStore.class);var flow=mock(SocketFlowService.class);var recovery=new ExamRecoveryService(flow,tx,store);var teacher=new SocketDTO.Member("AB12CD","teacher",null,"r",null);assertNull(recovery.markDisconnected(teacher,1));assertEquals(403,assertThrows(AuthException.class,()->recovery.reconnect(teacher,null,1)).status());verifyNoInteractions(tx,store,flow);}
    @Test void sqlFailureIsSafe503WithoutPrivateCause()throws Exception{var tx=new TransactionManager(()->{throw new SQLException("secret connection details");});var store=mock(RuntimeStore.class);var jwt=JwtUtil.fromEnvironment(java.util.Map.of("JWT_SECRET_KEY","synthetic-test-secret-with-at-least-32-bytes"),Clock.systemUTC());var flow=new SocketFlowService(tx,store,jwt,Clock.systemUTC());var recovery=flow.recoveryService();var student=new SocketDTO.Member("AB12CD","student","s","r",new RoomDTO.Student("s","An","AB12CD"));var error=assertThrows(AuthException.class,()->recovery.markDisconnected(student,1));assertEquals(503,error.status());assertFalse(error.detail().toString().contains("secret"));assertNull(error.getCause());verifyNoInteractions(store);}
}
