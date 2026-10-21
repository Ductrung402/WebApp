package com.ezam.dto;

import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.util.*;
import com.google.gson.*;
import java.util.*;

/** Socket wire DTOs; public questions are a six-field positive whitelist. */
public final class SocketDTO {
    private SocketDTO() { }
    public static final Gson JSON=new GsonBuilder().serializeNulls().create();
    public record Join(String maCodePhongThi,String userType,String idHs){
        public Join{RoomDTO.code(maCodePhongThi);if(!Set.of("teacher","student").contains(Objects.toString(userType,"")))throw invalid();if("student".equals(userType))idHs=DtoValidator.nonEmpty(idHs,"idHs");else if(idHs!=null)throw invalid();}
    }
    public record Member(String maCodePhongThi,String userType,String idHs,String idPhongThi,RoomDTO.Student student){ }
    public record Joined(String status,String maCodePhongThi,String userType,String idHs,String idPhongThi){ }
    public record Sync(String status,long serverTime,long requestReceivedAt,Run exam,Receipt submission,Recovery studentState){ }
    public record Reconnected(String status,long serverTime,long requestReceivedAt,Run exam,Receipt submission,Recovery studentState,Long remainingSeconds,boolean examExpired){ }
    private static AuthException invalid(){return new AuthException(422,"Invalid room join payload.");}
    private static JsonObject object(Object value,Set<String> keys,Map<String,String> aliases){
        var all=new HashSet<>(keys);all.addAll(aliases.keySet());var tree=JSON.toJsonTree(value);
        return DtoValidator.object(DtoValidator.aliases(DtoValidator.object(tree,all),aliases),keys);
    }
    public static Join join(Object value){
        var o=object(value,Set.of("maCodePhongThi","userType","idHs"),Map.of("ma_code_phong_thi","maCodePhongThi","user_type","userType","id_hs","idHs"));
        return new Join(DtoValidator.string(o,"maCodePhongThi",false),DtoValidator.string(o,"userType",false),DtoValidator.string(o,"idHs",true));
    }
    public static String code(Object value){var o=object(value,Set.of("maCodePhongThi"),Map.of("ma_code_phong_thi","maCodePhongThi"));return RoomDTO.code(DtoValidator.string(o,"maCodePhongThi",false));}
    public record Reconnect(String maCodePhongThi,String idHs){ }
    public record Submit(String maCodePhongThi,String runId,Map<String,String> answers,String reason){ }
    public static Submit submit(Object value){
        var o=object(value,Set.of("maCodePhongThi","runId","answers","reason"),Map.of("ma_code_phong_thi","maCodePhongThi","run_id","runId"));
        String code=RoomDTO.code(DtoValidator.string(o,"maCodePhongThi",false));
        String run=DtoValidator.nonEmpty(DtoValidator.string(o,"runId",false),"runId");
        String reason=o.has("reason")?DtoValidator.string(o,"reason",false):"manual";
        if(!Set.of("manual","timeout").contains(reason))throw new AuthException(422,"Invalid submission reason.");
        var node=DtoValidator.required(o,"answers");if(!node.isJsonObject())throw new AuthException(422,"Answers must be an object.");
        var answers=new LinkedHashMap<String,String>();
        for(var key:node.getAsJsonObject().keySet()){
            String id=DtoValidator.nonEmpty(key,"idCauHoi");String choice=DtoValidator.string(node.getAsJsonObject(),key,false);
            new SubmissionDTO.Answer(id,choice);if(answers.putIfAbsent(id,choice)!=null)throw new AuthException(422,"Duplicate question ID.");
        }
        return new Submit(code,run,Map.copyOf(answers),reason);
    }
    public static Reconnect reconnect(Object value){var o=object(value,Set.of("maCodePhongThi","idHs"),Map.of("ma_code_phong_thi","maCodePhongThi","id_hs","idHs"));return new Reconnect(RoomDTO.code(DtoValidator.string(o,"maCodePhongThi",false)),DtoValidator.nonEmpty(DtoValidator.string(o,"idHs",false),"idHs"));}
    /** Gson creates only JSON-safe detached maps for the Netty/Jackson transport adapter. */
    public static Object wire(Object dto){return JSON.fromJson(JSON.toJson(dto),Object.class);}
    public static String validationMessage(String event){return switch(event){case "join_exam_room"->"Invalid room join payload.";case "start_exam"->"Invalid start payload.";case "sync_exam"->"Invalid synchronization payload.";case "reconnect_exam"->"Invalid reconnect payload.";case "submit_exam"->"Invalid submission payload.";default->"Invalid event payload.";};}
    public static Map<String,Object> error(int status,String detail){var map=new LinkedHashMap<String,Object>();map.put("status","error");map.put("statusCode",status);map.put("detail",detail);return map;}
}
