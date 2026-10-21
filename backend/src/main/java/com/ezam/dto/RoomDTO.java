package com.ezam.dto;

import com.ezam.model.*;
import com.ezam.util.*;
import com.google.gson.*;
import java.util.*;

/** Explicit public boundaries; never serialize a room row/teacher/exam question. */
public final class RoomDTO {
    private RoomDTO() { }
    public record Create(String idDeThi, String tenLop, int thoiGianLamBai) {
        public Create { idDeThi=DtoValidator.nonEmpty(idDeThi,"idDeThi");tenLop=DtoValidator.nonEmpty(tenLop,"tenLop");if(thoiGianLamBai<1)throw AuthException.invalid("thoiGianLamBai","greater_than_equal","Input should be greater than or equal to 1"); }
    }
    public record Join(String hoVaTen, String maCodePhongThi) {
        public Join { hoVaTen=DtoValidator.nonEmpty(hoVaTen,"hoVaTen");maCodePhongThi=code(maCodePhongThi); }
    }
    public record Response(String idPhongThi,String maCodePhongThi,String tenLop,int thoiGianLamBai,TrangThaiPhongThi trangThaiPhong,String ngayTaoPhong) { }
    public record Joined(String idHs,String hoVaTen,String maCodePhongThi,String idPhongThi,String tenLop,int thoiGianLamBai) { }
    public record Student(String idHs,String hoVaTen,String maCodePhongThi) { }
    public record Score(String idHs,String hoVaTen,String maCodePhongThi,TrangThaiBaiLam trangThai,Double diemSo) { }
    public record Detail(String idPhongThi,String maCodePhongThi,String tenLop,int thoiGianLamBai,TrangThaiPhongThi trangThaiPhong,String ngayTaoPhong,List<Student> hocSinhList,List<Score> ketQuaList) { }
    public static String code(String value) {
        if(value==null||!value.matches("[A-Z0-9]{6}"))throw AuthException.invalid("maCodePhongThi","string_pattern_mismatch","Room code must contain six uppercase letters or digits");return value;
    }
    private static JsonObject input(String json,Map<String,String> aliases,Set<String> keys){
        // Validate shape first, then normalize exactly one name per field.
        var allowed=new HashSet<>(keys);allowed.addAll(aliases.keySet());
        return DtoValidator.object(DtoValidator.aliases(DtoValidator.object(json,allowed),aliases),keys);
    }
    public static Create create(String json){
        var o=input(json,Map.of("id_de_thi","idDeThi","ten_lop","tenLop","thoi_gian_lam_bai","thoiGianLamBai"),Set.of("idDeThi","tenLop","thoiGianLamBai"));
        return new Create(DtoValidator.string(o,"idDeThi",false),DtoValidator.string(o,"tenLop",false),DtoValidator.positiveInteger(o,"thoiGianLamBai"));
    }
    public static Join join(String json){
        var o=input(json,Map.of("ho_va_ten","hoVaTen","ma_code_phong_thi","maCodePhongThi"),Set.of("hoVaTen","maCodePhongThi"));
        return new Join(DtoValidator.string(o,"hoVaTen",false),DtoValidator.string(o,"maCodePhongThi",false));
    }
    public static Response response(PhongThi p){
        if(p.getIdPhongThi()==null||p.getTenLop()==null||p.getThoiGianLamBai()==null)throw new AuthException(503,"Invalid room data.");
        return new Response(p.getIdPhongThi(),code(p.getMaCodePhongThi()),p.getTenLop(),p.getThoiGianLamBai(),p.getTrangThaiPhong(),p.getNgayTaoPhong()==null?null:p.getNgayTaoPhong().toString());
    }
    public static Student student(HocSinh h){return new Student(Objects.requireNonNull(h.getIdHs()),Objects.requireNonNull(h.getHoVaTen()),code(h.getMaCodePhongThi()));}
    public static Detail detail(PhongThi p,List<HocSinh> students,List<KetQuaThi> results){
        var r=response(p);var byId=new HashMap<String,KetQuaThi>();
        for(var k:results)if(byId.put(k.getIdHs(),k)!=null)throw new AuthException(503,"Ambiguous student result.");
        var roster=students.stream().map(RoomDTO::student).toList();
        var scores=students.stream().map(h->{var k=byId.get(h.getIdHs());Double score=k!=null&&k.getTrangThai()==TrangThaiBaiLam.HOAN_THANH?k.getDiemSo():null;
            if(score!=null&&(!Double.isFinite(score)||score<0||score>10))throw new AuthException(503,"Invalid room result.");
            return new Score(h.getIdHs(),h.getHoVaTen(),h.getMaCodePhongThi(),k==null?null:k.getTrangThai(),score);}).toList();
        return new Detail(r.idPhongThi(),r.maCodePhongThi(),r.tenLop(),r.thoiGianLamBai(),r.trangThaiPhong(),r.ngayTaoPhong(),roster,scores);
    }
}
