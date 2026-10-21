package com.ezam.service;

import com.ezam.dto.*;
import com.ezam.dto.SubmissionDTO.*;
import com.ezam.model.TrangThaiBaiLam;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.util.*;
import com.google.gson.Gson;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubmissionContractTest {
    @ParameterizedTest @ValueSource(strings={"{}","{\"maCodePhongThi\":\"AB12CD\",\"runId\":\"r\",\"answers\":null}","{\"maCodePhongThi\":\"AB12CD\",\"runId\":\"r\",\"answers\":{\"q\":null}}","{\"maCodePhongThi\":\"AB12CD\",\"runId\":\"r\",\"answers\":{\"q\":\"X\"}}","{\"maCodePhongThi\":\"AB12CD\",\"runId\":\"r\",\"answers\":{},\"idHs\":\"spoof\"}","{\"maCodePhongThi\":\"AB12CD\",\"runId\":\"r\",\"answers\":{},\"reason\":\"other\"}"})
    void invalidSocketSubmission422(String json){assertEquals(422,assertThrows(AuthException.class,()->SocketDTO.submit(new Gson().fromJson(json,Object.class))).status());}
    @Test void validSocketManualDefaultTimeoutAndAliases(){
        var packet=SocketDTO.submit(Map.of("ma_code_phong_thi","AB12CD","run_id","run","answers",Map.of("q","A")));
        assertEquals("manual",packet.reason());assertEquals("run",packet.runId());
        assertEquals("timeout",SocketDTO.submit(Map.of("maCodePhongThi","AB12CD","runId","run","answers",Map.of(),"reason","timeout")).reason());
    }
    @Test void notificationFailureAfterCommitReturnsOriginalOutcomeAndRetryNotifiesAgain(){
        var grading=mock(GradingService.class);var request=new Request("s","room",List.of());
        var grade=new Grade("s",0,0,3,TrangThaiBaiLam.HOAN_THANH);var receipt=new Receipt("first","run","s",1,false,"manual",false);
        var outcome=new Outcome(grade,receipt,new Submitted("s",0,0,3,TrangThaiBaiLam.HOAN_THANH,"room","AB12CD","Student"));
        when(grading.grade(request,null,"manual")).thenReturn(outcome);
        var deliveries=new java.util.concurrent.atomic.AtomicInteger();var service=new SubmissionService(grading,event->{deliveries.incrementAndGet();throw new IllegalStateException("synthetic delivery failure");});
        assertSame(outcome,service.submit(request,null,"manual"));assertSame(outcome,service.submit(request,null,"manual"));assertEquals(2,deliveries.get());verify(grading,times(2)).grade(request,null,"manual");
    }
}
