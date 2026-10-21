package com.ezam.service;

import com.ezam.dto.*;
import com.ezam.model.*;
import com.ezam.util.*;
import com.ezam.util.runtime.RuntimeStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomCloseLeaderboardTest {
    @Test void studentCannotCloseAndTeacherNeedsValidJwtBeforeTransaction(){
        var tx=mock(TransactionManager.class);var store=mock(RuntimeStore.class);var jwt=mock(JwtUtil.class);
        var service=new RoomLifecycleService(tx,store,jwt);
        assertEquals(403,assertThrows(AuthException.class,()->service.close(new SocketDTO.Member("AB12CD","student","s","r",null),null)).status());
        when(jwt.teacherId(null)).thenThrow(AuthException.credentials());
        assertEquals(401,assertThrows(AuthException.class,()->service.close(new SocketDTO.Member("AB12CD","teacher",null,"r",null),null)).status());verifyNoInteractions(tx);
    }
    @Test void snapshotKeepsZeroCompletedScoreAndHidesUnfinishedDefaultScore(){
        var room=new PhongThi();room.setIdPhongThi("r");room.setMaCodePhongThi("AB12CD");room.setTenLop("Class");room.setThoiGianLamBai(1);
        room.setTrangThaiPhong(TrangThaiPhongThi.DA_DONG);var students=new ArrayList<HocSinh>();var results=new ArrayList<KetQuaThi>();
        for(int i=0;i<2;i++){var h=new HocSinh();h.setIdHs("s"+i);h.setHoVaTen("Student "+i);h.setMaCodePhongThi("AB12CD");students.add(h);var k=new KetQuaThi();k.setIdHs(h.getIdHs());k.setDiemSo(0.0);k.setTrangThai(i==0?TrangThaiBaiLam.HOAN_THANH:TrangThaiBaiLam.DANG_THI);results.add(k);}
        var scores=RoomDTO.detail(room,students,results).ketQuaList();assertEquals(0.0,scores.get(0).diemSo());assertNull(scores.get(1).diemSo());
        var json=SocketDTO.JSON.toJson(new RoomLifecycleService.Closed("r","AB12CD",TrangThaiPhongThi.DA_DONG));assertFalse(json.contains("status"));assertFalse(json.contains("diemSo"));
    }
}
