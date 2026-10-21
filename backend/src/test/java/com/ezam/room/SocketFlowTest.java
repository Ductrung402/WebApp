package com.ezam.room;

import com.ezam.dto.SocketDTO;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.service.SocketFlowService;
import com.ezam.util.AuthException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SocketFlowTest {
    @Test void teacherNullAndAliases(){var j=SocketDTO.join(Map.of("ma_code_phong_thi","AB12CD","user_type","teacher"));assertNull(j.idHs());var json=SocketDTO.JSON.toJson(SocketDTO.wire(new SocketDTO.Joined("ok","AB12CD","teacher",null,"r")));assertTrue(json.contains("\"idHs\":null"));}
    @ParameterizedTest @ValueSource(strings={"{}","[]","null","{\"maCodePhongThi\":\"AB12CD\",\"userType\":\"admin\"}","{\"maCodePhongThi\":\"AB12CD\",\"userType\":\"student\"}","{\"maCodePhongThi\":\"AB12CD\",\"userType\":\"teacher\",\"idHs\":\"s\"}","{\"maCodePhongThi\":\"AB12CD\",\"userType\":\"teacher\",\"idGv\":\"g\"}"})
    void malformedJoin422(String value){assertEquals(422,assertThrows(AuthException.class,()->SocketDTO.join(SocketDTO.JSON.fromJson(value,Object.class))).status());}
    @Test void requestCannotSmuggleIdentity(){assertThrows(AuthException.class,()->SocketDTO.code(Map.of("maCodePhongThi","AB12CD","idHs","other")));assertThrows(AuthException.class,()->SocketDTO.reconnect(Map.of("maCodePhongThi","AB12CD","idHs","")));assertEquals("Invalid reconnect payload.",SocketDTO.error(422,SocketDTO.validationMessage("reconnect_exam")).get("detail"));}
    @Test void publicProjectionIsWhitelist(){var q=new Question("q","A text mentioning dap_an_dung is ordinary content","A","B","C","D");var tree=SocketDTO.JSON.toJsonTree(q).getAsJsonObject();assertEquals(Set.of("idCauHoi","noiDung","dapAnA","dapAnB","dapAnC","dapAnD"),tree.keySet());}
    @Test void longMinuteMultiplicationAndCeiling(){long now=1_790_000_000_000L;var r=new Run("run","r","e","AB12CD","Exam","Class",Integer.MAX_VALUE,"DANG_THI",now,now+(long)Integer.MAX_VALUE*60000,now,List.of(new Question("q","?","a","b","c","d")));assertEquals((long)Integer.MAX_VALUE*60,SocketFlowService.remaining(r,now));assertEquals(1,SocketFlowService.remaining(r,r.endsAt()-1));assertEquals(0,SocketFlowService.remaining(r,r.endsAt()+1));}
}
