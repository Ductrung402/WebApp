package com.ezam.service;

import com.ezam.dao.*;
import com.ezam.dto.SocketDTO;
import com.ezam.model.TrangThaiPhongThi;
import com.ezam.util.*;
import com.ezam.util.runtime.RuntimeStore;
import java.util.Objects;

/** Close only this owned room. Never mass-grade or change completed scores. */
public final class RoomLifecycleService {
    public record Closed(String idPhongThi, String maCodePhongThi, TrangThaiPhongThi trangThaiPhong) { }
    private final TransactionManager tx;
    private final RuntimeStore store;
    private final JwtUtil jwt;
    public RoomLifecycleService(TransactionManager tx, RuntimeStore store, JwtUtil jwt) {
        this.tx=Objects.requireNonNull(tx);this.store=Objects.requireNonNull(store);this.jwt=Objects.requireNonNull(jwt);
    }
    public Closed close(SocketDTO.Member member, String token) {
        if (!"teacher".equals(member.userType())) throw new AuthException(403,"Only teachers can close exam rooms.");
        String teacher=jwt.teacherId(token);
        // Same ordering as grading: monitor before JDBC rows. Retry closed room keeps the same state.
        synchronized(store) {
            try { return tx.execute(c->{
                if(new TeacherDAO().findById(c,teacher).isEmpty())throw AuthException.credentials();
                var room=new RoomDAO().lockByCode(c,member.maCodePhongThi()).orElseThrow(()->new AuthException(404,"Room not found."));
                if(!room.getIdGv().equals(teacher)||!room.getIdPhongThi().equals(member.idPhongThi()))throw new AuthException(403,"Teacher does not own this room.");
                var state=room.getTrangThaiPhong();
                if(state==null)throw new AuthException(409,"Room is unavailable.");
                if(state!=TrangThaiPhongThi.DA_DONG&&!new RoomDAO().updateStateOwned(c,teacher,room.getIdPhongThi(),state,TrangThaiPhongThi.DA_DONG))
                    throw new AuthException(503,"Unable to close the room. Please retry.");
                return new Closed(room.getIdPhongThi(),room.getMaCodePhongThi(),TrangThaiPhongThi.DA_DONG);
            }); } catch(AuthException known){throw known;}
            catch(Exception unavailable){throw new AuthException(503,"Unable to close the room. Please retry.");}
        }
    }
}
