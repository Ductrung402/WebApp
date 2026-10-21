package com.ezam.service;

import com.ezam.dao.*;
import com.ezam.dto.RoomDTO;
import com.ezam.model.*;
import com.ezam.util.AuthException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** Admission and result are one atomic aggregate; runtime/Socket start is a separate task. */
public final class RoomService {
    private static final char[] ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private final SecureRandom random=new SecureRandom();
    private final TransactionManager tx;private final RoomDAO rooms=new RoomDAO();private final StudentDAO students=new StudentDAO();private final SubmissionDAO results=new SubmissionDAO();private final Clock clock;
    public RoomService(TransactionManager tx,Clock clock){this.tx=Objects.requireNonNull(tx);this.clock=Objects.requireNonNull(clock);}
    private String candidate(){char[] code=new char[6];for(int i=0;i<6;i++)code[i]=ALPHABET[random.nextInt(ALPHABET.length)];return new String(code);}
    private static AuthException database(SQLException error){return new AuthException(error.getSQLState()!=null&&error.getSQLState().startsWith("23")?409:503,error.getSQLState()!=null&&error.getSQLState().startsWith("23")?"Room data conflicts with existing records.":"Room database unavailable.");}
    public RoomDTO.Response create(String teacher,RoomDTO.Create payload){
        for(int attempt=0;attempt<5;attempt++)try{return tx.execute(c->{
            if(new ExamDAO().lockOwned(c,teacher,payload.idDeThi()).isEmpty())throw new AuthException(404,"Exam not found.");
            String code=null;for(int n=0;n<20;n++){String value=candidate();if(!rooms.existsCode(c,value)){code=value;break;}}
            if(code==null)throw new AuthException(503,"Unable to allocate a unique room code. Please retry.");
            var p=new PhongThi();p.setIdPhongThi(UUID.randomUUID().toString());p.setIdGv(teacher);p.setIdDeThi(payload.idDeThi());p.setMaCodePhongThi(code);p.setTenLop(payload.tenLop());p.setThoiGianLamBai(payload.thoiGianLamBai());p.setNgayTaoPhong(LocalDate.now(clock));p.setTrangThaiPhong(TrangThaiPhongThi.DANG_CHO);
            if(!rooms.insertOwned(c,teacher,p))throw new AuthException(404,"Exam not found.");return RoomDTO.response(p);
        });}catch(SQLException error){if(!SqlErrors.isRoomCodeConflict(error))throw database(error);}
        throw new AuthException(503,"Unable to allocate a unique room code. Please retry.");
    }
    public RoomDTO.Joined join(RoomDTO.Join payload){
        try{return tx.execute(c->{
            var p=rooms.lockByCode(c,payload.maCodePhongThi()).orElseThrow(()->new AuthException(404,"Room not found."));
            if(p.getTrangThaiPhong()!=TrangThaiPhongThi.DANG_CHO&&p.getTrangThaiPhong()!=TrangThaiPhongThi.DANG_THI)throw new AuthException(409,"Room is closed or unavailable.");
            var h=new HocSinh();h.setIdHs(UUID.randomUUID().toString());h.setHoVaTen(payload.hoVaTen());h.setMaCodePhongThi(p.getMaCodePhongThi());students.insert(c,h);
            var k=new KetQuaThi();k.setIdKq(UUID.randomUUID().toString());k.setIdHs(h.getIdHs());k.setIdPhongThi(p.getIdPhongThi());k.setDiemSo(0.0);k.setTrangThai(TrangThaiBaiLam.DANG_THI);
            if(!results.insert(c,k))throw new AuthException(409,"Student admission conflicts with existing records.");
            RoomDTO.response(p);RoomDTO.student(h);return new RoomDTO.Joined(h.getIdHs(),h.getHoVaTen(),h.getMaCodePhongThi(),p.getIdPhongThi(),p.getTenLop(),p.getThoiGianLamBai());
        });}catch(SQLException error){throw database(error);}
    }
    public RoomDTO.Detail get(String teacher,String id){
        try{return tx.execute(c->{var p=rooms.lockOwned(c,teacher,id).orElseThrow(()->new AuthException(404,"Room not found."));return RoomDTO.detail(p,students.listByOwnedRoom(c,teacher,id),results.listByOwnedRoom(c,teacher,id));});}
        catch(SQLException error){throw database(error);}
    }
}
